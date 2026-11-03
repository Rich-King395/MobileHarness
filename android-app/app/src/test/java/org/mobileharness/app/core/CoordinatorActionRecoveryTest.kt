package org.mobileharness.app.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Recovery here means another model decision, never replaying or repairing a native action. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorActionRecoveryTest {
    @Test fun failedInputGetsNewObservationAndOnlyTheModelsNextCoordinatesAreExecuted() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.nextResult = failed(ExecutionFailureReason.NO_FOCUSED_INPUT)
        f.coordinator.start(options())
        runCurrent()
        val original = f.gateway.guiCalls.single().context
        val failedReply = input("FAILED_INPUT")
        f.gateway.replyGui(0, failedReply)
        runCurrent()

        assertEquals(RunState.AUTO, f.state.state)
        assertEquals(2, f.state.guiUsed)
        assertEquals(0, f.state.plannerUsed)
        assertEquals(1, f.phone.executed.size)
        val next = f.gateway.guiCalls[1].context
        assertNotEquals(original.frame.id, next.frame.id)
        assertEquals(Phase.ORIGINAL, next.phase)
        assertEquals(listOf(failedReply), next.history.map { it.reply })
        assertEquals(ExecutionFailureReason.NO_FOCUSED_INPUT, next.history.single().result.failureReason)

        val modelSelectedTap = tap("MODEL_SELECTED_RETRY", 173, 681)
        f.gateway.replyGui(1, modelSelectedTap)
        runCurrent()
        assertEquals(listOf(failedReply.action, modelSelectedTap.action), f.phone.executed.map { it.action })
        assertEquals(next.frame.id, f.phone.executed.last().frame.id)
        assertEquals(3, f.state.guiUsed)
        assertEquals(listOf(failedReply, modelSelectedTap), f.gateway.guiCalls[2].context.history.map { it.reply })

        // A failed attempt is useful phase history but is not an Agent success supplied to Planner.
        f.coordinator.takeover()
        runCurrent()
        f.coordinator.giveBack()
        runCurrent()
        assertEquals(listOf(modelSelectedTap), f.gateway.plannerCalls.single().context.completedActions.map { it.reply })
        assertEquals(3, f.state.guiUsed)
    }

    @Test fun typedNativeFailureRetainsItsReasonAndAllowsANewModelDecision() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.nextFailure = RunFailure("DEVICE", "INPUT_FAILED",
            executionReason = ExecutionFailureReason.IME_SUBMIT_UNSUPPORTED)
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, GuiReply("MODEL_ENTER", PhoneAction("press_key", mapOf("key" to "enter")), "{}"))
        runCurrent()

        assertEquals(RunState.AUTO, f.state.state)
        assertEquals(2, f.gateway.guiCalls.size)
        assertEquals(1, f.phone.executed.size)
        assertEquals(ExecutionFailureReason.IME_SUBMIT_UNSUPPORTED,
            f.gateway.guiCalls[1].context.history.single().result.failureReason)
        assertEquals("IME_SUBMIT_UNSUPPORTED", f.audit.errors().single()["execution_failure_reason"])
    }

    @Test fun recoverableFailureAtLastSharedStepEndsWithoutCaptureOrAnotherCall() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.nextResult = failed(ExecutionFailureReason.NO_FOCUSED_INPUT)
        f.coordinator.start(options(budget = 1))
        runCurrent()
        f.gateway.replyGui(0, input())
        runCurrent()

        assertEquals(RunState.ENDED, f.state.state)
        assertEquals(1, f.state.guiUsed)
        assertEquals(0, f.state.plannerUsed)
        assertEquals(1, f.phone.observations)
        assertEquals(1, f.gateway.guiCalls.size)
        assertEquals(1, f.phone.executed.size)
        assertEquals("budget_exhausted", f.audit.ended()["reason"])
    }

    @Test fun confirmedGestureCancellationCanReturnToModelWithoutReplayingGesture() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.nextResult = ActionResult("cancelled", failureReason = ExecutionFailureReason.GESTURE_CANCELLED)
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, tap())
        runCurrent()

        assertEquals(RunState.AUTO, f.state.state)
        assertEquals(2, f.phone.observations)
        assertEquals(2, f.gateway.guiCalls.size)
        assertEquals(1, f.phone.executed.size)
        assertEquals("cancelled", f.gateway.guiCalls[1].context.history.single().result.status)
    }

    @Test fun unknownNativeResultStaysBlockedEvenWhenThereIsNoRemainingNativeCallback() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.nextResult = ActionResult("unknown", failureReason = ExecutionFailureReason.INPUT_RESULT_UNKNOWN)
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, tap())
        runCurrent()

        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(1, f.phone.observations)
        assertEquals(1, f.gateway.guiCalls.size)
        assertFalse(f.phone.captureAllowed)
        assertEquals("INPUT_RESULT_UNKNOWN", f.audit.errors().single()["execution_failure_reason"])
    }

    @Test fun unknownStatusCannotBeOverriddenByARecoverableReason() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.nextResult = ActionResult("unknown", failureReason = ExecutionFailureReason.NO_FOCUSED_INPUT)
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, input())
        runCurrent()

        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(1, f.state.guiUsed)
        assertEquals(1, f.phone.observations)
        assertEquals(1, f.gateway.guiCalls.size)
        assertFalse(f.phone.captureAllowed)
    }

    @Test fun evenAKnownFailureCannotRestartWhileNativeInputRemainsInFlight() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.nextResult = failed(ExecutionFailureReason.GESTURE_NOT_DISPATCHED)
        f.phone.keepInputInFlightAfterResult = true
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, tap())
        runCurrent()

        assertEquals(RunState.PAUSING, f.state.state)
        assertEquals(1, f.phone.observations)
        assertEquals(1, f.gateway.guiCalls.size)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(RunState.UNCERTAIN, f.state.state)
        assertEquals(1, f.gateway.guiCalls.size)

        f.phone.inputInFlight = false
        f.coordinator.recheck()
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(1, f.gateway.guiCalls.size)
    }

    @Test fun missingReasonAndUnclassifiedExceptionsRemainHardFailuresWithoutPrivateDetails() = runTest {
        for (throwException in listOf(false, true)) {
            val f = Fixture(backgroundScope)
            if (throwException) f.phone.nextFailure = IllegalStateException("PRIVATE_NATIVE_EXCEPTION")
            else f.phone.nextResult = ActionResult("device_error", "PRIVATE_NATIVE_RESULT")
            f.coordinator.start(options())
            runCurrent()
            f.gateway.replyGui(0, input())
            runCurrent()

            assertEquals(RunState.BLOCKED, f.state.state)
            assertEquals(1, f.state.guiUsed)
            assertEquals(1, f.phone.observations)
            assertEquals(1, f.gateway.guiCalls.size)
            assertEquals("INPUT_FAILED", f.audit.errors().single()["code"])
            if (throwException) {
                assertEquals("INPUT_RESULT_UNKNOWN", f.audit.errors().single()["execution_failure_reason"])
            } else {
                assertFalse(f.audit.errors().single().containsKey("execution_failure_reason"))
            }
            assertFalse(f.audit.events.toString().contains("PRIVATE_NATIVE"))
            assertFalse(f.state.message.contains("PRIVATE_NATIVE"))
        }
    }

    @Test fun protectedInputDoesNotBecomeAnAutomaticModelRetry() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.nextResult = failed(ExecutionFailureReason.PROTECTED_INPUT)
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, input())
        runCurrent()

        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(1, f.gateway.guiCalls.size)
        assertEquals(1, f.phone.observations)
        assertEquals("PROTECTED_INPUT", f.audit.errors().single()["execution_failure_reason"])
    }

    @Test fun immediateBlockKeepsItsControlledReasonInsteadOfReplacingItWithGenericGuidance() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.nextResult = ActionResult("device_error", "PRIVATE_RESULT_DETAIL", ExecutionFailureReason.PROTECTED_INPUT)
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, input())
        runCurrent()

        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals("已暂停 · 当前输入受保护（INPUT_FAILED）", f.state.message)
        f.coordinator.recheck()
        runCurrent()
        assertEquals("已暂停 · 当前输入受保护（INPUT_FAILED）", f.state.message)
        assertFalse(f.state.message.contains("PRIVATE_RESULT_DETAIL"))
        assertEquals(1, f.gateway.guiCalls.size)
    }

    @Test fun blockAfterNativeTerminalRestoresTheControlledReasonAcrossBarrierTimeout() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.holdInput = true
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, tap())
        runCurrent()
        f.coordinator.environmentUnavailable("SERVICE_DISCONNECTED")
        runCurrent()
        val expectedReason = "已暂停 · 设备或当前窗口暂不可用（SERVICE_DISCONNECTED）"
        assertEquals(RunState.PAUSING, f.state.state)
        assertEquals(expectedReason, f.state.message)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(RunState.UNCERTAIN, f.state.state)

        f.phone.completeInput(ActionResult("cancelled", "PRIVATE_NATIVE_TERMINAL", ExecutionFailureReason.GESTURE_CANCELLED))
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(expectedReason, f.state.message)
        assertFalse(f.state.message.contains("PRIVATE_NATIVE_TERMINAL"))
        assertEquals(1, f.phone.observations)
        assertEquals(1, f.gateway.guiCalls.size)
    }

    @Test fun recoveryFailureKeepsRecoveryHistoryGoalBudgetAndSinglePlannerCall() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, tap("ORIGINAL_SUCCESS"))
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.coordinator.giveBack()
        runCurrent()
        f.gateway.replyPlanner(0, PlannerDecision("INCOMPLETE", "EXECUTE", "REMAINING_GOAL", listOf("VISIBLE_RESULT")))
        runCurrent()
        val recovery = f.gateway.guiCalls[2].context
        assertEquals(Phase.RECOVERY, recovery.phase)
        assertTrue(recovery.history.isEmpty())
        f.phone.nextResult = failed(ExecutionFailureReason.INPUT_NOT_EDITABLE)
        val failedReply = input("RECOVERY_FAILURE")
        f.gateway.replyGui(2, failedReply)
        runCurrent()

        val next = f.gateway.guiCalls[3].context
        assertEquals(RunState.AUTO, f.state.state)
        assertEquals(Phase.RECOVERY, next.phase)
        assertEquals("REMAINING_GOAL", next.objective)
        assertEquals(listOf("VISIBLE_RESULT"), next.conditions)
        assertEquals(listOf(failedReply), next.history.map { it.reply })
        assertNotEquals(recovery.frame.id, next.frame.id)
        assertEquals(1, f.gateway.plannerCalls.size)
        assertEquals(1, f.state.plannerUsed)
        assertEquals(4, f.state.guiUsed)
        assertEquals(0, next.returnNoticeIndex)
    }

    @Test fun takeoverOrStopQueuedWithFailureWinsBeforeANewCaptureOrModelCall() = runTest {
        for (takeover in listOf(true, false)) {
            val f = Fixture(backgroundScope)
            f.phone.nextResult = failed(ExecutionFailureReason.NO_FOCUSED_INPUT)
            f.phone.beforeResult = { if (takeover) f.coordinator.takeover() else f.coordinator.stop() }
            f.coordinator.start(options())
            runCurrent()
            f.gateway.replyGui(0, input())
            runCurrent()

            assertEquals(if (takeover) RunState.MANUAL else RunState.ENDED, f.state.state)
            assertFalse(f.phone.captureAllowed)
            assertEquals(1, f.phone.observations)
            assertEquals(1, f.gateway.guiCalls.size)
            assertEquals(1, f.state.guiUsed)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, f.phone.observations)
            assertEquals(1, f.phone.executed.size)
        }
    }

    @Test fun submittedFailureWaitsForItsNativeTerminalBeforeManualControlIsGranted() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.holdInput = true
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, tap())
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        assertEquals(RunState.PAUSING, f.state.state)

        f.phone.completeInput(failed(ExecutionFailureReason.GESTURE_CANCELLED))
        runCurrent()
        assertEquals(RunState.MANUAL, f.state.state)
        assertFalse(f.phone.captureAllowed)
        assertEquals(1, f.gateway.guiCalls.size)
        val observationsAtGrant = f.phone.observations
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(observationsAtGrant, f.phone.observations)
    }

    @Test fun readonlyVerificationAndConfirmationStillRejectNativeInputWithoutAnotherPlanner() = runTest {
        for (completion in listOf("INCOMPLETE", "COMPLETE")) {
            val f = Fixture(backgroundScope)
            f.coordinator.start(options())
            runCurrent()
            f.coordinator.takeover()
            runCurrent()
            f.coordinator.giveBack()
            runCurrent()
            f.gateway.replyPlanner(0, PlannerDecision(completion, "VERIFY", "READ_ONLY_GOAL", listOf("VISIBLE_RESULT")))
            runCurrent()
            f.phone.nextResult = failed(ExecutionFailureReason.NO_FOCUSED_INPUT)
            f.gateway.replyGui(1, input())
            runCurrent()

            assertEquals(RunState.BLOCKED, f.state.state)
            assertEquals(if (completion == "COMPLETE") Phase.CONFIRM else Phase.VERIFY, f.state.phase)
            assertTrue(f.phone.executed.isEmpty())
            assertEquals(2, f.gateway.guiCalls.size)
            assertEquals(1, f.gateway.plannerCalls.size)
            assertEquals(2, f.state.guiUsed)
            assertEquals("INVALID_OUTPUT", f.audit.errors().single()["code"])
        }
    }

    @Test fun failureReasonMetadataUsesEnumCodesAndDoesNotIncludeResultTextOrActionArguments() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.nextResult = ActionResult("device_error", "PRIVATE_RESULT_DETAIL",
            ExecutionFailureReason.NO_FOCUSED_INPUT)
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, input("PRIVATE_REPLY_ID"))
        runCurrent()

        val allowedReasons = ExecutionFailureReason.values().map { it.name }.toSet()
        val reasons = f.audit.events.mapNotNull { it.second["execution_failure_reason"] }
        assertTrue(reasons.isNotEmpty())
        assertTrue(reasons.all { it in allowedReasons })
        assertTrue(f.audit.errors().any { it["execution_failure_reason"] == "NO_FOCUSED_INPUT" })
        assertTrue(f.audit.events.any { it.first == "action" && it.second["execution_failure_reason"] == "NO_FOCUSED_INPUT" })
        val logged = f.audit.events.toString()
        listOf("PRIVATE_RESULT_DETAIL", "PRIVATE_REPLY_ID", "PRIVATE_TEXT", "PRIVATE_ARGUMENTS", "PRIVATE_TASK", "PRIVATE_PACKAGE")
            .forEach { assertFalse(it, logged.contains(it)) }
    }

    private class Fixture(scope: CoroutineScope) {
        val gateway = FakeGateway()
        val phone = FakePhone()
        val audit = FakeAudit()
        val coordinator = Coordinator(scope, gateway, phone, audit)
        val state get() = coordinator.snapshot.value
    }

    private data class GuiCall(val context: GuiContext, val result: CompletableDeferred<GuiReply> = CompletableDeferred())
    private data class PlannerCall(val context: PlannerContext, val result: CompletableDeferred<PlannerDecision> = CompletableDeferred())
    private data class Execution(val action: PhoneAction, val frame: ScreenFrame)

    private class FakeGateway : ModelGateway {
        val guiCalls = mutableListOf<GuiCall>()
        val plannerCalls = mutableListOf<PlannerCall>()
        override suspend fun gui(context: GuiContext): GuiReply =
            GuiCall(context).also(guiCalls::add).result.await()
        override suspend fun planner(context: PlannerContext): PlannerDecision =
            PlannerCall(context).also(plannerCalls::add).result.await()
        override fun cancelAll() = Unit
        fun replyGui(index: Int, reply: GuiReply) { check(guiCalls[index].result.complete(reply)) }
        fun replyPlanner(index: Int, reply: PlannerDecision) { check(plannerCalls[index].result.complete(reply)) }
    }

    private class FakePhone : PhonePort {
        override var connected = true
        override var inputInFlight = false
        var captureAllowed = false
        var observations = 0
        var nextResult = ActionResult("completed")
        var nextFailure: Exception? = null
        var beforeResult: (() -> Unit)? = null
        var holdInput = false
        var keepInputInFlightAfterResult = false
        val executed = mutableListOf<Execution>()
        private var pendingInput: CompletableDeferred<ActionResult>? = null

        override suspend fun observe(): ScreenFrame {
            check(captureAllowed)
            observations++
            return ScreenFrame("frame-$observations", byteArrayOf(observations.toByte()), 1080, 2280)
        }

        override suspend fun perform(action: PhoneAction, frame: ScreenFrame,
            allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult {
            check(canDispatch())
            executed.add(Execution(action, frame))
            val result = if (holdInput) {
                inputInFlight = true
                CompletableDeferred<ActionResult>().also { pendingInput = it }.await()
            } else nextResult.also { nextResult = ActionResult("completed") }
            if (keepInputInFlightAfterResult) inputInFlight = true
            beforeResult?.invoke()
            nextFailure?.let { nextFailure = null; throw it }
            return result
        }

        fun completeInput(result: ActionResult) {
            inputInFlight = false
            checkNotNull(pendingInput).complete(result)
        }
        override fun setCaptureEnabled(enabled: Boolean) { captureAllowed = enabled }
        override fun showState(snapshot: RunSnapshot) = Unit
    }

    private class FakeAudit : AuditSink {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun begin(sessionId: String, options: SessionOptions) = Unit
        override fun event(event: String, data: Map<String, Any?>) { events.add(event to data.toMap()) }
        fun errors() = events.filter { it.first == "error" }.map { it.second }
        fun ended() = events.last { it.first == "session_ended" }.second
    }

    private companion object {
        // These frozen assertions intentionally exercise legacy execution policy 1.1.
        fun options(budget: Int = 30) = SessionOptions(
            task = "PRIVATE_TASK", initialPackage = "PRIVATE_PACKAGE", authorizedPackages = setOf("PRIVATE_PACKAGE"),
            harness = true, budget = budget, gui = ModelProfile(), planner = ModelProfile(), automaticRecovery = false,
        )
        fun failed(reason: ExecutionFailureReason) = ActionResult("device_error", failureReason = reason)
        fun input(id: String = "input-id") = GuiReply(id,
            PhoneAction("input_text", mapOf("text" to "PRIVATE_TEXT")), "PRIVATE_ARGUMENTS")
        fun tap(id: String = "tap-id", x: Int = 500, y: Int = 500) =
            GuiReply(id, PhoneAction("tap", mapOf("x" to x, "y" to y)), "{}")
    }
}
