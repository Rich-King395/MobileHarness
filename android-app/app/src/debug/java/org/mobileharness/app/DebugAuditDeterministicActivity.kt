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
import org.json.JSONArray
import org.json.JSONObject
import org.mobileharness.app.verification.MinimalAuditDeterministicVerification
import java.io.File

/** Explicit DUMP-only storage probe. All exported events are isolated synthetic records. */
class DebugAuditDeterministicActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var start = 0L
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        status = TextView(this).apply { textSize = 18f; setPadding(40, 100, 40, 50); text = "最小日志确定性验收\n隔离合成记录，零网络、零截图、零输入。" }
        setContentView(status)
        start = SystemClock.elapsedRealtime()
        write(MinimalAuditDeterministicVerification.Result("RUNNING", "PENDING", emptyMap(), JSONArray(), JSONArray(), JSONArray()))
        scope.launch(Dispatchers.Default) {
            val result = try { MinimalAuditDeterministicVerification(applicationContext).run() }
            catch (_: Exception) { MinimalAuditDeterministicVerification.Result("FAIL", "AUDIT_PROBE_FAILED", emptyMap(), JSONArray(), JSONArray(), JSONArray()) }
            write(result)
        }
    }
    @Synchronized private fun write(result: MinimalAuditDeterministicVerification.Result) {
        val payload = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("status", result.status).put("code", result.code).put("elapsed_ms", SystemClock.elapsedRealtime() - start)
            .put("gui_attempts", 0).put("planner_attempts", 0).put("screenshots", 0).put("phone_perform_calls", 0)
            .put("metadata_origin", "SYNTHETIC_STORAGE_ONLY").put("flags", JSONObject(result.flags))
            .put("metadata_v1_2", result.metadataV12).put("metadata_v1_1", result.metadataV11).put("metadata_v1_0", result.metadataV10)
        val file = AtomicFile(File(noBackupFilesDir, "audit-deterministic-verification.json"))
        val stream = file.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
        runOnUiThread { status.text = "最小日志确定性验收\n${result.status} · ${result.code}" }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
