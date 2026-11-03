package org.mobileharness.app.platform

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mobileharness.app.HarnessApplication
import org.mobileharness.app.MainActivity
import org.mobileharness.app.BuildConfig
import org.mobileharness.app.R
import org.mobileharness.app.core.ActionResult
import org.mobileharness.app.core.ExecutionFailureReason
import org.mobileharness.app.core.Phase
import org.mobileharness.app.core.PhoneAction
import org.mobileharness.app.core.PhonePort
import org.mobileharness.app.core.RunFailure
import org.mobileharness.app.core.RunSnapshot
import org.mobileharness.app.core.RunState
import org.mobileharness.app.core.RuntimeControls
import org.mobileharness.app.core.ScreenFrame
import org.mobileharness.app.core.SessionOptions
import org.mobileharness.app.core.StartMode
import org.mobileharness.app.ui.AppLanguage
import org.mobileharness.app.ui.AppStrings
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.floor

data class PlatformReadiness(
    val connected: Boolean = false,
    val unlocked: Boolean = false,
    val overlayStopAvailable: Boolean = false,
    val notificationControlAvailable: Boolean = false,
    val sensitivePageVisible: Boolean = false,
    val competingServiceEnabled: Boolean = false,
)

/** All pixels and action data stay in process memory. No event contents or screenshots are logged. */
class HarnessAccessibilityService : AccessibilityService(), PhonePort {
    /** Unset in normal use; probes are read-only and disabled in release builds. */
    @Volatile internal var diagnosticObserver: PlatformDiagnosticObserver? = null
    companion object {
        @Volatile var instance: HarnessAccessibilityService? = null
            private set

        @Volatile var sensitivePageVisible: Boolean = false
            set(value) {
                field = value
                instance?.sensitivePageChanged()
            }

        private const val CHANNEL = "harness_controls"
        private const val NOTIFICATION_ID = 1701
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureGate = AtomicBoolean(false)
    private val captureGeneration = AtomicLong(0)
    private val captureOrdinal = AtomicLong(0)
    private val nativeInput = AtomicBoolean(false)
    private val inputOrdinal = AtomicLong(0)
    private val activeInput = AtomicLong(0)
    private val mutableReadiness = MutableStateFlow(PlatformReadiness())
    val readiness: StateFlow<PlatformReadiness> = mutableReadiness.asStateFlow()
    @Volatile private var serviceConnected = false
    @Volatile private var captureOperation: Long? = null
    @Volatile private var observationBusy = false
    @Volatile private var lastFrame: FrameStamp? = null
    @Volatile private var sessionScope: SessionScope? = null
    @Volatile private var pendingLaunch: LaunchObservation? = null
    private var overlay: OverlayControlPanel? = null
    private var displayed = RunSnapshot()
    private var stateToken = UUID.randomUUID().toString()
    private var consumedReturnToken: String? = null
    private val notificationTokens = mutableListOf<PendingIntent>()
    private var entryLossStopRequested = false
    private var channelConflictStopRequested = false
    private val controlMonitor = object : Runnable {
        override fun run() {
            if (!connected) return
            // Permission/channel changes have no content-bearing accessibility event dependency.
            refreshReadiness()
            mainHandler.postDelayed(this, 500)
        }
    }
    private var deviceReceiverRegistered = false
    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Device lock state only; this receiver never reads user interaction events.
            if (intent.action == Intent.ACTION_SCREEN_OFF || !deviceUnlocked()) {
                setCaptureEnabled(false)
                val state = controls?.snapshot?.value?.state
                if (state in setOf(RunState.AUTO, RunState.PREPARING, RunState.CHECKING)) {
                    controls?.environmentUnavailable("AUTHORIZATION_UNAVAILABLE")
                }
            }
            refreshReadiness()
        }
    }

    @Volatile var controls: RuntimeControls? = null
        set(value) {
            field = value
            onMain { showState(value?.snapshot?.value ?: RunSnapshot()) }
        }

    override val connected: Boolean get() = serviceConnected && instance === this
    override val inputInFlight: Boolean get() = nativeInput.get()
    val captureEnabled: Boolean get() = captureGate.get()
    val overlayStopAvailable: Boolean get() = overlay?.available == true && controls != null
    val notificationControlAvailable: Boolean get() = notificationsAllowed() && controls != null
    val competingServiceEnabled: Boolean get() = try {
        // Enabled component metadata only. Never inspect the companion process, windows or data.
        getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service ->
                val component = service.resolveInfo.serviceInfo
                AppChannels.isCompanionService(packageName, component.packageName, component.name)
            }
    } catch (_: Exception) { true } // Fail closed if exclusivity cannot be verified.
    val platformReady: Boolean get() = connected && deviceUnlocked() && !sensitivePageVisible &&
        overlayStopAvailable && !competingServiceEnabled

    /** Pre-session launch handshake only. No text, pixels, or window history is retained. */
    fun isInitialAppForeground(expectedPackage: String): Boolean {
        if (!connected || sensitivePageVisible || !deviceUnlocked() || competingServiceEnabled ||
            AppChannels.isHarnessPackage(expectedPackage)) return false
        val root = rootInActiveWindow ?: return false
        return try { root.packageName?.toString() == expectedPackage } finally { root.recycle() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceConnected = true
        configureSession(null)
        overlay = OverlayControlPanel(this, { handlePanelCommand(it) }, { refreshReadiness() }).also { it.attach() }
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(deviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(deviceReceiver, filter)
        deviceReceiverRegistered = true
        (application as HarnessApplication).attachPhone(this)
        showState(controls?.snapshot?.value ?: RunSnapshot())
        mainHandler.post(controlMonitor)
    }

    // Deliberately do not inspect event text, source, package, windows, or event type.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLanguage()
    }

    /** Update fixed labels in place; this never rebinds the service or resets a session. */
    fun refreshLanguage() = onMain {
        if (!serviceConnected) return@onMain
        overlay?.refreshLanguage()
        createNotificationChannel()
        controls?.snapshot?.value?.let(::publishNotification)
    }

    override fun onInterrupt() {
        setCaptureEnabled(false)
        controls?.environmentUnavailable("AUTHORIZATION_UNAVAILABLE")
    }

    override fun onDestroy() {
        configureSession(null)
        serviceConnected = false
        mainHandler.removeCallbacks(controlMonitor)
        if (deviceReceiverRegistered) {
            unregisterReceiver(deviceReceiver)
            deviceReceiverRegistered = false
        }
        overlay?.detach()
        overlay = null
        notificationTokens.forEach { it.cancel() }
        notificationTokens.clear()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        (application as HarnessApplication).detachPhone(this)
        controls = null
        if (instance === this) instance = null
        refreshReadiness()
        // A lost service/coroutine is not proof that a previously dispatched input was cancelled.
        super.onDestroy()
    }

    override fun setCaptureEnabled(enabled: Boolean) {
        if (captureGate.getAndSet(enabled) != enabled) captureGeneration.incrementAndGet()
        if (!enabled) {
            lastFrame = null
            pendingLaunch = null
        }
        onMain { refreshReadiness() }
    }

    override fun configureSession(options: SessionOptions?) {
        // A replaced/cleared scope invalidates old callbacks, including on a retired service.
        setCaptureEnabled(false)
        sessionScope = null
        captureGeneration.incrementAndGet()
        lastFrame = null
        if (options == null) return
        val home = if (options.startMode == StartMode.HOME_AUTO) {
            options.homePackage.takeIf { it.isNotBlank() && it == options.initialPackage && !AppChannels.isHarnessPackage(it) }
                ?: throw deviceFailure("AUTHORIZATION_UNAVAILABLE")
        } else null
        sessionScope = SessionScope(options.authorizedPackages.filterNot(AppChannels::isHarnessPackage).toSet(), home)
    }

    private fun sensitivePageChanged() {
        captureGeneration.incrementAndGet()
        lastFrame = null
        pendingLaunch = null
        refreshReadiness()
    }

    fun refreshReadiness() {
        onMain {
            val readiness = PlatformReadiness(
                connected, deviceUnlocked(), overlayStopAvailable,
                notificationControlAvailable, sensitivePageVisible,
                competingServiceEnabled,
            )
            mutableReadiness.value = readiness
            val state = controls?.snapshot?.value?.state
            if (state == RunState.IDLE || state == RunState.ENDED || !readiness.competingServiceEnabled) {
                channelConflictStopRequested = false
            }
            if (connected && readiness.competingServiceEnabled && !channelConflictStopRequested &&
                state in setOf(RunState.AUTO, RunState.PREPARING, RunState.CHECKING)) {
                channelConflictStopRequested = true
                setCaptureEnabled(false)
                controls?.environmentUnavailable("AUTHORIZATION_UNAVAILABLE")
            }
            if (state == RunState.IDLE || state == RunState.ENDED || readiness.overlayStopAvailable ||
                readiness.notificationControlAvailable) entryLossStopRequested = false
            // Temporarily hiding a still-attached overlay to capture pixels is a closed input
            // interval (captureOperation != null). Actual loss of both entries ends automation,
            // including while an HTTP reply is still pending; it never waits for HTTP cancellation.
            val captureHidingInstalledOverlay = captureOperation != null && overlay?.installed == true
            if (connected && !entryLossStopRequested &&
                state in setOf(RunState.AUTO, RunState.PREPARING, RunState.CHECKING) &&
                !readiness.overlayStopAvailable && !readiness.notificationControlAvailable && !captureHidingInstalledOverlay) {
                entryLossStopRequested = true
                setCaptureEnabled(false)
                controls?.environmentUnavailable("STOP_ENTRY_UNAVAILABLE")
            }
        }
    }

    private data class Geometry(val width: Int, val height: Int, val rotation: Int, val displayId: Int)
    private data class SessionScope(val allowed: Set<String>, val home: String?)
    private data class FrameStamp(val id: String, val generation: Long, val geometry: Geometry, val packageName: String)
    private data class CaptureTicket(val operation: Long, val generation: Long, val geometry: Geometry, val packageName: String)
    private data class LaunchObservation(
        val source: String, val target: String, val generation: Long,
        val scope: SessionScope, val owner: RuntimeControls,
    )

    private fun geometry(): Geometry {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw deviceFailure("SCREENSHOT_FAILED")
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return Geometry(bounds.width(), bounds.height(), display.rotation, display.displayId)
    }

    private fun deviceUnlocked(): Boolean = try {
        val keyguard = getSystemService(KeyguardManager::class.java)
        getSystemService(PowerManager::class.java).isInteractive &&
            !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked
    } catch (_: Exception) { false }

    private fun captureAllowed(): Boolean = connected && captureGate.get() && sessionScope != null &&
        !sensitivePageVisible && deviceUnlocked() && !competingServiceEnabled

    /** Called only at observation boundaries; the capture gate wins before any target access. */
    private fun requireCaptureAllowed() {
        if (!connected || !captureGate.get() || sessionScope == null || sensitivePageVisible || competingServiceEnabled) {
            throw deviceFailure("AUTHORIZATION_UNAVAILABLE", ExecutionFailureReason.CONTROL_NOT_AVAILABLE)
        }
        if (!deviceUnlocked()) throw deviceFailure("AUTHORIZATION_UNAVAILABLE", ExecutionFailureReason.DEVICE_NOT_READY)
    }

    /** Package identity only, at an explicit observation/dispatch boundary; never a manual monitor. */
    private fun observationPackageOrNull(): String? {
        requireCaptureAllowed()
        val scope = sessionScope ?: throw deviceFailure("AUTHORIZATION_UNAVAILABLE")
        val root = rootInActiveWindow ?: return null
        val currentPackage = try { root.packageName?.toString() } finally { root.recycle() }
        if (currentPackage == null) return null
        if (AppChannels.isHarnessPackage(currentPackage) ||
            (currentPackage !in scope.allowed && currentPackage != scope.home)) {
            throw deviceFailure("AUTHORIZATION_UNAVAILABLE", ExecutionFailureReason.INPUT_APP_NOT_AUTHORIZED)
        }
        return currentPackage
    }

    private fun requireObservationPackage(expected: String? = null): String {
        val currentPackage = observationPackageOrNull()
            ?: throw deviceFailure("AUTHORIZATION_UNAVAILABLE", ExecutionFailureReason.SCREENSHOT_UNAVAILABLE)
        if (expected != null && currentPackage != expected) throw deviceFailure("SCREENSHOT_FAILED")
        return currentPackage
    }

    private suspend fun awaitLaunchedForeground(launch: LaunchObservation) {
        try {
            withTimeout(3_000) {
                var stableSamples = 0
                var targetSince = 0L
                while (true) {
                    // Ownership/scope invalidation wins before every package query. This is a
                    // bounded launch handshake, with no pixels, input, or manual monitoring.
                    if (pendingLaunch !== launch || captureGeneration.get() != launch.generation ||
                        sessionScope !== launch.scope || controls !== launch.owner || !captureAllowed() ||
                        inputInFlight || captureOperation != null ||
                        (!overlayStopAvailable && !notificationControlAvailable)) {
                        throw deviceFailure("AUTHORIZATION_UNAVAILABLE")
                    }
                    val current = observationPackageOrNull()
                    if (current == launch.target) {
                        val now = SystemClock.uptimeMillis()
                        if (stableSamples == 0) targetSince = now
                        // Package identity can precede the launch surface animation. Two
                        // frames alone produced a transitional PNG on the Pixel 4. Keep
                        // checking ownership every frame through a bounded settling window;
                        // take only the one requested screenshot after this handshake.
                        if (++stableSamples >= 3 && now - targetSince >= 750) return@withTimeout
                    } else {
                        stableSamples = 0
                        targetSince = 0L
                        if (current != null && current != launch.source) throw deviceFailure("SCREENSHOT_FAILED")
                    }
                    awaitDisplayFrame()
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw deviceFailure("SCREENSHOT_FAILED")
        }
        if (pendingLaunch === launch) pendingLaunch = null
    }

    private fun ticketCurrent(ticket: CaptureTicket): Boolean =
        captureOperation == ticket.operation && captureGeneration.get() == ticket.generation && captureAllowed()

    override suspend fun observe(): ScreenFrame = withContext(Dispatchers.Main.immediate) {
        requireCaptureAllowed()
        if (observationBusy || captureOperation != null || inputInFlight) throw deviceFailure("SCREENSHOT_FAILED")
        observationBusy = true
        try {
            // startActivity returning confirms native dispatch, not that the new window is ready.
            // Wait before freezing the screenshot's package; never retry a screenshot on mismatch.
            pendingLaunch?.let { awaitLaunchedForeground(it) }
            val observedPackage = requireObservationPackage()
            val ticket = CaptureTicket(captureOrdinal.incrementAndGet(), captureGeneration.get(), geometry(), observedPackage)
            captureOperation = ticket.operation
            lastFrame = null
            overlay?.setCaptureHidden(true)
            try {
                withTimeout(5_000) {
                    // Let the hidden overlay be removed from the compositor; never crop or paint over pixels.
                    awaitDisplayFrame()
                    awaitDisplayFrame()
                    if (!ticketCurrent(ticket)) throw deviceFailure("SCREENSHOT_FAILED")
                    requireObservationPackage(ticket.packageName)
                    screenshot(ticket)
                }
            } catch (_: TimeoutCancellationException) {
                throw deviceFailure("SCREENSHOT_FAILED")
            } finally {
                if (captureOperation == ticket.operation) {
                    captureOperation = null
                    overlay?.setCaptureHidden(false)
                    refreshReadiness()
                }
            }
        } finally {
            observationBusy = false
        }
    }

    private suspend fun awaitDisplayFrame() = suspendCancellableCoroutine<Unit> { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback {
            if (continuation.isActive) continuation.resume(Unit)
        }
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { mainHandler.post { choreographer.removeFrameCallback(callback) } }
    }

    private suspend fun screenshot(ticket: CaptureTicket): ScreenFrame = suspendCancellableCoroutine { continuation ->
        try {
            val requestedUptimeMs = SystemClock.uptimeMillis()
            takeScreenshot(Display.DEFAULT_DISPLAY, Dispatchers.Default.asExecutor(), object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    var wrapped: Bitmap? = null
                    var software: Bitmap? = null
                    var encoded: ByteArray? = null
                    try {
                        // The captured buffer already has its pixels. Restore the user's stop entry
                        // before doing PNG conversion; a late callback cannot reveal a newer capture.
                        onMain { if (captureOperation == ticket.operation) overlay?.setCaptureHidden(false) }
                        if (!continuation.isActive || !ticketCurrent(ticket)) return
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?: throw deviceFailure("SCREENSHOT_FAILED")
                        wrapped = hardware
                        val pixels = hardware.copy(Bitmap.Config.ARGB_8888, false)
                            ?: throw deviceFailure("SCREENSHOT_FAILED")
                        software = pixels
                        if (pixels.width != ticket.geometry.width || pixels.height != ticket.geometry.height) {
                            throw deviceFailure("SCREENSHOT_FAILED")
                        }
                        val png = ByteArrayOutputStream().use { output ->
                            if (!pixels.compress(Bitmap.CompressFormat.PNG, 100, output)) throw deviceFailure("SCREENSHOT_FAILED")
                            output.toByteArray()
                        }
                        if (BuildConfig.DEBUG) diagnosticObserver?.let { observer ->
                            runCatching { observer.imageEncoded(pixels, png, screenshot.timestamp, requestedUptimeMs) }
                        }
                        encoded = png
                        if (!continuation.isActive || !ticketCurrent(ticket)) {
                            png.fill(0)
                            return
                        }
                        val width = pixels.width
                        val height = pixels.height
                        // Final package check is on Main. Whole-display capture remains a best-
                        // effort package boundary, not a promise that foreign overlays cannot appear.
                        mainHandler.post {
                            try {
                                if (!continuation.isActive || !ticketCurrent(ticket)) {
                                    png.fill(0)
                                    return@post
                                }
                                if (geometry() != ticket.geometry) throw deviceFailure("SCREENSHOT_FAILED")
                                requireObservationPackage(ticket.packageName)
                                val frame = ScreenFrame(UUID.randomUUID().toString(), png, width, height)
                                lastFrame = FrameStamp(frame.id, ticket.generation, ticket.geometry, ticket.packageName)
                                continuation.resume(frame) { _, value, _ -> value.png.fill(0) }
                            } catch (failure: Exception) {
                                png.fill(0)
                                if (continuation.isActive) continuation.resumeWithException(
                                    (failure as? RunFailure) ?: deviceFailure("SCREENSHOT_FAILED"))
                            }
                        }
                        encoded = null // The posted finalizer now owns the short-lived PNG.
                    } catch (_: Exception) {
                        encoded?.fill(0)
                        if (continuation.isActive) continuation.resumeWithException(deviceFailure("SCREENSHOT_FAILED"))
                    } finally {
                        software?.recycle()
                        wrapped?.recycle()
                        buffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    // Includes screenshot-rate limits: fail explicitly, with no automatic retry.
                    if (continuation.isActive) continuation.resumeWithException(deviceFailure("SCREENSHOT_FAILED"))
                }
            })
        } catch (_: Exception) {
            if (continuation.isActive) continuation.resumeWithException(deviceFailure("SCREENSHOT_FAILED"))
        }
    }

    override suspend fun perform(
        action: PhoneAction,
        frame: ScreenFrame,
        allowedPackages: Set<String>,
        canDispatch: () -> Boolean,
    ): ActionResult = withContext(Dispatchers.Main.immediate) {
        validateArguments(action)
        assertDispatchAllowed(frame, canDispatch)
        val currentPackage = requireObservationPackage()
        val scope = sessionScope ?: throw deviceFailure("AUTHORIZATION_UNAVAILABLE")
        if (currentPackage == scope.home && action.name !in setOf("launch_app", "press_key", "wait", "finish")) {
            throw deviceFailure("INPUT_FAILED")
        }
        val phase = controls?.snapshot?.value?.phase
        if ((phase == Phase.VERIFY || phase == Phase.CONFIRM) && action.name !in setOf("wait", "finish")) {
            throw deviceFailure("INPUT_FAILED")
        }
        when (action.name) {
            "tap" -> {
                assertCurrentPackageAllowed(allowedPackages)
                val x = pixel(action, "x", frame.width)
                val y = pixel(action, "y", frame.height)
                clearOverlayForGesture(x, y, x, y, frame, allowedPackages, canDispatch)
                gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 100, frame, canDispatch)
            }
            "long_press" -> {
                assertCurrentPackageAllowed(allowedPackages)
                val x = pixel(action, "x", frame.width)
                val y = pixel(action, "y", frame.height)
                clearOverlayForGesture(x, y, x, y, frame, allowedPackages, canDispatch)
                gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, integer(action, "duration_ms").toLong(), frame, canDispatch)
            }
            "swipe" -> {
                assertCurrentPackageAllowed(allowedPackages)
                val x1 = pixel(action, "from_x", frame.width)
                val y1 = pixel(action, "from_y", frame.height)
                val x2 = pixel(action, "to_x", frame.width)
                val y2 = pixel(action, "to_y", frame.height)
                clearOverlayForGesture(x1, y1, x2, y2, frame, allowedPackages, canDispatch)
                gesture(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) },
                    integer(action, "duration_ms").toLong(), frame, canDispatch)
            }
            "input_text" -> setText(action.arguments.getValue("text") as String, frame, allowedPackages, canDispatch)
            "press_key" -> {
                val key = action.arguments.getValue("key") as String
                // This service does not expose an IME editor connection. Never substitute a generic key event.
                if (key == "enter") throw deviceFailure("INPUT_FAILED", ExecutionFailureReason.IME_SUBMIT_UNSUPPORTED)
                if (currentPackage != scope.home) assertCurrentPackageAllowed(allowedPackages)
                immediateInput(frame, canDispatch, ExecutionFailureReason.GLOBAL_ACTION_REJECTED) {
                    performGlobalAction(if (key == "back") GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME)
                }
            }
            "launch_app" -> {
                val target = action.arguments.getValue("package_name") as String
                if (AppChannels.isHarnessPackage(target) || target !in allowedPackages || target !in scope.allowed) {
                    throw deviceFailure("AUTHORIZATION_UNAVAILABLE", ExecutionFailureReason.INPUT_APP_NOT_AUTHORIZED)
                }
                val launch = packageManager.getLaunchIntentForPackage(target) ?: throw deviceFailure("INPUT_FAILED")
                val component = launch.resolveActivity(packageManager) ?: throw deviceFailure("INPUT_FAILED")
                if (component.packageName != target) {
                    throw deviceFailure("AUTHORIZATION_UNAVAILABLE", ExecutionFailureReason.INPUT_APP_NOT_AUTHORIZED)
                }
                launch.component = component
                launch.selector = null
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val owner = controls ?: throw deviceFailure("AUTHORIZATION_UNAVAILABLE")
                val generation = captureGeneration.get()
                val result = immediateInput(frame, canDispatch, ExecutionFailureReason.INPUT_RESULT_UNKNOWN) { startActivity(launch); true }
                pendingLaunch = LaunchObservation(currentPackage, target, generation, scope, owner)
                if (captureGeneration.get() != generation || sessionScope !== scope || controls !== owner || !captureAllowed()) {
                    pendingLaunch = null
                }
                lastFrame = null // A dispatched App transition invalidates its pre-launch frame.
                result
            }
            "wait" -> {
                delay(integer(action, "duration_ms").toLong())
                assertDispatchAllowed(frame, canDispatch)
                ActionResult("completed")
            }
            "finish" -> ActionResult("completed") // The runtime interprets the phase-specific declaration.
            else -> throw deviceFailure("INPUT_FAILED")
        }
    }

    private fun assertDispatchAllowed(frame: ScreenFrame, canDispatch: () -> Boolean) {
        if (!canDispatch() || !connected || controls == null || !captureAllowed() || observationBusy || captureOperation != null) {
            throw deviceFailure("AUTHORIZATION_UNAVAILABLE", ExecutionFailureReason.CONTROL_NOT_AVAILABLE)
        }
        if (!overlayStopAvailable && !notificationControlAvailable) {
            throw deviceFailure("AUTHORIZATION_UNAVAILABLE", ExecutionFailureReason.CONTROL_NOT_AVAILABLE)
        }
        val stamp = lastFrame ?: throw deviceFailure("SCREENSHOT_FAILED", ExecutionFailureReason.STALE_OBSERVATION)
        if (stamp.id != frame.id || stamp.generation != captureGeneration.get() ||
            stamp.geometry.width != frame.width || stamp.geometry.height != frame.height ||
            stamp.geometry != geometry() || frame.png.isEmpty()) {
            throw deviceFailure("SCREENSHOT_FAILED", ExecutionFailureReason.STALE_OBSERVATION)
        }
        // Check the current authorization before treating a changed authorized App as a stale frame.
        // Observation/capture failures keep their hard-stop policy in requireObservationPackage().
        if (requireObservationPackage() != stamp.packageName) {
            throw deviceFailure("SCREENSHOT_FAILED", ExecutionFailureReason.STALE_OBSERVATION)
        }
    }

    private fun assertCurrentPackageAllowed(allowed: Set<String>) {
        val root = rootInActiveWindow ?: throw deviceFailure("AUTHORIZATION_UNAVAILABLE")
        try {
            val current = root.packageName?.toString()
            if (AppChannels.isHarnessPackage(current) || current !in allowed || current !in sessionScope?.allowed.orEmpty()) {
                throw deviceFailure("AUTHORIZATION_UNAVAILABLE", ExecutionFailureReason.INPUT_APP_NOT_AUTHORIZED)
            }
        } finally { root.recycle() }
    }

    private suspend fun clearOverlayForGesture(
        x1: Int, y1: Int, x2: Int, y2: Int,
        frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean,
    ) {
        val panel = overlay ?: return
        if (!panel.conflictsWithGesture(x1, y1, x2, y2)) return
        // Move only our own controls. The model's target and the underlying App stay unchanged.
        // Never hide a touchable window and dispatch through it without a visible stop entry.
        if (!panel.relocateAwayFrom(x1, y1, x2, y2)) {
            throw deviceFailure("INPUT_FAILED", ExecutionFailureReason.OVERLAY_TARGET_BLOCKED)
        }
        awaitDisplayFrame()
        awaitDisplayFrame()
        // Takeover, rotation, or another capture may have invalidated this action during layout.
        assertDispatchAllowed(frame, canDispatch)
        assertCurrentPackageAllowed(allowedPackages)
        if (panel !== overlay || panel.conflictsWithGesture(x1, y1, x2, y2)) {
            throw deviceFailure("INPUT_FAILED", ExecutionFailureReason.OVERLAY_TARGET_BLOCKED)
        }
    }

    private suspend fun gesture(path: Path, duration: Long, frame: ScreenFrame, canDispatch: () -> Boolean): ActionResult =
        suspendCancellableCoroutine { continuation ->
            val description = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            assertDispatchAllowed(frame, canDispatch)
            if (!nativeInput.compareAndSet(false, true)) throw deviceFailure("INPUT_STATUS_UNKNOWN")
            val inputId = inputOrdinal.incrementAndGet()
            activeInput.set(inputId)
            fun settleNative(): Boolean {
                if (!activeInput.compareAndSet(inputId, 0)) return false
                nativeInput.set(false)
                return true
            }
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = settle("completed", gestureDescription)
                override fun onCancelled(gestureDescription: GestureDescription) = settle("cancelled", gestureDescription)
                private fun settle(status: String, gestureDescription: GestureDescription) {
                    // Duplicate/late callbacks can never clear a newer input's barrier.
                    if (!settleNative()) return
                    if (BuildConfig.DEBUG) diagnosticObserver?.let { observer ->
                        runCatching { observer.gestureTerminal(gestureDescription, status) }
                    }
                    if (continuation.isActive) continuation.resume(ActionResult(
                        status,
                        failureReason = if (status == "cancelled") ExecutionFailureReason.GESTURE_CANCELLED else null,
                    ))
                    notifyInputSettled()
                }
            }
            try {
                if (BuildConfig.DEBUG) diagnosticObserver?.let { observer ->
                    runCatching { observer.gestureDispatch(description, frame) }
                }
                if (!dispatchGesture(description, callback, mainHandler)) {
                    settleNative()
                    if (continuation.isActive) continuation.resumeWithException(
                        deviceFailure("INPUT_FAILED", ExecutionFailureReason.GESTURE_NOT_DISPATCHED))
                    notifyInputSettled()
                }
            } catch (_: SecurityException) {
                settleNative()
                if (continuation.isActive) continuation.resumeWithException(deviceFailure("AUTHORIZATION_UNAVAILABLE"))
                notifyInputSettled()
            } catch (_: Exception) {
                // Unknown dispatch outcome must keep the barrier closed until a genuine terminal callback.
                if (continuation.isActive) continuation.resumeWithException(deviceFailure("INPUT_STATUS_UNKNOWN"))
            }
            // Cancellation is deliberately not a native-input terminal and does not clear nativeInput.
        }

    private fun setText(text: String, frame: ScreenFrame, allowed: Set<String>, canDispatch: () -> Boolean): ActionResult {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw deviceFailure("INPUT_FAILED", ExecutionFailureReason.NO_FOCUSED_INPUT)
        try {
            // Authorization/protected-input checks stay hard stops, even when another precondition
            // would also fail. Only a known editable-input failure may return to model observation.
            if (node.isPassword) throw deviceFailure("INPUT_FAILED", ExecutionFailureReason.PROTECTED_INPUT)
            val inputPackage = node.packageName?.toString()
            if (AppChannels.isHarnessPackage(inputPackage) || inputPackage !in allowed || inputPackage !in sessionScope?.allowed.orEmpty()) {
                throw deviceFailure("INPUT_FAILED", ExecutionFailureReason.INPUT_APP_NOT_AUTHORIZED)
            }
            if (!node.isVisibleToUser || !node.isFocused) {
                throw deviceFailure("INPUT_FAILED", ExecutionFailureReason.INPUT_NOT_FOCUSED)
            }
            if (!node.isEditable) throw deviceFailure("INPUT_FAILED", ExecutionFailureReason.INPUT_NOT_EDITABLE)
            if (!node.isEnabled) throw deviceFailure("INPUT_FAILED", ExecutionFailureReason.INPUT_DISABLED)
            if (node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
                throw deviceFailure("INPUT_FAILED", ExecutionFailureReason.INPUT_ACTION_UNSUPPORTED)
            }
            return immediateInput(frame, canDispatch, ExecutionFailureReason.SET_TEXT_REJECTED) {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                })
            }
        } finally { node.recycle() }
    }

    private fun immediateInput(
        frame: ScreenFrame,
        canDispatch: () -> Boolean,
        rejectedReason: ExecutionFailureReason,
        action: () -> Boolean,
    ): ActionResult {
        assertDispatchAllowed(frame, canDispatch)
        if (!nativeInput.compareAndSet(false, true)) throw deviceFailure("INPUT_STATUS_UNKNOWN")
        try {
            if (!action()) throw deviceFailure("INPUT_FAILED", rejectedReason)
            return ActionResult("completed")
        } catch (failure: RunFailure) { throw failure
        } catch (_: SecurityException) { throw deviceFailure("AUTHORIZATION_UNAVAILABLE")
        } catch (_: Exception) { throw deviceFailure("INPUT_FAILED", ExecutionFailureReason.INPUT_RESULT_UNKNOWN)
        } finally {
            nativeInput.set(false)
            notifyInputSettled()
        }
    }

    private fun notifyInputSettled() {
        val state = controls?.snapshot?.value?.state
        if (state == RunState.PAUSING || state == RunState.STOPPING || state == RunState.UNCERTAIN) controls?.recheck()
    }

    private fun integer(action: PhoneAction, key: String): Int {
        val value = action.arguments[key]
        val long = when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw deviceFailure("INPUT_FAILED")
        }
        if (long !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw deviceFailure("INPUT_FAILED")
        return long.toInt()
    }

    private fun pixel(action: PhoneAction, key: String, dimension: Int): Int =
        floor(integer(action, key).toDouble() * dimension / 1000.0).toInt()

    private fun validateArguments(action: PhoneAction) {
        val required = when (action.name) {
            "tap" -> setOf("x", "y")
            "long_press" -> setOf("x", "y", "duration_ms")
            "swipe" -> setOf("from_x", "from_y", "to_x", "to_y", "duration_ms")
            "input_text" -> setOf("text")
            "press_key" -> setOf("key")
            "launch_app" -> setOf("package_name")
            "wait" -> setOf("duration_ms")
            "finish" -> setOf("outcome", "message")
            else -> throw deviceFailure("INPUT_FAILED")
        }
        if (action.arguments.keys != required) throw deviceFailure("INPUT_FAILED")
        for (key in required.filter { it == "x" || it == "y" || it.endsWith("_x") || it.endsWith("_y") }) {
            if (integer(action, key) !in 0..999) throw deviceFailure("INPUT_FAILED")
        }
        if ("duration_ms" in required) {
            val minimum = if (action.name == "long_press") 300 else 100
            if (integer(action, "duration_ms") !in minimum..2000) throw deviceFailure("INPUT_FAILED")
        }
        if (action.name == "input_text" && action.arguments["text"] !is String) throw deviceFailure("INPUT_FAILED")
        if (action.name == "press_key" && action.arguments["key"] !in setOf("back", "home", "enter")) throw deviceFailure("INPUT_FAILED")
        if (action.name == "launch_app" && (action.arguments["package_name"] as? String).isNullOrBlank()) throw deviceFailure("INPUT_FAILED")
        if (action.name == "finish" && (action.arguments["outcome"] !in setOf("completed", "unable") ||
                (action.arguments["message"] as? String).isNullOrBlank())) throw deviceFailure("INPUT_FAILED")
    }

    override fun showState(snapshot: RunSnapshot) {
        onMain {
            if (!connected) return@onMain
            if (snapshot != displayed) {
                stateToken = UUID.randomUUID().toString()
                consumedReturnToken = null
                notificationTokens.forEach { it.cancel() }
                notificationTokens.clear()
            }
            displayed = snapshot
            if (snapshot.state in setOf(RunState.IDLE, RunState.PAUSING, RunState.MANUAL, RunState.STOPPING,
                    RunState.UNCERTAIN, RunState.BLOCKED, RunState.ENDED)) setCaptureEnabled(false)
            overlay?.render(snapshot)
            publishNotification(snapshot)
            refreshReadiness()
        }
    }

    private fun allowedCommands(snapshot: RunSnapshot): Set<ControlCommand> = when (snapshot.state) {
        RunState.AUTO -> if (snapshot.handoffUsed) setOf(ControlCommand.STOP) else setOf(ControlCommand.TAKEOVER, ControlCommand.STOP)
        RunState.MANUAL -> setOf(ControlCommand.RETURN, ControlCommand.STOP)
        RunState.PAUSING, RunState.PREPARING, RunState.CHECKING -> setOf(ControlCommand.STOP)
        RunState.UNCERTAIN -> setOf(ControlCommand.STOP, ControlCommand.RECHECK, ControlCommand.OPEN)
        RunState.BLOCKED -> setOf(ControlCommand.STOP, ControlCommand.RETRY, ControlCommand.OPEN)
        RunState.IDLE, RunState.ENDED -> setOf(ControlCommand.OPEN)
        RunState.STOPPING -> emptySet()
    }

    private fun handlePanelCommand(command: ControlCommand) =
        handleNotificationCommand(displayed.sessionId, stateToken, command.wire)

    internal fun handleNotificationCommand(taskId: String, token: String, wire: String) {
        onMain {
            val current = controls?.snapshot?.value ?: return@onMain
            val command = ControlCommand.entries.firstOrNull { it.wire == wire } ?: return@onMain
            if (taskId != current.sessionId || token != stateToken || current != displayed || command !in allowedCommands(current)) return@onMain
            when (command) {
                ControlCommand.TAKEOVER -> controls?.takeover()
                ControlCommand.STOP -> controls?.stop()
                ControlCommand.RECHECK -> controls?.recheck()
                ControlCommand.RETRY -> if (deviceUnlocked() && !sensitivePageVisible && !competingServiceEnabled) controls?.retry()
                ControlCommand.RETURN -> {
                    if (!deviceUnlocked() || sensitivePageVisible || competingServiceEnabled ||
                        current.handoffUsed || consumedReturnToken == token) return@onMain
                    consumedReturnToken = token
                    controls?.giveBack()
                }
                ControlCommand.OPEN -> openApplication()
            }
        }
    }

    private fun createNotificationChannel() {
        val strings = AppLanguage.context(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, strings.getString(R.string.platform_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = strings.getString(R.string.platform_notification_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
            },
        )
    }

    private fun notificationsAllowed(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) false
        else {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(CHANNEL)
            manager.areNotificationsEnabled() && channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
        }
    } catch (_: Exception) { false }

    private fun publishNotification(snapshot: RunSnapshot) {
        val manager = getSystemService(NotificationManager::class.java)
        if (!notificationsAllowed()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        notificationTokens.forEach { it.cancel() }
        notificationTokens.clear()
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openPending = PendingIntent.getActivity(this, 1700, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val strings = AppLanguage.context(this)
        val stateLabel = AppStrings.stateLabel(strings, snapshot.state)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(strings.getString(R.string.channel_app_name))
            .setContentText(strings.getString(R.string.platform_notification_counts, stateLabel, snapshot.guiUsed, snapshot.budget, snapshot.plannerUsed))
            .setContentIntent(openPending)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(snapshot.state !in setOf(RunState.IDLE, RunState.ENDED))
        for (command in allowedCommands(snapshot).filter { it != ControlCommand.OPEN }.take(3)) {
            val intent = Intent(this, ControlReceiver::class.java).apply {
                action = ControlReceiver.ACTION
                data = Uri.parse("mobileharness://control/$stateToken/${command.wire}")
                putExtra(ControlReceiver.EXTRA_TASK, snapshot.sessionId)
                putExtra(ControlReceiver.EXTRA_TOKEN, stateToken)
                putExtra(ControlReceiver.EXTRA_COMMAND, command.wire)
            }
            val pending = PendingIntent.getBroadcast(this, command.ordinal, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            notificationTokens.add(pending)
            val action = Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), command.localizedLabel(strings), pending)
            if (Build.VERSION.SDK_INT >= 31 && command == ControlCommand.RETURN) action.setAuthenticationRequired(true)
            builder.addAction(action.build())
        }
        try { manager.notify(NOTIFICATION_ID, builder.build()) } catch (_: SecurityException) { }
    }

    private fun openApplication() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(intent) } catch (_: Exception) { }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    private fun deviceFailure(code: String, reason: ExecutionFailureReason? = when (code) {
        "AUTHORIZATION_UNAVAILABLE" -> ExecutionFailureReason.CONTROL_NOT_AVAILABLE
        "SCREENSHOT_FAILED" -> ExecutionFailureReason.SCREENSHOT_UNAVAILABLE
        "INPUT_STATUS_UNKNOWN" -> ExecutionFailureReason.INPUT_RESULT_UNKNOWN
        else -> null
    }) = RunFailure("DEVICE", code, executionReason = reason)
}
