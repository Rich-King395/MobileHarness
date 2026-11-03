package org.mobileharness.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mobileharness.app.R
import org.mobileharness.app.storage.ExperimentAnalysis
import org.mobileharness.app.storage.ExperimentStatistics
import org.mobileharness.app.storage.LocalTaskRecordStore
import org.mobileharness.app.storage.MinimalAudit

/** Task text is decrypted only while its record is being viewed, never added to event metadata. */
@Composable
internal fun ExperimentRecordCard(
    row: MinimalAudit.SessionSummary,
    selected: Boolean,
    canManage: Boolean,
    reloadKey: Int,
    loadTask: (String) -> LocalTaskRecordStore.Lookup,
    onSelect: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onAssess: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var expanded by remember(row.id) { mutableStateOf(false) }
    var readAttempt by remember(row.id) { mutableIntStateOf(0) }
    val lookup by produceState<LocalTaskRecordStore.Lookup?>(null, row.id, reloadKey, readAttempt) {
        value = withContext(Dispatchers.IO) {
            try { loadTask(row.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LocalTaskRecordStore.Lookup(LocalTaskRecordStore.Status.UNAVAILABLE) }
        }
    }
    val statistics = row.statistics
    val analysis = row.analysis
    val gui = if (statistics == null) row.guiUsed else statistics.guiUsed
    val budget = if (statistics == null) row.budget else statistics.guiBudget
    val planner = if (statistics == null) row.plannerUsed else statistics.plannerUsed
    val harness = if (statistics == null) row.harnessEnabled else statistics.harnessEnabled
    val task = lookup?.task
    val title = task ?: context.getString(when (lookup?.status) {
        null -> R.string.record_task_loading
        LocalTaskRecordStore.Status.UNAVAILABLE -> R.string.record_task_unavailable
        else -> R.string.record_task_missing
    })
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(selected, onSelect, enabled = canManage && row.ended,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(context.getString(R.string.ui_session, row.id.take(8)),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(context.getString(R.string.record_gui_usage, gui?.toString() ?: "?", budget?.toString() ?: "?"),
                    style = MaterialTheme.typography.labelLarge)
                Text(context.getString(R.string.record_planner_usage, planner?.toString() ?: "?"),
                    style = MaterialTheme.typography.labelLarge)
                statistics?.automaticRecoveries?.takeIf { it > 0 }?.let {
                    Text(context.getString(R.string.record_automatic_recoveries, it), style = MaterialTheme.typography.labelLarge)
                }
                Text(context.getString(when (harness) {
                    true -> R.string.record_harness_on
                    false -> R.string.record_harness_off
                    null -> R.string.record_harness_unknown
                }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(context.getString(if (!row.ended) R.string.ui_session_active else when (row.reason) {
                "model_declared_complete" -> R.string.ui_model_declared_end
                "model_unable" -> R.string.ui_model_unable
                "user_stop" -> R.string.ui_user_stop
                "budget_exhausted" -> R.string.ui_budget_exhausted
                "interrupted" -> R.string.ui_session_interrupted
                "runtime_failure" -> R.string.record_runtime_failure
                else -> R.string.ui_ended
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.ended) {
                Text(context.getString(R.string.analysis_result, context.getString(when (analysis?.userAssessment) {
                    "user_reported_complete" -> R.string.analysis_complete
                    "user_reported_incomplete" -> R.string.analysis_incomplete
                    else -> R.string.analysis_unrated
                })), style = MaterialTheme.typography.labelLarge)
            }
            analysis?.durationMs?.let {
                Text(context.getString(R.string.analysis_duration, analysisDuration(context, it)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                HorizontalDivider()
                if (row.ended) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = analysis?.userAssessment == "user_reported_complete",
                            onClick = { onAssess("user_reported_complete") }, enabled = canManage,
                            label = { Text(context.getString(R.string.ui_assessment_complete)) },
                            modifier = Modifier.heightIn(min = 48.dp))
                        FilterChip(selected = analysis?.userAssessment == "user_reported_incomplete",
                            onClick = { onAssess("user_reported_incomplete") }, enabled = canManage,
                            label = { Text(context.getString(R.string.ui_assessment_incomplete)) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                analysis?.let { RecordAnalysisDetails(it) }
                Text(context.getString(R.string.record_original_task), style = MaterialTheme.typography.labelLarge)
                if (task != null) SelectionContainer { Text(task, style = MaterialTheme.typography.bodyMedium) }
                else Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (lookup?.status == LocalTaskRecordStore.Status.UNAVAILABLE) {
                    TextButton(onClick = { readAttempt++ }) { Text(context.getString(R.string.record_retry_read)) }
                }
                Text(context.getString(R.string.record_step_definition), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(context.getString(R.string.record_planner_calls), style = MaterialTheme.typography.labelLarge)
                val calls = statistics?.plannerCalls.orEmpty()
                if (calls.isEmpty()) Text(context.getString(when {
                    planner == 0 && harness == false -> R.string.record_planner_disabled
                    planner == 0 -> R.string.record_planner_not_called
                    else -> R.string.record_planner_details_missing
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (call in calls) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(context.getString(R.string.record_planner_call_status, call.index, context.getString(when (call.status) {
                            ExperimentStatistics.CallStatus.SUCCEEDED -> R.string.record_call_succeeded
                            ExperimentStatistics.CallStatus.TRANSPORT_ERROR -> R.string.record_call_transport_error
                            ExperimentStatistics.CallStatus.API_ERROR -> R.string.record_call_api_error
                            ExperimentStatistics.CallStatus.INVALID_OUTPUT -> R.string.record_call_invalid_output
                            ExperimentStatistics.CallStatus.CANCELLED -> R.string.record_call_cancelled
                            ExperimentStatistics.CallStatus.LATE_DISCARDED -> R.string.record_call_late
                            ExperimentStatistics.CallStatus.IN_FLIGHT -> R.string.record_call_in_flight
                            ExperimentStatistics.CallStatus.NOT_RECORDED -> R.string.record_call_not_recorded
                        })), style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            call.durationMs?.let { Text(context.getString(R.string.record_call_duration, it), style = MaterialTheme.typography.bodySmall) }
                            call.httpStatus?.let { Text("HTTP $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        Text(context.getString(R.string.record_planner_decision, context.getString(when (call.decision) {
                            "EXECUTE" -> R.string.record_decision_execute
                            "VERIFY" -> R.string.record_decision_verify
                            "FALLBACK" -> R.string.record_decision_fallback
                            "FINISH" -> R.string.record_decision_finish
                            else -> R.string.record_not_recorded
                        })), style = MaterialTheme.typography.bodySmall)
                        call.completionStatus?.let { Text(context.getString(R.string.record_planner_assessment, context.getString(when (it) {
                            "COMPLETE" -> R.string.record_assessment_complete
                            "INCOMPLETE" -> R.string.record_assessment_incomplete
                            else -> R.string.record_assessment_unverifiable
                        })), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(context.getString(if (expanded) R.string.record_hide_details else R.string.record_details))
                }
                TextButton(enabled = canManage && row.ended, onClick = onDelete) { Text(context.getString(R.string.ui_delete)) }
            }
        }
    }
}

@Composable
private fun RecordAnalysisDetails(analysis: ExperimentAnalysis.Analysis) {
    val context = LocalContext.current
    val unknown = context.getString(R.string.analysis_unknown)
    if (analysis.durationMs == null && analysis.endReason == "interrupted") {
        analysis.recordedDurationMs?.let {
            Text(context.getString(R.string.analysis_recorded_duration, analysisDuration(context, it)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(context.getString(R.string.analysis_handoff_counts, analysis.takeoverCount, analysis.returnCount),
        style = MaterialTheme.typography.bodySmall)
    Text(context.getString(R.string.analysis_end_source, when (analysis.completionSource) {
        "gui" -> "GUI Agent"
        "planner" -> "Planner"
        "confirmation" -> context.getString(R.string.analysis_source_confirmation)
        "none" -> context.getString(R.string.analysis_source_none)
        else -> unknown
    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gui = analysis.guiCalls
    Text(context.getString(R.string.analysis_gui_requests, gui.started), style = MaterialTheme.typography.labelLarge)
    Text(context.getString(R.string.analysis_call_counts, gui.validResponses, gui.failed, gui.cancelled),
        style = MaterialTheme.typography.bodySmall)
    if (gui.lateDiscarded > 0) Text(context.getString(R.string.analysis_call_late, gui.lateDiscarded),
        style = MaterialTheme.typography.bodySmall)
    if (gui.inFlight > 0 || gui.missingTerminal > 0) Text(context.getString(R.string.analysis_call_unfinished,
        gui.inFlight, gui.missingTerminal), style = MaterialTheme.typography.bodySmall)
    gui.totalDurationMs?.let {
        Text(context.getString(R.string.analysis_call_time, analysisDuration(context, it), gui.durationSamples),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(context.getString(R.string.analysis_errors), style = MaterialTheme.typography.labelLarge)
    if (analysis.errors.isEmpty()) Text(context.getString(R.string.analysis_no_errors),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    analysis.errors.forEach { error ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(context.getString(R.string.analysis_error_line, context.getString(when (error.category) {
                "MODEL" -> R.string.analysis_error_model
                "DEVICE" -> R.string.analysis_error_device
                else -> R.string.analysis_error_coordinator
            }), error.code, error.count), style = MaterialTheme.typography.bodySmall)
            error.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    Text(context.getString(R.string.analysis_versions), style = MaterialTheme.typography.labelLarge)
    Text(context.getString(R.string.analysis_version_values, analysis.versions.promptBundleVersion ?: unknown,
        analysis.versions.adapterVersion ?: unknown, analysis.versions.executionPolicyVersion ?: unknown),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(context.getString(R.string.analysis_configuration_ids, analysis.versions.guiProfileId?.take(8) ?: unknown,
        analysis.versions.plannerProfileId?.take(8) ?: unknown), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
