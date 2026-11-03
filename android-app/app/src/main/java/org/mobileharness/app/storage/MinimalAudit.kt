package org.mobileharness.app.storage

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONObject
import org.mobileharness.app.core.AuditSink
import org.mobileharness.app.core.ExecutionFailureReason
import org.mobileharness.app.core.SessionOptions
import org.mobileharness.app.model.ProtocolReason
import java.io.File
import java.security.MessageDigest

/** Closed metadata allowlist. Task text is a separate encrypted local-only attachment. */
class MinimalAudit(
    private val context: Context,
    private val localTasks: LocalTaskRecordStore = LocalTaskRecordStore(context),
) : AuditSink {
    data class SessionSummary(
        val id: String, val ended: Boolean, val reason: String,
        val guiUsed: Int, val plannerUsed: Int, val budget: Int, val harnessEnabled: Boolean,
        val statistics: ExperimentStatistics.Statistics? = null,
        val analysis: ExperimentAnalysis.Analysis? = null,
    )
    private val directory = File(context.noBackupFilesDir, "events").apply { mkdirs() }
    private var session = ""
    private var sequence = 0L
    private var startedAt = 0L
    private var schemaVersion = "1.3.0"
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val fields = mapOf(
        "control" to setOf("transition"),
        "model_call" to setOf("role", "phase", "call_index", "gui_steps_used", "planner_calls_used", "duration_ms", "status", "http_status"),
        "action" to setOf("phase", "gui_step", "action_type", "result", "declared_outcome", "execution_failure_reason"),
        "planner_decision" to setOf("planner_call_index", "completion_status", "decision"),
        "error" to setOf("category", "code", "protocol_reason", "execution_failure_reason", "transport_reason", "http_status"),
        "recovery" to setOf("target", "attempt", "delay_ms", "trigger_code", "status", "recovery_id"),
        "session_ended" to setOf("reason", "completion_source", "gui_steps_used", "planner_calls_used"),
        "task_assessment" to setOf("assessment"),
    )
    private val strings = setOf(
        "takeover_requested", "manual_granted", "return_requested", "return_committed", "stop_requested", "pause_unconfirmed", "retry_requested",
        "GUI", "PLANNER", "ORIGINAL", "VERIFY", "CONFIRM", "RECOVERY", "PLANNING",
        "started", "succeeded", "transport_error", "api_error", "invalid_output", "cancelled", "late_discarded",
        "tap", "long_press", "swipe", "input_text", "press_key", "launch_app", "wait", "finish",
        "completed", "rejected", "unknown", "device_error", "unable", "COMPLETE", "INCOMPLETE", "UNVERIFIABLE",
        "EXECUTE", "FALLBACK", "FINISH", "MODEL", "DEVICE", "COORDINATOR", "TRANSPORT_ERROR", "API_ERROR",
        "INVALID_OUTPUT", "AUTHORIZATION_UNAVAILABLE", "SCREENSHOT_FAILED", "INPUT_FAILED", "INPUT_STATUS_UNKNOWN",
        "SERVICE_DISCONNECTED", "INVALID_STATE", "PAUSE_UNCONFIRMED", "STOP_ENTRY_UNAVAILABLE", "OBSERVATION_UNAVAILABLE",
        "model_declared_complete", "model_unable", "user_stop", "budget_exhausted", "interrupted", "runtime_failure",
        "OBSERVATION", "PLANNER_OBSERVATION", "scheduled",
        "none", "gui", "planner", "confirmation", "user_reported_complete", "user_reported_incomplete",
    )
    val manifestHash: String by lazy {
        val bytes = context.assets.open("prompts/v1.1.0/manifest.json").use { it.readBytes() }
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private val automaticManifestHash: String by lazy {
        val bytes = context.assets.open("prompts/v1.2.0/manifest.json").use { it.readBytes() }
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    @Synchronized override fun begin(sessionId: String, options: SessionOptions) {
        require(uuid.matches(sessionId))
        check(!File(directory, "$sessionId.jsonl").exists()) { "AUDIT_SESSION_ALREADY_EXISTS" }
        val metadata = mapOf("harness_enabled" to options.harness, "gui_budget" to options.budget,
            "prompt_bundle_version" to options.promptBundleVersion, "adapter_version" to options.adapterVersion,
            "execution_policy_version" to options.executionPolicyVersion,
            "bundle_manifest_sha256" to if (options.promptBundleVersion == "1.2.0") automaticManifestHash else manifestHash,
            "gui_profile_id" to options.gui.id, "planner_profile_id" to options.planner.id)
        // A task with no durable encrypted original must not proceed into model/device work.
        // Never add the task to session_started: metadata remains safe for content-free export.
        localTasks.save(sessionId, options.task)
        session = sessionId; sequence = 0; startedAt = SystemClock.elapsedRealtime()
        schemaVersion = if (options.automaticRecovery) "1.3.0" else "1.2.0"
        try { write("session_started", metadata) }
        catch (_: Exception) {
            clearCurrent()
            // Keep a committed start record and its attachment for interrupted-session review.
            // If no usable start exists, undo only this new attachment rather than orphan text.
            val path = File(directory, "$sessionId.jsonl")
            val hasStart = runCatching { records(path).firstOrNull()?.optString("event") == "session_started" }
                .getOrDefault(false)
            if (!hasStart) localTasks.delete(sessionId)
            throw IllegalStateException("AUDIT_BEGIN_FAILED")
        }
    }
    /** Explicit local UI lookup; summaries and metadata exports never decrypt task text. */
    @Synchronized fun readLocalTask(sessionId: String): LocalTaskRecordStore.Lookup {
        require(uuid.matches(sessionId))
        return localTasks.read(sessionId)
    }
    @Synchronized override fun event(event: String, data: Map<String, Any?>) {
        if (session.isEmpty()) return
        if (event == "task_assessment") {
            if (data.keys == setOf("assessment")) (data["assessment"] as? String)?.let { assessment ->
                if (assessment in assessments) assessSession(session, assessment)
            }
            return
        }
        val path = File(directory, "$session.jsonl")
        if (!path.isFile) { clearCurrent(); return }
        val allowed = fields[event] ?: return
        if (data.keys.any { it !in allowed }) return
        if (schemaVersion != "1.3.0" && (event == "recovery" || "transport_reason" in data ||
            (event == "error" && "http_status" in data) || data["reason"] == "runtime_failure")) return
        if (event == "recovery" && (data.keys != fields.getValue("recovery") ||
            data["target"] !in setOf("GUI", "OBSERVATION", "PLANNER_OBSERVATION") ||
            data["status"] !in setOf("scheduled", "started", "cancelled") ||
            data["trigger_code"] !in errorCodes ||
            integer(data["attempt"])?.let { it in 1L..2L } != true || integer(data["recovery_id"])?.let { it > 0 } != true ||
            integer(data["delay_ms"]) != if (integer(data["attempt"]) == 1L) 1_000L else 3_000L)) return
        if (event == "session_ended" && data["reason"] == "runtime_failure" && data["completion_source"] != "none") return
        if ("transport_reason" in data && (event != "error" || data["category"] != "MODEL" ||
            data["code"] != "TRANSPORT_ERROR" || data["transport_reason"] !in transportReasons)) return
        if ("http_status" in data && (integer(data["http_status"])?.let { it in 100L..599L } != true ||
            (event == "error" && (data["category"] != "MODEL" || data["code"] != "API_ERROR")))) return
        if ("protocol_reason" in data && (event != "error" || data["category"] != "MODEL" ||
            data["code"] != "INVALID_OUTPUT" || data["protocol_reason"] !in protocolReasons)) return
        if ("execution_failure_reason" in data && (data["execution_failure_reason"] !in executionFailureReasons ||
            (event == "action" && data["result"] == "completed") ||
            (event == "error" && data["category"] != "DEVICE"))) return
        val safe = data.mapNotNull { (key, value) ->
            when {
                key == "protocol_reason" && value is String && value in protocolReasons -> key to value
                key == "execution_failure_reason" && value is String && value in executionFailureReasons -> key to value
                key == "transport_reason" && value is String && value in transportReasons -> key to value
                value is String && value in strings -> key to value
                value is Int && value >= 0 -> key to value
                value is Long && value >= 0 -> key to value
                else -> null
            }
        }.toMap()
        if (safe.size != data.size) return
        write(event, safe)
    }
    private fun write(event: String, data: Map<String, Any?>, version: String = schemaVersion) {
        val record = JSONObject().put("schema_version", version).put("session_id", session)
            .put("seq", ++sequence).put("elapsed_ms", (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0))
            .put("event", event).put("data", JSONObject(data))
        File(directory, "$session.jsonl").appendText(record.toString() + "\n", Charsets.UTF_8)
    }
    @Synchronized fun sessionSummaries(): List<SessionSummary> = sessionFiles().sortedBy { it.name }.mapNotNull { path ->
        val records = records(path)
        val start = records.firstOrNull()?.takeIf { it.optString("event") == "session_started" } ?: return@mapNotNull null
        val end = records.lastOrNull { it.optString("event") == "session_ended" }?.optJSONObject("data")
        val attempts = records.filter { it.optString("event") == "model_call" }.mapNotNull { it.optJSONObject("data") }
        val startData = start.optJSONObject("data") ?: return@mapNotNull null
        val reason = end?.optString("reason")?.takeIf {
            it in setOf("model_declared_complete", "model_unable", "user_stop", "budget_exhausted", "interrupted", "runtime_failure")
        } ?: "unknown"
        val analysis = ExperimentAnalysis.fromRecords(records)
        SessionSummary(path.name.removeSuffix(".jsonl"), end != null, reason,
            (end?.optInt("gui_steps_used") ?: attempts.maxOfOrNull { it.optInt("gui_steps_used") } ?: 0).coerceAtLeast(0),
            (end?.optInt("planner_calls_used") ?: attempts.maxOfOrNull { it.optInt("planner_calls_used") } ?: 0).coerceAtLeast(0),
            startData.optInt("gui_budget").coerceAtLeast(0), startData.optBoolean("harness_enabled"),
            analysis.statistics, analysis)
    }
    /** User report for one ended record, including after process recreation.
     * Does not change the current execution session or expose an assessment to a model.
     * elapsed_ms remains the last recorded monotonic value; no invented post-restart clock.
     */
    @Synchronized fun assessSession(id: String, assessment: String): Boolean {
        require(uuid.matches(id) && assessment in assessments)
        val path = File(directory, "$id.jsonl")
        if (!path.isFile) return false
        val rows = records(path)
        val start = rows.firstOrNull()?.takeIf { it.optString("event") == "session_started" } ?: return false
        if (rows.none { it.optString("event") == "session_ended" }) return false
        val version = start.optString("schema_version").takeIf { it in schemaVersions } ?: return false
        val lastSequence = rows.mapNotNull { integer(it.opt("seq")) }.maxOrNull()?.takeIf { it in 1 until Long.MAX_VALUE } ?: return false
        val elapsed = rows.mapNotNull { integer(it.opt("elapsed_ms")) }.maxOrNull()?.takeIf { it >= 0 } ?: return false
        val record = JSONObject().put("schema_version", version).put("session_id", id)
            .put("seq", lastSequence + 1).put("elapsed_ms", elapsed).put("event", "task_assessment")
            .put("data", JSONObject().put("assessment", assessment))
        rewrite(path, rows + record)
        return true
    }
    /** Selected ended metadata only; never resolves local task text or profile credentials. */
    @Synchronized fun exportAnalysisCsv(ids: Set<String>): String {
        require(ids.all { uuid.matches(it) })
        val analyses = ids.sorted().mapNotNull { id ->
            val path = File(directory, "$id.jsonl")
            if (!path.isFile) return@mapNotNull null
            val rows = records(path)
            if (rows.firstOrNull()?.optString("event") != "session_started" ||
                rows.none { it.optString("event") == "session_ended" }) return@mapNotNull null
            ExperimentAnalysis.fromRecords(rows)
        }
        return if (analyses.isEmpty()) "" else ExperimentAnalysis.csv(analyses)
    }
    /** An empty/lost selection never means all sessions. Active and missing sessions are excluded. */
    @Synchronized fun exportMetadata(ids: Set<String>): String {
        require(ids.all { uuid.matches(it) })
        return ids.sorted().joinToString("") { id ->
            val path = File(directory, "$id.jsonl")
            if (!path.isFile) return@joinToString ""
            val records = records(path)
            if (records.firstOrNull()?.optString("event") != "session_started" ||
                records.none { it.optString("event") == "session_ended" }) return@joinToString ""
            records.joinToString("\n") { it.toString() } + "\n"
        }
    }
    @Synchronized fun deleteSession(id: String): Boolean {
        require(uuid.matches(id))
        val path = File(directory, "$id.jsonl")
        if (!path.isFile || records(path).none { it.optString("event") == "session_ended" }) return false
        // Preserve the metadata row as a retry entry if removing its attachment fails.
        // If the later log deletion fails, a retry can safely remove the now-missing attachment.
        if (!localTasks.delete(id)) return false
        val deleted = path.delete()
        if (deleted && session == id) clearCurrent()
        return deleted
    }
    @Synchronized fun deleteAll(): Boolean = sessionSummaries().filter { it.ended }
        .map { deleteSession(it.id) }.all { it }
    @Synchronized fun count(): Int = sessionSummaries().size
    private fun sessionFiles() = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".jsonl") && uuid.matches(it.name.removeSuffix(".jsonl")) }
    private fun records(path: File): List<JSONObject> {
        val id = path.name.removeSuffix(".jsonl")
        return path.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optString("session_id") == id }
    }
    private fun clearCurrent() { session = ""; sequence = 0; startedAt = 0; schemaVersion = "1.3.0" }
    /** A previous process cannot resume execution/history. Seal metadata without reading task text. */
    @Synchronized fun markInterrupted() {
        try {
            directory.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") && uuid.matches(it.name.removeSuffix(".jsonl")) }.forEach { path ->
                val lines = path.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                val records = lines.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                if (records.isEmpty()) {
                    // A torn first metadata write cannot expose an orphan task that users cannot
                    // select for deletion. Preserve this file for the next cleanup if removal fails.
                    if (localTasks.delete(path.name.removeSuffix(".jsonl"))) path.delete()
                    return@forEach
                }
                if (records.any { it.optString("event") == "session_ended" }) {
                    rewrite(path, records)
                    return@forEach
                }
                val last = records.lastOrNull() ?: return@forEach
                val attempts = records.filter { it.optString("event") == "model_call" }
                val gui = attempts.maxOfOrNull { it.getJSONObject("data").optInt("gui_steps_used") } ?: 0
                val planner = attempts.maxOfOrNull { it.getJSONObject("data").optInt("planner_calls_used") } ?: 0
                val version = records.first().optString("schema_version").takeIf { it in schemaVersions } ?: "1.0.0"
                val ended = JSONObject().put("schema_version", version).put("session_id", last.getString("session_id"))
                    .put("seq", last.getLong("seq") + 1).put("elapsed_ms", last.getLong("elapsed_ms"))
                    .put("event", "session_ended").put("data", JSONObject().put("reason", "interrupted")
                        .put("completion_source", "none").put("gui_steps_used", gui).put("planner_calls_used", planner))
                rewrite(path, records + ended)
            }
        } finally {
            // A process may have stopped after committing the attachment but before writing its
            // first event. Only remove such orphans; never infer a task from old metadata. An
            // unreadable log is protected because its validity cannot safely be established.
            val protectedIds = sessionFiles().filter { path ->
                runCatching { records(path).firstOrNull()?.optString("event") == "session_started" }
                    .getOrDefault(true)
            }.map { it.name.removeSuffix(".jsonl") }.toSet()
            localTasks.deleteOrphans(protectedIds)
        }
    }
    private fun rewrite(path: File, records: List<JSONObject>) {
        val atomic = AtomicFile(path)
        val stream = atomic.startWrite()
        try {
            stream.write((records.joinToString("\n") { it.toString() } + "\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }
    private val protocolReasons = ProtocolReason.entries.filter { it != ProtocolReason.VALID }.map { it.name }.toSet()
    private val executionFailureReasons = ExecutionFailureReason.entries.map { it.name }.toSet()
    private val transportReasons = setOf("DNS_FAILURE", "TLS_HANDSHAKE_FAILED", "TLS_PEER_UNVERIFIED", "TIMEOUT",
        "CONNECT_FAILED", "REQUEST_REPLAY_BLOCKED", "NETWORK_IO")
    private val errorCodes = setOf("TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT", "AUTHORIZATION_UNAVAILABLE",
        "SCREENSHOT_FAILED", "INPUT_FAILED", "INPUT_STATUS_UNKNOWN", "SERVICE_DISCONNECTED", "INVALID_STATE",
        "PAUSE_UNCONFIRMED", "STOP_ENTRY_UNAVAILABLE", "OBSERVATION_UNAVAILABLE")
    private val schemaVersions = setOf("1.0.0", "1.1.0", "1.2.0", "1.3.0")
    private val assessments = setOf("user_reported_complete", "user_reported_incomplete")
    private fun integer(value: Any?): Long? = when (value) { is Int -> value.toLong(); is Long -> value; else -> null }
}
