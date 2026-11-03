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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.verification.OverlayCaptureVerification
import java.io.File

/** DUMP-protected debug entry; no network, instrumentation lifecycle, or picture files. */
class DebugOverlayVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var status: TextView
    private var started = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 100, 40, 50) }
        status = TextView(this).apply { textSize = 18f; text = "悬浮截图验证\n仅合成测试页；图片只在内存，不调用模型。" }
        column.addView(status)
        column.addView(Button(this).apply {
            text = "停止验证"
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
        started = SystemClock.elapsedRealtime()
        write(OverlayCaptureVerification.Result())
        job = scope.launch(Dispatchers.Default) {
            OverlayCaptureVerification(applicationContext).run { write(it) }
        }
    }

    @Synchronized private fun write(value: OverlayCaptureVerification.Result) {
        val payload = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("status", value.status).put("code", value.code).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("gui_attempts", 0).put("planner_attempts", 0).put("phone_perform_calls", value.phonePerformCalls)
            .put("installed_before_test", value.installedBeforeTest)
            .put("available_before_test", value.availableBeforeTest)
            .put("synthetic_foreground", value.syntheticForeground)
            .put("expanded_before_capture", value.expandedBeforeCapture)
            .put("visible_reference_differs", value.visibleReferenceDiffers)
            .put("production_matches_hidden_reference", value.productionMatchesHiddenReference)
            .put("installed_after_capture", value.installedAfterCapture)
            .put("available_after_capture", value.availableAfterCapture)
            .put("restored_compact", value.restoredCompact)
            .put("target_initially_conflicts", value.targetInitiallyConflicts)
            .put("relocation_completes", value.relocationCompletes)
            .put("target_clear_after_relocation", value.targetClearAfterRelocation)
            .put("action_unchanged", value.actionUnchanged)
            .put("source_frame_identity_unchanged", value.sourceFrameIdentityUnchanged)
            .put("native_terminal_confirmed", value.nativeTerminalConfirmed)
            .put("controls_restored", value.controlsRestored)
        val file = AtomicFile(File(noBackupFilesDir, "overlay-capture-verification.json"))
        val stream = file.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
        runOnUiThread { status.text = "悬浮截图验证\n${value.status} · ${value.code}" }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
