package org.mobileharness.app.verification

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONArray
import org.json.JSONObject
import org.mobileharness.app.core.ExecutionFailureReason
import org.mobileharness.app.core.ModelProfile
import org.mobileharness.app.core.SessionOptions
import org.mobileharness.app.core.StartMode
import org.mobileharness.app.model.ProtocolReason
import org.mobileharness.app.storage.MinimalAudit
import java.io.File
import java.util.UUID

/** Storage-only integration probe. Never instantiates ProfileStore, a model, or a PhonePort. */
class MinimalAuditDeterministicVerification(private val context: Context) {
    data class Result(val status: String, val code: String, val flags: Map<String, Boolean>,
        val metadataV12: JSONArray, val metadataV11: JSONArray, val metadataV10: JSONArray)

    fun run(): Result {
        val flags = linkedMapOf<String, Boolean>()
        var current = JSONArray()
        var previous = JSONArray()
        var legacy = JSONArray()
        var code = "OK"
        var created = false
        val parent = context.noBackupFilesDir.canonicalFile
        val isolated = File(parent, "audit-deterministic-${UUID.randomUUID()}").canonicalFile
        try {
            require(isolated.parentFile == parent && isolated.name.matches(Regex("audit-deterministic-[0-9a-f-]{36}")))
            require(!isolated.exists() && isolated.mkdirs())
            created = true
            flags["isolated_directory_verified"] = true
            val ctx = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = isolated }
            val audit = MinimalAudit(ctx)
            val selected = mutableSetOf<String>()

            val classified = UUID.randomUUID().toString()
            audit.begin(classified, options())
            selected.add(classified)
            require(audit.exportMetadata(setOf(classified)).isEmpty())
            require(audit.exportMetadata(emptySet()).isEmpty())
            require(!audit.deleteSession(classified))
            flags["active_session_not_exported_or_deleted"] = true
            audit.event("task_assessment", mapOf("assessment" to "user_reported_incomplete"))
            require(records(isolated, classified).none { it.getString("event") == "task_assessment" })
            flags["active_session_assessment_rejected"] = true

            val before = records(isolated, classified).size
            audit.event("error", mapOf("category" to "MODEL", "code" to "API_ERROR", "reply" to PRIVATE_REPLY))
            audit.event("action", mapOf("phase" to "ORIGINAL", "gui_step" to 1, "action_type" to "tap", "result" to "completed", "x" to 500))
            audit.event("error", mapOf("category" to "MODEL", "code" to PRIVATE_REPLY))
            audit.event("error", mapOf("category" to "MODEL", "code" to "INVALID_OUTPUT", "protocol_reason" to PRIVATE_REPLY))
            audit.event("error", mapOf("category" to "DEVICE", "code" to "INPUT_FAILED", "protocol_reason" to "GUI_CONTENT"))
            audit.event("error", mapOf("category" to "DEVICE", "code" to "INPUT_FAILED", "execution_failure_reason" to PRIVATE_REPLY))
            audit.event("error", mapOf("category" to "MODEL", "code" to "INVALID_OUTPUT", "execution_failure_reason" to "NO_FOCUSED_INPUT"))
            audit.event("error", mapOf("category" to "COORDINATOR", "code" to "INVALID_STATE", "execution_failure_reason" to "CONTROL_NOT_AVAILABLE"))
            audit.event("action", mapOf("phase" to "ORIGINAL", "gui_step" to 1, "action_type" to "tap", "result" to "completed",
                "execution_failure_reason" to "GESTURE_NOT_DISPATCHED"))
            audit.event("unknown_event", mapOf("message" to PRIVATE_REPLY))
            require(records(isolated, classified).size == before)
            flags["unknown_fields_values_events_and_protocol_reason_misuse_rejected"] = true
            flags["execution_reason_unknown_value_model_coordinator_and_completed_action_rejected"] = true

            for (reason in ExecutionFailureReason.entries) {
                audit.event("action", mapOf("phase" to "ORIGINAL", "gui_step" to 1, "action_type" to "input_text", "result" to "device_error",
                    "execution_failure_reason" to reason.name))
                audit.event("error", mapOf("category" to "DEVICE", "code" to "INPUT_FAILED", "execution_failure_reason" to reason.name))
            }
            val reasonRows = records(isolated, classified).drop(before)
            require(reasonRows.size == ExecutionFailureReason.entries.size * 2)
            require(reasonRows.all { it.getJSONObject("data").getString("execution_failure_reason") in EXECUTION_REASONS })
            flags["all_fixed_execution_reasons_accepted_without_free_text"] = true
            val startData = records(isolated, classified).first().getJSONObject("data")
            require(startData.getString("execution_policy_version") == "1.1.0" && startData.getString("adapter_version") == "1.4.0")
            flags["execution_policy_and_adapter_versions_recorded_separately"] = true

            model(audit, "GUI", "ORIGINAL", 1, 1, 0, "started")
            model(audit, "GUI", "ORIGINAL", 1, 1, 0, "api_error", http = 401)
            audit.event("error", mapOf("category" to "MODEL", "code" to "API_ERROR"))
            audit.event("control", mapOf("transition" to "retry_requested"))
            model(audit, "GUI", "ORIGINAL", 2, 2, 0, "started")
            model(audit, "GUI", "ORIGINAL", 2, 2, 0, "invalid_output")
            audit.event("error", mapOf("category" to "MODEL", "code" to "INVALID_OUTPUT", "protocol_reason" to "GUI_CONTENT"))
            audit.event("control", mapOf("transition" to "retry_requested"))
            model(audit, "GUI", "ORIGINAL", 3, 3, 0, "started")
            model(audit, "GUI", "ORIGINAL", 3, 3, 0, "succeeded")
            audit.event("action", mapOf("phase" to "ORIGINAL", "gui_step" to 3, "action_type" to "tap", "result" to "device_error"))
            audit.event("error", mapOf("category" to "DEVICE", "code" to "INPUT_FAILED"))
            audit.event("error", mapOf("category" to "DEVICE", "code" to "SCREENSHOT_FAILED"))
            audit.event("control", mapOf("transition" to "stop_requested"))
            ended(audit, "user_stop", "none", 3, 0)
            val classificationRows = records(isolated, classified)
            val errorPairs = classificationRows.filter { it.getString("event") == "error" }.map { it.getJSONObject("data") }
            require(errorPairs.any { it.getString("category") == "MODEL" && it.getString("code") == "API_ERROR" })
            require(errorPairs.any { it.optString("protocol_reason") == "GUI_CONTENT" })
            require(errorPairs.any { it.getString("category") == "DEVICE" && it.getString("code") == "INPUT_FAILED" })
            require(errorPairs.any { it.getString("category") == "DEVICE" && it.getString("code") == "SCREENSHOT_FAILED" })
            flags["model_api_protocol_and_device_errors_kept_distinct"] = true

            for ((source, completeStatus) in listOf("planner" to "INCOMPLETE", "confirmation" to "COMPLETE")) {
                val id = UUID.randomUUID().toString()
                audit.begin(id, options())
                selected.add(id)
                model(audit, "GUI", "ORIGINAL", 1, 1, 0, "started")
                audit.event("control", mapOf("transition" to "takeover_requested"))
                model(audit, "GUI", "ORIGINAL", 1, 1, 0, "cancelled")
                for (transition in listOf("manual_granted", "return_requested", "return_committed"))
                    audit.event("control", mapOf("transition" to transition))
                model(audit, "PLANNER", "PLANNING", 1, 1, 1, "started")
                model(audit, "PLANNER", "PLANNING", 1, 1, 1, "succeeded")
                audit.event("planner_decision", mapOf("planner_call_index" to 1, "completion_status" to completeStatus, "decision" to "VERIFY"))
                val phase = if (source == "planner") "VERIFY" else "CONFIRM"
                model(audit, "GUI", phase, 2, 2, 1, "started")
                model(audit, "GUI", phase, 2, 2, 1, "succeeded")
                audit.event("action", mapOf("phase" to phase, "gui_step" to 2, "action_type" to "finish", "result" to "completed",
                    "declared_outcome" to if (source == "planner") "unable" else "completed"))
                if (source == "planner") {
                    model(audit, "PLANNER", "PLANNING", 2, 2, 2, "started")
                    model(audit, "PLANNER", "PLANNING", 2, 2, 2, "succeeded")
                    audit.event("planner_decision", mapOf("planner_call_index" to 2, "completion_status" to "COMPLETE", "decision" to "FINISH"))
                }
                // Selecting an active and a closed session must export only the closed one.
                val selection = exported(audit.exportMetadata(setOf(classified, id)))
                require(selection.isNotEmpty() && selection.all { it.getString("session_id") == classified })
                ended(audit, "model_declared_complete", source, 2, if (source == "planner") 2 else 1)
                val endedRows = records(isolated, id)
                require(endedRows.none { it.getString("event") == "task_assessment" })
                audit.event("task_assessment", mapOf("assessment" to "user_reported_incomplete"))
                val assessed = records(isolated, id)
                val end = assessed.single { it.getString("event") == "session_ended" }.getJSONObject("data")
                require(end.getString("reason") == "model_declared_complete" && end.getString("completion_source") == source)
                require(assessed.last().getString("event") == "task_assessment")
                require(assessed.last().getJSONObject("data").getString("assessment") == "user_reported_incomplete")
                flags["${source}_completion_source_and_independent_incomplete_assessment"] = true
            }
            flags["selection_never_exports_an_unended_session"] = true
            flags["model_end_does_not_create_automatic_task_assessment"] = true
            require(audit.exportMetadata(setOf(UUID.randomUUID().toString())).isEmpty())
            flags["missing_selection_never_exports_other_sessions"] = true

            val interrupted = UUID.randomUUID().toString()
            audit.begin(interrupted, options())
            model(audit, "GUI", "ORIGINAL", 1, 1, 0, "started")
            val prior = records(isolated, interrupted)
            require(audit.exportMetadata(setOf(interrupted)).isEmpty())
            MinimalAudit(ctx).markInterrupted()
            val resumed = records(isolated, interrupted)
            require(resumed.size == prior.size + 1 && resumed.all { it.getString("schema_version") == "1.2.0" })
            val tail = resumed.last()
            require(tail.getString("event") == "session_ended" && tail.getLong("seq") == prior.last().getLong("seq") + 1)
            require(tail.getJSONObject("data").getString("reason") == "interrupted")
            require(tail.getJSONObject("data").getString("completion_source") == "none")
            require(tail.getJSONObject("data").getInt("gui_steps_used") == 1)
            selected.add(interrupted)
            flags["new_schema_interrupted_tail_keeps_version_and_charged_attempt"] = true

            val previousId = UUID.randomUUID().toString()
            val previousAudit = MinimalAudit(ctx)
            previousAudit.begin(previousId, options())
            model(previousAudit, "GUI", "ORIGINAL", 1, 1, 0, "started")
            val originalPrevious = legacyRecords(records(isolated, previousId), "1.1.0")
            File(isolated, "events/$previousId.jsonl").writeText(originalPrevious.joinToString("\n") { it.toString() } + "\n", Charsets.UTF_8)
            MinimalAudit(ctx).markInterrupted()
            val previousResumed = records(isolated, previousId)
            require(previousResumed.size == originalPrevious.size + 1 && previousResumed.all { it.getString("schema_version") == "1.1.0" })
            require(previousResumed.last().getString("event") == "session_ended")
            require(previousResumed.last().getJSONObject("data").getString("reason") == "interrupted")
            require(previousResumed.last().getLong("seq") == originalPrevious.last().getLong("seq") + 1)
            flags["previous_schema_interrupted_tail_preserves_v1_1"] = true

            val legacyId = UUID.randomUUID().toString()
            val legacyAudit = MinimalAudit(ctx)
            legacyAudit.begin(legacyId, options(legacy = true))
            model(legacyAudit, "GUI", "ORIGINAL", 1, 1, 0, "started")
            val legacyFile = File(isolated, "events/$legacyId.jsonl")
            val originalLegacy = legacyRecords(records(isolated, legacyId), "1.0.0")
            legacyFile.writeText(originalLegacy.joinToString("\n") { it.toString() } + "\n", Charsets.UTF_8)
            MinimalAudit(ctx).markInterrupted()
            val oldResumed = records(isolated, legacyId)
            require(oldResumed.size == originalLegacy.size + 1 && oldResumed.all { it.getString("schema_version") == "1.0.0" })
            require(oldResumed.last().getString("event") == "session_ended")
            require(oldResumed.last().getJSONObject("data").getString("reason") == "interrupted")
            require(oldResumed.last().getLong("seq") == originalLegacy.last().getLong("seq") + 1)
            flags["legacy_schema_interrupted_tail_preserves_v1_0"] = true

            val allCurrent = exported(MinimalAudit(ctx).exportMetadata(selected))
            val allPrevious = exported(MinimalAudit(ctx).exportMetadata(setOf(previousId)))
            val allLegacy = exported(MinimalAudit(ctx).exportMetadata(setOf(legacyId)))
            validateClosedRecords(allCurrent, "1.2.0")
            validateClosedRecords(allPrevious, "1.1.0")
            validateClosedRecords(allLegacy, "1.0.0")
            val diskText = File(isolated, "events").listFiles().orEmpty().joinToString("\n") { it.readText(Charsets.UTF_8) }
            require(listOf(PRIVATE_TASK, PRIVATE_REPLY, PRIVATE_KEY, PRIVATE_MODEL, PRIVATE_PACKAGE).none { diskText.contains(it) })
            flags["task_key_model_reply_coordinates_and_private_sentinels_absent"] = true
            flags["all_exported_records_pass_local_closed_field_validation"] = true
            current = JSONArray(allCurrent.map { JSONObject(it.toString()) })
            previous = JSONArray(allPrevious.map { JSONObject(it.toString()) })
            legacy = JSONArray(allLegacy.map { JSONObject(it.toString()) })
        } catch (_: Exception) {
            code = "AUDIT_DETERMINISTIC_ASSERTION_FAILED"
            current = JSONArray(); previous = JSONArray(); legacy = JSONArray()
        } finally {
            try {
                require(isolated.canonicalFile.parentFile == parent)
                require(isolated.name.matches(Regex("audit-deterministic-[0-9a-f-]{36}")))
                // A failed mkdir/precondition must never delete a pre-existing directory.
                if (created) require(!isolated.exists() || isolated.deleteRecursively())
                flags["isolated_directory_removed"] = !isolated.exists()
            } catch (_: Exception) {
                flags["isolated_directory_removed"] = false
                code = "AUDIT_ISOLATION_CLEANUP_FAILED"
            }
        }
        return Result(if (code == "OK" && flags.values.all { it }) "PASS" else "FAIL", code, flags, current, previous, legacy)
    }

    private fun options(legacy: Boolean = false) = SessionOptions(PRIVATE_TASK, if (legacy) PRIVATE_PACKAGE else "SYNTHETIC_HOME",
        setOf(PRIVATE_PACKAGE), true, 30,
        ModelProfile(baseUrl = "https://invalid.example/v1", model = PRIVATE_MODEL, apiKey = PRIVATE_KEY),
        ModelProfile(baseUrl = "https://invalid.example/v1", model = PRIVATE_MODEL, apiKey = PRIVATE_KEY),
        if (legacy) StartMode.SELECTED_APP else StartMode.HOME_AUTO, if (legacy) "" else "SYNTHETIC_HOME", automaticRecovery = false)

    private fun model(audit: MinimalAudit, role: String, phase: String, index: Int, gui: Int, planner: Int, status: String, http: Int? = null) {
        val data = linkedMapOf<String, Any?>("role" to role, "phase" to phase, "call_index" to index,
            "gui_steps_used" to gui, "planner_calls_used" to planner, "duration_ms" to 0, "status" to status)
        http?.let { data["http_status"] = it }
        audit.event("model_call", data)
    }
    private fun ended(audit: MinimalAudit, reason: String, source: String, gui: Int, planner: Int) =
        audit.event("session_ended", mapOf("reason" to reason, "completion_source" to source, "gui_steps_used" to gui, "planner_calls_used" to planner))
    private fun records(root: File, id: String) = exported(File(root, "events/$id.jsonl").readText(Charsets.UTF_8))
    private fun exported(text: String) = text.lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
    private fun legacyRecords(rows: List<JSONObject>, version: String) = rows.map { row ->
        JSONObject(row.toString()).put("schema_version", version).also {
            if (it.getString("event") == "session_started") it.getJSONObject("data").apply {
                remove("execution_policy_version")
                if (optString("adapter_version") == "1.4.0") put("adapter_version", "1.3.0")
            }
        }
    }

    /** Reject unsafe fields before any actual metadata can leave this private isolated directory. */
    private fun validateClosedRecords(records: List<JSONObject>, version: String) {
        val perSession = records.groupBy { it.getString("session_id") }
        require(perSession.isNotEmpty())
        for ((id, rows) in perSession) {
            require(UUID_PATTERN.matches(id))
            require(rows.first().getString("event") == "session_started")
            require(rows.count { it.getString("event") == "session_ended" } == 1)
            rows.forEachIndexed { index, record ->
                require(keys(record) == setOf("schema_version", "session_id", "seq", "elapsed_ms", "event", "data"))
                require(record.getString("schema_version") == version && record.getString("session_id") == id)
                integer(record, "seq", 1); integer(record, "elapsed_ms")
                require(record.getLong("seq") == index + 1L)
                val event = record.getString("event")
                val data = record.getJSONObject("data")
                val fields = (FIELD_SETS[event] ?: error("UNSAFE_EVENT")) +
                    if (event == "session_started" && version == "1.2.0") setOf("execution_policy_version") else emptySet()
                val optional = when (event) {
                    "model_call" -> setOf("http_status")
                    "error" -> setOf("protocol_reason", "execution_failure_reason")
                    "action" -> setOf("declared_outcome", "execution_failure_reason")
                    else -> emptySet()
                }
                require(keys(data).all { it in fields } && keys(data).containsAll(fields - optional))
                when (event) {
                    "session_started" -> {
                        require(data.get("harness_enabled") is Boolean)
                        integer(data, "gui_budget", 1)
                        fixed(data, "prompt_bundle_version", setOf("1.1.0", "1.2.0"))
                        fixed(data, "adapter_version", if (version == "1.2.0") setOf("1.1.0", "1.4.0") else setOf("1.1.0", "1.3.0"))
                        if (version == "1.2.0") fixed(data, "execution_policy_version", setOf("1.1.0"))
                        require(data.get("bundle_manifest_sha256") is String && HASH_PATTERN.matches(data.getString("bundle_manifest_sha256")))
                        for (field in listOf("gui_profile_id", "planner_profile_id"))
                            require(data.get(field) is String && UUID_PATTERN.matches(data.getString(field)))
                    }
                    "control" -> fixed(data, "transition", TRANSITIONS)
                    "model_call" -> {
                        fixed(data, "role", setOf("GUI", "PLANNER")); fixed(data, "phase", PHASES + "PLANNING")
                        integer(data, "call_index", 1); integer(data, "gui_steps_used"); integer(data, "planner_calls_used", 0, 2)
                        integer(data, "duration_ms"); fixed(data, "status", CALL_STATUSES)
                        if (data.has("http_status")) integer(data, "http_status", 100, 599)
                        if (data.getString("role") == "GUI") require(data.getLong("call_index") == data.getLong("gui_steps_used"))
                        else require(data.getLong("call_index") == data.getLong("planner_calls_used") && data.getString("phase") == "PLANNING")
                    }
                    "action" -> {
                        fixed(data, "phase", PHASES); integer(data, "gui_step", 1); fixed(data, "action_type", ACTIONS)
                        fixed(data, "result", setOf("completed", "rejected", "cancelled", "unknown", "device_error"))
                        if (data.has("execution_failure_reason")) {
                            require(version == "1.2.0" && data.getString("result") != "completed")
                            fixed(data, "execution_failure_reason", EXECUTION_REASONS)
                        }
                    }
                    "planner_decision" -> {
                        integer(data, "planner_call_index", 1, 2); fixed(data, "completion_status", setOf("COMPLETE", "INCOMPLETE", "UNVERIFIABLE"))
                        fixed(data, "decision", setOf("EXECUTE", "VERIFY", "FALLBACK", "FINISH"))
                    }
                    "error" -> {
                        fixed(data, "category", setOf("MODEL", "DEVICE", "COORDINATOR")); fixed(data, "code", ERROR_CODES)
                        if (data.has("protocol_reason")) {
                            require(version in setOf("1.1.0", "1.2.0") && data.getString("category") == "MODEL" && data.getString("code") == "INVALID_OUTPUT")
                            fixed(data, "protocol_reason", ProtocolReason.entries.filter { it != ProtocolReason.VALID }.map { it.name }.toSet())
                        }
                        if (data.has("execution_failure_reason")) {
                            require(version == "1.2.0" && data.getString("category") == "DEVICE")
                            fixed(data, "execution_failure_reason", EXECUTION_REASONS)
                        }
                    }
                    "session_ended" -> {
                        fixed(data, "reason", setOf("model_declared_complete", "model_unable", "user_stop", "budget_exhausted", "interrupted"))
                        fixed(data, "completion_source", setOf("none", "gui", "planner", "confirmation"))
                        integer(data, "gui_steps_used"); integer(data, "planner_calls_used", 0, 2)
                    }
                    "task_assessment" -> fixed(data, "assessment", setOf("unknown", "user_reported_complete", "user_reported_incomplete"))
                }
                if (event == "action") {
                    if (data.getString("action_type") == "finish") fixed(data, "declared_outcome", setOf("completed", "unable"))
                    else require(!data.has("declared_outcome"))
                }
            }
        }
    }
    private fun keys(value: JSONObject) = value.keys().asSequence().toSet()
    private fun fixed(value: JSONObject, field: String, allowed: Set<String>) { require(value.get(field) is String && value.getString(field) in allowed) }
    private fun integer(value: JSONObject, field: String, minimum: Int = 0, maximum: Long = 9_007_199_254_740_991) {
        val raw = value.get(field)
        require((raw is Int || raw is Long) && (raw as Number).toLong() in minimum.toLong()..maximum)
    }

    private companion object {
        const val PRIVATE_TASK = "AUDIT_PRIVATE_TASK_SENTINEL"
        const val PRIVATE_REPLY = "AUDIT_PRIVATE_REPLY_SENTINEL"
        const val PRIVATE_KEY = "AUDIT_PRIVATE_KEY_SENTINEL"
        const val PRIVATE_MODEL = "AUDIT_PRIVATE_MODEL_SENTINEL"
        const val PRIVATE_PACKAGE = "audit.private.package.sentinel"
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        val HASH_PATTERN = Regex("[0-9a-f]{64}")
        val PHASES = setOf("ORIGINAL", "VERIFY", "CONFIRM", "RECOVERY")
        val ACTIONS = setOf("tap", "long_press", "swipe", "input_text", "press_key", "launch_app", "wait", "finish")
        val TRANSITIONS = setOf("takeover_requested", "manual_granted", "return_requested", "return_committed", "stop_requested", "pause_unconfirmed", "retry_requested")
        val CALL_STATUSES = setOf("started", "succeeded", "transport_error", "api_error", "invalid_output", "cancelled", "late_discarded")
        val ERROR_CODES = setOf("TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT", "AUTHORIZATION_UNAVAILABLE", "SCREENSHOT_FAILED", "INPUT_FAILED", "INPUT_STATUS_UNKNOWN",
            "SERVICE_DISCONNECTED", "INVALID_STATE", "PAUSE_UNCONFIRMED", "STOP_ENTRY_UNAVAILABLE", "OBSERVATION_UNAVAILABLE")
        val EXECUTION_REASONS = ExecutionFailureReason.entries.map { it.name }.toSet()
        val FIELD_SETS = mapOf(
            "session_started" to setOf("harness_enabled", "gui_budget", "prompt_bundle_version", "adapter_version", "bundle_manifest_sha256", "gui_profile_id", "planner_profile_id"),
            "control" to setOf("transition"),
            "model_call" to setOf("role", "phase", "call_index", "gui_steps_used", "planner_calls_used", "duration_ms", "status", "http_status"),
            "action" to setOf("phase", "gui_step", "action_type", "result", "declared_outcome", "execution_failure_reason"),
            "planner_decision" to setOf("planner_call_index", "completion_status", "decision"),
            "error" to setOf("category", "code", "protocol_reason", "execution_failure_reason"),
            "session_ended" to setOf("reason", "completion_source", "gui_steps_used", "planner_calls_used"),
            "task_assessment" to setOf("assessment"),
        )
    }
}
