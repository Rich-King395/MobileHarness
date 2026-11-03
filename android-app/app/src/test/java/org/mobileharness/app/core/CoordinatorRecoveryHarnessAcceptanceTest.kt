package org.mobileharness.app.core

import com.google.gson.JsonObject
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
import org.mobileharness.app.model.ModelRequestAssembler
import org.mobileharness.app.model.PromptBundles
import org.mobileharness.app.model.StrictJson
import java.io.File
import java.util.Base64

/** Current HOME_AUTO + policy 1.2 combinations. Every request uses the production assembler. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorRecoveryHarnessAcceptanceTest {
    @Test fun arh01OneBudgetCoversCancellationRecoveryCheckAndDeliveredExecution() = runTest {
        val f = started(budget = 7)
        f.answer(tap("ORIGINAL_SUCCESS")); runCurrent()
        returned(f)
        assertEquals(2, f.state.guiUsed) // The pre-handoff outstanding request is still paid.
        f.plan(decision("VERIFY")); runCurrent()
        f.answer(waitReply("CHECK_WAIT")); runCurrent()
        advanceTimeBy(100); runCurrent()
        f.fail(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("CHECK_WAIT"), f.gateway.gui.last().context.history.map { it.reply.callId })
        assertAbsent(f.gateway.guiJson.last(), "ORIGINAL_SUCCESS")
        f.answer(finish("unable", "CHECK_FINISH")); runCurrent()
        assertEquals(5, f.state.guiUsed)
        assertEquals(2, f.state.plannerUsed)
        f.plan(decision("EXECUTE")); runCurrent()
        assertIsolated(f.gateway.gui.last().context, Phase.RECOVERY)
        f.fail(RunFailure("MODEL", "TRANSPORT_ERROR", transportReason = "TIMEOUT")); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(7, f.state.guiUsed)
        assertIsolated(f.gateway.gui.last().context, Phase.RECOVERY)
        assertAbsent(f.gateway.guiJson.last(), "ORIGINAL_SUCCESS", "CHECK_WAIT", "CHECK_FINISH", "CHECK_REPORT")
        f.answer(finish()); runCurrent()
        assertEquals("model_declared_complete", f.audit.end()["reason"])
        assertEquals(2, f.gateway.planner.size)
        assertEquals(1, f.phone.performed.size)
        assertEquals(2, f.audit.of("recovery").count { it["status"] == "started" })
        assertClosed(f)
    }

    @Test fun arh02LastReadonlyWaitAfterRecoveryCannotBuyASecondPlannerOrFinish() = runTest {
        for (status in listOf("INCOMPLETE", "COMPLETE")) {
            val f = started(budget = 3)
            returned(f)
            f.plan(decision("VERIFY", status)); runCurrent()
            f.fail(); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            f.answer(waitReply("LAST_WAIT")); runCurrent()
            advanceTimeBy(100); runCurrent()
            assertEquals("budget_exhausted", f.audit.end()["reason"])
            assertEquals("none", f.audit.end()["completion_source"])
            assertEquals(3, f.state.guiUsed)
            assertEquals(1, f.gateway.planner.size)
            assertEquals(3, f.gateway.gui.size)
            assertTrue(f.audit.of("action").none { it["action_type"] == "finish" })
            assertTrue(f.phone.performed.isEmpty())
            assertClosed(f)
            advanceTimeBy(10_000); runCurrent()
            assertEquals(3, f.gateway.gui.size)
            assertEquals(1, f.gateway.planner.size)
        }
    }

    @Test fun arh03SecondPlannerCannotReopenVerificationAfterARecoveredCheck() = runTest {
        for (status in listOf("INCOMPLETE", "COMPLETE")) {
            val f = started()
            f.answer(tap("ORIGINAL_SUCCESS")); runCurrent()
            returned(f)
            f.plan(decision("VERIFY", status)); runCurrent()
            f.fail(); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            f.answer(finish("unable", "CHECK_FINISH")); runCurrent()
            f.plan(decision("VERIFY")); runCurrent() // Illegal second verification request.
            val resumed = f.gateway.gui.last().context
            assertEquals(Phase.ORIGINAL, resumed.phase)
            assertEquals(listOf("ORIGINAL_SUCCESS"), resumed.history.map { it.reply.callId })
            assertTrue(resumed.objective.isEmpty())
            assertAbsent(f.gateway.guiJson.last(), "CHECK_FINISH", "CHECK_REPORT", "REMAINING_OBJECTIVE")
            assertEquals(2, f.gateway.planner.size)
            assertEquals(1, f.audit.of("planner_decision").count { it["decision"] == "VERIFY" })
            f.answer(finish()); runCurrent()
            assertEquals(2, f.gateway.planner.size)
            assertClosed(f)
        }
    }

    @Test fun arh04SecondPlannerDirectlyEndsAtZeroBalanceAfterModelRecovery() = runTest {
        val f = started(budget = 3)
        returned(f)
        f.plan(decision("VERIFY")); runCurrent()
        f.fail(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        f.answer(finish("unable", "CHECK_FINISH")); runCurrent()
        assertEquals(3, f.state.guiUsed)
        assertEquals(2, f.state.plannerUsed)
        val observations = f.phone.observations
        val actions = f.audit.of("action").size
        f.plan(PlannerDecision("COMPLETE", "FINISH", "", emptyList())); runCurrent()
        assertEquals("planner", f.audit.end()["completion_source"])
        assertEquals("model_declared_complete", f.audit.end()["reason"])
        assertEquals(3, f.gateway.gui.size)
        assertEquals(observations, f.phone.observations)
        assertEquals(actions, f.audit.of("action").size)
        assertTrue(f.phone.performed.isEmpty())
        assertClosed(f)
    }

    @Test fun arh05LateSecondPlannerCannotContaminateANewTaskAfterRecoveredCheck() = runTest {
        val f = started()
        returned(f)
        f.plan(decision("VERIFY")); runCurrent()
        f.fail(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        f.gateway.ignorePlannerCancellation.add(1)
        f.answer(finish("unable")); runCurrent()
        val stale = f.gateway.planner.last()
        f.coordinator.stop(); runCurrent()
        val previousSession = f.state.sessionId
        f.coordinator.start(options(harness = false).copy(task = "NEW_TASK")); runCurrent()
        assertNotEquals(previousSession, f.state.sessionId)
        val current = f.gateway.gui.last().context
        stale.result.complete(PlannerDecision("COMPLETE", "FINISH", "", emptyList())); runCurrent()
        assertEquals(RunState.AUTO, f.state.state)
        assertEquals(0, f.state.plannerUsed)
        assertEquals(1, f.state.guiUsed)
        assertSame(current, f.gateway.gui.last().context)
        assertEquals(1, f.audit.of("session_ended").size)
        assertEquals("user_stop", f.audit.end()["reason"])
        f.answer(finish()); runCurrent()
        assertClosed(f)
    }

    @Test fun arh06LateRecoveryCaptureCannotBecomeAReturnFrameOrCollectDuringManual() = runTest {
        val f = started()
        f.fail(); runCurrent()
        val capture = f.phone.holdObservation(2)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, f.phone.observations)
        f.coordinator.takeover(); runCurrent()
        assertEquals(RunState.MANUAL, f.state.state)
        assertFalse(f.phone.captureAllowed)
        capture.complete(frame(2, "STALE_RECOVERY_IMAGE")); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, f.phone.observations)
        assertEquals(1, f.gateway.gui.size)
        f.coordinator.giveBack(); runCurrent()
        val planner = f.gateway.planner.single().context
        assertEquals(listOf("frame-3"), planner.frames.map { it.id })
        assertNull(planner.preHandoffFrameId)
        f.plan(decision("EXECUTE")); runCurrent()
        assertEquals("frame-3", f.gateway.gui.last().context.frame.id)
        assertTrue(f.gateway.gui.last().context.history.isEmpty())
        assertEquals(2, f.state.guiUsed)
        f.answer(finish()); runCurrent()
        assertClosed(f)
    }

    @Test fun arh07OnOffKeepEqualReturnObservationNoticeAndBudgetAfterAutomaticRecovery() = runTest {
        val contexts = mutableListOf<GuiContext>()
        val requests = mutableListOf<JsonObject>()
        for (harness in listOf(false, true)) {
            val f = started(harness = harness)
            f.answer(tap("ORIGINAL_SUCCESS")); runCurrent()
            f.fail(); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            returned(f)
            if (harness) {
                assertEquals(listOf("ORIGINAL_SUCCESS"), f.gateway.planner.single().context.completedActions.map { it.reply.callId })
                f.plan(decision("EXECUTE")); runCurrent()
            }
            contexts.add(f.gateway.gui.last().context)
            requests.add(f.gateway.guiJson.last())
            assertEquals(4, f.state.guiUsed)
            assertEquals(if (harness) 1 else 0, f.state.plannerUsed)
            assertEquals(4, f.phone.observations)
            assertEquals(1, f.phone.performed.size)
            assertEquals(1, f.audit.of("control").count { it["transition"] == "return_committed" })
            f.answer(finish()); runCurrent()
            assertClosed(f)
        }
        val (off, on) = contexts
        assertEquals(off.options.copy(harness = true), on.options)
        assertEquals(off.frame.id, on.frame.id)
        assertArrayEquals(off.frame.png, on.frame.png)
        assertEquals(Phase.ORIGINAL, off.phase)
        assertEquals(listOf("ORIGINAL_SUCCESS"), off.history.map { it.reply.callId })
        assertEquals(1, off.returnNoticeIndex)
        assertIsolated(on, Phase.RECOVERY)
        assertEquals(0, on.returnNoticeIndex)
        val returnedNotice = asset("gui/control.returned.txt")
        requests.forEach { request ->
            assertEquals(1, request["messages"].asJsonArray.count { entry ->
                val msg = entry.asJsonObject
                msg["role"].asString == "user" && msg["content"].isJsonPrimitive && msg["content"].asString == returnedNotice
            })
            assertEquals(1, imageCount(request))
        }
        assertAbsent(requests[1], "ORIGINAL_SUCCESS")
        assertEquals(requests[0].deepCopy().apply { remove("messages") }, requests[1].deepCopy().apply { remove("messages") })
    }

    @Test fun arh08DeliveredBranchesNeverReplanAfterRecoverableModelOrNativeRejection() = runTest {
        for (ordinal in listOf(1, 2)) for (branch in listOf("EXECUTE", "FALLBACK"))
            for (failure in listOf("MODEL", "INPUT")) for (outcome in listOf("completed", "unable")) {
                val f = started()
                returned(f)
                if (ordinal == 2) {
                    f.plan(decision("VERIFY")); runCurrent()
                    f.answer(finish("unable")); runCurrent()
                }
                f.plan(decision(branch)); runCurrent()
                val oldFrame = f.gateway.gui.last().context.frame.id
                if (failure == "MODEL") {
                    f.fail(); runCurrent()
                    advanceTimeBy(1_000); runCurrent()
                } else {
                    f.phone.nextResult = ActionResult("rejected", failureReason = ExecutionFailureReason.GESTURE_NOT_DISPATCHED)
                    f.answer(tap("REJECTED_NATIVE_ACTION")); runCurrent()
                }
                val resumed = f.gateway.gui.last().context
                assertEquals(if (branch == "EXECUTE") Phase.RECOVERY else Phase.ORIGINAL, resumed.phase)
                assertNotEquals(oldFrame, resumed.frame.id)
                assertEquals(ordinal, f.state.plannerUsed)
                assertEquals(ordinal, f.gateway.planner.size)
                if (failure == "INPUT") assertEquals("rejected", resumed.history.last().result.status)
                f.answer(finish(outcome)); runCurrent()
                assertEquals(if (outcome == "completed") "model_declared_complete" else "model_unable", f.audit.end()["reason"])
                assertEquals(ordinal, f.gateway.planner.size)
                assertTrue(f.audit.of("task_assessment").isEmpty())
                assertClosed(f)
            }
    }

    @Test fun arh09SecondPlannerFailureAtZeroBalanceIsNotRetriedOrGivenAFreeFallback() = runTest {
        for (error in listOf(invalid(), RunFailure("MODEL", "API_ERROR", httpStatus = 503),
            RunFailure("MODEL", "TRANSPORT_ERROR", transportReason = "TIMEOUT"))) {
            val f = started(budget = 3)
            returned(f)
            f.plan(decision("VERIFY")); runCurrent()
            f.fail(); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            f.answer(finish("unable")); runCurrent()
            f.gateway.planner.last().result.completeExceptionally(error); runCurrent()
            advanceTimeBy(10_000); runCurrent()
            assertEquals("budget_exhausted", f.audit.end()["reason"])
            assertEquals("none", f.audit.end()["completion_source"])
            assertEquals(2, f.gateway.planner.size)
            assertEquals(3, f.gateway.gui.size)
            assertEquals(1, f.audit.of("recovery").count { it["status"] == "started" })
            assertClosed(f)
        }
    }

    @Test fun arh10PlannerOwnSuccessTableExcludesKnownNativeRejectionsAndUnsentFailures() = runTest {
        val f = started()
        f.answer(tap("ONLY_COMPLETED_INPUT")); runCurrent()
        f.phone.nextResult = ActionResult("cancelled", failureReason = ExecutionFailureReason.GESTURE_CANCELLED)
        f.answer(tap("CANCELLED_NATIVE_INPUT")); runCurrent()
        f.fail(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        returned(f)
        val plan = f.gateway.planner.single().context
        assertEquals(listOf("ONLY_COMPLETED_INPUT"), plan.completedActions.map { it.reply.callId })
        assertAbsent(f.gateway.plannerJson.single(), "CANCELLED_NATIVE_INPUT")
        f.plan(decision("FALLBACK")); runCurrent()
        assertEquals(listOf("completed", "cancelled"), f.gateway.gui.last().context.history.map { it.result.status })
        assertEquals(listOf("ONLY_COMPLETED_INPUT", "CANCELLED_NATIVE_INPUT"), f.gateway.gui.last().context.history.map { it.reply.callId })
        f.answer(finish()); runCurrent()
        assertEquals(1, f.gateway.planner.size)
        assertClosed(f)
    }

    @Test fun arh11EveryLateRecoveredReplyShapeIsIgnoredAcrossHandoffAndReturnBoundaries() = runTest {
        for (harness in listOf(false, true)) for (arrival in listOf("MANUAL", "RETURN", "NEW_GUI"))
            for (late in listOf(tap("LATE_TAP"), finish("completed", "LATE_COMPLETE"), finish("unable", "LATE_UNABLE"))) {
                val f = started(harness = harness)
                f.fail(); runCurrent()
                f.gateway.ignoreGuiCancellation.add(1)
                advanceTimeBy(1_000); runCurrent()
                val stale = f.gateway.gui.last()
                f.coordinator.takeover(); runCurrent()
                assertEquals(RunState.MANUAL, f.state.state)
                if (arrival != "MANUAL") { f.coordinator.giveBack(); runCurrent() }
                if (arrival == "NEW_GUI" && harness) { f.plan(decision("EXECUTE")); runCurrent() }
                val counts = listOf(f.phone.observations, f.gateway.gui.size, f.gateway.planner.size, f.phone.performed.size, f.state.guiUsed)
                stale.result.complete(late); runCurrent()
                assertEquals(counts, listOf(f.phone.observations, f.gateway.gui.size, f.gateway.planner.size, f.phone.performed.size, f.state.guiUsed))
                assertTrue(f.audit.of("session_ended").isEmpty())
                assertTrue(f.phone.performed.isEmpty())
                if (arrival == "MANUAL") { f.coordinator.giveBack(); runCurrent() }
                if (harness && arrival != "NEW_GUI") { f.plan(decision("EXECUTE")); runCurrent() }
                assertTrue(f.gateway.gui.last().context.history.isEmpty())
                assertAbsent(f.gateway.guiJson.last(), "LATE_TAP", "LATE_COMPLETE", "LATE_UNABLE")
                assertEquals(3, f.state.guiUsed)
                f.answer(finish()); runCurrent()
                assertEquals("model_declared_complete", f.audit.end()["reason"])
                assertClosed(f)
            }
    }

    @Test fun arh12AUsedHandoffCycleDuringRecoveryStopsWithoutGrantingASecondManualCycle() = runTest {
        for (harness in listOf(false, true)) {
            val f = started(harness = harness)
            returned(f)
            if (harness) { f.plan(decision("EXECUTE")); runCurrent() }
            f.fail(); runCurrent()
            val observations = f.phone.observations
            val calls = f.gateway.gui.size
            f.coordinator.takeover(); runCurrent()
            assertEquals("user_stop", f.audit.end()["reason"])
            assertEquals(1, f.audit.of("control").count { it["transition"] == "manual_granted" })
            assertEquals(1, f.audit.of("control").count { it["transition"] == "return_committed" })
            assertEquals(1, f.audit.of("recovery").count { it["status"] == "cancelled" })
            f.coordinator.giveBack(); f.coordinator.retry(); runCurrent()
            advanceTimeBy(10_000); runCurrent()
            assertEquals(observations, f.phone.observations)
            assertEquals(calls, f.gateway.gui.size)
            assertEquals(if (harness) 1 else 0, f.gateway.planner.size)
            assertClosed(f)
        }
    }

    private fun TestScope.started(harness: Boolean = true, budget: Int = 30) = Fixture(backgroundScope).also {
        it.coordinator.start(options(harness, budget)); runCurrent()
        assertEquals("1.2.0", it.gateway.gui.single().context.options.executionPolicyVersion)
        assertEquals("1.2.0", it.gateway.gui.single().context.options.promptBundleVersion)
        assertEquals("1.4.0", it.gateway.gui.single().context.options.adapterVersion)
    }

    private fun TestScope.returned(f: Fixture) {
        f.coordinator.takeover(); runCurrent()
        assertEquals(RunState.MANUAL, f.state.state)
        assertFalse(f.phone.captureAllowed)
        val counts = listOf(f.phone.observations, f.gateway.gui.size, f.gateway.planner.size, f.phone.performed.size, f.state.guiUsed)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(counts, listOf(f.phone.observations, f.gateway.gui.size, f.gateway.planner.size, f.phone.performed.size, f.state.guiUsed))
        f.coordinator.giveBack(); runCurrent()
        assertTrue(f.state.handoffUsed)
    }

    private fun assertIsolated(context: GuiContext, phase: Phase) {
        assertEquals(phase, context.phase)
        assertTrue(context.history.isEmpty())
        assertEquals("REMAINING_OBJECTIVE", context.objective)
        assertEquals(listOf("VISIBLE_CONDITION"), context.conditions)
    }

    private fun assertClosed(f: Fixture) {
        assertEquals(RunState.ENDED, f.state.state)
        assertFalse(f.phone.captureAllowed)
        assertNull(f.phone.configured)
        assertTrue(f.phone.states.none { it.state == RunState.BLOCKED })
        assertTrue(f.state.guiUsed <= f.state.budget)
        assertTrue(f.state.plannerUsed <= 2)
    }

    private class Fixture(scope: CoroutineScope) {
        val phone = Phone()
        val gateway = Gateway()
        val audit = Audit()
        val coordinator = Coordinator(scope, gateway, phone, audit)
        val state get() = coordinator.snapshot.value
        fun answer(reply: GuiReply) { check(gateway.gui.last().result.complete(reply)) }
        fun fail(error: RunFailure = invalid()) { check(gateway.gui.last().result.completeExceptionally(error)) }
        fun plan(decision: PlannerDecision) { check(gateway.planner.last().result.complete(decision)) }
    }
    private data class GuiCall(val context: GuiContext, val result: CompletableDeferred<GuiReply> = CompletableDeferred())
    private data class PlannerCall(val context: PlannerContext, val result: CompletableDeferred<PlannerDecision> = CompletableDeferred())
    private class Gateway : ModelGateway {
        val gui = mutableListOf<GuiCall>()
        val planner = mutableListOf<PlannerCall>()
        val guiJson = mutableListOf<JsonObject>()
        val plannerJson = mutableListOf<JsonObject>()
        val ignoreGuiCancellation = mutableSetOf<Int>()
        val ignorePlannerCancellation = mutableSetOf<Int>()
        override fun preflightGui(context: GuiContext) { guiJson.add(StrictJson.parse(assembler.gui(context).toString(Charsets.UTF_8)).asJsonObject) }
        override fun preflightPlanner(context: PlannerContext) { plannerJson.add(StrictJson.parse(assembler.planner(context).toString(Charsets.UTF_8)).asJsonObject) }
        override suspend fun gui(context: GuiContext): GuiReply {
            val ordinal = gui.size
            val call = GuiCall(context).also(gui::add)
            return if (ordinal in ignoreGuiCancellation) withContext(NonCancellable) { call.result.await() } else call.result.await()
        }
        override suspend fun planner(context: PlannerContext): PlannerDecision {
            val ordinal = planner.size
            val call = PlannerCall(context).also(planner::add)
            return if (ordinal in ignorePlannerCancellation) withContext(NonCancellable) { call.result.await() } else call.result.await()
        }
        override fun cancelAll() = Unit
    }
    private class Phone : PhonePort {
        override var connected = true
        override var inputInFlight = false
        var captureAllowed = false
        var configured: SessionOptions? = null
        var observations = 0
        var nextResult = ActionResult("completed")
        val performed = mutableListOf<PhoneAction>()
        val states = mutableListOf<RunSnapshot>()
        private val heldObservations = mutableMapOf<Int, CompletableDeferred<ScreenFrame>>()
        fun holdObservation(ordinal: Int) = CompletableDeferred<ScreenFrame>().also { heldObservations[ordinal] = it }
        override fun configureSession(options: SessionOptions?) { configured = options }
        override suspend fun observe(): ScreenFrame {
            check(captureAllowed)
            val ordinal = ++observations
            return heldObservations.remove(ordinal)?.let { withContext(NonCancellable) { it.await() } } ?: frame(ordinal)
        }
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult {
            check(canDispatch())
            performed.add(action)
            return nextResult.also { nextResult = ActionResult("completed") }
        }
        override fun setCaptureEnabled(enabled: Boolean) { captureAllowed = enabled }
        override fun showState(snapshot: RunSnapshot) { states.add(snapshot) }
    }
    private class Audit : AuditSink {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun begin(sessionId: String, options: SessionOptions) = Unit
        override fun event(event: String, data: Map<String, Any?>) { events.add(event to data.toMap()) }
        fun of(event: String) = events.filter { it.first == event }.map { it.second }
        fun end() = of("session_ended").last()
    }
    private companion object {
        val project = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "prompts/v1.2.0").isDirectory }
        fun asset(name: String) = File(project, PromptBundles.assetPath("1.2.0", name)).readText(Charsets.UTF_8)
        val assembler = ModelRequestAssembler(::asset)
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII=")
        fun frame(ordinal: Int, id: String = "frame-$ordinal") = ScreenFrame(id, png.copyOf() + byteArrayOf(ordinal.toByte()), 1, 1)
        fun options(harness: Boolean = true, budget: Int = 30) = SessionOptions(
            task = "ORIGINAL_PRIVATE_TASK", initialPackage = "synthetic.home", authorizedPackages = setOf("synthetic.authorized"),
            harness = harness, budget = budget,
            gui = ModelProfile(baseUrl = "https://gui.example/v1", model = "synthetic-gui", id = "00000000-0000-4000-8000-000000000001"),
            planner = ModelProfile(baseUrl = "https://planner.example/v1", model = "synthetic-planner", id = "00000000-0000-4000-8000-000000000002"),
            startMode = StartMode.HOME_AUTO, homePackage = "synthetic.home", authorizedAppLabels = mapOf("synthetic.authorized" to "Synthetic"))
        fun invalid() = RunFailure("MODEL", "INVALID_OUTPUT", protocolReason = "COORDINATOR_REPLY_REJECTED")
        fun tap(id: String) = GuiReply(id, PhoneAction("tap", mapOf("x" to 500, "y" to 500)), "{\"x\":500,\"y\":500}")
        fun waitReply(id: String) = GuiReply(id, PhoneAction("wait", mapOf("duration_ms" to 100)), "{\"duration_ms\":100}")
        fun finish(outcome: String = "completed", id: String = "FINISH") = GuiReply(id,
            PhoneAction("finish", mapOf("outcome" to outcome, "message" to "CHECK_REPORT")),
            "{\"outcome\":\"$outcome\",\"message\":\"CHECK_REPORT\"}")
        fun decision(branch: String, status: String = "INCOMPLETE") = PlannerDecision(status, branch, "REMAINING_OBJECTIVE", listOf("VISIBLE_CONDITION"))
        fun assertAbsent(request: JsonObject, vararg markers: String) = markers.forEach { assertFalse(it, request.toString().contains(it)) }
        fun imageCount(request: JsonObject) = request["messages"].asJsonArray.sumOf { entry ->
            val content = entry.asJsonObject["content"]
            if (content.isJsonArray) content.asJsonArray.count { it.asJsonObject["type"].asString == "image_url" } else 0
        }
    }
}
