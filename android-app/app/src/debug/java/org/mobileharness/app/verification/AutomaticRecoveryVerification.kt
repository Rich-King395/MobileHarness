package org.mobileharness.app.verification

import android.content.Context
import kotlinx.coroutines.*
import org.mobileharness.app.HarnessApplication
import org.mobileharness.app.core.*
import org.mobileharness.app.platform.HarnessAccessibilityService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Local fault injection around the real screenshot/gesture service. Never calls a model server. */
class AutomaticRecoveryVerification(context: Context) {
    private val context = context.applicationContext
    data class CaseResult(
        val id: String, val passed: Boolean, val code: String,
        val guiSteps: Int, val guiCalls: Int, val plannerCalls: Int,
        val observationAttempts: Int, val injectedObservationFailures: Int,
        val realScreenshotCalls: Int, val performCalls: Int,
        val endReason: String, val executionPolicyVersion: String,
        val startedRecoveries: Int, val cancelledRecoveries: Int, val flags: Map<String, Boolean>,
    )
    data class Result(
        val status: String = "RUNNING", val code: String = "PENDING",
        val cases: List<CaseResult> = emptyList(), val flags: Map<String, Boolean> = emptyMap(),
    )
    private val result = AtomicReference(Result())
    private val active = AtomicReference<Coordinator?>(null)
    private val stopRequested = AtomicBoolean(false)
    val latest: Result get() = result.get()
    fun stop() { stopRequested.set(true); active.get()?.stop() }

    suspend fun run(onResult: (Result) -> Unit) {
        if (!DeviceVerification.RUNNING.compareAndSet(false, true)) {
            onResult(Result(status = "BLOCKED", code = "VERIFICATION_ALREADY_RUNNING")); return
        }
        result.set(Result())
        stopRequested.set(false)
        var real: HarnessAccessibilityService? = null
        var originalFlags: Int? = null
        var originalObserver: Any? = null
        try {
            val app = context as? HarnessApplication ?: fail("INVALID_STATE")
            val service = HarnessAccessibilityService.instance?.takeIf { it.connected }
                ?: fail("SERVICE_DISCONNECTED")
            real = service
            withContext(Dispatchers.Main.immediate) {
                require(service.controls === app.runtime && app.runtime.snapshot.value.state in setOf(RunState.IDLE, RunState.ENDED), "SERVICE_NOT_IDLE")
                require(!service.inputInFlight, "INPUT_TERMINAL_UNCONFIRMED")
                require(service.readiness.value.unlocked, "DEVICE_LOCKED")
                originalFlags = service.serviceInfo.flags
                originalObserver = service.diagnosticObserver
            }
            for (id in listOf("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8")) {
                if (stopRequested.get()) fail("USER_STOPPED_DIAGNOSTIC")
                val value = case(id)
                publish(onResult) { it.copy(cases = it.cases + value) }
                if (!value.passed) fail("CASE_FAILED")
            }
            val restored = withContext(Dispatchers.Main.immediate) {
                service.controls === app.runtime && !service.inputInFlight &&
                    service.serviceInfo.flags == originalFlags && service.diagnosticObserver === originalObserver
            }
            publish(onResult) { it.copy(status = if (restored) "PASS" else "FAIL",
                code = if (restored) "AUTOMATIC_RECOVERY_CONFIRMED" else "RESTORE_NOT_CONFIRMED",
                flags = mapOf("controls_restored" to (service.controls === app.runtime),
                    "native_terminal_confirmed" to !service.inputInFlight,
                    "service_flags_unchanged" to (service.serviceInfo.flags == originalFlags),
                    "diagnostic_observer_unchanged" to (service.diagnosticObserver === originalObserver),
                    "commercial_model_calls_zero" to true, "user_record_store_untouched" to true)) }
        } catch (cancelled: CancellationException) {
            publish(onResult) { it.copy(status = "CANCELLED", code = "CANCELLED") }
            throw cancelled
        } catch (failure: Exception) {
            publish(onResult) { it.copy(status = if (failure is VerificationFailure && failure.code in BLOCKED) "BLOCKED" else "FAIL",
                code = when (failure) { is VerificationFailure -> failure.code; is RunFailure -> failure.code.takeIf { it in DEVICE_CODES } ?: "DEVICE_FAILURE"; else -> "VERIFICATION_FAILED" }) }
        } finally {
            active.set(null)
            val app = context as? HarnessApplication
            real?.let { service ->
                publish(onResult) { it.copy(flags = it.flags + mapOf(
                    "controls_restored" to (service.controls === app?.runtime),
                    "native_terminal_confirmed" to !service.inputInFlight,
                    "service_flags_unchanged" to (service.serviceInfo.flags == originalFlags),
                    "diagnostic_observer_unchanged" to (service.diagnosticObserver === originalObserver),
                    "commercial_model_calls_zero" to true, "user_record_store_untouched" to true)) }
            }
            DeviceVerification.RUNNING.set(false)
        }
    }

    private suspend fun case(id: String): CaseResult {
        var answer: CaseResult? = null
        DeviceVerification(context).withFixture { fixture ->
            val audit = MemoryAudit()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val calls = AtomicInteger()
            val plannerCalls = AtomicInteger()
            val observedFrameIds = CopyOnWriteArrayList<String>()
            val guiPhases = CopyOnWriteArrayList<Phase>()
            val guiHistoryIds = CopyOnWriteArrayList<List<String>>()
            val guiNoticeIndices = CopyOnWriteArrayList<Int?>()
            val plannerFrameIds = CopyOnWriteArrayList<List<String>>()
            val plannerCompletedIds = CopyOnWriteArrayList<List<String>>()
            val guiReplyIds = CopyOnWriteArrayList<String>()
            val gateOldReply = CompletableDeferred<Unit>()
            val gateNewReply = CompletableDeferred<Unit>()
            val oldReplyProduced = AtomicBoolean(false)
            val plannerTwoObservationInjected = AtomicBoolean(false)
            val seenStates = CopyOnWriteArrayList<RunState>()
            val observationAttempts = AtomicInteger()
            val injectedFailures = AtomicInteger()
            val realCaptures = AtomicInteger()
            val performs = AtomicInteger()
            val proposedAction = AtomicReference<PhoneAction?>(null)
            val proposedFrameId = AtomicReference<String?>(null)
            val flags = linkedMapOf<String, Boolean>()
            var coordinator: Coordinator? = null
            var caseCode = "OK"
            var passed = false
            try {
                require(fixture.text("fixture_click_count") == "点击次数：0", "PRECONDITION_NOT_RESET")
                val model = object : ModelGateway {
                    override suspend fun gui(context: GuiContext): GuiReply {
                        val index = calls.incrementAndGet()
                        observedFrameIds.add(context.frame.id)
                        guiPhases.add(context.phase)
                        guiHistoryIds.add(context.history.map { it.reply.callId })
                        guiNoticeIndices.add(context.returnNoticeIndex)
                        if (stopRequested.get()) throw CancellationException()
                        if ((id == "R2" && index == 1) || (id in setOf("R6", "R7") && index == 3)) {
                            throw RunFailure("MODEL", "TRANSPORT_ERROR", transportReason = "TIMEOUT")
                        }
                        // A pending scripted request provides a deterministic handoff opportunity.
                        // No UI/node observations are made by these suspended replies.
                        if (id in setOf("R6", "R7") && index == 2) gateOldReply.await()
                        if (id == "R8") {
                            if (index == 1) return withContext(NonCancellable) {
                                gateOldReply.await()
                                delay(650)
                                oldReplyProduced.set(true)
                                DeviceVerification.finish("completed")
                            }
                            else gateNewReply.await()
                        }
                        // Planned pacing of local replies, not a screenshot or network retry.
                        delay(650)
                        if (stopRequested.get()) throw CancellationException()
                        if (id in setOf("R6", "R7") && index == 1) {
                            val reply = DeviceVerification.action("wait", mapOf("duration_ms" to 100))
                            guiReplyIds.add(reply.callId)
                            return reply
                        }
                        if (id == "R2" && index == 2) {
                            require(checkNotNull(coordinator).snapshot.value.state == RunState.AUTO && fixture.real.captureEnabled,
                                "SCRIPT_WITHOUT_AUTOMATIC_CONTROL")
                            val target = fixture.bounds("fixture_click")
                            val reply = DeviceVerification.action("tap", mapOf(
                                "x" to DeviceVerification.norm(target.left + target.width() * 85 / 100, context.frame.width),
                                "y" to DeviceVerification.norm(target.centerY(), context.frame.height),
                            ))
                            proposedAction.set(reply.action.copy(arguments = reply.action.arguments.toMap()))
                            proposedFrameId.set(context.frame.id)
                            return reply
                        }
                        return DeviceVerification.finish("completed")
                    }
                    override suspend fun planner(context: PlannerContext): PlannerDecision {
                        plannerCalls.incrementAndGet()
                        require(id == "R6", "UNEXPECTED_PLANNER_CALL")
                        plannerFrameIds.add(context.frames.map { it.id })
                        plannerCompletedIds.add(context.completedActions.map { it.reply.callId })
                        delay(100)
                        return if (context.callIndex == 1) PlannerDecision("INCOMPLETE", "VERIFY",
                            "Observe this synthetic fixture only", listOf("Synthetic fixture is visible"))
                        else {
                            require(context.callIndex == 2, "EXTRA_PLANNER_CALL")
                            PlannerDecision("COMPLETE", "FINISH", "", emptyList())
                        }
                    }
                    override fun cancelAll() = Unit
                }
                val phone = object : PhonePort {
                    override val connected get() = fixture.phone.connected
                    override val inputInFlight get() = fixture.phone.inputInFlight
                    override fun configureSession(options: SessionOptions?) = fixture.phone.configureSession(options)
                    override fun setCaptureEnabled(enabled: Boolean) = fixture.phone.setCaptureEnabled(enabled)
                    override fun showState(snapshot: RunSnapshot) {
                        seenStates.add(snapshot.state)
                        fixture.phone.showState(snapshot)
                    }
                    override suspend fun observe(): ScreenFrame {
                        val runtime = checkNotNull(coordinator)
                        require(runtime.snapshot.value.state !in setOf(RunState.MANUAL, RunState.PAUSING, RunState.STOPPING, RunState.ENDED), "OBSERVATION_WITHOUT_AUTOMATIC_CONTROL")
                        val ordinal = observationAttempts.incrementAndGet()
                        val isPlannerTwoObservation = id == "R6" && runtime.snapshot.value.plannerUsed == 1 &&
                            runtime.snapshot.value.guiUsed == 4 && runtime.snapshot.value.phase == Phase.VERIFY &&
                            calls.get() == 4 && plannerTwoObservationInjected.compareAndSet(false, true)
                        if (id == "R3" || (id in setOf("R1", "R4", "R5") && ordinal == 1) || isPlannerTwoObservation) {
                            injectedFailures.incrementAndGet()
                            throw RunFailure("DEVICE", "SCREENSHOT_FAILED", executionReason = ExecutionFailureReason.SCREENSHOT_UNAVAILABLE)
                        }
                        realCaptures.incrementAndGet()
                        return fixture.phone.observe()
                    }
                    override suspend fun perform(action: PhoneAction, frame: ScreenFrame,
                        allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult {
                        require(checkNotNull(coordinator).snapshot.value.state == RunState.AUTO &&
                            fixture.real.captureEnabled && canDispatch(), "SCRIPT_WITHOUT_AUTOMATIC_CONTROL")
                        performs.incrementAndGet()
                        flags["original_script_action_unchanged"] = action == proposedAction.get() && frame.id == proposedFrameId.get()
                        val value = fixture.phone.perform(action, frame, allowedPackages, canDispatch)
                        flags["native_tap_completed"] = value.status == "completed"
                        flags["original_script_action_unchanged"] = flags["original_script_action_unchanged"] == true && action == proposedAction.get()
                        return value
                    }
                }
                val runtime = Coordinator(scope, model, phone, audit)
                coordinator = runtime
                active.set(runtime)
                withContext(Dispatchers.Main.immediate) {
                    fixture.phone.coordinator = runtime
                    fixture.real.controls = runtime
                    fixture.real.showState(runtime.snapshot.value)
                    runtime.start(options(id))
                }
                if (id == "R4") {
                    await { injectedFailures.get() == 1 && !fixture.real.captureEnabled }
                    withContext(Dispatchers.Main.immediate) { runtime.takeover() }
                    await { runtime.snapshot.value.state == RunState.MANUAL }
                    val attemptsAtManual = observationAttempts.get()
                    val capturesAtManual = realCaptures.get()
                    val callsAtManual = calls.get()
                    // Properties/counters only: no root, nodes, frame or pixels during MANUAL.
                    delay(1_400)
                    flags["manual_window_zero_observations"] = observationAttempts.get() == attemptsAtManual && realCaptures.get() == capturesAtManual
                    flags["manual_window_zero_gui_calls"] = calls.get() == callsAtManual
                    flags["manual_capture_disabled"] = !fixture.real.captureEnabled
                    withContext(Dispatchers.Main.immediate) { runtime.stop() }
                }
                if (id == "R5") {
                    await { injectedFailures.get() == 1 && !fixture.real.captureEnabled }
                    withContext(Dispatchers.Main.immediate) { runtime.stop() }
                    await { runtime.snapshot.value.state == RunState.ENDED }
                    val attemptsAtEnd = observationAttempts.get()
                    delay(1_400)
                    flags["ended_backoff_zero_later_observations"] = observationAttempts.get() == attemptsAtEnd
                }
                if (id in setOf("R6", "R7")) {
                    await { calls.get() == 2 }
                    withContext(Dispatchers.Main.immediate) { runtime.takeover() }
                    await { runtime.snapshot.value.state == RunState.MANUAL }
                    val attemptsAtManual = observationAttempts.get()
                    val capturesAtManual = realCaptures.get()
                    val callsAtManual = calls.get()
                    // Counter-only manual window: no screenshot/root/package/node queries.
                    delay(450)
                    flags["manual_window_zero_observations"] = observationAttempts.get() == attemptsAtManual && realCaptures.get() == capturesAtManual
                    flags["manual_window_zero_gui_calls"] = calls.get() == callsAtManual
                    flags["manual_capture_disabled"] = !fixture.real.captureEnabled
                    withContext(Dispatchers.Main.immediate) { runtime.giveBack() }
                }
                if (id == "R8") {
                    await { calls.get() == 1 }
                    val firstSessionId = runtime.snapshot.value.sessionId
                    withContext(Dispatchers.Main.immediate) { runtime.stop() }
                    await { runtime.snapshot.value.state == RunState.ENDED }
                    withContext(Dispatchers.Main.immediate) { runtime.start(options(id)) }
                    await { calls.get() == 2 }
                    flags["new_session_id"] = runtime.snapshot.value.sessionId != firstSessionId
                    gateOldReply.complete(Unit)
                    delay(900)
                    flags["old_finish_did_not_end_new_session"] = oldReplyProduced.get() && runtime.snapshot.value.state == RunState.AUTO && audit.endCount() == 1
                    flags["new_session_history_empty"] = guiHistoryIds[1].isEmpty() && guiNoticeIndices[1] == null
                    flags["new_session_fresh_frame"] = observedFrameIds.size == 2 && observedFrameIds[0] != observedFrameIds[1]
                    gateNewReply.complete(Unit)
                }
                await {
                    val state = runtime.snapshot.value.state
                    if (id != "R4" && state == RunState.MANUAL) fail("USER_HANDOFF_STOPS_DIAGNOSTIC")
                    state == RunState.ENDED || state == RunState.BLOCKED || state == RunState.UNCERTAIN
                }
                require(runtime.snapshot.value.state == RunState.ENDED, "AUTOMATIC_RECOVERY_DID_NOT_END")
                flags["no_blocked_state"] = RunState.BLOCKED !in seenStates
                flags["no_user_retry"] = audit.transitionCount("retry_requested") == 0
                if (id != "R6") flags["no_planner_call"] = plannerCalls.get() == 0 && runtime.snapshot.value.plannerUsed == 0
                flags["script_calls_match_shared_gui_budget"] = calls.get() == runtime.snapshot.value.guiUsed + (if (id == "R8") 1 else 0)
                flags["native_terminal_confirmed"] = !fixture.real.inputInFlight
                flags["execution_policy_automatic"] = audit.policy == "1.2.0"
                val expectedRecoveries = when (id) { "R3", "R6" -> 2; "R4", "R5", "R8" -> 0; else -> 1 }
                flags["bounded_recovery_count"] = audit.recoveryCount("started") == expectedRecoveries &&
                    audit.recoveryCount("cancelled") == (if (id in setOf("R4", "R5")) 1 else 0)
                when (id) {
                    "R1" -> {
                        flags["single_failed_capture_then_real_frame"] = injectedFailures.get() == 1 && observationAttempts.get() == 2 && realCaptures.get() == 1
                        flags["one_gui_finish_zero_action"] = calls.get() == 1 && performs.get() == 0
                        flags["completed_after_recovery"] = audit.endReason() == "model_declared_complete"
                    }
                    "R2" -> {
                        flags["transport_failure_counted_once"] = audit.errorCount("TRANSPORT_ERROR") == 1
                        flags["fresh_frame_after_transport_failure"] = observedFrameIds.size == 3 && observedFrameIds.distinct().size == 3
                        flags["three_gui_attempts_one_native_tap"] = calls.get() == 3 && performs.get() == 1 && realCaptures.get() == 3 && injectedFailures.get() == 0
                        flags["click_count_independently_one"] = fixture.text("fixture_click_count") == "点击次数：1"
                        flags["completed_after_recovery"] = audit.endReason() == "model_declared_complete"
                    }
                    "R3" -> {
                        flags["three_failed_captures_bounded"] = observationAttempts.get() == 3 && injectedFailures.get() == 3 && realCaptures.get() == 0
                        flags["no_model_or_native_action"] = calls.get() == 0 && performs.get() == 0
                        flags["runtime_failure_end"] = audit.endReason() == "runtime_failure"
                    }
                    "R4" -> {
                        flags["cancelled_backoff_zero_later_work"] = observationAttempts.get() == 1 && realCaptures.get() == 0 && calls.get() == 0 && performs.get() == 0
                        flags["manual_granted_once"] = audit.transitionCount("manual_granted") == 1
                        flags["user_stop_end"] = audit.endReason() == "user_stop"
                    }
                    "R5" -> {
                        flags["cancelled_backoff_zero_later_work"] = observationAttempts.get() == 1 && realCaptures.get() == 0 && calls.get() == 0 && performs.get() == 0
                        flags["capture_disabled_at_end"] = !fixture.real.captureEnabled
                        flags["user_stop_end"] = audit.endReason() == "user_stop"
                    }
                    "R6" -> {
                        flags["handoff_return_once"] = audit.transitionCount("manual_granted") == 1 && audit.transitionCount("return_committed") == 1
                        flags["two_planner_calls_only"] = plannerCalls.get() == 2 && runtime.snapshot.value.plannerUsed == 2
                        flags["one_check_phase_new_history"] = guiPhases.toList() == listOf(Phase.ORIGINAL, Phase.ORIGINAL, Phase.VERIFY, Phase.VERIFY) && guiHistoryIds[2].isEmpty() && guiHistoryIds[3].isEmpty()
                        flags["planner_only_successful_original_wait"] = plannerCompletedIds.size == 2 && plannerCompletedIds.all { it == guiReplyIds.toList() }
                        flags["fresh_return_observation"] = observedFrameIds[1] != observedFrameIds[2] && plannerFrameIds[0].containsAll(listOf(observedFrameIds[1], observedFrameIds[2]))
                        flags["fresh_planner_two_observation_only"] = plannerFrameIds[1].size == 1 && plannerFrameIds[1][0] !in observedFrameIds
                        flags["zero_budget_planner_two_capture_recovered"] = plannerTwoObservationInjected.get() && injectedFailures.get() == 1 && runtime.snapshot.value.guiUsed == runtime.snapshot.value.budget && runtime.snapshot.value.budget == 4
                        flags["transport_failure_counted_once"] = audit.errorCount("TRANSPORT_ERROR") == 1
                        flags["planner_direct_finish_without_gui_done"] = calls.get() == 4 && performs.get() == 0 && audit.endReason() == "model_declared_complete" && audit.endSource() == "planner"
                    }
                    "R7" -> {
                        flags["handoff_return_once"] = audit.transitionCount("manual_granted") == 1 && audit.transitionCount("return_committed") == 1
                        flags["off_original_phase_only"] = guiPhases.all { it == Phase.ORIGINAL }
                        flags["off_original_history_preserved"] = guiHistoryIds.drop(1).all { it == guiReplyIds.toList() }
                        flags["off_return_notice_preserved"] = guiNoticeIndices[2] == 1 && guiNoticeIndices[3] == 1
                        flags["fresh_return_and_recovery_frames"] = observedFrameIds.size == 4 && observedFrameIds.distinct().size == 4
                        flags["transport_recovered_without_planner"] = audit.errorCount("TRANSPORT_ERROR") == 1 && audit.endReason() == "model_declared_complete"
                    }
                    "R8" -> {
                        flags["two_sessions_end_independently"] = audit.endCount() == 2 && audit.endReason() == "model_declared_complete" && runtime.snapshot.value.guiUsed == 1
                        flags["no_stale_action_dispatch"] = performs.get() == 0
                    }
                }
                require(flags.values.all { it }, "RECOVERY_INVARIANT_FAILED")
                passed = true
            } catch (_: TimeoutCancellationException) {
                caseCode = "STATE_WAIT_TIMEOUT"
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                caseCode = when (failure) { is VerificationFailure -> failure.code; is RunFailure -> failure.code.takeIf { it in DEVICE_CODES } ?: "DEVICE_FAILURE"; else -> "CASE_FAILURE" }
            } finally {
                withContext(NonCancellable) {
                    gateOldReply.complete(Unit)
                    gateNewReply.complete(Unit)
                    withContext(Dispatchers.Main.immediate) {
                        fixture.real.setCaptureEnabled(false)
                        coordinator?.stop()
                    }
                    await { !fixture.real.inputInFlight && (coordinator == null || coordinator?.snapshot?.value?.state in setOf(RunState.IDLE, RunState.ENDED)) }
                    scope.cancel()
                    withContext(Dispatchers.Main.immediate) {
                        fixture.phone.coordinator = fixture.coordinator
                        fixture.real.controls = fixture.coordinator
                        fixture.real.showState(fixture.coordinator.snapshot.value)
                    }
                    active.compareAndSet(coordinator, null)
                    answer = CaseResult(id, passed, caseCode, coordinator?.snapshot?.value?.guiUsed ?: 0,
                        calls.get(), plannerCalls.get(), observationAttempts.get(), injectedFailures.get(), realCaptures.get(),
                        performs.get(), audit.endReason(), audit.policy,
                        audit.recoveryCount("started"), audit.recoveryCount("cancelled"), flags.toMap())
                    audit.clear()
                    observedFrameIds.clear()
                    proposedAction.set(null)
                    proposedFrameId.set(null)
                }
            }
        }
        return checkNotNull(answer)
    }

    private class MemoryAudit : AuditSink {
        @Volatile var policy = ""
        private val values = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        override fun begin(sessionId: String, options: SessionOptions) { policy = options.executionPolicyVersion }
        override fun event(event: String, data: Map<String, Any?>) {
            val allowed = setOf("transition", "reason", "completion_source", "category", "code", "status")
            if (event in setOf("control", "session_ended", "error", "recovery")) values.add(event to data.filterKeys { it in allowed })
        }
        fun transitionCount(name: String) = values.count { it.first == "control" && it.second["transition"] == name }
        fun errorCount(code: String) = values.count { it.first == "error" && it.second["code"] == code }
        fun endReason() = values.lastOrNull { it.first == "session_ended" }?.second?.get("reason") as? String ?: "NONE"
        fun endSource() = values.lastOrNull { it.first == "session_ended" }?.second?.get("completion_source") as? String ?: "none"
        fun endCount() = values.count { it.first == "session_ended" }
        fun recoveryCount(status: String) = values.count { it.first == "recovery" && it.second["status"] == status }
        fun clear() = values.clear()
    }
    private fun options(id: String) = SessionOptions("Synthetic automatic recovery acceptance", DeviceVerification.PACKAGE,
        setOf(DeviceVerification.PACKAGE), id == "R6", if (id == "R6") 4 else 8,
        ModelProfile(baseUrl = "https://invalid.example/v1", model = "synthetic"),
        ModelProfile(baseUrl = "https://invalid.example/v1", model = "synthetic"), automaticRecovery = true)
    private fun publish(report: (Result) -> Unit, transform: (Result) -> Result) { report(result.updateAndGet { transform(it) }) }
    private class VerificationFailure(val code: String) : Exception(code)
    private fun fail(code: String): Nothing = throw VerificationFailure(code)
    private fun require(value: Boolean, code: String) { if (!value) fail(code) }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(20) }
    private companion object {
        val BLOCKED = setOf("INVALID_STATE", "SERVICE_DISCONNECTED", "SERVICE_NOT_IDLE", "INPUT_TERMINAL_UNCONFIRMED", "DEVICE_LOCKED")
        val DEVICE_CODES = setOf("AUTHORIZATION_UNAVAILABLE", "SCREENSHOT_FAILED", "INPUT_FAILED", "INPUT_STATUS_UNKNOWN", "SERVICE_DISCONNECTED")
    }
}
