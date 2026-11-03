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
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.verification.CalculatorPointVerification
import java.io.File

/** Manifest DUMP-protected debug entry. Metadata has no coordinates, formulas, images, or replies. */
class DebugCalculatorVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var verifier: CalculatorPointVerification
    private lateinit var status: TextView
    private var startedAt = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        verifier = CalculatorPointVerification(applicationContext)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 100, 40, 50) }
        status = TextView(this).apply {
            textSize = 18f
            text = "计算器单步定位诊断\n需当前算式为空。仅一次真实 GUI 请求；截图发送至已配置服务。模型点位命中数字2才派发一次原始点按；不读取历史、不保存截图。"
        }
        column.addView(status)
        column.addView(Button(this).apply {
            text = "停止诊断"
            setOnClickListener { HarnessAccessibilityService.instance?.controls?.stop(); job?.cancel() }
        })
        setContentView(column)
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
        write(CalculatorPointVerification.Result())
        job = scope.launch(Dispatchers.Default) {
            try { verifier.run { write(it) } }
            catch (cancelled: CancellationException) {
                write(verifier.latest.copy(status = "CANCELLED", code = "CANCELLED"))
                throw cancelled
            } catch (_: Exception) {
                write(verifier.latest.copy(status = "FAIL", code = "DIAGNOSTIC_FAILURE"))
            }
            withContext(Dispatchers.Main.immediate) {
                status.text = "计算器单步定位诊断\n${verifier.latest.status} · ${verifier.latest.code}\n此结果不代表多步计算任务通过。"
            }
        }
    }
    @Synchronized private fun write(value: CalculatorPointVerification.Result) {
        val payload = JSONObject().put("schema_version", "1.0.0")
            .put("app_version", BuildConfig.VERSION_NAME).put("prompt_bundle_version", "1.1.0")
            .put("status", value.status).put("code", value.code)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt)
            .put("gui_attempts", value.guiAttempts).put("planner_attempts", 0)
            .put("phone_perform_calls", value.phonePerformCalls)
            .put("precondition_formula_empty", value.preconditionEmpty ?: JSONObject.NULL)
            .put("model_parsed", value.modelParsed).put("output_is_tap", value.outputIsTap ?: JSONObject.NULL)
            .put("point_in_digit_2", value.groundingHit ?: JSONObject.NULL)
            .put("not_executed", value.notExecuted ?: JSONObject.NULL)
            .put("native_completed", value.nativeCompleted ?: JSONObject.NULL)
            .put("formula_is_2", value.formulaIs2 ?: JSONObject.NULL)
            .put("native_terminal_confirmed", value.nativeTerminalConfirmed ?: JSONObject.NULL)
            .put("controls_restored", value.controlsRestored)
        val file = AtomicFile(File(noBackupFilesDir, "calculator-point-verification.json"))
        val stream = file.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
