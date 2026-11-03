package org.mobileharness.app.verification

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.Choreographer
import android.view.Display
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mobileharness.app.core.RunSnapshot
import org.mobileharness.app.core.RunState
import org.mobileharness.app.core.RuntimeControls
import org.mobileharness.app.core.PhoneAction
import org.mobileharness.app.core.ScreenFrame
import org.mobileharness.app.core.SessionOptions
import org.mobileharness.app.core.ModelProfile
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.platform.OverlayControlPanel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

/**
 * Debug-only, zero-model compositor check. Three synthetic-fixture frames exist only in RAM:
 * visible expanded control, manually hidden reference, then the production observe() result.
 * Neither pictures, pixel digests, coordinates, nor fixture contents leave this class.
 */
class OverlayCaptureVerification(context: Context) {
    private val context = context.applicationContext

    data class Result(
        val status: String = "RUNNING",
        val code: String = "PENDING",
        val installedBeforeTest: Boolean = false,
        val availableBeforeTest: Boolean = false,
        val syntheticForeground: Boolean = false,
        val expandedBeforeCapture: Boolean = false,
        val visibleReferenceDiffers: Boolean = false,
        val productionMatchesHiddenReference: Boolean = false,
        val installedAfterCapture: Boolean = false,
        val availableAfterCapture: Boolean = false,
        val restoredCompact: Boolean = false,
        val phonePerformCalls: Int = 0,
        val targetInitiallyConflicts: Boolean = false,
        val relocationCompletes: Boolean = false,
        val targetClearAfterRelocation: Boolean = false,
        val actionUnchanged: Boolean = false,
        val sourceFrameIdentityUnchanged: Boolean = false,
        val nativeTerminalConfirmed: Boolean = false,
        val controlsRestored: Boolean = false,
    )

    @Volatile var latest = Result()
        private set

    suspend fun run(report: (Result) -> Unit) {
        if (!RUNNING.compareAndSet(false, true)) {
            latest = Result(status = "BLOCKED", code = "VERIFICATION_ALREADY_RUNNING")
            report(latest)
            return
        }
        var service: HarnessAccessibilityService? = null
        var oldControls: RuntimeControls? = null
        var temporary: LocalControls? = null
        var shown: Bitmap? = null
        var hidden: Bitmap? = null
        var production: Bitmap? = null
        var currentFrame: ScreenFrame? = null
        var failureCode: String? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                val live = HarnessAccessibilityService.instance ?: fail("SERVICE_UNAVAILABLE")
                val old = live.controls ?: fail("CONTROLS_UNAVAILABLE")
                if (!live.connected || live.inputInFlight || old.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED)) {
                    fail("SERVICE_NOT_IDLE_OR_CONNECTED")
                }
                service = live
                oldControls = old
                val initialPanel = overlay(live)
                latest = latest.copy(installedBeforeTest = initialPanel.installed, availableBeforeTest = initialPanel.available)
                live.setCaptureEnabled(false)
                live.configureSession(SessionOptions("Synthetic overlay verification", FIXTURE, setOf(FIXTURE),
                    false, 1, ModelProfile(model = "synthetic"), ModelProfile(model = "unused")))
                val local = LocalControls(live)
                temporary = local
                live.controls = local
                live.showState(local.snapshot.value)
                context.startActivity(Intent().setClassName(FIXTURE, "$FIXTURE.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            // Planned settling and screenshot pacing; failures are never retried.
            delay(1_000)
            val live = checkNotNull(service)
            val local = checkNotNull(temporary)
            val panel = withContext(Dispatchers.Main.immediate) {
                requireFixture(live, local)
                if (HarnessAccessibilityService.sensitivePageVisible) fail("SENSITIVE_PAGE_VISIBLE")
                latest = latest.copy(syntheticForeground = true)
                overlay(live).also {
                    // This diagnostic reset deliberately hides the control without a production
                    // capture ticket. Keep it IDLE so the real entry-loss guard stays enabled.
                    local.beginReference()
                    it.setCaptureHidden(true)
                    it.setCaptureHidden(false)
                    local.endReference()
                    live.showState(local.snapshot.value)
                }
            }
            settle()
            val compact = withContext(Dispatchers.Main.immediate) {
                panel.bounds()?.let(::Rect) ?: fail("OVERLAY_NOT_VISIBLE")
            }
            withContext(Dispatchers.Main.immediate) { expand(panel) }
            settle()
            val region = withContext(Dispatchers.Main.immediate) {
                requireFixture(live, local)
                val expanded = panel.bounds()?.let(::Rect) ?: fail("OVERLAY_NOT_VISIBLE")
                val isExpanded = expanded.width() > compact.width() || expanded.height() > compact.height()
                latest = latest.copy(expandedBeforeCapture = isExpanded)
                if (!isExpanded) fail("OVERLAY_DID_NOT_EXPAND")
                expanded
            }
            shown = rawFixtureFrame(live, local)
            delay(SCREENSHOT_INTERVAL_MS)
            withContext(Dispatchers.Main.immediate) {
                requireFixture(live, local)
                // The reference is a diagnostic-only IDLE interval, never human ownership.
                // It avoids treating this intentional raw-reference hide as loss of controls.
                local.beginReference()
                panel.setCaptureHidden(true)
            }
            settle()
            hidden = rawFixtureFrame(live, local, reference = true)
            withContext(Dispatchers.Main.immediate) {
                panel.setCaptureHidden(false)
                local.endReference()
                live.showState(local.snapshot.value)
            }
            settle()
            withContext(Dispatchers.Main.immediate) { expand(panel) }
            settle()
            delay(SCREENSHOT_INTERVAL_MS)
            production = withContext(Dispatchers.Main.immediate) {
                requireFixture(live, local)
                live.setCaptureEnabled(true)
                val frame = live.observe()
                currentFrame = frame
                BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size) ?: fail("PNG_DECODE_FAILED")
            }
            settle()
            val a = checkNotNull(shown)
            val b = checkNotNull(hidden)
            val c = checkNotNull(production)
            if (a.width != b.width || a.height != b.height || b.width != c.width || b.height != c.height) {
                fail("CAPTURE_GEOMETRY_CHANGED")
            }
            // Inspect inside the visible expanded window only. System bars and outside shadows
            // cannot manufacture a successful difference or a false hidden-reference mismatch.
            region.inset(3, 3)
            if (!region.intersect(0, 0, a.width, a.height) || region.isEmpty) fail("EMPTY_COMPARISON_REGION")
            val ab = compare(a, b, region)
            val bc = compare(b, c, region)
            latest = latest.copy(
                visibleReferenceDiffers = ab.changed * 20L >= ab.samples,
                productionMatchesHiddenReference = bc.changed * 1_000L <= bc.samples,
            )
            withContext(Dispatchers.Main.immediate) {
                requireFixture(live, local)
                val restored = panel.bounds()
                latest = latest.copy(
                    installedAfterCapture = panel.installed,
                    availableAfterCapture = panel.available,
                    restoredCompact = restored != null && restored.width() == compact.width() && restored.height() == compact.height(),
                )
            }
            if (!latest.visibleReferenceDiffers) fail("VISIBLE_BASELINE_NOT_ESTABLISHED")
            if (!latest.productionMatchesHiddenReference) fail("OVERLAY_PIXELS_PRESENT_OR_FRAME_CHANGED")
            if (!latest.installedAfterCapture || !latest.availableAfterCapture || !latest.restoredCompact) {
                fail("OVERLAY_NOT_RESTORED_COMPACT")
            }
            // Exercise the actual PhonePort overlay-avoidance integration once. The target is
            // beneath our own compact window on the synthetic page. No business result is claimed.
            withContext(Dispatchers.Main.immediate) {
                requireFixture(live, local)
                val bounds = panel.bounds() ?: fail("OVERLAY_NOT_VISIBLE")
                val frame = checkNotNull(currentFrame)
                val x = (bounds.centerX().toLong() * 1_000 / frame.width).toInt().coerceIn(0, 999)
                val y = (bounds.centerY().toLong() * 1_000 / frame.height).toInt().coerceIn(0, 999)
                val pixelX = x * frame.width / 1_000
                val pixelY = y * frame.height / 1_000
                val action = PhoneAction("tap", mapOf("x" to x, "y" to y))
                val originalArguments = action.arguments.toMap()
                val originalId = frame.id
                val originalWidth = frame.width
                val originalHeight = frame.height
                val originalPng = frame.png
                latest = latest.copy(targetInitiallyConflicts = panel.conflictsWithGesture(pixelX, pixelY, pixelX, pixelY))
                if (!latest.targetInitiallyConflicts) fail("TARGET_NOT_UNDER_OVERLAY")
                latest = latest.copy(phonePerformCalls = 1)
                val result = live.perform(action, frame, setOf(FIXTURE)) {
                    live.controls === local && local.snapshot.value.state == RunState.AUTO
                }
                latest = latest.copy(
                    relocationCompletes = result.status == "completed",
                    targetClearAfterRelocation = !panel.conflictsWithGesture(pixelX, pixelY, pixelX, pixelY),
                    actionUnchanged = action.name == "tap" && action.arguments == originalArguments,
                    sourceFrameIdentityUnchanged = frame.id == originalId && frame.width == originalWidth &&
                        frame.height == originalHeight && frame.png === originalPng,
                    nativeTerminalConfirmed = !live.inputInFlight,
                )
                if (!latest.relocationCompletes || !latest.targetClearAfterRelocation || !latest.actionUnchanged ||
                    !latest.sourceFrameIdentityUnchanged || !latest.nativeTerminalConfirmed) fail("OVERLAY_RELOCATION_FAILED")
            }
        } catch (cancelled: CancellationException) {
            failureCode = "CANCELLED"
            throw cancelled
        } catch (failure: VerificationFailure) {
            failureCode = failure.code
        } catch (_: Exception) {
            failureCode = "VERIFICATION_FAILURE"
        } finally {
            shown?.recycle()
            hidden?.recycle()
            production?.recycle()
            currentFrame?.png?.fill(0)
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                service?.let { live ->
                    live.setCaptureEnabled(false)
                    live.configureSession(null)
                    temporary?.end()
                    // A cancelled coroutine is not a native gesture terminal. Do not mask a
                    // still-running input by replacing its controls or displaying a fake idle.
                    if (live.inputInFlight) {
                        try { withTimeout(5_000) { while (live.inputInFlight) delay(25) } }
                        catch (_: Exception) { temporary?.uncertain() }
                    }
                    if (!live.inputInFlight && live.controls === temporary && oldControls != null) {
                        try { overlay(live).setCaptureHidden(false) } catch (_: Exception) { }
                        live.controls = oldControls
                        live.showState(checkNotNull(oldControls).snapshot.value)
                        latest = latest.copy(controlsRestored = live.controls === oldControls)
                    }
                }
            }
            latest = latest.copy(
                status = if (failureCode == null && latest.controlsRestored) "PASS" else if (failureCode == "CANCELLED") "CANCELLED" else "FAIL",
                code = failureCode ?: if (latest.controlsRestored) "OK" else "CONTROLS_NOT_RESTORED",
            )
            RUNNING.set(false)
            report(latest)
        }
    }

    /** Public AccessibilityService capture, with an explicit fixture guard before invocation. */
    private suspend fun rawFixtureFrame(service: HarnessAccessibilityService, local: LocalControls, reference: Boolean = false): Bitmap =
        withContext(Dispatchers.Main.immediate) {
            requireFixture(service, local, reference)
            withTimeout(5_000) {
                suspendCancellableCoroutine { continuation ->
                    service.takeScreenshot(Display.DEFAULT_DISPLAY, Dispatchers.Default.asExecutor(), object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val buffer = screenshot.hardwareBuffer
                            var wrapped: Bitmap? = null
                            var pixels: Bitmap? = null
                            try {
                                wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                    ?: fail("RAW_CAPTURE_FAILED")
                                pixels = wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: fail("RAW_CAPTURE_FAILED")
                                if (!continuation.isActive) return
                                val owned = checkNotNull(pixels)
                                continuation.resume(owned) { _, value, _ -> value.recycle() }
                                pixels = null
                            } catch (_: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(VerificationFailure("RAW_CAPTURE_FAILED"))
                            } finally { pixels?.recycle(); wrapped?.recycle(); buffer.close() }
                        }
                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) continuation.resumeWithException(VerificationFailure("RAW_CAPTURE_FAILED"))
                        }
                    })
                }
            }
        }

    private fun requireFixture(service: HarnessAccessibilityService, local: LocalControls, reference: Boolean = false) {
        val expectedState = if (reference) RunState.IDLE else RunState.AUTO
        if (!service.connected || service.inputInFlight || service.controls !== local || local.snapshot.value.state != expectedState) {
            fail("VERIFICATION_STOPPED_OR_SERVICE_CHANGED")
        }
        val root = service.rootInActiveWindow ?: fail("SYNTHETIC_FIXTURE_NOT_FOREGROUND")
        try { if (root.packageName?.toString() != FIXTURE) fail("SYNTHETIC_FIXTURE_NOT_FOREGROUND") }
        finally { root.recycle() }
    }

    // Test-only reflection avoids adding diagnostic accessors to the production service/panel.
    private fun overlay(service: HarnessAccessibilityService): OverlayControlPanel =
        HarnessAccessibilityService::class.java.getDeclaredField("overlay").let {
            it.isAccessible = true
            it.get(service) as? OverlayControlPanel ?: fail("OVERLAY_UNAVAILABLE")
        }

    private fun expand(panel: OverlayControlPanel) {
        val button = OverlayControlPanel::class.java.getDeclaredField("ball").let {
            it.isAccessible = true
            it.get(panel) as? View ?: fail("OVERLAY_EXPAND_UNAVAILABLE")
        }
        if (!button.performClick()) fail("OVERLAY_EXPAND_UNAVAILABLE")
    }

    private data class Comparison(val changed: Long, val samples: Long)
    private fun compare(first: Bitmap, second: Bitmap, region: Rect): Comparison {
        var changed = 0L
        var samples = 0L
        for (y in region.top until region.bottom step 2) for (x in region.left until region.right step 2) {
            val a = first.getPixel(x, y)
            val b = second.getPixel(x, y)
            val delta = max(abs((a shr 16 and 255) - (b shr 16 and 255)),
                max(abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255))))
            if (delta > 4) changed++
            samples++
        }
        return Comparison(changed, samples)
    }

    private suspend fun settle() = withContext(Dispatchers.Main.immediate) {
        repeat(3) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val choreographer = Choreographer.getInstance()
                val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
                choreographer.postFrameCallback(callback)
                continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { choreographer.removeFrameCallback(callback) } }
            }
        }
    }

    private class LocalControls(private val service: HarnessAccessibilityService) : RuntimeControls {
        override val snapshot = MutableStateFlow(RunSnapshot(state = RunState.AUTO, message = "本地截图验证", sessionId = "debug-overlay-capture"))
        override fun start(options: SessionOptions) = Unit
        override fun takeover() = stop()
        override fun giveBack() = Unit
        override fun stop() { end(); service.showState(snapshot.value) }
        override fun retry() = Unit
        override fun recheck() { if (!service.inputInFlight) stop() }
        override fun environmentUnavailable(code: String) = stop()
        fun beginReference() { snapshot.value = snapshot.value.copy(state = RunState.IDLE) }
        fun endReference() {
            if (snapshot.value.state != RunState.IDLE) throw VerificationFailure("VERIFICATION_STOPPED_OR_SERVICE_CHANGED")
            snapshot.value = snapshot.value.copy(state = RunState.AUTO)
        }
        fun end() {
            snapshot.value = snapshot.value.copy(state = if (service.inputInFlight) RunState.STOPPING else RunState.ENDED)
            service.setCaptureEnabled(false)
            service.showState(snapshot.value)
        }
        fun uncertain() {
            snapshot.value = snapshot.value.copy(state = RunState.UNCERTAIN)
            service.showState(snapshot.value)
        }
    }

    private class VerificationFailure(val code: String) : Exception()
    private fun fail(code: String): Nothing = throw VerificationFailure(code)

    companion object {
        private const val FIXTURE = "org.mobileharness.fixture"
        private const val SCREENSHOT_INTERVAL_MS = 650L
        private val RUNNING = AtomicBoolean(false)
    }
}
