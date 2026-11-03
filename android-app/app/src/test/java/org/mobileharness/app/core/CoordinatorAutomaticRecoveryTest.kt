package org.mobileharness.app.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Policy 1.2: explicit, bounded recovery with synthetic time/data; no device or network. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorAutomaticRecoveryTest {
    @Test fun newSessionsEnableTheVersionedPolicyWithoutChangingPromptOrAdapterSelection() {
        val automatic = options()
        val legacy = automatic.copy(automaticRecovery = false)
        assertTrue(automatic.automaticRecovery)
        assertEquals("1.2.0", automatic.executionPolicyVersion)
        assertEquals("1.1.0", legacy.executionPolicyVersion)
        assertEquals(legacy.promptBundleVersion, automatic.promptBundleVersion)
        assertEquals(legacy.adapterVersion, automatic.adapterVersion)
    }

    @Test fun invalidOutputWaitsOneSecondThenUsesANewFrameAndANewCountedRequest() = runTest {
        val f = started()
        val old = f.gateway.gui.single().context.frame
        f.gateway.failGui(0, invalid())
        runCurrent()
        assertEquals(RunState.AUTO, f.state.state)
        assertFalse(f.phone.captureAllowed)
        assertEquals(1, f.state.guiUsed)
        assertEquals(1, f.gateway.gui.size)
        assertRecovery(f, "GUI", 1, 1_000, "scheduled")
        advanceTimeBy(999); runCurrent()
        assertEquals(1, f.phone.observations)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, f.state.guiUsed)
        assertEquals(2, f.gateway.gui.size)
        assertNotEquals(old.id, f.gateway.gui[1].context.frame.id)
        assertTrue(f.gateway.gui[1].context.history.isEmpty())
        assertTrue(f.phone.performed.isEmpty())
        assertRecovery(f, "GUI", 1, 1_000, "started")
        assertEquals(listOf("started", "invalid_output", "started"), f.audit.of("model_call").map { it["status"] })
        assertNoBlockedState(f)
    }

    @Test fun thirdConsecutiveModelFailureEndsInsteadOfPausingOrCallingAFourthTime() = runTest {
        val f = started()
        f.gateway.failGui(0, invalid()); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        f.gateway.failGui(1, invalid()); runCurrent()
        assertRecovery(f, "GUI", 2, 3_000, "scheduled")
        advanceTimeBy(2_999); runCurrent()
        assertEquals(2, f.gateway.gui.size)
        advanceTimeBy(1); runCurrent()
        f.gateway.failGui(2, invalid()); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals("runtime_failure", f.audit.end()["reason"])
        assertEquals(3, f.state.guiUsed)
        assertEquals(3, f.audit.of("error").size)
        assertEquals(2, f.audit.recovery("scheduled").size)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, f.gateway.gui.size)
        assertTrue(f.gateway.planner.isEmpty())
        assertNoBlockedState(f)
    }

    @Test fun transientTransportFailureIsRecordedAndItsNextRequestConsumesAnotherStep() = runTest {
        val f = started()
        f.gateway.failGui(0, RunFailure("MODEL", "TRANSPORT_ERROR", transportReason = "TIMEOUT")); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, f.state.guiUsed)
        assertEquals(2, f.gateway.gui.size)
        val error = f.audit.of("error").single()
        assertEquals("TRANSPORT_ERROR", error["code"])
        assertEquals("TIMEOUT", error["transport_reason"])
        f.gateway.replyGui(1, finish()); runCurrent()
        assertEquals("model_declared_complete", f.audit.end()["reason"])
        assertNoBlockedState(f)
    }

    @Test fun retryableHttpStatusesRecoverWithoutReplayingAPhoneAction() = runTest {
        for (status in listOf(408, 429, 500, 502, 503)) {
            val f = started()
            f.gateway.failGui(0, RunFailure("MODEL", "API_ERROR", httpStatus = status)); runCurrent()
            assertEquals(status, f.audit.of("error").single()["http_status"])
            advanceTimeBy(1_000); runCurrent()
            assertEquals(2, f.gateway.gui.size)
            assertTrue(f.phone.performed.isEmpty())
            f.gateway.replyGui(1, finish()); runCurrent()
            assertNoBlockedState(f)
        }
    }

    @Test fun authenticationFailureEndsWithoutAutomaticallySpendingMoreCalls() = runTest {
        val f = started()
        f.gateway.failGui(0, RunFailure("MODEL", "API_ERROR", httpStatus = 401)); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals("runtime_failure", f.audit.end()["reason"])
        assertTrue(f.audit.of("recovery").isEmpty())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, f.gateway.gui.size)
        assertNoBlockedState(f)
    }

    @Test fun handshakeInterruptionGetsANewCountedRequestWithoutChangingTransportVerification() = runTest {
        val f = started()
        f.gateway.failGui(0, RunFailure("MODEL", "TRANSPORT_ERROR", transportReason = "TLS_HANDSHAKE_FAILED"))
        runCurrent()
        assertEquals("TLS_HANDSHAKE_FAILED", f.audit.of("error").single()["transport_reason"])
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, f.state.guiUsed)
        assertEquals(2, f.gateway.gui.size)
        assertNotEquals(f.gateway.gui[0].context.frame.id, f.gateway.gui[1].context.frame.id)
        assertTrue(f.phone.performed.isEmpty())
        f.gateway.replyGui(1, finish()); runCurrent()
        assertEquals("model_declared_complete", f.audit.end()["reason"])
        assertNoBlockedState(f)
    }

    @Test fun peerVerificationFailureEndsWithoutWeakeningAuthorizationOrRetrying() = runTest {
        val f = started()
        f.gateway.failGui(0, RunFailure("MODEL", "TRANSPORT_ERROR", transportReason = "TLS_PEER_UNVERIFIED"))
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals("runtime_failure", f.audit.end()["reason"])
        assertEquals(1, f.gateway.gui.size)
        assertTrue(f.audit.of("recovery").isEmpty())
        assertTrue(f.phone.performed.isEmpty())
        assertNoBlockedState(f)
    }

    @Test fun lastFailedGuiDecisionExhaustsTheBudgetWithoutAFreeRepairCall() = runTest {
        val f = started(budget = 1)
        f.gateway.failGui(0, invalid()); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals("budget_exhausted", f.audit.end()["reason"])
        assertEquals(1, f.state.guiUsed)
        assertTrue(f.audit.of("recovery").isEmpty())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, f.gateway.gui.size)
        assertTrue(f.phone.performed.isEmpty())
    }

    @Test fun theSecondFailedCallStillUsesTheLastSharedStep() = runTest {
        val f = started(budget = 2)
        f.gateway.failGui(0, invalid()); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        f.gateway.failGui(1, invalid()); runCurrent()
        assertEquals("budget_exhausted", f.audit.end()["reason"])
        assertEquals(2, f.state.guiUsed)
        assertEquals(1, f.audit.recovery("scheduled").size)
        assertTrue(f.gateway.planner.isEmpty())
    }

    @Test fun threeCaptureFailuresAreBoundedEvenThoughNoGuiStepHasBeenSpent() = runTest {
        val f = Fixture(backgroundScope)
        repeat(3) { f.phone.observationFailures.add(screenshot()) }
        f.coordinator.start(options()); runCurrent()
        assertEquals(RunState.AUTO, f.state.state)
        assertEquals(0, f.state.guiUsed)
        assertRecovery(f, "OBSERVATION", 1, 1_000, "scheduled")
        advanceTimeBy(1_000); runCurrent()
        assertRecovery(f, "OBSERVATION", 2, 3_000, "scheduled")
        advanceTimeBy(3_000); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals("runtime_failure", f.audit.end()["reason"])
        assertEquals(3, f.phone.observations)
        assertEquals(0, f.state.guiUsed)
        assertTrue(f.gateway.gui.isEmpty())
        assertTrue(f.gateway.planner.isEmpty())
        assertNoBlockedState(f)
    }

    @Test fun anUnavailableCurrentWindowCanRecoverBeforeAnyGuiCall() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.observationFailures.add(RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE",
            executionReason = ExecutionFailureReason.SCREENSHOT_UNAVAILABLE))
        f.coordinator.start(options()); runCurrent()
        assertEquals(0, f.state.guiUsed)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, f.gateway.gui.size)
        assertEquals(1, f.state.guiUsed)
        assertEquals(2, f.phone.observations)
        assertNoBlockedState(f)
    }

    @Test fun aSuccessfulObservationResetsOnlyTheObservationFailureStreak() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.observationFailures.add(screenshot())
        f.coordinator.start(options()); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        f.phone.observationFailures.add(screenshot())
        f.gateway.replyGui(0, tap()); runCurrent()
        val scheduled = f.audit.recovery("scheduled")
        assertEquals(listOf(1, 1), scheduled.map { (it["attempt"] as Number).toInt() })
        assertEquals(listOf(1_000L, 1_000L), scheduled.map { (it["delay_ms"] as Number).toLong() })
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, f.gateway.gui.size)
        assertEquals(1, f.phone.performed.size)
        assertEquals(4, f.phone.observations)
    }

    @Test fun aValidGuiReplyResetsTheModelStreakWithoutReplayingEarlierSuccesses() = runTest {
        val f = started()
        f.gateway.failGui(0, invalid()); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        val successful = tap("SUCCESSFUL_ORIGINAL_ACTION")
        f.gateway.replyGui(1, successful); runCurrent()
        f.gateway.failGui(2, invalid()); runCurrent()
        val scheduled = f.audit.recovery("scheduled")
        assertEquals(listOf(1, 1), scheduled.map { (it["attempt"] as Number).toInt() })
        advanceTimeBy(1_000); runCurrent()
        assertEquals(4, f.gateway.gui.size)
        assertEquals(4, f.state.guiUsed)
        assertEquals(1, f.phone.performed.size)
        assertSame(successful.action, f.phone.performed.single())
        assertEquals(listOf(successful), f.gateway.gui.last().context.history.map { it.reply })
        assertTrue(f.gateway.planner.isEmpty())
    }

    @Test fun takingOverDuringBackoffCancelsRecoveryAndOffModeReturnsWithOriginalHistory() = runTest {
        val f = started(harness = false)
        val completed = tap("ORIGINAL_SUCCESS")
        f.gateway.replyGui(0, completed); runCurrent()
        f.gateway.failGui(1, invalid()); runCurrent()
        val observations = f.phone.observations
        f.coordinator.takeover(); runCurrent()
        assertEquals(RunState.MANUAL, f.state.state)
        assertFalse(f.phone.captureAllowed)
        assertEquals(1, f.audit.recovery("cancelled").size)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(observations, f.phone.observations)
        assertEquals(2, f.gateway.gui.size)
        f.coordinator.giveBack(); runCurrent()
        val continued = f.gateway.gui.last().context
        assertEquals(3, f.state.guiUsed)
        assertEquals(Phase.ORIGINAL, continued.phase)
        assertEquals(listOf(completed), continued.history.map { it.reply })
        assertEquals(continued.history.size, continued.returnNoticeIndex)
        assertTrue(continued.objective.isEmpty())
        assertTrue(f.gateway.planner.isEmpty())
        assertEquals(observations + 1, f.phone.observations)
        assertEquals(1, f.phone.performed.size)
    }

    @Test fun stoppingDuringBackoffCannotRestartObservationOrTheModelLater() = runTest {
        val f = started()
        f.gateway.failGui(0, invalid()); runCurrent()
        f.coordinator.stop(); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals("user_stop", f.audit.end()["reason"])
        assertEquals(1, f.audit.recovery("cancelled").size)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, f.gateway.gui.size)
        assertEquals(1, f.phone.observations)
        assertFalse(f.phone.captureAllowed)
    }

    @Test fun aLatePreHandoffFinishCannotEndTheNewAutomaticConversation() = runTest {
        val f = Fixture(backgroundScope)
        f.gateway.ignoreGuiCancellation.add(0)
        f.coordinator.start(options(harness = false)); runCurrent()
        f.coordinator.takeover(); runCurrent()
        f.coordinator.giveBack(); runCurrent()
        assertEquals(2, f.gateway.gui.size)
        f.gateway.replyGui(0, finish("unable")); runCurrent()
        assertEquals(RunState.AUTO, f.state.state)
        assertTrue(f.audit.of("session_ended").isEmpty())
        assertTrue(f.phone.performed.isEmpty())
        f.gateway.replyGui(1, finish()); runCurrent()
        assertEquals("model_declared_complete", f.audit.end()["reason"])
        assertEquals(2, f.state.guiUsed)
    }

    @Test fun readonlyProtocolRecoveryDoesNotRunInputOrOpenAnotherVerificationRound() = runTest {
        val f = returnedToPlanner(budget = 3)
        f.gateway.replyPlanner(0, decision("INCOMPLETE", "VERIFY")); runCurrent()
        assertEquals(Phase.VERIFY, f.state.phase)
        f.gateway.replyGui(1, tap("FORBIDDEN_READONLY_TAP")); runCurrent()
        assertTrue(f.phone.performed.isEmpty())
        assertEquals(1, f.state.plannerUsed)
        advanceTimeBy(1_000); runCurrent()
        val check = f.gateway.gui.last().context
        assertEquals(Phase.VERIFY, check.phase)
        assertEquals("CHECK_OR_REMAINING_GOAL", check.objective)
        assertTrue(check.history.isEmpty())
        f.gateway.replyGui(2, finish("unable")); runCurrent()
        assertEquals(3, f.state.guiUsed)
        assertEquals(2, f.state.plannerUsed)
        assertTrue(f.gateway.planner[1].context.verificationFailed)
        f.gateway.replyPlanner(1, PlannerDecision("COMPLETE", "FINISH", "", emptyList())); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals("planner", f.audit.end()["completion_source"])
        assertEquals(3, f.gateway.gui.size)
        assertEquals(2, f.gateway.planner.size)
        assertTrue(f.phone.performed.isEmpty())
        assertNoBlockedState(f)
    }

    @Test fun confirmationProtocolRecoveryStaysReadonlyAndCanFinishWithoutPlannerTwo() = runTest {
        val f = returnedToPlanner()
        f.gateway.replyPlanner(0, decision("COMPLETE", "VERIFY")); runCurrent()
        assertEquals(Phase.CONFIRM, f.state.phase)
        f.gateway.failGui(1, invalid()); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(Phase.CONFIRM, f.gateway.gui.last().context.phase)
        assertTrue(f.gateway.gui.last().context.history.isEmpty())
        f.gateway.replyGui(2, finish()); runCurrent()
        assertEquals("confirmation", f.audit.end()["completion_source"])
        assertEquals(1, f.gateway.planner.size)
        assertTrue(f.phone.performed.isEmpty())
    }

    @Test fun unsentPlannerTwoObservationMayRecoverAtZeroGuiBalanceAndStillFinishDirectly() = runTest {
        val f = returnedToPlanner(budget = 2)
        f.gateway.replyPlanner(0, decision("INCOMPLETE", "VERIFY")); runCurrent()
        f.phone.observationFailures.add(screenshot())
        f.gateway.replyGui(1, finish("unable")); runCurrent()
        assertEquals(2, f.state.guiUsed)
        assertEquals(1, f.state.plannerUsed)
        assertEquals(1, f.gateway.planner.size)
        assertRecovery(f, "PLANNER_OBSERVATION", 1, 1_000, "scheduled")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, f.gateway.planner.size)
        assertEquals(2, f.gateway.planner.last().context.callIndex)
        f.gateway.replyPlanner(1, PlannerDecision("COMPLETE", "FINISH", "", emptyList())); runCurrent()
        assertEquals("model_declared_complete", f.audit.end()["reason"])
        assertEquals("planner", f.audit.end()["completion_source"])
        assertEquals(2, f.gateway.gui.size)
        assertTrue(f.phone.performed.isEmpty())
    }

    @Test fun sentPlannerFailureFallsBackWithoutAPlannerRetryOrAThirdCall() = runTest {
        val f = returnedToPlanner()
        f.gateway.failPlanner(0, RunFailure("MODEL", "API_ERROR", httpStatus = 503)); runCurrent()
        assertEquals(1, f.state.plannerUsed)
        assertEquals(Phase.ORIGINAL, f.gateway.gui.last().context.phase)
        assertTrue(f.gateway.gui.last().context.objective.isEmpty())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, f.gateway.planner.size)
        assertTrue(f.audit.recovery("scheduled").isEmpty())
        f.gateway.replyGui(1, finish()); runCurrent()
        assertEquals("model_declared_complete", f.audit.end()["reason"])
    }

    @Test fun recoveredRecoveryExecutionRetainsItsPhaseGoalAndIsolatedHistory() = runTest {
        val f = returnedToPlanner()
        f.gateway.replyPlanner(0, decision("INCOMPLETE", "EXECUTE")); runCurrent()
        f.gateway.failGui(1, invalid()); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        val context = f.gateway.gui.last().context
        assertEquals(Phase.RECOVERY, context.phase)
        assertEquals("CHECK_OR_REMAINING_GOAL", context.objective)
        assertEquals(listOf("VISIBLE_CONDITION"), context.conditions)
        assertTrue(context.history.isEmpty())
        assertEquals(0, context.returnNoticeIndex)
        assertEquals(1, f.state.plannerUsed)
        assertEquals(3, f.state.guiUsed)
        assertEquals(1, f.gateway.planner.size)
        assertTrue(f.phone.performed.isEmpty())
    }

    @Test fun deviceNotReadyEndsWithItsOwnCauseRatherThanCallingTheModelOrPausing() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.observationFailures.add(RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE",
            executionReason = ExecutionFailureReason.DEVICE_NOT_READY))
        f.coordinator.start(options()); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals("runtime_failure", f.audit.end()["reason"])
        assertEquals("DEVICE", f.audit.of("error").single()["category"])
        assertTrue(f.gateway.gui.isEmpty())
        assertTrue(f.audit.of("recovery").isEmpty())
        assertNoBlockedState(f)
    }

    @Test fun aProtectedInputIsNotRetriedAsIfItWereAFailedCoordinateChoice() = runTest {
        val f = started()
        f.phone.nextResult = ActionResult("device_error", failureReason = ExecutionFailureReason.PROTECTED_INPUT)
        f.gateway.replyGui(0, tap()); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals("runtime_failure", f.audit.end()["reason"])
        assertEquals(1, f.phone.performed.size)
        assertTrue(f.audit.of("recovery").isEmpty())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, f.gateway.gui.size)
    }

    @Test fun anUnknownNativeInputStillWaitsForItsRealTerminalBeforeEnding() = runTest {
        val f = started()
        f.phone.nextResult = ActionResult("unknown", failureReason = ExecutionFailureReason.INPUT_RESULT_UNKNOWN)
        f.phone.keepNativePending = true
        f.gateway.replyGui(0, tap()); runCurrent()
        assertNotEquals(RunState.ENDED, f.state.state)
        assertTrue(f.audit.of("session_ended").isEmpty())
        advanceTimeBy(5_000); runCurrent()
        assertEquals(RunState.UNCERTAIN, f.state.state)
        assertEquals(1, f.gateway.gui.size)
        assertEquals(1, f.phone.performed.size)
        f.phone.inputInFlight = false
        f.coordinator.recheck(); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals("runtime_failure", f.audit.end()["reason"])
        assertTrue(f.audit.of("recovery").isEmpty())
    }

    @Test fun failureWhileReturningRemainsHumanControlledAndDoesNotSpendOrPlan() = runTest {
        val f = started()
        f.coordinator.takeover(); runCurrent()
        f.phone.observationFailures.add(screenshot())
        f.coordinator.giveBack(); runCurrent()
        assertEquals(RunState.MANUAL, f.state.state)
        assertFalse(f.state.handoffUsed)
        assertEquals(1, f.state.guiUsed)
        assertTrue(f.gateway.planner.isEmpty())
        assertTrue(f.audit.of("recovery").isEmpty())
        val count = f.phone.observations
        advanceTimeBy(10_000); runCurrent()
        assertEquals(count, f.phone.observations)
    }

    @Test fun recoveryMetadataDoesNotContainTaskKeysRepliesCoordinatesOrScreenContent() = runTest {
        val f = started()
        f.gateway.failGui(0, RunFailure("MODEL", "TRANSPORT_ERROR", transportReason = "TIMEOUT")); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        f.gateway.replyGui(1, finish()); runCurrent()
        val events = f.audit.events.toString()
        listOf("PRIVATE_ORIGINAL_TASK", "PRIVATE_KEY", "PRIVATE_REPLY_ID", "PRIVATE_REPORT", "PRIVATE_ARGUMENTS", "frame-")
            .forEach { assertFalse(it, events.contains(it)) }
        val ids = f.audit.recovery("scheduled").map { (it["recovery_id"] as Number).toLong() }
        assertEquals(listOf(1L), ids)
        assertEquals(listOf("scheduled", "started"), f.audit.of("recovery").map { it["status"] })
    }

    @Test fun auditFailureAtBeginEndsWithoutRunningAnUnrecordedTask() = runTest {
        val f = Fixture(backgroundScope)
        f.audit.failBegin = true
        f.coordinator.start(options()); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals(0, f.phone.observations)
        assertTrue(f.gateway.gui.isEmpty())
        assertTrue(f.audit.of("session_ended").isEmpty())
        assertFalse(f.phone.captureAllowed)
        assertNoBlockedState(f)
    }

    @Test fun auditFailureDuringUnknownInputCannotBypassTheNativeEndBarrier() = runTest {
        val f = started()
        f.audit.failEvent = "action"
        f.phone.nextResult = ActionResult("unknown", failureReason = ExecutionFailureReason.INPUT_RESULT_UNKNOWN)
        f.phone.keepNativePending = true
        f.gateway.replyGui(0, tap()); runCurrent()
        assertNotEquals(RunState.ENDED, f.state.state)
        assertFalse(f.phone.captureAllowed)
        assertEquals(1, f.gateway.gui.size)
        assertTrue(f.audit.of("session_ended").isEmpty())
        f.phone.inputInFlight = false
        f.coordinator.recheck(); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertTrue(f.audit.of("session_ended").isEmpty())
        assertTrue(f.audit.of("recovery").isEmpty())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, f.gateway.gui.size)
        assertEquals(1, f.phone.performed.size)
    }

    @Test fun legacyPolicyStillRequiresExplicitRetryAndEmitsNoNewRecoveryFields() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options().copy(automaticRecovery = false)); runCurrent()
        f.gateway.failGui(0, RunFailure("MODEL", "TRANSPORT_ERROR", transportReason = "TIMEOUT")); runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertTrue(f.audit.of("recovery").isEmpty())
        assertFalse(f.audit.of("error").single().containsKey("transport_reason"))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, f.gateway.gui.size)
        f.coordinator.retry(); runCurrent()
        assertEquals(2, f.gateway.gui.size)
        assertEquals(2, f.state.guiUsed)
        assertEquals(1, f.audit.of("control").count { it["transition"] == "retry_requested" })
    }

    private fun TestScope.started(harness: Boolean = true, budget: Int = 30): Fixture =
        Fixture(backgroundScope).also { it.coordinator.start(options(harness, budget)); runCurrent() }

    private fun TestScope.returnedToPlanner(budget: Int = 30): Fixture = started(budget = budget).also {
        it.coordinator.takeover(); runCurrent()
        assertEquals(RunState.MANUAL, it.state.state)
        it.coordinator.giveBack(); runCurrent()
        assertEquals(1, it.gateway.planner.size)
    }

    private fun assertRecovery(f: Fixture, target: String, attempt: Int, delay: Long, status: String) {
        assertTrue(f.audit.of("recovery").any {
            it["target"] == target && (it["attempt"] as? Number)?.toInt() == attempt &&
                (it["delay_ms"] as? Number)?.toLong() == delay && it["status"] == status
        })
    }

    private fun assertNoBlockedState(f: Fixture) {
        assertTrue(f.phone.states.none { it.state == RunState.BLOCKED })
    }

    private class Fixture(scope: CoroutineScope) {
        val phone = Phone()
        val gateway = Gateway()
        val audit = Audit()
        val coordinator = Coordinator(scope, gateway, phone, audit)
        val state get() = coordinator.snapshot.value
    }
    private data class GuiCall(val context: GuiContext, val result: CompletableDeferred<GuiReply> = CompletableDeferred())
    private data class PlannerCall(val context: PlannerContext, val result: CompletableDeferred<PlannerDecision> = CompletableDeferred())
    private class Gateway : ModelGateway {
        val gui = mutableListOf<GuiCall>()
        val planner = mutableListOf<PlannerCall>()
        val ignoreGuiCancellation = mutableSetOf<Int>()
        override suspend fun gui(context: GuiContext): GuiReply {
            val index = gui.size
            val call = GuiCall(context).also(gui::add)
            return if (index in ignoreGuiCancellation) withContext(NonCancellable) { call.result.await() }
                else call.result.await()
        }
        override suspend fun planner(context: PlannerContext): PlannerDecision = PlannerCall(context).also(planner::add).result.await()
        override fun cancelAll() = Unit
        fun failGui(index: Int, error: RunFailure) { check(gui[index].result.completeExceptionally(error)) }
        fun replyGui(index: Int, reply: GuiReply) { check(gui[index].result.complete(reply)) }
        fun failPlanner(index: Int, error: RunFailure) { check(planner[index].result.completeExceptionally(error)) }
        fun replyPlanner(index: Int, reply: PlannerDecision) { check(planner[index].result.complete(reply)) }
    }
    private class Phone : PhonePort {
        override var connected = true
        override var inputInFlight = false
        var keepNativePending = false
        var captureAllowed = false
        var observations = 0
        var nextResult = ActionResult("completed")
        val observationFailures = ArrayDeque<RunFailure>()
        val performed = mutableListOf<PhoneAction>()
        val states = mutableListOf<RunSnapshot>()
        override suspend fun observe(): ScreenFrame {
            check(captureAllowed)
            observations++
            if (observationFailures.isNotEmpty()) throw observationFailures.removeFirst()
            return ScreenFrame("frame-$observations", byteArrayOf(observations.toByte()), 1080, 2280)
        }
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult {
            check(canDispatch())
            performed.add(action)
            if (keepNativePending) inputInFlight = true
            return nextResult.also { nextResult = ActionResult("completed") }
        }
        override fun setCaptureEnabled(enabled: Boolean) { captureAllowed = enabled }
        override fun showState(snapshot: RunSnapshot) { states.add(snapshot) }
    }
    private class Audit : AuditSink {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        var failBegin = false
        var failEvent: String? = null
        override fun begin(sessionId: String, options: SessionOptions) { if (failBegin) throw IllegalStateException("SYNTHETIC_WRITE_FAILURE") }
        override fun event(event: String, data: Map<String, Any?>) {
            if (event == failEvent) throw IllegalStateException("SYNTHETIC_WRITE_FAILURE")
            events.add(event to data.toMap())
        }
        fun of(name: String) = events.filter { it.first == name }.map { it.second }
        fun recovery(status: String) = of("recovery").filter { it["status"] == status }
        fun end() = of("session_ended").last()
    }
    private companion object {
        fun options(harness: Boolean = true, budget: Int = 30) = SessionOptions(
            "PRIVATE_ORIGINAL_TASK", "synthetic.authorized", setOf("synthetic.authorized"), harness, budget,
            ModelProfile(apiKey = "PRIVATE_KEY"), ModelProfile(apiKey = "PRIVATE_KEY"),
        )
        fun invalid() = RunFailure("MODEL", "INVALID_OUTPUT", protocolReason = "COORDINATOR_REPLY_REJECTED")
        fun screenshot() = RunFailure("DEVICE", "SCREENSHOT_FAILED", executionReason = ExecutionFailureReason.SCREENSHOT_UNAVAILABLE)
        fun tap(id: String = "PRIVATE_REPLY_ID") = GuiReply(id, PhoneAction("tap", mapOf("x" to 500, "y" to 500)), "PRIVATE_ARGUMENTS")
        fun finish(outcome: String = "completed") = GuiReply("PRIVATE_REPLY_ID", PhoneAction("finish", mapOf("outcome" to outcome, "message" to "PRIVATE_REPORT")), "PRIVATE_ARGUMENTS")
        fun decision(status: String, branch: String) = PlannerDecision(status, branch, "CHECK_OR_REMAINING_GOAL", listOf("VISIBLE_CONDITION"))
    }
}
