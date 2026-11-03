package org.mobileharness.app.core

import com.google.gson.JsonObject
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
import org.mobileharness.app.model.ModelRequestAssembler
import org.mobileharness.app.model.PromptBundles
import org.mobileharness.app.model.StrictJson
import java.io.File
import java.util.Base64

/** Deterministic C/A acceptance only: synthetic frames, controllable callbacks, no Android or HTTP. */
@OptIn(ExperimentalCoroutinesApi::class)
class HarnessInformationAcceptanceTest {
    // CT-01/02, HX-10. Four exact delivery boundaries, both switches, all three stale actions.
    @Test fun ct02AllLateGuiShapesAtAllFourBoundariesStayOutsideEffectiveHistory() = runTest {
        for (harness in listOf(false, true)) for (arrival in Arrival.entries) for (shape in 0..2) {
            val f = Fixture(backgroundScope)
            f.gateway.ignoreGuiCancellation.add(1)
            f.coordinator.start(options(harness))
            runCurrent()
            f.gateway.answerGui(0, tap(ORIGINAL))
            runCurrent()
            val old = f.gateway.guiCalls[1]
            val stale = when (shape) {
                0 -> tap("CT02_STALE_TAP")
                1 -> finish("completed", "CT02_STALE_COMPLETED", "CT02_STALE_TEXT")
                else -> finish("unable", "CT02_STALE_UNABLE", "CT02_STALE_TEXT")
            }
            var arrivals = 0
            fun deliver() {
                arrivals++
                assertTrue(old.result.complete(stale))
                assertFalse(old.result.complete(stale))
            }
            if (arrival == Arrival.PAUSING) f.phone.onState = {
                if (it.state == RunState.PAUSING && arrivals == 0) deliver()
            }
            f.coordinator.takeover()
            runCurrent()
            assertEquals(RunState.MANUAL, f.state.state)
            assertEquals(1, f.audit.transitions("manual_granted"))
            assertFalse(f.phone.captureAllowed)
            val manualCounts = f.counts()
            if (arrival == Arrival.MANUAL) deliver()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(manualCounts, f.counts())
            if (arrival == Arrival.RETURN_COMMITTED) f.audit.onEvent = { name, data ->
                if (name == "control" && data["transition"] == "return_committed") {
                    assertEquals(2, f.gateway.guiCalls.size)
                    assertTrue(f.gateway.plannerCalls.isEmpty())
                    deliver()
                }
            }
            f.coordinator.giveBack()
            runCurrent()
            if (harness) {
                val planning = f.gateway.plannerCalls.single().context
                assertEquals(listOf(ORIGINAL), planning.completedActions.map { it.reply.callId })
                f.gateway.answerPlanner(0, decision("INCOMPLETE", "EXECUTE", "CURRENT_REMAINING_WORK"))
                runCurrent()
            }
            if (arrival == Arrival.NEW_REQUEST) deliver()
            runCurrent()
            assertEquals(1, arrivals)
            val after = f.counts()
            assertFalse(old.result.complete(stale)) // A repeated callback cannot complete this attempt twice.
            runCurrent()
            assertEquals(after, f.counts())
            assertEquals(3, f.state.guiUsed)
            assertEquals(if (harness) 1 else 0, f.state.plannerUsed)
            assertEquals(1, f.phone.dispatches)
            assertEquals(3, f.phone.observations)
            assertEquals(1, f.audit.transitions("return_committed"))
            assertEquals(1, f.audit.terminals("GUI", 2).size)
            assertEquals("cancelled", f.audit.terminals("GUI", 2).single()["status"])
            assertTrue(f.audit.events.none { it.first == "session_ended" })
            val resumed = f.gateway.guiCalls[2].context
            assertEquals(if (harness) Phase.RECOVERY else Phase.ORIGINAL, resumed.phase)
            assertEquals(if (harness) emptyList<String>() else listOf(ORIGINAL), resumed.history.map { it.reply.callId })
            assertTrue(f.gateway.guiJson.none { it.toString().contains("CT02_STALE") })
            assertTrue(f.gateway.plannerJson.none { it.toString().contains("CT02_STALE") })
            f.coordinator.stop()
            runCurrent()
        }
    }

    // CT-04: genuine completion and cancellation both close the input barrier exactly once.
    @Test fun ct04BothNativeTerminalKindsAreRequiredBeforeManualAndOnlyCompletedActionsReachPlanner() = runTest {
        for (terminal in listOf("completed", "cancelled")) {
            val f = Fixture(backgroundScope)
            f.phone.holdInput = true
            f.coordinator.start(options())
            runCurrent()
            f.gateway.answerGui(0, tap("CT04_SUBMITTED"))
            runCurrent()
            assertTrue(f.phone.inputInFlight)
            f.coordinator.takeover()
            runCurrent()
            val counts = f.counts()
            assertEquals(RunState.PAUSING, f.state.state)
            assertEquals(0, f.audit.transitions("manual_granted"))
            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(RunState.PAUSING, f.state.state)
            assertEquals(counts, f.counts())
            assertFalse(f.phone.captureAllowed)
            assertTrue(f.phone.completeInput(ActionResult(terminal)))
            runCurrent()
            assertEquals(RunState.MANUAL, f.state.state)
            assertEquals(1, f.audit.transitions("manual_granted"))
            assertFalse(f.phone.completeInput(ActionResult(terminal)))
            runCurrent()
            assertEquals(counts, f.counts())
            assertEquals(1, f.audit.transitions("manual_granted"))
            f.coordinator.giveBack()
            runCurrent()
            val own = f.gateway.plannerCalls.single().context.completedActions.map { it.reply.callId }
            assertEquals(if (terminal == "completed") listOf("CT04_SUBMITTED") else emptyList<String>(), own)
            assertEquals(1, f.state.guiUsed)
            f.coordinator.stop()
            runCurrent()
        }
    }

    // CT-07/HX-09/10: late capture at pause/manual/after a fresh return, with and without pre-image.
    @Test fun ct07LateScreenshotsNeverBecomeCurrentOrBoundaryAndManualHasNoCaptureStarts() = runTest {
        for (hasPreFrame in listOf(false, true)) for (arrival in listOf(Arrival.PAUSING, Arrival.MANUAL, Arrival.NEW_REQUEST)) {
            val f = Fixture(backgroundScope)
            val oldOrdinal = if (hasPreFrame) 2 else 1
            val staleGate = f.phone.holdObservation(oldOrdinal)
            f.coordinator.start(options())
            runCurrent()
            if (hasPreFrame) {
                f.gateway.answerGui(0, tap(ORIGINAL))
                runCurrent()
            }
            val old = frame(oldOrdinal, "CT07_STALE_IMAGE")
            var delivered = false
            fun deliver() {
                delivered = true
                assertTrue(staleGate.complete(old))
                assertFalse(staleGate.complete(old))
            }
            if (arrival == Arrival.PAUSING) f.phone.onState = {
                if (it.state == RunState.PAUSING && !delivered) deliver()
            }
            f.coordinator.takeover()
            runCurrent()
            assertEquals(RunState.MANUAL, f.state.state)
            val manualCounts = f.counts()
            if (arrival == Arrival.MANUAL) deliver()
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(manualCounts, f.counts())
            assertFalse(f.phone.captureAllowed)
            f.coordinator.giveBack()
            runCurrent()
            val first = f.gateway.plannerCalls.single().context
            val returned = first.frames.single { it.id == first.currentFrameId }
            assertNotEquals(old.id, returned.id)
            assertEquals(oldOrdinal + 1, f.phone.observations)
            assertEquals(if (hasPreFrame) "frame-1" else null, first.preHandoffFrameId)
            assertEquals(if (hasPreFrame) 2 else 1, first.frames.size)
            assertTrue(first.frames.none { it === old || it.id == old.id })
            if (arrival == Arrival.NEW_REQUEST) deliver()
            runCurrent()
            assertTrue(delivered)
            val counts = f.counts()
            assertFalse(staleGate.complete(old))
            runCurrent()
            assertEquals(counts, f.counts())
            f.gateway.answerPlanner(0, decision("INCOMPLETE", "VERIFY", "CT07_READ_CURRENT"))
            runCurrent()
            val gui = f.gateway.guiCalls.last().context
            assertSame(returned, gui.frame)
            assertTrue(gui.history.isEmpty())
            assertSingleImage(f.gateway.guiJson.last(), returned)
            assertTrue(f.gateway.guiJson.none { it.toString().contains("CT07_STALE_IMAGE") })
            assertTrue(f.gateway.plannerJson.none { it.toString().contains("CT07_STALE_IMAGE") })
            assertNull(privateField(f.coordinator, "beforeHandoff"))
            f.coordinator.stop()
            runCurrent()
            assertNull(privateField(f.coordinator, "current"))
            assertNull(privateField(f.coordinator, "beforeHandoff"))
        }
    }

    // CT-07 failure variants and CT-09 duplicate return: precheck vs attempted capture are separate.
    @Test fun ct07FailedReturnPreservesCycleAndExplicitReturnGetsOneFreshFrame() = runTest {
        for (harness in listOf(false, true)) for (captureFailure in listOf(false, true)) {
            val f = Fixture(backgroundScope)
            f.coordinator.start(options(harness))
            runCurrent()
            f.coordinator.takeover()
            runCurrent()
            val baseline = f.counts()
            if (captureFailure) f.phone.failNextObservation = true else f.phone.connected = false
            f.coordinator.giveBack()
            runCurrent()
            assertEquals(RunState.MANUAL, f.state.state)
            assertFalse(f.state.handoffUsed)
            assertEquals(baseline.gui, f.state.guiUsed)
            assertEquals(baseline.planner, f.state.plannerUsed)
            assertEquals(baseline.observations + if (captureFailure) 1 else 0, f.phone.observations)
            assertEquals(0, f.audit.transitions("return_committed"))
            f.phone.connected = true
            f.coordinator.giveBack()
            f.coordinator.giveBack()
            runCurrent()
            assertTrue(f.state.handoffUsed)
            assertEquals(1, f.audit.transitions("return_committed"))
            assertEquals(baseline.observations + if (captureFailure) 2 else 1, f.phone.observations)
            assertEquals(if (harness) 1 else 0, f.gateway.plannerCalls.size)
            val returned = if (harness) f.gateway.plannerCalls.single().context.currentFrameId else f.gateway.guiCalls.last().context.frame.id
            assertEquals("frame-${f.phone.observations}", returned)
            val finalCounts = f.counts()
            repeat(3) { f.coordinator.giveBack() }
            runCurrent()
            assertEquals(finalCounts, f.counts())
            f.coordinator.stop()
            runCurrent()
        }
    }

    // CT-08: cancelled waits cannot refresh now or after the new generation is running.
    @Test fun ct08OldWaitDeadlinesCannotCaptureOrAppendHistoryBeforeOrAfterReturn() = runTest {
        for (harness in listOf(false, true)) {
            val f = Fixture(backgroundScope)
            f.coordinator.start(options(harness))
            runCurrent()
            f.gateway.answerGui(0, waitReply("CT08_OLD_WAIT", 1_000))
            runCurrent()
            advanceTimeBy(499)
            runCurrent()
            f.coordinator.takeover()
            runCurrent()
            assertEquals(RunState.MANUAL, f.state.state)
            val manual = f.counts()
            repeat(2) { advanceTimeBy(2_000); runCurrent(); assertEquals(manual, f.counts()) }
            assertEquals(0, f.audit.actions("wait"))
            f.coordinator.giveBack()
            runCurrent()
            if (harness) {
                f.gateway.answerPlanner(0, decision("INCOMPLETE", "EXECUTE", "CT08_NEW_WORK"))
                runCurrent()
            }
            val resumed = f.counts()
            repeat(2) { advanceTimeBy(5_000); f.coordinator.recheck(); runCurrent(); assertEquals(resumed, f.counts()) }
            assertEquals(1, f.audit.transitions("manual_granted"))
            assertEquals(1, f.audit.transitions("return_committed"))
            assertEquals(0, f.audit.actions("wait"))
            assertTrue(f.gateway.guiJson.none { it.toString().contains("CT08_OLD_WAIT") })
            assertTrue(f.gateway.plannerJson.none { it.toString().contains("CT08_OLD_WAIT") })
            assertEquals(2, f.state.guiUsed)
            f.coordinator.stop()
            runCurrent()
        }
    }

    // HX-01/02/03/07/09: coordinator output is fed through the actual assembler on every attempt.
    @Test fun hx01To03ReadonlyAndRecoveryConversationsAccumulateOnlyTheirOwnTurns() = runTest {
        for (confirmation in listOf(false, true)) {
            val f = Fixture(backgroundScope)
            f.coordinator.start(options())
            runCurrent()
            f.gateway.answerGui(0, tap(ORIGINAL))
            runCurrent()
            f.coordinator.takeover()
            runCurrent()
            f.coordinator.giveBack()
            runCurrent()
            f.gateway.answerPlanner(0, decision(if (confirmation) "COMPLETE" else "INCOMPLETE", "VERIFY", "HX_CHECK_OBJECTIVE"))
            runCurrent()
            val firstCheck = f.gateway.guiCalls[2].context
            assertEquals(if (confirmation) Phase.CONFIRM else Phase.VERIFY, firstCheck.phase)
            assertTrue(firstCheck.history.isEmpty())
            assertEquals("HX_CHECK_OBJECTIVE", firstCheck.objective)
            assertEquals(options().task, firstCheck.options.task)
            assertReadOnly(f.gateway.guiJson[2])
            assertSingleImage(f.gateway.guiJson[2], firstCheck.frame)
            assertNoMarkers(f.gateway.guiJson[2], ORIGINAL, "HX_PRIVATE_EVALUATOR", "HX_HUMAN_TRAJECTORY")
            f.gateway.answerGui(2, waitReply("HX_CHECK_WAIT"))
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            val secondCheck = f.gateway.guiCalls[3].context
            assertEquals(listOf("HX_CHECK_WAIT"), secondCheck.history.map { it.reply.callId })
            assertNotEquals(firstCheck.frame.id, secondCheck.frame.id)
            assertReadOnly(f.gateway.guiJson[3])
            assertSingleImage(f.gateway.guiJson[3], secondCheck.frame)
            assertNoMarkers(f.gateway.guiJson[3], ORIGINAL, "HX_PRIVATE_EVALUATOR", "HX_HUMAN_TRAJECTORY")
            f.gateway.answerGui(3, finish("unable", "HX_CHECK_FINISH", "HX_UNVERIFIED_CHECK_REPORT"))
            runCurrent()
            val p2 = f.gateway.plannerCalls[1].context
            assertTrue(p2.verificationFailed)
            assertEquals("HX_UNVERIFIED_CHECK_REPORT", p2.checkReport)
            assertEquals(1, p2.frames.size)
            assertNull(p2.preHandoffFrameId)
            assertEquals(p2.currentFrameId, p2.frames.single().id)
            assertTrue(p2.frames.none { it.id == firstCheck.frame.id || it.id == secondCheck.frame.id })
            f.gateway.answerPlanner(1, decision("INCOMPLETE", "EXECUTE", "HX_REMAINING_ALL_WORK"))
            runCurrent()
            val firstRecovery = f.gateway.guiCalls[4].context
            assertEquals(Phase.RECOVERY, firstRecovery.phase)
            assertTrue(firstRecovery.history.isEmpty())
            assertEquals("HX_REMAINING_ALL_WORK", firstRecovery.objective)
            assertEquals(options().task, firstRecovery.options.task)
            assertEquals(5, f.state.guiUsed)
            assertEquals(2, f.state.plannerUsed)
            assertNoMarkers(f.gateway.guiJson[4], ORIGINAL, "HX_CHECK_WAIT", "HX_CHECK_FINISH", "HX_UNVERIFIED_CHECK_REPORT", "HX_PRIVATE_EVALUATOR")
            assertSingleImage(f.gateway.guiJson[4], firstRecovery.frame)
            f.gateway.answerGui(4, tap("HX_RECOVERY_OWN_ACTION"))
            runCurrent()
            val next = f.gateway.guiCalls[5].context
            assertEquals(listOf("HX_RECOVERY_OWN_ACTION"), next.history.map { it.reply.callId })
            assertEquals("HX_REMAINING_ALL_WORK", next.objective)
            assertNoMarkers(f.gateway.guiJson[5], ORIGINAL, "HX_CHECK_WAIT", "HX_UNVERIFIED_CHECK_REPORT")
            assertSingleImage(f.gateway.guiJson[5], next.frame)
            assertEquals(2, f.gateway.plannerCalls.size)
            f.coordinator.stop()
            runCurrent()
        }
    }

    // HX-04/07/08: fallback changes history, never the already-edited phone state.
    @Test fun hx04SecondPlannerFallbackRestoresOriginalPairsAtCurrentFrameWithoutUndo() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options())
        runCurrent()
        f.gateway.answerGui(0, tap(ORIGINAL))
        runCurrent()
        f.coordinator.takeover()
        runCurrent()
        f.phone.businessValue = "HX_HUMAN_EDIT_RETAINED" // A private fake state; never a trajectory or model input.
        f.coordinator.giveBack()
        runCurrent()
        f.gateway.answerPlanner(0, decision("INCOMPLETE", "VERIFY", "HX_CHECK_OBJECTIVE"))
        runCurrent()
        f.gateway.answerGui(2, waitReply("HX_CHECK_WAIT"))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        f.gateway.answerGui(3, finish("completed", "HX_CHECK_FINISH", "HX_CHECK_REPORT"))
        runCurrent()
        val fresh = f.gateway.plannerCalls[1].context.frames.single()
        val before = f.counts()
        f.gateway.answerPlanner(1, decision("INCOMPLETE", "FALLBACK", "HX_FALLBACK_TEXT_MUST_NOT_LEAK"))
        runCurrent()
        val resumed = f.gateway.guiCalls[4].context
        assertEquals(Phase.ORIGINAL, resumed.phase)
        assertEquals("", resumed.objective)
        assertTrue(resumed.conditions.isEmpty())
        assertEquals(listOf(ORIGINAL), resumed.history.map { it.reply.callId })
        assertSame(fresh, resumed.frame)
        assertEquals(before.dispatches, f.phone.dispatches)
        assertEquals(before.observations, f.phone.observations)
        assertEquals("HX_HUMAN_EDIT_RETAINED", f.phone.businessValue)
        assertEquals(before.gui + 1, f.state.guiUsed)
        assertNoMarkers(f.gateway.guiJson[4], "HX_CHECK_WAIT", "HX_CHECK_FINISH", "HX_CHECK_REPORT", "HX_FALLBACK_TEXT_MUST_NOT_LEAK", "HX_HUMAN_EDIT_RETAINED")
        assertNativePairs(f.gateway.guiJson[4], listOf(ORIGINAL))
        assertSingleImage(f.gateway.guiJson[4], fresh)
        f.coordinator.stop()
        runCurrent()
    }

    // HX-05/06/07/08: paired switch runs share capability/config/image/notice, not phase histories.
    @Test fun hx05And06OnOffShareParametersNoticeAndReturnImageWhileKeepingDifferentLegalHistory() = runTest {
        val contexts = mutableListOf<GuiContext>()
        val requests = mutableListOf<JsonObject>()
        for (harness in listOf(false, true)) {
            val f = Fixture(backgroundScope)
            f.coordinator.start(options(harness))
            runCurrent()
            f.gateway.answerGui(0, tap("HX_PAIR_ONE"))
            runCurrent()
            f.gateway.answerGui(1, input("HX_PAIR_TWO", "HX_REQUIRED_TEXT_42"))
            runCurrent()
            f.coordinator.takeover()
            runCurrent()
            val before = f.counts()
            advanceTimeBy(1_500)
            runCurrent()
            assertEquals(before, f.counts())
            f.coordinator.giveBack()
            runCurrent()
            if (harness) {
                assertEquals(listOf("HX_PAIR_ONE", "HX_PAIR_TWO"), f.gateway.plannerCalls.single().context.completedActions.map { it.reply.callId })
                f.gateway.answerPlanner(0, decision("INCOMPLETE", "EXECUTE", "HX_NEW_REMAINING_GOAL"))
                runCurrent()
            }
            contexts.add(f.gateway.guiCalls[3].context)
            requests.add(f.gateway.guiJson[3])
            assertEquals(4, f.state.guiUsed)
            assertEquals(if (harness) 1 else 0, f.state.plannerUsed)
            assertEquals(4, f.phone.observations)
            assertEquals(2, f.phone.dispatches)
            assertEquals(1, f.audit.transitions("return_committed"))
            if (!harness) {
                assertTrue(f.gateway.plannerCalls.isEmpty())
                assertTrue(f.gateway.guiCalls.all { it.context.phase == Phase.ORIGINAL && it.context.objective.isEmpty() && it.context.conditions.isEmpty() })
                assertNativePairs(f.gateway.guiJson[3], listOf("HX_PAIR_ONE", "HX_PAIR_TWO"))
            }
            f.coordinator.stop()
            runCurrent()
        }
        val (off, on) = contexts
        assertEquals(off.options.task, on.options.task)
        assertEquals(off.options.gui, on.options.gui)
        assertEquals(off.options.authorizedPackages, on.options.authorizedPackages)
        assertEquals(off.options.budget, on.options.budget)
        assertEquals(off.frame.id, on.frame.id)
        assertArrayEquals(off.frame.png, on.frame.png)
        assertEquals(Phase.ORIGINAL, off.phase)
        assertEquals(Phase.RECOVERY, on.phase)
        assertEquals(listOf("HX_PAIR_ONE", "HX_PAIR_TWO"), off.history.map { it.reply.callId })
        assertTrue(on.history.isEmpty())
        assertEquals(2, off.returnNoticeIndex)
        assertEquals(0, on.returnNoticeIndex)
        val notice = assets("gui/control.returned.txt")
        for (request in requests) {
            assertEquals(1, request["messages"].asJsonArray.count {
                it.asJsonObject["role"].asString == "user" && it.asJsonObject["content"].isJsonPrimitive && it.asJsonObject["content"].asString == notice
            })
            assertSingleImage(request, off.frame)
            assertTrue(request.toString().contains(options().task))
        }
        val common = requests.map { it.deepCopy().apply { remove("messages") } }
        assertEquals(common[0], common[1])
        assertNoMarkers(requests[1], "HX_PAIR_ONE", "HX_PAIR_TWO", "HX_REQUIRED_TEXT_42")
    }

    // HX-11 C: proposed, failed and manual state do not become own successful phone actions.
    @Test fun hx11PlannerReceivesOnlyGenuinelyCompletedNativeActionsAcrossAllTerminalStatuses() = runTest {
        for (status in listOf("completed", "rejected", "cancelled", "unknown", "device_error")) {
            val f = Fixture(backgroundScope)
            f.phone.nextActionResult = ActionResult(status)
            f.coordinator.start(options())
            runCurrent()
            f.gateway.answerGui(0, input("HX_RESULT_$status", "HX_VALUE_$status"))
            runCurrent()
            f.coordinator.takeover()
            runCurrent()
            assertEquals(RunState.MANUAL, f.state.state)
            val duringManual = f.counts()
            f.phone.businessValue = "HX_HUMAN_STATE"
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(duringManual, f.counts())
            f.coordinator.giveBack()
            runCurrent()
            val planner = f.gateway.plannerCalls.single().context
            val actual = planner.completedActions.map { it.reply.callId }
            assertEquals(if (status == "completed") listOf("HX_RESULT_$status") else emptyList<String>(), actual)
            val body = f.gateway.plannerJson.single().toString()
            assertFalse(body.contains("HX_HUMAN_STATE"))
            if (status != "completed") assertFalse(body.contains("HX_VALUE_$status"))
            f.coordinator.stop()
            runCurrent()
        }
    }

    // HX-11 A: defensive assembly must not turn a phase finish declaration into a phone action.
    @Test fun hx11PlannerAssemblyExcludesFinishDeclarationsEvenWhenInputTableMarksThemCompleted() {
        val statuses = listOf("completed", "rejected", "cancelled", "unknown", "device_error", "proposed")
        val turns = statuses.map { status -> Turn(input("HX_INPUT_$status", "HX_VALUE_$status"), ActionResult(status)) } +
            Turn(finish("completed", "HX_FINISH_DECLARATION", "HX_DECLARED_NOT_INDEPENDENT_SUCCESS"), ActionResult("completed"))
        val latest = frame(1)
        val request = assembler.planner(PlannerContext(options(), 1, turns, listOf(latest), latest.id, null))
        val parsed = StrictJson.parse(request.toString(Charsets.UTF_8)).asJsonObject
        val own = plannerInput(parsed)["own_action_history"].asJsonArray
        assertEquals(1, own.size())
        assertEquals("input_text", own.single().asJsonObject["action"].asString)
        assertEquals("HX_VALUE_completed", own.single().asJsonObject["arguments"].asJsonObject["text"].asString)
        assertFalse(parsed.toString().contains("HX_DECLARED_NOT_INDEPENDENT_SUCCESS"))
    }

    // Late P1/P2 decisions cannot revive a stopped task or target the next task's current frame.
    @Test fun ct02LatePlannerDecisionsCannotAlterNewTaskOrAcquireExtraCalls() = runTest {
        for (second in listOf(false, true)) for (branch in listOf("EXECUTE", "FALLBACK", if (second) "FINISH" else "VERIFY")) {
            val f = Fixture(backgroundScope)
            val ordinal = if (second) 1 else 0
            f.gateway.ignorePlannerCancellation.add(ordinal)
            f.coordinator.start(options())
            runCurrent()
            f.coordinator.takeover()
            runCurrent()
            f.coordinator.giveBack()
            runCurrent()
            if (second) {
                f.gateway.answerPlanner(0, decision("INCOMPLETE", "VERIFY", "HX_VALID_CHECK"))
                runCurrent()
                f.gateway.answerGui(1, finish("unable", "HX_VALID_CHECK_FINISH"))
                runCurrent()
            }
            val old = f.gateway.plannerCalls[ordinal]
            f.coordinator.stop()
            runCurrent()
            assertEquals("user_stop", f.audit.lastEnd()["reason"])
            f.coordinator.start(options(harness = false).copy(task = "HX_NEXT_TASK_LITERAL"))
            runCurrent()
            val before = f.counts()
            val stale = if (branch == "FINISH") PlannerDecision("COMPLETE", "FINISH", "", emptyList())
                else decision("INCOMPLETE", branch, "HX_STALE_PLANNER_OBJECTIVE")
            assertTrue(old.result.complete(stale))
            runCurrent()
            assertEquals(before, f.counts())
            assertFalse(old.result.complete(stale))
            runCurrent()
            assertEquals(before, f.counts())
            assertEquals(RunState.AUTO, f.state.state)
            assertEquals(Phase.ORIGINAL, f.state.phase)
            assertEquals("HX_NEXT_TASK_LITERAL", f.gateway.guiCalls.last().context.options.task)
            assertTrue(f.gateway.guiCalls.last().context.history.isEmpty())
            assertFalse(f.gateway.guiJson.last().toString().contains("HX_STALE_PLANNER_OBJECTIVE"))
            f.coordinator.stop()
            runCurrent()
        }
    }

    private enum class Arrival { PAUSING, MANUAL, RETURN_COMMITTED, NEW_REQUEST }
    private class Fixture(scope: CoroutineScope) {
        val phone = FakePhone()
        val gateway = FakeGateway()
        val audit = FakeAudit()
        val coordinator = Coordinator(scope, gateway, phone, audit)
        val state get() = coordinator.snapshot.value
        fun counts() = Counts(state.guiUsed, state.plannerUsed, phone.observations, phone.dispatches,
            gateway.guiCalls.size, gateway.plannerCalls.size)
    }
    private data class Counts(val gui: Int, val planner: Int, val observations: Int, val dispatches: Int, val guiCalls: Int, val plannerCalls: Int)
    private data class GuiCall(val context: GuiContext, val result: CompletableDeferred<GuiReply> = CompletableDeferred())
    private data class PlannerCall(val context: PlannerContext, val result: CompletableDeferred<PlannerDecision> = CompletableDeferred())
    private class FakeGateway : ModelGateway {
        val guiCalls = mutableListOf<GuiCall>()
        val plannerCalls = mutableListOf<PlannerCall>()
        val guiJson = mutableListOf<JsonObject>()
        val plannerJson = mutableListOf<JsonObject>()
        val ignoreGuiCancellation = mutableSetOf<Int>()
        val ignorePlannerCancellation = mutableSetOf<Int>()
        override fun preflightGui(context: GuiContext) { guiJson.add(StrictJson.parse(assembler.gui(context).toString(Charsets.UTF_8)).asJsonObject) }
        override fun preflightPlanner(context: PlannerContext) { plannerJson.add(StrictJson.parse(assembler.planner(context).toString(Charsets.UTF_8)).asJsonObject) }
        override suspend fun gui(context: GuiContext): GuiReply {
            val index = guiCalls.size
            val call = GuiCall(context).also(guiCalls::add)
            return if (index in ignoreGuiCancellation) withContext(NonCancellable) { call.result.await() } else call.result.await()
        }
        override suspend fun planner(context: PlannerContext): PlannerDecision {
            val index = plannerCalls.size
            val call = PlannerCall(context).also(plannerCalls::add)
            return if (index in ignorePlannerCancellation) withContext(NonCancellable) { call.result.await() } else call.result.await()
        }
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
        var failNextObservation = false
        var nextActionResult = ActionResult("completed")
        var businessValue = "HX_INITIAL_STATE"
        var onState: ((RunSnapshot) -> Unit)? = null
        private var input: CompletableDeferred<ActionResult>? = null
        private val pendingObservations = mutableMapOf<Int, CompletableDeferred<ScreenFrame>>()
        fun holdObservation(ordinal: Int) = CompletableDeferred<ScreenFrame>().also { pendingObservations[ordinal] = it }
        override suspend fun observe(): ScreenFrame {
            check(captureAllowed)
            val ordinal = ++observations
            if (failNextObservation) { failNextObservation = false; throw RunFailure("DEVICE", "SCREENSHOT_FAILED") }
            val gate = pendingObservations.remove(ordinal)
            return if (gate != null) withContext(NonCancellable) { gate.await() } else frame(ordinal)
        }
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult {
            if (!canDispatch()) return ActionResult("rejected")
            dispatches++
            if (!holdInput) return nextActionResult.also { nextActionResult = ActionResult("completed") }
            inputInFlight = true
            return CompletableDeferred<ActionResult>().also { input = it }.await()
        }
        fun completeInput(value: ActionResult): Boolean {
            inputInFlight = false
            return checkNotNull(input).complete(value)
        }
        override fun setCaptureEnabled(enabled: Boolean) { captureAllowed = enabled }
        override fun showState(snapshot: RunSnapshot) { onState?.invoke(snapshot) }
    }
    private class FakeAudit : AuditSink {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        var onEvent: ((String, Map<String, Any?>) -> Unit)? = null
        override fun begin(sessionId: String, options: SessionOptions) = Unit
        override fun event(event: String, data: Map<String, Any?>) { events.add(event to data.toMap()); onEvent?.invoke(event, data) }
        fun transitions(value: String) = events.count { it.first == "control" && it.second["transition"] == value }
        fun actions(value: String) = events.count { it.first == "action" && it.second["action_type"] == value }
        fun terminals(role: String, index: Int) = events.filter { it.first == "model_call" && it.second["role"] == role && it.second["call_index"] == index && it.second["status"] != "started" }.map { it.second }
        fun lastEnd() = events.last { it.first == "session_ended" }.second
    }
    private companion object {
        const val ORIGINAL = "HX_ORIGINAL_HISTORY"
        val project = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "prompts/v1.2.0").isDirectory }
        fun assets(name: String) = File(project, PromptBundles.assetPath("1.2.0", name)).readText(Charsets.UTF_8)
        val assembler = ModelRequestAssembler(::assets)
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII=")
        fun frame(index: Int, id: String = "frame-$index") = ScreenFrame(id, png.copyOf() + byteArrayOf(index.toByte()), 1, 1)
        // These frozen assertions intentionally exercise legacy execution policy 1.1.
        fun options(harness: Boolean = true) = SessionOptions(
            task = "HX_ORIGINAL_TASK required literal 42 {{ORIGINAL_TASK}}", initialPackage = "org.synthetic.home",
            authorizedPackages = setOf("org.synthetic.fixture"), harness = harness, budget = 30,
            gui = ModelProfile(baseUrl = "https://gui.example/v1", model = "synthetic-gui", id = "00000000-0000-4000-8000-000000000001"),
            planner = ModelProfile(baseUrl = "https://planner.example/v1", model = "synthetic-planner", id = "00000000-0000-4000-8000-000000000002"),
            startMode = StartMode.HOME_AUTO, homePackage = "org.synthetic.home", authorizedAppLabels = mapOf("org.synthetic.fixture" to "Synthetic fixture"), automaticRecovery = false)
        fun tap(id: String) = GuiReply(id, PhoneAction("tap", mapOf("x" to 500, "y" to 500)), "{\"x\":500,\"y\":500}")
        fun input(id: String, value: String) = GuiReply(id, PhoneAction("input_text", mapOf("text" to value)), JsonObject().apply { addProperty("text", value) }.toString())
        fun waitReply(id: String, duration: Int = 100) = GuiReply(id, PhoneAction("wait", mapOf("duration_ms" to duration)), "{\"duration_ms\":$duration}")
        fun finish(outcome: String, id: String, report: String = "HX_CHECK_REPORT") = GuiReply(id, PhoneAction("finish", mapOf("outcome" to outcome, "message" to report)), JsonObject().apply { addProperty("outcome", outcome); addProperty("message", report) }.toString())
        fun decision(status: String, branch: String, objective: String) = PlannerDecision(status, branch, objective, if (branch == "FALLBACK") emptyList() else listOf("HX_VISIBLE_CONDITION_42"))
        fun assertNoMarkers(request: JsonObject, vararg markers: String) { markers.forEach { assertFalse(it, request.toString().contains(it)) } }
        fun assertReadOnly(request: JsonObject) = assertEquals(setOf("wait", "finish"), request["tools"].asJsonArray.map { it.asJsonObject["function"].asJsonObject["name"].asString }.toSet())
        fun assertSingleImage(request: JsonObject, frame: ScreenFrame) {
            val images = request["messages"].asJsonArray.flatMap { msg ->
                val content = msg.asJsonObject["content"]
                if (content.isJsonArray) content.asJsonArray.filter { it.asJsonObject["type"].asString == "image_url" } else emptyList()
            }
            assertEquals(1, images.size)
            assertTrue("CURRENT_IMAGE_ONLY", images.single().asJsonObject["image_url"].asJsonObject["url"].asString ==
                "data:image/png;base64," + Base64.getEncoder().encodeToString(frame.png))
        }
        fun assertNativePairs(request: JsonObject, ids: List<String>) {
            val messages = request["messages"].asJsonArray.map { it.asJsonObject }
            val calls = messages.filter { it["role"].asString == "assistant" }.flatMap { it["tool_calls"].asJsonArray.map { call -> call.asJsonObject["id"].asString } }
            val results = messages.filter { it["role"].asString == "tool" }.map { it["tool_call_id"].asString }
            assertEquals(ids, calls)
            assertEquals(ids, results)
            ids.forEach { id ->
                val index = messages.indexOfFirst { it["role"].asString == "assistant" && it["tool_calls"].asJsonArray.single().asJsonObject["id"].asString == id }
                assertEquals("tool", messages[index + 1]["role"].asString)
                assertEquals(id, messages[index + 1]["tool_call_id"].asString)
            }
        }
        fun plannerInput(request: JsonObject) = StrictJson.parse(request["messages"].asJsonArray[1].asJsonObject["content"].asJsonArray[0].asJsonObject["text"].asString).asJsonObject
        fun privateField(target: Any, name: String) = Coordinator::class.java.getDeclaredField(name).let { it.isAccessible = true; it.get(target) }
    }
}

