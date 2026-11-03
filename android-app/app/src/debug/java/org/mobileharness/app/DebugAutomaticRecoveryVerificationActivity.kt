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
import org.mobileharness.app.verification.AutomaticRecoveryVerification
import java.io.File

/** DUMP-only synthetic fault injection. No remote model or user record is used. */
class DebugAutomaticRecoveryVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var verifier: AutomaticRecoveryVerification
    private var startedAt = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        verifier = AutomaticRecoveryVerification(applicationContext)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 50)
            addView(TextView(this@DebugAutomaticRecoveryVerificationActivity).apply {
                text = "自动恢复故障注入验收\n仅合成测试页，本地脚本模型；不访问远端、不创建用户实验记录。"
                textSize = 18f
            })
            addView(Button(this@DebugAutomaticRecoveryVerificationActivity).apply {
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
        startedAt = SystemClock.elapsedRealtime()
        write(AutomaticRecoveryVerification.Result())
        job = scope.launch(Dispatchers.Default) { verifier.run(::write) }
    }
    @Synchronized private fun write(value: AutomaticRecoveryVerification.Result) {
        val payload = JSONObject().put("schema_version", "1.1.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("verification_suite_version", "1.1.0")
            .put("execution_policy_version", "1.2.0").put("status", value.status).put("code", value.code)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt)
            .put("gui_network_requests", 0).put("planner_network_requests", 0)
            .put("flags", JSONObject(value.flags))
            .put("cases", JSONArray(value.cases.map { result -> JSONObject()
                .put("id", result.id).put("passed", result.passed).put("code", result.code)
                .put("gui_steps", result.guiSteps).put("scripted_gui_calls", result.guiCalls)
                .put("planner_calls", result.plannerCalls).put("observation_attempts", result.observationAttempts)
                .put("injected_observation_failures", result.injectedObservationFailures)
                .put("real_screenshot_calls", result.realScreenshotCalls).put("perform_calls", result.performCalls)
                .put("end_reason", result.endReason).put("execution_policy_version", result.executionPolicyVersion)
                .put("started_recoveries", result.startedRecoveries).put("cancelled_recoveries", result.cancelledRecoveries)
                .put("flags", JSONObject(result.flags)) }))
        val file = AtomicFile(File(noBackupFilesDir, "automatic-recovery-verification.json"))
        val stream = file.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
    }
    override fun onDestroy() { verifier.stop(); scope.cancel(); super.onDestroy() }
}
