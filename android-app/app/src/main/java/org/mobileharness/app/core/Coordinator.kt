package org.mobileharness.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.UUID

/** All mutations run on the supplied serial Main scope. No session content is persisted. */
class Coordinator(
    private val scope: CoroutineScope,
    private val gateway: ModelGateway,
    private val phone: PhonePort,
    private val audit: AuditSink,
) : RuntimeControls {
    private val mutableSnapshot = MutableStateFlow(RunSnapshot())
    override val snapshot: StateFlow<RunSnapshot> = mutableSnapshot.asStateFlow()

    private var options: SessionOptions? = null
    @Volatile private var epoch = 0L
    private var work: Job? = null
    // A submitted perform is deliberately NOT a child of the cancellable model/capture work.
    private var inputOutstanding = false
    private var barrierPoll: Job? = null
    private var barrierTimeout: Job? = null
    private var controlIntent: ControlIntent? = null
    private var endReason = "user_stop"
    private var endSource = "none"
    // Keep the controlled cause while the native-input barrier temporarily changes the status text.
    private var blockedMessage = "已暂停，请处理设备或模型问题"
    private var resume = Resume.GUI
    private var current: ScreenFrame? = null
    private var beforeHandoff: ScreenFrame? = null
    private val originalHistory = mutableListOf<Turn>()
    private var phaseHistory: MutableList<Turn> = originalHistory
    private val completedActions = mutableListOf<Turn>()
    private var objective = ""
    private var conditions: List<String> = emptyList()
    private var checkObjective = ""
    private var checkConditions: List<String> = emptyList()
    private var checkReport = ""
    private var verificationFailed = false
    private var returnNoticeIndex: Int? = null
    private var checkStarted = false
    private val attempts = mutableSetOf<Attempt>()
    // A failed audit cannot be repaired by silently running the rest of an experiment
    // without its record. Keep this latched for this Coordinator's lifetime.
    private var auditFailed = false
    private var guiFailureStreak = 0
    private var observationFailureStreak = 0
    private var recoveryOrdinal = 0
    private var pendingRecovery: Recovery? = null
    private var endFailureCode = "INVALID_STATE"

    /** Abort only the current coroutine, without an uncaught Android main-thread error. */
    private class AuditWriteAborted : CancellationException()

    private enum class ControlIntent { MANUAL, END, BLOCK }
    private enum class Resume { GUI, FALLBACK, PLANNER_ONE, PLANNER_TWO }
    private data class Recovery(val id: Int, val target: String, val attempt: Int, val delayMs: Long, val trigger: String)
    private data class Token(val session: String, val epoch: Long, val phase: Phase)
    private class Attempt(
        val session: String, val role: String, val phase: String, val index: Int,
        val guiUsed: Int, val plannerUsed: Int, val startNanos: Long = System.nanoTime(),
        var terminal: Boolean = false,
    )

    override fun start(options: SessionOptions) = command {
        if (auditFailed) return@command
        if (snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED) || hasInput()) return@command
        if (options.budget < 1 || options.task.isBlank()) return@command
        invalidate()
        clearContent()
        val frozen = options.copy(authorizedPackages = options.authorizedPackages.toSet(),
            authorizedAppLabels = options.authorizedAppLabels.toMap())
        this.options = frozen
        originalHistory.clear()
        phaseHistory = originalHistory
        checkStarted = false
        returnNoticeIndex = null
        controlIntent = null
        val id = UUID.randomUUID().toString()
        mutableSnapshot.value = RunSnapshot(
            state = RunState.AUTO, budget = options.budget, sessionId = id, message = "获取当前画面",
        )
        writeAudit { audit.begin(id, frozen) }
        publish()
        try {
            phone.configureSession(frozen)
        } catch (_: Exception) {
            fail(RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE"), Resume.GUI)
            return@command
        }
        captureEnabled(true)
        queueGui()
    }

    override fun takeover() = command {
        if (auditFailed) return@command
        val state = snapshot.value.state
        if (options == null || state !in setOf(RunState.AUTO, RunState.CHECKING, RunState.PREPARING, RunState.BLOCKED)) return@command
        if (snapshot.value.handoffUsed) {
            requestStop()
            return@command
        }
        event("control", "transition" to "takeover_requested")
        beforeHandoff = if (options?.harness == true) current else null
        invalidate()
        current = null
        controlIntent = ControlIntent.MANUAL
        update(state = RunState.PAUSING, message = "正在结束当前操作，请等待交接确认")
        beginBarrier()
    }

    override fun giveBack() = command {
        if (auditFailed) return@command
        if (snapshot.value.state != RunState.MANUAL || snapshot.value.handoffUsed || options == null) return@command
        event("control", "transition" to "return_requested")
        epoch++
        captureEnabled(true)
        update(state = RunState.PREPARING, message = "归还检查及获取新画面")
        val token = token()
        launchWork {
            try {
                requireConnected()
                val frame = phone.observe()
                if (!active(token)) return@launchWork
                requireFrame(frame)
                observationFailureStreak = 0
                current = frame
                returnNoticeIndex = originalHistory.size
                update(handoffUsed = true, message = "控制权已归还")
                event("control", "transition" to "return_committed")
                if (snapshot.value.guiUsed >= snapshot.value.budget) requestEnd("budget_exhausted", "none")
                else if (options?.harness == true) queuePlanner(1) else fallback(frame)
            } catch (failure: Exception) {
                if (!active(token)) return@launchWork
                val safe = safeFailure(failure, "DEVICE", "SCREENSHOT_FAILED")
                reportError(safe)
                current = null
                beforeHandoff = null
                captureEnabled(false)
                update(state = RunState.MANUAL, message = "归还检查失败，可继续手动或修复后再试")
            }
        }
    }

    override fun stop() = command { requestStop() }

    override fun environmentUnavailable(code: String) = command {
        if (auditFailed) return@command
        if (options == null || snapshot.value.state in setOf(RunState.IDLE, RunState.ENDED) ||
            controlIntent == ControlIntent.END) return@command
        val failure = if (code == "STOP_ENTRY_UNAVAILABLE") RunFailure("COORDINATOR", code)
            else RunFailure("DEVICE", code.takeIf { it in ERROR_CODES["DEVICE"].orEmpty() }
                ?: "AUTHORIZATION_UNAVAILABLE")
        if (snapshot.value.state == RunState.MANUAL) {
            // The person still owns the phone. Return must pass the normal fresh
            // authorization/capture checks; losing a service is not a user stop.
            reportError(failure)
            invalidate()
            clearFrames()
            update(message = "设备条件不可用，可继续手动，修复后再归还")
            return@command
        }
        val plannerAlreadySent = snapshot.value.state == RunState.PREPARING && when (resume) {
            Resume.PLANNER_ONE -> snapshot.value.plannerUsed >= 1
            Resume.PLANNER_TWO -> snapshot.value.plannerUsed >= 2
            else -> false
        }
        // Cancelling an actual Planner attempt consumes it. Retrying the
        // environment must not send that ordinal again or create a repair call.
        fail(failure, if (plannerAlreadySent) Resume.FALLBACK else resume)
    }

    override fun recheck() = command { settleControl() }

    override fun retry() = command {
        if (auditFailed) return@command
        if (snapshot.value.state != RunState.BLOCKED || options == null || hasInput()) return@command
        if (snapshot.value.guiUsed >= snapshot.value.budget && !mayResumeUnsentP2(resume)) {
            requestEnd("budget_exhausted", "none")
            return@command
        }
        event("control", "transition" to "retry_requested")
        epoch++
        controlIntent = null
        captureEnabled(true)
        when (resume) {
            Resume.GUI -> queueGui()
            Resume.FALLBACK -> {
                phaseHistory = originalHistory
                objective = ""
                conditions = emptyList()
                update(phase = Phase.ORIGINAL)
                queueGui()
            }
            Resume.PLANNER_ONE -> queuePlanner(1, refresh = true)
            Resume.PLANNER_TWO -> queuePlanner(2, refresh = true)
        }
    }

    private fun command(block: () -> Unit) { scope.launch { block() } }

    private fun requestStop() {
        if (options == null || snapshot.value.state in setOf(RunState.IDLE, RunState.ENDED)) return
        if (controlIntent == ControlIntent.END && endReason == "user_stop") return
        try {
            event("control", "transition" to "stop_requested")
            requestEnd("user_stop", "none")
        } catch (_: AuditWriteAborted) {
            // The same stop press must still wait for genuine native terminals,
            // even if writing its control/cancellation/end event failed.
            if (options != null) requestEnd("interrupted", "none")
        }
    }

    private fun requestEnd(reason: String, source: String) {
        if (controlIntent == ControlIntent.END && endReason == "user_stop" && reason != "user_stop") return
        val uncertain = snapshot.value.state == RunState.UNCERTAIN
        invalidate()
        clearFrames()
        endReason = if (auditFailed) "interrupted" else reason
        endSource = if (auditFailed) "none" else source
        controlIntent = ControlIntent.END
        update(state = if (uncertain) RunState.UNCERTAIN else RunState.STOPPING,
            message = if (uncertain) "已请求停止，仍需确认当前操作终态" else "停止处理中")
        beginBarrier()
    }

    private fun beginBarrier() {
        if (settleControl()) return
        if (barrierPoll?.isActive != true) barrierPoll = scope.launch {
            while (controlIntent != null) {
                delay(50)
                if (settleControl()) break
            }
        }
        if (barrierTimeout?.isActive != true && snapshot.value.state != RunState.UNCERTAIN) {
            barrierTimeout = scope.launch {
                delay(5_000)
                if (controlIntent != null && hasInput()) {
                    update(state = RunState.UNCERTAIN, message = "暂停未确认，继续禁止自动操作")
                    event("control", "transition" to "pause_unconfirmed")
                    reportError(RunFailure("COORDINATOR", "PAUSE_UNCONFIRMED"))
                }
            }
        }
    }

    private fun settleControl(): Boolean {
        val intent = controlIntent ?: return true
        if (hasInput()) return false
        controlIntent = null
        barrierPoll?.cancel()
        barrierPoll = null
        barrierTimeout?.cancel()
        barrierTimeout = null
        when (intent) {
            ControlIntent.MANUAL -> {
                update(state = RunState.MANUAL, message = "人工控制，你可以自由操作")
                event("control", "transition" to "manual_granted")
            }
            ControlIntent.BLOCK -> update(state = RunState.BLOCKED, message = blockedMessage)
            ControlIntent.END -> finishSession()
        }
        return true
    }

    private fun finishSession() {
        event("session_ended", "reason" to endReason, "completion_source" to endSource,
            "gui_steps_used" to snapshot.value.guiUsed, "planner_calls_used" to snapshot.value.plannerUsed)
        val message = when (endReason) {
            "model_declared_complete" -> "模型判断结束；结果待独立确认"
            "model_unable" -> "模型无法继续"
            "budget_exhausted" -> "已用完 GUI 额度"
            "interrupted" -> "任务已中断"
            "runtime_failure" -> "任务因运行错误结束（$endFailureCode）"
            else -> "已停止，可继续手动操作"
        }
        clearContent()
        options = null
        update(state = RunState.ENDED, message = message)
    }

    private fun queueGui(frame: ScreenFrame? = null) {
        if (auditFailed || options == null || controlIntent != null) return
        if (snapshot.value.guiUsed >= snapshot.value.budget) {
            requestEnd("budget_exhausted", "none")
            return
        }
        resume = Resume.GUI
        update(state = if (isCheck()) RunState.CHECKING else RunState.AUTO, message = "获取当前画面")
        val token = token()
        launchWork {
            var attempt: Attempt? = null
            try {
                requireConnected()
                val observed = frame ?: phone.observe()
                if (!active(token)) return@launchWork
                requireFrame(observed)
                observationFailureStreak = 0
                current = observed
                val opts = options ?: return@launchWork
                val notice = if (token.phase == Phase.ORIGINAL) returnNoticeIndex
                    else if (snapshot.value.handoffUsed) 0 else null
                val context = GuiContext(opts, token.phase, objective, conditions.toList(), phaseHistory.toList(),
                    observed, notice)
                gateway.preflightGui(context)
                if (!active(token)) return@launchWork
                update(guiUsed = snapshot.value.guiUsed + 1, message = "等待 GUI 模型回复")
                attempt = beginAttempt("GUI", token.phase.name, snapshot.value.guiUsed)
                val reply = gateway.gui(context)
                if (!active(token)) {
                    terminal(attempt, "late_discarded")
                    return@launchWork
                }
                requireValidReply(reply, token.phase)
                guiFailureStreak = 0
                terminal(attempt, "succeeded")
                // Let already queued ownership events win before the final dispatch gate.
                yield()
                if (!active(token) || current?.id != observed.id) return@launchWork
                acceptGuiReply(reply, observed, token, snapshot.value.guiUsed)
            } catch (failure: Exception) {
                if (!active(token)) return@launchWork
                val safe = safeFailure(failure, if (attempt == null) "DEVICE" else "MODEL",
                    if (attempt == null) "SCREENSHOT_FAILED" else "TRANSPORT_ERROR")
                attempt?.let { terminal(it, statusFor(safe, failure), safe.httpStatus) }
                fail(safe, Resume.GUI)
            }
        }
    }

    private fun acceptGuiReply(reply: GuiReply, frame: ScreenFrame, token: Token, step: Int) {
        if (!canDispatch(token, frame)) return
        val history = phaseHistory
        when (reply.action.name) {
            "finish" -> {
                val outcome = reply.action.arguments["outcome"] as String
                val report = reply.action.arguments["message"] as String
                val turn = Turn(reply, ActionResult("completed"))
                history.add(turn)
                actionEvent(token.phase, step, reply.action, "completed", outcome)
                if (!isCheck()) {
                    requestEnd(if (outcome == "completed") "model_declared_complete" else "model_unable", "gui")
                } else if (token.phase == Phase.CONFIRM && outcome == "completed") {
                    requestEnd("model_declared_complete", "confirmation")
                } else {
                    checkReport = report
                    verificationFailed = outcome == "unable"
                    queuePlanner(2, refresh = true)
                }
            }
            "wait" -> launchWork {
                try {
                    update(message = "等待画面刷新")
                    delay((reply.action.arguments["duration_ms"] as Number).toLong())
                    if (!active(token)) return@launchWork
                    val turn = Turn(reply, ActionResult("completed"))
                    history.add(turn)
                    completedActions.add(turn)
                    actionEvent(token.phase, step, reply.action, "completed")
                    val fresh = phone.observe()
                    if (!active(token)) return@launchWork
                    requireFrame(fresh)
                    observationFailureStreak = 0
                    current = fresh
                    // A final wait is not a phase finish and cannot establish a P2 entry.
                    if (snapshot.value.guiUsed >= snapshot.value.budget) requestEnd("budget_exhausted", "none")
                    else queueGui(fresh)
                } catch (failure: Exception) {
                    if (active(token)) fail(safeFailure(failure, "DEVICE", "SCREENSHOT_FAILED"), Resume.GUI)
                }
            }
            else -> {
                update(message = when (reply.action.name) {
                    "tap" -> "正在点击"
                    "long_press" -> "正在长按"
                    "swipe" -> "正在滑动"
                    "input_text" -> "正在输入文字"
                    "press_key" -> "正在执行按键"
                    "launch_app" -> "正在打开 App"
                    else -> "正在执行操作"
                })
                if (!canDispatch(token, frame)) return
                inputOutstanding = true
                scope.launch {
                    var executionFailure: RunFailure? = null
                    val result = try {
                        phone.perform(reply.action, frame, options?.authorizedPackages.orEmpty()) { canDispatch(token, frame) }
                    } catch (_: CancellationException) {
                        ActionResult("unknown", failureReason = ExecutionFailureReason.INPUT_RESULT_UNKNOWN)
                    } catch (failure: RunFailure) {
                        val safe = safeFailure(failure, "DEVICE", "INPUT_FAILED")
                        executionFailure = safe
                        ActionResult(if (safe.code == "INPUT_STATUS_UNKNOWN") "unknown" else "device_error",
                            failureReason = safe.executionReason)
                    } catch (_: Exception) {
                        ActionResult("device_error", failureReason = ExecutionFailureReason.INPUT_RESULT_UNKNOWN)
                    }
                    inputOutstanding = false
                    if (snapshot.value.sessionId != token.session || options == null) return@launch
                    val status = result.status.takeIf { it in ACTION_RESULTS } ?: "unknown"
                    val reason = when (status) {
                        "completed" -> null
                        "unknown" -> ExecutionFailureReason.INPUT_RESULT_UNKNOWN
                        else -> result.failureReason
                    }
                    val turn = Turn(reply, result.copy(status = status, failureReason = reason))
                    // A genuinely completed pre-takeover input remains the Agent's own action.
                    // A stale proposal rejected at canDispatch never enters effective history.
                    if (status == "completed") {
                        history.add(turn)
                        completedActions.add(turn)
                    } else if (active(token)) history.add(turn)
                    actionEvent(token.phase, step, reply.action, status, executionReason = reason)
                    if (controlIntent != null) {
                        settleControl()
                    } else if (active(token)) {
                        if (status == "completed") queueGui()
                        else {
                            val failure = if (status == "unknown") RunFailure("DEVICE", "INPUT_STATUS_UNKNOWN", executionReason = reason)
                                else executionFailure ?: RunFailure("DEVICE", "INPUT_FAILED", executionReason = reason)
                            val canReobserve = status in setOf("device_error", "cancelled", "rejected") &&
                                reason?.canReobserve == true && failure.category == "DEVICE" &&
                                failure.code in setOf("INPUT_FAILED", "SCREENSHOT_FAILED") &&
                                token.phase in setOf(Phase.ORIGINAL, Phase.RECOVERY) && !hasInput() && phone.connected
                            if (!canReobserve) fail(failure, Resume.GUI)
                            else {
                                // This is a new model decision, never a replay of the failed input.
                                // The failed turn remains in this stage; Planner sees successes only.
                                reportError(failure)
                                if (snapshot.value.guiUsed >= snapshot.value.budget) {
                                    requestEnd("budget_exhausted", "none")
                                } else {
                                    // Already queued handoff/stop commands win before a new observation.
                                    yield()
                                    if (!active(token)) return@launch
                                    if (hasInput()) fail(RunFailure("DEVICE", "INPUT_STATUS_UNKNOWN",
                                        executionReason = ExecutionFailureReason.INPUT_RESULT_UNKNOWN), Resume.GUI)
                                    else queueGui()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun queuePlanner(index: Int, refresh: Boolean = false) {
        if (auditFailed || options == null || controlIntent != null) return
        if (index !in 1..2 || snapshot.value.plannerUsed != index - 1) {
            fail(RunFailure("COORDINATOR", "INVALID_STATE"), Resume.FALLBACK)
            return
        }
        resume = if (index == 1) Resume.PLANNER_ONE else Resume.PLANNER_TWO
        update(state = RunState.PREPARING, message = "恢复规划 $index/2")
        val token = token()
        launchWork {
            var attempt: Attempt? = null
            try {
                requireConnected()
                val observed = if (refresh) phone.observe() else current ?: throw RunFailure("DEVICE", "SCREENSHOT_FAILED")
                if (!active(token)) return@launchWork
                requireFrame(observed)
                observationFailureStreak = 0
                current = observed
                val opts = options ?: return@launchWork
                // P2 needs the latest bounded-check evidence, not a replay of P1 images.
                val pre = if (index == 1) beforeHandoff else null
                val frames = listOfNotNull(pre, observed).distinctBy { it.id }
                val context = PlannerContext(opts, index, completedActions.toList(), frames, observed.id,
                    pre?.id, checkObjective, checkConditions.toList(), checkReport, verificationFailed)
                gateway.preflightPlanner(context)
                if (!active(token)) return@launchWork
                update(plannerUsed = index, message = "等待 Planner 回复（$index/2）")
                attempt = beginAttempt("PLANNER", "PLANNING", index)
                val decision = gateway.planner(context)
                if (!active(token)) {
                    terminal(attempt, "late_discarded")
                    return@launchWork
                }
                requireValidDecision(decision, index)
                terminal(attempt, "succeeded")
                yield()
                if (!active(token)) return@launchWork
                event("planner_decision", "planner_call_index" to index,
                    "completion_status" to decision.status, "decision" to decision.decision)
                beforeHandoff = null
                when (decision.decision) {
                    "FINISH" -> requestEnd("model_declared_complete", "planner")
                    "FALLBACK" -> fallback(observed)
                    "EXECUTE" -> enterPhase(Phase.RECOVERY, decision, observed)
                    "VERIFY" -> {
                        if (checkStarted) throw RunFailure("MODEL", "INVALID_OUTPUT")
                        checkStarted = true
                        checkObjective = decision.objective
                        checkConditions = decision.conditions.toList()
                        enterPhase(if (decision.status == "COMPLETE") Phase.CONFIRM else Phase.VERIFY, decision, observed)
                    }
                }
            } catch (failure: Exception) {
                if (!active(token)) return@launchWork
                val safe = safeFailure(failure, if (attempt == null) "DEVICE" else "MODEL",
                    if (attempt == null) "SCREENSHOT_FAILED" else "TRANSPORT_ERROR")
                attempt?.let { terminal(it, statusFor(safe, failure), safe.httpStatus) }
                reportError(safe)
                if (attempt != null) {
                    // Planner failure never buys a repair call, P2 or P3.
                    val fresh = current
                    if (fresh != null && phone.connected) fallback(fresh)
                    else fail(safe, Resume.FALLBACK, report = false)
                } else fail(safe, resume, report = false)
            }
        }
    }

    private fun enterPhase(phase: Phase, decision: PlannerDecision, frame: ScreenFrame) {
        epoch++
        phaseHistory = mutableListOf()
        objective = decision.objective
        conditions = decision.conditions.toList()
        current = frame
        update(phase = phase)
        queueGui(frame)
    }

    private fun fallback(frame: ScreenFrame) {
        epoch++
        beforeHandoff = null
        phaseHistory = originalHistory
        objective = ""
        conditions = emptyList()
        current = frame
        update(phase = Phase.ORIGINAL)
        queueGui(frame)
    }

    private fun fail(failure: RunFailure, next: Resume, report: Boolean = true) {
        if (options == null || controlIntent == ControlIntent.END) return
        if (report) reportError(failure)
        if (snapshot.value.guiUsed >= snapshot.value.budget && !mayResumeUnsentP2(next)) {
            requestEnd("budget_exhausted", "none")
            return
        }
        if (options?.automaticRecovery == true) {
            recoverOrEnd(failure, next)
            return
        }
        resume = next
        val uncertain = snapshot.value.state == RunState.UNCERTAIN
        invalidate()
        clearFrames()
        controlIntent = ControlIntent.BLOCK
        blockedMessage = pauseMessage(failure)
        update(state = if (!hasInput()) RunState.BLOCKED else if (uncertain) RunState.UNCERTAIN else RunState.PAUSING,
            message = blockedMessage)
        beginBarrier()
    }

    /** New decisions only: no input replay, no transport-level retry, no extra Planner call. */
    private fun recoverOrEnd(failure: RunFailure, next: Resume) {
        val observationFailure = failure.category == "DEVICE" &&
            (failure.code == "SCREENSHOT_FAILED" ||
                (failure.code == "AUTHORIZATION_UNAVAILABLE" && failure.executionReason == ExecutionFailureReason.SCREENSHOT_UNAVAILABLE)) &&
            failure.executionReason !in setOf(ExecutionFailureReason.DEVICE_NOT_READY,
                ExecutionFailureReason.INPUT_APP_NOT_AUTHORIZED, ExecutionFailureReason.CONTROL_NOT_AVAILABLE,
                ExecutionFailureReason.INPUT_RESULT_UNKNOWN, ExecutionFailureReason.PROTECTED_INPUT)
        val modelFailure = next == Resume.GUI && failure.category == "MODEL" && when (failure.code) {
            "INVALID_OUTPUT" -> true
            "TRANSPORT_ERROR" -> failure.transportReason !in setOf("TLS_PEER_UNVERIFIED", "REQUEST_REPLAY_BLOCKED")
            "API_ERROR" -> failure.httpStatus in setOf(408, 429, 500, 502, 503, 504)
            else -> false
        }
        if ((!observationFailure && !modelFailure) || hasInput() || !phone.connected) {
            endRuntimeFailure(failure)
            return
        }
        val consecutive = if (modelFailure) ++guiFailureStreak else ++observationFailureStreak
        if (consecutive > 2) {
            endRuntimeFailure(failure)
            return
        }
        // invalidate cancels old work/replies before installing this separate delayed job.
        // Only the current image is released. A required pre-handoff boundary remains available.
        invalidate()
        current = null
        resume = next
        val recovery = Recovery(++recoveryOrdinal,
            if (modelFailure) "GUI" else if (next in setOf(Resume.PLANNER_ONE, Resume.PLANNER_TWO)) "PLANNER_OBSERVATION" else "OBSERVATION",
            consecutive, if (consecutive == 1) 1_000 else 3_000, failure.code)
        pendingRecovery = recovery
        recoveryEvent(recovery, "scheduled")
        update(state = if (next in setOf(Resume.PLANNER_ONE, Resume.PLANNER_TWO)) RunState.PREPARING
            else if (isCheck()) RunState.CHECKING else RunState.AUTO,
            message = "正在自动恢复（$consecutive/2）")
        val token = token()
        launchWork {
            delay(recovery.delayMs)
            yield()
            if (!active(token) || pendingRecovery !== recovery) return@launchWork
            recoveryEvent(recovery, "started")
            pendingRecovery = null
            captureEnabled(true)
            when (next) {
                Resume.GUI -> queueGui()
                Resume.FALLBACK -> {
                    phaseHistory = originalHistory
                    objective = ""
                    conditions = emptyList()
                    update(phase = Phase.ORIGINAL)
                    queueGui()
                }
                Resume.PLANNER_ONE -> queuePlanner(1, refresh = true)
                Resume.PLANNER_TWO -> queuePlanner(2, refresh = true)
            }
        }
    }

    private fun endRuntimeFailure(failure: RunFailure) {
        endFailureCode = failure.code
        // The normal end barrier still waits for every real native input terminal.
        requestEnd("runtime_failure", "none")
    }

    private fun recoveryEvent(value: Recovery, status: String) = event("recovery",
        "recovery_id" to value.id, "target" to value.target, "attempt" to value.attempt,
        "delay_ms" to value.delayMs, "trigger_code" to value.trigger, "status" to status)

    private fun pauseMessage(failure: RunFailure): String {
        val detail = when (failure.executionReason) {
            ExecutionFailureReason.PROTECTED_INPUT -> "当前输入受保护"
            ExecutionFailureReason.INPUT_APP_NOT_AUTHORIZED -> "当前目标窗口不在授权范围"
            ExecutionFailureReason.DEVICE_NOT_READY -> "设备锁屏或屏幕未就绪"
            ExecutionFailureReason.SCREENSHOT_UNAVAILABLE -> "没有可用的当前画面或前台窗口"
            ExecutionFailureReason.INPUT_RESULT_UNKNOWN -> "输入结果尚未确认"
            else -> when (failure.code) {
                "AUTHORIZATION_UNAVAILABLE", "SERVICE_DISCONNECTED" -> "设备或当前窗口暂不可用"
                "SCREENSHOT_FAILED", "OBSERVATION_UNAVAILABLE" -> "当前画面获取失败"
                "INPUT_STATUS_UNKNOWN", "PAUSE_UNCONFIRMED" -> "输入终态尚未确认"
                "INPUT_FAILED" -> "操作失败，原因尚未确认"
                "TRANSPORT_ERROR" -> "模型请求未完成"
                "API_ERROR" -> "模型服务返回错误"
                "INVALID_OUTPUT" -> "模型回复未通过动作协议校验"
                "STOP_ENTRY_UNAVAILABLE" -> "控制入口不可用"
                else -> "运行状态需要检查"
            }
        }
        return "已暂停 · $detail（${failure.code}）"
    }

    private fun invalidate() {
        epoch++
        captureEnabled(false)
        pendingRecovery?.let { recovery ->
            pendingRecovery = null
            recoveryEvent(recovery, "cancelled")
        }
        attempts.toList().forEach { terminal(it, "cancelled") }
        work?.cancel()
        work = null
        runCatching { gateway.cancelAll() }
    }

    private fun launchWork(block: suspend () -> Unit) {
        work = scope.launch(start = CoroutineStart.LAZY) { block() }.also { it.start() }
    }

    private fun token() = Token(snapshot.value.sessionId, epoch, snapshot.value.phase)
    private fun active(token: Token): Boolean = !auditFailed && options != null && controlIntent == null &&
        token.session == snapshot.value.sessionId && token.epoch == epoch && token.phase == snapshot.value.phase &&
        snapshot.value.state in setOf(RunState.AUTO, RunState.CHECKING, RunState.PREPARING)
    private fun canDispatch(token: Token, frame: ScreenFrame): Boolean = active(token) && phone.connected &&
        snapshot.value.state in setOf(RunState.AUTO, RunState.CHECKING) && current?.let {
            it.id == frame.id && it.width == frame.width && it.height == frame.height
        } == true
    private fun hasInput() = inputOutstanding || phone.inputInFlight
    private fun isCheck() = snapshot.value.phase in setOf(Phase.VERIFY, Phase.CONFIRM)
    private fun mayResumeUnsentP2(next: Resume) = next == Resume.PLANNER_TWO &&
        checkStarted && snapshot.value.plannerUsed == 1
    private fun captureEnabled(enabled: Boolean) { runCatching { phone.setCaptureEnabled(enabled) } }
    private fun requireConnected() { if (!phone.connected) throw RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE") }
    private fun requireFrame(frame: ScreenFrame) {
        if (frame.id.isBlank() || frame.png.isEmpty() || frame.width < 1 || frame.height < 1)
            throw RunFailure("DEVICE", "SCREENSHOT_FAILED")
    }

    private fun beginAttempt(role: String, phase: String, index: Int): Attempt = Attempt(
        snapshot.value.sessionId, role, phase, index, snapshot.value.guiUsed, snapshot.value.plannerUsed,
    ).also { attempts.add(it); modelEvent(it, "started", 0) }
    private fun terminal(attempt: Attempt, status: String, httpStatus: Int? = null) {
        if (attempt.terminal) return
        attempt.terminal = true
        attempts.remove(attempt)
        if (snapshot.value.sessionId == attempt.session)
            modelEvent(attempt, status, ((System.nanoTime() - attempt.startNanos) / 1_000_000).coerceAtLeast(0), httpStatus)
    }
    private fun modelEvent(attempt: Attempt, status: String, duration: Long, httpStatus: Int? = null) {
        val data = linkedMapOf<String, Any?>("role" to attempt.role, "phase" to attempt.phase, "call_index" to attempt.index,
            "gui_steps_used" to attempt.guiUsed, "planner_calls_used" to attempt.plannerUsed,
            "duration_ms" to duration, "status" to status)
        httpStatus?.takeIf { it in 100..599 }?.let { data["http_status"] = it }
        auditEvent("model_call", data)
    }
    private fun actionEvent(phase: Phase, step: Int, action: PhoneAction, result: String, outcome: String? = null,
        executionReason: ExecutionFailureReason? = null) {
        val data = linkedMapOf<String, Any?>("phase" to phase.name, "gui_step" to step,
            "action_type" to action.name, "result" to result)
        if (action.name == "finish") data["declared_outcome"] = outcome
        if (result != "completed") executionReason?.let { data["execution_failure_reason"] = it.name }
        auditEvent("action", data)
    }
    private fun reportError(failure: RunFailure) {
        val data = linkedMapOf<String, Any?>("category" to failure.category, "code" to failure.code)
        if (failure.category == "MODEL" && failure.code == "INVALID_OUTPUT")
            failure.protocolReason?.let { data["protocol_reason"] = it }
        if (failure.category == "DEVICE") failure.executionReason?.let { data["execution_failure_reason"] = it.name }
        if (options?.automaticRecovery == true && failure.category == "MODEL") {
            if (failure.code == "TRANSPORT_ERROR") failure.transportReason?.takeIf { it in setOf(
                "DNS_FAILURE", "TLS_HANDSHAKE_FAILED", "TLS_PEER_UNVERIFIED", "TIMEOUT", "CONNECT_FAILED", "REQUEST_REPLAY_BLOCKED", "NETWORK_IO",
            ) }?.let { data["transport_reason"] = it }
            if (failure.code == "API_ERROR") failure.httpStatus?.takeIf { it in 100..599 }?.let { data["http_status"] = it }
        }
        auditEvent("error", data)
    }
    private fun event(name: String, vararg data: Pair<String, Any?>) { auditEvent(name, linkedMapOf(*data)) }

    private fun auditEvent(name: String, data: Map<String, Any?>) = writeAudit { audit.event(name, data) }

    private inline fun writeAudit(write: () -> Unit) {
        if (auditFailed) return
        try {
            write()
        } catch (_: Exception) {
            // Latch before invalidation: cancelling outstanding attempts also writes
            // terminal metadata, and must never call a broken sink recursively.
            auditFailed = true
            val uncertain = snapshot.value.state == RunState.UNCERTAIN
            val alreadyEnding = controlIntent == ControlIntent.END
            invalidate()
            clearFrames()
            endReason = "interrupted"
            endSource = "none"
            controlIntent = if (alreadyEnding || options?.automaticRecovery == true) ControlIntent.END else ControlIntent.BLOCK
            update(state = if (!hasInput()) {
                if (controlIntent == ControlIntent.END) RunState.STOPPING else RunState.BLOCKED
            } else if (uncertain) RunState.UNCERTAIN else if (controlIntent == ControlIntent.END) RunState.STOPPING else RunState.PAUSING)
            beginBarrier()
            throw AuditWriteAborted()
        }
    }
    private fun statusFor(failure: RunFailure, original: Exception): String = when {
        original is CancellationException -> "cancelled"
        failure.code == "API_ERROR" -> "api_error"
        failure.code == "INVALID_OUTPUT" -> "invalid_output"
        else -> "transport_error"
    }
    private fun safeFailure(error: Exception, category: String, code: String): RunFailure =
        (error as? RunFailure)?.takeIf { it.code in ERROR_CODES[it.category].orEmpty() } ?: RunFailure(category, code)

    private fun clearFrames() { current = null; beforeHandoff = null }
    private fun clearContent() {
        captureEnabled(false)
        runCatching { phone.configureSession(null) }
        blockedMessage = "已暂停，请处理设备或模型问题"
        clearFrames()
        originalHistory.clear()
        if (phaseHistory !== originalHistory) phaseHistory.clear()
        completedActions.clear()
        objective = ""
        conditions = emptyList()
        checkObjective = ""
        checkConditions = emptyList()
        checkReport = ""
        verificationFailed = false
        returnNoticeIndex = null
        guiFailureStreak = 0
        observationFailureStreak = 0
        recoveryOrdinal = 0
        pendingRecovery = null
    }
    private fun update(
        state: RunState = snapshot.value.state, phase: Phase = snapshot.value.phase,
        guiUsed: Int = snapshot.value.guiUsed, plannerUsed: Int = snapshot.value.plannerUsed,
        handoffUsed: Boolean = snapshot.value.handoffUsed, message: String = snapshot.value.message,
    ) {
        val visibleMessage = if (!auditFailed) message else when {
            state == RunState.ENDED -> "日志写入失败，任务已结束；请检查本机存储后重启应用。"
            hasInput() -> "日志写入失败，正在等待当前操作终态；请结束任务并重启应用。"
            else -> "日志写入失败，请结束当前任务并检查本机存储后重启应用。"
        }
        mutableSnapshot.value = snapshot.value.copy(state = state, phase = phase, guiUsed = guiUsed,
            plannerUsed = plannerUsed, handoffUsed = handoffUsed, message = visibleMessage)
        publish()
    }
    private fun publish() { phone.showState(snapshot.value) }

    private fun requireValidDecision(value: PlannerDecision, call: Int) {
        val valid = if (call == 1) when (value.decision) {
            "VERIFY" -> value.status in setOf("COMPLETE", "INCOMPLETE", "UNVERIFIABLE")
            "EXECUTE" -> value.status == "INCOMPLETE"
            "FALLBACK" -> value.status in setOf("INCOMPLETE", "UNVERIFIABLE")
            else -> false
        } else when (value.decision) {
            "FINISH" -> value.status == "COMPLETE" && value.objective.isEmpty() && value.conditions.isEmpty()
            "EXECUTE" -> value.status == "INCOMPLETE"
            "FALLBACK" -> value.status in setOf("INCOMPLETE", "UNVERIFIABLE")
            else -> false
        }
        if (!valid || (value.decision != "FINISH" && value.objective.isBlank()) ||
            value.conditions.any { it.isBlank() } ||
            (value.decision in setOf("VERIFY", "EXECUTE") && value.conditions.isEmpty()))
            throw RunFailure("MODEL", "INVALID_OUTPUT", protocolReason = "COORDINATOR_PLANNER_REJECTED")
    }

    private fun requireValidReply(reply: GuiReply, phase: Phase) {
        val action = reply.action
        val args = action.arguments
        fun keys(vararg names: String) = args.keys == names.toSet()
        fun number(name: String, min: Long, max: Long): Boolean {
            val n = args[name]
            return (n is Byte || n is Short || n is Int || n is Long) && (n as Number).toLong() in min..max
        }
        val valid = when (action.name) {
            "tap" -> keys("x", "y") && number("x", 0, 999) && number("y", 0, 999)
            "long_press" -> keys("x", "y", "duration_ms") && number("x", 0, 999) && number("y", 0, 999) && number("duration_ms", 300, 2000)
            "swipe" -> keys("from_x", "from_y", "to_x", "to_y", "duration_ms") &&
                listOf("from_x", "from_y", "to_x", "to_y").all { number(it, 0, 999) } && number("duration_ms", 100, 2000)
            "input_text" -> keys("text") && args["text"] is String
            "press_key" -> keys("key") && args["key"] in setOf("back", "home", "enter")
            "launch_app" -> keys("package_name") && args["package_name"] is String && args["package_name"] in options?.authorizedPackages.orEmpty()
            "wait" -> keys("duration_ms") && number("duration_ms", 100, 2000)
            "finish" -> keys("outcome", "message") && args["outcome"] in setOf("completed", "unable") && (args["message"] as? String)?.isNotBlank() == true
            else -> false
        }
        if (reply.callId.isBlank() || reply.argumentsJson.isBlank() || !valid ||
            (phase in setOf(Phase.VERIFY, Phase.CONFIRM) && action.name !in setOf("wait", "finish")))
            throw RunFailure("MODEL", "INVALID_OUTPUT", protocolReason = "COORDINATOR_REPLY_REJECTED")
    }

    private companion object {
        val ACTION_RESULTS = setOf("completed", "rejected", "cancelled", "unknown", "device_error")
        val ERROR_CODES = mapOf(
            "MODEL" to setOf("TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT"),
            "DEVICE" to setOf("AUTHORIZATION_UNAVAILABLE", "SCREENSHOT_FAILED", "INPUT_FAILED", "INPUT_STATUS_UNKNOWN", "SERVICE_DISCONNECTED"),
            "COORDINATOR" to setOf("INVALID_STATE", "PAUSE_UNCONFIRMED", "STOP_ENTRY_UNAVAILABLE", "OBSERVATION_UNAVAILABLE"),
        )
    }
}
