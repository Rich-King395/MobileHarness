package org.mobileharness.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.WindowManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.mobileharness.app.core.*
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.platform.PlatformDiagnosticObserver
import org.mobileharness.app.verification.ArgbDifferenceAnalyzer
import org.mobileharness.app.verification.ArgbRoundTripProbe
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** DUMP-only, zero-network localization. Pixels, source text and image derivatives never leave RAM. */
class DebugArgbLocalizationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var activeControls: ProbeControls? = null
    private val runId = UUID.randomUUID().toString()
    private val startedAt = SystemClock.elapsedRealtime()
    private val frames = mutableListOf<Map<String, Any?>>()
    private val synthetic = mutableListOf<Map<String, Any?>>()
    private var status = "RUNNING"
    private var code = "PENDING"
    private var screenshotCalls = 0
    private var observeCalls = 0
    private var navigationLaunches = 0
    private var controlsRestored = false
    private var flagsRestored = false
    private var terminalConfirmed = false
    private var captureDisabled = false
    private var scopeCleared = false
    private var observerRestored = false
    private var writeFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        setContentView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 100, 40, 50)
            addView(android.widget.TextView(this@DebugArgbLocalizationActivity).apply {
                text = "图像差异定位\n零联网，仅内存分析；不点击目标 App，不改变模型坐标。"
                textSize = 18f
            })
            addView(android.widget.Button(this@DebugArgbLocalizationActivity).apply {
                text = "停止诊断"
                setOnClickListener { activeControls?.stop() ?: job?.cancel() }
            })
        })
        write()
        job = scope.launch { runLocalization() }
    }

    private suspend fun runLocalization() {
        val app = application as HarnessApplication
        var service: HarnessAccessibilityService? = null
        var previous: RuntimeControls? = null
        var temporary: ProbeControls? = null
        var previousFlags: Int? = null
        var previousObserver: PlatformDiagnosticObserver? = null
        val probe = ArgbRoundTripProbe()
        try {
            if (writeFailed) fail("DIAGNOSTIC_WRITE_FAILED")
            val phone = HarnessAccessibilityService.instance?.takeIf { it.connected }
                ?: fail("SERVICE_DISCONNECTED")
            service = phone
            val old = phone.controls ?: fail("SERVICE_NOT_IDLE")
            if (old !== app.runtime || old.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED) || phone.inputInFlight) {
                fail("SERVICE_NOT_IDLE")
            }
            if (HarnessAccessibilityService.sensitivePageVisible) fail("AUTHORIZATION_UNAVAILABLE")
            previous = old
            val controls = ProbeControls(phone, currentCoroutineContext().job)
            temporary = controls
            activeControls = controls
            previousFlags = phone.serviceInfo.flags
            previousObserver = phone.diagnosticObserver
            phone.controls = controls
            phone.serviceInfo = phone.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            phone.diagnosticObserver = probe

            val home = resolveHome()
            val calculator = packageManager.getLaunchIntentForPackage(CALCULATOR)
                ?: fail("CALCULATOR_NOT_LAUNCHABLE")
            val calculatorComponent = calculator.resolveActivity(packageManager)
                ?.takeIf { it.packageName == CALCULATOR } ?: fail("CALCULATOR_NOT_LAUNCHABLE")
            calculator.component = calculatorComponent
            calculator.selector = null
            calculator.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val dummy = ModelProfile(baseUrl = "https://unused.invalid/v1", model = "unused")
            phone.configureSession(SessionOptions(
                "Zero-network image round-trip localization", home.component!!.packageName, setOf(CALCULATOR),
                false, 30, dummy, dummy, StartMode.HOME_AUTO, home.component!!.packageName,
                mapOf(CALCULATOR to "Calculator"),
            ))

            // Fixed generated content is analyzed before collecting any phone pixels.
            for ((label, space, alpha) in SYNTHETIC_CASES) {
                requireOwner(phone, controls)
                probe.prepare(listOf(ArgbDifferenceAnalyzer.Region("SYNTHETIC_CONTENT", 0, 0, 128, 128)))
                val report = withContext(Dispatchers.Default) {
                    val bitmap = makeSynthetic(space, alpha)
                    try { probe.analyzeSynthetic(bitmap) } finally { bitmap.recycle() }
                }
                currentCoroutineContext().ensureActive()
                validateReport(report)
                synthetic.add(mapOf("case_label" to label, "generated_content" to true) + report)
                writeChecked()
            }

            val targets = listOf("HOME" to home, "CALCULATOR" to calculator)
            for ((label, intent) in targets) {
                requireOwner(phone, controls)
                phone.setCaptureEnabled(false)
                startActivity(intent)
                navigationLaunches++
                delay(950)
                val expectedPackage = checkNotNull(intent.component).packageName
                for (ordinal in 1..2) {
                    stablePackage(phone, controls, expectedPackage)
                    val regions = collectRegions(phone, controls, expectedPackage, label == "CALCULATOR")
                    requirePackage(phone, controls, expectedPackage)
                    probe.prepare(regions)
                    phone.setCaptureEnabled(true)
                    observeCalls++
                    val frame = phone.observe().also { screenshotCalls++ }
                    try {
                        requirePackage(phone, controls, expectedPackage)
                        val report = probe.latest()
                        if (report.isEmpty()) fail("DIAGNOSTIC_HOOK_MISSING")
                        validateReport(report)
                        frames.add(mapOf(
                            "case_label" to "${label}_$ordinal",
                            "source_width" to frame.width, "source_height" to frame.height,
                            "region_count" to regions.size,
                            "foreground_package_rechecked" to true,
                        ) + report)
                        // A strict equality mismatch is a finding, never a reason to correct pixels.
                        writeChecked()
                    } finally { frame.png.fill(0) }
                    if (ordinal < 2) delay(350)
                }
            }
            status = "DIAGNOSED"
            code = "FOUR_FRAMES_AND_FOUR_SYNTHETICS_ANALYZED"
        } catch (cancelled: CancellationException) {
            status = "CANCELLED"
            code = "CANCELLED"
        } catch (_: OutOfMemoryError) {
            status = "FAIL"
            code = "DIAGNOSTIC_MEMORY_LIMIT"
        } catch (failure: Exception) {
            status = "FAIL"
            code = safeCode(failure)
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                val phone = service
                val controls = temporary
                if (phone != null && controls != null && phone.controls === controls) {
                    phone.setCaptureEnabled(false)
                    captureDisabled = !phone.captureEnabled
                    phone.configureSession(null)
                    scopeCleared = true
                    terminalConfirmed = try {
                        withTimeout(5_000) { while (phone.inputInFlight) delay(20) }
                        true
                    } catch (_: TimeoutCancellationException) { false }
                    phone.diagnosticObserver = previousObserver
                    observerRestored = phone.diagnosticObserver === previousObserver
                    probe.clear()
                    previousFlags?.let { saved ->
                        try { phone.serviceInfo = phone.serviceInfo.apply { flags = saved }; flagsRestored = true }
                        catch (_: Exception) { status = "FAIL"; code = "SERVICE_FLAGS_RESTORE_FAILED" }
                    }
                    val restore = {
                        if (previous != null && phone.controls === controls && !phone.inputInFlight &&
                            HarnessAccessibilityService.instance === phone) {
                            phone.controls = previous
                            phone.showState(previous.snapshot.value)
                            controlsRestored = true
                            terminalConfirmed = true
                            write()
                        }
                        Unit
                    }
                    if (terminalConfirmed) restore()
                    else {
                        status = "UNCERTAIN"; code = "INPUT_TERMINAL_UNCONFIRMED"
                        controls.awaitRealTerminal(restore)
                    }
                    if (status == "DIAGNOSED" && !(controlsRestored && flagsRestored && observerRestored && captureDisabled && scopeCleared)) {
                        status = "FAIL"; code = "FINAL_STATE_RESTORE_FAILED"
                    }
                }
                if (activeControls === controls) activeControls = null
                write()
            }
        }
    }

    private fun resolveHome(): Intent {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?.takeIf { it.enabled && it.applicationInfo.enabled && it.exported && it.packageName != "android" && !it.name.contains("ResolverActivity") }
            ?: fail("HOME_UNAVAILABLE")
        intent.component = ComponentName(resolved.packageName, resolved.name)
        intent.selector = null
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun requireOwner(phone: HarnessAccessibilityService, controls: ProbeControls) {
        if (controls.stopped.get() || !controls.ownerActive || !phone.connected ||
            HarnessAccessibilityService.instance !== phone || phone.controls !== controls ||
            controls.snapshot.value.state != RunState.AUTO || phone.inputInFlight ||
            HarnessAccessibilityService.sensitivePageVisible || !phone.readiness.value.unlocked) {
            fail("AUTHORIZATION_UNAVAILABLE")
        }
    }

    private fun requirePackage(phone: HarnessAccessibilityService, controls: ProbeControls, expected: String) {
        requireOwner(phone, controls)
        val root = phone.rootInActiveWindow ?: fail("FOREGROUND_PACKAGE_UNAVAILABLE")
        try { if (root.packageName?.toString() != expected) fail("AUTHORIZATION_UNAVAILABLE") }
        finally { root.recycle() }
    }

    private suspend fun stablePackage(phone: HarnessAccessibilityService, controls: ProbeControls, expected: String) {
        repeat(3) {
            currentCoroutineContext().ensureActive()
            requirePackage(phone, controls, expected)
            delay(80)
        }
        requirePackage(phone, controls, expected)
    }

    private fun collectRegions(phone: HarnessAccessibilityService, controls: ProbeControls, expected: String, calculator: Boolean): List<ArgbDifferenceAnalyzer.Region> {
        requirePackage(phone, controls, expected)
        val geometry = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val width = geometry.width(); val height = geometry.height()
        if (width <= 0 || height <= 0) fail("INVALID_GEOMETRY")
        fun barHeight(name: String): Int {
            val id = resources.getIdentifier(name, "dimen", "android")
            return if (id == 0) 0 else resources.getDimensionPixelSize(id).coerceIn(0, height / 3)
        }
        val statusHeight = barHeight("status_bar_height")
        val navHeight = barHeight("navigation_bar_height")
        val regions = mutableListOf<ArgbDifferenceAnalyzer.Region>()
        if (statusHeight > 0) regions.add(ArgbDifferenceAnalyzer.Region("SYSTEM_STATUS", 0, 0, width, statusHeight))
        if (navHeight > 0) regions.add(ArgbDifferenceAnalyzer.Region("SYSTEM_NAV", 0, height - navHeight, width, height))
        if (height - navHeight > statusHeight) regions.add(ArgbDifferenceAnalyzer.Region("APP_CONTENT", 0, statusHeight, width, height - navHeight))
        if (!calculator) return regions
        val root = phone.rootInActiveWindow ?: fail("FOREGROUND_PACKAGE_UNAVAILABLE")
        try {
            if (root.packageName?.toString() != expected) fail("AUTHORIZATION_UNAVAILABLE")
            for ((id, name) in CALCULATOR_REGIONS) {
                requireOwner(phone, controls)
                val nodes = root.findAccessibilityNodeInfosByViewId("$CALCULATOR:id/$id")
                try {
                    val node = nodes.singleOrNull { it.refresh() && it.isVisibleToUser && it.packageName?.toString() == expected }
                    if (node == null) {
                        if (id == "formula" || id.startsWith("digit_") || id in REQUIRED_OPERATORS) fail("CALCULATOR_REGIONS_UNAVAILABLE")
                        continue
                    }
                    val rect = Rect().also(node::getBoundsInScreen)
                    if (rect.isEmpty || rect.left < 0 || rect.top < 0 || rect.right > width || rect.bottom > height) fail("INVALID_GEOMETRY")
                    regions.add(ArgbDifferenceAnalyzer.Region(name, rect.left, rect.top, rect.right, rect.bottom))
                    if (name.startsWith("KEY_")) {
                        rect.inset(rect.width() / 4, rect.height() / 4)
                        if (!rect.isEmpty) regions.add(ArgbDifferenceAnalyzer.Region("${name}_CENTER", rect.left, rect.top, rect.right, rect.bottom))
                    }
                } finally { nodes.forEach { it.recycle() } }
            }
        } finally { root.recycle() }
        return regions
    }

    private fun makeSynthetic(space: ColorSpace.Named, alpha: Boolean): Bitmap {
        val width = 128; val height = 128
        val colors = IntArray(width * height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, ColorSpace.get(space))
        try {
            for (y in 0 until height) for (x in 0 until width) {
                val a = if (alpha) (x * 17 + y * 29) and 255 else 255
                val r = x * 255 / (width - 1)
                val g = y * 255 / (height - 1)
                val b = (x * 7 + y * 11) and 255
                colors[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(colors, 0, width, 0, 0, width, height)
            return bitmap
        } catch (failure: Exception) { bitmap.recycle(); throw failure
        } finally { colors.fill(0) }
    }

    private fun validateReport(report: Map<String, Any?>) {
        if (report["status"] != "ANALYZED") fail("DIAGNOSTIC_HOOK_MISSING")
        if ((report["regions_dropped_names"] as? List<*>)?.isEmpty() != true) fail("REGION_MAPPING_DROPPED")
        if (report["source_generation_unchanged"] != true || report["original_png_bytes_unchanged"] != true) fail("DIAGNOSTIC_INPUT_MUTATED")
    }

    private fun writeChecked() { write(); if (writeFailed) fail("DIAGNOSTIC_WRITE_FAILED") }
    private fun write() {
        val value = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("run_id", runId).put("status", status).put("code", code)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt)
            .put("gui_attempts", 0).put("planner_attempts", 0).put("native_action_calls", 0)
            .put("observe_calls", observeCalls).put("screenshot_calls", screenshotCalls)
            .put("screenshot_calls_count_successful_frames_only", true).put("app_navigation_launches", navigationLaunches)
            .put("controls_restored", controlsRestored).put("service_flags_restored", flagsRestored)
            .put("native_terminal_confirmed", terminalConfirmed).put("capture_disabled", captureDisabled)
            .put("session_scope_cleared", scopeCleared).put("observer_restored", observerRestored)
            .put("strict_mismatch_observational_only", true).put("diagnostic_write_failed", writeFailed)
            .put("frames", JSONArray(frames.map { JSONObject(it) }))
            .put("synthetic", JSONArray(synthetic.map { JSONObject(it) }))
        val file = AtomicFile(File(noBackupFilesDir, "argb-localization-verification.json"))
        var stream: java.io.FileOutputStream? = null
        try { stream = file.startWrite(); stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream)
        } catch (_: Exception) { if (stream != null) file.failWrite(stream); writeFailed = true }
    }

    private class ProbeControls(private val phone: HarnessAccessibilityService, private val owner: Job) : RuntimeControls {
        private val state = MutableStateFlow(RunSnapshot(state = RunState.AUTO, budget = 30,
            sessionId = UUID.randomUUID().toString(), message = "图像差异定位 · 零联网"))
        override val snapshot: StateFlow<RunSnapshot> = state
        val stopped = AtomicBoolean(false)
        val ownerActive: Boolean get() = owner.isActive
        private var restore: (() -> Unit)? = null
        override fun start(options: SessionOptions) = stop()
        override fun takeover() = stop()
        override fun giveBack() = stop()
        override fun retry() = stop()
        override fun environmentUnavailable(code: String) = stop()
        override fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            phone.setCaptureEnabled(false)
            state.value = state.value.copy(state = RunState.STOPPING, message = "停止图像诊断")
            phone.showState(state.value)
            owner.cancel()
        }
        override fun recheck() { if (!phone.inputInFlight) { val action = restore; restore = null; action?.invoke() } }
        fun awaitRealTerminal(action: () -> Unit) {
            restore = action
            state.value = state.value.copy(state = RunState.UNCERTAIN, message = "等待真实输入终态")
            phone.showState(state.value)
            recheck()
        }
    }

    override fun onDestroy() { activeControls?.stop(); scope.cancel(); super.onDestroy() }
    private class Failure(val safe: String) : Exception(safe)
    private companion object {
        const val CALCULATOR = "com.google.android.calculator"
        val REQUIRED_OPERATORS = setOf("op_add", "op_sub", "op_mul", "op_div", "eq")
        val CALCULATOR_REGIONS = linkedMapOf("formula" to "FORMULA", "result_preview" to "RESULT_PREVIEW").apply {
            for (digit in 0..9) put("digit_$digit", "KEY_D$digit")
            putAll(mapOf("op_add" to "KEY_ADD", "op_sub" to "KEY_SUB", "op_mul" to "KEY_MUL", "op_div" to "KEY_DIV",
                "eq" to "KEY_EQUAL", "clr" to "KEY_CLEAR", "del" to "KEY_DELETE", "dec_point" to "KEY_DECIMAL",
                "parens" to "KEY_PARENS", "op_pct" to "KEY_PERCENT"))
        }
        val SYNTHETIC_CASES = listOf(
            Triple("SRGB_OPAQUE", ColorSpace.Named.SRGB, false), Triple("DISPLAY_P3_OPAQUE", ColorSpace.Named.DISPLAY_P3, false),
            Triple("SRGB_ALPHA", ColorSpace.Named.SRGB, true), Triple("DISPLAY_P3_ALPHA", ColorSpace.Named.DISPLAY_P3, true),
        )
        val SAFE_CODES = setOf("DIAGNOSTIC_WRITE_FAILED", "SERVICE_DISCONNECTED", "SERVICE_NOT_IDLE", "AUTHORIZATION_UNAVAILABLE",
            "CALCULATOR_NOT_LAUNCHABLE", "HOME_UNAVAILABLE", "FOREGROUND_PACKAGE_UNAVAILABLE", "CALCULATOR_REGIONS_UNAVAILABLE",
            "INVALID_GEOMETRY", "DIAGNOSTIC_HOOK_MISSING", "REGION_MAPPING_DROPPED", "DIAGNOSTIC_INPUT_MUTATED",
            "SCREENSHOT_FAILED", "INPUT_STATUS_UNKNOWN")
        fun fail(code: String): Nothing = throw Failure(code)
        fun safeCode(failure: Exception): String = (when (failure) { is Failure -> failure.safe; is RunFailure -> failure.code; else -> "DIAGNOSTIC_FAILURE" })
            .takeIf { it in SAFE_CODES } ?: "DIAGNOSTIC_FAILURE"
    }
}
