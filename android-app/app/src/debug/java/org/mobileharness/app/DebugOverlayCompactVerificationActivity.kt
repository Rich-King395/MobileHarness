package org.mobileharness.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mobileharness.app.verification.OverlayCompactVerification
import java.io.File

/** DUMP-only synthetic control rendering. No device capture, target nodes or model service. */
class DebugOverlayCompactVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var started = 0L
    private lateinit var statusView: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        statusView = TextView(this).apply { text = "悬浮控制验证\n仅绘制合成控件，不采集屏幕。"; textSize = 18f }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 50)
            addView(statusView)
        })
        runVerification()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (job?.isActive != true) runVerification()
    }

    private fun runVerification() {
        if (job?.isActive == true) return
        started = SystemClock.elapsedRealtime()
        write(OverlayCompactVerification.Result())
        job = scope.launch(Dispatchers.Default) {
            OverlayCompactVerification(applicationContext).run(::write)
        }
    }

    @Synchronized private fun write(result: OverlayCompactVerification.Result) {
        val payload = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("status", result.status).put("code", result.code)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("flags", JSONObject(result.flags)).put("cases", JSONArray(result.cases))
            .put("gui_network_requests", 0).put("planner_network_requests", 0)
            .put("device_screenshots", 0).put("target_node_reads", 0)
            .put("synthetic_control_image_written", result.imageWritten)
            .put("runtime_controls_unchanged", result.controlsUnchanged)
        val file = AtomicFile(File(noBackupFilesDir, "overlay-compact-verification.json"))
        val stream = file.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
        runOnUiThread { statusView.text = "悬浮控制验证\n${result.status} · ${result.code}" }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
