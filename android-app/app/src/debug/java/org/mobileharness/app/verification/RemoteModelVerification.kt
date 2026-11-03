package org.mobileharness.app.verification

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mobileharness.app.HarnessApplication
import org.mobileharness.app.core.*
import org.mobileharness.app.model.QwenGateway
import org.mobileharness.app.platform.HarnessAccessibilityService
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only, at most one GUI and one Planner request. Never executes any returned output. */
class RemoteModelVerification(context: Context) {
    private val context = context.applicationContext
    private val guiCount = AtomicInteger()
    private val plannerCount = AtomicInteger()
    val guiAttempts: Int get() = guiCount.get()
    val plannerAttempts: Int get() = plannerCount.get()
    val attemptCounts: Map<String, Int> get() = mapOf("GUI" to guiAttempts, "PLANNER" to plannerAttempts)

    /** accepted means this adapter parsed the response, never task success or phone execution. */
    suspend fun runAll(plannerOnly: Boolean = false, onResult: (role: String, accepted: Boolean, code: String, decision: String?) -> Unit) {
        if (!RUNNING.compareAndSet(false, true)) {
            onResult(if (plannerOnly) "PLANNER" else "GUI", false, "VERIFICATION_ALREADY_RUNNING", null)
            return
        }
        guiCount.set(0)
        plannerCount.set(0)
        val role = AtomicReference(if (plannerOnly) "PLANNER" else "GUI")
        var gateway: QwenGateway? = null
        var service: HarnessAccessibilityService? = null
        var oldControls: RuntimeControls? = null
        var controls: ProbeControls? = null
        try {
            val app = context as? HarnessApplication ?: throw ProbeFailure("INVALID_STATE")
            val model = QwenGateway(context, app.profiles::keyFor)
            gateway = model
            val phone = withTimeout(5_000) {
                var candidate = HarnessAccessibilityService.instance
                while (candidate?.connected != true) {
                    delay(50)
                    candidate = HarnessAccessibilityService.instance
                }
                candidate ?: throw ProbeFailure("SERVICE_DISCONNECTED")
            }
            service = phone
            val previous = withContext(Dispatchers.Main.immediate) {
                val current = phone.controls ?: throw ProbeFailure("INVALID_STATE")
                if (current !== app.runtime || current.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED) || phone.inputInFlight) {
                    throw ProbeFailure("SERVICE_NOT_IDLE")
                }
                phone.setCaptureEnabled(false)
                current
            }
            oldControls = previous
            supervisorScope {
                val probeJob = AtomicReference<Job?>(null)
                val temporary = ProbeControls(phone, model) { probeJob.get()?.cancel() }
                controls = temporary
                var monitor: Job? = null
                val probe = async(start = CoroutineStart.LAZY) {
                    withContext(Dispatchers.Main.immediate) {
                        phone.controls = temporary
                        phone.showState(temporary.snapshot.value)
                    }
                    val gui = withContext(Dispatchers.Default) {
                        if (!plannerOnly && !app.profiles.hasKey("gui")) throw ProbeFailure("KEY_MISSING")
                        app.profiles.metadata("gui")
                    }
                    // No key string is read by this engine; the gateway resolves it locally.
                    val planner = withContext(Dispatchers.Default) { app.profiles.metadata("planner") }
                    val options = SessionOptions(TASK, PACKAGE, setOf(PACKAGE), true, 1, gui, planner)
                    withContext(Dispatchers.Main.immediate) {
                        phone.configureSession(options)
                        context.startActivity(Intent("org.mobileharness.fixture.action.RESET")
                            .setClassName(PACKAGE, "$PACKAGE.MainActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                    delay(650)
                    requireFixture(phone, temporary)
                    monitor = launch(Dispatchers.Main.immediate) {
                        while (isActive && !temporary.stopped.get()) {
                            try { requireFixture(phone, temporary) }
                            catch (_: Exception) { temporary.abort("AUTHORIZATION_UNAVAILABLE"); return@launch }
                            delay(100)
                        }
                    }
                    if (!plannerOnly) {
                        val action = guiOnce(model, phone, temporary, options)
                        currentCoroutineContext().ensureActive()
                        requireFixture(phone, temporary)
                        onResult("GUI", true, "OK", action)
                        if (temporary.stopped.get()) throw CancellationException()
                    }

                    role.set("PLANNER")
                    if (!withContext(Dispatchers.Default) { app.profiles.hasKey("planner") }) throw ProbeFailure("KEY_MISSING")
                    if (!plannerOnly) delay(450) // Two planned observations; no screenshot retry.
                    val decision = plannerOnce(model, phone, temporary, options)
                    currentCoroutineContext().ensureActive()
                    requireFixture(phone, temporary)
                    onResult("PLANNER", true, "OK", decision)
                    monitor?.cancel()
                }
                probeJob.set(probe)
                probe.start()
                try {
                    probe.await()
                } catch (cancelled: CancellationException) {
                    // A stop cancels only the probe child, so the entry can still write safe results.
                    currentCoroutineContext().ensureActive()
                    onResult(role.get(), false, temporary.stopCode.get(), null)
                } finally {
                    withContext(NonCancellable) {
                        phone.setCaptureEnabled(false)
                        model.cancelAll()
                        monitor?.cancelAndJoin()
                        probe.cancelAndJoin()
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            onResult(role.get(), false, "SERVICE_DISCONNECTED", null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onResult(role.get(), false, safeCode(failure), null)
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                gateway?.cancelAll()
                val phone = service
                phone?.setCaptureEnabled(false)
                phone?.configureSession(null)
                val old = oldControls
                if (phone != null && old != null && HarnessAccessibilityService.instance === phone && phone.controls === controls) {
                    phone.controls = old
                    phone.showState(old.snapshot.value)
                }
                gateway = null
                controls = null
                oldControls = null
                service = null
                RUNNING.set(false)
            }
        }
    }

    private suspend fun guiOnce(model: QwenGateway, phone: HarnessAccessibilityService, controls: ProbeControls, options: SessionOptions): String {
        val frame = freshFrame(phone, controls)
        try {
            val request = GuiContext(options, Phase.VERIFY, OBJECTIVE, listOf(CONDITION), emptyList(), frame, null)
            requireFixture(phone, controls)
            model.preflightGui(request)
            currentCoroutineContext().ensureActive()
            requireFixture(phone, controls)
            val attempt = guiCount.incrementAndGet()
            controls.progress(gui = attempt, planner = plannerAttempts)
            val reply = model.gui(request) // Exactly one actual attempt; the same preflight context.
            return reply.action.name.takeIf { it in setOf("wait", "finish") } ?: throw ProbeFailure("INVALID_OUTPUT")
        } finally {
            frame.png.fill(0)
            model.cancelAll()
        }
    }

    private suspend fun plannerOnce(model: QwenGateway, phone: HarnessAccessibilityService, controls: ProbeControls, options: SessionOptions): String {
        val frame = freshFrame(phone, controls)
        try {
            val request = PlannerContext(options, 1, emptyList(), listOf(frame), frame.id, null)
            requireFixture(phone, controls)
            model.preflightPlanner(request)
            currentCoroutineContext().ensureActive()
            requireFixture(phone, controls)
            val attempt = plannerCount.incrementAndGet()
            controls.progress(gui = guiAttempts, planner = attempt)
            val reply = model.planner(request) // No P2, GUI execution, navigation, or continuation.
            return reply.decision.takeIf { it in setOf("VERIFY", "EXECUTE", "FALLBACK", "FINISH") }
                ?: throw ProbeFailure("INVALID_OUTPUT")
        } finally {
            frame.png.fill(0)
            model.cancelAll()
        }
    }

    private suspend fun freshFrame(phone: HarnessAccessibilityService, controls: ProbeControls): ScreenFrame = withContext(Dispatchers.Main.immediate) {
        requireFixture(phone, controls)
        if (!phone.overlayStopAvailable) throw ProbeFailure("STOP_ENTRY_UNAVAILABLE")
        phone.setCaptureEnabled(true)
        var frame: ScreenFrame? = null
        try {
            val captured = phone.observe()
            frame = captured
            requireFixture(phone, controls)
            captured
        } catch (failure: Exception) {
            frame?.png?.fill(0)
            throw failure
        } finally {
            // No further screenshot request is enabled while awaiting either remote response.
            phone.setCaptureEnabled(false)
        }
    }

    private suspend fun requireFixture(phone: HarnessAccessibilityService, controls: ProbeControls) = withContext(Dispatchers.Main.immediate) {
        if (controls.stopped.get()) throw CancellationException()
        if (!phone.connected || HarnessAccessibilityService.instance !== phone || phone.controls !== controls ||
            HarnessAccessibilityService.sensitivePageVisible || phone.inputInFlight) {
            throw ProbeFailure("AUTHORIZATION_UNAVAILABLE")
        }
        val root = phone.rootInActiveWindow ?: throw ProbeFailure("AUTHORIZATION_UNAVAILABLE")
        try {
            // Package identity is the only local tree value read, never foreign text or children.
            if (root.packageName?.toString() != PACKAGE) throw ProbeFailure("AUTHORIZATION_UNAVAILABLE")
        } finally { root.recycle() }
    }

    private class ProbeControls(
        private val phone: HarnessAccessibilityService,
        private val model: QwenGateway,
        private val cancelProbe: () -> Unit,
    ) : RuntimeControls {
        private val state = MutableStateFlow(RunSnapshot(state = RunState.PREPARING, phase = Phase.VERIFY,
            budget = 1, sessionId = UUID.randomUUID().toString(), message = "远端兼容性诊断；模型输出不执行"))
        override val snapshot: StateFlow<RunSnapshot> = state
        val stopped = AtomicBoolean(false)
        val stopCode = AtomicReference("CANCELLED")
        override fun start(options: SessionOptions) = stop()
        override fun takeover() = stop()
        override fun giveBack() = stop()
        override fun retry() = stop()
        override fun recheck() = Unit
        override fun stop() = abort("CANCELLED")
        override fun environmentUnavailable(code: String) = abort(
            code.takeIf { it in setOf("AUTHORIZATION_UNAVAILABLE", "STOP_ENTRY_UNAVAILABLE", "SERVICE_DISCONNECTED") }
                ?: "AUTHORIZATION_UNAVAILABLE")
        fun abort(code: String) {
            if (!stopped.compareAndSet(false, true)) return
            stopCode.set(code)
            phone.setCaptureEnabled(false)
            state.value = state.value.copy(state = RunState.STOPPING, message = "已停止远端诊断；未执行模型输出")
            phone.showState(state.value)
            model.cancelAll()
            cancelProbe()
        }
        suspend fun progress(gui: Int, planner: Int) = withContext(Dispatchers.Main.immediate) {
            if (stopped.get()) throw CancellationException()
            state.value = state.value.copy(guiUsed = gui, plannerUsed = planner)
            phone.showState(state.value)
        }
    }

    private class ProbeFailure(val safeCode: String) : Exception(safeCode)
    private companion object {
        val RUNNING = AtomicBoolean(false)
        const val PACKAGE = "org.mobileharness.fixture"
        const val TASK = "Synthetic API compatibility diagnostic only. Observe the current MobileHarness Test page and determine whether its title '专用测试页面' is visible. Do not navigate, edit, or infer a real user task. No returned GUI or Planner output will be executed."
        const val OBJECTIVE = "Observe only the current synthetic fixture title; if visible, finish with a short evidence statement."
        const val CONDITION = "The current synthetic page visibly contains the title 专用测试页面."
        fun safeCode(failure: Exception): String {
            val code = when (failure) {
                is ProbeFailure -> failure.safeCode
                is RunFailure -> failure.code
                else -> "REMOTE_DIAGNOSTIC_FAILURE"
            }
            val safe = code.takeIf { it in setOf("KEY_MISSING", "INVALID_STATE", "SERVICE_NOT_IDLE", "API_ERROR",
                "TRANSPORT_ERROR", "INVALID_OUTPUT", "AUTHORIZATION_UNAVAILABLE", "SCREENSHOT_FAILED",
                "STOP_ENTRY_UNAVAILABLE", "SERVICE_DISCONNECTED", "REMOTE_DIAGNOSTIC_FAILURE") }
                ?: "REMOTE_DIAGNOSTIC_FAILURE"
            val http = (failure as? RunFailure)?.httpStatus?.takeIf { it in 100..599 }
            val transport = (failure as? RunFailure)?.transportReason?.takeIf { it in setOf(
                "DNS_FAILURE", "TLS_HANDSHAKE_FAILED", "TLS_PEER_UNVERIFIED", "TIMEOUT", "CONNECT_FAILED", "NETWORK_IO", "REQUEST_REPLAY_BLOCKED") }
            return when {
                safe == "API_ERROR" && http != null -> "${safe}_HTTP_$http"
                safe == "TRANSPORT_ERROR" && transport != null -> "${safe}_$transport"
                else -> safe
            }
        }
    }
}
