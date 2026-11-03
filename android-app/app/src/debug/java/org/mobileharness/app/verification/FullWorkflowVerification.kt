package org.mobileharness.app.verification

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.mobileharness.app.HarnessApplication
import org.mobileharness.app.core.*
import org.mobileharness.app.model.ProtocolDiagnostic
import org.mobileharness.app.model.ProtocolReason
import org.mobileharness.app.model.QwenGateway
import org.mobileharness.app.platform.HarnessAccessibilityService
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The real Coordinator, request assembler, gateway and phone executor run the original task.
 * Diagnostic predicates never enter model history or change a returned action. No retries.
 * Node contents, bounds, coordinates, PNGs, model arguments and replies remain memory-only.
 */
class FullWorkflowVerification(context: Context) {
    private val context = context.applicationContext
    data class Result(
        val status: String = "RUNNING", val code: String = "PENDING", val state: String = "IDLE",
        val adapterVersion: String? = null,
        val sessionId: String = "", val guiUsed: Int = 0, val plannerUsed: Int = 0,
        val actualGuiCalls: Int = 0, val actualPlannerCalls: Int = 0,
        val observeCalls: Int = 0, val homeFrames: Int = 0, val fixtureFrames: Int = 0,
        val performCalls: Int = 0, val completedPerforms: Int = 0,
        val blockedAtGui: Int? = null, val blockedAtPlanner: Int? = null,
        val errorCategory: String? = null, val errorCode: String? = null, val protocolReason: String? = null,
        val endReason: String? = null, val endSource: String? = null,
        val flags: Map<String, Boolean> = emptyMap(),
        val calls: List<Map<String, Any?>> = emptyList(), val taps: List<Map<String, Any?>> = emptyList(),
    )
    private val result = AtomicReference(Result())
    val latest: Result get() = result.get()

    suspend fun run(onResult: (Result) -> Unit) {
        if (!RUNNING.compareAndSet(false, true)) {
            onResult(Result(status = "BLOCKED", code = "VERIFICATION_ALREADY_RUNNING")); return
        }
        result.set(Result())
        var live: HarnessAccessibilityService? = null
        var previous: RuntimeControls? = null
        var coordinator: Coordinator? = null
        var ownerScope: CoroutineScope? = null
        var gateway: QwenGateway? = null
        var phone: DiagnosticPhone? = null
        var oldFlags: Int? = null
        val deferredRestore = AtomicReference<(() -> Unit)?>(null)
        val done = CompletableDeferred<Unit>()
        val manualSeen = AtomicBoolean(false)
        try {
            val app = context as? HarnessApplication ?: fail("INVALID_STATE")
            val service = HarnessAccessibilityService.instance?.takeIf { it.connected } ?: fail("SERVICE_DISCONNECTED")
            live = service
            previous = withContext(Dispatchers.Main.immediate) {
                val old = service.controls ?: fail("INVALID_STATE")
                if (old !== app.runtime || old.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED) || service.inputInFlight) {
                    fail("SERVICE_NOT_IDLE")
                }
                if (HarnessAccessibilityService.sensitivePageVisible) fail("AUTHORIZATION_UNAVAILABLE")
                service.setCaptureEnabled(false)
                old
            }
            val profiles = withContext(Dispatchers.Default) {
                if (!app.profiles.hasKey("gui")) fail("KEY_MISSING")
                app.profiles.metadata("gui") to app.profiles.metadata("planner")
            }
            val model = QwenGateway(context, app.profiles::keyFor)
            gateway = model
            val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            ownerScope = mainScope
            val workflowAudit = DiagnosticAudit(app.audit, onResult, manualSeen)
            val workflowGateway = DiagnosticGateway(model, onResult)
            val servicePhone = DiagnosticPhone(service, onResult, manualSeen, done, deferredRestore)
            phone = servicePhone
            val runtime = Coordinator(mainScope, workflowGateway, servicePhone, workflowAudit)
            coordinator = runtime
            servicePhone.coordinator = runtime
            val home = withContext(Dispatchers.Main.immediate) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val activity = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
                    ?.takeIf { it.packageName != "android" && !it.name.contains("ResolverActivity") }
                    ?: fail("HOME_UNAVAILABLE")
                intent.component = ComponentName(activity.packageName, activity.name)
                intent.selector = null
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                servicePhone.home = activity.packageName
                service.controls = runtime
                oldFlags = service.serviceInfo.flags
                service.serviceInfo = service.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
                // A deterministic synthetic baseline is prepared before starting the task.
                context.startActivity(Intent("org.mobileharness.fixture.action.RESET")
                    .setClassName(FIXTURE, "$FIXTURE.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                intent
            }
            delay(650)
            val baseline = withContext(Dispatchers.Main.immediate) { servicePhone.fixtureStateForSetup() }
            flag(onResult, "fixture_reset_baseline", baseline.isReset)
            if (!baseline.isReset) fail("PRECONDITION_NOT_RESET")
            withContext(Dispatchers.Main.immediate) { context.startActivity(home) }
            delay(800) // Planned settling before task submission, not an observation retry.
            withContext(Dispatchers.Main.immediate) {
                servicePhone.requireHomeBeforeStart()
                if (!service.overlayStopAvailable && !service.notificationControlAvailable) fail("STOP_ENTRY_UNAVAILABLE")
                val options = SessionOptions(TASK, servicePhone.home, setOf(FIXTURE), true, BUDGET,
                    profiles.first, profiles.second, StartMode.HOME_AUTO, servicePhone.home,
                    mapOf(FIXTURE to "MobileHarness Test"))
                change(onResult) { it.copy(adapterVersion = options.adapterVersion) }
                runtime.start(options)
            }
            try { withTimeout(180_000) { done.await() } }
            catch (_: TimeoutCancellationException) {
                flag(onResult, "workflow_timeout", true)
                change(onResult) { it.copy(code = "WORKFLOW_TIMEOUT", status = "FAIL") }
                withContext(Dispatchers.Main.immediate) { runtime.stop() }
            }
            withContext(Dispatchers.Main.immediate) {
                if (runtime.snapshot.value.state == RunState.ENDED && !service.inputInFlight && !manualSeen.get()) {
                    val after = servicePhone.finalFixtureStateOrNull()
                    flag(onResult, "final_fixture_current", after != null)
                    after?.let {
                        flag(onResult, "final_click_count_is_one", it.count == 1)
                        flag(onResult, "final_other_fixture_state_unchanged", it.sameOtherState(baseline))
                    }
                }
            }
            val value = latest
            val noManual = !manualSeen.get()
            flag(onResult, "no_takeover_or_return", noManual)
            flag(onResult, "no_planner_request", value.actualPlannerCalls == 0 && value.plannerUsed == 0)
            flag(onResult, "gui_attempt_accounting_matches", value.actualGuiCalls == value.guiUsed && value.guiUsed <= BUDGET)
            flag(onResult, "no_retry_requested", value.flags["retry_requested"] != true)
            val mechanicalInvariants = value.flags["observed_png_geometry_valid"] == true && value.taps.all { tap ->
                listOf("same_captured_frame", "png_unchanged_before_dispatch", "png_unchanged_after_dispatch",
                    "original_action_unchanged", "native_completed", "native_terminal_confirmed").all { tap[it] == true }
            }
            val renderEvidenceSufficient = value.fixtureFrames == 0 || value.flags["fixture_static_background_rendered"] == true
            val contractInvariants = mechanicalInvariants && renderEvidenceSufficient
            flag(onResult, "execution_contract_invariants", contractInvariants)
            val achieved = latest.flags["final_click_count_is_one"] == true && latest.flags["final_other_fixture_state_unchanged"] == true
            val clean = noManual && value.actualPlannerCalls == 0 && value.plannerUsed == 0 && value.actualGuiCalls == value.guiUsed &&
                value.guiUsed <= BUDGET && value.flags["retry_requested"] != true && value.flags["workflow_timeout"] != true &&
                value.flags["diagnostic_report_write_failed"] != true && contractInvariants
            if (value.flags["workflow_timeout"] != true) change(onResult) { it.copy(
                status = if (clean && achieved && it.blockedAtGui == null && it.endReason == "model_declared_complete") "PASS" else "DIAGNOSED",
                code = when {
                    !renderEvidenceSufficient -> "FIXTURE_SCREENSHOT_RENDER_EVIDENCE_INSUFFICIENT"
                    !mechanicalInvariants -> "EXECUTION_CONTRACT_INVARIANT_FAILED"
                    !clean -> "WORKFLOW_CONDITION_INTERRUPTED"
                    it.blockedAtGui != null -> "WORKFLOW_BLOCKED_STOPPED_NO_RETRY"
                    it.endReason == "model_declared_complete" && achieved -> "FULL_WORKFLOW_CONFIRMED"
                    it.endReason == "model_declared_complete" -> "MODEL_ENDED_TASK_NOT_CONFIRMED"
                    it.endReason == "budget_exhausted" -> "SHARED_BUDGET_EXHAUSTED"
                    it.endReason == "model_unable" -> "MODEL_UNABLE_END"
                    else -> "WORKFLOW_STOPPED"
                }) }
        } catch (cancelled: CancellationException) {
            change(onResult) { it.copy(status = "CANCELLED", code = "CANCELLED") }
            throw cancelled
        } catch (failure: Exception) {
            val code = safeCode(failure)
            change(onResult) { it.copy(status = if (code in BLOCKED_CODES) "BLOCKED" else "FAIL", code = code) }
        } finally {
            withContext(NonCancellable) {
                val service = live
                val runtime = coordinator
                withContext(Dispatchers.Main.immediate) {
                    service?.setCaptureEnabled(false)
                    runtime?.stop()
                    gateway?.cancelAll()
                }
                val terminal = try {
                    withTimeout(8_000) {
                        while (service?.inputInFlight == true || (runtime != null && runtime.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED))) delay(20)
                    }
                    true
                } catch (_: TimeoutCancellationException) { false }
                withContext(Dispatchers.Main.immediate) {
                    service?.setCaptureEnabled(false)
                    service?.configureSession(null)
                    phone?.releaseFrame()
                    if (service != null) {
                        oldFlags?.let { saved ->
                            if (HarnessAccessibilityService.instance === service) {
                                try { service.serviceInfo = service.serviceInfo.apply { flags = saved }; flag(onResult, "service_flags_restored", true) }
                                catch (_: Exception) { flag(onResult, "service_flags_restored", false) }
                            }
                        }
                        val restore = {
                            if (previous != null && HarnessAccessibilityService.instance === service && service.controls === runtime && !service.inputInFlight &&
                                (runtime == null || runtime.snapshot.value.state in setOf(RunState.IDLE, RunState.ENDED))) {
                                service.controls = previous
                                service.showState(checkNotNull(previous).snapshot.value)
                                flag(onResult, "controls_restored", true)
                                flag(onResult, "native_terminal_confirmed", true)
                                ownerScope?.cancel()
                            }
                        }
                        if (terminal) restore()
                        else {
                            flag(onResult, "native_terminal_confirmed", false)
                            change(onResult) { it.copy(status = "UNCERTAIN", code = "INPUT_TERMINAL_UNCONFIRMED") }
                            deferredRestore.set(restore)
                            runtime?.recheck()
                        }
                    } else ownerScope?.cancel()
                    if (terminal && runtime != null && latest.status == "PASS" &&
                        (latest.flags["controls_restored"] != true || latest.flags["service_flags_restored"] != true)) {
                        change(onResult) { it.copy(status = "FAIL", code = "FINAL_STATE_RESTORE_FAILED") }
                    }
                }
                RUNNING.set(false)
                onResult(latest)
            }
        }
    }

    private inner class DiagnosticGateway(private val real: QwenGateway, private val report: (Result) -> Unit) : ModelGateway {
        private val ordinal = AtomicInteger()
        private var activeRole = "GUI"
        private var activeIndex = 0
        init {
            real.protocolDiagnosticObserver = { diagnostic ->
                callUpdate(report, activeRole, activeIndex, diagnostic.safeFields())
            }
        }
        override fun preflightGui(context: GuiContext) = real.preflightGui(context)
        override fun preflightPlanner(context: PlannerContext) = real.preflightPlanner(context)
        override suspend fun gui(context: GuiContext): GuiReply {
            val index = ordinal.incrementAndGet()
            activeRole = "GUI"; activeIndex = index
            change(report) { it.copy(actualGuiCalls = it.actualGuiCalls + 1) }
            callUpdate(report, "GUI", index, mapOf("status" to "STARTED"))
            try {
                val reply = real.gui(context)
                callUpdate(report, "GUI", index, mapOf("status" to "PARSED", "action" to actionName(reply.action.name)))
                return reply
            } catch (cancelled: CancellationException) {
                callUpdate(report, "GUI", index, mapOf("status" to "CANCELLED")); throw cancelled
            } catch (failure: Exception) {
                callUpdate(report, "GUI", index, mapOf("status" to "FAILED", "error_code" to safeCode(failure),
                    "error_protocol_reason" to safeProtocol((failure as? RunFailure)?.protocolReason)))
                throw failure
            }
        }
        override suspend fun planner(context: PlannerContext): PlannerDecision {
            activeRole = "PLANNER"; activeIndex = context.callIndex
            change(report) { it.copy(actualPlannerCalls = it.actualPlannerCalls + 1) }
            callUpdate(report, "PLANNER", context.callIndex, mapOf("status" to "STARTED"))
            return real.planner(context)
        }
        override fun cancelAll() = real.cancelAll()
    }

    private inner class DiagnosticAudit(private val real: AuditSink, private val report: (Result) -> Unit,
        private val manualSeen: AtomicBoolean) : AuditSink {
        override fun begin(sessionId: String, options: SessionOptions) {
            real.begin(sessionId, options)
            change(report) { it.copy(sessionId = sessionId) }
        }
        override fun event(event: String, data: Map<String, Any?>) {
            real.event(event, data) // The actual production minimum log remains authoritative.
            when (event) {
                "error" -> change(report) { it.copy(errorCategory = (data["category"] as? String)?.takeIf { it in setOf("MODEL", "DEVICE", "COORDINATOR") },
                    errorCode = (data["code"] as? String)?.takeIf { it in ERROR_CODES }, protocolReason = safeProtocol(data["protocol_reason"] as? String)) }
                "session_ended" -> change(report) { it.copy(endReason = (data["reason"] as? String)?.takeIf { it in END_REASONS },
                    endSource = (data["completion_source"] as? String)?.takeIf { it in setOf("none", "gui", "planner", "confirmation") }) }
                "control" -> when (data["transition"]) {
                    "takeover_requested", "manual_granted", "return_requested", "return_committed" -> { manualSeen.set(true); flag(report, "takeover_or_return_seen", true) }
                    "retry_requested" -> flag(report, "retry_requested", true)
                }
            }
        }
    }

    private inner class DiagnosticPhone(
        private val real: HarnessAccessibilityService, private val report: (Result) -> Unit,
        private val manualSeen: AtomicBoolean, private val done: CompletableDeferred<Unit>,
        private val deferredRestore: AtomicReference<(() -> Unit)?>,
    ) : PhonePort {
        lateinit var coordinator: Coordinator
        lateinit var home: String
        private var retained: FrameEvidence? = null
        override val connected get() = real.connected && HarnessAccessibilityService.instance === real
        override val inputInFlight get() = real.inputInFlight
        override fun configureSession(options: SessionOptions?) = real.configureSession(options)
        override fun setCaptureEnabled(enabled: Boolean) = real.setCaptureEnabled(enabled)
        override fun showState(snapshot: RunSnapshot) {
            real.showState(snapshot)
            change(report) { it.copy(state = snapshot.state.name, guiUsed = snapshot.guiUsed, plannerUsed = snapshot.plannerUsed) }
            when (snapshot.state) {
                RunState.BLOCKED -> {
                    if (latest.blockedAtGui == null) {
                        change(report) { it.copy(blockedAtGui = snapshot.guiUsed, blockedAtPlanner = snapshot.plannerUsed) }
                        flag(report, "stop_after_blocked_without_retry", true)
                        coordinator.stop()
                    }
                }
                RunState.MANUAL -> { manualSeen.set(true); real.setCaptureEnabled(false); coordinator.stop() }
                RunState.PAUSING -> if (manualSeen.get()) coordinator.stop()
                RunState.ENDED -> { done.complete(Unit); deferredRestore.getAndSet(null)?.invoke() }
                else -> Unit
            }
        }
        override suspend fun observe(): ScreenFrame = withContext(Dispatchers.Main.immediate) {
            requireAutomatic()
            // Do not demand a non-null root before the real executor's pending-launch
            // handshake. A legitimate startActivity transition can temporarily have no root.
            // This optional read inspects children only when the package is the fixture.
            val before = fixtureStateOrNull()
            releaseFrame()
            change(report) { it.copy(observeCalls = it.observeCalls + 1) }
            val frame = real.observe()
            try {
                requireAutomatic()
                val current = authorizedPackage()
                val after = if (current == FIXTURE) fixtureStateOrNull() else null
                val decoded = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size, decoded)
                flag(report, "observed_png_geometry_valid", latest.flags["observed_png_geometry_valid"] != false &&
                    decoded.outWidth == frame.width && decoded.outHeight == frame.height && decoded.outMimeType == "image/png")
                val backgroundRendered = if (current == FIXTURE) fixtureStaticBackgroundRendered(frame) else null
                backgroundRendered?.let { rendered ->
                    flag(report, "fixture_static_background_rendered", latest.flags["fixture_static_background_rendered"] != false && rendered)
                }
                change(report) { if (current == home) it.copy(homeFrames = it.homeFrames + 1) else it.copy(fixtureFrames = it.fixtureFrames + 1) }
                retained = FrameEvidence(frame, after, digest(frame.png), before?.let { it == after }, backgroundRendered)
                frame
            } catch (failure: Exception) { frame.png.fill(0); throw failure }
        }
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult =
            withContext(Dispatchers.Main.immediate) {
                requireAutomatic()
                val current = authorizedPackage()
                if (allowedPackages != setOf(FIXTURE)) throw RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE")
                change(report) { it.copy(performCalls = it.performCalls + 1) }
                val evidence = retained?.takeIf { it.frame === frame && it.frame.id == frame.id }
                val originalName = action.name
                val originalArgs = action.arguments.toMap()
                val before = if (action.name == "tap" && current == FIXTURE) fixtureStateOrNull() else null
                val x = coordinate(action.arguments["x"], frame.width)
                val y = coordinate(action.arguments["y"], frame.height)
                val rawX = normalizedOrNull(action.arguments["x"])
                val rawY = normalizedOrNull(action.arguments["y"])
                val tapIndex = if (action.name == "tap") latest.taps.size else -1
                val tap = if (tapIndex >= 0) mapOf<String, Any?>(
                    "ordinal" to tapIndex + 1, "gui_step" to coordinator.snapshot.value.guiUsed,
                    "same_captured_frame" to (evidence != null),
                    "target_available_before" to (before != null),
                    "point_inside_captured_target" to if (x != null && y != null) evidence?.fixture?.target?.contains(x, y) else null,
                    "point_inside_dispatch_target" to if (x != null && y != null) before?.target?.contains(x, y) else null,
                    "raw_arguments_as_pixels_inside_target" to if (rawX != null && rawY != null) evidence?.fixture?.target?.contains(rawX, rawY) else null,
                    "target_and_count_stable_since_capture" to (before != null && before == evidence?.fixture),
                    "target_and_count_stable_across_capture" to evidence?.stableAcrossCapture,
                    "fixture_static_background_rendered" to evidence?.backgroundRendered,
                    "png_unchanged_before_dispatch" to evidence?.let { matches(frame.png, it.digest) },
                ) else emptyMap()
                if (tapIndex >= 0) tapUpdate(report, tapIndex, tap)
                var outcome: ActionResult? = null
                try {
                    // Exactly the received PhoneAction, ScreenFrame and gate; no target correction.
                    val native = real.perform(action, frame, allowedPackages, canDispatch)
                    outcome = native
                    if (native.status == "completed") change(report) { it.copy(completedPerforms = it.completedPerforms + 1) }
                    native
                } finally {
                    if (tapIndex >= 0) {
                        val after = if (!real.inputInFlight && automatic() && !manualSeen.get()) fixtureStateOrNull() else null
                        tapUpdate(report, tapIndex, mapOf(
                            "native_completed" to (outcome?.status == "completed"),
                            "native_terminal_confirmed" to !real.inputInFlight,
                            "original_action_unchanged" to (action.name == originalName && action.arguments == originalArgs),
                            "png_unchanged_after_dispatch" to evidence?.let { matches(frame.png, it.digest) },
                            "target_available_after" to (after != null),
                            "counter_delta_one" to if (before != null && after != null) after.count == before.count + 1 else null,
                            "counter_unchanged" to if (before != null && after != null) after.count == before.count else null,
                            "other_fixture_state_unchanged" to if (before != null && after != null) after.sameOtherState(before) else null,
                        ))
                    }
                }
            }
        fun fixtureStateForSetup(): FixtureState {
            if (manualSeen.get() || real.inputInFlight || real.controls !== coordinator || !connected || HarnessAccessibilityService.sensitivePageVisible) fail("AUTHORIZATION_UNAVAILABLE")
            return fixtureStateOrNull() ?: fail("TARGET_NODE_UNAVAILABLE")
        }
        fun requireHomeBeforeStart() {
            if (real.controls !== coordinator || manualSeen.get() || !connected || authorizedPackage() != home) fail("HOME_NOT_FOREGROUND")
            flag(report, "started_from_resolved_home", true)
        }
        fun finalFixtureStateOrNull(): FixtureState? {
            if (manualSeen.get() || coordinator.snapshot.value.state != RunState.ENDED || real.inputInFlight || real.controls !== coordinator) return null
            return fixtureStateOrNull()
        }
        private fun automatic() = !manualSeen.get() && coordinator.snapshot.value.state in setOf(RunState.AUTO, RunState.CHECKING, RunState.PREPARING)
        private fun requireAutomatic() {
            if (!automatic() || real.controls !== coordinator || !connected || HarnessAccessibilityService.sensitivePageVisible) throw RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE")
        }
        private fun authorizedPackage(): String {
            if (manualSeen.get() || coordinator.snapshot.value.state == RunState.MANUAL || !connected || real.controls !== coordinator || HarnessAccessibilityService.sensitivePageVisible) {
                throw RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE")
            }
            val root = real.rootInActiveWindow ?: throw RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE")
            val current = try { root.packageName?.toString() } finally { root.recycle() }
            if (current !in setOf(home, FIXTURE)) {
                real.setCaptureEnabled(false)
                coordinator.environmentUnavailable("AUTHORIZATION_UNAVAILABLE")
                throw RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE")
            }
            return checkNotNull(current)
        }
        private fun fixtureStateOrNull(): FixtureState? {
            if (manualSeen.get() || coordinator.snapshot.value.state == RunState.MANUAL || !connected || real.controls !== coordinator || HarnessAccessibilityService.sensitivePageVisible) return null
            val root = real.rootInActiveWindow ?: return null
            try {
                if (root.packageName?.toString() != FIXTURE) return null
                val bounds = node(root, "fixture_click") { Rect().also(it::getBoundsInScreen) } ?: return null
                if (bounds.isEmpty) return null
                return FixtureState(bounds, count(root, "fixture_click_count") ?: return null,
                    count(root, "fixture_long_press_count") ?: return null, text(root, "fixture_input") ?: return null,
                    text(root, "fixture_save_state") ?: return null, text(root, "fixture_saved_value") ?: return null)
            } finally { root.recycle() }
        }
        private suspend fun fixtureStaticBackgroundRendered(frame: ScreenFrame): Boolean {
            // The fixture ScrollView is #EDF2F7. Its sole transparent child has 8dp
            // padding, so left+2 physical pixels lies outside all row text. This is a
            // synthetic rendering predicate, never a model observation or target correction.
            requireAutomatic()
            val root = real.rootInActiveWindow ?: return false
            val bounds = try {
                if (root.packageName?.toString() != FIXTURE) return false
                node(root, "fixture_scroll") { Rect().also(it::getBoundsInScreen) } ?: return false
            } finally { root.recycle() }
            if (bounds.width() < 4 || bounds.height() < 4 || bounds.left < 0 || bounds.top < 0 ||
                bounds.right > frame.width || bounds.bottom > frame.height) return false
            return withContext(Dispatchers.Default) {
                val bitmap = BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size) ?: return@withContext false
                try {
                    if (bitmap.width != frame.width || bitmap.height != frame.height) return@withContext false
                    val x = bounds.left + 2
                    listOf(1, 2, 3).all { quarter ->
                        val color = bitmap.getPixel(x, bounds.top + bounds.height() * quarter / 4)
                        kotlin.math.abs(Color.red(color) - 0xed) <= 2 &&
                            kotlin.math.abs(Color.green(color) - 0xf2) <= 2 &&
                            kotlin.math.abs(Color.blue(color) - 0xf7) <= 2
                    }
                } finally { bitmap.recycle() }
            }
        }
        private fun count(root: AccessibilityNodeInfo, id: String) = text(root, id)?.substringAfterLast('：')?.toIntOrNull()?.takeIf { it >= 0 }
        private fun text(root: AccessibilityNodeInfo, id: String) = node(root, id) { it.text?.toString().orEmpty() }
        private fun <T> node(root: AccessibilityNodeInfo, id: String, read: (AccessibilityNodeInfo) -> T): T? {
            val nodes = root.findAccessibilityNodeInfosByViewId("$FIXTURE:id/$id")
            try { return nodes.singleOrNull { it.refresh() && it.isVisibleToUser && it.packageName?.toString() == FIXTURE }?.let(read) }
            finally { nodes.forEach { it.recycle() } }
        }
        fun releaseFrame() {
            retained?.frame?.png?.fill(0)
            retained?.digest?.fill(0)
            retained = null
        }
    }

    private data class FrameEvidence(val frame: ScreenFrame, val fixture: FixtureState?, val digest: ByteArray,
        val stableAcrossCapture: Boolean?, val backgroundRendered: Boolean?)
    private data class FixtureState(val target: Rect, val count: Int, val longCount: Int, val input: String, val saveState: String, val savedValue: String) {
        val isReset get() = count == 0 && longCount == 0 && input == "initial" && saveState == "保存状态：未保存" && savedValue == "已保存内容：无"
        fun sameOtherState(other: FixtureState) = longCount == other.longCount && input == other.input && saveState == other.saveState && savedValue == other.savedValue
    }
    private fun ProtocolDiagnostic.safeFields(): Map<String, Any?> = mapOf(
        "protocol_reason" to reason.name, "finish_reason" to finishReason.name,
        "choice_count" to choiceCount, "tool_call_count" to toolCallCount,
        "content_shape" to contentShape.name, "content_chars" to contentChars,
        "reasoning_shape" to reasoningShape.name, "reasoning_chars" to reasoningChars, "tool_name" to toolName.name,
    )
    private fun callUpdate(report: (Result) -> Unit, role: String, index: Int, fields: Map<String, Any?>) = change(report) {
        val old = it.calls.indexOfFirst { call -> call["role"] == role && call["index"] == index }
        val calls = it.calls.toMutableList()
        if (old < 0) calls.add(mapOf("role" to role, "index" to index) + fields) else calls[old] = calls[old] + fields
        it.copy(calls = calls.toList())
    }
    private fun tapUpdate(report: (Result) -> Unit, index: Int, fields: Map<String, Any?>) = change(report) {
        val taps = it.taps.toMutableList()
        if (index == taps.size) taps.add(fields) else if (index in taps.indices) taps[index] = taps[index] + fields
        it.copy(taps = taps.toList())
    }
    private fun flag(report: (Result) -> Unit, key: String, value: Boolean) = change(report) { it.copy(flags = it.flags + (key to value)) }
    private fun change(report: (Result) -> Unit, transform: (Result) -> Result) {
        val value = result.updateAndGet { transform(it) }
        try { report(value) }
        catch (_: Exception) {
            // A diagnostic-file failure must not change a model reply or create a fake
            // executor/transport error. Keep the separate evidence fault in RAM.
            result.updateAndGet { it.copy(flags = it.flags + ("diagnostic_report_write_failed" to true)) }
        }
    }
    private class Failure(val code: String) : Exception(code)
    private companion object {
        val RUNNING = AtomicBoolean(false)
        const val FIXTURE = "org.mobileharness.fixture"
        const val BUDGET = 8
        const val TASK = "Open MobileHarness Test org.mobileharness.fixture. Tap the click test button exactly once so the visible click count becomes 1. Then finish. Do not edit text or long press or save or open any other App."
        val ERROR_CODES = setOf("TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT", "AUTHORIZATION_UNAVAILABLE", "SCREENSHOT_FAILED",
            "INPUT_FAILED", "INPUT_STATUS_UNKNOWN", "SERVICE_DISCONNECTED", "INVALID_STATE", "PAUSE_UNCONFIRMED", "STOP_ENTRY_UNAVAILABLE", "OBSERVATION_UNAVAILABLE")
        val BLOCKED_CODES = setOf("INVALID_STATE", "SERVICE_DISCONNECTED", "SERVICE_NOT_IDLE", "KEY_MISSING", "HOME_UNAVAILABLE", "HOME_NOT_FOREGROUND", "TARGET_NODE_UNAVAILABLE", "PRECONDITION_NOT_RESET", "STOP_ENTRY_UNAVAILABLE")
        val END_REASONS = setOf("model_declared_complete", "model_unable", "user_stop", "budget_exhausted", "interrupted")
        fun fail(code: String): Nothing = throw Failure(code)
        fun safeCode(failure: Exception): String = when (failure) { is Failure -> failure.code; is RunFailure -> failure.code; else -> "DIAGNOSTIC_FAILURE" }
            .takeIf { it in ERROR_CODES || it in BLOCKED_CODES || it == "DIAGNOSTIC_FAILURE" } ?: "DIAGNOSTIC_FAILURE"
        fun safeProtocol(reason: String?): String? = reason?.takeIf { candidate -> ProtocolReason.entries.any { it.name == candidate } }
        fun actionName(name: String): String = name.takeIf { it in setOf("tap", "long_press", "swipe", "input_text", "press_key", "launch_app", "wait", "finish") } ?: "UNKNOWN"
        fun normalizedOrNull(value: Any?): Int? = when (value) { is Int -> value.toLong(); is Long -> value; else -> null }?.takeIf { it in 0..999 }?.toInt()
        fun coordinate(value: Any?, dimension: Int): Int? = normalizedOrNull(value)?.let { (it.toLong() * dimension / 1000).toInt() }
        fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun matches(bytes: ByteArray, expected: ByteArray): Boolean {
            val actual = digest(bytes)
            return try { MessageDigest.isEqual(actual, expected) } finally { actual.fill(0) }
        }
    }
}
