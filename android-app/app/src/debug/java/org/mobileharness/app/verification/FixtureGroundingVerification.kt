package org.mobileharness.app.verification

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mobileharness.app.HarnessApplication
import org.mobileharness.app.core.*
import org.mobileharness.app.model.QwenGateway
import org.mobileharness.app.model.ProtocolDiagnostic
import org.mobileharness.app.model.ModelTransport
import org.mobileharness.app.model.OkHttpModelTransport
import org.mobileharness.app.model.PromptBundles
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.platform.PlatformDiagnosticObserver
import org.mobileharness.app.storage.AuthorizedAppsStore
import org.mobileharness.app.storage.LocalTaskRecordStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * One real GUI request, one unchanged model tap at most, and one independent known-point tap.
 * Only the synthetic fixture is observed. Pixels, node text/bounds, payloads and digests stay in RAM.
 * A target miss is evidence about this output, not evidence that the native dispatcher failed.
 */
class FixtureGroundingVerification(context: Context) {
    private val context = context.applicationContext
    enum class Mode { TAP, FINISH, LOCAL_RENDER, UNABLE_PROBE, LAST_TASK_HOME_PROBE }
    data class Result(
        val status: String = "RUNNING", val code: String = "PENDING",
        val mode: String = "TAP",
        val adapterVersion: String? = null,
        val guiAttempts: Int = 0, val screenshotCalls: Int = 0,
        val modelPerformCalls: Int = 0, val controlPerformCalls: Int = 0,
        val modelCode: String = "NOT_CALLED",
        val responseOutcome: String = "NOT_CALLED", val statedReason: String = "NOT_APPLICABLE",
        val finishMessageChars: Int? = null,
        val flags: Map<String, Boolean> = emptyMap(),
        val protocol: Map<String, Any?> = emptyMap(),
    )
    private val result = AtomicReference(Result())
    val latest: Result get() = result.get()

    suspend fun run(mode: Mode = Mode.TAP, onResult: (Result) -> Unit) {
        if (!RUNNING.compareAndSet(false, true)) {
            onResult(Result(status = "BLOCKED", code = "VERIFICATION_ALREADY_RUNNING")); return
        }
        result.set(Result(mode = mode.name))
        var phone: HarnessAccessibilityService? = null
        var previous: RuntimeControls? = null
        var controls: Controls? = null
        var gateway: QwenGateway? = null
        var oldFlags: Int? = null
        var oldObserver: PlatformDiagnosticObserver? = null
        var observerInstalled = false
        var probeTransport: ProbeTransport? = null
        try {
            val app = context as? HarnessApplication ?: fail("INVALID_STATE")
            val service = HarnessAccessibilityService.instance?.takeIf { it.connected }
                ?: fail("SERVICE_DISCONNECTED")
            phone = service
            previous = withContext(Dispatchers.Main.immediate) {
                val old = service.controls ?: fail("INVALID_STATE")
                if (old !== app.runtime || old.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED) || service.inputInFlight) {
                    fail("SERVICE_NOT_IDLE")
                }
                service.setCaptureEnabled(false)
                old
            }
            val transport = if (mode in setOf(Mode.UNABLE_PROBE, Mode.LAST_TASK_HOME_PROBE)) ProbeTransport(app, onResult) else null
            probeTransport = transport
            val model = if (transport == null) QwenGateway(context, app.profiles::keyFor) else QwenGateway(
                { name -> asset("1.1.0", name) }, transport,
                { name -> asset("1.2.0", name) }, app.profiles::keyFor,
            )
            model.protocolDiagnosticObserver = { diagnostic ->
                change(onResult) { it.copy(protocol = diagnostic.safeFields()) }
            }
            gateway = model
            supervisorScope {
                val active = AtomicReference<Job?>(null)
                val temporary = Controls(service, model) { active.get()?.cancel() }
                controls = temporary
                var monitor: Job? = null
                val probe = async(start = CoroutineStart.LAZY) {
                    val gui = if (mode == Mode.LOCAL_RENDER) ModelProfile(baseUrl = "https://invalid.example/v1", model = "unused") else withContext(Dispatchers.Default) {
                        if (!app.profiles.hasKey("gui")) fail("KEY_MISSING")
                        app.profiles.metadata("gui")
                    }
                    if (mode == Mode.LAST_TASK_HOME_PROBE) {
                        withContext(Dispatchers.Main.immediate) {
                            service.controls = temporary
                            oldFlags = service.serviceInfo.flags
                            oldObserver = service.diagnosticObserver
                            service.diagnosticObserver = captureObserver(onResult)
                            observerInstalled = true
                        }
                        lastTaskHomeProbe(app, model, service, temporary, gui, checkNotNull(transport), onResult)
                        return@async
                    }
                    val options = withContext(Dispatchers.Main.immediate) {
                        val home = context.packageManager.resolveActivity(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
                        )?.activityInfo?.takeIf { it.packageName != "android" && !it.name.contains("ResolverActivity") }
                            ?: fail("HOME_UNAVAILABLE")
                        SessionOptions(when (mode) { Mode.TAP -> TASK; Mode.FINISH, Mode.UNABLE_PROBE -> FINISH_TASK; Mode.LOCAL_RENDER -> "Local synthetic rendering diagnostic; no model request"; Mode.LAST_TASK_HOME_PROBE -> fail("INVALID_STATE") }, home.packageName, setOf(FIXTURE), false, 1, gui,
                            ModelProfile(baseUrl = "https://invalid.example/v1", model = "unused"),
                            StartMode.HOME_AUTO, home.packageName, mapOf(FIXTURE to "MobileHarness Test"))
                    }
                    change(onResult) { it.copy(adapterVersion = options.adapterVersion) }
                    withContext(Dispatchers.Main.immediate) {
                        service.controls = temporary
                        oldFlags = service.serviceInfo.flags
                        service.serviceInfo = service.serviceInfo.apply {
                            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        }
                        service.configureSession(options)
                        if (mode == Mode.UNABLE_PROBE) {
                            oldObserver = service.diagnosticObserver
                            service.diagnosticObserver = captureObserver(onResult)
                            observerInstalled = true
                        }
                        service.showState(temporary.snapshot.value)
                        context.startActivity(Intent("org.mobileharness.fixture.action.RESET")
                            .setClassName(FIXTURE, "$FIXTURE.MainActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                    delay(650) // A planned launch-settling interval, never an observation retry.
                    requireFixture(service, temporary)
                    monitor = launch(Dispatchers.Main.immediate) {
                        while (isActive && !temporary.stopped.get()) {
                            try { requireFixture(service, temporary) }
                            catch (_: Exception) { temporary.abort("AUTHORIZATION_UNAVAILABLE"); return@launch }
                            delay(100)
                        }
                    }
                    val baseline = state(service, temporary)
                    flag(onResult, "fixture_reset_baseline", baseline.isReset)
                    if (!baseline.isReset) fail("PRECONDITION_NOT_RESET")
                    if (mode == Mode.LOCAL_RENDER) {
                        localRender(service, temporary, onResult)
                        monitor?.cancel()
                        return@async
                    }
                    modelOnce(model, service, temporary, options, mode, onResult, transport)
                    currentCoroutineContext().ensureActive()
                    requireFixture(service, temporary)
                    if (service.inputInFlight) fail("INPUT_TERMINAL_UNCONFIRMED")
                    if (mode == Mode.UNABLE_PROBE) {
                        flag(onResult, "saved_gui_metadata_unchanged", withContext(Dispatchers.Default) { app.profiles.metadata("gui") } == gui)
                        flag(onResult, "probe_one_gui_no_phone_action", latest.guiAttempts == 1 && latest.modelPerformCalls == 0 && latest.controlPerformCalls == 0)
                        change(onResult) { it.copy(status = "DIAGNOSED", code = when (it.responseOutcome) {
                            "COMPLETED" -> "MODEL_COMPLETED_OBSERVATION_PROBE"
                            "UNABLE" -> "MODEL_UNABLE_OBSERVATION_PROBE"
                            "NON_FINISH" -> "MODEL_NON_FINISH_NOT_EXECUTED"
                            else -> "MODEL_REQUEST_FAILED_OBSERVATION_PROBE"
                        }) }
                        monitor?.cancel()
                        return@async
                    }
                    if (mode == Mode.FINISH) {
                        val accepted = latest.modelCode == "FINISH_PARSED"
                        change(onResult) { it.copy(status = if (accepted) "PASS" else "DIAGNOSED",
                            code = if (accepted) "FINISH_PROTOCOL_ACCEPTED" else "MODEL_FINISH_NOT_RETURNED") }
                        monitor?.cancel()
                        return@async
                    }
                    // Independent control test. It is not a model retry or replacement action.
                    delay(650)
                    controlTap(service, temporary, onResult)
                    val flags = latest.flags
                    val modelEffect = flags["model_counter_delta_one"] == true
                    val modelHit = flags["model_point_inside_captured_target"] == true
                    val controlEffect = flags["control_counter_delta_one"] == true
                    val invariantsHold = listOf("control_native_completed", "control_native_terminal_confirmed",
                        "control_original_action_unchanged", "control_png_unchanged", "control_other_fixture_state_unchanged").all { flags[it] == true } &&
                        (latest.modelPerformCalls == 0 || listOf("model_native_completed", "model_native_terminal_confirmed",
                            "model_original_action_unchanged", "model_png_unchanged_before_dispatch", "model_png_unchanged_after_dispatch",
                            "model_other_fixture_state_unchanged").all { flags[it] == true })
                    val code = when {
                        !invariantsHold -> "PAYLOAD_OR_FIXTURE_INVARIANT_FAILED"
                        !controlEffect -> "CONTROL_TAP_EFFECT_FAILED"
                        latest.modelCode != "TAP_PARSED" -> "MODEL_OUTPUT_NOT_EXECUTED_CONTROL_CONFIRMED"
                        !modelHit && !modelEffect -> "MODEL_POINT_MISS_CONTROL_CONFIRMED"
                        modelHit && !modelEffect -> "MODEL_POINT_HIT_WITHOUT_EFFECT_CONTROL_CONFIRMED"
                        !modelHit && modelEffect -> "MODEL_EFFECT_OUTSIDE_TARGET_UNEXPECTED"
                        else -> "MODEL_POINT_AND_CONTROL_EFFECT_CONFIRMED"
                    }
                    change(onResult) { it.copy(status = if (!invariantsHold) "FAIL" else if (modelHit && modelEffect && controlEffect) "PASS" else "DIAGNOSED", code = code) }
                    monitor?.cancel()
                }
                active.set(probe)
                probe.start()
                try { probe.await() }
                catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    change(onResult) { it.copy(status = "CANCELLED", code = temporary.stopCode.get()) }
                } finally {
                    withContext(NonCancellable) {
                        service.setCaptureEnabled(false)
                        model.cancelAll()
                        monitor?.cancelAndJoin()
                        probe.cancelAndJoin()
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            val code = safeCode(failure)
            change(onResult) { it.copy(status = if (code in BLOCKED_CODES) "BLOCKED" else "FAIL", code = code) }
        } finally {
            withContext(NonCancellable) {
                gateway?.cancelAll()
                probeTransport?.clear()
                phone?.let { service ->
                    service.setCaptureEnabled(false)
                    service.configureSession(null)
                    val terminal = try {
                        withTimeout(5_000) { while (service.inputInFlight) delay(20) }
                        true
                    } catch (_: TimeoutCancellationException) { false }
                    withContext(Dispatchers.Main.immediate) {
                        if (observerInstalled) {
                            service.diagnosticObserver = oldObserver
                            flag(onResult, "diagnostic_observer_restored", true)
                        }
                        oldFlags?.let { saved ->
                            if (HarnessAccessibilityService.instance === service) {
                                try {
                                    service.serviceInfo = service.serviceInfo.apply { flags = saved }
                                    flag(onResult, "service_flags_restored", true)
                                } catch (_: Exception) {
                                    flag(onResult, "service_flags_restored", false)
                                    change(onResult) { it.copy(status = "FAIL", code = "SERVICE_INFO_RESTORE_FAILED") }
                                }
                            }
                        }
                        val restore = {
                            if (previous != null && HarnessAccessibilityService.instance === service && service.controls === controls && !service.inputInFlight) {
                                service.controls = previous
                                service.showState(checkNotNull(previous).snapshot.value)
                                flag(onResult, "controls_restored", true)
                                flag(onResult, "native_terminal_confirmed", true)
                            }
                        }
                        if (terminal) restore()
                        else {
                            flag(onResult, "native_terminal_confirmed", false)
                            change(onResult) { it.copy(status = "UNCERTAIN", code = "INPUT_TERMINAL_UNCONFIRMED") }
                            controls?.awaitRealTerminal(restore)
                        }
                    }
                }
                RUNNING.set(false)
                onResult(latest)
            }
        }
    }

    private fun captureObserver(report: (Result) -> Unit) = object : PlatformDiagnosticObserver {
        override fun imageEncoded(source: Bitmap, png: ByteArray, screenshotUptimeMs: Long, requestedUptimeMs: Long) {
            flag(report, "capture_native_probe_seen", true)
            flag(report, "capture_timestamp_since_request", screenshotUptimeMs >= requestedUptimeMs)
            flag(report, "capture_native_dimensions_valid", source.width > 0 && source.height > 0)
        }
    }

    /** Replays only the first model request, never any proposed action or an audit session. */
    private suspend fun lastTaskHomeProbe(app: HarnessApplication, model: QwenGateway,
        phone: HarnessAccessibilityService, controls: Controls, gui: ModelProfile,
        transport: ProbeTransport, report: (Result) -> Unit) = supervisorScope {
        val original = withContext(Dispatchers.Default) {
            val summary = app.audit.sessionSummaries()
                .filter { it.ended && it.reason == "model_unable" && it.guiUsed == 1 }
                .maxByOrNull { File(context.noBackupFilesDir, "events/${it.id}.jsonl").lastModified() }
            flag(report, "selected_last_task_known", summary != null)
            if (summary == null) fail("NO_MATCHING_SESSION")
            val lookup = app.audit.readLocalTask(summary.id)
            flag(report, "selected_last_task_record_available", lookup.status == LocalTaskRecordStore.Status.AVAILABLE)
            lookup.task?.takeIf { it.isNotBlank() } ?: fail("LOCAL_TASK_UNAVAILABLE")
        }
        val scopeStore = AuthorizedAppsStore(context)
        val savedScope = withContext(Dispatchers.Default) { scopeStore.load() }
        val installed = withContext(Dispatchers.Main.immediate) {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
                .distinctBy { it.first }.filter { it.first != context.packageName }.toMap()
        }
        val allowed = savedScope.intersect(installed.keys)
        flag(report, "markor_installed", "net.gsantner.markor" in installed)
        flag(report, "markor_in_saved_authorized_scope", "net.gsantner.markor" in savedScope)
        flag(report, "markor_in_request_authorized_scope", "net.gsantner.markor" in allowed)
        flag(report, "authorized_scope_nonempty", allowed.isNotEmpty())
        if (allowed.isEmpty()) fail("EMPTY_AUTHORIZED_SCOPE")
        flag(report, "authorized_saved_scope_all_launchable", allowed == savedScope)
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val home = withContext(Dispatchers.Main.immediate) {
            context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
                ?.takeIf { it.packageName != "android" && !it.name.contains("ResolverActivity") }
                ?: fail("HOME_UNAVAILABLE")
        }
        val options = SessionOptions(original, home.packageName, allowed, false, 1, gui,
            ModelProfile(baseUrl = "https://invalid.example/v1", model = "unused"),
            StartMode.HOME_AUTO, home.packageName, installed.filterKeys { it in allowed })
        change(report) { it.copy(adapterVersion = options.adapterVersion) }
        flag(report, "known_model_qwen38max", gui.model.equals("qwen3.8-max", ignoreCase = true))
        withContext(Dispatchers.Main.immediate) {
            requireProbeOwner(phone, controls)
            phone.configureSession(options)
            phone.showState(controls.snapshot.value)
            homeIntent.component = ComponentName(home.packageName, home.name)
            homeIntent.selector = null
            homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(homeIntent)
            try {
                withTimeout(3_000) {
                    var since = 0L
                    while (true) {
                        requireProbeOwner(phone, controls)
                        val now = SystemClock.uptimeMillis()
                        if (phone.isInitialAppForeground(home.packageName)) {
                            if (since == 0L) since = now
                            if (now - since >= 750) break
                        } else since = 0L
                        delay(50)
                    }
                }
            } catch (_: TimeoutCancellationException) { fail("HOME_NOT_FOREGROUND") }
            requireProbeHome(phone, controls, home.packageName)
            flag(report, "home_foreground_stable_before_capture", true)
        }
        val monitor = launch(Dispatchers.Main.immediate) {
            while (isActive && !controls.stopped.get()) {
                try { requireProbeHome(phone, controls, home.packageName) }
                catch (_: Exception) { controls.abort("AUTHORIZATION_UNAVAILABLE"); return@launch }
                delay(100)
            }
        }
        var frame: ScreenFrame? = null
        var pngDigest: ByteArray? = null
        try {
            val captured = withContext(Dispatchers.Main.immediate) {
                requireProbeHome(phone, controls, home.packageName)
                if (phone.inputInFlight) fail("INPUT_TERMINAL_UNCONFIRMED")
                if (!phone.overlayStopAvailable && !phone.notificationControlAvailable) fail("STOP_ENTRY_UNAVAILABLE")
                val before = geometry(phone)
                phone.setCaptureEnabled(true)
                change(report) { it.copy(screenshotCalls = it.screenshotCalls + 1) }
                val value = phone.observe()
                try {
                    requireProbeHome(phone, controls, home.packageName)
                    val valid = before == geometry(phone) && value.width == before.width && value.height == before.height
                    flag(report, "model_frame_geometry_valid", valid)
                    if (!valid) fail("FRAME_TOKEN_OR_GEOMETRY_CHANGED")
                    value
                } catch (failure: Exception) { value.png.fill(0); throw failure }
            }
            frame = captured
            pngDigest = digest(captured.png)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(captured.png, 0, captured.png.size, bounds)
            flag(report, "observed_png_geometry_valid", bounds.outWidth == captured.width && bounds.outHeight == captured.height && bounds.outMimeType == "image/png")
            val request = GuiContext(options, Phase.ORIGINAL, frame = captured)
            transport.bind(request)
            withContext(Dispatchers.Main.immediate) { requireProbeHome(phone, controls, home.packageName) }
            model.preflightGui(request)
            currentCoroutineContext().ensureActive()
            controls.markAttempt()
            change(report) { it.copy(guiAttempts = 1, code = "MODEL_PENDING", modelCode = "MODEL_PENDING") }
            val reply = try { model.gui(request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: RunFailure) {
                change(report) { it.copy(status = "DIAGNOSED", code = "MODEL_REQUEST_FAILED_HOME_PROBE", modelCode = safeCode(failure)) }
                flag(report, "model_parsed", false)
                return@supervisorScope
            }
            withContext(Dispatchers.Main.immediate) { requireProbeHome(phone, controls, home.packageName) }
            val outcome = if (reply.action.name == "finish") when (reply.action.arguments["outcome"]) {
                "completed" -> "COMPLETED"; "unable" -> "UNABLE"; else -> "INVALID"
            } else "NON_FINISH"
            change(report) { it.copy(responseOutcome = outcome,
                modelCode = if (reply.action.name == "finish") "FINISH_PARSED" else "NON_FINISH_NOT_EXECUTED") }
            flag(report, "model_parsed", true)
            flag(report, "model_output_is_finish", reply.action.name == "finish")
            flag(report, "model_output_is_launch_app", reply.action.name == "launch_app")
            if (reply.action.name == "launch_app") {
                flag(report, "model_launch_package_authorized", reply.action.arguments["package_name"] in allowed)
                flag(report, "model_launch_targets_markor", reply.action.arguments["package_name"] == "net.gsantner.markor")
            }
            if (reply.action.name == "finish") {
                val statement = reply.action.arguments["message"] as? String ?: ""
                classifyMessage(statement, outcome, report)
            }
            flag(report, "model_png_unchanged_after_reply", matchesDigest(captured.png, checkNotNull(pngDigest)))
            change(report) { it.copy(status = "DIAGNOSED", code = when (outcome) {
                "COMPLETED" -> "MODEL_COMPLETED_HOME_PROBE"; "UNABLE" -> "MODEL_UNABLE_HOME_PROBE"; else -> "MODEL_NON_FINISH_NOT_EXECUTED"
            }) }
        } finally {
            withContext(NonCancellable) {
                phone.setCaptureEnabled(false)
                model.cancelAll()
                monitor.cancelAndJoin()
                transport.clear()
                frame?.png?.fill(0)
                pngDigest?.fill(0)
                flag(report, "saved_gui_metadata_unchanged", withContext(Dispatchers.Default) { app.profiles.metadata("gui") } == gui)
                flag(report, "authorized_scope_preserved", withContext(Dispatchers.Default) { scopeStore.load() } == savedScope && options.authorizedPackages == allowed)
                flag(report, "probe_one_gui_no_phone_action", latest.guiAttempts == 1 && latest.modelPerformCalls == 0 && latest.controlPerformCalls == 0)
                flag(report, "probe_no_phone_action", latest.modelPerformCalls == 0 && latest.controlPerformCalls == 0)
            }
        }
    }

    private fun requireProbeOwner(phone: HarnessAccessibilityService, controls: Controls) {
        if (controls.stopped.get()) throw CancellationException()
        if (!phone.connected || HarnessAccessibilityService.instance !== phone || phone.controls !== controls) fail("AUTHORIZATION_UNAVAILABLE")
    }
    private fun requireProbeHome(phone: HarnessAccessibilityService, controls: Controls, home: String) {
        requireProbeOwner(phone, controls)
        if (!phone.isInitialAppForeground(home)) fail("HOME_NOT_FOREGROUND")
    }

    private suspend fun localRender(phone: HarnessAccessibilityService, controls: Controls, onResult: (Result) -> Unit) {
        val captured = capture(phone, controls, onResult, "local")
        try {
            val bounds = withContext(Dispatchers.Main.immediate) {
                requireFixture(phone, controls)
                val root = phone.rootInActiveWindow ?: fail("FIXTURE_NOT_FOREGROUND")
                try {
                    if (root.packageName?.toString() != FIXTURE) fail("FIXTURE_NOT_FOREGROUND")
                    val nodes = root.findAccessibilityNodeInfosByViewId("$FIXTURE:id/fixture_scroll")
                    try {
                        val valid = nodes.filter { it.refresh() && it.packageName?.toString() == FIXTURE }
                        flag(onResult, "scroll_node_id_match_found", nodes.isNotEmpty())
                        flag(onResult, "scroll_node_found", valid.isNotEmpty())
                        flag(onResult, "scroll_node_unique", valid.size == 1)
                        flag(onResult, "scroll_node_visible", valid.any { it.isVisibleToUser })
                        flag(onResult, "scroll_visible_node_unique", valid.count { it.isVisibleToUser } == 1)
                        val target = valid.singleOrNull()
                        flag(onResult, "scroll_unique_node_is_visible", target?.isVisibleToUser == true)
                        target?.let { Rect().also(it::getBoundsInScreen) }
                    } finally { nodes.forEach { it.recycle() } }
                } finally { root.recycle() }
            }
            val frame = captured.frame
            val inside = bounds != null && bounds.width() >= 4 && bounds.height() >= 4 && bounds.left >= 0 && bounds.top >= 0 &&
                bounds.right <= frame.width && bounds.bottom <= frame.height
            flag(onResult, "scroll_bounds_in_png", inside)
            val samples = withContext(Dispatchers.Default) {
                val bitmap = BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size)
                flag(onResult, "local_render_bitmap_decoded", bitmap != null)
                if (bitmap == null) return@withContext emptyList<String>()
                try {
                    val geometryCorrect = bitmap.width == frame.width && bitmap.height == frame.height
                    flag(onResult, "local_render_png_geometry_correct", geometryCorrect)
                    if (!geometryCorrect || !inside || bounds == null) return@withContext emptyList<String>()
                    listOf(1, 2, 3).map { quarter ->
                        val color = bitmap.getPixel(bounds.left + 2, bounds.top + bounds.height() * quarter / 4)
                        fun near(r: Int, g: Int, b: Int) = kotlin.math.abs(Color.red(color) - r) <= 2 &&
                            kotlin.math.abs(Color.green(color) - g) <= 2 && kotlin.math.abs(Color.blue(color) - b) <= 2
                        when {
                            near(0xed, 0xf2, 0xf7) -> "MATCH"
                            near(0xff, 0xff, 0xff) -> "WHITE"
                            near(0, 0, 0) -> "BLACK"
                            else -> "OTHER"
                        }
                    }
                } finally { bitmap.recycle() }
            }
            samples.forEachIndexed { index, category ->
                listOf("MATCH", "WHITE", "BLACK", "OTHER").forEach { allowed ->
                    flag(onResult, "background_sample_${index + 1}_${allowed.lowercase()}", category == allowed)
                }
            }
            val allMatch = samples.size == 3 && samples.all { it == "MATCH" }
            flag(onResult, "background_three_samples_match", allMatch)
            flag(onResult, "local_render_zero_model_and_perform", latest.guiAttempts == 0 && latest.modelPerformCalls == 0 && latest.controlPerformCalls == 0)
            change(onResult) { it.copy(status = if (allMatch) "PASS" else "DIAGNOSED",
                code = when { allMatch -> "LOCAL_RENDER_MARKER_CONFIRMED"
                    !inside -> "LOCAL_RENDER_SCROLL_EVIDENCE_UNAVAILABLE"
                    else -> "LOCAL_RENDER_MARKER_NOT_CONFIRMED" }) }
        } finally {
            captured.frame.png.fill(0)
            phone.setCaptureEnabled(false)
        }
    }

    private suspend fun modelOnce(model: QwenGateway, phone: HarnessAccessibilityService, controls: Controls,
        options: SessionOptions, mode: Mode, onResult: (Result) -> Unit, probeTransport: ProbeTransport? = null) {
        val captured = capture(phone, controls, onResult, "model")
        val frame = captured.frame
        val pngDigest = digest(frame.png)
        try {
            val request = GuiContext(options, Phase.ORIGINAL, frame = frame)
            if (mode == Mode.UNABLE_PROBE) {
                flag(onResult, "fixture_background_rendered_in_request_frame", fixtureBackgroundRendered(phone, controls, frame))
                flag(onResult, "known_model_qwen38max", options.gui.model.equals("qwen3.8-max", ignoreCase = true))
                probeTransport?.bind(request)
            }
            requireFixture(phone, controls)
            model.preflightGui(request)
            currentCoroutineContext().ensureActive()
            requireFixture(phone, controls)
            controls.markAttempt()
            change(onResult) { it.copy(guiAttempts = 1, code = "MODEL_PENDING", modelCode = "MODEL_PENDING") }
            val reply = try { model.gui(request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: RunFailure) {
                change(onResult) { it.copy(modelCode = safeCode(failure)) }
                flag(onResult, "model_parsed", false)
                return
            }
            flag(onResult, "model_parsed", true)
            flag(onResult, "model_output_is_tap", reply.action.name == "tap")
            flag(onResult, "model_output_is_finish", reply.action.name == "finish")
            if (mode == Mode.UNABLE_PROBE) {
                val outcome = if (reply.action.name == "finish") when (reply.action.arguments["outcome"]) {
                    "completed" -> "COMPLETED"; "unable" -> "UNABLE"; else -> "INVALID"
                } else "NON_FINISH"
                change(onResult) { it.copy(responseOutcome = outcome,
                    modelCode = if (reply.action.name == "finish") "FINISH_PARSED" else "NON_FINISH_NOT_EXECUTED") }
                if (reply.action.name == "finish") classifyMessage(reply.action.arguments["message"] as? String ?: "", outcome, onResult)
                flag(onResult, "probe_no_phone_action", true)
                flag(onResult, "probe_fixture_unchanged", state(phone, controls) == captured.before)
                flag(onResult, "model_png_unchanged_after_reply", matchesDigest(frame.png, pngDigest))
                return
            }
            if (mode == Mode.FINISH) {
                // This diagnostic tests declaration syntax only; no returned action is executed.
                change(onResult) { it.copy(modelCode = if (reply.action.name == "finish") "FINISH_PARSED" else "NON_FINISH_NOT_EXECUTED") }
                flag(onResult, "finish_mode_no_phone_action", true)
                flag(onResult, "finish_mode_fixture_unchanged", state(phone, controls) == captured.before)
                flag(onResult, "model_png_unchanged_after_reply", matchesDigest(frame.png, pngDigest))
                return
            }
            if (reply.action.name != "tap") {
                change(onResult) { it.copy(modelCode = "NON_TAP_NOT_EXECUTED") }
                return
            }
            change(onResult) { it.copy(modelCode = "TAP_PARSED") }
            val originalArgs = reply.action.arguments.toMap()
            val originalJson = reply.argumentsJson
            val originalName = reply.action.name
            val xArgument = normalized(reply.action.arguments["x"])
            val yArgument = normalized(reply.action.arguments["y"])
            val x = xArgument * frame.width / 1000
            val y = yArgument * frame.height / 1000
            val hit = captured.before.target.contains(x, y)
            val rawPixelHit = captured.before.target.contains(xArgument, yArgument)
            flag(onResult, "model_point_inside_captured_target", hit)
            flag(onResult, "model_raw_arguments_as_pixels_inside_target", rawPixelHit)
            flag(onResult, "model_raw_pixel_only_hit_pattern", !hit && rawPixelHit)
            requireFixture(phone, controls)
            val ready = state(phone, controls)
            flag(onResult, "model_target_and_count_stable_before_dispatch", ready == captured.before)
            if (ready != captured.before) fail("PRECONDITION_CHANGED_NOT_EXECUTED")
            flag(onResult, "model_frame_token_current_before_dispatch", frameCurrent(phone, captured))
            if (!frameCurrent(phone, captured)) fail("FRAME_TOKEN_OR_GEOMETRY_CHANGED")
            flag(onResult, "model_png_unchanged_before_dispatch", matchesDigest(frame.png, pngDigest))
            if (!matchesDigest(frame.png, pngDigest)) fail("PAYLOAD_MUTATED")
            currentCoroutineContext().ensureActive()
            val native = perform(phone, controls, reply.action, captured, onResult, modelAction = true)
            flag(onResult, "model_native_completed", native.status == "completed")
            flag(onResult, "model_original_action_unchanged", reply.action.name == originalName &&
                reply.action.arguments == originalArgs && reply.argumentsJson == originalJson)
            flag(onResult, "model_png_unchanged_after_dispatch", matchesDigest(frame.png, pngDigest))
            if (native.status != "completed") fail("NATIVE_NOT_COMPLETED")
            currentCoroutineContext().ensureActive()
            delay(250)
            val after = state(phone, controls)
            flag(onResult, "model_counter_delta_one", after.count == captured.before.count + 1)
            flag(onResult, "model_counter_unchanged", after.count == captured.before.count)
            flag(onResult, "model_other_fixture_state_unchanged", after.sameOtherState(captured.before))
        } finally {
            flag(onResult, "model_png_retained_in_ram_only", true)
            probeTransport?.clear()
            frame.png.fill(0)
            pngDigest.fill(0)
            phone.setCaptureEnabled(false)
            model.cancelAll()
        }
    }

    /** Inspects the very bytes handed to the real transport, then forwards them unchanged. */
    private inner class ProbeTransport(private val app: HarnessApplication, private val report: (Result) -> Unit) : ModelTransport {
        private val real = OkHttpModelTransport()
        private val calls = AtomicLong()
        @Volatile private var expected: GuiContext? = null
        fun bind(value: GuiContext) { expected = value }
        fun clear() { expected = null }
        override fun cancelAll() = real.cancelAll()
        override suspend fun exchange(endpoint: String, key: String, body: ByteArray): ByteArray {
            val flags = linkedMapOf<String, Boolean>()
            flags["probe_actual_transport_called_once"] = calls.incrementAndGet() == 1L
            try {
                val request = checkNotNull(expected)
                val options = request.options
                val value = JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject
                val version = options.promptBundleVersion
                val messages = value.getAsJsonArray("messages")
                val system = messages[0].asJsonObject.get("content").asString
                val task = messages[1].asJsonObject.get("content").asString
                val gson = Gson()
                val slots = mapOf(
                    "ORIGINAL_TASK" to options.task,
                    "INITIAL_APP_PACKAGE" to options.initialPackage,
                    "AUTHORIZED_APP_PACKAGES_JSON" to gson.toJson(options.authorizedPackages.sorted()),
                    "START_MODE" to options.startMode.name,
                    "HOME_APP_PACKAGE" to options.homePackage,
                    "AUTHORIZED_APP_LABELS_JSON" to gson.toJson(options.authorizedAppLabels.toSortedMap()),
                )
                val taskTemplate = asset(version, "gui/original.task.txt")
                val expectedTask = Regex("\\{\\{([A-Z_]+)\\}\\}").replace(taskTemplate) { match -> checkNotNull(slots[match.groupValues[1]]) }
                val observation = messages[2].asJsonObject.getAsJsonArray("content")
                val observationSlots = mapOf("FRAME_ID" to request.frame.id, "IMAGE_WIDTH" to request.frame.width.toString(),
                    "IMAGE_HEIGHT" to request.frame.height.toString(), "GUI_PHASE" to request.phase.name)
                val expectedObservation = Regex("\\{\\{([A-Z_]+)\\}\\}").replace(asset(version, "gui-observation.template.txt")) { match -> checkNotNull(observationSlots[match.groupValues[1]]) }
                val imageParts = messages.flatMap { message ->
                    val content = message.asJsonObject.get("content")
                    if (content.isJsonArray) content.asJsonArray.filter { part -> part.isJsonObject && part.asJsonObject.get("type")?.asString == "image_url" } else emptyList()
                }
                flags["saved_gui_metadata_matches_request"] = app.profiles.metadata("gui") == options.gui
                flags["request_model_matches_saved_gui"] = value.get("model").asString == options.gui.model
                flags["request_model_known_qwen38max"] = value.get("model").asString.equals("qwen3.8-max", ignoreCase = true)
                flags["request_endpoint_matches_saved_gui"] = endpoint == QwenGateway.endpoint(options.gui.baseUrl)
                flags["request_uses_resolved_nonempty_key"] = key.isNotBlank()
                flags["request_phase_original"] = request.phase == Phase.ORIGINAL && task.lineSequence().firstOrNull() == "Phase: ORIGINAL"
                flags["request_system_template_exact"] = system == asset(version, "gui/system.txt")
                flags["request_task_template_exact"] = task == expectedTask
                flags["request_original_task_verbatim"] = task.contains("\n${options.task}\n")
                flags["request_slots_filled"] = !Regex("\\{\\{[A-Z_]+\\}\\}").containsMatchIn(task) && !Regex("\\{\\{[A-Z_]+\\}\\}").containsMatchIn(observation[0].asJsonObject.get("text").asString)
                flags["request_three_messages_no_history"] = messages.size() == 3
                flags["request_observation_exact"] = observation.size() == 2 && observation[0].asJsonObject.get("text").asString == expectedObservation
                flags["request_full_execution_tools_exact"] = value.getAsJsonArray("tools") == JsonParser.parseString(asset(version, "gui/tools.execution.json"))
                val profile = JsonParser.parseString(asset(version, "request-profiles.json")).asJsonObject
                flags["request_execution_profile_exact"] = profile.getAsJsonObject("common_request_body").entrySet().all { (name, expectedValue) ->
                    name == "model" || value.get(name) == expectedValue
                } && profile.getAsJsonObject("gui_body_overrides").entrySet().all { (name, expectedValue) -> value.get(name) == expectedValue }
                flags["request_single_current_image"] = imageParts.size == 1
                if (imageParts.size == 1) {
                    val part = imageParts.single().asJsonObject
                    val url = part.getAsJsonObject("image_url").get("url").asString
                    val prefix = "data:image/png;base64,"
                    flags["request_image_png_data_uri"] = url.startsWith(prefix)
                    flags["request_image_max_pixels_exact"] = part.get("max_pixels").asInt == 2_621_440
                    val decoded = if (url.startsWith(prefix)) java.util.Base64.getDecoder().decode(url.substring(prefix.length)) else byteArrayOf()
                    try { flags["request_image_matches_captured_png"] = decoded.contentEquals(request.frame.png) }
                    finally { decoded.fill(0) }
                }
                flags["request_inspection_completed"] = true
            } catch (_: Exception) { flags["request_inspection_completed"] = false }
            change(report) { it.copy(flags = it.flags + flags) }
            return real.exchange(endpoint, key, body)
        }
    }

    private fun asset(version: String, name: String): String =
        context.assets.open(PromptBundles.assetPath(version, name)).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** Fixed synthetic marker only. No OCR, picture, coordinates or sampled color leaves RAM. */
    private suspend fun fixtureBackgroundRendered(phone: HarnessAccessibilityService, controls: Controls, frame: ScreenFrame): Boolean {
        val bounds = withContext(Dispatchers.Main.immediate) {
            requireFixture(phone, controls)
            val root = phone.rootInActiveWindow ?: return@withContext null
            try { node(root, "fixture_scroll") { Rect().also(it::getBoundsInScreen) } }
            finally { root.recycle() }
        } ?: return false
        if (bounds.width() < 4 || bounds.height() < 4 || bounds.left < 0 || bounds.top < 0 || bounds.right > frame.width || bounds.bottom > frame.height) return false
        return withContext(Dispatchers.Default) {
            val bitmap = BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size) ?: return@withContext false
            try {
                bitmap.width == frame.width && bitmap.height == frame.height && listOf(1, 2, 3).all { quarter ->
                    val color = bitmap.getPixel(bounds.left + 2, bounds.top + bounds.height() * quarter / 4)
                    kotlin.math.abs(Color.red(color) - 0xed) <= 2 && kotlin.math.abs(Color.green(color) - 0xf2) <= 2 && kotlin.math.abs(Color.blue(color) - 0xf7) <= 2
                }
            } finally { bitmap.recycle() }
        }
    }

    /** Categories describe the model's statement, not a verified platform diagnosis. */
    private fun classifyMessage(message: String, outcome: String, report: (Result) -> Unit) {
        val text = message.lowercase(Locale.ROOT)
        fun mentions(vararg phrases: String) = phrases.any { text.contains(it) }
        val cannotView = mentions("cannot view", "can't view", "unable to view", "cannot directly view", "cannot see", "can't see", "unable to see", "cannot access the screenshot", "no screenshot", "no image provided", "screenshot is unavailable", "image is unavailable", "无法查看", "无法看到", "看不到", "无法读取截图", "未提供截图", "没有截图")
        val noControl = mentions("cannot interact", "can't interact", "unable to interact", "cannot directly interact", "cannot control", "can't control", "unable to control", "cannot operate", "can't operate", "unable to operate", "do not have the ability", "don't have the ability", "无法操作", "不能操作", "无法控制", "不能控制")
        val scope = mentions("not authorized", "unauthorized", "not in the authorized", "outside the authorized", "not in the list of task-authorized", "not permitted", "permission denied", "lack permission", "无权限", "未授权", "不在授权", "超出授权")
        val insufficient = mentions("insufficient evidence", "not enough evidence", "cannot confirm", "can't confirm", "unable to confirm", "cannot establish", "cannot determine", "unable to determine", "无法确认", "无法判断", "证据不足")
        val flags = mapOf(
            "message_stated_cannot_view_image" to cannotView,
            "message_stated_no_device_control" to noControl,
            "message_stated_authorized_scope" to scope,
            "message_stated_insufficient_evidence" to insufficient,
            "message_stated_other" to (!cannotView && !noControl && !scope && !insufficient),
            "message_contains_cannot_directly_view" to text.contains("cannot directly view"),
            "message_contains_cannot_directly_interact" to text.contains("cannot directly interact"),
            "message_contains_current_authorized_capabilities" to text.contains("current authorized capabilities"),
            "message_contains_supplied_observation_cannot_support" to text.contains("supplied observation cannot support a safe valid action"),
            "message_contains_screenshot_not_provided" to mentions("screenshot was not provided", "screenshot is not provided", "no screenshot was provided"),
            "message_contains_cannot_view_or_interact" to text.contains("cannot view or interact"),
        )
        change(report) { it.copy(finishMessageChars = message.length.coerceAtMost(100_000), flags = it.flags + flags,
            statedReason = if (outcome != "UNABLE") "NOT_APPLICABLE" else when {
                cannotView -> "CANNOT_VIEW_IMAGE"; noControl -> "NO_DEVICE_CONTROL"; scope -> "AUTHORIZED_SCOPE"; insufficient -> "INSUFFICIENT_EVIDENCE"; else -> "OTHER"
            }) }
    }

    private suspend fun controlTap(phone: HarnessAccessibilityService, controls: Controls, onResult: (Result) -> Unit) {
        val captured = capture(phone, controls, onResult, "control")
        val frame = captured.frame
        val pngDigest = digest(frame.png)
        try {
            val x = (captured.before.target.centerX().toLong() * 1000 / frame.width).toInt().coerceIn(0, 999)
            val y = (captured.before.target.centerY().toLong() * 1000 / frame.height).toInt().coerceIn(0, 999)
            val action = PhoneAction("tap", mapOf("x" to x, "y" to y))
            val originalArgs = action.arguments.toMap()
            val pointInside = captured.before.target.contains(x * frame.width / 1000, y * frame.height / 1000)
            flag(onResult, "control_point_inside_target", pointInside)
            if (!pointInside) fail("CONTROL_POINT_OUTSIDE_TARGET")
            if (state(phone, controls) != captured.before) fail("PRECONDITION_CHANGED_NOT_EXECUTED")
            flag(onResult, "control_frame_token_current_before_dispatch", frameCurrent(phone, captured))
            if (!frameCurrent(phone, captured)) fail("FRAME_TOKEN_OR_GEOMETRY_CHANGED")
            val native = perform(phone, controls, action, captured, onResult, modelAction = false)
            flag(onResult, "control_native_completed", native.status == "completed")
            flag(onResult, "control_original_action_unchanged", action.arguments == originalArgs)
            flag(onResult, "control_png_unchanged", matchesDigest(frame.png, pngDigest))
            if (native.status != "completed") fail("NATIVE_NOT_COMPLETED")
            currentCoroutineContext().ensureActive()
            delay(250)
            val after = state(phone, controls)
            flag(onResult, "control_counter_delta_one", after.count == captured.before.count + 1)
            flag(onResult, "control_other_fixture_state_unchanged", after.sameOtherState(captured.before))
        } finally {
            frame.png.fill(0)
            pngDigest.fill(0)
            phone.setCaptureEnabled(false)
        }
    }

    private suspend fun perform(phone: HarnessAccessibilityService, controls: Controls, action: PhoneAction,
        captured: Captured, onResult: (Result) -> Unit, modelAction: Boolean): ActionResult =
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            requireFixture(phone, controls)
            if (phone.inputInFlight) fail("INPUT_TERMINAL_UNCONFIRMED")
            change(onResult) { if (modelAction) it.copy(modelPerformCalls = it.modelPerformCalls + 1, code = "MODEL_NATIVE_PENDING")
                else it.copy(controlPerformCalls = it.controlPerformCalls + 1, code = "CONTROL_NATIVE_PENDING") }
            val result = try {
                withTimeout(5_000) {
                    // The model branch passes its exact PhoneAction and exact captured PNG; no snapping.
                    phone.perform(action, captured.frame, setOf(FIXTURE)) {
                        !controls.stopped.get() && phone.controls === controls && phone.connected && phone.captureEnabled &&
                            !HarnessAccessibilityService.sensitivePageVisible
                    }
                }
            } catch (_: TimeoutCancellationException) { fail("INPUT_TERMINAL_UNCONFIRMED") }
            flag(onResult, if (modelAction) "model_native_terminal_confirmed" else "control_native_terminal_confirmed", !phone.inputInFlight)
            if (phone.inputInFlight) fail("INPUT_TERMINAL_UNCONFIRMED")
            result
        }

    private data class Geometry(val width: Int, val height: Int, val rotation: Int, val displayId: Int)
    private data class Captured(val frame: ScreenFrame, val before: FixtureState, val geometry: Geometry,
        val stamp: Any, val generation: Long)
    private data class FixtureState(val target: Rect, val count: Int, val longCount: Int,
        val input: String, val saveState: String, val savedValue: String) {
        val isReset get() = count == 0 && longCount == 0 && input == "initial" &&
            saveState == "保存状态：未保存" && savedValue == "已保存内容：无"
        fun sameOtherState(other: FixtureState) = longCount == other.longCount && input == other.input &&
            saveState == other.saveState && savedValue == other.savedValue
    }
    private suspend fun capture(phone: HarnessAccessibilityService, controls: Controls, onResult: (Result) -> Unit, prefix: String): Captured =
        withContext(Dispatchers.Main.immediate) {
            requireFixture(phone, controls)
            if (phone.inputInFlight) fail("INPUT_TERMINAL_UNCONFIRMED")
            if (!phone.overlayStopAvailable && !phone.notificationControlAvailable) fail("STOP_ENTRY_UNAVAILABLE")
            val before = state(phone, controls)
            val geometry = geometry(phone)
            phone.setCaptureEnabled(true)
            change(onResult) { it.copy(screenshotCalls = it.screenshotCalls + 1) }
            val frame = phone.observe()
            try {
                requireFixture(phone, controls)
                flag(onResult, "${prefix}_target_and_count_stable_across_capture", state(phone, controls) == before)
                if (state(phone, controls) != before) fail("PRECONDITION_CHANGED_NOT_EXECUTED")
                val validGeometry = geometry == geometry(phone) && frame.width == geometry.width && frame.height == geometry.height &&
                    before.target.left >= 0 && before.target.top >= 0 && before.target.right <= frame.width && before.target.bottom <= frame.height
                flag(onResult, "${prefix}_frame_geometry_valid", validGeometry)
                if (!validGeometry) fail("FRAME_TOKEN_OR_GEOMETRY_CHANGED")
                Captured(frame, before, geometry, stamp(phone) ?: fail("FRAME_TOKEN_OR_GEOMETRY_CHANGED"), generation(phone))
            } catch (failure: Exception) { frame.png.fill(0); throw failure }
        }
    private fun frameCurrent(phone: HarnessAccessibilityService, captured: Captured): Boolean =
        phone.captureEnabled && captured.stamp === stamp(phone) && captured.generation == generation(phone) &&
            captured.geometry == geometry(phone) && captured.frame.width == captured.geometry.width && captured.frame.height == captured.geometry.height
    private fun stamp(phone: HarnessAccessibilityService): Any? = HarnessAccessibilityService::class.java.getDeclaredField("lastFrame").let {
        it.isAccessible = true; it.get(phone)
    }
    private fun generation(phone: HarnessAccessibilityService): Long = HarnessAccessibilityService::class.java.getDeclaredField("captureGeneration").let {
        it.isAccessible = true; (it.get(phone) as AtomicLong).get()
    }
    private fun geometry(phone: HarnessAccessibilityService): Geometry {
        val display = phone.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: fail("FRAME_TOKEN_OR_GEOMETRY_CHANGED")
        val bounds = phone.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return Geometry(bounds.width(), bounds.height(), display.rotation, display.displayId)
    }
    private suspend fun requireFixture(phone: HarnessAccessibilityService, controls: Controls) = withContext(Dispatchers.Main.immediate) {
        if (controls.stopped.get()) throw CancellationException()
        if (!phone.connected || HarnessAccessibilityService.instance !== phone || phone.controls !== controls ||
            HarnessAccessibilityService.sensitivePageVisible) fail("AUTHORIZATION_UNAVAILABLE")
        val root = phone.rootInActiveWindow ?: fail("FIXTURE_NOT_FOREGROUND")
        try { if (root.packageName?.toString() != FIXTURE) fail("FIXTURE_NOT_FOREGROUND") }
        finally { root.recycle() }
    }
    private suspend fun state(phone: HarnessAccessibilityService, controls: Controls): FixtureState = withContext(Dispatchers.Main.immediate) {
        requireFixture(phone, controls)
        val root = phone.rootInActiveWindow ?: fail("FIXTURE_NOT_FOREGROUND")
        try {
            if (root.packageName?.toString() != FIXTURE) fail("FIXTURE_NOT_FOREGROUND")
            val target = node(root, "fixture_click") { Rect().also(it::getBoundsInScreen) }
            if (target.isEmpty) fail("TARGET_NODE_UNAVAILABLE")
            FixtureState(target, counter(root, "fixture_click_count"), counter(root, "fixture_long_press_count"),
                text(root, "fixture_input"), text(root, "fixture_save_state"), text(root, "fixture_saved_value"))
        } finally { root.recycle() }
    }
    private fun counter(root: AccessibilityNodeInfo, id: String): Int = text(root, id).substringAfterLast('：').toIntOrNull()
        ?.takeIf { it >= 0 } ?: fail("FIXTURE_COUNTER_UNAVAILABLE")
    private fun text(root: AccessibilityNodeInfo, id: String) = node(root, id) { it.text?.toString().orEmpty() }
    private fun <T> node(root: AccessibilityNodeInfo, id: String, read: (AccessibilityNodeInfo) -> T): T {
        val nodes = root.findAccessibilityNodeInfosByViewId("$FIXTURE:id/$id")
        try {
            val target = nodes.singleOrNull { it.refresh() && it.isVisibleToUser && it.packageName?.toString() == FIXTURE }
                ?: fail("TARGET_NODE_UNAVAILABLE")
            return read(target)
        } finally { nodes.forEach { it.recycle() } }
    }
    private fun flag(onResult: (Result) -> Unit, key: String, value: Boolean) = change(onResult) { it.copy(flags = it.flags + (key to value)) }
    private fun change(onResult: (Result) -> Unit, transform: (Result) -> Result) { onResult(result.updateAndGet { previous -> transform(previous) }) }
    private fun ProtocolDiagnostic.safeFields(): Map<String, Any?> = mapOf(
        "reason" to reason.name,
        "finish_reason" to finishReason.name,
        "choice_count" to choiceCount,
        "tool_call_count" to toolCallCount,
        "content_shape" to contentShape.name,
        "content_chars" to contentChars,
        "reasoning_shape" to reasoningShape.name,
        "reasoning_chars" to reasoningChars,
        "tool_name" to toolName.name,
    )
    private class Controls(private val phone: HarnessAccessibilityService, private val gateway: QwenGateway, private val cancel: () -> Unit) : RuntimeControls {
        private val state = MutableStateFlow(RunSnapshot(state = RunState.AUTO, budget = 1,
            sessionId = UUID.randomUUID().toString(), message = "合成页面定位诊断 · 一次模型请求与一次独立控制点按"))
        override val snapshot: StateFlow<RunSnapshot> = state
        val stopped = AtomicBoolean(false)
        val stopCode = AtomicReference("CANCELLED")
        private val restore = AtomicReference<(() -> Unit)?>(null)
        override fun start(options: SessionOptions) = stop()
        override fun takeover() = stop()
        override fun giveBack() = stop()
        override fun retry() = stop()
        override fun stop() = abort("CANCELLED")
        override fun environmentUnavailable(code: String) = abort(code.takeIf { it in setOf("AUTHORIZATION_UNAVAILABLE", "STOP_ENTRY_UNAVAILABLE", "SERVICE_DISCONNECTED") } ?: "AUTHORIZATION_UNAVAILABLE")
        override fun recheck() { if (!phone.inputInFlight) restore.getAndSet(null)?.invoke() }
        fun abort(code: String) {
            if (!stopped.compareAndSet(false, true)) return
            stopCode.set(code)
            phone.setCaptureEnabled(false)
            state.value = state.value.copy(state = RunState.STOPPING, message = "停止诊断，等待真实输入终态")
            phone.showState(state.value)
            gateway.cancelAll()
            cancel()
        }
        suspend fun markAttempt() = withContext(Dispatchers.Main.immediate) {
            if (stopped.get()) throw CancellationException()
            state.value = state.value.copy(guiUsed = 1)
            phone.showState(state.value)
        }
        fun awaitRealTerminal(action: () -> Unit) {
            restore.set(action)
            state.value = state.value.copy(state = RunState.UNCERTAIN, message = "输入终态未确认；禁止新动作")
            phone.showState(state.value)
            recheck()
        }
    }
    private class Failure(val code: String) : Exception(code)
    private companion object {
        val RUNNING = AtomicBoolean(false)
        const val FIXTURE = "org.mobileharness.fixture"
        const val TASK = "Single-step synthetic fixture pointing diagnostic. The current MobileHarness Test screen is already open. Tap the button labeled 点击测试 exactly once using its position in the current screenshot. Return that button's native tap action as your next action. Do not finish, launch an app, edit text, long press, save, scroll, navigate, or perform any other operation."
        const val FINISH_TASK = "Synthetic fixture termination-protocol diagnostic. The current MobileHarness Test screen is already open and has just been reset. The only task is to observe that the visible click count is 0. No phone interaction is needed. If the visible click count is 0, return the native finish action with outcome completed and a brief message. Do not tap, launch an app, edit text, long press, save, scroll, navigate, or perform any other operation."
        val BLOCKED_CODES = setOf("INVALID_STATE", "SERVICE_DISCONNECTED", "SERVICE_NOT_IDLE", "KEY_MISSING", "HOME_UNAVAILABLE", "PRECONDITION_NOT_RESET", "TARGET_NODE_UNAVAILABLE", "STOP_ENTRY_UNAVAILABLE",
            "NO_MATCHING_SESSION", "LOCAL_TASK_UNAVAILABLE", "EMPTY_AUTHORIZED_SCOPE", "HOME_NOT_FOREGROUND")
        fun fail(code: String): Nothing = throw Failure(code)
        fun normalized(value: Any?): Int {
            val long = when (value) { is Int -> value.toLong(); is Long -> value; else -> fail("INVALID_OUTPUT") }
            return long.takeIf { it in 0..999 }?.toInt() ?: fail("INVALID_OUTPUT")
        }
        fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun matchesDigest(bytes: ByteArray, expected: ByteArray): Boolean {
            val actual = digest(bytes)
            return try { MessageDigest.isEqual(actual, expected) } finally { actual.fill(0) }
        }
        fun safeCode(failure: Exception): String {
            val allowed = BLOCKED_CODES + setOf("AUTHORIZATION_UNAVAILABLE", "FIXTURE_NOT_FOREGROUND", "FIXTURE_COUNTER_UNAVAILABLE",
                "PRECONDITION_CHANGED_NOT_EXECUTED", "FRAME_TOKEN_OR_GEOMETRY_CHANGED", "PAYLOAD_MUTATED", "INPUT_TERMINAL_UNCONFIRMED",
                "NATIVE_NOT_COMPLETED", "CONTROL_POINT_OUTSIDE_TARGET", "INVALID_OUTPUT", "API_ERROR", "TRANSPORT_ERROR", "SCREENSHOT_FAILED",
                "INPUT_FAILED", "INPUT_STATUS_UNKNOWN", "DIAGNOSTIC_FAILURE")
            return when (failure) { is Failure -> failure.code; is RunFailure -> failure.code; else -> "DIAGNOSTIC_FAILURE" }
                .takeIf { it in allowed } ?: "DIAGNOSTIC_FAILURE"
        }
    }
}
