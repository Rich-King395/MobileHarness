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

/** Policy 1.2 lifecycle signals and real PhoneBridge; synthetic ports, time and replies. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorLifecycleStabilityTest {
    @Test fun hardDeviceSignalsCancelPendingGuiAndLateInputCannotReachTheNextSession() = runTest {
        for (harness in listOf(false, true)) {
            for (code in listOf("AUTHORIZATION_UNAVAILABLE", "SERVICE_DISCONNECTED", "STOP_ENTRY_UNAVAILABLE")) {
                val f = Fixture(backgroundScope)
                f.gateway.ignoreCancellation.add(0)
                f.coordinator.start(options(harness)); runCurrent()
                val oldSession = f.state.sessionId
                f.coordinator.environmentUnavailable(code); runCurrent()
                assertEquals(RunState.ENDED, f.state.state)
                assertEquals("runtime_failure", f.audit.ends().single()["reason"])
                assertEquals("none", f.audit.ends().single()["completion_source"])
                assertFalse(f.old.captureAllowed)
                assertNull(f.old.configured)
                assertEquals(1, f.state.guiUsed)
                assertTrue(f.audit.of("recovery").isEmpty())

                f.coordinator.start(options(harness)); runCurrent()
                assertNotEquals(oldSession, f.state.sessionId)
                assertEquals(1, f.state.guiUsed)
                assertEquals(0, f.state.plannerUsed)
                assertTrue(f.gateway.gui.last().context.history.isEmpty())
                f.gateway.gui[0].result.complete(tap()) // An uncancellable, obsolete request.
                runCurrent()
                assertEquals(RunState.AUTO, f.state.state)
                assertTrue(f.old.actions.isEmpty())
                assertEquals(2, f.gateway.gui.size)
                f.gateway.gui[1].result.complete(finish()); runCurrent()
                assertEquals(RunState.ENDED, f.state.state)
                assertEquals(listOf("runtime_failure", "model_declared_complete"), f.audit.ends().map { it["reason"] })
                assertEquals(1, f.audit.of("model_call").count { it["status"] == "cancelled" })
                assertNoBlocked(f)
            }
        }
    }

    @Test fun hardDeviceLossDuringRecoveryCancelsTheDelayWithoutAnotherObservationOrRequest() = runTest {
        for (code in listOf("AUTHORIZATION_UNAVAILABLE", "SERVICE_DISCONNECTED")) {
            val f = Fixture(backgroundScope)
            f.coordinator.start(options(true)); runCurrent()
            f.gateway.gui.single().result.completeExceptionally(RunFailure("MODEL", "TRANSPORT_ERROR", transportReason = "TIMEOUT"))
            runCurrent()
            assertEquals(1, f.audit.of("recovery").count { it["status"] == "scheduled" })
            assertFalse(f.old.captureAllowed)
            f.coordinator.environmentUnavailable(code); runCurrent()
            assertEquals(RunState.ENDED, f.state.state)
            advanceTimeBy(20_000); runCurrent()
            assertEquals(1, f.old.observations)
            assertEquals(1, f.gateway.gui.size)
            assertEquals(1, f.audit.of("recovery").count { it["status"] == "cancelled" })
            assertEquals(0, f.audit.of("recovery").count { it["status"] == "started" })
            assertEquals("runtime_failure", f.audit.ends().single()["reason"])
            assertNoBlocked(f)
        }
    }

    @Test fun manualDisconnectAndReplacementKeepHumanOwnershipUntilAFreshSuccessfulReturn() = runTest {
        for (harness in listOf(false, true)) {
            val f = Fixture(backgroundScope)
            f.gateway.ignoreCancellation.add(0)
            f.coordinator.start(options(harness)); runCurrent()
            f.coordinator.takeover(); runCurrent()
            assertEquals(RunState.MANUAL, f.state.state)
            assertFalse(f.old.captureAllowed)

            f.coordinator.environmentUnavailable("SERVICE_DISCONNECTED")
            f.bridge.detach(f.old)
            f.old.connected = false
            runCurrent()
            val observationsBefore = f.old.observations
            advanceTimeBy(10_000); runCurrent()
            assertEquals(RunState.MANUAL, f.state.state)
            assertEquals(observationsBefore, f.old.observations)
            assertEquals(1, f.gateway.gui.size)
            assertTrue(f.gateway.planner.isEmpty())

            f.coordinator.giveBack(); runCurrent() // Missing service must not commit the return.
            assertEquals(RunState.MANUAL, f.state.state)
            assertFalse(f.state.handoffUsed)
            assertEquals(1, f.state.guiUsed)
            assertEquals(0, f.audit.of("control").count { it["transition"] == "return_committed" })

            val replacement = f.replacement()
            assertEquals(setOf("synthetic.authorized"), replacement.configured?.authorizedPackages)
            assertFalse(replacement.captureAllowed)
            advanceTimeBy(10_000); runCurrent()
            assertEquals(0, replacement.observations)
            f.coordinator.giveBack(); runCurrent()
            assertTrue(f.state.handoffUsed)
            assertEquals(1, replacement.observations)
            assertEquals(1, f.audit.of("control").count { it["transition"] == "return_committed" })
            if (harness) {
                val planner = f.gateway.planner.single()
                assertEquals("replacement-1", planner.context.currentFrameId)
                assertNull(planner.context.preHandoffFrameId) // Lost-service invalidation released the old boundary image.
                planner.result.complete(PlannerDecision("UNVERIFIABLE", "FALLBACK", "PRIVATE_TASK", emptyList()))
                runCurrent()
            } else assertTrue(f.gateway.planner.isEmpty())
            val resumed = f.gateway.gui.last().context
            assertEquals("replacement-1", resumed.frame.id)
            assertEquals(Phase.ORIGINAL, resumed.phase)
            assertEquals(0, resumed.returnNoticeIndex)
            assertTrue(resumed.history.isEmpty())
            assertEquals(2, f.state.guiUsed)
            f.gateway.gui[0].result.complete(tap()); runCurrent()
            assertTrue(f.old.actions.isEmpty())
            assertTrue(replacement.actions.isEmpty())
            f.gateway.gui.last().result.complete(finish()); runCurrent()
            assertEquals(RunState.ENDED, f.state.state)
            assertFalse(replacement.captureAllowed)
            assertNull(replacement.configured)
            assertNoBlocked(f)
        }
    }

    @Test fun retiredServiceInputRemainsABarrierAcrossReplacementTimeoutAndRepeatedCommands() = runTest {
        for (nativeStatus in listOf("completed", "cancelled")) {
            val f = Fixture(backgroundScope)
            f.old.holdNative = true
            f.coordinator.start(options(true)); runCurrent()
            f.gateway.gui.single().result.complete(tap()); runCurrent()
            assertTrue(f.old.inputInFlight)

            f.coordinator.environmentUnavailable("SERVICE_DISCONNECTED")
            f.bridge.detach(f.old)
            f.old.connected = false
            val replacement = f.replacement()
            runCurrent()
            assertEquals(RunState.STOPPING, f.state.state)
            assertTrue(f.bridge.inputInFlight)
            assertTrue(f.audit.ends().isEmpty())
            assertFalse(replacement.captureAllowed)
            advanceTimeBy(5_000); runCurrent()
            assertEquals(RunState.UNCERTAIN, f.state.state)
            repeat(3) {
                f.coordinator.recheck()
                f.coordinator.giveBack()
                f.coordinator.start(options(false))
                advanceTimeBy(5_000); runCurrent()
                assertEquals(RunState.UNCERTAIN, f.state.state)
                assertTrue(f.audit.ends().isEmpty())
                assertEquals(0, replacement.observations)
                assertTrue(replacement.actions.isEmpty())
                assertEquals(1, f.gateway.gui.size)
            }
            f.old.completeNative(nativeStatus); runCurrent()
            assertEquals(RunState.ENDED, f.state.state)
            assertFalse(f.bridge.inputInFlight)
            assertEquals(1, f.audit.ends().size)
            assertEquals("runtime_failure", f.audit.ends().single()["reason"])
            assertEquals("none", f.audit.ends().single()["completion_source"])
            assertEquals(nativeStatus, f.audit.of("action").single()["result"])
            assertNull(replacement.configured)
            assertFalse(replacement.captureAllowed)
            f.coordinator.recheck(); runCurrent()
            assertEquals(1, f.audit.ends().size)
            assertNoBlocked(f)
        }
    }

    @Test fun screenLockDuringWaitCancelsItsLaterObservationWithoutChargingAnotherStep() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(options(true)); runCurrent()
        f.gateway.gui.single().result.complete(GuiReply("wait", PhoneAction("wait", mapOf("duration_ms" to 2_000)), "{}"))
        runCurrent()
        advanceTimeBy(500); runCurrent()
        f.coordinator.environmentUnavailable("AUTHORIZATION_UNAVAILABLE"); runCurrent()
        assertEquals(RunState.ENDED, f.state.state)
        assertEquals("runtime_failure", f.audit.ends().single()["reason"])
        assertEquals(1, f.state.guiUsed)
        assertEquals(0, f.state.plannerUsed)
        assertFalse(f.old.captureAllowed)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, f.old.observations)
        assertEquals(1, f.gateway.gui.size)
        assertTrue(f.audit.of("action").isEmpty()) // The interrupted wait did not complete.
        assertTrue(f.audit.of("recovery").isEmpty())
        assertNoBlocked(f)
    }

    private fun assertNoBlocked(f: Fixture) = assertTrue(f.states.none { it.state == RunState.BLOCKED })

    private class Fixture(scope: CoroutineScope) {
        val states = mutableListOf<RunSnapshot>()
        val bridge = PhoneBridge()
        val old = Port("old", states).also(bridge::attach)
        val gateway = Gateway()
        val audit = Audit()
        val coordinator = Coordinator(scope, gateway, bridge, audit)
        val state get() = coordinator.snapshot.value
        fun replacement() = Port("replacement", states).also(bridge::attach)
    }

    private class Port(private val label: String, private val states: MutableList<RunSnapshot>) : PhonePort {
        override var connected = true
        override var inputInFlight = false
        var configured: SessionOptions? = null
        var captureAllowed = false
        var holdNative = false
        var observations = 0
        val actions = mutableListOf<PhoneAction>()
        private var terminal: CompletableDeferred<ActionResult>? = null
        override fun configureSession(options: SessionOptions?) { configured = options; captureAllowed = false }
        override suspend fun observe(): ScreenFrame {
            check(connected && captureAllowed && configured != null)
            observations++
            return ScreenFrame("$label-$observations", byteArrayOf(1), 1080, 2280)
        }
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult {
            check(canDispatch())
            actions.add(action)
            if (!holdNative) return ActionResult("completed")
            inputInFlight = true
            return CompletableDeferred<ActionResult>().also { terminal = it }.await()
        }
        fun completeNative(status: String) {
            inputInFlight = false
            check(terminal!!.complete(ActionResult(status,
                failureReason = if (status == "cancelled") ExecutionFailureReason.GESTURE_CANCELLED else null)))
        }
        override fun setCaptureEnabled(enabled: Boolean) { captureAllowed = enabled }
        override fun showState(snapshot: RunSnapshot) { states.add(snapshot) }
    }

    private data class GuiCall(val context: GuiContext, val result: CompletableDeferred<GuiReply> = CompletableDeferred())
    private data class PlannerCall(val context: PlannerContext, val result: CompletableDeferred<PlannerDecision> = CompletableDeferred())
    private class Gateway : ModelGateway {
        val gui = mutableListOf<GuiCall>()
        val planner = mutableListOf<PlannerCall>()
        val ignoreCancellation = mutableSetOf<Int>()
        override suspend fun gui(context: GuiContext): GuiReply {
            val index = gui.size
            val call = GuiCall(context).also(gui::add)
            return if (index in ignoreCancellation) withContext(NonCancellable) { call.result.await() } else call.result.await()
        }
        override suspend fun planner(context: PlannerContext) = PlannerCall(context).also(planner::add).result.await()
        override fun cancelAll() = Unit
    }

    private class Audit : AuditSink {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun begin(sessionId: String, options: SessionOptions) = Unit
        override fun event(event: String, data: Map<String, Any?>) { events.add(event to data.toMap()) }
        fun of(event: String) = events.filter { it.first == event }.map { it.second }
        fun ends() = of("session_ended")
    }

    private companion object {
        fun options(harness: Boolean) = SessionOptions("PRIVATE_TASK", "synthetic.home", setOf("synthetic.authorized"), harness,
            30, ModelProfile(), ModelProfile(), StartMode.HOME_AUTO, "synthetic.home")
        fun tap() = GuiReply("tap", PhoneAction("tap", mapOf("x" to 500, "y" to 500)), "{}")
        fun finish() = GuiReply("finish", PhoneAction("finish", mapOf("outcome" to "completed", "message" to "PRIVATE_REPORT")), "{}")
    }
}
