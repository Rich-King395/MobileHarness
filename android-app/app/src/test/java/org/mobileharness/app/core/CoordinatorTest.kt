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

@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorTest {
    @Test fun autoHomeScopeIsFrozenBeforeCaptureAndRemovedOnlyAfterCaptureStops() = runTest {
        val f = Fixture(backgroundScope)
        val apps = mutableSetOf("PRIVATE_PACKAGE")
        val labels = mutableMapOf("PRIVATE_PACKAGE" to "Synthetic authorized App")
        val task = options().copy(initialPackage = "SYNTHETIC_HOME", authorizedPackages = apps,
            startMode = StartMode.HOME_AUTO, homePackage = "SYNTHETIC_HOME", authorizedAppLabels = labels)
        f.coordinator.start(task)
        runCurrent()
        apps.add("UNAUTHORIZED_LATER")
        labels["PRIVATE_PACKAGE"] = "CHANGED_LABEL"
        assertTrue(f.phone.scopeWasPresentAtObservation)
        val sent = f.gateway.guiCalls.single().context.options
        assertEquals(setOf("PRIVATE_PACKAGE"), sent.authorizedPackages)
        assertEquals("Synthetic authorized App", sent.authorizedAppLabels["PRIVATE_PACKAGE"])
        assertEquals(sent, f.phone.configured)
        assertEquals("1.2.0", sent.promptBundleVersion)
        assertEquals("1.1.0", options().promptBundleVersion)
        f.coordinator.stop()
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertNull(f.phone.configured)
        assertTrue(f.phone.scopeClearedWithCaptureDisabled)
    }

    @Test fun unauthorizedReturnKeepsManualWithoutPlannerBudgetOrLateInput() = runTest {
        val f = Fixture(backgroundScope)
        f.gateway.ignoreGuiCancellation.add(0)
        f.coordinator.start(options().copy(initialPackage = "SYNTHETIC_HOME", startMode = StartMode.HOME_AUTO,
            homePackage = "SYNTHETIC_HOME"))
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        assertEquals(RunState.MANUAL, f.state.state)
        val priorCaptures = f.phone.observations
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(priorCaptures, f.phone.observations)
        f.phone.rejectNextObservationAuthorization = true
        f.coordinator.giveBack()
        runCurrent()
        assertEquals(RunState.MANUAL, f.state.state)
        assertFalse(f.state.handoffUsed)
        assertEquals(1, f.state.guiUsed)
        assertEquals(0, f.state.plannerUsed)
        assertEquals(0, f.audit.transitions("return_committed"))
        assertFalse(f.phone.captureAllowed)
        f.gateway.replyGui(0, tap("STALE_HOME_REPLY"))
        runCurrent()
        assertEquals(0, f.phone.performCalls)
        f.coordinator.giveBack()
        runCurrent()
        assertTrue(f.state.handoffUsed)
        assertEquals(1, f.state.guiUsed)
        assertEquals(1, f.state.plannerUsed)
        assertEquals(1, f.audit.transitions("return_committed"))
    }

    @Test fun takeoverDoesNotWaitForHttpCancellationAndLateReplyCannotEnterHistory() = runTest {
        val f = Fixture(backgroundScope)
        f.gateway.ignoreGuiCancellation.add(0)
        f.coordinator.start(options(harness = false))
        runCurrent()
        assertEquals(1, f.state.guiUsed)
        f.coordinator.takeover()
        runCurrent()
        assertEquals(RunState.MANUAL, f.state.state)
        assertFalse(f.phone.captureAllowed)
        val captures = f.phone.observations
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(captures, f.phone.observations)
        f.coordinator.giveBack()
        runCurrent()
        assertEquals(2, f.state.guiUsed)
        f.gateway.guiCalls[0].result.complete(tap("OLD_REPLY"))
        runCurrent()
        assertEquals(0, f.phone.dispatches)
        assertTrue(f.gateway.guiCalls[1].context.history.isEmpty())
        assertEquals(2, f.state.guiUsed)
        assertEquals(1, f.audit.terminals("GUI", 1).size)
        assertEquals("cancelled", f.audit.terminals("GUI", 1).single()["status"])
    }

    @Test fun submittedInputNeedsItsTerminalAndStopIntentWinsAfterFiveSeconds() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.holdInput = true
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, tap())
        runCurrent()
        assertTrue(f.phone.inputInFlight)
        f.coordinator.takeover()
        runCurrent()
        assertEquals(RunState.PAUSING, f.state.state)
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(RunState.PAUSING, f.state.state)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(RunState.UNCERTAIN, f.state.state)
        f.coordinator.stop()
        runCurrent()
        assertEquals(RunState.UNCERTAIN, f.state.state)
        f.phone.completeInput(ActionResult("completed"))
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals("user_stop", f.audit.ended().getValue("reason"))
        assertEquals(0, f.audit.transitions("manual_granted"))
        assertEquals(1, f.state.guiUsed)
        assertEquals(1, f.phone.dispatches)
    }

    @Test fun ownershipEventQueuedBeforeDispatchRejectsAlreadyParsedCandidate() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options(harness = false))
        runCurrent()
        f.gateway.beforeGuiReturn = { f.coordinator.takeover() }
        f.gateway.replyGui(0, tap("PARSED_BUT_NEVER_EXECUTED"))
        runCurrent()
        assertEquals(RunState.MANUAL, f.state.state)
        assertEquals(0, f.phone.performCalls)
        f.gateway.beforeGuiReturn = null
        f.coordinator.giveBack()
        runCurrent()
        assertTrue(f.gateway.guiCalls[1].context.history.isEmpty())
    }

    @Test fun sharedBudgetIncludesCancelledOriginalWaitAndBothFinishDeclarations() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options(budget = 6))
        runCurrent()
        f.gateway.replyGui(0, tap("ORIGINAL_TURN"))
        runCurrent()
        assertEquals(2, f.state.guiUsed)
        f.coordinator.takeover()
        runCurrent()
        assertEquals(2, f.state.guiUsed)
        f.coordinator.giveBack()
        runCurrent()
        f.gateway.replyPlanner(0, decision("INCOMPLETE", "VERIFY"))
        runCurrent()
        assertEquals(Phase.VERIFY, f.gateway.guiCalls[2].context.phase)
        assertTrue(f.gateway.guiCalls[2].context.history.isEmpty())
        f.gateway.replyGui(2, waitReply())
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(4, f.state.guiUsed)
        assertEquals(listOf("wait"), f.gateway.guiCalls[3].context.history.map { it.reply.action.name })
        f.gateway.replyGui(3, finish("completed", "CHECK_ONLY"))
        runCurrent()
        assertEquals(2, f.state.plannerUsed)
        assertEquals(listOf("tap", "wait"), f.gateway.plannerCalls[1].context.completedActions.map { it.reply.action.name })
        f.gateway.replyPlanner(1, decision("INCOMPLETE", "EXECUTE", "REMAINING_ONLY"))
        runCurrent()
        val recovery = f.gateway.guiCalls[4].context
        assertEquals(Phase.RECOVERY, recovery.phase)
        assertTrue(recovery.history.isEmpty())
        assertEquals("REMAINING_ONLY", recovery.objective)
        assertEquals(0, recovery.returnNoticeIndex)
        f.gateway.replyGui(4, tap("RECOVERY_TURN"))
        runCurrent()
        assertEquals(6, f.state.guiUsed)
        f.gateway.replyGui(5, finish("completed"))
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals(6, f.state.guiUsed)
        assertEquals(2, f.state.plannerUsed)
        assertEquals(6, f.gateway.guiCalls.size)
        assertEquals("gui", f.audit.ended()["completion_source"])
        assertFalse(f.audit.events.any { it.first == "task_assessment" })
    }

    @Test fun secondPlannerFinishEndsWithZeroGuiBalanceFromBothLegalEntrances() = runTest {
        for (status in listOf("INCOMPLETE", "COMPLETE")) {
            val f = Fixture(backgroundScope)
            f.coordinator.start(options(budget = 2))
            runCurrent()
            f.coordinator.takeover()
            runCurrent()
            f.coordinator.giveBack()
            runCurrent()
            f.gateway.replyPlanner(0, decision(status, "VERIFY"))
            runCurrent()
            assertEquals(if (status == "COMPLETE") Phase.CONFIRM else Phase.VERIFY, f.state.phase)
            // A local capture failure before P2 is not a sent Planner attempt;
            // its already-valid finish entry can be resumed even at zero GUI balance.
            if (status == "INCOMPLETE") f.phone.failNextObservation = true
            f.gateway.replyGui(1, finish("unable", "UNVERIFIED_REPORT"))
            runCurrent()
            if (status == "INCOMPLETE") {
                assertEquals(RunState.BLOCKED, f.state.state)
                assertEquals(1, f.state.plannerUsed)
                f.coordinator.retry()
                runCurrent()
            }
            assertEquals(2, f.state.guiUsed)
            assertEquals(2, f.state.plannerUsed)
            val context = f.gateway.plannerCalls[1].context
            assertTrue(context.verificationFailed)
            assertEquals("UNVERIFIED_REPORT", context.checkReport)
            assertEquals(1, context.frames.size)
            assertEquals(context.currentFrameId, context.frames.single().id)
            f.gateway.replyPlanner(1, PlannerDecision("COMPLETE", "FINISH", "", emptyList()))
            runCurrent()
            assertEquals(RunState.ENDED, f.state.state)
            assertEquals(2, f.gateway.guiCalls.size)
            assertEquals(0, f.phone.dispatches)
            assertEquals("planner", f.audit.ended()["completion_source"])
            assertEquals(1, f.audit.actions("finish").size)
        }
    }

    @Test fun lastReadonlyWaitEndsForBudgetWithoutInventingFinishOrP2() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options(budget = 2))
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.coordinator.giveBack()
        runCurrent()
        f.gateway.replyPlanner(0, decision("INCOMPLETE", "VERIFY"))
        runCurrent()
        f.gateway.replyGui(1, waitReply())
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals(2, f.state.guiUsed)
        assertEquals(1, f.state.plannerUsed)
        assertEquals(1, f.gateway.plannerCalls.size)
        assertTrue(f.audit.actions("finish").isEmpty())
        assertEquals("budget_exhausted", f.audit.ended()["reason"])
    }

    @Test fun invalidReadonlyLastStepIsModelErrorAndCannotCreateP2Entry() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options(budget = 2))
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.coordinator.giveBack()
        runCurrent()
        f.gateway.replyPlanner(0, decision("INCOMPLETE", "VERIFY"))
        runCurrent()
        f.gateway.replyGui(1, tap())
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals(1, f.state.plannerUsed)
        assertEquals(0, f.phone.dispatches)
        assertTrue(f.audit.errors().any { it["category"] == "MODEL" && it["code"] == "INVALID_OUTPUT" })
        assertEquals("budget_exhausted", f.audit.ended()["reason"])
    }

    @Test fun firstPlannerFailureFallsBackToOriginalHistoryWithoutRepairCall() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, tap("ORIGINAL_TURN"))
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.coordinator.giveBack()
        runCurrent()
        f.gateway.plannerCalls[0].result.completeExceptionally(RunFailure("MODEL", "API_ERROR"))
        runCurrent()
        val fallback = f.gateway.guiCalls[2].context
        assertEquals(Phase.ORIGINAL, fallback.phase)
        assertEquals(listOf("ORIGINAL_TURN"), fallback.history.map { it.reply.callId })
        assertEquals(1, fallback.returnNoticeIndex)
        assertEquals("", fallback.objective)
        assertEquals(1, f.gateway.plannerCalls.size)
        assertEquals(1, f.state.plannerUsed)
        assertEquals(3, f.state.guiUsed)
    }

    @Test fun secondPlannerInvalidVerifyFallsBackWithoutAThirdPlannerOrCheckPhase() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options())
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.coordinator.giveBack()
        runCurrent()
        f.gateway.replyPlanner(0, decision("INCOMPLETE", "VERIFY"))
        runCurrent()
        f.gateway.replyGui(1, finish("completed", "CHECK_ONLY"))
        runCurrent()
        f.gateway.replyPlanner(1, decision("INCOMPLETE", "VERIFY"))
        runCurrent()
        assertEquals(2, f.gateway.plannerCalls.size)
        val fallback = f.gateway.guiCalls[2].context
        assertEquals(Phase.ORIGINAL, fallback.phase)
        assertTrue(fallback.history.isEmpty())
        assertEquals("", fallback.objective)
        assertEquals(2, f.state.plannerUsed)
    }

    @Test fun successfulConfirmationEndsWithoutSecondPlanner() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options())
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.coordinator.giveBack()
        runCurrent()
        f.gateway.replyPlanner(0, decision("COMPLETE", "VERIFY"))
        runCurrent()
        assertEquals(Phase.CONFIRM, f.gateway.guiCalls[1].context.phase)
        assertTrue(f.gateway.guiCalls[1].context.history.isEmpty())
        f.gateway.replyGui(1, finish("completed"))
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals(1, f.gateway.plannerCalls.size)
        assertEquals("confirmation", f.audit.ended()["completion_source"])
    }

    @Test fun preflightFailureDoesNotChargeAnAttemptAndExplicitRetryDoes() = runTest {
        val f = Fixture(backgroundScope)
        f.gateway.guiPreflightFailure = RunFailure("COORDINATOR", "OBSERVATION_UNAVAILABLE")
        f.coordinator.start(options())
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(0, f.state.guiUsed)
        assertEquals(0, f.gateway.guiCalls.size)
        assertTrue(f.audit.events.none { it.first == "model_call" })
        f.coordinator.retry()
        runCurrent()
        assertEquals(1, f.state.guiUsed)
        assertEquals(1, f.gateway.guiCalls.size)
        assertSame(f.gateway.guiPreflights.last(), f.gateway.guiCalls[0].context)
    }

    @Test fun retryAfterCheckingErrorPreservesReadonlyPhaseAndPlannerCount() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options())
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.coordinator.giveBack()
        runCurrent()
        f.gateway.replyPlanner(0, decision("INCOMPLETE", "VERIFY"))
        runCurrent()
        f.gateway.replyGui(1, tap())
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(Phase.VERIFY, f.state.phase)
        f.coordinator.retry()
        runCurrent()
        assertEquals(Phase.VERIFY, f.gateway.guiCalls[2].context.phase)
        assertEquals(1, f.state.plannerUsed)
        assertEquals(3, f.state.guiUsed)
        assertTrue(f.gateway.guiCalls[2].context.history.isEmpty())
    }

    @Test fun stopInvalidatesLatePlannerFinishAndDoesNotTurnStopIntoCompletion() = runTest {
        val f = Fixture(backgroundScope)
        f.gateway.ignorePlannerCancellation.add(0)
        f.coordinator.start(options())
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.coordinator.giveBack()
        runCurrent()
        f.coordinator.stop()
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        f.gateway.replyPlanner(0, decision("INCOMPLETE", "EXECUTE"))
        runCurrent()
        assertEquals(1, f.gateway.guiCalls.size)
        assertEquals(1, f.state.plannerUsed)
        assertEquals("user_stop", f.audit.ended()["reason"])
        assertEquals(1, f.audit.terminals("PLANNER", 1).size)
    }

    @Test fun returningScreenshotFailureKeepsManualAndDoesNotConsumeTheCycle() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options())
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.phone.failNextObservation = true
        f.coordinator.giveBack()
        runCurrent()
        assertEquals(RunState.MANUAL, f.state.state)
        assertFalse(f.state.handoffUsed)
        assertEquals(0, f.state.plannerUsed)
        assertEquals(1, f.state.guiUsed)
        f.coordinator.giveBack()
        runCurrent()
        assertTrue(f.state.handoffUsed)
        assertEquals(1, f.state.plannerUsed)
        f.coordinator.giveBack()
        runCurrent()
        assertEquals(1, f.gateway.plannerCalls.size)
    }

    @Test fun secondTakeoverSafelyEndsRatherThanOfferingAnotherReturn() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options(harness = false))
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.coordinator.giveBack()
        runCurrent()
        assertTrue(f.state.handoffUsed)
        f.coordinator.takeover()
        runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals("user_stop", f.audit.ended()["reason"])
        val calls = f.gateway.guiCalls.size
        f.coordinator.giveBack()
        runCurrent()
        assertEquals(calls, f.gateway.guiCalls.size)
        assertEquals(1, f.audit.transitions("manual_granted"))
    }

    @Test fun onOffKeepTheSameTaskReturnFrameBudgetAndNoticeButUseDifferentLegalHistories() = runTest {
        val samples = mutableListOf<GuiContext>()
        for (harness in listOf(false, true)) {
            val f = Fixture(backgroundScope)
            f.coordinator.start(options(harness = harness))
            runCurrent()
            f.gateway.replyGui(0, tap("ORIGINAL_SENTINEL"))
            runCurrent()
            f.coordinator.takeover()
            runCurrent()
            f.coordinator.giveBack()
            runCurrent()
            if (harness) {
                f.gateway.replyPlanner(0, decision("INCOMPLETE", "EXECUTE"))
                runCurrent()
            }
            samples.add(f.gateway.guiCalls[2].context)
            assertEquals(3, f.state.guiUsed)
            assertEquals(if (harness) 1 else 0, f.state.plannerUsed)
            f.coordinator.stop()
            runCurrent()
        }
        val (off, on) = samples
        assertEquals(off.options.task, on.options.task)
        assertEquals(off.frame.id, on.frame.id)
        assertArrayEquals(off.frame.png, on.frame.png)
        assertEquals(off.options.budget, on.options.budget)
        assertNotNull(off.returnNoticeIndex)
        assertNotNull(on.returnNoticeIndex)
        assertEquals(Phase.ORIGINAL, off.phase)
        assertEquals(listOf("ORIGINAL_SENTINEL"), off.history.map { it.reply.callId })
        assertEquals(Phase.RECOVERY, on.phase)
        assertTrue(on.history.isEmpty())
        assertEquals("", off.objective)
    }

    @Test fun completedPreTakeoverInputIsRetainedOnlyAfterItsTerminal() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.holdInput = true
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, tap("ACTUALLY_EXECUTED"))
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.phone.completeInput(ActionResult("completed"))
        runCurrent()
        assertEquals(RunState.MANUAL, f.state.state)
        f.coordinator.giveBack()
        runCurrent()
        assertEquals(listOf("ACTUALLY_EXECUTED"), f.gateway.plannerCalls.single().context.completedActions.map { it.reply.callId })
        assertEquals(1, f.state.guiUsed)
    }

    @Test fun runtimeAuditContainsOnlyMetadataNotTaskArgumentsReportsOrReplies() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, GuiReply("PRIVATE_CALL_ID", PhoneAction("input_text", mapOf("text" to "PRIVATE_TEXT")), "PRIVATE_ARGUMENT_JSON"))
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.coordinator.stop()
        runCurrent()
        val metadata = f.audit.events.toString()
        for (sentinel in listOf("PRIVATE_TASK", "PRIVATE_CALL_ID", "PRIVATE_TEXT", "PRIVATE_ARGUMENT_JSON", "PRIVATE_PACKAGE")) {
            assertFalse(sentinel, metadata.contains(sentinel))
        }
    }

    @Test fun environmentFailureWaitsForInputThenBlocksWithoutPretendingUserStop() = runTest {
        val f = Fixture(backgroundScope)
        f.phone.holdInput = true
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, tap("EXECUTED_BEFORE_DEVICE_FAILURE"))
        runCurrent()
        f.phone.connected = false
        f.coordinator.environmentUnavailable("AUTHORIZATION_UNAVAILABLE")
        runCurrent()
        assertEquals(RunState.PAUSING, f.state.state)
        assertFalse(f.phone.captureAllowed)
        assertTrue(f.audit.events.none { it.first == "session_ended" })
        assertEquals(0, f.audit.transitions("stop_requested"))
        f.phone.completeInput(ActionResult("completed"))
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(1, f.state.guiUsed)
        assertTrue(f.audit.errors().any { it["category"] == "DEVICE" && it["code"] == "AUTHORIZATION_UNAVAILABLE" })
        f.phone.connected = true
        f.phone.holdInput = false
        f.coordinator.retry()
        runCurrent()
        assertEquals(Phase.ORIGINAL, f.gateway.guiCalls[1].context.phase)
        assertEquals(listOf("EXECUTED_BEFORE_DEVICE_FAILURE"), f.gateway.guiCalls[1].context.history.map { it.reply.callId })
        assertEquals(2, f.state.guiUsed)
    }

    @Test fun environmentCancellingSentP1CanOnlyRetryAsOriginalFallback() = runTest {
        val f = Fixture(backgroundScope)
        f.gateway.ignorePlannerCancellation.add(0)
        f.coordinator.start(options())
        runCurrent()
        f.gateway.replyGui(0, tap("ORIGINAL_HISTORY"))
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.coordinator.giveBack()
        runCurrent()
        assertEquals(1, f.state.plannerUsed)
        f.coordinator.environmentUnavailable("STOP_ENTRY_UNAVAILABLE")
        runCurrent()
        assertEquals(RunState.BLOCKED, f.state.state)
        assertEquals(0, f.audit.transitions("stop_requested"))
        assertTrue(f.audit.errors().any { it["category"] == "COORDINATOR" && it["code"] == "STOP_ENTRY_UNAVAILABLE" })
        f.coordinator.retry()
        runCurrent()
        assertEquals(1, f.gateway.plannerCalls.size)
        assertEquals(1, f.state.plannerUsed)
        val resumed = f.gateway.guiCalls[2].context
        assertEquals(Phase.ORIGINAL, resumed.phase)
        assertEquals("", resumed.objective)
        assertEquals(listOf("ORIGINAL_HISTORY"), resumed.history.map { it.reply.callId })
        f.gateway.replyPlanner(0, decision("INCOMPLETE", "EXECUTE", "STALE_TARGET"))
        runCurrent()
        assertEquals(1, f.gateway.plannerCalls.size)
        assertEquals(3, f.gateway.guiCalls.size)
        assertEquals(1, f.audit.terminals("PLANNER", 1).size)
        assertEquals("cancelled", f.audit.terminals("PLANNER", 1).single()["status"])
    }

    private class Fixture(scope: CoroutineScope) {
        val phone = FakePhone()
        val gateway = FakeGateway()
        val audit = FakeAudit()
        val coordinator = Coordinator(scope, gateway, phone, audit)
        val state get() = coordinator.snapshot.value
    }

    private data class GuiCall(val context: GuiContext, val result: CompletableDeferred<GuiReply> = CompletableDeferred())
    private data class PlannerCall(val context: PlannerContext, val result: CompletableDeferred<PlannerDecision> = CompletableDeferred())

    private class FakeGateway : ModelGateway {
        val guiCalls = mutableListOf<GuiCall>()
        val plannerCalls = mutableListOf<PlannerCall>()
        val guiPreflights = mutableListOf<GuiContext>()
        val ignoreGuiCancellation = mutableSetOf<Int>()
        val ignorePlannerCancellation = mutableSetOf<Int>()
        var guiPreflightFailure: RunFailure? = null
        var beforeGuiReturn: (() -> Unit)? = null
        override fun preflightGui(context: GuiContext) {
            guiPreflights.add(context)
            guiPreflightFailure?.let { guiPreflightFailure = null; throw it }
        }
        override suspend fun gui(context: GuiContext): GuiReply {
            val index = guiCalls.size
            val call = GuiCall(context).also(guiCalls::add)
            val reply = if (index in ignoreGuiCancellation) withContext(NonCancellable) { call.result.await() } else call.result.await()
            beforeGuiReturn?.invoke()
            return reply
        }
        override suspend fun planner(context: PlannerContext): PlannerDecision {
            val index = plannerCalls.size
            val call = PlannerCall(context).also(plannerCalls::add)
            return if (index in ignorePlannerCancellation) withContext(NonCancellable) { call.result.await() } else call.result.await()
        }
        override fun cancelAll() = Unit // Deliberately no cancellation acknowledgement.
        fun replyGui(index: Int, reply: GuiReply) { guiCalls[index].result.complete(reply) }
        fun replyPlanner(index: Int, reply: PlannerDecision) { plannerCalls[index].result.complete(reply) }
    }

    private class FakePhone : PhonePort {
        override var connected = true
        override var inputInFlight = false
        var captureAllowed = false
        var observations = 0
        var dispatches = 0
        var performCalls = 0
        var holdInput = false
        var failNextObservation = false
        var rejectNextObservationAuthorization = false
        var configured: SessionOptions? = null
        var scopeWasPresentAtObservation = false
        var scopeClearedWithCaptureDisabled = true
        private var inputResult: CompletableDeferred<ActionResult>? = null
        val states = mutableListOf<RunSnapshot>()
        override fun configureSession(options: SessionOptions?) {
            if (options == null) scopeClearedWithCaptureDisabled = scopeClearedWithCaptureDisabled && !captureAllowed
            configured = options
        }
        override suspend fun observe(): ScreenFrame {
            check(captureAllowed)
            scopeWasPresentAtObservation = configured != null
            observations++
            if (rejectNextObservationAuthorization) {
                rejectNextObservationAuthorization = false
                throw RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE")
            }
            if (failNextObservation) { failNextObservation = false; throw RunFailure("DEVICE", "SCREENSHOT_FAILED") }
            return ScreenFrame("frame-$observations", byteArrayOf(observations.toByte()), 1080, 2280)
        }
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult {
            performCalls++
            if (!canDispatch()) return ActionResult("rejected")
            dispatches++
            if (!holdInput) return ActionResult("completed")
            inputInFlight = true
            return CompletableDeferred<ActionResult>().also { inputResult = it }.await()
        }
        fun completeInput(result: ActionResult) {
            inputInFlight = false
            checkNotNull(inputResult).complete(result)
        }
        override fun setCaptureEnabled(enabled: Boolean) { captureAllowed = enabled }
        override fun showState(snapshot: RunSnapshot) { states.add(snapshot) }
    }

    private class FakeAudit : AuditSink {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun begin(sessionId: String, options: SessionOptions) = Unit
        override fun event(event: String, data: Map<String, Any?>) { events.add(event to data.toMap()) }
        fun ended() = events.last { it.first == "session_ended" }.second
        fun transitions(name: String) = events.count { it.first == "control" && it.second["transition"] == name }
        fun terminals(role: String, index: Int) = events.filter {
            it.first == "model_call" && it.second["role"] == role && it.second["call_index"] == index && it.second["status"] != "started"
        }.map { it.second }
        fun actions(name: String) = events.filter { it.first == "action" && it.second["action_type"] == name }.map { it.second }
        fun errors() = events.filter { it.first == "error" }.map { it.second }
    }

    private companion object {
        // These frozen assertions intentionally exercise legacy execution policy 1.1.
        fun options(harness: Boolean = true, budget: Int = 30) = SessionOptions(
            task = "PRIVATE_TASK {{literal-placeholder}}", initialPackage = "PRIVATE_PACKAGE",
            authorizedPackages = setOf("PRIVATE_PACKAGE"), harness = harness, budget = budget,
            gui = ModelProfile(apiKey = ""), planner = ModelProfile(apiKey = ""), automaticRecovery = false,
        )
        fun tap(id: String = "tap-id") = GuiReply(id, PhoneAction("tap", mapOf("x" to 500, "y" to 500)), "{}")
        fun waitReply() = GuiReply("wait-id", PhoneAction("wait", mapOf("duration_ms" to 100)), "{}")
        fun finish(outcome: String, report: String = "SYNTHETIC_REPORT") = GuiReply("finish-id", PhoneAction("finish", mapOf("outcome" to outcome, "message" to report)), "{}")
        fun decision(status: String, branch: String, objective: String = "SYNTHETIC_GOAL") = PlannerDecision(status, branch, objective, listOf("SYNTHETIC_VISIBLE_CONDITION"))
    }
}
