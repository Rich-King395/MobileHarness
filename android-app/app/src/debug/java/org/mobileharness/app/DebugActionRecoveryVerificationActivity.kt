package org.mobileharness.app

import android.app.Activity
import android.content.Intent
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
import org.mobileharness.app.verification.ActionRecoveryVerification
import java.io.File

/** DUMP-only real-device action recovery acceptance. Fixed metadata only; no model service. */
class DebugActionRecoveryVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var verifier: ActionRecoveryVerification
    private var started = 0L
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        verifier = ActionRecoveryVerification(applicationContext)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 50)
            addView(TextView(this@DebugActionRecoveryVerificationActivity).apply {
                text = "动作失败恢复验收\n仅合成测试页，本地模型替身，网络请求为零。"
                textSize = 18f
            })
            addView(Button(this@DebugActionRecoveryVerificationActivity).apply {
                text = "停止验收"
                setOnClickListener { verifier.stop(); job?.cancel() }
            })
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
        write(ActionRecoveryVerification.Result())
        job = scope.launch(Dispatchers.Default) { verifier.run(::write) }
    }
    @Synchronized private fun write(value: ActionRecoveryVerification.Result) {
        val payload = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("execution_policy_version", "1.1.0").put("status", value.status).put("code", value.code)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("gui_steps", value.guiSteps)
            .put("budget", 8).put("planner_calls", value.plannerCalls).put("screenshot_calls", value.screenshotCalls)
            .put("perform_calls", value.performCalls).put("gui_network_requests", 0).put("planner_network_requests", 0)
            .put("execution_failure_reasons", JSONArray(value.reasons)).put("flags", JSONObject(value.flags))
        val file = AtomicFile(File(noBackupFilesDir, "action-recovery-verification.json"))
        val stream = file.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
