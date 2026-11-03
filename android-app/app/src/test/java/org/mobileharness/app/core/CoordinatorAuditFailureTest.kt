package org.mobileharness.app.core

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorAuditFailureTest {
    @Test fun failedBeginNeverCapturesOrCallsModelsAndCannotResumeWithoutRestart() = runTest {
        val f = Fixture(backgroundScope)
        f.audit.failWhen = { name, _ -> name == "begin" }
        f.coordinator.start(options())
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(0, f.phone.observations)
        assertEquals(0, f.gateway.guiCalls.size)
        assertEquals(0, f.gateway.plannerCalls.size)
        assertFalse(f.phone.captureAllowed)
        assertTrue(f.gateway.cancellations > 0)
        assertTrue(f.state.message.contains("日志写入失败"))
        val writes = f.audit.writeAttempts
        f.coordinator.retry()
        f.coordinator.takeover()
        f.coordinator.giveBack()
        f.coordinator.environmentUnavailable("SERVICE_DISCONNECTED")
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(writes, f.audit.writeAttempts)
        f.coordinator.stop()
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertTrue(f.state.message.contains("日志写入失败"))
        assertFalse(f.audit.events.any { it.first == "session_ended" })
        f.audit.failWhen = { _, _ -> false }
        f.coordinator.start(options())
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals(writes, f.audit.writeAttempts)
        assertEquals(0, f.phone.observations)
        assertEquals(0, f.gateway.guiCalls.size)
    }

    @Test fun failedGuiStartedWriteAbortsBeforeTheRequestAndKeepsTheChargedBudget() = runTest {
        val f = Fixture(backgroundScope)
        f.audit.failWhen = { name, data -> name == "model_call" && data["status"] == "started" }
        f.coordinator.start(options())
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(1, f.state.guiUsed)
        assertEquals(1, f.phone.observations)
        assertEquals(0, f.gateway.guiCalls.size)
        assertEquals(0, f.phone.performCalls)
        assertFalse(f.phone.captureAllowed)
        val writes = f.audit.writeAttempts
        f.coordinator.retry()
        runCurrent()
        assertEquals(1, f.phone.observations)
        assertEquals(0, f.gateway.guiCalls.size)
        assertEquals(writes, f.audit.writeAttempts)
    }

    @Test fun failedGuiTerminalWriteCannotDispatchAnAlreadyParsedAction() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options())
        runCurrent()
        f.audit.failWhen = { name, data -> name == "model_call" && data["status"] == "succeeded" }
        f.gateway.guiCalls.single().result.complete(tap())
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(0, f.phone.performCalls)
        assertEquals(1, f.gateway.guiCalls.size)
        assertFalse(f.phone.captureAllowed)
        assertFalse(f.audit.events.any { it.first == "action" })
        assertFalse(f.audit.events.any { it.first == "session_ended" })
        val writes = f.audit.writeAttempts
        f.coordinator.stop()
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals(writes, f.audit.writeAttempts)
    }

    @Test fun failedActionWriteAfterNativeCompletionCannotStartTheNextObservation() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.holdInput = true
        f.coordinator.start(options())
        runCurrent()
        f.gateway.guiCalls.single().result.complete(tap())
        runCurrent()
        assertTrue(f.phone.inputInFlight)
        f.audit.failWhen = { name, _ -> name == "action" }
        f.phone.completeInput()
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertFalse(f.phone.inputInFlight)
        assertFalse(f.phone.captureAllowed)
        assertEquals(1, f.phone.observations)
        assertEquals(1, f.gateway.guiCalls.size)
        assertEquals(1, f.phone.performCalls)
        assertFalse(f.audit.events.any { it.first == "action" })
        val writes = f.audit.writeAttempts
        f.coordinator.giveBack()
        f.coordinator.retry()
        runCurrent()
        assertEquals(0, f.gateway.plannerCalls.size)
        assertEquals(writes, f.audit.writeAttempts)
    }

    @Test fun failedStopWriteKeepsTheNativeBarrierUntilItsRealTerminalWithoutMoreWrites() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.holdInput = true
        f.coordinator.start(options())
        runCurrent()
        f.gateway.guiCalls.single().result.complete(tap())
        runCurrent()
        f.audit.failWhen = { name, data -> name == "control" && data["transition"] == "stop_requested" }
        f.coordinator.stop()
        runCurrent()
        val writes = f.audit.writeAttempts
        assertTrue(f.phone.inputInFlight)
        assertNotEquals(RunState.ENDED, f.state.state)
        assertFalse(f.phone.captureAllowed)
        assertTrue(f.state.message.contains("当前操作终态"))
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(RunState.UNCERTAIN, f.state.state)
        assertTrue(f.phone.inputInFlight)
        assertEquals(writes, f.audit.writeAttempts)
        f.coordinator.retry()
        f.coordinator.giveBack()
        f.coordinator.recheck()
        runCurrent()
        assertNotEquals(RunState.ENDED, f.state.state)
        f.phone.completeInput()
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertTrue(f.state.message.contains("日志写入失败"))
        assertFalse(f.state.message.contains("模型判断结束"))
        assertEquals(writes, f.audit.writeAttempts)
        assertFalse(f.audit.events.any { it.first == "session_ended" })
        assertEquals(1, f.phone.performCalls)
        assertEquals(1, f.gateway.guiCalls.size)
    }

    @Test fun failedCancellationWriteStillCompletesTheSameExplicitStopAndRejectsLateReplies() = runTest {
        val f = Fixture(backgroundScope)
        f.gateway.ignoreCancellation = true
        f.coordinator.start(options())
        runCurrent()
        f.audit.failWhen = { name, data -> name == "model_call" && data["status"] == "cancelled" }
        f.coordinator.stop()
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertFalse(f.phone.captureAllowed)
        val writes = f.audit.writeAttempts
        f.gateway.guiCalls.single().result.complete(tap())
        runCurrent()
        assertEquals(0, f.phone.performCalls)
        assertEquals(writes, f.audit.writeAttempts)
        assertFalse(f.audit.events.any { it.first == "session_ended" })
    }

    @Test fun failedErrorWriteDoesNotEscapeOrGenerateAnotherAttempt() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options())
        runCurrent()
        f.audit.failWhen = { name, _ -> name == "error" }
        f.gateway.guiCalls.single().result.completeExceptionally(RunFailure("MODEL", "INVALID_OUTPUT"))
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertTrue(f.state.message.contains("日志写入失败"))
        assertEquals(1, f.state.guiUsed)
        assertEquals(0, f.phone.performCalls)
        assertFalse(f.phone.captureAllowed)
        assertFalse(f.audit.events.any { it.first == "error" })
        assertFalse(f.audit.events.toString().contains("PRIVATE_EXCEPTION_PAYLOAD"))
        assertFalse(f.state.message.contains("PRIVATE_EXCEPTION_PAYLOAD"))
    }

    @Test fun failedSessionEndWriteCannotBePresentedAsModelCompletion() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options())
        runCurrent()
        f.audit.failWhen = { name, _ -> name == "session_ended" }
        f.gateway.guiCalls.single().result.complete(finish())
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertTrue(f.state.message.contains("日志写入失败"))
        assertFalse(f.state.message.contains("模型判断结束"))
        assertFalse(f.audit.events.any { it.first == "session_ended" })
        val writes = f.audit.writeAttempts
        f.coordinator.stop()
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals(writes, f.audit.writeAttempts)
        assertEquals(0, f.phone.performCalls)
    }

    @Test fun failedPlannerStartedWriteCannotSendThePlannerOrReturnToGui() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options())
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.audit.failWhen = { name, data -> name == "model_call" && data["role"] == "PLANNER" }
        f.coordinator.giveBack()
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(1, f.state.plannerUsed)
        assertEquals(0, f.gateway.plannerCalls.size)
        assertEquals(1, f.gateway.guiCalls.size)
        assertFalse(f.phone.captureAllowed)
        val writes = f.audit.writeAttempts
        f.coordinator.retry()
        runCurrent()
        assertEquals(writes, f.audit.writeAttempts)
        assertEquals(0, f.gateway.plannerCalls.size)
    }

    @Test fun failedPlannerTerminalOrDecisionWriteCannotEnterRecoveryOrFallback() = runTest {
        for (failedEvent in listOf("model_call", "planner_decision")) {
            val f = Fixture(backgroundScope)
            f.coordinator.start(options())
            runCurrent()
            f.coordinator.takeover()
            runCurrent()
            f.coordinator.giveBack()
            runCurrent()
            assertEquals(1, f.gateway.plannerCalls.size)
            f.audit.failWhen = { name, data -> name == failedEvent &&
                (name != "model_call" || data["status"] == "succeeded") }
            f.gateway.plannerCalls.single().result.complete(PlannerDecision("INCOMPLETE", "EXECUTE",
                "SYNTHETIC_GOAL", listOf("SYNTHETIC_CONDITION")))
            runCurrent()
            assertEquals(RunState.BLOCKED, f.state.state)
            assertFalse(f.phone.captureAllowed)
            assertEquals(1, f.gateway.guiCalls.size)
            assertEquals(0, f.phone.performCalls)
            val writes = f.audit.writeAttempts
            f.coordinator.retry()
            f.coordinator.stop()
            runCurrent()
            assertEquals(RunState.ENDED, f.state.state)
            assertEquals(writes, f.audit.writeAttempts)
            assertEquals(1, f.gateway.guiCalls.size)
            assertFalse(f.audit.events.any { it.first == "session_ended" })
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val phone = FakePhone()
        val gateway = FakeGateway()
        val audit = ThrowingAudit()
        val coordinator = Coordinator(scope, gateway, phone, audit)
        val state get() = coordinator.snapshot.value
    }

    private data class GuiCall(val result: CompletableDeferred<GuiReply> = CompletableDeferred())
    private data class PlannerCall(val result: CompletableDeferred<PlannerDecision> = CompletableDeferred())

    private class FakeGateway : ModelGateway {
        val guiCalls = mutableListOf<GuiCall>()
        val plannerCalls = mutableListOf<PlannerCall>()
        var cancellations = 0
        var ignoreCancellation = false
        override suspend fun gui(context: GuiContext): GuiReply {
            val call = GuiCall().also(guiCalls::add)
            return if (ignoreCancellation) withContext(NonCancellable) { call.result.await() } else call.result.await()
        }
        override suspend fun planner(context: PlannerContext): PlannerDecision {
            val call = PlannerCall().also(plannerCalls::add)
            return call.result.await()
        }
        override fun cancelAll() { cancellations++ }
    }

    private class FakePhone : PhonePort {
        override var connected = true
        override var inputInFlight = false
        var captureAllowed = false
        var observations = 0
        var performCalls = 0
        var holdInput = false
        private var terminal: CompletableDeferred<ActionResult>? = null
        override suspend fun observe(): ScreenFrame {
            check(captureAllowed)
            observations++
            return ScreenFrame("frame-$observations", byteArrayOf(1), 1080, 2280)
        }
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult {
            performCalls++
            check(canDispatch())
            if (!holdInput) return ActionResult("completed")
            inputInFlight = true
            return CompletableDeferred<ActionResult>().also { terminal = it }.await()
        }
        fun completeInput() {
            inputInFlight = false
            checkNotNull(terminal).complete(ActionResult("completed"))
        }
        override fun setCaptureEnabled(enabled: Boolean) { captureAllowed = enabled }
        override fun showState(snapshot: RunSnapshot) = Unit
    }

    private class ThrowingAudit : AuditSink {
        var failWhen: (String, Map<String, Any?>) -> Boolean = { _, _ -> false }
        var writeAttempts = 0
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun begin(sessionId: String, options: SessionOptions) { write("begin", emptyMap()) }
        override fun event(event: String, data: Map<String, Any?>) { write(event, data) }
        private fun write(event: String, data: Map<String, Any?>) {
            writeAttempts++
            if (failWhen(event, data)) throw IOException("PRIVATE_EXCEPTION_PAYLOAD")
            events.add(event to data.toMap())
        }
    }

    private companion object {
        // These frozen assertions intentionally exercise legacy execution policy 1.1.
        fun options() = SessionOptions("SYNTHETIC_TASK", "SYNTHETIC_APP", setOf("SYNTHETIC_APP"), true, 30,
            ModelProfile(), ModelProfile(), automaticRecovery = false)
        fun tap() = GuiReply("tap-id", PhoneAction("tap", mapOf("x" to 500, "y" to 500)), "{}")
        fun finish() = GuiReply("finish-id", PhoneAction("finish", mapOf("outcome" to "completed", "message" to "SYNTHETIC_REPORT")), "{}")
    }
}
