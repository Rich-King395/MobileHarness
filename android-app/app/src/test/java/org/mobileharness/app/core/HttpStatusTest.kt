package org.mobileharness.app.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic failure metadata only: no HTTP connection, response body, credentials, or device. */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpStatusTest {
    @Test fun guiApiFailureKeepsOnlyStatusAndExistingErrorClassification() = runTest {
        for (httpStatus in listOf(400, 401, 403, 429, 500, 503)) {
            val failure = RunFailure("MODEL", "API_ERROR", httpStatus)
            val gateway = FailureGateway(guiFailure = failure)
            val audit = MetadataAudit()
            val coordinator = Coordinator(backgroundScope, gateway, SyntheticPhone(), audit)
            coordinator.start(options())
            runCurrent()
            assertEquals("API_ERROR", failure.message)
            assertEquals(RunState.BLOCKED, coordinator.snapshot.value.state)
            assertEquals(1, coordinator.snapshot.value.guiUsed)
            val terminal = audit.calls("GUI").single { it["status"] != "started" }
            assertEquals("api_error", terminal["status"])
            assertEquals(httpStatus, terminal["http_status"])
            assertFalse(audit.calls("GUI").single { it["status"] == "started" }.containsKey("http_status"))
            assertTrue(audit.events.any { it.first == "error" && it.second == mapOf("category" to "MODEL", "code" to "API_ERROR") })
            assertFalse(audit.events.toString().contains("SYNTHETIC_PRIVATE"))
        }
    }

    @Test fun plannerApiFailureKeepsStatusWithoutAddingRetryOrGuiCancellationStatus() = runTest {
        val gateway = FailureGateway(plannerFailure = RunFailure("MODEL", "API_ERROR", 403))
        val audit = MetadataAudit()
        val coordinator = Coordinator(backgroundScope, gateway, SyntheticPhone(), audit)
        coordinator.start(options())
        runCurrent()
        coordinator.takeover()
        runCurrent()
        assertEquals(RunState.MANUAL, coordinator.snapshot.value.state)
        coordinator.giveBack()
        runCurrent()
        assertEquals(1, coordinator.snapshot.value.plannerUsed)
        assertEquals(2, coordinator.snapshot.value.guiUsed) // original attempt plus existing fallback
        assertEquals(1, gateway.plannerCalls)
        val plannerTerminal = audit.calls("PLANNER").single { it["status"] != "started" }
        assertEquals("api_error", plannerTerminal["status"])
        assertEquals(403, plannerTerminal["http_status"])
        assertTrue(audit.calls("GUI").all { !it.containsKey("http_status") })
        assertFalse(audit.events.toString().contains("SYNTHETIC_PRIVATE"))
    }

    @Test fun absentAndOutOfRangeHttpCodesDoNotEnterMetadata() = runTest {
        for (httpStatus in listOf(null, 99, 600)) {
            val gateway = FailureGateway(guiFailure = RunFailure("MODEL", "API_ERROR", httpStatus))
            val audit = MetadataAudit()
            val coordinator = Coordinator(backgroundScope, gateway, SyntheticPhone(), audit)
            coordinator.start(options())
            runCurrent()
            assertTrue(audit.calls("GUI").all { !it.containsKey("http_status") })
            assertEquals(1, coordinator.snapshot.value.guiUsed)
        }
    }

    // These frozen assertions intentionally exercise legacy execution policy 1.1.
    private fun options() = SessionOptions("SYNTHETIC_PRIVATE_TASK", "org.synthetic", setOf("org.synthetic"), true,
        6, ModelProfile(apiKey = "SYNTHETIC_PRIVATE_KEY"), ModelProfile(apiKey = "SYNTHETIC_PRIVATE_KEY"), automaticRecovery = false)

    private class FailureGateway(var guiFailure: RunFailure? = null, private val plannerFailure: RunFailure? = null) : ModelGateway {
        var plannerCalls = 0
        override suspend fun gui(context: GuiContext): GuiReply {
            guiFailure?.let { guiFailure = null; throw it }
            return CompletableDeferred<GuiReply>().await()
        }
        override suspend fun planner(context: PlannerContext): PlannerDecision {
            plannerCalls++
            plannerFailure?.let { throw it }
            return CompletableDeferred<PlannerDecision>().await()
        }
        override fun cancelAll() = Unit
    }

    private class SyntheticPhone : PhonePort {
        override val connected = true
        override val inputInFlight = false
        private var ordinal = 0
        override suspend fun observe() = ScreenFrame("synthetic-${++ordinal}", byteArrayOf(1), 1, 1)
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean) = ActionResult("completed")
        override fun setCaptureEnabled(enabled: Boolean) = Unit
        override fun showState(snapshot: RunSnapshot) = Unit
    }

    private class MetadataAudit : AuditSink {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun begin(sessionId: String, options: SessionOptions) = Unit
        override fun event(event: String, data: Map<String, Any?>) { events.add(event to data.toMap()) }
        fun calls(role: String) = events.filter { it.first == "model_call" && it.second["role"] == role }.map { it.second }
    }
}
