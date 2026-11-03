package org.mobileharness.app.storage

import org.json.JSONObject
import org.mobileharness.app.core.ExecutionFailureReason
import org.mobileharness.app.model.ProtocolReason

/** Derived, content-free analysis. It never reads task attachments or writes runtime events. */
object ExperimentAnalysis {
    const val VERSION = "1.0.0"

    data class CallMetrics(
        val started: Int = 0,
        val validResponses: Int = 0,
        val failed: Int = 0,
        val cancelled: Int = 0,
        val lateDiscarded: Int = 0,
        val inFlight: Int = 0,
        val missingTerminal: Int = 0,
        val durationSamples: Int = 0,
        val totalDurationMs: Long? = null,
        val orphanTerminals: Int = 0,
    )

    data class ErrorCount(val category: String, val code: String, val detail: String?, val count: Int)

    data class Versions(
        val schemaVersion: String? = null,
        val promptBundleVersion: String? = null,
        val adapterVersion: String? = null,
        val executionPolicyVersion: String? = null,
        val bundleManifestSha256: String? = null,
        val guiProfileId: String? = null,
        val plannerProfileId: String? = null,
    )

    data class Analysis(
        val sessionId: String?,
        val statistics: ExperimentStatistics.Statistics,
        val endReason: String?,
        val completionSource: String?,
        val userAssessment: String?,
        val durationMs: Long?,
        val recordedDurationMs: Long?,
        val takeoverCount: Int,
        val returnCount: Int,
        val guiCalls: CallMetrics,
        val plannerCalls: CallMetrics,
        val errors: List<ErrorCount>,
        val versions: Versions,
    ) {
        val ended: Boolean get() = statistics.ended
    }

    data class Mean(val value: Double?, val samples: Int)
    data class Group(
        val sessions: Int,
        val rated: Int,
        val completed: Int,
        val incomplete: Int,
        val unrated: Int,
        val meanGuiSteps: Mean,
        val meanDurationMs: Mean,
    ) {
        /** Explicit user reports only. A valid model response or finish never enters this ratio. */
        val completionRate: Double? get() = if (rated > 0) completed.toDouble() / rated else null
    }
    data class Comparison(val on: Group, val off: Group, val unknown: Group)

    /** One local session in file order. Repeated sequence numbers cannot inflate event counts. */
    fun fromRecords(records: List<JSONObject>): Analysis {
        val start = records.firstOrNull { it.optString("event") == "session_started" }
        val sessionId = start?.string("session_id")?.takeIf(uuid::matches)
        val seen = mutableSetOf<Long>()
        val rows = if (sessionId == null) emptyList() else records.filter { record ->
            record.string("session_id") == sessionId &&
                (record.number("seq")?.let { it > 0 && seen.add(it) } ?: true)
        }
        val actualStart = rows.firstOrNull { it.optString("event") == "session_started" }
        val startData = actualStart?.optJSONObject("data")
        val end = rows.lastOrNull { it.optString("event") == "session_ended" }
        val endData = end?.optJSONObject("data")
        val endReason = endData?.string("reason")?.takeIf { it in endReasons }
        val endSource = endData?.string("completion_source")?.takeIf { it in completionSources }
        val untilEnd = if (end == null) rows else rows.take(rows.indexOfLast { it === end } + 1)
        val elapsed = untilEnd.map { it.number("elapsed_ms") }
        val monotonic = elapsed.isNotEmpty() && elapsed.all { it != null } &&
            elapsed.zipWithNext().all { (before, after) -> after!! >= before!! }
        val origin = actualStart?.number("elapsed_ms")
        val last = untilEnd.lastOrNull()?.number("elapsed_ms")
        val recordedDuration = if (monotonic && origin != null && last != null && last >= origin) last - origin else null
        val statistics = ExperimentStatistics.fromRecords(rows)
        val assessment = rows.filter { it.optString("event") == "task_assessment" }
            .mapNotNull { it.optJSONObject("data")?.string("assessment")?.takeIf { value -> value in assessments } }
            .lastOrNull()?.takeUnless { it == "unknown" }
        val transitions = rows.filter { it.optString("event") == "control" }
            .mapNotNull { it.optJSONObject("data")?.string("transition") }
        val errors = rows.filter { it.optString("event") == "error" }
            .mapNotNull { it.optJSONObject("data")?.let(::errorKey) }
            .groupingBy { it }.eachCount().map { (key, count) -> ErrorCount(key.first, key.second, key.third, count) }
            .sortedWith(compareBy({ it.category }, { it.code }, { it.detail.orEmpty() }))
        return Analysis(
            sessionId, statistics, endReason, endSource, assessment,
            durationMs = recordedDuration.takeIf { end != null && endReason != null && endReason != "interrupted" },
            recordedDurationMs = recordedDuration,
            takeoverCount = transitions.count { it == "manual_granted" },
            returnCount = transitions.count { it == "return_committed" },
            guiCalls = calls(rows, "GUI", statistics.ended),
            plannerCalls = calls(rows, "PLANNER", statistics.ended),
            errors = errors,
            versions = Versions(
                schemaVersion = actualStart?.string("schema_version")?.takeIf { it in schemas },
                promptBundleVersion = startData?.string("prompt_bundle_version")?.takeIf(version::matches),
                adapterVersion = startData?.string("adapter_version")?.takeIf(version::matches),
                executionPolicyVersion = startData?.string("execution_policy_version")?.takeIf(version::matches),
                bundleManifestSha256 = startData?.string("bundle_manifest_sha256")?.takeIf(hash::matches),
                guiProfileId = startData?.string("gui_profile_id")?.takeIf(uuid::matches),
                plannerProfileId = startData?.string("planner_profile_id")?.takeIf(uuid::matches),
            ),
        )
    }

    /** No matching, causal comparison or significance claim: only descriptive ON/OFF groups. */
    fun aggregate(analyses: List<Analysis>): Comparison {
        val eligible = eligible(analyses)
        return Comparison(group(eligible.filter { it.statistics.harnessEnabled == true }),
            group(eligible.filter { it.statistics.harnessEnabled == false }),
            group(eligible.filter { it.statistics.harnessEnabled == null }))
    }

    /** Fixed columns; ended, valid-ID rows only. Unknown cells stay empty, never invented zero. */
    fun csv(analyses: List<Analysis>): String = buildString {
        append(csvLine(columns))
        eligible(analyses).sortedBy { it.sessionId }.forEach { row ->
            val stats = row.statistics
            val v = row.versions
            val errors = row.errors.mapNotNull { item ->
                safeError(item)?.let { "${it.category}/${it.code}${it.detail?.let { detail -> "/$detail" }.orEmpty()}:${it.count}" }
            }
            append(csvLine(listOf(
                VERSION, row.sessionId, v.schemaVersion?.takeIf { it in schemas }, stats.harnessEnabled?.toString(),
                stats.guiUsed.safeCell(), stats.guiBudget.safeCell(), stats.plannerUsed.safeCell(),
                row.durationMs.safeCell(), row.recordedDurationMs.safeCell(), row.endReason?.takeIf { it in endReasons },
                row.completionSource?.takeIf { it in completionSources }, row.userAssessment?.takeIf { it in ratedAssessments },
                row.takeoverCount.safeCell(), row.returnCount.safeCell(), stats.automaticRecoveries.safeCell(),
            ) + metricCells(row.guiCalls) + metricCells(row.plannerCalls) + listOf(
                row.errors.filter { safeError(it)?.category == "MODEL" }.sumOf { it.count.toLong() }.toString(),
                row.errors.filter { safeError(it)?.category == "DEVICE" }.sumOf { it.count.toLong() }.toString(),
                row.errors.filter { safeError(it)?.category == "COORDINATOR" }.sumOf { it.count.toLong() }.toString(),
                errors.joinToString(";"),
                v.promptBundleVersion?.takeIf(version::matches), v.adapterVersion?.takeIf(version::matches),
                v.executionPolicyVersion?.takeIf(version::matches), v.bundleManifestSha256?.takeIf(hash::matches),
                v.guiProfileId?.takeIf(uuid::matches), v.plannerProfileId?.takeIf(uuid::matches),
            )))
        }
    }

    private fun eligible(rows: List<Analysis>) = rows.filter { it.ended && it.sessionId?.let(uuid::matches) == true }
        .associateBy { it.sessionId }.values.toList() // A later snapshot replaces an earlier one, not a second task.

    private fun group(rows: List<Analysis>): Group {
        val rated = rows.count { it.userAssessment in ratedAssessments }
        val completed = rows.count { it.userAssessment == "user_reported_complete" }
        fun mean(values: List<Double>) = Mean(values.takeIf { it.isNotEmpty() }?.average(), values.size)
        return Group(rows.size, rated, completed, rated - completed, rows.size - rated,
            mean(rows.mapNotNull { it.statistics.guiUsed?.takeIf { n -> n >= 0 }?.toDouble() }),
            mean(rows.mapNotNull { it.durationMs?.takeIf { n -> n in 0..MAX_NUMBER }?.toDouble() }))
    }

    private class MutableCall {
        var started = false
        var terminal: String? = null
        var durationMs: Long? = null
    }
    private fun calls(rows: List<JSONObject>, role: String, ended: Boolean): CallMetrics {
        val calls = linkedMapOf<Long, MutableCall>()
        rows.filter { it.optString("event") == "model_call" }.forEach { record ->
            val data = record.optJSONObject("data") ?: return@forEach
            if (data.string("role") != role) return@forEach
            val index = data.number("call_index")?.takeIf { it > 0 } ?: return@forEach
            val status = data.string("status")?.takeIf { it == "started" || it in terminalStatuses } ?: return@forEach
            val call = calls.getOrPut(index) { MutableCall() }
            if (status == "started") call.started = true else {
                // Distinct attempts are identified by role + call_index, not event count.
                call.terminal = status
                call.durationMs = data.number("duration_ms")
            }
        }
        val started = calls.values.filter { it.started }
        val terminal = started.filter { it.terminal != null }
        val durations = terminal.mapNotNull { it.durationMs }
        val total = if (terminal.isNotEmpty() && durations.size == terminal.size) durations.fold(0L as Long?) { sum, n ->
            sum?.takeIf { it <= Long.MAX_VALUE - n }?.plus(n)
        } else null
        return CallMetrics(started.size, started.count { it.terminal == "succeeded" },
            started.count { it.terminal in failedStatuses }, started.count { it.terminal == "cancelled" },
            started.count { it.terminal == "late_discarded" },
            if (ended) 0 else started.count { it.terminal == null },
            if (ended) started.count { it.terminal == null } else 0,
            durations.size, total, calls.values.count { !it.started && it.terminal != null })
    }

    private fun errorKey(data: JSONObject): Triple<String, String, String?>? {
        val category = data.string("category") ?: return null
        val code = data.string("code")?.takeIf { it in errorCodes[category].orEmpty() } ?: return null
        val detail = when {
            category == "MODEL" && code == "TRANSPORT_ERROR" -> data.string("transport_reason")?.takeIf { it in transportReasons }
            category == "MODEL" && code == "API_ERROR" -> data.number("http_status")?.takeIf { it in 100..599 }?.let { "HTTP_$it" }
            category == "MODEL" && code == "INVALID_OUTPUT" -> data.string("protocol_reason")?.takeIf { it in protocolReasons }
            category == "DEVICE" -> data.string("execution_failure_reason")?.takeIf { it in executionReasons }
            else -> null
        }
        return Triple(category, code, detail)
    }
    private fun safeError(value: ErrorCount): ErrorCount? {
        if (value.count <= 0 || value.code !in errorCodes[value.category].orEmpty()) return null
        val detail = value.detail?.takeIf { when {
            value.category == "MODEL" && value.code == "TRANSPORT_ERROR" -> it in transportReasons
            value.category == "MODEL" && value.code == "API_ERROR" -> httpDetail.matches(it)
            value.category == "MODEL" && value.code == "INVALID_OUTPUT" -> it in protocolReasons
            value.category == "DEVICE" -> it in executionReasons
            else -> false
        } }
        return value.copy(detail = detail)
    }

    private fun metricCells(m: CallMetrics) = listOf(m.started.safeCell(), m.validResponses.safeCell(), m.failed.safeCell(),
        m.cancelled.safeCell(), m.lateDiscarded.safeCell(), m.inFlight.safeCell(), m.missingTerminal.safeCell(),
        m.durationSamples.safeCell(), m.totalDurationMs.safeCell(), m.orphanTerminals.safeCell())
    private fun csvLine(values: List<String?>) = values.joinToString(",") { "\"${it.orEmpty().replace("\"", "\"\"")}\"" } + "\r\n"
    private fun Int?.safeCell(): String? = this?.takeIf { it >= 0 }?.toString()
    private fun Long?.safeCell(): String? = this?.takeIf { it >= 0 }?.toString()
    private fun JSONObject.string(key: String) = opt(key) as? String
    private fun JSONObject.number(key: String): Long? = when (val v = opt(key)) {
        is Byte -> v.toLong(); is Short -> v.toLong(); is Int -> v.toLong(); is Long -> v
        else -> null
    }?.takeIf { it in 0..MAX_NUMBER }

    private const val MAX_NUMBER = 9_007_199_254_740_991L
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val hash = Regex("[0-9a-f]{64}")
    private val httpDetail = Regex("HTTP_[1-5][0-9]{2}")
    private val version = Regex("(?:0|[1-9][0-9]{0,2})\\.(?:0|[1-9][0-9]{0,2})\\.(?:0|[1-9][0-9]{0,2})")
    private val schemas = setOf("1.0.0", "1.1.0", "1.2.0", "1.3.0")
    private val endReasons = setOf("model_declared_complete", "model_unable", "user_stop", "budget_exhausted", "interrupted", "runtime_failure")
    private val completionSources = setOf("none", "gui", "planner", "confirmation")
    private val ratedAssessments = setOf("user_reported_complete", "user_reported_incomplete")
    private val assessments = ratedAssessments + "unknown"
    private val terminalStatuses = setOf("succeeded", "transport_error", "api_error", "invalid_output", "cancelled", "late_discarded")
    private val failedStatuses = setOf("transport_error", "api_error", "invalid_output")
    private val errorCodes = mapOf(
        "MODEL" to setOf("TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT"),
        "DEVICE" to setOf("AUTHORIZATION_UNAVAILABLE", "SCREENSHOT_FAILED", "INPUT_FAILED", "INPUT_STATUS_UNKNOWN", "SERVICE_DISCONNECTED"),
        "COORDINATOR" to setOf("INVALID_STATE", "PAUSE_UNCONFIRMED", "STOP_ENTRY_UNAVAILABLE", "OBSERVATION_UNAVAILABLE"),
    )
    private val transportReasons = setOf("DNS_FAILURE", "TLS_HANDSHAKE_FAILED", "TLS_PEER_UNVERIFIED", "TIMEOUT", "CONNECT_FAILED", "REQUEST_REPLAY_BLOCKED", "NETWORK_IO")
    private val protocolReasons = ProtocolReason.entries.filter { it != ProtocolReason.VALID }.map { it.name }.toSet()
    private val executionReasons = ExecutionFailureReason.entries.map { it.name }.toSet()
    private val columns = listOf(
        "analysis_version", "session_id", "schema_version", "harness_enabled", "gui_steps", "gui_budget", "planner_calls",
        "duration_ms", "recorded_duration_ms", "end_reason", "completion_source", "user_assessment", "takeover_count", "return_count", "automatic_recoveries",
    ) + listOf("gui", "planner").flatMap { role -> listOf("attempts_started", "valid_responses", "failed_calls", "cancelled_calls", "late_discarded_calls",
        "in_flight_calls", "missing_terminal_calls", "duration_samples", "call_duration_ms", "orphan_terminals").map { "${role}_$it" } } + listOf(
        "model_error_events", "device_error_events", "coordinator_error_events", "error_summary", "prompt_bundle_version", "adapter_version",
        "execution_policy_version", "bundle_manifest_sha256", "gui_profile_id", "planner_profile_id",
    )
}
