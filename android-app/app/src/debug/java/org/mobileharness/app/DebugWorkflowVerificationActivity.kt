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
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.verification.FullWorkflowVerification
import java.io.File

/** DUMP-only full real workflow diagnostic. Additional output is closed metadata only. */
class DebugWorkflowVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var verifier: FullWorkflowVerification
    private lateinit var status: TextView
    private var startedAt = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        verifier = FullWorkflowVerification(applicationContext)
        status = TextView(this).apply {
            textSize = 18f
            text = "真实任务流程诊断\n从桌面打开合成测试页，点击一次后结束。最多 GUI 8 步，不接管、不重试；桌面及合成截图将发送至已配置服务。"
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 50)
            addView(status)
            addView(Button(this@DebugWorkflowVerificationActivity).apply {
                text = "停止诊断"
                setOnClickListener { HarnessAccessibilityService.instance?.controls?.stop(); job?.cancel() }
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
        write(FullWorkflowVerification.Result())
        job = scope.launch(Dispatchers.Default) {
            try { verifier.run { write(it) } }
            catch (cancelled: CancellationException) {
                val last = verifier.latest
                write(if (last.status == "UNCERTAIN") last else last.copy(status = "CANCELLED", code = "CANCELLED"))
                throw cancelled
            } catch (_: Exception) { write(verifier.latest.copy(status = "FAIL", code = "DIAGNOSTIC_FAILURE")) }
            withContext(Dispatchers.Main.immediate) {
                status.text = "真实任务流程诊断\n${verifier.latest.status} · ${verifier.latest.code}"
            }
        }
    }
    @Synchronized private fun write(value: FullWorkflowVerification.Result) {
        val payload = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("prompt_bundle_version", "1.2.0").put("status", value.status).put("code", value.code).put("state", value.state)
            .put("adapter_version", value.adapterVersion ?: JSONObject.NULL)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt).put("session_id", value.sessionId)
            .put("gui_budget", 8).put("harness_enabled", true)
            .put("gui_steps_used", value.guiUsed).put("planner_calls_used", value.plannerUsed)
            .put("actual_gui_calls", value.actualGuiCalls).put("actual_planner_calls", value.actualPlannerCalls)
            .put("observe_calls", value.observeCalls).put("home_frames", value.homeFrames).put("fixture_frames", value.fixtureFrames)
            .put("phone_perform_calls", value.performCalls).put("completed_performs", value.completedPerforms)
            .put("blocked_at_gui", value.blockedAtGui ?: JSONObject.NULL).put("blocked_at_planner", value.blockedAtPlanner ?: JSONObject.NULL)
            .put("error_category", value.errorCategory ?: JSONObject.NULL).put("error_code", value.errorCode ?: JSONObject.NULL)
            .put("protocol_reason", value.protocolReason ?: JSONObject.NULL)
            .put("end_reason", value.endReason ?: JSONObject.NULL).put("completion_source", value.endSource ?: JSONObject.NULL)
            .put("flags", JSONObject(value.flags)).put("calls", JSONArray(value.calls.map { JSONObject(it) }))
            .put("taps", JSONArray(value.taps.map { JSONObject(it) }))
        val file = AtomicFile(File(noBackupFilesDir, "full-workflow-verification.json"))
        val stream = file.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
