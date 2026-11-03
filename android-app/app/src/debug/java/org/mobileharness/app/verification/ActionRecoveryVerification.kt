package org.mobileharness.app.verification

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.mobileharness.app.HarnessApplication
import org.mobileharness.app.core.*
import org.mobileharness.app.platform.HarnessAccessibilityService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Real Service/Coordinator, local scripted replies, synthetic fixture only. No HTTP or pixel export. */
class ActionRecoveryVerification(context: Context) {
    private val context = context.applicationContext
    data class Result(
        val status: String = "RUNNING",
        val code: String = "PENDING",
        val guiSteps: Int = 0,
        val plannerCalls: Int = 0,
        val screenshotCalls: Int = 0,
        val performCalls: Int = 0,
        val reasons: List<String> = emptyList(),
        val flags: Map<String, Boolean> = emptyMap(),
    )
    private val result = AtomicReference(Result())
    private val active = AtomicReference<Coordinator?>(null)
    val latest: Result get() = result.get()
    fun stop() { active.get()?.stop() }

    suspend fun run(onResult: (Result) -> Unit) {
        if (!DeviceVerification.RUNNING.compareAndSet(false, true)) {
            publish(Result(status = "BLOCKED", code = "VERIFICATION_ALREADY_RUNNING"), onResult)
            return
        }
        result.set(Result())
        val flags = ConcurrentHashMap<String, Boolean>()
        val reasons = CopyOnWriteArrayList<String>()
        var expectedSteps = 0
        try {
            val app = context as? HarnessApplication ?: fail("INVALID_STATE", true)
            val service = HarnessAccessibilityService.instance?.takeIf { it.connected }
                ?: fail("SERVICE_DISCONNECTED", true)
            withContext(Dispatchers.Main.immediate) {
                if (service.controls !== app.runtime ||
                    app.runtime.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED) || service.inputInFlight) {
                    fail("SERVICE_NOT_IDLE", true)
                }
                if (!service.readiness.value.unlocked) fail("DEVICE_LOCKED", true)
            }
            DeviceVerification(context).withFixture { f ->
                flags["fixture_reset_baseline"] = f.text("fixture_input") == "initial" &&
                    f.text("fixture_click_count") == "点击次数：0" && f.text("fixture_long_press_count") == "长按次数：0"
                require(flags["fixture_reset_baseline"] == true, "PRECONDITION_NOT_RESET", true)
                val noFocus = withContext(Dispatchers.Main.immediate) {
                    f.phone.requireFixture()
                    val focus = f.real.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    try { focus == null } finally { focus?.recycle() }
                }
                // A pre-focused fixture is reported as uncovered, never changed to manufacture failure.
                flags["no_focused_input_precondition"] = noFocus
                flags["no_focused_input_covered"] = false
                val historyEvidence = CopyOnWriteArrayList<List<ExecutionFailureReason>>()
                val freshFrames = CopyOnWriteArrayList<Boolean>()
                var precedingFrame: String? = null
                val gateway = object : ModelGateway {
                    override suspend fun gui(context: GuiContext): GuiReply {
                        freshFrames.add(precedingFrame == null || precedingFrame != context.frame.id)
                        precedingFrame = context.frame.id
                        historyEvidence.add(context.history.mapNotNull { it.result.failureReason })
                        return f.gateway.gui(context)
                    }
                    override suspend fun planner(context: PlannerContext): PlannerDecision = f.gateway.planner(context)
                    override fun cancelAll() = f.gateway.cancelAll()
                }
                var proposedTap: PhoneAction? = null
                var proposedTapFrame: String? = null
                val phone = object : PhonePort {
                    override val connected get() = f.phone.connected
                    override val inputInFlight get() = f.phone.inputInFlight
                    override fun configureSession(options: SessionOptions?) = f.phone.configureSession(options)
                    override fun setCaptureEnabled(enabled: Boolean) = f.phone.setCaptureEnabled(enabled)
                    override fun showState(snapshot: RunSnapshot) = f.phone.showState(snapshot)
                    override suspend fun observe(): ScreenFrame = f.phone.observe()
                    override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult {
                        if (action.name == "tap") {
                            flags["tap_original_parameters_preserved"] = action == proposedTap && frame.id == proposedTapFrame
                        }
                        return try {
                            f.phone.perform(action, frame, allowedPackages, canDispatch).also {
                                it.failureReason?.let { reason -> reasons.add(reason.name) }
                                if (action.name == "tap") {
                                    flags["tap_original_parameters_preserved"] = flags["tap_original_parameters_preserved"] == true &&
                                        action == proposedTap && action.arguments == proposedTap?.arguments
                                    flags["tap_native_completed"] = it.status == "completed"
                                }
                                if (action.name == "input_text" && it.status == "completed") flags["text_native_completed"] = true
                            }
                        } catch (failure: RunFailure) {
                            failure.executionReason?.let { reasons.add(it.name) }
                            throw failure
                        }
                    }
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val coordinator = Coordinator(scope, gateway, phone, f.audit)
                active.set(coordinator)
                suspend fun call(index: Int): DeviceVerification.GuiCall {
                    DeviceVerification.await("GUI call $index") {
                        f.gateway.guiCalls.size > index || coordinator.snapshot.value.state in setOf(RunState.BLOCKED, RunState.ENDED)
                    }
                    require(coordinator.snapshot.value.state != RunState.BLOCKED, "UNEXPECTED_ACTION_BLOCK", false)
                    require(f.gateway.guiCalls.size > index, "ENDED_BEFORE_NEXT_DECISION", false)
                    require(coordinator.snapshot.value.guiUsed == index + 1, "GUI_BUDGET_COUNT_MISMATCH", false)
                    return f.gateway.guiCalls[index]
                }
                suspend fun answer(call: DeviceVerification.GuiCall, reply: GuiReply) {
                    // Planned spacing, including failures: no rate-limit or screenshot retry is hidden.
                    delay(650)
                    f.gateway.answer(call, reply)
                }
                try {
                    withContext(Dispatchers.Main.immediate) {
                        f.phone.coordinator = coordinator
                        f.real.controls = coordinator
                        f.real.showState(coordinator.snapshot.value)
                        coordinator.start(SessionOptions("Synthetic action-failure recovery acceptance", DeviceVerification.PACKAGE,
                            setOf(DeviceVerification.PACKAGE), false, 8,
                            ModelProfile(baseUrl = "https://invalid.example/v1", model = "synthetic"),
                            ModelProfile(baseUrl = "https://invalid.example/v1", model = "synthetic")))
                    }
                    var index = 0
                    var current = call(index)
                    val expectedReasons = mutableListOf<ExecutionFailureReason>()
                    if (noFocus) {
                        answer(current, DeviceVerification.action("input_text", mapOf("text" to SYNTHETIC_TEXT)))
                        current = call(++index)
                        expectedReasons.add(ExecutionFailureReason.NO_FOCUSED_INPUT)
                        require(reasons.toList() == expectedReasons.map { it.name }, "NO_FOCUS_REASON_MISMATCH", false)
                        require(historyEvidence[index] == expectedReasons, "NO_FOCUS_HISTORY_MISMATCH", false)
                        flags["no_focused_input_covered"] = true
                        flags["no_focus_continued_without_retry"] = true
                    }
                    answer(current, DeviceVerification.action("press_key", mapOf("key" to "enter")))
                    current = call(++index)
                    expectedReasons.add(ExecutionFailureReason.IME_SUBMIT_UNSUPPORTED)
                    require(reasons.toList() == expectedReasons.map { it.name }, "ENTER_REASON_MISMATCH", false)
                    require(historyEvidence[index] == expectedReasons, "ENTER_HISTORY_MISMATCH", false)
                    flags["enter_continued_without_retry"] = true
                    val tap = f.at(current, "fixture_input", "tap")
                    proposedTap = tap.action.copy(arguments = tap.action.arguments.toMap())
                    proposedTapFrame = current.frameId
                    answer(current, tap)
                    current = call(++index)
                    answer(current, DeviceVerification.action("input_text", mapOf("text" to SYNTHETIC_TEXT)))
                    current = call(++index)
                    flags["synthetic_text_visible_equal"] = f.text("fixture_input") == SYNTHETIC_TEXT
                    require(flags["synthetic_text_visible_equal"] == true, "TEXT_EFFECT_NOT_CONFIRMED", false)
                    require(historyEvidence[index] == expectedReasons, "FAILED_HISTORY_FEEDBACK_LOST", false)
                    answer(current, DeviceVerification.finish("completed"))
                    DeviceVerification.await("action recovery end") { coordinator.snapshot.value.state == RunState.ENDED }
                    expectedSteps = index + 1
                    flags["new_observation_each_decision"] = freshFrames.size == expectedSteps && freshFrames.all { it }
                    flags["one_capture_per_decision"] = f.phone.observeCalls.get() == expectedSteps &&
                        f.phone.frameCount.get() == expectedSteps
                    flags["one_dispatch_attempt_per_proposal"] = f.phone.performCalls.get() == expectedSteps - 1
                    flags["shared_budget_preserved"] = coordinator.snapshot.value.budget == 8 &&
                        coordinator.snapshot.value.guiUsed == expectedSteps
                    flags["no_planner_calls"] = f.gateway.plannerCalls.isEmpty()
                    flags["no_user_retry"] = f.audit.transitionCount("retry_requested") == 0
                    flags["native_terminal_confirmed"] = !f.real.inputInFlight
                    require(listOf("tap_original_parameters_preserved", "tap_native_completed", "text_native_completed",
                        "new_observation_each_decision", "one_capture_per_decision", "one_dispatch_attempt_per_proposal",
                        "shared_budget_preserved", "no_planner_calls", "no_user_retry",
                        "native_terminal_confirmed").all { flags[it] == true }, "RECOVERY_INVARIANT_FAILED", false)
                } finally {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.Main.immediate) {
                            f.real.setCaptureEnabled(false)
                            coordinator.stop()
                            f.gateway.releaseAll()
                            f.phone.releaseObservations()
                            f.phone.releaseInputTerminals()
                        }
                        DeviceVerification.await("cleanup genuine input terminal") { !f.real.inputInFlight }
                        DeviceVerification.await("cleanup coordinator barrier") {
                            coordinator.snapshot.value.state in setOf(RunState.IDLE, RunState.ENDED)
                        }
                        scope.cancel()
                        withContext(Dispatchers.Main.immediate) {
                            f.phone.coordinator = f.coordinator
                            f.real.controls = f.coordinator
                            f.real.showState(f.coordinator.snapshot.value)
                        }
                        active.compareAndSet(coordinator, null)
                        publish(latest.copy(guiSteps = coordinator.snapshot.value.guiUsed,
                            plannerCalls = f.gateway.plannerCalls.size, screenshotCalls = f.phone.observeCalls.get(),
                            performCalls = f.phone.performCalls.get(), reasons = reasons.toList(), flags = flags.toMap()), onResult)
                    }
                }
            }
            flags["controls_restored"] = service.controls === app.runtime
            require(flags["controls_restored"] == true, "CONTROLS_NOT_RESTORED", false)
            publish(latest.copy(status = "PASS", code = if (flags["no_focused_input_covered"] == true)
                "ACTION_RECOVERY_CONFIRMED" else "ENTER_RECOVERY_CONFIRMED_NO_FOCUS_NOT_COVERED",
                guiSteps = expectedSteps, reasons = reasons.toList(), flags = flags.toMap()), onResult)
        } catch (cancelled: CancellationException) {
            publish(latest.copy(status = "CANCELLED", code = "CANCELLED", reasons = reasons.toList(), flags = flags.toMap()), onResult)
            throw cancelled
        } catch (failure: Exception) {
            val known = failure as? VerificationFailure
            publish(latest.copy(status = if (known?.blocked == true) "BLOCKED" else "FAIL",
                code = known?.code ?: "ACTION_RECOVERY_VERIFICATION_FAILED", reasons = reasons.toList(), flags = flags.toMap()), onResult)
        } finally {
            active.set(null)
            DeviceVerification.RUNNING.set(false)
        }
    }
    private fun publish(value: Result, callback: (Result) -> Unit) { result.set(value); callback(value) }
    private class VerificationFailure(val code: String, val blocked: Boolean) : Exception(code)
    private fun fail(code: String, blocked: Boolean): Nothing = throw VerificationFailure(code, blocked)
    private fun require(value: Boolean, code: String, blocked: Boolean) { if (!value) fail(code, blocked) }
    private companion object { const val SYNTHETIC_TEXT = "SYNTHETIC-ACTION-RECOVERY-42" }
}
