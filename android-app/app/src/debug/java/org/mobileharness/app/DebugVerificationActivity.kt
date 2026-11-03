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
import org.mobileharness.app.verification.DeviceVerification
import java.io.File

/** Debug/shell-only entry. It preserves the service process, settings and user credentials. */
class DebugVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var progress: TextView
    private val results = linkedMapOf<String, Pair<String, String>>()
    private var start = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 100, 40, 50) }
        progress = TextView(this).apply { textSize = 18f; text = "本地设备验收\n仅操作 MobileHarness Test，图片不落盘、不发送远端。" }
        root.addView(progress)
        root.addView(Button(this).apply {
            text = "停止当前验证"
            setOnClickListener { HarnessAccessibilityService.instance?.controls?.stop() }
        })
        setContentView(root)
        runVerification()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (job?.isActive != true) runVerification() }

    private fun runVerification() {
        if (job?.isActive == true) return
        start = SystemClock.elapsedRealtime()
        results.clear()
        (1..4).forEach { results["D-0$it"] = "NOT_RUN" to "PENDING" }
        val service = HarnessAccessibilityService.instance
        if (service?.connected != true || service.inputInFlight || service.controls?.snapshot?.value?.state !in setOf(RunState.IDLE, RunState.ENDED)) {
            write("BLOCKED", "SERVICE_NOT_IDLE_OR_CONNECTED")
            return
        }
        write("RUNNING", "OK")
        job = scope.launch(Dispatchers.Default) {
            try {
                DeviceVerification(applicationContext).runAll { id, passed, code ->
                    if (id in results && code.matches(Regex("[A-Za-z0-9_]{1,80}"))) results[id] = (if (passed) "PASS" else "FAIL") to code
                    write("RUNNING", "OK")
                }
                write(if (results.values.all { it.first == "PASS" }) "PASS" else "FAIL", "FINISHED")
            } catch (cancelled: CancellationException) {
                write("CANCELLED", "CANCELLED")
                throw cancelled
            } catch (_: Exception) {
                write("FAIL", "VERIFICATION_FAILURE")
            }
            withContext(Dispatchers.Main) { progress.text = display() }
        }
    }

    @Synchronized private fun write(status: String, code: String) {
        val payload = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("prompt_bundle_version", "1.1.0").put("status", status).put("code", code)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - start)
            .put("cases", JSONArray().apply { results.forEach { (id, result) ->
                put(JSONObject().put("id", id).put("status", result.first).put("code", result.second))
            } })
        val file = AtomicFile(File(noBackupFilesDir, "device-verification.json"))
        val stream = file.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
    }
    private fun display() = "本地设备验收\n" + results.entries.joinToString("\n") { "${it.key}: ${it.value.first} (${it.value.second})" }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
