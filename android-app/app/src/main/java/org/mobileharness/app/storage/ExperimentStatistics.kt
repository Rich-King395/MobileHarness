package org.mobileharness.app.storage

import org.json.JSONObject

/** Reads only fixed metadata fields; never carries task text, model bodies or credentials. */
object ExperimentStatistics {
    enum class CallStatus {
        SUCCEEDED, TRANSPORT_ERROR, API_ERROR, INVALID_OUTPUT, CANCELLED, LATE_DISCARDED,
        IN_FLIGHT, NOT_RECORDED,
    }

    data class PlannerCall(
        val index: Int,
        val status: CallStatus,
        val durationMs: Long?,
        val httpStatus: Int?,
        val completionStatus: String?,
        val decision: String?,
        val startedRecorded: Boolean,
    )

    data class Statistics(
        val guiUsed: Int?,
        val guiBudget: Int?,
        val plannerUsed: Int?,
        val harnessEnabled: Boolean?,
        val plannerCalls: List<PlannerCall>,
        val ended: Boolean,
        val automaticRecoveries: Int? = null,
    ) {
        /** Distinct call indexes with model_call evidence, not started + terminal log lines. */
        val observedPlannerCalls: Int get() = plannerCalls.size
    }

    /** Input is one session's records in file order. Missing legacy fields stay unknown. */
    fun fromRecords(records: List<JSONObject>): Statistics {
        val startRecord = records.firstOrNull { it.optString("event") == "session_started" }
        val start = startRecord?.optJSONObject("data")
        val endRecord = records.lastOrNull { it.optString("event") == "session_ended" }
        val end = endRecord?.optJSONObject("data")
        val modelCalls = records.asSequence().filter { it.optString("event") == "model_call" }
            .mapNotNull { it.optJSONObject("data") }
            .filter { it.optString("role") in setOf("GUI", "PLANNER") }.toList()
        val started = modelCalls.filter { it.optString("status") == "started" }
        val calls = linkedMapOf<Int, MutableCall>()
        modelCalls.filter { it.optString("role") == "PLANNER" }.forEach { data ->
            val index = data.nonNegativeInt("call_index")?.takeIf { it > 0 } ?: return@forEach
            val call = calls.getOrPut(index) { MutableCall() }
            if (data.optString("status") == "started") call.startedRecorded = true
            else terminalStatuses[data.optString("status")]?.let { status ->
                call.status = status
                call.durationMs = data.nonNegativeLong("duration_ms")
                call.httpStatus = data.nonNegativeInt("http_status")?.takeIf { it in 100..599 }
            }
        }
        records.filter { it.optString("event") == "planner_decision" }.forEach { record ->
            val data = record.optJSONObject("data") ?: return@forEach
            val index = data.nonNegativeInt("planner_call_index") ?: return@forEach
            // A decision without model_call evidence cannot fabricate an additional call.
            val call = calls[index] ?: return@forEach
            call.completionStatus = data.optString("completion_status").takeIf { it in completionStatuses }
            call.decision = data.optString("decision").takeIf { it in decisions }
        }
        val recoveries = records.filter { it.optString("event") == "recovery" }.mapNotNull { it.optJSONObject("data") }
            .filter { data -> data.nonNegativeLong("recovery_id")?.let { it > 0 } == true &&
                data.optString("target") in setOf("GUI", "OBSERVATION", "PLANNER_OBSERVATION") &&
                data.optString("trigger_code") in recoveryTriggerCodes &&
                data.nonNegativeInt("attempt") in setOf(1, 2) &&
                data.nonNegativeInt("delay_ms") == (if (data.nonNegativeInt("attempt") == 1) 1_000 else 3_000) &&
                data.optString("status") in setOf("scheduled", "started", "cancelled") }
        return Statistics(
            guiUsed = end?.nonNegativeInt("gui_steps_used")
                ?: started.mapNotNull { it.nonNegativeInt("gui_steps_used") }.maxOrNull(),
            guiBudget = start?.nonNegativeInt("gui_budget"),
            plannerUsed = end?.nonNegativeInt("planner_calls_used")
                ?: started.mapNotNull { it.nonNegativeInt("planner_calls_used") }.maxOrNull(),
            harnessEnabled = start?.opt("harness_enabled") as? Boolean,
            plannerCalls = calls.toSortedMap().map { (index, call) ->
                PlannerCall(index, call.status ?: if (endRecord == null && call.startedRecorded)
                    CallStatus.IN_FLIGHT else CallStatus.NOT_RECORDED,
                    call.durationMs, call.httpStatus, call.completionStatus, call.decision, call.startedRecorded)
            },
            ended = endRecord != null,
            automaticRecoveries = if (startRecord?.optString("schema_version") == "1.3.0" || recoveries.isNotEmpty())
                recoveries.filter { it.optString("status") == "started" }.mapNotNull { it.nonNegativeLong("recovery_id") }.distinct().size
                else null,
        )
    }

    private class MutableCall {
        var startedRecorded = false
        var status: CallStatus? = null
        var durationMs: Long? = null
        var httpStatus: Int? = null
        var completionStatus: String? = null
        var decision: String? = null
    }

    private val terminalStatuses = mapOf(
        "succeeded" to CallStatus.SUCCEEDED,
        "transport_error" to CallStatus.TRANSPORT_ERROR,
        "api_error" to CallStatus.API_ERROR,
        "invalid_output" to CallStatus.INVALID_OUTPUT,
        "cancelled" to CallStatus.CANCELLED,
        "late_discarded" to CallStatus.LATE_DISCARDED,
    )
    private val completionStatuses = setOf("COMPLETE", "INCOMPLETE", "UNVERIFIABLE")
    private val decisions = setOf("EXECUTE", "VERIFY", "FALLBACK", "FINISH")
    private val recoveryTriggerCodes = setOf("TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT", "AUTHORIZATION_UNAVAILABLE",
        "SCREENSHOT_FAILED", "INPUT_FAILED", "INPUT_STATUS_UNKNOWN", "SERVICE_DISCONNECTED", "INVALID_STATE",
        "PAUSE_UNCONFIRMED", "STOP_ENTRY_UNAVAILABLE", "OBSERVATION_UNAVAILABLE")

    private fun JSONObject.nonNegativeInt(key: String): Int? =
        nonNegativeLong(key)?.takeIf { it <= Int.MAX_VALUE }?.toInt()

    private fun JSONObject.nonNegativeLong(key: String): Long? = when (val value = opt(key)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it >= 0 }
}
