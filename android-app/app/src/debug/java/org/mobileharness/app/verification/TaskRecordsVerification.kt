package org.mobileharness.app.verification

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.mobileharness.app.core.*
import org.mobileharness.app.storage.ExperimentStatistics
import org.mobileharness.app.storage.LocalTaskRecordStore
import org.mobileharness.app.storage.MinimalAudit
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Real encrypted storage with synthetic content only. No real model or PhonePort is used. */
class TaskRecordsVerification(private val context: Context, private val runtimeIdle: () -> Boolean) {
    data class Result(
        val status: String = "RUNNING", val code: String = "PENDING",
        val flags: Map<String, Boolean> = emptyMap(),
        val localModelStubCalls: Int = 0, val localObserveStubCalls: Int = 0,
        val localPerformStubCalls: Int = 0,
    )

    suspend fun run(): Result {
        val flags = linkedMapOf<String, Boolean>()
        val parent = context.noBackupFilesDir.canonicalFile
        val token = UUID.randomUUID().toString()
        val isolated = File(parent, "task-records-verification-" + token).canonicalFile
        val alias = ALIAS_PREFIX + token
        val counters = Counters()
        var created = false
        var code = "OK"
        try {
            check(runtimeIdle())
            flags["actual_runtime_idle_or_ended"] = true
            check(isolated.parentFile == parent && isolated.name == "task-records-verification-" + token)
            check(!isolated.exists() && isolated.mkdirs())
            created = true
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            check(!keyStore.containsAlias(alias))
            flags["isolated_directory_and_unique_key_alias"] = true
            val ctx = wrapped(isolated)
            var store = LocalTaskRecordStore(ctx, alias)
            val first = id()
            val second = id()
            store.save(first, TASK)
            store = LocalTaskRecordStore(ctx, alias)
            check(store.read(first).let { it.status == LocalTaskRecordStore.Status.AVAILABLE && it.task == TASK })
            flags["encrypted_task_exact_roundtrip_after_reinstantiation"] = true
            check(!store.read(first).toString().contains(PRIVATE_SENTINEL))
            flags["lookup_diagnostic_does_not_include_task"] = true
            val firstFile = taskFile(isolated, first)
            val original = firstFile.readBytes()
            check(!String(original, Charsets.ISO_8859_1).contains(PRIVATE_SENTINEL))
            store.save(second, TASK)
            val secondFile = taskFile(isolated, second)
            val secondBytes = secondFile.readBytes()
            check(original.size > 28 && secondBytes.size > 28 &&
                !original.copyOfRange(0, 12).contentEquals(secondBytes.copyOfRange(0, 12)))
            flags["task_ciphertext_has_no_plaintext_and_uses_fresh_iv"] = true
            store.save(first, TASK)
            check(runCatching { store.save(first, TASK + " changed") }.isFailure)
            check(store.read(first).task == TASK)
            flags["same_session_cannot_replace_original_task"] = true
            secondFile.writeBytes(original)
            check(store.read(second).status == LocalTaskRecordStore.Status.UNAVAILABLE)
            secondFile.writeBytes(secondBytes)
            check(store.read(second).task == TASK)
            flags["cross_session_ciphertext_swap_rejected_by_aad"] = true
            val damaged = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            firstFile.writeBytes(damaged)
            check(store.read(first).status == LocalTaskRecordStore.Status.UNAVAILABLE)
            firstFile.writeBytes(original)
            check(store.read(first).task == TASK)
            flags["modified_ciphertext_rejected_without_overwrite"] = true
            check(store.read(id()).status == LocalTaskRecordStore.Status.LEGACY_MISSING)
            flags["missing_legacy_task_is_not_reconstructed"] = true
            for (invalid in listOf("", "../profiles/catalog-v2", "not-a-session")) {
                check(runCatching { store.read(invalid) }.isFailure)
                check(runCatching { store.save(invalid, TASK) }.isFailure)
                check(runCatching { store.delete(invalid) }.isFailure)
            }
            flags["attachment_identifiers_reject_path_traversal"] = true

            check(runtimeIdle())
            val audit = MinimalAudit(ctx, store)
            val completed = id()
            audit.begin(completed, options())
            check(audit.readLocalTask(completed).task == TASK)
            model(audit, "GUI", 1, 1, 0, "started")
            model(audit, "GUI", 1, 1, 0, "cancelled")
            model(audit, "PLANNER", 1, 1, 1, "started")
            model(audit, "PLANNER", 1, 1, 1, "succeeded", 11, 200)
            audit.event("planner_decision", mapOf("planner_call_index" to 1,
                "completion_status" to "INCOMPLETE", "decision" to "VERIFY"))
            for (index in 2..4) {
                model(audit, "GUI", index, index, 1, "started")
                model(audit, "GUI", index, index, 1, if (index == 3) "invalid_output" else "succeeded")
            }
            audit.event("action", mapOf("phase" to "VERIFY", "gui_step" to 4,
                "action_type" to "wait", "result" to "completed"))
            model(audit, "PLANNER", 2, 4, 2, "started")
            model(audit, "PLANNER", 2, 4, 2, "api_error", 23, 429)
            ended(audit, 4, 2)
            check(runCatching { audit.begin(completed, options()) }.isFailure)
            check(audit.readLocalTask(completed).task == TASK)
            flags["audit_session_id_cannot_be_reused"] = true
            val summary = audit.sessionSummaries().single { it.id == completed }
            val statistics = checkNotNull(summary.statistics)
            check(summary.guiUsed == 4 && summary.budget == 7 && summary.plannerUsed == 2)
            check(statistics.guiUsed == 4 && statistics.guiBudget == 7 && statistics.plannerUsed == 2)
            check(statistics.observedPlannerCalls == 2 && statistics.plannerCalls.size == 2)
            val p1 = statistics.plannerCalls[0]
            val p2 = statistics.plannerCalls[1]
            check(p1.index == 1 && p1.status == ExperimentStatistics.CallStatus.SUCCEEDED &&
                p1.durationMs == 11L && p1.httpStatus == 200 && p1.decision == "VERIFY" &&
                p1.completionStatus == "INCOMPLETE" && p1.startedRecorded)
            check(p2.index == 2 && p2.status == ExperimentStatistics.CallStatus.API_ERROR &&
                p2.durationMs == 23L && p2.httpStatus == 429 && p2.decision == null)
            flags["actual_metadata_pairs_planner_attempts_and_decisions_once"] = true
            flags["gui_usage_includes_cancelled_and_invalid_attempts"] = true
            val exported = audit.exportMetadata(setOf(completed))
            check(exported.isNotBlank() && exported.lineSequence().filter { it.isNotBlank() }
                .all { JSONObject(it).getString("session_id") == completed })
            check(listOf(PRIVATE_SENTINEL, PRIVATE_KEY, PRIVATE_MODEL, PRIVATE_REPLY).none { exported.contains(it) })
            val eventText = File(isolated, "events").listFiles().orEmpty()
                .filter { it.isFile }.joinToString("\n") { it.readText(Charsets.UTF_8) }
            check(listOf(PRIVATE_SENTINEL, PRIVATE_KEY, PRIVATE_MODEL, PRIVATE_REPLY).none { eventText.contains(it) })
            flags["metadata_and_selected_export_exclude_task_key_model_and_reply"] = true
            check(audit.exportMetadata(emptySet()).isEmpty())
            check(audit.exportMetadata(setOf(id())).isEmpty())
            flags["empty_or_absent_selection_never_exports_other_records"] = true

            val active = id()
            audit.begin(active, options(false))
            check(!audit.deleteSession(active) && audit.exportMetadata(setOf(active)).isEmpty())
            check(audit.readLocalTask(active).task == TASK)
            flags["active_record_and_attachment_not_deleted_or_exported"] = true
            val completedTaskPath = taskFile(isolated, completed)
            val completedCiphertext = completedTaskPath.readBytes()
            File(completedTaskPath.path + ".bak").writeBytes(completedCiphertext)
            File(completedTaskPath.path + ".new").writeBytes(completedCiphertext)
            check(audit.deleteSession(completed))
            check(!File(isolated, "events/" + completed + ".jsonl").exists() &&
                attachmentAbsent(isolated, completed))
            flags["ended_single_delete_removes_log_and_all_attachment_files"] = true
            val off = id()
            val otherAudit = MinimalAudit(ctx, store)
            otherAudit.begin(off, options(false))
            ended(otherAudit, 0, 0)
            val offStats = checkNotNull(otherAudit.sessionSummaries().single { it.id == off }.statistics)
            check(offStats.harnessEnabled == false && offStats.plannerUsed == 0 && offStats.plannerCalls.isEmpty())
            flags["harness_off_records_zero_planner_calls"] = true
            otherAudit.deleteAll()
            check(otherAudit.sessionSummaries().map { it.id }.toSet() == setOf(active))
            check(attachmentAbsent(isolated, off) && otherAudit.readLocalTask(active).task == TASK)
            flags["delete_all_removes_only_ended_attachments"] = true

            MinimalAudit(ctx, LocalTaskRecordStore(ctx, alias)).markInterrupted()
            check(audit.sessionSummaries().single { it.id == active }.let { it.ended && it.reason == "interrupted" })
            check(audit.readLocalTask(active).task == TASK)
            flags["process_recreation_keeps_original_and_seals_metadata_without_resume"] = true
            for (version in listOf("1.0.0", "1.1.0", "1.2.0")) {
                val legacy = id()
                val old = MinimalAudit(ctx, store)
                old.begin(legacy, options(false))
                ended(old, 0, 0)
                check(store.delete(legacy))
                val path = File(isolated, "events/" + legacy + ".jsonl")
                val rows = path.readLines().filter { it.isNotBlank() }.map { line ->
                    JSONObject(line).put("schema_version", version).also { row ->
                        if (version != "1.2.0" && row.getString("event") == "session_started") {
                            row.getJSONObject("data").remove("execution_policy_version")
                            row.getJSONObject("data").put("adapter_version", "1.3.0")
                        }
                    }
                }
                path.writeText(rows.joinToString("\n") { it.toString() } + "\n", Charsets.UTF_8)
                check(old.sessionSummaries().single { it.id == legacy }.ended)
                check(old.readLocalTask(legacy).status == LocalTaskRecordStore.Status.LEGACY_MISSING)
                old.event("task_assessment", mapOf("assessment" to "user_reported_incomplete"))
                check(path.readLines().filter { it.isNotBlank() }.last().let { JSONObject(it).getString("event") == "task_assessment" })
                check(old.exportMetadata(setOf(legacy)).lineSequence().filter { it.isNotBlank() }
                    .all { JSONObject(it).getString("schema_version") == version })
            }
            flags["legacy_event_versions_remain_readable_without_invented_task"] = true
            flags["legacy_assessment_append_keeps_original_schema_version"] = true
            val recovery = id()
            val recoveryAudit = MinimalAudit(ctx, store)
            recoveryAudit.begin(recovery, options().copy(automaticRecovery = true))
            val recoveryFields = mapOf("target" to "GUI", "attempt" to 1, "delay_ms" to 1_000,
                "trigger_code" to "TRANSPORT_ERROR", "status" to "scheduled", "recovery_id" to 1)
            recoveryAudit.event("recovery", recoveryFields)
            recoveryAudit.event("recovery", recoveryFields + ("status" to "started"))
            recoveryAudit.event("recovery", recoveryFields + ("status" to "started"))
            recoveryAudit.event("error", mapOf("category" to "MODEL", "code" to "TRANSPORT_ERROR", "transport_reason" to "DNS_FAILURE"))
            recoveryAudit.event("error", mapOf("category" to "MODEL", "code" to "API_ERROR", "http_status" to 503))
            val recoveryPath = File(isolated, "events/$recovery.jsonl")
            val recordedCount = recoveryPath.readLines().filter { it.isNotBlank() }.size
            recoveryAudit.event("recovery", recoveryFields + ("message" to PRIVATE_REPLY))
            recoveryAudit.event("recovery", recoveryFields + ("attempt" to 3))
            recoveryAudit.event("recovery", recoveryFields + ("delay_ms" to 0))
            recoveryAudit.event("error", mapOf("category" to "MODEL", "code" to "TRANSPORT_ERROR", "transport_reason" to PRIVATE_REPLY))
            recoveryAudit.event("error", mapOf("category" to "MODEL", "code" to "API_ERROR", "http_status" to 999))
            check(recoveryPath.readLines().filter { it.isNotBlank() }.size == recordedCount)
            recoveryAudit.event("session_ended", mapOf("reason" to "runtime_failure", "completion_source" to "none", "gui_steps_used" to 2, "planner_calls_used" to 0))
            val recoveryRows = recoveryPath.readLines().filter { it.isNotBlank() }.map(::JSONObject)
            check(recoveryRows.all { it.getString("schema_version") == "1.3.0" })
            check(recoveryRows.first().getJSONObject("data").getString("execution_policy_version") == "1.2.0")
            check(!recoveryPath.readText().contains(PRIVATE_REPLY))
            check(recoveryAudit.sessionSummaries().single { it.id == recovery }.let {
                it.reason == "runtime_failure" && it.statistics?.automaticRecoveries == 1
            })
            flags["automatic_recovery_fixed_metadata_and_runtime_failure_use_schema13"] = true
            flags["automatic_recovery_metadata_rejects_body_unknown_code_and_invalid_bounds"] = true
            val recoveryInterrupted = id()
            recoveryAudit.begin(recoveryInterrupted, options().copy(automaticRecovery = true))
            MinimalAudit(ctx, store).markInterrupted()
            check(File(isolated, "events/$recoveryInterrupted.jsonl").readLines().filter { it.isNotBlank() }
                .all { JSONObject(it).getString("schema_version") == "1.3.0" })
            check(recoveryAudit.sessionSummaries().single { it.id == recoveryInterrupted }.reason == "interrupted")
            flags["interrupted_schema13_session_keeps_its_version"] = true
            // Existing synthetic direct-store records have no session_started and are orphans.
            val brokenLog = id()
            store.save(brokenLog, TASK)
            File(isolated, "events/" + brokenLog + ".jsonl").writeText("{broken", Charsets.UTF_8)
            MinimalAudit(ctx, store).markInterrupted()
            check(attachmentAbsent(isolated, first) && attachmentAbsent(isolated, second))
            check(attachmentAbsent(isolated, brokenLog) && !File(isolated, "events/" + brokenLog + ".jsonl").exists())
            check(audit.readLocalTask(active).task == TASK)
            flags["startup_removes_orphan_and_unreadable_first_record_attachments"] = true

            val analysisProbe = analysisRecords(File(isolated, "analysis"), alias, flags)

            gateFailure(File(isolated, "failure-save"), alias, true, counters)
            flags["task_save_failure_blocks_coordinator_before_model_or_observation"] = true
            gateFailure(File(isolated, "failure-log"), alias, false, counters)
            flags["metadata_start_failure_rolls_back_task_and_blocks_coordinator"] = true
            check(counters.models == 0 && counters.observes == 0 && counters.performs == 0)
            val keyLoss = id()
            store.save(keyLoss, TASK)
            check(keyStore.containsAlias(alias))
            keyStore.deleteEntry(alias)
            check(store.read(keyLoss).status == LocalTaskRecordStore.Status.UNAVAILABLE)
            check(store.delete(keyLoss))
            flags["missing_encryption_key_is_unavailable_and_ciphertext_can_be_deleted"] = true
            check(analysisProbe.audit.readLocalTask(analysisProbe.id).status == LocalTaskRecordStore.Status.UNAVAILABLE)
            check(analysisProbe.audit.exportAnalysisCsv(setOf(analysisProbe.id)) == analysisProbe.csv)
            flags["analysis_csv_independent_of_task_encryption_key_availability"] = true
            check(runtimeIdle())
            flags["actual_runtime_left_idle_or_ended"] = true
        } catch (_: Exception) {
            code = "TASK_RECORDS_ASSERTION_FAILED"
        } finally {
            try {
                check(alias.startsWith(ALIAS_PREFIX) && alias == ALIAS_PREFIX + token)
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.also { store ->
                    if (store.containsAlias(alias)) store.deleteEntry(alias)
                    check(!store.containsAlias(alias))
                }
                flags["only_unique_verification_key_alias_removed"] = true
                check(isolated.parentFile == parent && isolated.name == "task-records-verification-" + token)
                if (created) check(!isolated.exists() || isolated.deleteRecursively())
                flags["isolated_directory_removed"] = !isolated.exists()
            } catch (_: Exception) {
                code = "TASK_RECORDS_ISOLATION_CLEANUP_FAILED"
                flags["isolated_directory_removed"] = false
            }
        }
        return Result(if (code == "OK" && flags.values.all { it }) "PASS" else "FAIL", code,
            flags, counters.models, counters.observes, counters.performs)
    }

    /** Synthetic on-disk histories; no task text is needed by derived analysis or CSV. */
    private fun analysisRecords(folder: File, alias: String, flags: MutableMap<String, Boolean>): AnalysisCsvProbe {
        check(folder.mkdirs())
        val ctx = wrapped(folder)
        val tasks = LocalTaskRecordStore(ctx, alias)
        val ids = mutableListOf<String>()
        for (version in listOf("1.0.0", "1.1.0", "1.2.0", "1.3.0")) {
            val sessionId = id().also { ids.add(it) }
            val writer = MinimalAudit(ctx, tasks)
            writer.begin(sessionId, options().copy(automaticRecovery = version == "1.3.0"))
            ended(writer, 0, 0)
            val path = File(folder, "events/$sessionId.jsonl")
            val rows = path.readLines().filter { it.isNotBlank() }.mapIndexed { index, line ->
                JSONObject(line).put("schema_version", version).put("elapsed_ms", if (index == 0) 0L else 100L).also { record ->
                    if (version in setOf("1.0.0", "1.1.0") && record.optString("event") == "session_started") {
                        record.getJSONObject("data").remove("execution_policy_version")
                        record.getJSONObject("data").put("adapter_version", "1.3.0")
                    }
                }
            }
            path.writeText(rows.joinToString("\n") { it.toString() } + "\n", Charsets.UTF_8)
            val ciphertext = taskFile(folder, sessionId).readBytes()
            val recreated = MinimalAudit(ctx, LocalTaskRecordStore(ctx, alias))
            check(recreated.sessionSummaries().single { it.id == sessionId }.analysis?.userAssessment == null)
            check(recreated.assessSession(sessionId, "user_reported_complete"))
            check(recreated.sessionSummaries().single { it.id == sessionId }.analysis?.let {
                it.userAssessment == "user_reported_complete" && it.durationMs == 100L && it.versions.schemaVersion == version
            } == true)
            check(recreated.assessSession(sessionId, "user_reported_incomplete"))
            val rated = recreated.sessionSummaries().single { it.id == sessionId }
            check(rated.analysis?.let { it.userAssessment == "user_reported_incomplete" &&
                it.durationMs == 100L && it.recordedDurationMs == 100L } == true)
            check(taskFile(folder, sessionId).readBytes().contentEquals(ciphertext))
            val ratedRows = path.readLines().filter { it.isNotBlank() }.map(::JSONObject)
            check(ratedRows.size == 4 && ratedRows.all { it.getString("schema_version") == version })
            check(ratedRows.map { it.getLong("seq") } == listOf(1L, 2L, 3L, 4L))
            check(ratedRows.map { it.getLong("elapsed_ms") } == listOf(0L, 100L, 100L, 100L))
        }
        flags["historical_assessments_after_reinstantiation_keep_all_four_schema_versions"] = true
        flags["latest_user_assessment_does_not_extend_task_duration_or_rewrite_task_attachment"] = true

        val audit = MinimalAudit(ctx, tasks)
        val current = id()
        audit.begin(current, options(false))
        model(audit, "GUI", 1, 1, 0, "started")
        val currentPath = File(folder, "events/$current.jsonl")
        val beforeCurrent = currentPath.readBytes()
        check(audit.assessSession(ids.first(), "user_reported_complete"))
        check(currentPath.readBytes().contentEquals(beforeCurrent))
        check(!audit.assessSession(current, "user_reported_complete"))
        check(currentPath.readBytes().contentEquals(beforeCurrent))
        model(audit, "GUI", 1, 1, 0, "succeeded", 13)
        val currentRows = currentPath.readLines().filter { it.isNotBlank() }.map(::JSONObject)
        check(currentRows.size == 3 && currentRows.all { it.getString("session_id") == current })
        check(currentRows.map { it.getLong("seq") } == listOf(1L, 2L, 3L))
        flags["historical_assessment_does_not_change_current_audit_session_or_sequence"] = true
        flags["active_record_assessment_rejected_without_writing"] = true
        check(!audit.assessSession(id(), "user_reported_complete"))
        check(runCatching { audit.assessSession(ids.first(), PRIVATE_REPLY) }.isFailure)
        check(runCatching { audit.assessSession("../events/private", "user_reported_complete") }.isFailure)
        flags["assessment_rejects_missing_record_invalid_value_and_identifier"] = true
        check(audit.exportAnalysisCsv(emptySet()).isEmpty())
        check(audit.exportAnalysisCsv(setOf(current, id())).isEmpty())
        check(runCatching { audit.exportAnalysisCsv(setOf("../events/private")) }.isFailure)
        flags["analysis_csv_empty_active_or_missing_selection_never_exports_other_records"] = true

        val selected = ids.first()
        val csv = audit.exportAnalysisCsv(setOf(selected, current))
        check(csv.lineSequence().filter { it.isNotBlank() }.count() == 2 && csv.contains(selected))
        check((ids.drop(1) + current).none { csv.contains(it) })
        check(listOf(TASK, PRIVATE_SENTINEL, PRIVATE_KEY, PRIVATE_MODEL, PRIVATE_REPLY, "https://invalid.example").none { csv.contains(it) })
        flags["analysis_csv_contains_only_selected_ended_record"] = true

        // Deliberately malformed synthetic input proves the typed CSV is not a raw metadata dump.
        val selectedPath = File(folder, "events/$selected.jsonl")
        val selectedRows = selectedPath.readLines().filter { it.isNotBlank() }.map(::JSONObject)
        selectedRows.first().getJSONObject("data").put("task", TASK).put("api_key", PRIVATE_KEY)
            .put("model", PRIVATE_MODEL).put("reply", PRIVATE_REPLY).put("url", "https://invalid.example")
        selectedPath.writeText(selectedRows.joinToString("\n") { it.toString() } + "\n", Charsets.UTF_8)
        check(audit.exportAnalysisCsv(setOf(selected)) == csv)
        flags["typed_analysis_csv_excludes_unknown_sensitive_fields_in_synthetic_metadata"] = true
        val taskPath = taskFile(folder, selected)
        val taskCiphertext = taskPath.readBytes()
        taskPath.writeBytes(byteArrayOf(0))
        check(tasks.read(selected).status == LocalTaskRecordStore.Status.UNAVAILABLE)
        check(audit.exportAnalysisCsv(setOf(selected)) == csv)
        taskPath.writeBytes(taskCiphertext)
        check(tasks.read(selected).task == TASK)
        flags["analysis_csv_independent_of_readable_task_attachment"] = true
        check(audit.sessionSummaries().single { it.id == selected }.analysis?.let {
            it.userAssessment == "user_reported_complete" && it.durationMs == 100L && it.ended
        } == true)
        flags["session_summary_exposes_derived_analysis_and_user_report_separately_from_end_reason"] = true
        return AnalysisCsvProbe(audit, selected, csv)
    }

    private data class AnalysisCsvProbe(val audit: MinimalAudit, val id: String, val csv: String)

    private suspend fun gateFailure(folder: File, alias: String, blockTask: Boolean, counters: Counters) {
        check(folder.mkdirs())
        val ctx = wrapped(folder)
        val taskStore = LocalTaskRecordStore(ctx, alias)
        val audit = MinimalAudit(ctx, taskStore)
        val blocked = File(folder, if (blockTask) "local-task-records" else "events")
        check((!blocked.exists() || blocked.delete()) && blocked.createNewFile())
        withContext(Dispatchers.Main.immediate) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val coordinator = Coordinator(scope, RejectingGateway(counters), RejectingPhone(counters), audit)
                coordinator.start(options())
                yield()
                check(coordinator.snapshot.value.state == RunState.BLOCKED)
                check(coordinator.snapshot.value.guiUsed == 0 && coordinator.snapshot.value.plannerUsed == 0)
                check(counters.models == 0 && counters.observes == 0 && counters.performs == 0 && counters.configure == 0)
                if (!blockTask) check(File(folder, "local-task-records").listFiles().orEmpty().isEmpty())
            } finally { scope.cancel() }
        }
    }

    private fun wrapped(folder: File) = object : ContextWrapper(context) {
        override fun getNoBackupFilesDir(): File = folder
    }
    private fun taskFile(root: File, id: String) = File(root, "local-task-records/" + id + ".enc")
    private fun attachmentAbsent(root: File, id: String): Boolean {
        val path = taskFile(root, id)
        return listOf(path, File(path.path + ".bak"), File(path.path + ".new")).none { it.exists() }
    }
    private fun id() = UUID.randomUUID().toString()
    private fun options(harness: Boolean = true) = SessionOptions(TASK, "synthetic.home", setOf("synthetic.authorized"),
        harness, 7, ModelProfile("https://invalid.example/v1", PRIVATE_MODEL, PRIVATE_KEY),
        ModelProfile("https://invalid.example/v1", PRIVATE_MODEL, PRIVATE_KEY), StartMode.HOME_AUTO, "synthetic.home", automaticRecovery = false)
    private fun model(audit: MinimalAudit, role: String, index: Int, gui: Int, planner: Int,
                      status: String, duration: Int = 0, http: Int? = null) {
        val data = linkedMapOf<String, Any?>("role" to role, "phase" to if (role == "PLANNER") "PLANNING" else "ORIGINAL",
            "call_index" to index, "gui_steps_used" to gui, "planner_calls_used" to planner,
            "duration_ms" to duration, "status" to status)
        if (http != null) data["http_status"] = http
        audit.event("model_call", data)
    }
    private fun ended(audit: MinimalAudit, gui: Int, planner: Int) = audit.event("session_ended",
        mapOf("reason" to "user_stop", "completion_source" to "none", "gui_steps_used" to gui, "planner_calls_used" to planner))

    private class Counters { var models = 0; var observes = 0; var performs = 0; var configure = 0 }
    private class RejectingGateway(private val counters: Counters) : ModelGateway {
        override suspend fun gui(context: GuiContext): GuiReply { counters.models++; error("UNEXPECTED_LOCAL_MODEL_STUB") }
        override suspend fun planner(context: PlannerContext): PlannerDecision { counters.models++; error("UNEXPECTED_LOCAL_MODEL_STUB") }
        override fun cancelAll() {}
    }
    private class RejectingPhone(private val counters: Counters) : PhonePort {
        override val connected = true
        override val inputInFlight = false
        override fun configureSession(options: SessionOptions?) { if (options != null) counters.configure++ }
        override suspend fun observe(): ScreenFrame { counters.observes++; error("UNEXPECTED_LOCAL_OBSERVE_STUB") }
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>,
                                     canDispatch: () -> Boolean): ActionResult {
            counters.performs++; error("UNEXPECTED_LOCAL_PERFORM_STUB")
        }
        override fun setCaptureEnabled(enabled: Boolean) { check(!enabled) }
        override fun showState(snapshot: RunSnapshot) {}
    }

    private companion object {
        const val ALIAS_PREFIX = "mobileharness.verification.task-records."
        const val PRIVATE_SENTINEL = "TASK_RECORD_PRIVATE_SENTINEL"
        const val TASK = "TASK_RECORD_PRIVATE_SENTINEL 中文 task 😀\n第二行 English 123"
        const val PRIVATE_KEY = "TASK_RECORD_PRIVATE_KEY_SENTINEL"
        const val PRIVATE_MODEL = "TASK_RECORD_PRIVATE_MODEL_SENTINEL"
        const val PRIVATE_REPLY = "TASK_RECORD_PRIVATE_REPLY_SENTINEL"
    }
}
