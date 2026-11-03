package org.mobileharness.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.mobileharness.app.core.RunState
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.storage.ExperimentStatistics
import org.mobileharness.app.storage.ExperimentAnalysis
import org.mobileharness.app.storage.LocalTaskRecordStore
import org.mobileharness.app.storage.MinimalAudit
import org.mobileharness.app.ui.AppLanguage
import org.mobileharness.app.ui.ExperimentRecordCard
import org.mobileharness.app.ui.ExperimentAnalysisPanel
import org.mobileharness.app.ui.theme.MobileHarnessTheme
import org.json.JSONObject

/** DUMP-only view fixture. No storage, live language choice, screenshot or model access. */
class DebugRecordUiActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        val app = application as HarnessApplication
        if (app.runtime.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED) ||
            HarnessAccessibilityService.instance?.inputInFlight == true) { finish(); return }
        setContent {
            var language by remember { mutableStateOf("en") }
            var assessment by remember { mutableStateOf("user_reported_incomplete") }
            val available = remember(assessment) { AVAILABLE.copy(analysis = ON_ANALYSIS.copy(userAssessment = assessment)) }
            val localized = remember(language) { AppLanguage.previewContext(this@DebugRecordUiActivity, language) }
            CompositionLocalProvider(LocalContext provides localized) {
                MobileHarnessTheme {
                    Surface {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row {
                                TextButton(onClick = { language = "en" }) { Text("English") }
                                TextButton(onClick = { language = "zh" }) { Text("简体中文") }
                            }
                            Text("Record UI fixture", style = MaterialTheme.typography.titleLarge)
                            ExperimentAnalysisPanel(listOfNotNull(available.analysis, MISSING.analysis), selectedScope = false)
                            ExperimentRecordCard(
                                row = available, selected = false, canManage = true, reloadKey = 0,
                                loadTask = { LocalTaskRecordStore.Lookup(LocalTaskRecordStore.Status.AVAILABLE, TASK) },
                                onSelect = {}, onDelete = {}, onAssess = { assessment = it },
                            )
                            ExperimentRecordCard(
                                row = MISSING, selected = false, canManage = false, reloadKey = 0,
                                loadTask = { LocalTaskRecordStore.Lookup(LocalTaskRecordStore.Status.LEGACY_MISSING) },
                                onSelect = {}, onDelete = {},
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TASK = "Open the synthetic test page and inspect the example counter.\n合成任务 · TASK_RECORD_UI_070 · 🧪"
        val ON_ANALYSIS = syntheticAnalysis("00000000-0000-4000-8000-000000000701", true)
        val OFF_ANALYSIS = syntheticAnalysis("00000000-0000-4000-8000-000000000702", false)
        val AVAILABLE = MinimalAudit.SessionSummary("00000000-0000-4000-8000-000000000701", true, "user_stop", 7, 2, 30, true,
            ExperimentStatistics.Statistics(7, 30, 2, true, listOf(
                ExperimentStatistics.PlannerCall(1, ExperimentStatistics.CallStatus.SUCCEEDED, 1200, null, "INCOMPLETE", "VERIFY", true),
                ExperimentStatistics.PlannerCall(2, ExperimentStatistics.CallStatus.API_ERROR, 800, 429, null, null, true),
            ), true), ON_ANALYSIS)
        val MISSING = MinimalAudit.SessionSummary("00000000-0000-4000-8000-000000000702", true, "budget_exhausted", 30, 0, 30, false,
            ExperimentStatistics.Statistics(30, 30, 0, false, emptyList(), true), OFF_ANALYSIS)

        private fun syntheticAnalysis(id: String, harness: Boolean): ExperimentAnalysis.Analysis {
            val records = mutableListOf<JSONObject>()
            fun row(event: String, elapsed: Long, vararg data: Pair<String, Any>) {
                records += JSONObject().put("schema_version", "1.3.0").put("session_id", id)
                    .put("seq", records.size + 1).put("elapsed_ms", elapsed).put("event", event).put("data", JSONObject(data.toMap()))
            }
            fun call(role: String, index: Int, status: String, elapsed: Long, gui: Int, planner: Int, duration: Long = 0, http: Int? = null) {
                val data = mutableListOf<Pair<String, Any>>("role" to role, "phase" to if (role == "PLANNER") "PLANNING" else "ORIGINAL",
                    "call_index" to index, "status" to status, "gui_steps_used" to gui, "planner_calls_used" to planner)
                if (status != "started") data += "duration_ms" to duration
                if (http != null) data += "http_status" to http
                row("model_call", elapsed, *data.toTypedArray())
            }
            row("session_started", 0, "harness_enabled" to harness, "gui_budget" to 30,
                "prompt_bundle_version" to "1.2.0", "adapter_version" to "1.4.0", "execution_policy_version" to "1.2.0",
                "gui_profile_id" to "00000000-0000-4000-8000-000000000901", "planner_profile_id" to "00000000-0000-4000-8000-000000000902")
            if (harness) {
                call("GUI", 1, "started", 100, 1, 0)
                call("GUI", 1, "succeeded", 300, 1, 0, 200)
                call("GUI", 2, "started", 500, 2, 0)
                row("control", 600, "transition" to "takeover_requested")
                call("GUI", 2, "cancelled", 610, 2, 0, 110)
                row("control", 650, "transition" to "manual_granted")
                row("control", 5_000, "transition" to "return_requested")
                row("control", 5_600, "transition" to "return_committed")
                call("PLANNER", 1, "started", 5_700, 2, 1)
                call("PLANNER", 1, "succeeded", 6_900, 2, 1, 1_200)
                row("planner_decision", 6_901, "planner_call_index" to 1, "completion_status" to "INCOMPLETE", "decision" to "VERIFY")
                for (index in 3..6) {
                    call("GUI", index, "started", 7_000L + (index - 3) * 200, index, 1)
                    call("GUI", index, "succeeded", 7_100L + (index - 3) * 200, index, 1, 100)
                }
                call("PLANNER", 2, "started", 7_800, 6, 2)
                call("PLANNER", 2, "api_error", 8_600, 6, 2, 800, 429)
                row("error", 8_601, "category" to "MODEL", "code" to "API_ERROR", "http_status" to 429)
                call("GUI", 7, "started", 9_000, 7, 2)
                call("GUI", 7, "succeeded", 9_200, 7, 2, 200)
            } else {
                for (index in 1..30) {
                    call("GUI", index, "started", index * 200L, index, 0)
                    call("GUI", index, "succeeded", index * 200L + 110, index, 0, 110)
                }
            }
            val duration = if (harness) 43_000L else 9_000L
            row("session_ended", duration, "reason" to if (harness) "user_stop" else "budget_exhausted", "completion_source" to "none",
                "gui_steps_used" to if (harness) 7 else 30, "planner_calls_used" to if (harness) 2 else 0)
            row("task_assessment", duration, "assessment" to if (harness) "user_reported_incomplete" else "user_reported_complete")
            return ExperimentAnalysis.fromRecords(records)
        }
    }
}
