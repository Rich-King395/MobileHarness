package org.mobileharness.app

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.verification.NotificationDeterministicVerification
import java.io.File

/** Shell/DUMP-only deterministic own-notification entry; no remote calls. */
class DebugNotificationDeterministicActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val results = linkedMapOf<String, NotificationDeterministicVerification.Result>()
    private var started = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        val status = TextView(this).apply { textSize = 18f; text = "通知入口确定性验收\n仅自身通知与合成测试页，零网络；人工阶段不读取画面或节点。" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 100, 40, 50); addView(status)
            addView(Button(this@DebugNotificationDeterministicActivity).apply { text = "停止验收"; setOnClickListener { HarnessAccessibilityService.instance?.controls?.stop(); scope.cancel() } })
        })
        started = SystemClock.elapsedRealtime()
        write("RUNNING")
        scope.launch(Dispatchers.Default) {
            try {
                NotificationDeterministicVerification(applicationContext).run(::record)
                val final = synchronized(this@DebugNotificationDeterministicActivity) { if (results.size == 2 && results.values.all { it.status == "PASS" }) "PASS" else if (results.values.any { it.status == "UNCERTAIN" }) "UNCERTAIN" else if (results.values.any { it.status == "BLOCKED" }) "BLOCKED" else "FAIL" }
                write(final)
                withContext(Dispatchers.Main.immediate) { status.text = "通知入口确定性验收\n$final" }
            } catch (cancelled: CancellationException) {
                val uncertain = synchronized(this@DebugNotificationDeterministicActivity) { results.values.any { it.status == "UNCERTAIN" } }
                write(if (uncertain) "UNCERTAIN" else "CANCELLED"); throw cancelled
            }
            catch (_: Exception) { write("FAIL") }
        }
    }
    @Synchronized private fun record(result: NotificationDeterministicVerification.Result) { results[result.variant] = result; write("RUNNING") }
    @Synchronized private fun write(status: String) {
        val rows = JSONArray()
        results.values.forEach { value -> rows.put(JSONObject().put("case_id", "CT-09").put("variant", value.variant)
            .put("layer", "D").put("status", value.status).put("code", value.code).put("flags", JSONObject(value.flags))
            .put("synthetic_gui_attempts", value.syntheticGuiAttempts).put("synthetic_planner_attempts", value.syntheticPlannerAttempts)
            .put("observation_entries", value.observationEntries).put("actual_capture_tickets", value.actualCaptureTickets)
            .put("perform_calls", value.performCalls)) }
        val payload = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("acceptance_contract_version", "1.0.0").put("prompt_bundle_version", "1.1.0")
            .put("status", status).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("gui_network_attempts", 0).put("planner_network_attempts", 0).put("cases", rows)
        val file = AtomicFile(File(noBackupFilesDir, "notification-deterministic-verification.json"))
        val stream = file.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
