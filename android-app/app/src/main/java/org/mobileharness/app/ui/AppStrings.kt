package org.mobileharness.app.ui

import android.content.Context
import org.mobileharness.app.R
import org.mobileharness.app.core.RunState

/** Presentation translations only. Runtime messages, model history and audit codes stay unchanged. */
object AppStrings {
    fun stateLabel(context: Context, state: RunState): String = AppLanguage.context(context).getString(when (state) {
        RunState.IDLE -> R.string.runtime_state_idle
        RunState.AUTO -> R.string.runtime_state_auto
        RunState.PAUSING -> R.string.runtime_state_pausing
        RunState.MANUAL -> R.string.runtime_state_manual
        RunState.PREPARING -> R.string.runtime_state_preparing
        RunState.CHECKING -> R.string.runtime_state_checking
        RunState.STOPPING -> R.string.runtime_state_stopping
        RunState.UNCERTAIN -> R.string.runtime_state_uncertain
        RunState.BLOCKED -> R.string.runtime_state_blocked
        RunState.ENDED -> R.string.runtime_state_ended
    })

    fun runtimeMessage(context: Context, message: String): String {
        val localized = AppLanguage.context(context)
        messages[message]?.let { return localized.getString(it) }
        planning.matchEntire(message)?.let { return localized.getString(R.string.runtime_planning, it.groupValues[1].toInt()) }
        plannerReply.matchEntire(message)?.let { return localized.getString(R.string.runtime_wait_planner, it.groupValues[1].toInt()) }
        automaticRecovery.matchEntire(message)?.let { return localized.getString(R.string.runtime_automatic_recovery, it.groupValues[1].toInt()) }
        runtimeFailureCode(message)?.let { return localized.getString(R.string.runtime_failure_with_code, it) }
        paused.matchEntire(message)?.let { match ->
            // Only translate the fixed executor description. The original code remains verbatim.
            val detail = pauseDetails[match.groupValues[1]] ?: return message
            return localized.getString(R.string.runtime_paused_with_code, localized.getString(detail), match.groupValues[2])
        }
        // Model/user content and future unknown messages are never guessed or silently rewritten.
        return message
    }

    private val planning = Regex("恢复规划 ([12])/2")
    private val plannerReply = Regex("等待 Planner 回复（([12])/2）")
    private val paused = Regex("已暂停 · (.+)（([A-Z][A-Z0-9_]{0,63})）")
    private val automaticRecovery = Regex("正在自动恢复（([12])/2）")
    private val runtimeFailure = Regex("任务因运行错误结束（([A-Z][A-Z0-9_]{0,63})）")
    private val failureCodes = setOf("TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT", "AUTHORIZATION_UNAVAILABLE",
        "SCREENSHOT_FAILED", "INPUT_FAILED", "INPUT_STATUS_UNKNOWN", "SERVICE_DISCONNECTED", "INVALID_STATE",
        "PAUSE_UNCONFIRMED", "STOP_ENTRY_UNAVAILABLE", "OBSERVATION_UNAVAILABLE")
    internal fun runtimeFailureCode(message: String): String? = runtimeFailure.matchEntire(message)
        ?.groupValues?.get(1)?.takeIf { it in failureCodes }

    private val messages = mapOf(
        "准备就绪" to R.string.runtime_ready,
        "已暂停，请处理设备或模型问题" to R.string.runtime_paused_generic,
        "获取当前画面" to R.string.runtime_capture_current,
        "正在结束当前操作，请等待交接确认" to R.string.runtime_wait_handoff,
        "归还检查及获取新画面" to R.string.runtime_return_check,
        "控制权已归还" to R.string.runtime_returned,
        "归还检查失败，可继续手动或修复后再试" to R.string.runtime_return_failed,
        "设备条件不可用，可继续手动，修复后再归还" to R.string.runtime_manual_device_unavailable,
        "已请求停止，仍需确认当前操作终态" to R.string.runtime_stop_unconfirmed,
        "停止处理中" to R.string.runtime_stopping,
        "暂停未确认，继续禁止自动操作" to R.string.runtime_pause_unconfirmed,
        "人工控制，你可以自由操作" to R.string.runtime_manual_granted,
        "模型判断结束；结果待独立确认" to R.string.runtime_model_ended,
        "模型无法继续" to R.string.runtime_model_unable,
        "已用完 GUI 额度" to R.string.runtime_budget_exhausted,
        "任务已中断" to R.string.runtime_interrupted,
        "已停止，可继续手动操作" to R.string.runtime_user_stopped,
        "等待 GUI 模型回复" to R.string.runtime_wait_gui,
        "等待画面刷新" to R.string.runtime_wait_refresh,
        "正在点击" to R.string.runtime_tapping,
        "正在长按" to R.string.runtime_long_pressing,
        "正在滑动" to R.string.runtime_swiping,
        "正在输入文字" to R.string.runtime_typing,
        "正在执行按键" to R.string.runtime_pressing_key,
        "正在打开 App" to R.string.runtime_launching_app,
        "正在执行操作" to R.string.runtime_executing,
        "日志写入失败，任务已结束；请检查本机存储后重启应用。" to R.string.runtime_audit_failed_ended,
        "日志写入失败，正在等待当前操作终态；请结束任务并重启应用。" to R.string.runtime_audit_failed_waiting,
        "日志写入失败，请结束当前任务并检查本机存储后重启应用。" to R.string.runtime_audit_failed,
    )

    private val pauseDetails = mapOf(
        "当前输入受保护" to R.string.runtime_reason_protected_input,
        "当前目标窗口不在授权范围" to R.string.runtime_reason_not_authorized,
        "设备锁屏或屏幕未就绪" to R.string.runtime_reason_device_not_ready,
        "没有可用的当前画面或前台窗口" to R.string.runtime_reason_no_observation,
        "输入结果尚未确认" to R.string.runtime_reason_input_unknown,
        "设备或当前窗口暂不可用" to R.string.runtime_reason_device_unavailable,
        "当前画面获取失败" to R.string.runtime_reason_capture_failed,
        "输入终态尚未确认" to R.string.runtime_reason_terminal_unknown,
        "操作失败，原因尚未确认" to R.string.runtime_reason_action_failed,
        "模型请求未完成" to R.string.runtime_reason_request_incomplete,
        "模型服务返回错误" to R.string.runtime_reason_api_error,
        "模型回复未通过动作协议校验" to R.string.runtime_reason_protocol_error,
        "控制入口不可用" to R.string.runtime_reason_controls_unavailable,
        "运行状态需要检查" to R.string.runtime_reason_needs_check,
    )
}
