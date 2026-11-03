package org.mobileharness.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mobileharness.app.verification.LocalizationVerification
import java.io.File

/** DUMP-only locale preview acceptance. Never changes the actual app language. */
class DebugLocalizationVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var started = 0L
    private lateinit var status: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        status = TextView(this).apply { text = "Language verification\nSynthetic controls only; no screen capture."; textSize = 18f; setPadding(40, 100, 40, 50) }
        setContentView(status)
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
        write(LocalizationVerification.Result())
        job = scope.launch(Dispatchers.Default) { LocalizationVerification(applicationContext).run(::write) }
    }

    @Synchronized private fun write(result: LocalizationVerification.Result) {
        val record = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("status", result.status).put("code", result.code).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("flags", JSONObject(result.flags)).put("runtime_cases", result.runtimeCases).put("overlay_cases", JSONArray(result.overlayCases))
            .put("gui_network_requests", 0).put("planner_network_requests", 0).put("device_screenshots", 0).put("target_node_reads", 0)
            .put("synthetic_control_image_written", result.imageWritten).put("runtime_controls_unchanged", result.controlsUnchanged)
        val file = AtomicFile(File(noBackupFilesDir, "localization-verification.json"))
        val stream = file.startWrite()
        try { stream.write(record.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
        runOnUiThread { status.text = "Language verification\n${result.status} · ${result.code}" }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
