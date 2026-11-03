package org.mobileharness.app

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.mobileharness.app.core.RunState
import org.mobileharness.app.verification.TaskRecordsVerification
import java.io.File

/** DUMP-only isolated encrypted task-record probe. Never accesses actual user task records. */
class DebugTaskRecordsVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = 0L
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        status = TextView(this).apply {
            textSize = 18f
            setPadding(40, 100, 40, 50)
            text = "Local task-record verification\nIsolated synthetic data. No network, screenshots or phone input."
        }
        setContentView(status)
        started = SystemClock.elapsedRealtime()
        val app = application as HarnessApplication
        val idle = { app.runtime.snapshot.value.state in setOf(RunState.IDLE, RunState.ENDED) }
        if (!idle()) {
            write(TaskRecordsVerification.Result("BLOCKED", "ACTUAL_SESSION_ACTIVE"))
            return
        }
        write(TaskRecordsVerification.Result())
        scope.launch(Dispatchers.Default) {
            val result = try { TaskRecordsVerification(applicationContext, idle).run() }
            catch (_: Exception) { TaskRecordsVerification.Result("FAIL", "TASK_RECORDS_PROBE_FAILED") }
            write(result)
        }
    }

    @Synchronized private fun write(result: TaskRecordsVerification.Result) {
        val payload = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("status", result.status).put("code", result.code)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("metadata_origin", "SYNTHETIC_STORAGE_ONLY")
            .put("gui_network_requests", 0).put("planner_network_requests", 0)
            .put("screenshots", 0).put("phone_perform_calls", 0)
            .put("local_model_stub_calls", result.localModelStubCalls)
            .put("local_observe_stub_calls", result.localObserveStubCalls)
            .put("local_perform_stub_calls", result.localPerformStubCalls)
            .put("flags", JSONObject(result.flags))
        val target = AtomicFile(File(noBackupFilesDir, "task-records-verification.json"))
        val stream = target.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch (_: Exception) { target.failWrite(stream) }
        runOnUiThread { status.text = "Local task-record verification\n" + result.status + " · " + result.code }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
