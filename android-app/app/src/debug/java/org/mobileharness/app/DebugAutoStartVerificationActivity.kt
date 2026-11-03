package org.mobileharness.app

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.verification.AutoStartVerification
import java.io.File

/** Explicit DUMP-only debug entry. No credentials, network requests, or saved pictures. */
class DebugAutoStartVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var status: TextView
    private var started = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        status = TextView(this).apply { textSize = 18f; text = "自动启动验证\n仅桌面与合成测试页；不调用模型。" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 50)
            addView(status)
            addView(Button(this@DebugAutoStartVerificationActivity).apply {
                text = "停止验证"
                setOnClickListener { HarnessAccessibilityService.instance?.controls?.stop(); job?.cancel() }
            })
        })
        started = SystemClock.elapsedRealtime()
        write(AutoStartVerification.Result("RUNNING", "PENDING", emptyMap(), 0, 0))
        job = scope.launch(Dispatchers.Default) { write(AutoStartVerification(applicationContext).run()) }
    }
    private fun write(result: AutoStartVerification.Result) {
        val json = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("prompt_bundle_version", "1.2.0").put("status", result.status).put("code", result.code)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("gui_attempts", 0).put("planner_attempts", 0)
            .put("perform_attempts", result.performAttempts).put("completed_performs", result.completedPerforms)
            .put("flags", JSONObject(result.flags))
        val file = AtomicFile(File(noBackupFilesDir, "auto-start-verification.json"))
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
        runOnUiThread { status.text = "自动启动验证\n${result.status} · ${result.code}" }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
