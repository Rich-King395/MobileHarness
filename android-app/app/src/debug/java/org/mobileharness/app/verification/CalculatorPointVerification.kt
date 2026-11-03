package org.mobileharness.app.verification

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mobileharness.app.HarnessApplication
import org.mobileharness.app.core.*
import org.mobileharness.app.model.QwenGateway
import org.mobileharness.app.platform.HarnessAccessibilityService
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One native, unmodified model tap at most. No coordinates, images, text, or replies are exported. */
class CalculatorPointVerification(context: Context) {
    private val context = context.applicationContext
    data class Result(
        val status: String = "RUNNING", val code: String = "PENDING",
        val guiAttempts: Int = 0, val phonePerformCalls: Int = 0,
        val preconditionEmpty: Boolean? = null, val modelParsed: Boolean = false,
        val outputIsTap: Boolean? = null, val groundingHit: Boolean? = null,
        val notExecuted: Boolean? = true, val nativeCompleted: Boolean? = null,
        val formulaIs2: Boolean? = null, val nativeTerminalConfirmed: Boolean? = null,
        val controlsRestored: Boolean = false,
    )
    private val result = AtomicReference(Result())
    val latest: Result get() = result.get()

    suspend fun run(onResult: (Result) -> Unit) {
        if (!RUNNING.compareAndSet(false, true)) {
            onResult(Result(status = "BLOCKED", code = "VERIFICATION_ALREADY_RUNNING")); return
        }
        result.set(Result())
        var phone: HarnessAccessibilityService? = null
        var old: RuntimeControls? = null
        var temporary: Controls? = null
        var gateway: QwenGateway? = null
        var oldFlags: Int? = null
        try {
            val app = context as? HarnessApplication ?: throw Failure("INVALID_STATE")
            val service = HarnessAccessibilityService.instance?.takeIf { it.connected }
                ?: throw Failure("SERVICE_DISCONNECTED")
            phone = service
            old = withContext(Dispatchers.Main.immediate) {
                val current = service.controls ?: throw Failure("INVALID_STATE")
                if (current !== app.runtime || current.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED) || service.inputInFlight) {
                    throw Failure("SERVICE_NOT_IDLE")
                }
                service.setCaptureEnabled(false)
                current
            }
            val model = QwenGateway(context, app.profiles::keyFor)
            gateway = model
            supervisorScope {
                val active = AtomicReference<Job?>(null)
                val controls = Controls(service, model) { active.get()?.cancel() }
                temporary = controls
                var monitor: Job? = null
                val probe = async(start = CoroutineStart.LAZY) {
                    withContext(Dispatchers.Main.immediate) {
                        service.controls = controls
                        oldFlags = service.serviceInfo.flags
                        service.serviceInfo = service.serviceInfo.apply {
                            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        }
                    }
                    val gui = withContext(Dispatchers.Default) {
                        if (!app.profiles.hasKey("gui")) throw Failure("KEY_MISSING")
                        app.profiles.metadata("gui")
                    }
                    val options = SessionOptions(TASK, PACKAGE, setOf(PACKAGE), false, 1, gui,
                        ModelProfile(baseUrl = "https://invalid.example/v1", model = "unused"))
                    withContext(Dispatchers.Main.immediate) {
                        service.configureSession(options)
                        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
                            ?: throw Failure("CALCULATOR_NOT_LAUNCHABLE")
                        val component = launch.resolveActivity(context.packageManager)
                            ?: throw Failure("CALCULATOR_NOT_LAUNCHABLE")
                        if (component.packageName != PACKAGE) throw Failure("AUTHORIZATION_UNAVAILABLE")
                        launch.component = component
                        launch.selector = null
                        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    delay(650)
                    requireCalculator(service, controls)
                    monitor = launch(Dispatchers.Main.immediate) {
                        while (isActive && !controls.stopped.get()) {
                            try { requireCalculator(service, controls) }
                            catch (_: Exception) { controls.abort("AUTHORIZATION_UNAVAILABLE"); return@launch }
                            delay(100)
                        }
                    }
                    val empty = formulaEquals(service, controls, "")
                    change(onResult) { it.copy(preconditionEmpty = empty) }
                    if (!empty) throw Failure("PRECONDITION_FORMULA_NOT_EMPTY")
                    // Read the target bounds only for a local predicate. They never enter the request.
                    digitBounds(service, controls)
                    if (!service.overlayStopAvailable) throw Failure("STOP_ENTRY_UNAVAILABLE")
                    val frame = withContext(Dispatchers.Main.immediate) {
                        requireCalculator(service, controls)
                        service.setCaptureEnabled(true)
                        service.observe()
                    }
                    try {
                        val request = GuiContext(options, Phase.ORIGINAL, frame = frame)
                        requireCalculator(service, controls)
                        model.preflightGui(request)
                        currentCoroutineContext().ensureActive()
                        requireCalculator(service, controls)
                        controls.markAttempt()
                        change(onResult) { it.copy(guiAttempts = 1, code = "MODEL_PENDING") }
                        val reply = model.gui(request) // One request, frozen v1.1 execution tools.
                        val tap = reply.action.name == "tap"
                        change(onResult) { it.copy(modelParsed = true, outputIsTap = tap) }
                        if (!tap) throw Failure("NON_TAP_OUTPUT_NOT_EXECUTED")
                        val x = pixel(reply.action.arguments["x"], frame.width)
                        val y = pixel(reply.action.arguments["y"], frame.height)
                        val hit = digitBounds(service, controls).contains(x, y)
                        change(onResult) { it.copy(groundingHit = hit) }
                        if (!hit) throw Failure("GROUNDING_MISS")
                        if (!formulaEquals(service, controls, "")) throw Failure("PRECONDITION_CHANGED_NOT_EXECUTED")
                        currentCoroutineContext().ensureActive()
                        withContext(NonCancellable + Dispatchers.Main.immediate) {
                            requireCalculator(service, controls)
                            change(onResult) { it.copy(phonePerformCalls = 1, notExecuted = null, code = "NATIVE_PENDING") }
                            val native = try {
                                withTimeout(5_000) {
                                    // Exactly the original action object; never substitute the target's centre.
                                    service.perform(reply.action, frame, setOf(PACKAGE)) {
                                        !controls.stopped.get() && service.controls === controls && service.connected &&
                                            !HarnessAccessibilityService.sensitivePageVisible && service.captureEnabled
                                    }
                                }
                            } catch (_: TimeoutCancellationException) {
                                throw Failure("INPUT_TERMINAL_UNCONFIRMED")
                            }
                            change(onResult) { it.copy(notExecuted = false,
                                nativeCompleted = native.status == "completed",
                                nativeTerminalConfirmed = !service.inputInFlight) }
                            if (native.status != "completed") throw Failure("NATIVE_NOT_COMPLETED")
                        }
                        currentCoroutineContext().ensureActive()
                        delay(250) // A single post-action observation, no model/device retry.
                        val correct = formulaEquals(service, controls, "2")
                        change(onResult) { it.copy(formulaIs2 = correct) }
                        if (!correct) throw Failure("COMPLETED_FORMULA_NOT_2")
                        change(onResult) { it.copy(status = "PASS", code = "POINT_AND_EFFECT_CONFIRMED") }
                    } finally {
                        frame.png.fill(0)
                        service.setCaptureEnabled(false)
                        model.cancelAll()
                    }
                    monitor?.cancel()
                }
                active.set(probe)
                probe.start()
                try { probe.await() }
                catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    change(onResult) { it.copy(status = "CANCELLED", code = controls.stopCode.get()) }
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
            val blocked = code in setOf("SERVICE_NOT_IDLE", "SERVICE_DISCONNECTED", "KEY_MISSING", "INVALID_STATE",
                "PRECONDITION_FORMULA_NOT_EMPTY", "PRECONDITION_CHANGED_NOT_EXECUTED", "TARGET_NODE_UNAVAILABLE",
                "CALCULATOR_NOT_LAUNCHABLE", "STOP_ENTRY_UNAVAILABLE")
            change(onResult) { it.copy(status = if (blocked) "BLOCKED" else "FAIL", code = code) }
        } finally {
            withContext(NonCancellable) {
                gateway?.cancelAll()
                val service = phone
                val previous = old
                val controls = temporary
                if (service != null) {
                    service.setCaptureEnabled(false)
                    service.configureSession(null)
                    val terminal = try {
                        withTimeout(5_000) { while (service.inputInFlight) delay(20) }
                        true
                    } catch (_: TimeoutCancellationException) { false }
                    withContext(Dispatchers.Main.immediate) {
                        oldFlags?.let { flags ->
                            if (HarnessAccessibilityService.instance === service) {
                                try { service.serviceInfo = service.serviceInfo.apply { this.flags = flags } }
                                catch (_: Exception) {
                                    result.updateAndGet { it.copy(status = "FAIL", code = "SERVICE_INFO_RESTORE_FAILED") }
                                }
                            }
                        }
                        val restore = {
                            if (previous != null && HarnessAccessibilityService.instance === service && service.controls === controls && !service.inputInFlight) {
                                service.controls = previous
                                service.showState(previous.snapshot.value)
                                result.updateAndGet { it.copy(controlsRestored = true, nativeTerminalConfirmed = true) }
                            }
                            Unit
                        }
                        if (terminal) restore()
                        else {
                            change(onResult) { it.copy(status = "UNCERTAIN", code = "INPUT_TERMINAL_UNCONFIRMED", nativeTerminalConfirmed = false) }
                            controls?.awaitRealTerminal(restore)
                        }
                    }
                }
                RUNNING.set(false)
                onResult(latest)
            }
        }
    }

    private suspend fun requireCalculator(phone: HarnessAccessibilityService, controls: Controls) = withContext(Dispatchers.Main.immediate) {
        if (controls.stopped.get()) throw CancellationException()
        if (!phone.connected || HarnessAccessibilityService.instance !== phone || phone.controls !== controls || HarnessAccessibilityService.sensitivePageVisible) {
            throw Failure("AUTHORIZATION_UNAVAILABLE")
        }
        val root = phone.rootInActiveWindow ?: throw Failure("AUTHORIZATION_UNAVAILABLE")
        try { if (root.packageName?.toString() != PACKAGE) throw Failure("AUTHORIZATION_UNAVAILABLE") }
        finally { root.recycle() }
    }

    private suspend fun <T> node(phone: HarnessAccessibilityService, controls: Controls, id: String, read: (AccessibilityNodeInfo) -> T): T =
        withContext(Dispatchers.Main.immediate) {
            requireCalculator(phone, controls)
            val root = phone.rootInActiveWindow ?: throw Failure("TARGET_NODE_UNAVAILABLE")
            try {
                if (root.packageName?.toString() != PACKAGE) throw Failure("AUTHORIZATION_UNAVAILABLE")
                val nodes = root.findAccessibilityNodeInfosByViewId("$PACKAGE:id/$id")
                try {
                    val target = nodes.firstOrNull { it.refresh() && it.isVisibleToUser && it.packageName?.toString() == PACKAGE }
                        ?: throw Failure("TARGET_NODE_UNAVAILABLE")
                    read(target)
                } finally { nodes.forEach { it.recycle() } }
            } finally { root.recycle() }
        }
    private suspend fun formulaEquals(phone: HarnessAccessibilityService, controls: Controls, expected: String) =
        node(phone, controls, "formula") { it.text?.toString().orEmpty().trim() == expected }
    private suspend fun digitBounds(phone: HarnessAccessibilityService, controls: Controls): Rect =
        node(phone, controls, "digit_2") { Rect().also(it::getBoundsInScreen) }.also {
            if (it.isEmpty) throw Failure("TARGET_NODE_UNAVAILABLE")
        }
    private fun change(onResult: (Result) -> Unit, transform: (Result) -> Result) {
        onResult(result.updateAndGet { previous -> transform(previous) })
    }

    private class Controls(private val phone: HarnessAccessibilityService, private val gateway: QwenGateway, private val cancel: () -> Unit) : RuntimeControls {
        private val state = MutableStateFlow(RunSnapshot(state = RunState.PREPARING, budget = 1,
            sessionId = UUID.randomUUID().toString(), message = "计算器单步定位诊断；最多派发一次命中的点按"))
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
            state.value = state.value.copy(state = RunState.STOPPING, message = "停止单步诊断，等待真实输入终态")
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
        const val PACKAGE = "com.google.android.calculator"
        const val TASK = "Single-step Calculator pointing diagnostic: tap the digit 2 key exactly once on the current main Calculator page. Return the native tap action for that key. Do not clear input, compute an expression, open history, navigate, or perform any other operation."
        fun pixel(value: Any?, dimension: Int): Int {
            val normalized = when (value) { is Int -> value.toLong(); is Long -> value; else -> throw Failure("INVALID_OUTPUT") }
            if (normalized !in 0..999 || dimension <= 0) throw Failure("INVALID_OUTPUT")
            return (normalized * dimension / 1000).toInt()
        }
        fun safeCode(failure: Exception): String {
            val code = when (failure) { is Failure -> failure.code; is RunFailure -> failure.code; else -> "DIAGNOSTIC_FAILURE" }
            val allowed = setOf("INVALID_STATE", "SERVICE_DISCONNECTED", "SERVICE_NOT_IDLE", "KEY_MISSING", "CALCULATOR_NOT_LAUNCHABLE",
                "AUTHORIZATION_UNAVAILABLE", "STOP_ENTRY_UNAVAILABLE", "TARGET_NODE_UNAVAILABLE", "PRECONDITION_FORMULA_NOT_EMPTY",
                "PRECONDITION_CHANGED_NOT_EXECUTED", "NON_TAP_OUTPUT_NOT_EXECUTED", "GROUNDING_MISS", "INPUT_TERMINAL_UNCONFIRMED",
                "NATIVE_NOT_COMPLETED", "COMPLETED_FORMULA_NOT_2", "INVALID_OUTPUT", "API_ERROR", "TRANSPORT_ERROR", "SCREENSHOT_FAILED",
                "INPUT_FAILED", "INPUT_STATUS_UNKNOWN", "DIAGNOSTIC_FAILURE")
            val safe = code.takeIf { it in allowed } ?: "DIAGNOSTIC_FAILURE"
            val http = (failure as? RunFailure)?.httpStatus?.takeIf { it in 100..599 }
            return if (safe == "API_ERROR" && http != null) "API_ERROR_HTTP_$http" else safe
        }
    }
}
