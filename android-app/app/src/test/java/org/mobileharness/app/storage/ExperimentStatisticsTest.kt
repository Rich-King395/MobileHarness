package org.mobileharness.app.storage

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mobileharness.app.storage.ExperimentStatistics.CallStatus

class ExperimentStatisticsTest {
    @Test fun startedAndTerminalAreOnePlannerCallAndGuiWithSameIndexCannotOverwriteIt() {
        val result = statistics(
            start(), call("PLANNER", 1, "started", gui = 4, planner = 1),
            call("GUI", 1, "started", gui = 5, planner = 1),
            call("PLANNER", 1, "succeeded", duration = 813),
            call("GUI", 1, "invalid_output", duration = 17),
            decision(1, "INCOMPLETE", "EXECUTE"), end(gui = 5, planner = 1),
        )
        assertEquals(1, result.observedPlannerCalls)
        assertEquals(1, result.plannerUsed)
        assertEquals(5, result.guiUsed)
        assertEquals(30, result.guiBudget)
        assertEquals(true, result.harnessEnabled)
        assertTrue(result.ended)
        val call = result.plannerCalls.single()
        assertEquals(1, call.index)
        assertEquals(CallStatus.SUCCEEDED, call.status)
        assertEquals(813L, call.durationMs)
        assertNull(call.httpStatus)
        assertEquals("INCOMPLETE", call.completionStatus)
        assertEquals("EXECUTE", call.decision)
        assertTrue(call.startedRecorded)
    }

    @Test fun failedAndCancelledCallsConsumePlannerCountWithoutDuplicatingLogLines() {
        val result = statistics(
            start(), call("PLANNER", 1, "started", gui = 3, planner = 1),
            call("PLANNER", 1, "api_error", duration = 72, http = 503),
            call("PLANNER", 2, "started", gui = 3, planner = 2),
            call("PLANNER", 2, "cancelled", duration = 42),
            end(gui = 3, planner = 2),
        )
        assertEquals(2, result.plannerUsed)
        assertEquals(2, result.observedPlannerCalls)
        assertEquals(listOf(CallStatus.API_ERROR, CallStatus.CANCELLED), result.plannerCalls.map { it.status })
        assertEquals(503, result.plannerCalls[0].httpStatus)
        assertEquals(72L, result.plannerCalls[0].durationMs)
        assertNull(result.plannerCalls[0].decision)
        assertEquals(42L, result.plannerCalls[1].durationMs)
    }

    @Test fun transportProtocolAndLateReplyFailuresRemainDistinct() {
        listOf(
            "transport_error" to CallStatus.TRANSPORT_ERROR,
            "invalid_output" to CallStatus.INVALID_OUTPUT,
            "late_discarded" to CallStatus.LATE_DISCARDED,
        ).forEach { (status, expected) ->
            val result = statistics(start(), call("PLANNER", 1, "started", planner = 1),
                call("PLANNER", 1, status, duration = 9), end(gui = 0, planner = 1))
            assertEquals(expected, result.plannerCalls.single().status)
            assertEquals(1, result.plannerUsed)
            assertNull(result.plannerCalls.single().decision)
        }
    }

    @Test fun secondPlannerMayFinishDirectlyWithoutAnyGuiFinishCall() {
        val result = statistics(
            start(), call("GUI", 1, "started", gui = 1, planner = 0),
            call("GUI", 1, "succeeded"),
            call("PLANNER", 1, "started", gui = 1, planner = 1),
            call("PLANNER", 1, "succeeded", duration = 100), decision(1, "COMPLETE", "VERIFY"),
            call("GUI", 2, "started", gui = 2, planner = 1), call("GUI", 2, "succeeded"),
            action(2, "finish", "completed"),
            call("PLANNER", 2, "started", gui = 2, planner = 2),
            call("PLANNER", 2, "succeeded", duration = 200), decision(2, "COMPLETE", "FINISH"),
            end(gui = 2, planner = 2, source = "planner"),
        )
        assertEquals(2, result.guiUsed)
        assertEquals(2, result.plannerUsed)
        assertEquals(listOf("VERIFY", "FINISH"), result.plannerCalls.map { it.decision })
        assertEquals(listOf("COMPLETE", "COMPLETE"), result.plannerCalls.map { it.completionStatus })
    }

    @Test fun guiBudgetCountsFailuresWaitAndFinishInsteadOfOnlyCompletedActions() {
        val result = statistics(
            start(budget = 5),
            call("GUI", 1, "started", gui = 1, planner = 0), call("GUI", 1, "api_error"),
            call("GUI", 2, "started", gui = 2, planner = 0), call("GUI", 2, "invalid_output"),
            call("GUI", 3, "started", gui = 3, planner = 0), call("GUI", 3, "succeeded"),
            action(3, "wait", "completed"),
            call("GUI", 4, "started", gui = 4, planner = 0), call("GUI", 4, "succeeded"),
            action(4, "tap", "rejected"),
            call("GUI", 5, "started", gui = 5, planner = 0), call("GUI", 5, "succeeded"),
            action(5, "finish", "completed"), end(gui = 5, planner = 0),
        )
        assertEquals(5, result.guiUsed)
        assertEquals(5, result.guiBudget)
        assertEquals(0, result.plannerUsed)
        assertTrue(result.plannerCalls.isEmpty())
    }

    @Test fun activeCountersComeFromStartedMaximaNotActionOrTerminalRows() {
        val result = statistics(
            start(), call("GUI", 1, "started", gui = 1, planner = 0),
            call("GUI", 1, "succeeded", gui = 999, planner = 999),
            action(500, "tap", "completed"),
            call("PLANNER", 1, "started", gui = 3, planner = 1),
            call("GUI", 2, "started", gui = 2, planner = 0),
        )
        assertEquals(3, result.guiUsed)
        assertEquals(1, result.plannerUsed)
        assertFalse(result.ended)
        assertEquals(CallStatus.IN_FLIGHT, result.plannerCalls.single().status)
    }

    @Test fun explicitEndCountsAreAuthoritativeIncludingZero() {
        val result = statistics(start(), call("PLANNER", 1, "started", gui = 8, planner = 1),
            end(gui = 0, planner = 0))
        assertEquals(0, result.guiUsed)
        assertEquals(0, result.plannerUsed)
        assertEquals(1, result.observedPlannerCalls)
    }

    @Test fun incompleteCallIsInFlightOnlyUntilSessionEndsAndHasNoMadeUpDuration() {
        val rows = arrayOf(start(), call("PLANNER", 1, "started", duration = 0, planner = 1))
        val active = statistics(*rows).plannerCalls.single()
        assertEquals(CallStatus.IN_FLIGHT, active.status)
        assertNull(active.durationMs)
        val ended = statistics(*rows, end(gui = 0, planner = 1)).plannerCalls.single()
        assertEquals(CallStatus.NOT_RECORDED, ended.status)
        assertNull(ended.durationMs)
        assertNull(ended.httpStatus)
        assertNull(ended.decision)
    }

    @Test fun legacyMissingFieldsStayUnknownAndDoNotInventSuccessOrZeroCounts() {
        val result = statistics(event("session_started"), call("PLANNER", 1, "started"),
            event("session_ended"))
        assertNull(result.guiUsed)
        assertNull(result.plannerUsed)
        assertNull(result.guiBudget)
        assertNull(result.harnessEnabled)
        assertEquals(CallStatus.NOT_RECORDED, result.plannerCalls.single().status)
        assertNull(result.plannerCalls.single().durationMs)
    }

    @Test fun noPlannerInvocationIsDistinguishedFromHarnessDisabled() {
        val enabled = statistics(start(harness = true), end(gui = 2, planner = 0))
        val disabled = statistics(start(harness = false), end(gui = 2, planner = 0))
        assertEquals(0, enabled.plannerUsed)
        assertEquals(0, disabled.plannerUsed)
        assertTrue(enabled.plannerCalls.isEmpty())
        assertTrue(disabled.plannerCalls.isEmpty())
        assertEquals(true, enabled.harnessEnabled)
        assertEquals(false, disabled.harnessEnabled)
    }

    @Test fun terminalWithoutStartPreservesEvidenceButOrphanDecisionCannotFabricateACall() {
        val result = statistics(event("session_started"),
            call("PLANNER", 2, "succeeded", duration = 65, http = 200),
            decision(2, "UNVERIFIABLE", "FALLBACK"), decision(1, "COMPLETE", "FINISH"))
        assertEquals(1, result.observedPlannerCalls)
        assertNull(result.plannerUsed)
        val call = result.plannerCalls.single()
        assertEquals(2, call.index)
        assertFalse(call.startedRecorded)
        assertEquals(CallStatus.SUCCEEDED, call.status)
        assertEquals(65L, call.durationMs)
        assertEquals(200, call.httpStatus)
        assertEquals("UNVERIFIABLE", call.completionStatus)
        assertEquals("FALLBACK", call.decision)
    }

    @Test fun duplicatesDoNotDoubleCountAndRecordedIndexesAreNotTruncatedToTheDesignLimit() {
        val first = call("PLANNER", 1, "started", planner = 1)
        val terminal = call("PLANNER", 1, "succeeded", duration = 31)
        val result = statistics(start(), first, first, terminal, terminal,
            call("PLANNER", 3, "started", planner = 3), call("PLANNER", 3, "api_error"),
            end(gui = 0, planner = 3))
        assertEquals(3, result.plannerUsed)
        assertEquals(listOf(1, 3), result.plannerCalls.map { it.index })
        assertEquals(2, result.observedPlannerCalls)
    }

    @Test fun invalidNumbersAndArbitraryStringsDoNotBecomeSyntheticMetricsOrLabels() {
        val result = statistics(
            event("session_started", "harness_enabled" to "true", "gui_budget" to "30"),
            event("model_call", "role" to "PLANNER", "call_index" to 0, "status" to "started"),
            event("model_call", "role" to "PLANNER", "call_index" to 1, "status" to "started",
                "gui_steps_used" to 1.5, "planner_calls_used" to -1),
            event("model_call", "role" to "PLANNER", "call_index" to 1, "status" to "succeeded",
                "duration_ms" to -2, "http_status" to 999),
            decision(1, "arbitrary body", "arbitrary body"),
            event("session_ended", "gui_steps_used" to Long.MAX_VALUE,
                "planner_calls_used" to JSONObject.NULL),
        )
        assertNull(result.guiUsed)
        assertNull(result.plannerUsed)
        assertNull(result.guiBudget)
        assertNull(result.harnessEnabled)
        val call = result.plannerCalls.single()
        assertNull(call.durationMs)
        assertNull(call.httpStatus)
        assertNull(call.completionStatus)
        assertNull(call.decision)
    }

    @Test fun recoveryCountStaysUnknownForLegacyButIsZeroForSchema13WithoutAttempts() {
        assertNull(statistics(start(), end(gui = 0, planner = 0)).automaticRecoveries)
        assertEquals(0, statistics(start().put("schema_version", "1.3.0"), end(gui = 0, planner = 0)).automaticRecoveries)
    }

    @Test fun recoveriesCountActualStartsOnceAndNotSchedulesOrCancellations() {
        val first = recovery(1, "started")
        val result = statistics(start().put("schema_version", "1.3.0"), recovery(1, "scheduled"), first, first,
            recovery(2, "scheduled", attempt = 2), recovery(2, "cancelled", attempt = 2),
            recovery(3, "started", target = "OBSERVATION"), end(gui = 0, planner = 0))
        assertEquals(2, result.automaticRecoveries)
        assertEquals(0, result.guiUsed)
        assertEquals(0, result.plannerUsed)
        assertTrue(result.plannerCalls.isEmpty())
    }

    @Test fun malformedRecoveryFieldsDoNotInventAttempts() {
        val invalid = listOf(
            recovery(0, "started"), recovery(1, "body"), recovery(2, "started", attempt = 3),
            recovery(3, "started", target = "PROVIDER_BODY"),
            recovery(4, "started").also { it.getJSONObject("data").put("delay_ms", 0) },
            recovery(5, "started").also { it.getJSONObject("data").put("recovery_id", "5") },
            recovery(6, "started").also { it.getJSONObject("data").put("trigger_code", "PRIVATE_REASON") },
        )
        assertEquals(0, statistics(start().put("schema_version", "1.3.0"), *invalid.toTypedArray(), end(gui = 0, planner = 0)).automaticRecoveries)
    }

    @Test fun recoveryEventsCannotResetOrInflateTheSharedGuiAndPlannerCounters() {
        val result = statistics(start().put("schema_version", "1.3.0"),
            call("GUI", 1, "started", gui = 1, planner = 0), call("GUI", 1, "transport_error"),
            recovery(1, "scheduled"), recovery(1, "started"),
            call("GUI", 2, "started", gui = 2, planner = 0), call("GUI", 2, "succeeded"),
            end(gui = 2, planner = 0).also { it.getJSONObject("data").put("reason", "runtime_failure") })
        assertEquals(1, result.automaticRecoveries)
        assertEquals(2, result.guiUsed)
        assertEquals(0, result.plannerUsed)
        assertTrue(result.ended)
    }

    private fun statistics(vararg records: JSONObject) = ExperimentStatistics.fromRecords(records.toList())

    private fun event(name: String, vararg fields: Pair<String, Any?>) = JSONObject()
        .put("event", name).put("data", JSONObject(fields.toMap()))

    private fun start(harness: Boolean = true, budget: Int = 30) = event("session_started",
        "harness_enabled" to harness, "gui_budget" to budget)

    private fun call(role: String, index: Int, status: String, gui: Int? = null, planner: Int? = null,
        duration: Long? = null, http: Int? = null) = event("model_call",
        "role" to role, "call_index" to index, "status" to status,
        "gui_steps_used" to gui, "planner_calls_used" to planner,
        "duration_ms" to duration, "http_status" to http)

    private fun decision(index: Int, completion: String, decision: String) = event("planner_decision",
        "planner_call_index" to index, "completion_status" to completion, "decision" to decision)

    private fun end(gui: Int, planner: Int, source: String = "none") = event("session_ended",
        "gui_steps_used" to gui, "planner_calls_used" to planner, "completion_source" to source)

    private fun action(step: Int, type: String, result: String) = event("action",
        "gui_step" to step, "action_type" to type, "result" to result)

    private fun recovery(id: Int, status: String, attempt: Int = 1, target: String = "GUI") = event("recovery",
        "recovery_id" to id, "status" to status, "attempt" to attempt, "delay_ms" to if (attempt == 1) 1_000 else 3_000,
        "target" to target, "trigger_code" to "TRANSPORT_ERROR")
}
