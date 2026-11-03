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
import org.mobileharness.app.core.RunState
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.verification.DeviceVerification
import java.io.File
import java.util.UUID

/** Shell/DUMP-only deterministic acceptance; no real model, images or reply text exported. */
class DebugHarnessAcceptanceActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val results = linkedMapOf<String, Pair<String, String>>()
    private var started = 0L
    private var runId = ""
    private var prefix = ""
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 100, 40, 50)
            addView(TextView(this@DebugHarnessAcceptanceActivity).apply {
                text = "Harness 确定性验收\n仅合成测试页，模型为本地替身，网络请求为零。"; textSize = 18f
            })
            addView(Button(this@DebugHarnessAcceptanceActivity).apply {
                text = "停止验收"
                setOnClickListener { HarnessAccessibilityService.instance?.controls?.stop(); job?.cancel() }
            })
        })
        runCases()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (job?.isActive != true) runCases() }
    private fun runCases() {
        if (job?.isActive == true) return
        val requested = intent.getStringExtra("prefix").orEmpty()
        prefix = requested.takeIf { it.matches(Regex("[A-Z0-9_-]{0,80}")) } ?: "INVALID"
        started = SystemClock.elapsedRealtime(); runId = UUID.randomUUID().toString(); results.clear()
        val verifier = DeviceVerification(applicationContext)
        verifier.harnessCases().filter { it.first.startsWith(prefix) }.forEach { results[it.first] = "NOT_RUN" to "PENDING" }
        if (results.isEmpty()) { write("BLOCKED", "INVALID_SELECTION"); return }
        val service = HarnessAccessibilityService.instance
        val runtime = (application as HarnessApplication).runtime
        if (service?.connected != true || service.inputInFlight || service.controls !== runtime ||
            runtime.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED)) {
            write("BLOCKED", "SERVICE_NOT_IDLE_OR_CONNECTED"); return
        }
        write("RUNNING", "OK")
        job = scope.launch(Dispatchers.Default) {
            try {
                verifier.runHarnessAcceptance(prefix) { id, passed, code ->
                    if (id in results && code.matches(Regex("[A-Za-z0-9_]{1,100}"))) {
                        results[id] = (if (passed) "PASS" else "FAIL") to code
                        write("RUNNING", "OK")
                    }
                }
                write(if (results.values.all { it.first == "PASS" }) "PASS" else "FAIL", "FINISHED")
            } catch (cancelled: CancellationException) { write("CANCELLED", "CANCELLED"); throw cancelled }
            catch (_: Exception) { write("FAIL", "DETERMINISTIC_SUITE_FAILURE") }
        }
    }
    @Synchronized private fun write(status: String, code: String) {
        val service = HarnessAccessibilityService.instance
        val restored = service?.controls === (application as HarnessApplication).runtime
        val record = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("run_id", runId).put("prefix", prefix).put("status", status).put("code", code)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("gui_network_requests", 0).put("planner_network_requests", 0)
            .put("controls_restored", restored).put("native_terminal_confirmed", service?.inputInFlight == false)
            .put("cases", JSONArray().apply { results.forEach { (id, result) ->
                put(JSONObject().put("id", id).put("status", result.first).put("code", result.second))
            } })
        val file = AtomicFile(File(noBackupFilesDir, "harness-deterministic-verification.json"))
        val stream = file.startWrite()
        try { stream.write(record.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
