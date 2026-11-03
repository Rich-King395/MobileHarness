package org.mobileharness.app.verification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import org.mobileharness.app.HarnessApplication
import org.mobileharness.app.core.*
import org.mobileharness.app.platform.ControlCommand
import org.mobileharness.app.platform.ControlReceiver
import org.mobileharness.app.platform.HarnessAccessibilityService
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** CT-09 D: real own notification PendingIntents and private receiver, synthetic model only. */
class NotificationDeterministicVerification(context: Context) {
    private val context = context.applicationContext
    data class Result(val variant: String, val status: String, val code: String,
        val flags: Map<String, Boolean> = emptyMap(), val syntheticGuiAttempts: Int = 0,
        val syntheticPlannerAttempts: Int = 0, val observationEntries: Int = 0,
        val actualCaptureTickets: Long = 0, val performCalls: Int = 0)

    suspend fun run(report: (Result) -> Unit) {
        if (!RUNNING.compareAndSet(false, true)) { report(Result("BOTH", "BLOCKED", "VERIFICATION_ALREADY_RUNNING")); return }
        try { for (harness in listOf(false, true)) {
            var passed = false
            runVariant(harness) { result -> report(result); passed = result.status == "PASS" }
            if (!passed) break
        } }
        finally { RUNNING.set(false) }
    }

    private suspend fun runVariant(harness: Boolean, report: (Result) -> Unit) {
        val variant = if (harness) "ON" else "OFF"
        val flags = linkedMapOf<String, Boolean>()
        var service: HarnessAccessibilityService? = null
        var previous: RuntimeControls? = null
        var ownedScope: CoroutineScope? = null
        var coordinator: Coordinator? = null
        val gateway = ControlledGateway()
        var phone: GuardedPhone? = null
        var initialTickets: Long? = null
        var status = "FAIL"
        var code = "DIAGNOSTIC_FAILURE"
        fun checkFlag(name: String, value: Boolean) { flags[name] = value; if (!value) fail(name) }
        try {
            val app = context as? HarnessApplication ?: fail("APPLICATION_UNAVAILABLE")
            val real = HarnessAccessibilityService.instance?.takeIf { it.connected } ?: fail("SERVICE_DISCONNECTED")
            service = real
            previous = withContext(Dispatchers.Main.immediate) {
                val old = real.controls ?: fail("SERVICE_NOT_IDLE")
                if (old !== app.runtime || old.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED) || real.inputInFlight) fail("SERVICE_NOT_IDLE")
                if (!real.notificationControlAvailable) fail("NOTIFICATIONS_UNAVAILABLE")
                real.setCaptureEnabled(false)
                old
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            ownedScope = scope
            val audit = MemoryAudit()
            val guarded = GuardedPhone(real)
            phone = guarded
            val runtime = Coordinator(scope, gateway, guarded, audit)
            coordinator = runtime
            guarded.coordinator = runtime
            withContext(Dispatchers.Main.immediate) {
                initialTickets = captureOrdinal(real)
                real.controls = runtime
                real.showState(runtime.snapshot.value)
                context.startActivity(Intent("org.mobileharness.fixture.action.RESET")
                    .setClassName(FIXTURE, "$FIXTURE.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            delay(650)
            withContext(Dispatchers.Main.immediate) {
                runtime.start(SessionOptions("Synthetic notification return acceptance", FIXTURE, setOf(FIXTURE), harness, 8,
                    ModelProfile(baseUrl = "https://invalid.example/v1", model = "synthetic"),
                    ModelProfile(baseUrl = "https://invalid.example/v1", model = "synthetic")))
            }
            await { gateway.guiCalls.size == 1 }
            val takeover = withContext(Dispatchers.Main.immediate) { ownAction(ControlCommand.TAKEOVER) }
            takeover.send()
            await { runtime.snapshot.value.state == RunState.MANUAL }
            val manual = withContext(Dispatchers.Main.immediate) {
                checkFlag("manual_capture_disabled", !real.captureEnabled)
                checkFlag("manual_granted_once", audit.transitionCount("manual_granted") == 1)
                Triple(runtime.snapshot.value.sessionId, stateToken(real), ownAction(ControlCommand.RETURN))
            }
            val oldCount = guarded.observeCalls.get()
            val oldTickets = withContext(Dispatchers.Main.immediate) { captureOrdinal(real) }
            // This entire manual interval reads only our own notification, tokens, state and counters.
            // No root, package, node, screenshot or human-event read is made here.
            receiver(manual.first, "STALE_TOKEN", ControlCommand.RETURN)
            receiver(UUID.randomUUID().toString(), manual.second, ControlCommand.RETURN)
            receiver(manual.first, manual.second, ControlCommand.TAKEOVER)
            withContext(Dispatchers.Main.immediate) {
                checkFlag("old_task_token_state_commands_ignored", runtime.snapshot.value.state == RunState.MANUAL &&
                    !real.captureEnabled && guarded.observeCalls.get() == oldCount && captureOrdinal(real) == oldTickets &&
                    runtime.snapshot.value.guiUsed == 1 && runtime.snapshot.value.plannerUsed == 0)
                guarded.nextObservationGate = CompletableDeferred()
            }
            delay(450) // Planned spacing of two observations; never a screenshot retry.
            // Two sends use the real NotificationManager.Action PendingIntent. They are not UI taps.
            manual.third.send()
            try { manual.third.send() } catch (_: PendingIntent.CanceledException) { flags["duplicate_pending_intent_cancelled"] = true }
            await { guarded.observeCalls.get() == oldCount + 1 }
            withContext(Dispatchers.Main.immediate) {
                checkFlag("one_return_observation_before_commit", audit.transitionCount("return_committed") == 0 &&
                    runtime.snapshot.value.state == RunState.PREPARING && guarded.observeCalls.get() == oldCount + 1)
            }
            receiver(manual.first, manual.second, ControlCommand.RETURN)
            // Exercise the overlay's shared command entry in-process; no rendered UI tap is claimed.
            withContext(Dispatchers.Main.immediate) {
                val method = HarnessAccessibilityService::class.java.getDeclaredMethod("handlePanelCommand", ControlCommand::class.java)
                method.isAccessible = true
                method.invoke(real, ControlCommand.RETURN)
                checkFlag("panel_entry_duplicate_ignored", guarded.observeCalls.get() == oldCount + 1)
                guarded.nextObservationGate?.complete(Unit)
            }
            await { audit.transitionCount("return_committed") == 1 &&
                (if (harness) gateway.plannerCalls.size == 1 else gateway.guiCalls.size == 2) }
            // The old action may be cancelled by Android, or delivered and rejected by the token guard.
            try { manual.third.send() } catch (_: PendingIntent.CanceledException) { flags["old_pending_intent_cancelled"] = true }
            receiver(manual.first, manual.second, ControlCommand.RETURN)
            receiver(UUID.randomUUID().toString(), withContext(Dispatchers.Main.immediate) { stateToken(real) }, ControlCommand.RETURN)
            delay(100)
            withContext(Dispatchers.Main.immediate) {
                checkFlag("single_commit_and_handoff", audit.transitionCount("return_committed") == 1 && runtime.snapshot.value.handoffUsed)
                checkFlag("one_fresh_capture_for_return", guarded.observeCalls.get() == oldCount + 1 && captureOrdinal(real) == oldTickets + 1)
                checkFlag("expected_gui_planner_counts", runtime.snapshot.value.guiUsed == if (harness) 1 else 2)
                checkFlag("expected_planner_count", runtime.snapshot.value.plannerUsed == if (harness) 1 else 0)
                checkFlag("single_new_request", gateway.guiCalls.size == if (harness) 1 else 2)
                checkFlag("single_planner_request", gateway.plannerCalls.size == if (harness) 1 else 0)
                checkFlag("no_phone_input", guarded.performCalls.get() == 0 && !real.inputInFlight)
                checkFlag("real_notification_pending_intent_used", true)
                checkFlag("private_receiver_injected_commands_used", true)
            }
            status = "PASS"; code = "CT09_NOTIFICATION_GUARDS_CONFIRMED"
        } catch (cancelled: CancellationException) { status = "CANCELLED"; code = "CANCELLED"; throw cancelled
        } catch (failure: Failure) { code = failure.code; status = if (code in BLOCKED) "BLOCKED" else "FAIL"
        } catch (_: Exception) { code = "DIAGNOSTIC_FAILURE"; status = "FAIL"
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main.immediate) { service?.setCaptureEnabled(false); coordinator?.stop(); phone?.nextObservationGate?.complete(Unit); gateway.release() }
                val terminal = try {
                    withTimeout(6_000) { while (service?.inputInFlight == true || (coordinator != null && coordinator!!.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED))) delay(20) }
                    true
                } catch (_: TimeoutCancellationException) { false }
                withContext(Dispatchers.Main.immediate) {
                    val real = service
                    if (terminal && real != null && previous != null && real.controls === coordinator && HarnessAccessibilityService.instance === real) {
                        real.setCaptureEnabled(false); real.configureSession(null)
                        real.controls = previous; real.showState(checkNotNull(previous).snapshot.value)
                        flags["controls_restored"] = true
                        ownedScope?.cancel()
                    } else if (!terminal) { status = "UNCERTAIN"; code = "INPUT_TERMINAL_UNCONFIRMED"; flags["controls_restored"] = false }
                    flags["native_terminal_confirmed"] = terminal
                    val baseline = initialTickets
                    flags["baseline_initialized"] = baseline != null
                    val tickets = if (real != null && baseline != null) (captureOrdinal(real) - baseline).coerceAtLeast(0) else 0L
                    report(Result(variant, status, code, flags.toMap(), gateway.guiCalls.size,
                        gateway.plannerCalls.size, phone?.observeCalls?.get() ?: 0, tickets,
                        phone?.performCalls?.get() ?: 0))
                }
            }
        }
    }

    private suspend fun receiver(task: String, token: String, command: ControlCommand) {
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main.immediate) {
            context.sendOrderedBroadcast(Intent(context, ControlReceiver::class.java).apply {
                action = ControlReceiver.ACTION
                putExtra(ControlReceiver.EXTRA_TASK, task); putExtra(ControlReceiver.EXTRA_TOKEN, token)
                putExtra(ControlReceiver.EXTRA_COMMAND, command.wire)
            }, null, object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { done.complete(Unit) } },
                Handler(Looper.getMainLooper()), 0, null, null)
        }
        withTimeout(3_000) { done.await() }
    }
    private fun ownAction(command: ControlCommand): PendingIntent {
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .singleOrNull { it.id == 1701 }?.notification ?: fail("NOTIFICATION_NOT_PRESENT")
        return notification.actions?.singleOrNull { it.title.toString() == command.localizedLabel(context) }?.actionIntent ?: fail("NOTIFICATION_ACTION_NOT_PRESENT")
    }
    private fun stateToken(real: HarnessAccessibilityService) = HarnessAccessibilityService::class.java.getDeclaredField("stateToken").let {
        it.isAccessible = true; it.get(real) as String
    }
    private fun captureOrdinal(real: HarnessAccessibilityService) = HarnessAccessibilityService::class.java.getDeclaredField("captureOrdinal").let {
        it.isAccessible = true; (it.get(real) as java.util.concurrent.atomic.AtomicLong).get()
    }
    private class GuardedPhone(private val real: HarnessAccessibilityService) : PhonePort {
        lateinit var coordinator: Coordinator
        val observeCalls = AtomicInteger(); val performCalls = AtomicInteger()
        var nextObservationGate: CompletableDeferred<Unit>? = null
        override val connected get() = real.connected
        override val inputInFlight get() = real.inputInFlight
        override fun configureSession(options: SessionOptions?) = real.configureSession(options)
        override suspend fun observe(): ScreenFrame {
            if (coordinator.snapshot.value.state == RunState.MANUAL || !real.captureEnabled) fail("MANUAL_OBSERVATION_ATTEMPT")
            observeCalls.incrementAndGet()
            nextObservationGate?.let { it.await(); nextObservationGate = null }
            return real.observe()
        }
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult {
            performCalls.incrementAndGet(); fail("UNEXPECTED_PHONE_INPUT")
        }
        override fun setCaptureEnabled(enabled: Boolean) = real.setCaptureEnabled(enabled)
        override fun showState(snapshot: RunSnapshot) = real.showState(snapshot)
    }
    private class ControlledGateway : ModelGateway {
        val guiCalls = mutableListOf<CompletableDeferred<GuiReply>>()
        val plannerCalls = mutableListOf<CompletableDeferred<PlannerDecision>>()
        override suspend fun gui(context: GuiContext): GuiReply {
            val answer = CompletableDeferred<GuiReply>(); guiCalls.add(answer)
            return withContext(NonCancellable) { answer.await() }
        }
        override suspend fun planner(context: PlannerContext): PlannerDecision {
            val answer = CompletableDeferred<PlannerDecision>(); plannerCalls.add(answer)
            return withContext(NonCancellable) { answer.await() }
        }
        override fun cancelAll() = Unit
        fun release() {
            guiCalls.forEach { it.complete(GuiReply("synthetic-release", PhoneAction("finish", mapOf("outcome" to "unable", "message" to "SYNTHETIC")), "{}")) }
            plannerCalls.forEach { it.complete(PlannerDecision("INCOMPLETE", "FALLBACK", "Synthetic original task", emptyList())) }
        }
    }
    private class MemoryAudit : AuditSink {
        private val transitions = mutableListOf<String>()
        override fun begin(sessionId: String, options: SessionOptions) = Unit
        override fun event(event: String, data: Map<String, Any?>) { if (event == "control") (data["transition"] as? String)?.let(transitions::add) }
        fun transitionCount(value: String) = transitions.count { it == value }
    }
    private class Failure(val code: String) : Exception(code)
    private companion object {
        const val FIXTURE = "org.mobileharness.fixture"
        val RUNNING = AtomicBoolean(false)
        val BLOCKED = setOf("APPLICATION_UNAVAILABLE", "SERVICE_DISCONNECTED", "SERVICE_NOT_IDLE", "NOTIFICATIONS_UNAVAILABLE", "NOTIFICATION_NOT_PRESENT", "NOTIFICATION_ACTION_NOT_PRESENT")
        fun fail(code: String): Nothing = throw Failure(code.uppercase())
        suspend fun await(predicate: () -> Boolean) { withTimeout(8_000) { while (!withContext(Dispatchers.Main.immediate) { predicate() }) delay(20) } }
    }
}
