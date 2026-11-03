package org.mobileharness.app.verification

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.mobileharness.app.HarnessApplication
import org.mobileharness.app.core.*
import org.mobileharness.app.model.*
import org.mobileharness.app.platform.PlatformDiagnosticObserver
import java.util.Base64
import java.util.UUID
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
class CalculatorWorkflowVerification(context: Context) {
    private val context = context.applicationContext
    data class Result(
        val status: String = "RUNNING", val code: String = "PENDING", val state: String = "IDLE",
        val adapterVersion: String? = null,
        val runId: String = UUID.randomUUID().toString(), val sessionId: String = "", val guiUsed: Int = 0, val plannerUsed: Int = 0,
        val actualGuiCalls: Int = 0, val actualPlannerCalls: Int = 0,
        val observeCalls: Int = 0, val homeFrames: Int = 0, val calculatorFrames: Int = 0,
        val performCalls: Int = 0, val completedPerforms: Int = 0,
        val blockedAtGui: Int? = null, val blockedAtPlanner: Int? = null,
        val errorCategory: String? = null, val errorCode: String? = null, val protocolReason: String? = null,
        val endReason: String? = null, val endSource: String? = null,
        val flags: Map<String, Boolean> = emptyMap(),
        val frames: List<Map<String, Any?>> = emptyList(),
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
        var previousObserver: PlatformDiagnosticObserver? = null
        val fidelity = CalculatorFidelityEvidence()
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
            val transport = FidelityTransport(onResult)
            val model = QwenGateway(
                { name -> context.assets.open(PromptBundles.assetPath("1.1.0", name)).bufferedReader(Charsets.UTF_8).use { it.readText() } },
                transport,
                { name -> context.assets.open(PromptBundles.assetPath("1.2.0", name)).bufferedReader(Charsets.UTF_8).use { it.readText() } },
                app.profiles::keyFor)
            previousObserver = service.diagnosticObserver
            service.diagnosticObserver = fidelity
            gateway = model
            val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            ownerScope = mainScope
            val workflowAudit = DiagnosticAudit(app.audit, onResult, manualSeen)
            val workflowGateway = DiagnosticGateway(model, transport, onResult)
            val servicePhone = DiagnosticPhone(service, fidelity, onResult, manualSeen, done, deferredRestore)
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
                val calculator = context.packageManager.getLaunchIntentForPackage(CALCULATOR)
                    ?: fail("CALCULATOR_NOT_LAUNCHABLE")
                val component = calculator.resolveActivity(context.packageManager)
                    ?.takeIf { it.packageName == CALCULATOR } ?: fail("CALCULATOR_NOT_LAUNCHABLE")
                calculator.component = component
                calculator.selector = null
                context.startActivity(calculator.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                intent
            }
            delay(800)
            val baseline = withContext(Dispatchers.Main.immediate) { servicePhone.calculatorStateForSetup() }
            flag(onResult, "precondition_formula_empty", baseline.formula.isEmpty() && baseline.preview.isEmpty())
            if (baseline.formula.isNotEmpty() || baseline.preview.isNotEmpty()) fail("PRECONDITION_NOT_EMPTY")
            withContext(Dispatchers.Main.immediate) { context.startActivity(home) }
            delay(800) // Planned settling before task submission, not an observation retry.
            withContext(Dispatchers.Main.immediate) {
                servicePhone.requireHomeBeforeStart()
                if (!service.overlayStopAvailable && !service.notificationControlAvailable) fail("STOP_ENTRY_UNAVAILABLE")
                val options = SessionOptions(TASK, servicePhone.home, setOf(CALCULATOR), true, BUDGET,
                    profiles.first, profiles.second, StartMode.HOME_AUTO, servicePhone.home,
                    mapOf(CALCULATOR to "Calculator"))
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
                    val after = servicePhone.finalCalculatorStateOrNull()
                    flag(onResult, "final_calculator_current", after != null)
                    after?.let {
                        flag(onResult, "final_result_is_420", it.formula == "420" || it.preview == "420")
                        flag(onResult, "final_formula_empty", it.formula.isEmpty())
                    }
                }
            }
            val value = latest
            flag(onResult, "no_takeover_or_return", !manualSeen.get())
            flag(onResult, "no_planner_request", value.actualPlannerCalls == 0 && value.plannerUsed == 0)
            flag(onResult, "gui_attempt_accounting_matches", value.actualGuiCalls == value.guiUsed && value.guiUsed <= BUDGET)
            flag(onResult, "no_retry_requested", value.flags["retry_requested"] != true)
            if (value.flags["workflow_timeout"] != true) change(onResult) { it.copy(
                status = "DIAGNOSED",
                code = when {
                    it.blockedAtGui != null -> "WORKFLOW_BLOCKED_STOPPED_NO_RETRY"
                    it.endReason == "model_declared_complete" && latest.flags["final_result_is_420"] == true -> "MODEL_ENDED_RESULT_CONFIRMED"
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
                    if (runtime != null && service?.controls === runtime) service.setCaptureEnabled(false)
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
                    if (runtime != null && service?.controls === runtime) {
                        service.setCaptureEnabled(false)
                        service.configureSession(null)
                    }
                    phone?.releaseFrame()
                    if (service?.diagnosticObserver === fidelity) service.diagnosticObserver = previousObserver
                    fidelity.clear()
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
                    if (terminal && runtime != null && latest.status == "DIAGNOSED" &&
                        (latest.flags["controls_restored"] != true || latest.flags["service_flags_restored"] != true)) {
                        change(onResult) { it.copy(status = "FAIL", code = "FINAL_STATE_RESTORE_FAILED") }
                    }
                }
                RUNNING.set(false)
                onResult(latest)
            }
        }
    }

    private inner class DiagnosticGateway(private val real: QwenGateway, private val transport: FidelityTransport, private val report: (Result) -> Unit) : ModelGateway {
        private val ordinal = AtomicInteger()
        private var activeRole = "GUI"
        private var activeIndex = 0
        init {
            real.protocolDiagnosticObserver = { diagnostic ->
                callUpdate(report, activeRole, activeIndex, diagnostic.safeFields())
            }
        }
        override fun preflightGui(context: GuiContext) {
            transport.frame = context.frame
            real.preflightGui(context)
        }
        override fun preflightPlanner(context: PlannerContext) = real.preflightPlanner(context)
        override suspend fun gui(context: GuiContext): GuiReply {
            val index = ordinal.incrementAndGet()
            activeRole = "GUI"; activeIndex = index
            transport.activeIndex = index
            transport.frame = context.frame
            change(report) { it.copy(actualGuiCalls = it.actualGuiCalls + 1) }
            callUpdate(report, "GUI", index, mapOf("status" to "STARTED"))
            try {
                val reply = real.gui(context)
                callUpdate(report, "GUI", index, mapOf("status" to "PARSED", "action" to actionName(reply.action.name)) + transport.compareReply(reply))
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
        override fun cancelAll() { transport.clear(); real.cancelAll() }
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
        private val real: HarnessAccessibilityService, private val fidelity: CalculatorFidelityEvidence, private val report: (Result) -> Unit,
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
            // No root requirement before the production pending-launch handshake.
            val before = calculatorStateOrNull()
            releaseFrame()
            change(report) { it.copy(observeCalls = it.observeCalls + 1) }
            fidelity.resetImage()
            val frame = real.observe()
            try {
                requireAutomatic()
                val current = authorizedPackage()
                val after = if (current == CALCULATOR) calculatorStateOrNull() else null
                val decoded = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size, decoded)
                val geometry = decoded.outWidth == frame.width && decoded.outHeight == frame.height && decoded.outMimeType == "image/png"
                val raster = if (after != null) CalculatorStableRasterProbe.compare(real, frame,
                    after.formulaBounds, after.keys.values) {
                    automatic() && real.controls === coordinator && connected && !HarnessAccessibilityService.sensitivePageVisible
                } else emptyMap()
                requireAutomatic()
                val afterReference = if (current == CALCULATOR) calculatorStateOrNull() else null
                flag(report, "observed_png_geometry_valid", latest.flags["observed_png_geometry_valid"] != false && geometry)
                change(report) { if (current == home) it.copy(homeFrames = it.homeFrames + 1) else it.copy(calculatorFrames = it.calculatorFrames + 1) }
                change(report) { it.copy(frames = it.frames + (mapOf("ordinal" to it.observeCalls,
                    "package_class" to if (current == home) "HOME" else "CALCULATOR",
                    "png_geometry_valid" to geometry,
                    "calculator_nodes_available" to (after != null),
                    "calculator_state_stable_across_capture" to before?.let { old -> old == after },
                    "calculator_state_stable_across_reference" to after?.let { old -> old == afterReference }) + fidelity.latestImage() + raster)) }
                retained = FrameEvidence(frame, after, digest(frame.png), before?.let { it == after }, fidelity.latestImage())
                frame
            } catch (failure: Exception) { frame.png.fill(0); throw failure }
        }
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult =
            withContext(Dispatchers.Main.immediate) {
                requireAutomatic()
                val current = authorizedPackage()
                if (allowedPackages != setOf(CALCULATOR)) throw RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE")
                change(report) { it.copy(performCalls = it.performCalls + 1) }
                val evidence = retained?.takeIf { it.frame === frame && it.frame.id == frame.id }
                val originalName = action.name
                val originalArgs = action.arguments.toMap()
                val before = if (action.name == "tap" && current == CALCULATOR) calculatorStateOrNull() else null
                val x = coordinate(action.arguments["x"], frame.width)
                val y = coordinate(action.arguments["y"], frame.height)
                val rawX = normalizedOrNull(action.arguments["x"])
                val rawY = normalizedOrNull(action.arguments["y"])
                val tapIndex = if (action.name == "tap") latest.taps.size else -1
                val capturedKey = evidence?.calculator?.keyAt(x, y)
                val currentKey = before?.keyAt(x, y)
                val tap = if (tapIndex >= 0) mapOf<String, Any?>(
                    "ordinal" to tapIndex + 1, "gui_step" to coordinator.snapshot.value.guiUsed,
                    "same_captured_frame" to (evidence != null),
                    "calculator_available_before" to (before != null),
                    "normalized_point_captured_key" to (capturedKey ?: "OTHER"),
                    "normalized_point_dispatch_key" to (currentKey ?: "OTHER"),
                    "raw_arguments_as_pixels_key" to (evidence?.calculator?.keyAt(rawX, rawY) ?: "OTHER"),
                    "calculator_state_stable_since_capture" to (before != null && before == evidence?.calculator),
                    "calculator_state_stable_across_capture" to evidence?.stableAcrossCapture,
                    "png_unchanged_before_dispatch" to evidence?.let { matches(frame.png, it.digest) },
                ) + evidence?.image.orEmpty() else emptyMap()
                if (tapIndex >= 0) tapUpdate(report, tapIndex, tap)
                var outcome: ActionResult? = null
                fidelity.beginAction(action, frame)
                try {
                    // Diagnostic membership never changes, withholds, or snaps a legal action.
                    val native = real.perform(action, frame, allowedPackages, canDispatch)
                    outcome = native
                    if (native.status == "completed") change(report) { it.copy(completedPerforms = it.completedPerforms + 1) }
                    native
                } finally {
                    val nativeEvidence = fidelity.endAction()
                    if (tapIndex >= 0) {
                        // Immediate local read: do not delay the next production capture to hide rendering timing defects.
                        val after = if (!real.inputInFlight && automatic() && !manualSeen.get()) calculatorStateOrNull() else null
                        val digit = currentKey?.takeIf { it.matches(Regex("D[0-9]")) }?.last()?.toString()
                        val append = if (digit != null && before != null && after != null) after.formula == before.formula + digit else null
                        tapUpdate(report, tapIndex, mapOf(
                            "native_completed" to (outcome?.status == "completed"),
                            "native_terminal_confirmed" to !real.inputInFlight,
                            "original_action_unchanged" to (action.name == originalName && action.arguments == originalArgs),
                            "png_unchanged_after_dispatch" to evidence?.let { matches(frame.png, it.digest) },
                            "calculator_available_after" to (after != null),
                            "formula_changed" to if (before != null && after != null) before.formula != after.formula else null,
                            "digit_append_expected_effect" to append,
                            "formula_equals_selected_digit" to if (digit != null && after != null) after.formula == digit else null,
                        ) + nativeEvidence)
                    }
                }
            }
        fun calculatorStateForSetup(): CalculatorState {
            if (manualSeen.get() || real.inputInFlight || real.controls !== coordinator || !connected || HarnessAccessibilityService.sensitivePageVisible) fail("AUTHORIZATION_UNAVAILABLE")
            return calculatorStateOrNull() ?: fail("TARGET_NODE_UNAVAILABLE")
        }
        fun requireHomeBeforeStart() {
            if (real.controls !== coordinator || manualSeen.get() || !connected || authorizedPackage() != home) fail("HOME_NOT_FOREGROUND")
            flag(report, "started_from_resolved_home", true)
        }
        fun finalCalculatorStateOrNull(): CalculatorState? {
            if (manualSeen.get() || coordinator.snapshot.value.state != RunState.ENDED || real.inputInFlight || real.controls !== coordinator) return null
            return calculatorStateOrNull()
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
            if (current !in setOf(home, CALCULATOR)) {
                real.setCaptureEnabled(false)
                coordinator.environmentUnavailable("AUTHORIZATION_UNAVAILABLE")
                throw RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE")
            }
            return checkNotNull(current)
        }
        private fun calculatorStateOrNull(): CalculatorState? {
            if (manualSeen.get() || coordinator.snapshot.value.state in setOf(RunState.MANUAL, RunState.PAUSING) || !connected || real.controls !== coordinator || HarnessAccessibilityService.sensitivePageVisible) return null
            val root = real.rootInActiveWindow ?: return null
            try {
                if (root.packageName?.toString() != CALCULATOR) return null
                val formula = text(root, "formula") ?: return null
                val formulaBounds = node(root, "formula") { Rect().also(it::getBoundsInScreen) } ?: return null
                val preview = text(root, "result_preview") ?: text(root, "result_final").orEmpty()
                val keys = KEY_IDS.mapNotNull { (id, label) ->
                    node(root, id) { Rect().also(it::getBoundsInScreen) }?.takeUnless { it.isEmpty }?.let { label to it }
                }.toMap()
                if (keys.keys.count { it.matches(Regex("D[0-9]")) } < 10) return null
                return CalculatorState(keys, formulaBounds, formula, preview)
            } finally { root.recycle() }
        }
        private fun text(root: AccessibilityNodeInfo, id: String) = node(root, id) { normalize(it.text?.toString().orEmpty()) }
        private fun <T> node(root: AccessibilityNodeInfo, id: String, read: (AccessibilityNodeInfo) -> T): T? {
            val nodes = root.findAccessibilityNodeInfosByViewId("$CALCULATOR:id/$id")
            try { return nodes.singleOrNull { it.refresh() && it.isVisibleToUser && it.packageName?.toString() == CALCULATOR }?.let(read) }
            finally { nodes.forEach { it.recycle() } }
        }
        fun releaseFrame() {
            retained?.frame?.png?.fill(0)
            retained?.digest?.fill(0)
            retained = null
        }
    }

    private data class FrameEvidence(val frame: ScreenFrame, val calculator: CalculatorState?, val digest: ByteArray,
        val stableAcrossCapture: Boolean?, val image: Map<String, Any?>)
    private data class CalculatorState(val keys: Map<String, Rect>, val formulaBounds: Rect, val formula: String, val preview: String) {
        fun keyAt(x: Int?, y: Int?): String? = if (x == null || y == null) null else keys.entries.singleOrNull { it.value.contains(x, y) }?.key
    }

    /** Same one-shot transport; added checks do not mutate any body/response/endpoint/key. */
    private inner class FidelityTransport(private val report: (Result) -> Unit) : ModelTransport {
        private val real = OkHttpModelTransport()
        var frame: ScreenFrame? = null
        var activeIndex: Int = 0
        private var rawName: String? = null
        private var rawArguments: String? = null
        override suspend fun exchange(endpoint: String, key: String, body: ByteArray): ByteArray {
            clearReply()
            val imageEqual = runCatching {
                val request = StrictJson.parse(body.toString(Charsets.UTF_8)).asJsonObject
                val last = request.getAsJsonArray("messages").last().asJsonObject
                val images = last.getAsJsonArray("content").filter { it.asJsonObject.get("type")?.asString == "image_url" }
                if (images.size != 1) false else {
                    val url = images.single().asJsonObject.getAsJsonObject("image_url").get("url").asString
                    if (!url.startsWith("data:image/png;base64,")) false else {
                        val decoded = Base64.getDecoder().decode(url.substringAfter(','))
                        try { frame?.png?.contentEquals(decoded) == true } finally { decoded.fill(0) }
                    }
                }
            }.getOrNull()
            callUpdate(report, "GUI", activeIndex, mapOf("request_image_equals_frame_png" to imageEqual))
            val bytes = real.exchange(endpoint, key, body)
            try {
                runCatching {
                    val response = StrictJson.parse(bytes.toString(Charsets.UTF_8)).asJsonObject
                    val function = response.getAsJsonArray("choices").single().asJsonObject.getAsJsonObject("message")
                        .getAsJsonArray("tool_calls").single().asJsonObject.getAsJsonObject("function")
                    rawName = function.get("name").asString
                    rawArguments = function.get("arguments").asString
                }.onFailure { clearReply() }
                return bytes
            } catch (failure: Exception) { bytes.fill(0); throw failure }
        }
        fun compareReply(reply: GuiReply): Map<String, Any?> {
            val same = runCatching {
                rawName == reply.action.name && rawArguments == reply.argumentsJson &&
                    StrictJson.parse(checkNotNull(rawArguments)) == StrictJson.parse(reply.argumentsJson) &&
                    StrictJson.parse(reply.argumentsJson).asJsonObject.entrySet().associate { (k, v) ->
                        k to if (v.asJsonPrimitive.isNumber) v.asBigDecimal.intValueExact() else v.asString
                    } == reply.action.arguments
            }.getOrNull()
            clearReply()
            return mapOf("raw_reply_equals_parsed_action" to same)
        }
        fun clear() { frame = null; clearReply() }
        private fun clearReply() { rawName = null; rawArguments = null }
        override fun cancelAll() { real.cancelAll(); clear() }
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
        const val CALCULATOR = "com.google.android.calculator"
        const val BUDGET = 30
        const val TASK = "Open the authorized Calculator App. Use the calculator to add 23 and 47 then multiply that sum by 6. Leave the final result visible and finish. Do not open other Apps."
        val ERROR_CODES = setOf("TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT", "AUTHORIZATION_UNAVAILABLE", "SCREENSHOT_FAILED",
            "INPUT_FAILED", "INPUT_STATUS_UNKNOWN", "SERVICE_DISCONNECTED", "INVALID_STATE", "PAUSE_UNCONFIRMED", "STOP_ENTRY_UNAVAILABLE", "OBSERVATION_UNAVAILABLE")
        val BLOCKED_CODES = setOf("INVALID_STATE", "SERVICE_DISCONNECTED", "SERVICE_NOT_IDLE", "KEY_MISSING", "HOME_UNAVAILABLE", "HOME_NOT_FOREGROUND", "TARGET_NODE_UNAVAILABLE", "PRECONDITION_NOT_EMPTY", "CALCULATOR_NOT_LAUNCHABLE", "STOP_ENTRY_UNAVAILABLE")
        val KEY_IDS = (0..9).associate { "digit_$it" to "D$it" } + mapOf("op_add" to "ADD", "op_sub" to "SUB",
            "op_mul" to "MUL", "op_div" to "DIV", "eq" to "EQUAL", "clr" to "CLEAR", "del" to "DELETE",
            "dec_point" to "DECIMAL", "parens" to "PARENS", "lparen" to "LPAREN", "rparen" to "RPAREN", "op_pct" to "PERCENT")
        fun normalize(text: String) = text.filterNot { it in listOf('\u200e', '\u200f', '\u202a', '\u202b', '\u202c', '\u2066', '\u2067', '\u2069', ',', ' ') }.trim()
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
