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
import org.json.JSONObject
import org.mobileharness.app.verification.TlsVerification
import java.io.File

/** DUMP-only, one unauthenticated HTTPS request; fixed network/cause predicates only. */
class DebugTlsVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var verifier: TlsVerification
    private var startedAt = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        verifier = TlsVerification(applicationContext)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 50)
            addView(TextView(this@DebugTlsVerificationActivity).apply {
                text = "HTTPS 连接诊断\n一次无密钥、无模型内容的请求；不截图，不修改任务控制状态。"
                textSize = 18f
            })
            addView(Button(this@DebugTlsVerificationActivity).apply {
                text = "停止诊断"
                setOnClickListener { verifier.cancel(); job?.cancel() }
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
        write(TlsVerification.Result())
        job = scope.launch(Dispatchers.Default) { verifier.run(::write, intent.getBooleanExtra("no_http_proxy", false)) }
    }
    @Synchronized private fun write(value: TlsVerification.Result) {
        val payload = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("status", value.status).put("code", value.code)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt)
            .put("http_status", value.httpStatus ?: JSONObject.NULL).put("network_requests", value.networkRequests)
            .put("gui_model_calls", 0).put("planner_model_calls", 0).put("screenshots", 0)
            .put("cause_depth", value.causeDepth).put("flags", JSONObject(value.flags))
        val file = AtomicFile(File(noBackupFilesDir, "tls-verification.json"))
        val stream = file.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
    }
    override fun onDestroy() { verifier.cancel(); scope.cancel(); super.onDestroy() }
}
