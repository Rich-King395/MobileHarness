package org.mobileharness.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.mobileharness.app.core.*
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.verification.CalculatorFidelityEvidence
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Explicit zero-network control calibration; never substitutes any model action. */
class DebugCalculatorCalibrationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runId = UUID.randomUUID().toString()
    private val steps = mutableListOf<Map<String, Any?>>()
    private var status = "RUNNING"
    private var code = "PENDING"
    private var restored = false
    private var terminal = false
    private var probeFailed = false
    private var captures = 0
    private val started = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        setContentView(TextView(this).apply { text = "计算器执行器校准 · 不调用模型"; setPadding(40, 100, 40, 50) })
        write()
        scope.launch { runCalibration() }
    }

    private suspend fun runCalibration() {
        val service = HarnessAccessibilityService.instance
        val app = application as HarnessApplication
        var controls: CalibrationControls? = null
        var previous: RuntimeControls? = null
        var flags: Int? = null
        val evidence = CalculatorFidelityEvidence()
        val oldObserver = service?.diagnosticObserver
        var frame: ScreenFrame? = null
        var signature: ByteArray? = null
        try {
            check(service != null && service.connected) { "SERVICE_DISCONNECTED" }
            previous = service.controls
            check(previous === app.runtime && previous.snapshot.value.state in setOf(RunState.IDLE, RunState.ENDED) && !service.inputInFlight) { "SERVICE_NOT_IDLE" }
            controls = CalibrationControls(service, currentCoroutineContext().job)
            service.controls = controls
            flags = service.serviceInfo.flags
            service.serviceInfo = service.serviceInfo.apply { this.flags = this.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            service.diagnosticObserver = evidence
            val unused = ModelProfile(baseUrl = "https://invalid.example/v1", model = "unused")
            service.configureSession(SessionOptions("Zero-network calculator executor calibration", PACKAGE, setOf(PACKAGE), false, 4, unused, unused))
            val launch = packageManager.getLaunchIntentForPackage(PACKAGE) ?: error("CALCULATOR_NOT_LAUNCHABLE")
            launch.selector = null
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(900)
            check(formula(service).first.isEmpty()) { "PRECONDITION_FORMULA_NOT_EMPTY" }
            service.setCaptureEnabled(true)
            evidence.resetImage()
            frame = service.observe().also { captures++ }
            signature = formulaSignature(frame, formula(service).second)
            var expected = ""
            for (digit in listOf(1, 5, 9, 0)) {
                currentCoroutineContext().ensureActive()
                check(controls.snapshot.value.state == RunState.AUTO) { "AUTHORIZATION_UNAVAILABLE" }
                val currentFrame = checkNotNull(frame)
                val bounds = bounds(service, "digit_$digit")
                val x = bounds.centerX().toLong() * 1000 / currentFrame.width
                val y = bounds.centerY().toLong() * 1000 / currentFrame.height
                val action = PhoneAction("tap", mapOf("x" to x.toInt(), "y" to y.toInt()))
                val prior = formula(service)
                val image = evidence.latestImage()
                evidence.beginAction(action, currentFrame)
                val result = service.perform(action, currentFrame, setOf(PACKAGE)) {
                    service.controls === controls && controls.snapshot.value.state == RunState.AUTO && !HarnessAccessibilityService.sensitivePageVisible
                }
                val native = evidence.endAction()
                terminal = !service.inputInFlight
                delay(350)
                val after = formula(service)
                expected += digit.toString()
                val formulaMatched = after.first == expected
                // The next real screenshot is both the post-action probe and the next action frame.
                currentFrame.png.fill(0)
                frame = null
                evidence.resetImage()
                frame = service.observe().also { captures++ }
                val nextSignature = formulaSignature(frame, after.second)
                val rasterChanged = !MessageDigest.isEqual(signature, nextSignature)
                signature?.fill(0); signature = nextSignature
                steps.add(image + native + mapOf(
                    "control_digit" to digit, "source" to "INDEPENDENT_ZERO_NETWORK_CONTROL",
                    "normalized_point_inside_key" to bounds.contains((x * currentFrame.width / 1000).toInt(), (y * currentFrame.height / 1000).toInt()),
                    "native_completed" to (result.status == "completed"), "native_terminal_confirmed" to terminal,
                    "formula_matches_control_sequence" to formulaMatched,
                    "formula_changed" to (after.first != prior.first), "formula_raster_changed" to rasterChanged,
                    "formula_bounds_unchanged" to (prior.second == after.second),
                    "post_image_source_png_equal" to evidence.latestImage()["image_source_png_equal"],
                    "post_capture_after_native_terminal" to evidence.latestImage()["capture_after_previous_native_terminal"],
                ))
                write()
                check(formulaMatched && rasterChanged && result.status == "completed" && terminal &&
                    image["image_source_png_equal"] == true && native["native_path_matches_normalized_point"] == true &&
                    native["native_callback_path_matches"] == true) { "CALIBRATION_MISMATCH" }
            }
            status = "PASS"; code = "FOUR_CONTROL_KEYS_AND_RASTER_CONFIRMED"
        } catch (failure: Exception) {
            status = if (failure is CancellationException) "CANCELLED" else "FAIL"
            code = failure.message?.takeIf { it in CODES } ?: "CALIBRATION_FAILURE"
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                frame?.png?.fill(0); signature?.fill(0)
                service?.let {
                    if (controls != null && it.controls === controls) {
                        it.setCaptureEnabled(false)
                        it.configureSession(null)
                        terminal = try { withTimeout(5_000) { while (it.inputInFlight) delay(20) }; true }
                            catch (_: TimeoutCancellationException) { false }
                        it.diagnosticObserver = oldObserver
                        evidence.clear()
                        flags?.let { old -> it.serviceInfo = it.serviceInfo.apply { this.flags = old } }
                        val restore = {
                            if (previous != null && it.controls === controls && !it.inputInFlight) {
                                it.controls = previous
                                it.showState(previous.snapshot.value)
                                restored = true
                                terminal = true
                                write()
                            }
                            Unit
                        }
                        if (terminal) restore()
                        else {
                            status = "UNCERTAIN"; code = "INPUT_TERMINAL_UNCONFIRMED"
                            controls.awaitRealTerminal(restore)
                        }
                    }
                }
                write()
            }
        }
    }

    private fun formula(service: HarnessAccessibilityService): Pair<String, Rect> = read(service, "formula") { it.text?.toString().orEmpty().trim() to Rect().also(it::getBoundsInScreen) }
    private fun bounds(service: HarnessAccessibilityService, id: String) = read(service, id) { Rect().also(it::getBoundsInScreen) }
    private fun <T> read(service: HarnessAccessibilityService, id: String, read: (android.view.accessibility.AccessibilityNodeInfo) -> T): T {
        check(service.connected && !HarnessAccessibilityService.sensitivePageVisible && service.controls is CalibrationControls && !service.inputInFlight) { "AUTHORIZATION_UNAVAILABLE" }
        val root = service.rootInActiveWindow ?: error("TARGET_NODE_UNAVAILABLE")
        try {
            check(root.packageName?.toString() == PACKAGE) { "AUTHORIZATION_UNAVAILABLE" }
            val nodes = root.findAccessibilityNodeInfosByViewId("$PACKAGE:id/$id")
            try { return read(nodes.single { it.isVisibleToUser && it.packageName?.toString() == PACKAGE }) }
            finally { nodes.forEach { it.recycle() } }
        } finally { root.recycle() }
    }
    private fun formulaSignature(frame: ScreenFrame, rect: Rect): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size) ?: error("INVALID_PNG")
        try {
            check(rect.left >= 0 && rect.top >= 0 && rect.right <= bitmap.width && rect.bottom <= bitmap.height && !rect.isEmpty) { "INVALID_PNG" }
            val digest = MessageDigest.getInstance("SHA-256")
            // Only sample the formula content, away from the transient insertion cursor.
            for (y in rect.top + 5 until rect.bottom - 5 step 3) for (x in rect.left + 5 until rect.right - 5 step 3) {
                val color = bitmap.getPixel(x, y)
                digest.update((color shr 16).toByte()); digest.update((color shr 8).toByte()); digest.update(color.toByte())
            }
            return digest.digest()
        } finally { bitmap.recycle() }
    }

    private fun write() {
        val payload = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("run_id", runId).put("status", status).put("code", code)
            .put("gui_attempts", 0).put("planner_attempts", 0).put("screenshot_calls", captures)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("controls_restored", restored).put("native_terminal_confirmed", terminal)
            .put("diagnostic_write_failed", probeFailed).put("steps", JSONArray(steps.map { JSONObject(it) }))
        val atomic = AtomicFile(File(noBackupFilesDir, "calculator-calibration-verification.json"))
        var stream: java.io.FileOutputStream? = null
        try { stream = atomic.startWrite(); stream.write(payload.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (_: Exception) { if (stream != null) atomic.failWrite(stream); probeFailed = true }
    }
    private class CalibrationControls(private val phone: HarnessAccessibilityService, private val job: Job) : RuntimeControls {
        private var restore: (() -> Unit)? = null
        private val state = MutableStateFlow(RunSnapshot(state = RunState.AUTO, budget = 4, sessionId = UUID.randomUUID().toString(), message = "计算器独立校准 · 零联网"))
        override val snapshot: StateFlow<RunSnapshot> = state
        override fun start(options: SessionOptions) = stop()
        override fun takeover() = stop()
        override fun giveBack() = stop()
        override fun retry() = stop()
        override fun recheck() { if (!phone.inputInFlight) { val pending = restore; restore = null; pending?.invoke() } }
        fun awaitRealTerminal(action: () -> Unit) {
            restore = action
            state.value = state.value.copy(state = RunState.UNCERTAIN, message = "等待真实输入终态")
            phone.showState(state.value)
            recheck()
        }
        override fun environmentUnavailable(code: String) = stop()
        override fun stop() {
            phone.setCaptureEnabled(false)
            state.value = state.value.copy(state = RunState.STOPPING)
            phone.showState(state.value)
            job.cancel()
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private companion object {
        const val PACKAGE = "com.google.android.calculator"
        val CODES = setOf("SERVICE_DISCONNECTED", "SERVICE_NOT_IDLE", "CALCULATOR_NOT_LAUNCHABLE", "PRECONDITION_FORMULA_NOT_EMPTY",
            "AUTHORIZATION_UNAVAILABLE", "CALIBRATION_MISMATCH", "TARGET_NODE_UNAVAILABLE", "INVALID_PNG")
    }
}
