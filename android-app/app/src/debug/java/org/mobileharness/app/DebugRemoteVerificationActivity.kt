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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mobileharness.app.core.RunState
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.verification.RemoteModelVerification
import java.io.File

/** Debug/shell-only compatibility check. Keys never leave phone storage except HTTPS auth. */
class DebugRemoteVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var progress: TextView
    private lateinit var verifier: RemoteModelVerification
    private val results = linkedMapOf<String, Result>()
    private data class Result(val status: String, val code: String, val decision: String?)
    private var start = 0L
    private var plannerOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        verifier = RemoteModelVerification(applicationContext)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 100, 40, 50) }
        progress = TextView(this).apply {
            textSize = 18f
            text = "真实模型联通验证\n合成任务和测试页截图将发往已配置的 GUI 服务；通过后发往 Planner 服务。每角色最多一次，模型输出不执行。截图不保存。"
        }
        root.addView(progress)
        root.addView(Button(this).apply {
            text = "停止验证"
            setOnClickListener {
                HarnessAccessibilityService.instance?.controls?.stop()
                job?.cancel()
            }
        })
        setContentView(root)
        runVerification()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (job?.isActive != true) runVerification() }

    private fun runVerification() {
        if (job?.isActive == true) return
        start = SystemClock.elapsedRealtime()
        plannerOnly = intent.getStringExtra("role") == "PLANNER"
        results.clear()
        listOf("GUI", "PLANNER").forEach { results[it] = Result("NOT_RUN", "PENDING", null) }
        if (plannerOnly) results["GUI"] = Result("NOT_REQUESTED", "NOT_REQUESTED", null)
        val service = HarnessAccessibilityService.instance
        val runtime = (application as HarnessApplication).runtime
        if (service?.connected != true || service.inputInFlight || service.controls !== runtime || runtime.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED)) {
            write("BLOCKED", "SERVICE_NOT_IDLE_OR_CONNECTED")
            return
        }
        write("RUNNING", "OK")
        job = scope.launch(Dispatchers.Default) {
            try {
                verifier.runAll(plannerOnly = plannerOnly) { role, accepted, code, decision ->
                    if (role in results && code.matches(Regex("[A-Z0-9_]{1,80}"))) {
                        val safeDecision = decision?.takeIf { it in setOf("wait", "finish", "VERIFY", "EXECUTE", "FALLBACK", "FINISH") }
                        results[role] = Result(if (accepted) "PASS" else "FAIL", code, safeDecision)
                        write("RUNNING", "OK")
                    }
                }
                write(if (results.filterKeys { !plannerOnly || it == "PLANNER" }.values.all { it.status == "PASS" }) "PASS" else "FAIL", "FINISHED")
            } catch (cancelled: CancellationException) {
                write("CANCELLED", "CANCELLED")
                throw cancelled
            } catch (_: Exception) {
                write("FAIL", "REMOTE_DIAGNOSTIC_FAILURE")
            }
            withContext(Dispatchers.Main) { progress.text = display() }
        }
    }

    @Synchronized private fun write(status: String, code: String) {
        val counts = verifier.attemptCounts
        val payload = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("prompt_bundle_version", "1.1.0").put("status", status).put("code", code)
            .put("requested_roles", if (plannerOnly) "PLANNER" else "GUI_THEN_PLANNER")
            .put("elapsed_ms", SystemClock.elapsedRealtime() - start)
            .put("gui_attempts", counts["GUI"] ?: 0).put("planner_attempts", counts["PLANNER"] ?: 0)
            .put("phone_actions", 0).put("task_success_evaluated", false)
            .put("roles", JSONArray().apply { results.forEach { (role, result) ->
                put(JSONObject().put("role", role).put("status", result.status).put("code", result.code)
                    .put("decision", result.decision ?: JSONObject.NULL))
            } })
        val file = AtomicFile(File(noBackupFilesDir, "remote-verification.json"))
        val stream = file.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
    }
    private fun display() = "真实模型联通验证\n" + results.entries.joinToString("\n") { "${it.key}: ${it.value.status} (${it.value.code})" } + "\n仅验证请求及输出协议，不代表任务成功。"
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
