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

/** Contract variants omitted by the original smoke tests. All data and time are synthetic. */
@OptIn(ExperimentalCoroutinesApi::class)
class HarnessBudgetAcceptanceTest {
    @Test fun bp02SentModelFailuresAndDeviceRejectionsChargeExactlyOneAttempt() = runTest {
        for (failure in listOf("TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT", "INVALID_TYPED_REPLY",
            "rejected", "device_error", "unknown")) {
            val f = Fixture(backgroundScope)
            f.coordinator.start(options())
            runCurrent()
            repeat(7) { f.gateway.guiCalls.last().reply.complete(tap()); runCurrent() }
            assertEquals(8, f.state.guiUsed)
            assertEquals(8, f.gateway.guiCalls.size)
            val performedBefore = f.phone.performCalls
            when (failure) {
                "TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT" ->
                    f.gateway.guiCalls.last().reply.completeExceptionally(RunFailure("MODEL", failure))
                "INVALID_TYPED_REPLY" -> f.gateway.guiCalls.last().reply.complete(
                    GuiReply("invalid", PhoneAction("tap", mapOf("x" to 1000, "y" to 500)), "{}"))
                else -> {
                    f.phone.nextResult = ActionResult(failure)
                    f.gateway.guiCalls.last().reply.complete(tap())
                }
            }
            runCurrent()
            assertEquals(RunState.BLOCKED, f.state.state)
            assertEquals(8, f.state.guiUsed)
            assertEquals(8, f.gateway.guiCalls.size)
            assertEquals(if (failure in setOf("rejected", "device_error", "unknown")) performedBefore + 1 else performedBefore,
                f.phone.performCalls)
            assertEquals(1, f.audit.terminals("GUI", 8).size)
            val errors = f.audit.of("error")
            assertEquals(if (failure in setOf("rejected", "device_error", "unknown")) "DEVICE" else "MODEL",
                errors.last()["category"])
            advanceTimeBy(2_000); runCurrent()
            assertEquals(8, f.gateway.guiCalls.size)
            val observations = f.phone.observations
            f.coordinator.retry(); runCurrent()
            assertEquals(9, f.state.guiUsed)
            assertEquals(9, f.gateway.guiCalls.size)
            assertTrue(f.phone.observations > observations)
            assertEquals(1, f.audit.transition("retry_requested"))
            close(f)
        }
    }

    @Test fun bp03ObservationAndBothPreflightsRejectBeforeAnyModelAttempt() = runTest {
        for (mode in listOf("GUI_OBSERVATION", "GUI_PREFLIGHT", "PLANNER_PREFLIGHT")) {
            val f = Fixture(backgroundScope)
            if (mode == "GUI_OBSERVATION") f.phone.nextObservationFailure = RunFailure("DEVICE", "SCREENSHOT_FAILED")
            if (mode == "GUI_PREFLIGHT") f.gateway.guiPreflightFailure = RunFailure("COORDINATOR", "OBSERVATION_UNAVAILABLE")
            f.coordinator.start(options()); runCurrent()
            if (mode == "PLANNER_PREFLIGHT") {
                f.coordinator.takeover(); runCurrent()
                f.gateway.plannerPreflightFailure = RunFailure("COORDINATOR", "OBSERVATION_UNAVAILABLE")
                f.coordinator.giveBack(); runCurrent()
                assertEquals(1, f.state.guiUsed)
                assertEquals(0, f.state.plannerUsed)
                assertTrue(f.gateway.plannerCalls.isEmpty())
                assertTrue(f.audit.of("model_call").none { it["role"] == "PLANNER" })
                f.coordinator.retry(); runCurrent()
                assertEquals(1, f.state.plannerUsed)
                assertEquals(1, f.gateway.plannerCalls.size)
                assertEquals(1, f.state.guiUsed)
            } else {
                assertEquals(0, f.state.guiUsed)
                assertEquals(0, f.state.plannerUsed)
                assertTrue(f.gateway.guiCalls.isEmpty())
                assertTrue(f.audit.of("model_call").isEmpty())
                f.coordinator.retry(); runCurrent()
                assertEquals(1, f.state.guiUsed)
                assertEquals(1, f.gateway.guiCalls.size)
            }
            close(f)
        }
    }

    @Test fun bp04LastConfiguredStepAcceptsFinishOrEndsAfterOrdinaryActionWithoutFreeDone() = runTest {
        for (budget in listOf(6, 30)) for (recovery in listOf(false, true)) for (outcome in listOf("completed", "unable", "ordinary")) {
            val f = Fixture(backgroundScope)
            if (recovery) {
                returned(f, budget)
                f.gateway.plannerCalls.last().reply.complete(decision("INCOMPLETE", "EXECUTE")); runCurrent()
            } else { f.coordinator.start(options(budget)); runCurrent() }
            while (f.state.guiUsed < budget) { f.gateway.guiCalls.last().reply.complete(tap()); runCurrent() }
            assertEquals(budget, f.state.guiUsed)
            val performed = f.phone.performCalls
            f.gateway.guiCalls.last().reply.complete(if (outcome == "ordinary") tap() else finish(outcome))
            runCurrent()
            assertEquals(RunState.ENDED, f.state.state)
            assertEquals(budget, f.state.guiUsed)
            assertEquals(budget, f.gateway.guiCalls.size)
            assertEquals(performed + if (outcome == "ordinary") 1 else 0, f.phone.performCalls)
            assertEquals(if (outcome == "ordinary") 0 else 1, f.audit.actions("finish").size)
            assertEquals(when (outcome) { "ordinary" -> "budget_exhausted"; "unable" -> "model_unable"; else -> "model_declared_complete" },
                f.audit.ended()["reason"])
            f.coordinator.retry(); f.coordinator.giveBack(); runCurrent()
            assertEquals(budget, f.gateway.guiCalls.size)
            assertFalse(f.phone.captureAllowed)
        }
    }

    @Test fun bp05FirstPlannerLegalBranchesEnterExactlyTheirSpecifiedPhase() = runTest {
        for ((status, branch) in listOf("COMPLETE" to "VERIFY", "INCOMPLETE" to "VERIFY", "UNVERIFIABLE" to "VERIFY",
            "INCOMPLETE" to "EXECUTE", "INCOMPLETE" to "FALLBACK", "UNVERIFIABLE" to "FALLBACK")) {
            val f = Fixture(backgroundScope)
            returned(f)
            f.gateway.plannerCalls.single().reply.complete(decision(status, branch)); runCurrent()
            val context = f.gateway.guiCalls.last().context
            assertEquals(when (branch) { "VERIFY" -> if (status == "COMPLETE") Phase.CONFIRM else Phase.VERIFY
                "EXECUTE" -> Phase.RECOVERY; else -> Phase.ORIGINAL }, context.phase)
            assertEquals(1, f.state.plannerUsed)
            assertEquals(2, f.state.guiUsed)
            assertEquals(1, f.gateway.plannerCalls.size)
            assertEquals(if (branch == "FALLBACK") "" else "SYNTHETIC_GOAL", context.objective)
            assertTrue(context.history.isEmpty())
            assertTrue(f.audit.of("session_ended").isEmpty())
            close(f)
        }
    }

    @Test fun bp06MultipleWaitsRemainOneReadonlyStageAndEveryWriteToolIsRejected() = runTest {
        val f = Fixture(backgroundScope)
        checking(f)
        repeat(2) {
            f.gateway.guiCalls.last().reply.complete(waitReply()); runCurrent()
            advanceTimeBy(100); runCurrent()
            assertEquals(Phase.VERIFY, f.state.phase)
            assertEquals(1, f.state.plannerUsed)
            assertEquals(it + 1, f.gateway.guiCalls.last().context.history.size)
        }
        f.gateway.guiCalls.last().reply.complete(finish("completed")); runCurrent()
        assertEquals(4, f.state.guiUsed)
        assertEquals(2, f.state.plannerUsed)
        assertEquals(1, f.audit.of("planner_decision").count { it["decision"] == "VERIFY" })
        assertEquals(0, f.phone.performCalls)
        close(f)

        val writes = listOf(tap(), action("swipe", mapOf("from_x" to 1, "from_y" to 1, "to_x" to 2, "to_y" to 2, "duration_ms" to 100)),
            action("long_press", mapOf("x" to 500, "y" to 500, "duration_ms" to 300)),
            action("input_text", mapOf("text" to "SYNTHETIC")), action("press_key", mapOf("key" to "back")),
            action("launch_app", mapOf("package_name" to "SYNTHETIC_APP")))
        for (write in writes) for (status in listOf("INCOMPLETE", "COMPLETE")) {
            val blocked = Fixture(backgroundScope)
            checking(blocked, status)
            blocked.gateway.guiCalls.last().reply.complete(write); runCurrent()
            assertEquals(RunState.BLOCKED, blocked.state.state)
            assertEquals(if (status == "COMPLETE") Phase.CONFIRM else Phase.VERIFY, blocked.state.phase)
            assertEquals(2, blocked.state.guiUsed)
            assertEquals(1, blocked.state.plannerUsed)
            assertEquals(0, blocked.phone.performCalls)
            assertEquals("INVALID_OUTPUT", blocked.audit.of("error").last()["code"])
            close(blocked)
        }
    }

    @Test fun bp07ReadonlyFinishesHaveFourDistinctMeaningsAndNoAutomaticSuccessLabel() = runTest {
        for (status in listOf("INCOMPLETE", "COMPLETE")) for (outcome in listOf("completed", "unable")) {
            val f = Fixture(backgroundScope)
            checking(f, status)
            f.gateway.guiCalls.last().reply.complete(finish(outcome)); runCurrent()
            if (status == "COMPLETE" && outcome == "completed") {
                assertEquals(RunState.ENDED, f.state.state)
                assertEquals(1, f.state.plannerUsed)
                assertEquals("confirmation", f.audit.ended()["completion_source"])
            } else {
                assertEquals(2, f.state.plannerUsed)
                assertEquals(2, f.gateway.plannerCalls.size)
                val p2 = f.gateway.plannerCalls.last().context
                assertEquals(outcome == "unable", p2.verificationFailed)
                assertEquals("SYNTHETIC_REPORT", p2.checkReport)
                assertEquals(1, p2.frames.size)
                assertEquals(p2.currentFrameId, p2.frames.single().id)
                assertNull(p2.preHandoffFrameId)
                assertTrue(f.audit.of("session_ended").isEmpty())
            }
            assertTrue(f.audit.of("task_assessment").isEmpty())
            assertEquals(0, f.phone.performCalls)
            close(f)
        }
    }

    @Test fun bp08SecondPlannerLegalAndInvalidMatrixCannotOpenAnotherCheckOrP3() = runTest {
        val cases = listOf(
            decision("COMPLETE", "FINISH"), decision("INCOMPLETE", "EXECUTE"),
            decision("INCOMPLETE", "FALLBACK"), decision("UNVERIFIABLE", "FALLBACK"),
            decision("INCOMPLETE", "VERIFY"), decision("UNVERIFIABLE", "EXECUTE"), decision("COMPLETE", "EXECUTE"),
            PlannerDecision("COMPLETE", "FINISH", "ILLEGAL_NONEMPTY", emptyList()),
            PlannerDecision("COMPLETE", "FINISH", "", listOf("ILLEGAL_CONDITION")))
        for (entryStatus in listOf("INCOMPLETE", "COMPLETE")) for (candidate in cases) {
            val f = Fixture(backgroundScope)
            secondPlanner(f, entryStatus)
            val guiBefore = f.gateway.guiCalls.size
            f.gateway.plannerCalls.last().reply.complete(candidate); runCurrent()
            val valid = candidate == decision("COMPLETE", "FINISH") || candidate.status == "INCOMPLETE" && candidate.decision in setOf("EXECUTE", "FALLBACK") ||
                candidate.status == "UNVERIFIABLE" && candidate.decision == "FALLBACK"
            assertEquals(2, f.state.plannerUsed)
            assertEquals(2, f.gateway.plannerCalls.size)
            assertEquals(1, f.audit.of("planner_decision").count { it["decision"] == "VERIFY" })
            if (valid && candidate.decision == "FINISH") {
                assertEquals(RunState.ENDED, f.state.state)
                assertEquals(guiBefore, f.gateway.guiCalls.size)
                assertEquals("planner", f.audit.ended()["completion_source"])
            } else {
                assertEquals(guiBefore + 1, f.gateway.guiCalls.size)
                assertEquals(if (valid && candidate.decision == "EXECUTE") Phase.RECOVERY else Phase.ORIGINAL,
                    f.gateway.guiCalls.last().context.phase)
                if (!valid) assertTrue(f.audit.of("error").any { it["category"] == "MODEL" && it["code"] == "INVALID_OUTPUT" })
            }
            close(f)
        }
    }

    @Test fun bp09SecondPlannerAtZeroBalanceCanOnlyFinishAndNeverDispatchGuiDone() = runTest {
        for (entryStatus in listOf("INCOMPLETE", "COMPLETE")) for (branch in listOf("FINISH", "EXECUTE", "FALLBACK")) {
            val f = Fixture(backgroundScope)
            secondPlanner(f, entryStatus, budget = 2)
            assertEquals(2, f.state.guiUsed)
            val observations = f.phone.observations
            val finishEvents = f.audit.actions("finish").size
            f.gateway.plannerCalls.last().reply.complete(decision(if (branch == "FINISH") "COMPLETE" else "INCOMPLETE", branch)); runCurrent()
            assertEquals(RunState.ENDED, f.state.state)
            assertEquals(2, f.state.guiUsed)
            assertEquals(2, f.state.plannerUsed)
            assertEquals(2, f.gateway.guiCalls.size)
            assertEquals(observations, f.phone.observations)
            assertEquals(0, f.phone.performCalls)
            assertEquals(finishEvents, f.audit.actions("finish").size)
            assertEquals(if (branch == "FINISH") "model_declared_complete" else "budget_exhausted", f.audit.ended()["reason"])
            assertEquals(if (branch == "FINISH") "planner" else "none", f.audit.ended()["completion_source"])
            assertTrue(f.audit.of("task_assessment").isEmpty())
        }
    }

    @Test fun bp10PlannerErrorsFallbackWithoutRepairCallsAndNeverRefundOrdinals() = runTest {
        for (ordinal in listOf(1, 2)) for (code in listOf("TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT")) {
            val f = Fixture(backgroundScope)
            if (ordinal == 1) returned(f) else secondPlanner(f)
            val currentFrame = f.gateway.plannerCalls.last().context.currentFrameId
            f.gateway.plannerCalls.last().reply.completeExceptionally(RunFailure("MODEL", code)); runCurrent()
            assertEquals(ordinal, f.state.plannerUsed)
            assertEquals(ordinal, f.gateway.plannerCalls.size)
            val next = f.gateway.guiCalls.last().context
            assertEquals(Phase.ORIGINAL, next.phase)
            assertEquals(currentFrame, next.frame.id)
            assertEquals("", next.objective)
            assertTrue(f.audit.of("error").any { it["category"] == "MODEL" && it["code"] == code })
            val gui = f.gateway.guiCalls.size
            advanceTimeBy(2_000); runCurrent()
            assertEquals(gui, f.gateway.guiCalls.size)
            assertEquals(ordinal, f.gateway.plannerCalls.size)
            close(f)
        }
    }

    @Test fun bp10FailedSecondPlannerWithNoGuiBalanceEndsWithoutARepairOrFallbackCall() = runTest {
        for (code in listOf("TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT")) {
            val f = Fixture(backgroundScope)
            secondPlanner(f, budget = 2)
            f.gateway.plannerCalls.last().reply.completeExceptionally(RunFailure("MODEL", code)); runCurrent()
            assertEquals(RunState.ENDED, f.state.state)
            assertEquals(2, f.gateway.plannerCalls.size)
            assertEquals(2, f.gateway.guiCalls.size)
            assertEquals(2, f.state.guiUsed)
            assertEquals(2, f.state.plannerUsed)
            assertEquals(0, f.phone.performCalls)
            assertEquals("budget_exhausted", f.audit.ended()["reason"])
            assertEquals("none", f.audit.ended()["completion_source"])
            assertTrue(f.audit.of("error").any { it["category"] == "MODEL" && it["code"] == code })
        }
    }

    @Test fun bp11DeliveredExecuteAndFallbackNeverReplanAfterActionsErrorsRetriesOrUnable() = runTest {
        for (ordinal in listOf(1, 2)) for (branch in listOf("EXECUTE", "FALLBACK")) for (errorKind in listOf("MODEL", "DEVICE")) for (outcome in listOf("completed", "unable")) {
            val f = Fixture(backgroundScope)
            if (ordinal == 1) returned(f) else secondPlanner(f)
            f.gateway.plannerCalls.last().reply.complete(decision("INCOMPLETE", branch)); runCurrent()
            f.gateway.guiCalls.last().reply.complete(tap()); runCurrent()
            val priorG = f.state.guiUsed
            if (errorKind == "MODEL") f.gateway.guiCalls.last().reply.completeExceptionally(RunFailure("MODEL", "INVALID_OUTPUT"))
            else {
                f.phone.nextResult = ActionResult("device_error")
                f.gateway.guiCalls.last().reply.complete(tap())
            }
            runCurrent()
            assertEquals(RunState.BLOCKED, f.state.state)
            assertEquals(priorG, f.state.guiUsed)
            assertEquals(ordinal, f.state.plannerUsed)
            f.coordinator.retry(); runCurrent()
            assertEquals(priorG + 1, f.state.guiUsed)
            assertEquals(if (branch == "EXECUTE") Phase.RECOVERY else Phase.ORIGINAL, f.gateway.guiCalls.last().context.phase)
            assertEquals(ordinal, f.gateway.plannerCalls.size)
            f.gateway.guiCalls.last().reply.complete(finish(outcome)); runCurrent()
            assertEquals(RunState.ENDED, f.state.state)
            assertEquals(if (outcome == "unable") "model_unable" else "model_declared_complete", f.audit.ended()["reason"])
            assertEquals(ordinal, f.state.plannerUsed)
            assertEquals(ordinal, f.gateway.plannerCalls.size)
            assertTrue(f.audit.of("error").none { it["code"] == "INVALID_OUTPUT" && errorKind == "DEVICE" })
            val calls = f.gateway.guiCalls.size
            f.coordinator.retry(); f.coordinator.giveBack(); runCurrent()
            assertEquals(calls, f.gateway.guiCalls.size)
            assertEquals(ordinal, f.gateway.plannerCalls.size)
        }
    }

    @Test fun bp12DuplicateReturnsAtEveryPreparationBoundaryCannotConsumeAnotherPlanner() = runTest {
        val f = Fixture(backgroundScope)
        returned(f)
        repeat(3) { f.coordinator.giveBack() }; runCurrent()
        assertEquals(1, f.gateway.plannerCalls.size)
        f.gateway.plannerCalls.last().reply.complete(decision("INCOMPLETE", "VERIFY")); runCurrent()
        repeat(3) { f.coordinator.giveBack() }; runCurrent()
        assertEquals(1, f.gateway.plannerCalls.size)
        f.gateway.guiCalls.last().reply.complete(finish("completed")); runCurrent()
        repeat(3) { f.coordinator.giveBack() }; runCurrent()
        assertEquals(2, f.gateway.plannerCalls.size)
        f.gateway.plannerCalls.last().reply.complete(decision("INCOMPLETE", "EXECUTE")); runCurrent()
        repeat(3) { f.coordinator.giveBack() }; runCurrent()
        assertEquals(2, f.gateway.plannerCalls.size)
        assertEquals(3, f.state.guiUsed)
        assertEquals(1, f.audit.transition("return_committed"))
        assertEquals(1, f.audit.of("planner_decision").count { it["decision"] == "VERIFY" })
        assertEquals(2, f.audit.of("planner_decision").size)
        close(f)
    }

    @Test fun bp13LateSecondPlannerCompleteCannotOverrideUserStopOrCreateGuiDone() = runTest {
        val f = Fixture(backgroundScope)
        f.gateway.ignorePlannerCancellation.add(1)
        secondPlanner(f)
        val stale = f.gateway.plannerCalls.last().reply
        f.coordinator.stop(); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        val gui = f.gateway.guiCalls.size
        val actions = f.phone.performCalls
        assertTrue(stale.complete(decision("COMPLETE", "FINISH")))
        assertFalse(stale.complete(decision("COMPLETE", "FINISH")))
        runCurrent()
        assertEquals(gui, f.gateway.guiCalls.size)
        assertEquals(actions, f.phone.performCalls)
        assertEquals(2, f.state.plannerUsed)
        assertEquals("user_stop", f.audit.ended()["reason"])
        assertEquals("none", f.audit.ended()["completion_source"])
        assertEquals(1, f.audit.terminals("PLANNER", 2).size)
        assertEquals("cancelled", f.audit.terminals("PLANNER", 2).single()["status"])
        assertTrue(f.audit.of("planner_decision").none { it["planner_call_index"] == 2 })
    }

    @Test fun bp14BothCheckPhasesAndFailureVariantsCannotInventFinishAtExhaustion() = runTest {
        for (status in listOf("INCOMPLETE", "COMPLETE")) for (last in listOf("wait", "error", "invalid")) {
            val f = Fixture(backgroundScope)
            checking(f, status, budget = 2)
            when (last) {
                "wait" -> f.gateway.guiCalls.last().reply.complete(waitReply())
                "error" -> f.gateway.guiCalls.last().reply.completeExceptionally(RunFailure("MODEL", "API_ERROR"))
                else -> f.gateway.guiCalls.last().reply.complete(tap())
            }
            runCurrent()
            if (last == "wait") { advanceTimeBy(100); runCurrent() }
            assertEquals(RunState.ENDED, f.state.state)
            assertEquals(2, f.state.guiUsed)
            assertEquals(1, f.state.plannerUsed)
            assertEquals(1, f.gateway.plannerCalls.size)
            assertEquals(2, f.gateway.guiCalls.size)
            assertEquals(0, f.phone.performCalls)
            assertTrue(f.audit.actions("finish").isEmpty())
            assertEquals("budget_exhausted", f.audit.ended()["reason"])
            assertEquals("none", f.audit.ended()["completion_source"])
            if (last != "wait") assertTrue(f.audit.of("error").any { it["category"] == "MODEL" })
            assertFalse(f.phone.captureAllowed)
            assertNull(f.phone.configured)
            f.coordinator.retry(); runCurrent()
            assertEquals(1, f.gateway.plannerCalls.size)
            assertEquals(2, f.gateway.guiCalls.size)
        }
    }

    @Test fun bp15ValidUnableAndCompletionAreNotErrorsOrIndependentSuccessAssessments() = runTest {
        for (outcome in listOf("completed", "unable")) {
            val f = Fixture(backgroundScope)
            f.coordinator.start(options()); runCurrent()
            f.gateway.guiCalls.single().reply.complete(finish(outcome)); runCurrent()
            assertEquals(RunState.ENDED, f.state.state)
            assertTrue(f.audit.of("error").isEmpty())
            assertTrue(f.audit.of("task_assessment").isEmpty())
            assertEquals(if (outcome == "completed") "model_declared_complete" else "model_unable", f.audit.ended()["reason"])
            assertEquals("gui", f.audit.ended()["completion_source"])
            val metadata = f.audit.events.toString()
            assertFalse(metadata.contains("SYNTHETIC_REPORT"))
            assertFalse(metadata.contains("SYNTHETIC_PRIVATE_TASK"))
        }
    }

    private fun TestScope.returned(f: Fixture, budget: Int = 30) {
        f.coordinator.start(options(budget)); runCurrent()
        f.coordinator.takeover(); runCurrent()
        assertEquals(RunState.MANUAL, f.state.state)
        val before = f.state.guiUsed
        val observations = f.phone.observations
        advanceTimeBy(500); runCurrent()
        assertEquals(before, f.state.guiUsed)
        assertEquals(observations, f.phone.observations)
        f.coordinator.giveBack(); runCurrent()
        assertEquals(1, f.state.plannerUsed)
    }
    private fun TestScope.checking(f: Fixture, status: String = "INCOMPLETE", budget: Int = 30) {
        returned(f, budget)
        f.gateway.plannerCalls.last().reply.complete(decision(status, "VERIFY")); runCurrent()
        assertEquals(if (status == "COMPLETE") Phase.CONFIRM else Phase.VERIFY, f.state.phase)
    }
    private fun TestScope.secondPlanner(f: Fixture, status: String = "INCOMPLETE", budget: Int = 30) {
        checking(f, status, budget)
        f.gateway.guiCalls.last().reply.complete(finish(if (status == "COMPLETE") "unable" else "completed")); runCurrent()
        assertEquals(2, f.state.plannerUsed)
    }
    private fun TestScope.close(f: Fixture) { f.coordinator.stop(); runCurrent() }

    private class Fixture(scope: CoroutineScope) {
        val phone = Phone()
        val gateway = Gateway()
        val audit = Audit()
        val coordinator = Coordinator(scope, gateway, phone, audit)
        val state get() = coordinator.snapshot.value
    }
    private data class GuiCall(val context: GuiContext, val reply: CompletableDeferred<GuiReply> = CompletableDeferred())
    private data class PlannerCall(val context: PlannerContext, val reply: CompletableDeferred<PlannerDecision> = CompletableDeferred())
    private class Gateway : ModelGateway {
        val guiCalls = mutableListOf<GuiCall>()
        val plannerCalls = mutableListOf<PlannerCall>()
        val ignorePlannerCancellation = mutableSetOf<Int>()
        var guiPreflightFailure: RunFailure? = null
        var plannerPreflightFailure: RunFailure? = null
        override fun preflightGui(context: GuiContext) { guiPreflightFailure?.let { guiPreflightFailure = null; throw it } }
        override fun preflightPlanner(context: PlannerContext) { plannerPreflightFailure?.let { plannerPreflightFailure = null; throw it } }
        override suspend fun gui(context: GuiContext): GuiReply = GuiCall(context).also(guiCalls::add).reply.await()
        override suspend fun planner(context: PlannerContext): PlannerDecision {
            val index = plannerCalls.size
            val call = PlannerCall(context).also(plannerCalls::add)
            return if (index in ignorePlannerCancellation) withContext(NonCancellable) { call.reply.await() } else call.reply.await()
        }
        override fun cancelAll() = Unit
    }
    private class Phone : PhonePort {
        override var connected = true
        override val inputInFlight = false
        var captureAllowed = false
        var configured: SessionOptions? = null
        var observations = 0
        var performCalls = 0
        var nextResult = ActionResult("completed")
        var nextObservationFailure: RunFailure? = null
        override fun configureSession(options: SessionOptions?) { configured = options }
        override suspend fun observe(): ScreenFrame {
            check(captureAllowed)
            nextObservationFailure?.let { nextObservationFailure = null; throw it }
            observations++
            return ScreenFrame("budget-frame-$observations", byteArrayOf(observations.toByte()), 1080, 2280)
        }
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult {
            performCalls++
            check(canDispatch())
            return nextResult.also { nextResult = ActionResult("completed") }
        }
        override fun setCaptureEnabled(enabled: Boolean) { captureAllowed = enabled }
        override fun showState(snapshot: RunSnapshot) = Unit
    }
    private class Audit : AuditSink {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun begin(sessionId: String, options: SessionOptions) = Unit
        override fun event(event: String, data: Map<String, Any?>) { events.add(event to data.toMap()) }
        fun of(event: String) = events.filter { it.first == event }.map { it.second }
        fun ended() = of("session_ended").last()
        fun actions(name: String) = of("action").filter { it["action_type"] == name }
        fun transition(name: String) = of("control").count { it["transition"] == name }
        fun terminals(role: String, ordinal: Int) = of("model_call").filter { it["role"] == role && it["call_index"] == ordinal && it["status"] != "started" }
    }
    private companion object {
        // These frozen assertions intentionally exercise legacy execution policy 1.1.
        fun options(budget: Int = 30) = SessionOptions("SYNTHETIC_PRIVATE_TASK", "SYNTHETIC_APP", setOf("SYNTHETIC_APP"), true,
            budget, ModelProfile(), ModelProfile(), automaticRecovery = false)
        fun action(name: String, args: Map<String, Any?>) = GuiReply("synthetic-$name", PhoneAction(name, args), "{}")
        fun tap() = action("tap", mapOf("x" to 500, "y" to 500))
        fun waitReply() = action("wait", mapOf("duration_ms" to 100))
        fun finish(outcome: String) = action("finish", mapOf("outcome" to outcome, "message" to "SYNTHETIC_REPORT"))
        fun decision(status: String, branch: String) = if (branch == "FINISH") PlannerDecision(status, branch, "", emptyList())
            else PlannerDecision(status, branch, "SYNTHETIC_GOAL", listOf("SYNTHETIC_CONDITION"))
    }
}
