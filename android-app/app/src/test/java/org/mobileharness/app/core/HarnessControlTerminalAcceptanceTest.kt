package org.mobileharness.app.core

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

/** C-layer terminal/control permutations. No real HTTP, pixels, Android callback or input. */
@OptIn(ExperimentalCoroutinesApi::class)
class HarnessControlTerminalAcceptanceTest {
    // CT-05: 2 switches x 2 real native results. A deadline is never an input terminal.
    @Test fun ct05MissingNativeTerminalStaysUncertainAcrossRechecksUntilMatchingResult() = runTest {
        for (harness in listOf(false, true)) for (status in listOf("completed", "cancelled")) {
            val f = Fixture(backgroundScope)
            f.phone.holdInput = true
            f.coordinator.start(options(harness))
            runCurrent()
            f.gateway.answerGui(0, tap("CT05_SUBMITTED"))
            runCurrent()
            assertTrue(f.phone.inputInFlight)
            f.coordinator.takeover()
            runCurrent()
            val before = f.counts()
            assertEquals(RunState.PAUSING, f.state.state)
            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(RunState.PAUSING, f.state.state)
            assertEquals(0, f.audit.transitions("manual_granted"))
            advanceTimeBy(1)
            runCurrent()
            assertEquals(RunState.UNCERTAIN, f.state.state)
            assertTrue(f.phone.inputInFlight)
            assertFalse(f.phone.captureAllowed)
            assertEquals(0, f.audit.transitions("manual_granted"))
            assertEquals(0, f.audit.transitions("return_committed"))
            assertEquals(before, f.counts())
            repeat(3) {
                f.coordinator.recheck()
                f.coordinator.giveBack()
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(RunState.UNCERTAIN, f.state.state)
                assertEquals(before, f.counts())
                assertTrue(f.phone.inputInFlight)
                assertFalse(f.phone.captureAllowed)
                assertEquals(0, f.audit.transitions("manual_granted"))
            }
            assertTrue(f.phone.completeInput(status))
            runCurrent()
            assertEquals(RunState.MANUAL, f.state.state)
            assertFalse(f.phone.inputInFlight)
            assertEquals(1, f.audit.transitions("manual_granted"))
            assertEquals(before, f.counts())
            assertFalse(f.phone.completeInput(status))
            f.coordinator.recheck()
            runCurrent()
            assertEquals(1, f.audit.transitions("manual_granted"))
            assertEquals(before, f.counts())
            f.coordinator.stop()
            runCurrent()
            assertEquals(RunState.ENDED, f.state.state)
        }
    }

    // CT-06: PAUSING/UNCERTAIN x 2 switches x 2 results. The newest stop intent wins.
    @Test fun ct06StopFromEitherPauseStateNeverBrieflyGrantsManualOrReturn() = runTest {
        for (harness in listOf(false, true)) for (fromUncertain in listOf(false, true)) for (status in listOf("completed", "cancelled")) {
            val f = Fixture(backgroundScope)
            f.coordinator.start(options(harness))
            runCurrent()
            // The wait is a legal predecessor, not a fabricated concurrent model operation.
            f.gateway.answerGui(0, waitReply("CT06_OLD_WAIT"))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, f.gateway.guiCalls.size)
            f.phone.holdInput = true
            f.gateway.answerGui(1, tap("CT06_SUBMITTED"))
            runCurrent()
            assertTrue(f.phone.inputInFlight)
            f.coordinator.takeover()
            runCurrent()
            if (fromUncertain) {
                advanceTimeBy(5_000)
                runCurrent()
            }
            assertEquals(if (fromUncertain) RunState.UNCERTAIN else RunState.PAUSING, f.state.state)
            assertEquals(0, f.audit.transitions("manual_granted"))
            val baseline = f.counts()
            f.coordinator.stop()
            runCurrent()
            val stopTraceStart = f.phone.states.lastIndex
            assertTrue(f.state.state in setOf(RunState.STOPPING, RunState.UNCERTAIN))
            assertFalse(f.phone.captureAllowed)
            assertEquals(0, f.audit.endCount())
            // These normalized single-result interfaces reject duplicate delivery themselves.
            // The pending native result remains genuinely withheld, so none can close its barrier.
            assertFalse(f.gateway.guiCalls[1].result.complete(finish("completed", "CT06_STALE_DONE")))
            assertFalse(f.phone.observationResults.first().complete(frame(99)))
            repeat(2) {
                f.coordinator.giveBack()
                f.coordinator.recheck()
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(baseline, f.counts())
                assertEquals(0, f.audit.transitions("manual_granted"))
                assertEquals(0, f.audit.transitions("return_committed"))
                assertEquals(0, f.audit.endCount())
                assertTrue(f.phone.inputInFlight)
                assertFalse(f.phone.captureAllowed)
                assertTrue(f.state.state in setOf(RunState.STOPPING, RunState.UNCERTAIN))
            }
            assertTrue(f.phone.completeInput(status))
            runCurrent()
            assertEquals(RunState.ENDED, f.state.state)
            assertEquals("user_stop", f.audit.lastEnd()["reason"])
            assertEquals("none", f.audit.lastEnd()["completion_source"])
            assertEquals(0, f.audit.transitions("manual_granted"))
            assertEquals(0, f.audit.transitions("return_committed"))
            assertEquals(1, f.audit.endCount())
            assertEquals(baseline, f.counts())
            assertTrue(f.phone.states.drop(stopTraceStart).all { it.state in setOf(RunState.STOPPING, RunState.UNCERTAIN, RunState.ENDED) })
            assertEquals(1, f.audit.actions("wait"))
            assertFalse(f.phone.completeInput(status))
            f.coordinator.giveBack()
            f.coordinator.takeover()
            f.coordinator.recheck()
            runCurrent()
            assertEquals(baseline, f.counts())
            assertEquals(1, f.audit.endCount())
        }
    }

    // CT-10: 2 switches x (no input, native completed, native cancelled) = 6 trajectories.
    @Test fun ct10UsedReturnCycleStillStopsSafelyButNeverPermitsASecondReturn() = runTest {
        for (harness in listOf(false, true)) for (nativeStatus in listOf<String?>(null, "completed", "cancelled")) {
            val f = Fixture(backgroundScope)
            f.gateway.ignoreGuiCancellation.add(0)
            if (nativeStatus == null) f.gateway.ignoreGuiCancellation.add(1)
            f.coordinator.start(options(harness))
            runCurrent()
            val oldBeforeFirstTakeover = f.gateway.guiCalls[0]
            f.coordinator.takeover()
            runCurrent()
            assertEquals(RunState.MANUAL, f.state.state)
            f.phone.businessValue = "CT10_HUMAN_CURRENT_VALUE"
            f.coordinator.giveBack()
            runCurrent()
            if (harness) {
                f.gateway.answerPlanner(0, PlannerDecision("INCOMPLETE", "EXECUTE", "CT10_REMAINING_WORK", listOf("CT10_VISIBLE_VALUE")))
                runCurrent()
            }
            assertTrue(f.state.handoffUsed)
            assertEquals(1, f.audit.transitions("manual_granted"))
            assertEquals(1, f.audit.transitions("return_committed"))
            val latest = f.gateway.guiCalls[1]
            if (nativeStatus != null) {
                f.phone.holdInput = true
                f.gateway.answerGui(1, tap("CT10_ALREADY_SUBMITTED"))
                runCurrent()
                assertTrue(f.phone.inputInFlight)
            }
            val baseline = f.counts()
            f.coordinator.takeover() // UI's "stop and continue manually" after the single cycle.
            runCurrent()
            if (nativeStatus == null) assertEquals(RunState.ENDED, f.state.state)
            else {
                assertEquals(RunState.STOPPING, f.state.state)
                assertEquals(0, f.audit.endCount())
                f.coordinator.giveBack()
                f.coordinator.recheck()
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(RunState.UNCERTAIN, f.state.state)
                assertTrue(f.phone.inputInFlight)
                assertEquals(1, f.audit.transitions("manual_granted"))
                assertEquals(1, f.audit.transitions("return_committed"))
                assertEquals(baseline, f.counts())
                assertTrue(f.phone.completeInput(nativeStatus))
                runCurrent()
                assertEquals(RunState.ENDED, f.state.state)
            }
            assertFalse(f.phone.captureAllowed)
            assertTrue(f.state.handoffUsed)
            assertEquals("user_stop", f.audit.lastEnd()["reason"])
            assertEquals("none", f.audit.lastEnd()["completion_source"])
            assertEquals("CT10_HUMAN_CURRENT_VALUE", f.phone.businessValue)
            assertEquals(baseline, f.counts())
            assertTrue(oldBeforeFirstTakeover.result.complete(tap("CT10_STALE_FIRST_TASK_REPLY")))
            if (nativeStatus == null) assertTrue(latest.result.complete(finish("completed", "CT10_STALE_FINISH")))
            else assertFalse(latest.result.complete(finish("completed", "CT10_STALE_FINISH")))
            f.phone.observationResults.forEach { assertFalse(it.complete(frame(99))) }
            runCurrent()
            repeat(3) {
                f.coordinator.giveBack()
                f.coordinator.takeover()
                f.coordinator.recheck()
                advanceTimeBy(2_000)
                runCurrent()
                assertEquals(RunState.ENDED, f.state.state)
                assertEquals(baseline, f.counts())
            }
            assertEquals(1, f.audit.endCount())
            assertEquals(1, f.audit.transitions("manual_granted"))
            assertEquals(1, f.audit.transitions("return_committed"))
            assertEquals(if (harness) 1 else 0, f.gateway.plannerCalls.size)
            assertEquals(0, f.audit.actions("finish"))
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val phone = FakePhone()
        val gateway = FakeGateway()
        val audit = FakeAudit()
        val coordinator = Coordinator(scope, gateway, phone, audit)
        val state get() = coordinator.snapshot.value
        fun counts() = Counts(state.guiUsed, state.plannerUsed, phone.observations, phone.dispatches, gateway.guiCalls.size, gateway.plannerCalls.size)
    }
    private data class Counts(val gui: Int, val planner: Int, val observations: Int, val dispatches: Int, val guiCalls: Int, val plannerCalls: Int)
    private data class GuiCall(val context: GuiContext, val result: CompletableDeferred<GuiReply> = CompletableDeferred())
    private data class PlannerCall(val context: PlannerContext, val result: CompletableDeferred<PlannerDecision> = CompletableDeferred())
    private class FakeGateway : ModelGateway {
        val guiCalls = mutableListOf<GuiCall>()
        val plannerCalls = mutableListOf<PlannerCall>()
        val ignoreGuiCancellation = mutableSetOf<Int>()
        override suspend fun gui(context: GuiContext): GuiReply {
            val index = guiCalls.size
            val call = GuiCall(context).also(guiCalls::add)
            return if (index in ignoreGuiCancellation) withContext(NonCancellable) { call.result.await() } else call.result.await()
        }
        override suspend fun planner(context: PlannerContext): PlannerDecision = PlannerCall(context).also(plannerCalls::add).result.await()
        override fun cancelAll() = Unit
        fun answerGui(index: Int, value: GuiReply) { check(guiCalls[index].result.complete(value)) }
        fun answerPlanner(index: Int, value: PlannerDecision) { check(plannerCalls[index].result.complete(value)) }
    }
    private class FakePhone : PhonePort {
        override var connected = true
        override var inputInFlight = false
        var captureAllowed = false
        var observations = 0
        var dispatches = 0
        var holdInput = false
        var businessValue = "CT_INITIAL_VALUE"
        val states = mutableListOf<RunSnapshot>()
        val observationResults = mutableListOf<CompletableDeferred<ScreenFrame>>()
        private var input: CompletableDeferred<ActionResult>? = null
        override suspend fun observe(): ScreenFrame {
            check(captureAllowed)
            val result = CompletableDeferred(frame(++observations)).also(observationResults::add)
            return result.await()
        }
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult {
            if (!canDispatch()) return ActionResult("rejected")
            dispatches++
            if (!holdInput) return ActionResult("completed")
            inputInFlight = true
            return CompletableDeferred<ActionResult>().also { input = it }.await()
        }
        fun completeInput(status: String): Boolean {
            inputInFlight = false
            return checkNotNull(input).complete(ActionResult(status))
        }
        override fun setCaptureEnabled(enabled: Boolean) { captureAllowed = enabled }
        override fun showState(snapshot: RunSnapshot) { states.add(snapshot) }
    }
    private class FakeAudit : AuditSink {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun begin(sessionId: String, options: SessionOptions) = Unit
        override fun event(event: String, data: Map<String, Any?>) { events.add(event to data.toMap()) }
        fun transitions(value: String) = events.count { it.first == "control" && it.second["transition"] == value }
        fun actions(value: String) = events.count { it.first == "action" && it.second["action_type"] == value }
        fun endCount() = events.count { it.first == "session_ended" }
        fun lastEnd() = events.last { it.first == "session_ended" }.second
    }
    private companion object {
        // These frozen assertions intentionally exercise legacy execution policy 1.1.
        fun options(harness: Boolean) = SessionOptions("CT_SYNTHETIC_ORIGINAL_TASK", "org.synthetic.fixture", setOf("org.synthetic.fixture"), harness, 30,
            ModelProfile(apiKey = ""), ModelProfile(apiKey = ""), automaticRecovery = false)
        fun frame(index: Int) = ScreenFrame("frame-$index", byteArrayOf(index.toByte()), 1080, 2280)
        fun tap(id: String) = GuiReply(id, PhoneAction("tap", mapOf("x" to 500, "y" to 500)), "{\"x\":500,\"y\":500}")
        fun waitReply(id: String) = GuiReply(id, PhoneAction("wait", mapOf("duration_ms" to 1_000)), "{\"duration_ms\":1000}")
        fun finish(outcome: String, id: String) = GuiReply(id, PhoneAction("finish", mapOf("outcome" to outcome, "message" to "CT_SYNTHETIC_REPORT")), "{\"outcome\":\"$outcome\",\"message\":\"CT_SYNTHETIC_REPORT\"}")
    }
}
