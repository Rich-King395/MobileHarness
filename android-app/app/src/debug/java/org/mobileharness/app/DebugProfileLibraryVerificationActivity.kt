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
import org.mobileharness.app.verification.ProfileLibraryVerification
import java.io.File

/** DUMP-protected, storage-only debug entry. No model, screenshots, input, or user profiles. */
class DebugProfileLibraryVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val results = mutableListOf<ProfileLibraryVerification.CaseResult>()
    private var started = 0L
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        status = TextView(this).apply { textSize = 18f; setPadding(40, 100, 40, 50); text = "本地配置库验证\n仅使用隔离合成数据，不调用模型。" }
        setContentView(status)
        started = SystemClock.elapsedRealtime()
        write("RUNNING")
        scope.launch(Dispatchers.Default) {
            try {
                ProfileLibraryVerification(applicationContext).runAll { result -> results.add(result); write("RUNNING") }
                write(if (results.size == 6 && results.all { it.passed }) "PASS" else "FAIL")
            } catch (_: Exception) { write("FAIL") }
        }
    }

    @Synchronized private fun write(value: String) {
        val payload = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("status", value).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("gui_attempts", 0).put("planner_attempts", 0).put("screenshots", 0).put("phone_perform_calls", 0)
            .put("cases", JSONArray().apply { for (result in results) put(JSONObject().put("id", result.id)
                .put("passed", result.passed).put("code", result.code)) })
        val target = AtomicFile(File(noBackupFilesDir, "profile-library-verification.json"))
        val stream = target.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch (_: Exception) { target.failWrite(stream) }
        runOnUiThread { status.text = "本地配置库验证\n$value" }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
