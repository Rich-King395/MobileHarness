package org.mobileharness.app.verification

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mobileharness.app.core.*
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.storage.AuthorizedAppsStore
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Zero-network, synthetic Home/App scope checks. Pixels and coordinates never leave RAM. */
class AutoStartVerification(private val context: Context) {
    data class Result(val status: String, val code: String, val flags: Map<String, Boolean>,
        val performAttempts: Int, val completedPerforms: Int)

    suspend fun run(): Result {
        if (!RUNNING.compareAndSet(false, true)) return Result("BLOCKED", "ALREADY_RUNNING", emptyMap(), 0, 0)
        val flags = linkedMapOf<String, Boolean>()
        var live: HarnessAccessibilityService? = null
        var previous: RuntimeControls? = null
        var temporary: LocalControls? = null
        var oldServiceFlags: Int? = null
        var homeLaunch: Intent? = null
        var homeFrame: ScreenFrame? = null
        var fixtureFrame: ScreenFrame? = null
        var attempts = 0
        var completed = 0
        var code = "OK"
        try {
            withContext(Dispatchers.Default) { verifyStore(flags) }
            val setup = withContext(Dispatchers.Main.immediate) {
                val service = HarnessAccessibilityService.instance ?: fail("SERVICE_UNAVAILABLE")
                val old = service.controls ?: fail("CONTROLS_UNAVAILABLE")
                if (!service.connected || service.inputInFlight || old.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED)) {
                    fail("SERVICE_NOT_IDLE")
                }
                live = service
                previous = old
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                    ?: fail("HOME_UNAVAILABLE")
                if (resolved.activityInfo.packageName == "android" || resolved.activityInfo.name.contains("ResolverActivity")) fail("HOME_UNAVAILABLE")
                home.component = android.content.ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
                home.selector = null
                home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                homeLaunch = Intent(home)
                val options = SessionOptions("Synthetic automatic start", resolved.activityInfo.packageName, setOf(FIXTURE),
                    false, 6, ModelProfile(model = "synthetic"), ModelProfile(model = "unused"),
                    StartMode.HOME_AUTO, resolved.activityInfo.packageName, mapOf(FIXTURE to "Synthetic fixture"))
                val controls = LocalControls(service)
                temporary = controls
                service.controls = controls
                service.configureSession(options)
                oldServiceFlags = service.serviceInfo.flags
                service.serviceInfo = service.serviceInfo.apply { this.flags = this.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
                context.startActivity(home)
                options
            }
            delay(800) // Planned launch settling, not a screenshot retry.
            val service = checkNotNull(live)
            val controls = checkNotNull(temporary)
            withContext(Dispatchers.Main.immediate) {
                service.setCaptureEnabled(true)
                homeFrame = service.observe()
                flags["home_frame_captured"] = true
                val frame = checkNotNull(homeFrame)
                attempts++
                val rejected = try {
                    service.perform(PhoneAction("tap", mapOf("x" to 500, "y" to 500)), frame, setOf(FIXTURE)) { controls.active() }
                    false
                } catch (failure: RunFailure) { failure.category == "DEVICE" && failure.code == "INPUT_FAILED" }
                flags["arbitrary_home_tap_rejected"] = rejected
                requireFlag(rejected, "HOME_INPUT_NOT_REJECTED")
                // Simulate model latency before launch so immediate post-launch observation
                // does not introduce the screenshot-rate limit as a second variable.
                delay(650)
                val action = PhoneAction("launch_app", mapOf("package_name" to FIXTURE))
                attempts++
                val launched = service.perform(action, frame, setOf(FIXTURE)) { controls.active() }.status == "completed"
                if (launched) completed++
                flags["authorized_app_launched"] = launched
                requireFlag(launched, "FIXTURE_LAUNCH_FAILED")
                frame.png.fill(0)
            }
            // Observe immediately after the executor reports launch completion, like the runtime.
            withContext(Dispatchers.Main.immediate) {
                fixtureFrame = service.observe()
                val before = fixtureCount(service)
                val target = fixtureClickBounds(service)
                val frame = checkNotNull(fixtureFrame)
                // Check the exact first post-launch PNG, before any synthetic target tap.
                // Stable active-window identity alone does not prove that launch animation
                // has finished drawing the screenshot. No second screenshot is requested.
                val rendered = fixtureBackgroundRendered(service, controls, frame)
                flags["fixture_static_background_rendered_before_tap"] = rendered
                requireFlag(rendered, "FIXTURE_RENDER_NOT_CONFIRMED_BEFORE_TAP")
                val action = PhoneAction("tap", mapOf("x" to (target.centerX().toLong() * 1000 / frame.width).toInt(),
                    "y" to (target.centerY().toLong() * 1000 / frame.height).toInt()))
                val original = action.arguments.toMap()
                attempts++
                val result = service.perform(action, frame, setOf(FIXTURE)) { controls.active() }
                if (result.status == "completed") completed++
                delay(150)
                flags["fixture_tap_effect"] = result.status == "completed" && fixtureCount(service) == before + 1
                flags["original_action_unchanged"] = action.arguments == original
                flags["native_terminal_confirmed"] = !service.inputInFlight
                requireFlag(flags["fixture_tap_effect"] == true && flags["native_terminal_confirmed"] == true, "FIXTURE_TAP_FAILED")
                frame.png.fill(0)

                service.configureSession(setup.copy(authorizedPackages = emptySet(), authorizedAppLabels = emptyMap()))
                service.setCaptureEnabled(true)
                val beforeTicket = captureOrdinal(service)
                val unauthorized = try { service.observe().also { it.png.fill(0) }; false }
                    catch (failure: RunFailure) { failure.code == "AUTHORIZATION_UNAVAILABLE" }
                flags["unauthorized_foreground_rejected"] = unauthorized
                flags["unauthorized_rejected_before_capture_ticket"] = captureOrdinal(service) == beforeTicket
                service.configureSession(null)
                service.setCaptureEnabled(true)
                val noScope = try { service.observe().also { it.png.fill(0) }; false }
                    catch (failure: RunFailure) { failure.code == "AUTHORIZATION_UNAVAILABLE" }
                flags["missing_scope_rejected"] = noScope
                requireFlag(unauthorized && flags["unauthorized_rejected_before_capture_ticket"] == true && noScope, "OBSERVATION_SCOPE_FAILED")
            }

            // Synthetic ownership invalidation only: this is not a human-control interval.
            // An abandoned launch must not force a subsequent observation back to its old target.
            withContext(Dispatchers.Main.immediate) {
                service.configureSession(setup)
                service.setCaptureEnabled(true)
                delay(650) // Pace independent screenshots; no capture or model retry.
                fixtureFrame = service.observe()
                val frame = checkNotNull(fixtureFrame)
                attempts++
                val launched = service.perform(PhoneAction("launch_app", mapOf("package_name" to FIXTURE)),
                    frame, setOf(FIXTURE)) { controls.active() }.status == "completed"
                if (launched) completed++
                flags["launch_before_invalidation_completed"] = launched
                requireFlag(launched && !service.inputInFlight, "INVALIDATION_LAUNCH_FAILED")
                frame.png.fill(0)
                service.setCaptureEnabled(false)
                // No window/package/text/pixel read occurs while capture is disabled.
                context.startActivity(Intent(checkNotNull(homeLaunch)))
                delay(800)
                service.setCaptureEnabled(true)
                val beforeTicket = captureOrdinal(service)
                homeFrame = service.observe()
                flags["invalidated_launch_observes_current_home"] =
                    service.isInitialAppForeground(setup.homePackage) && captureOrdinal(service) == beforeTicket + 1
                requireFlag(flags["invalidated_launch_observes_current_home"] == true, "INVALIDATED_LAUNCH_REUSED")
                checkNotNull(homeFrame).png.fill(0)
            }
        } catch (cancelled: CancellationException) {
            code = "CANCELLED"
        } catch (failure: CheckFailure) {
            code = failure.code
        } catch (failure: RunFailure) {
            code = failure.code.takeIf { it in setOf("AUTHORIZATION_UNAVAILABLE", "SCREENSHOT_FAILED", "INPUT_FAILED", "INPUT_STATUS_UNKNOWN") } ?: "DEVICE_FAILURE"
        } catch (_: Exception) { code = "VERIFICATION_FAILURE" }
        finally {
            homeFrame?.png?.fill(0)
            fixtureFrame?.png?.fill(0)
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                live?.let { service ->
                    service.setCaptureEnabled(false)
                    service.configureSession(null)
                    temporary?.end()
                    try { withTimeout(5_000) { while (service.inputInFlight) delay(25) } }
                    catch (_: Exception) { temporary?.uncertain() }
                    oldServiceFlags?.let { saved -> runCatching { service.serviceInfo = service.serviceInfo.apply { this.flags = saved } } }
                    if (!service.inputInFlight && service.controls === temporary && previous != null) {
                        service.controls = previous
                        service.showState(checkNotNull(previous).snapshot.value)
                        flags["controls_restored"] = true
                    } else flags["controls_restored"] = false
                }
            }
            RUNNING.set(false)
        }
        if (code == "OK" && (flags["controls_restored"] != true || flags.values.any { !it })) code = "ASSERTION_FAILED"
        return Result(if (code == "OK") "PASS" else "FAIL", code, flags.toMap(), attempts, completed)
    }

    private fun fixtureRoot(service: HarnessAccessibilityService): android.view.accessibility.AccessibilityNodeInfo {
        val root = service.rootInActiveWindow ?: fail("FIXTURE_NOT_FOREGROUND")
        if (root.packageName?.toString() != FIXTURE) { root.recycle(); fail("FIXTURE_NOT_FOREGROUND") }
        return root
    }
    private fun fixtureClickBounds(service: HarnessAccessibilityService): Rect {
        val root = fixtureRoot(service)
        return try {
            val nodes = root.findAccessibilityNodeInfosByViewId("$FIXTURE:id/fixture_click")
            try { nodes.firstOrNull { it.isVisibleToUser }?.let { Rect().also(it::getBoundsInScreen) } ?: fail("FIXTURE_BUTTON_UNAVAILABLE") }
            finally { nodes.forEach { it.recycle() } }
        } finally { root.recycle() }
    }
    private fun fixtureCount(service: HarnessAccessibilityService): Int {
        val root = fixtureRoot(service)
        return try {
            val nodes = root.findAccessibilityNodeInfosByViewId("$FIXTURE:id/fixture_click_count")
            try { nodes.firstOrNull()?.text?.toString()?.substringAfterLast('：')?.toIntOrNull() ?: fail("FIXTURE_COUNTER_UNAVAILABLE") }
            finally { nodes.forEach { it.recycle() } }
        } finally { root.recycle() }
    }
    private suspend fun fixtureBackgroundRendered(service: HarnessAccessibilityService, controls: LocalControls, frame: ScreenFrame): Boolean {
        if (!controls.active() || !service.connected || !service.captureEnabled || HarnessAccessibilityService.sensitivePageVisible) return false
        val root = fixtureRoot(service)
        val bounds = try {
            val nodes = root.findAccessibilityNodeInfosByViewId("$FIXTURE:id/fixture_scroll")
            try {
                nodes.singleOrNull { it.refresh() && it.isVisibleToUser && it.packageName?.toString() == FIXTURE }
                    ?.let { Rect().also(it::getBoundsInScreen) } ?: return false
            } finally { nodes.forEach { it.recycle() } }
        } finally { root.recycle() }
        if (bounds.width() < 4 || bounds.height() < 4 || bounds.left < 0 || bounds.top < 0 ||
            bounds.right > frame.width || bounds.bottom > frame.height) return false
        return withContext(Dispatchers.Default) {
            val bitmap = BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size) ?: return@withContext false
            try {
                if (bitmap.width != frame.width || bitmap.height != frame.height) return@withContext false
                // Fixture ScrollView has a fixed #EDF2F7 background and its transparent
                // child has 8dp horizontal padding; left+2px avoids row text/scrollbars.
                val matched = listOf(1, 2, 3).all { quarter ->
                    val color = bitmap.getPixel(bounds.left + 2, bounds.top + bounds.height() * quarter / 4)
                    kotlin.math.abs(Color.red(color) - 0xed) <= 2 &&
                        kotlin.math.abs(Color.green(color) - 0xf2) <= 2 &&
                        kotlin.math.abs(Color.blue(color) - 0xf7) <= 2
                }
                matched && controls.active()
            } finally { bitmap.recycle() }
        }
    }
    private fun captureOrdinal(service: HarnessAccessibilityService): Long = HarnessAccessibilityService::class.java.getDeclaredField("captureOrdinal").let {
        it.isAccessible = true
        (it.get(service) as AtomicLong).get()
    }
    private fun verifyStore(flags: MutableMap<String, Boolean>) {
        val isolated = File(context.noBackupFilesDir, "auto-start-store-${UUID.randomUUID()}").canonicalFile
        require(isolated.parentFile == context.noBackupFilesDir.canonicalFile)
        check(isolated.mkdirs())
        val isolatedContext = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = isolated }
        try {
            val store = AuthorizedAppsStore(isolatedContext)
            val packages = setOf("synthetic.authorized", "synthetic.second")
            requireFlag(store.load().isEmpty(), "SCOPE_STORE_INITIAL_STATE")
            store.save(packages)
            val encrypted = File(isolated, "authorized-apps/scope-v1.enc")
            val bytes = encrypted.readBytes()
            flags["scope_store_roundtrip"] = store.load() == packages
            flags["scope_store_no_plaintext"] = packages.none { String(bytes, Charsets.ISO_8859_1).contains(it) }
            bytes.fill(0)
            val corrupt = byteArrayOf(1, 2, 3)
            encrypted.writeBytes(corrupt)
            val rejected = try { store.load(); false } catch (_: IllegalStateException) { true }
            flags["corrupt_scope_rejected_without_rewrite"] = rejected && encrypted.readBytes().contentEquals(corrupt)
            store.save(emptySet())
            flags["explicit_empty_scope_saved"] = store.load().isEmpty()
            requireFlag(flags.values.all { it }, "SCOPE_STORE_FAILED")
        } finally { check(isolated.parentFile == context.noBackupFilesDir.canonicalFile); isolated.deleteRecursively() }
    }
    private class LocalControls(private val service: HarnessAccessibilityService) : RuntimeControls {
        override val snapshot = MutableStateFlow(RunSnapshot(state = RunState.AUTO, message = "本地启动验证"))
        fun active() = snapshot.value.state == RunState.AUTO && service.controls === this
        override fun start(options: SessionOptions) = Unit
        override fun takeover() = stop()
        override fun giveBack() = Unit
        override fun retry() = Unit
        override fun recheck() { if (!service.inputInFlight) end() }
        override fun stop() = end()
        override fun environmentUnavailable(code: String) = end()
        fun end() { snapshot.value = snapshot.value.copy(state = if (service.inputInFlight) RunState.STOPPING else RunState.ENDED); service.setCaptureEnabled(false); service.showState(snapshot.value) }
        fun uncertain() { snapshot.value = snapshot.value.copy(state = RunState.UNCERTAIN); service.showState(snapshot.value) }
    }
    private class CheckFailure(val code: String) : Exception()
    private fun fail(code: String): Nothing = throw CheckFailure(code)
    private fun requireFlag(value: Boolean, code: String) { if (!value) fail(code) }
    private companion object { const val FIXTURE = "org.mobileharness.fixture"; val RUNNING = AtomicBoolean(false) }
}
