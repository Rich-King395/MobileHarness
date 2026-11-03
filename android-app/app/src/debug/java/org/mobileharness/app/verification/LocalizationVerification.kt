package org.mobileharness.app.verification

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.mobileharness.app.core.RunState
import org.mobileharness.app.R
import org.mobileharness.app.ui.AppLanguage
import org.mobileharness.app.ui.AppStrings
import org.mobileharness.app.ui.UiMessages
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed synthetic display strings and private overlay Views only; no live language changes. */
class LocalizationVerification(private val context: Context) {
    data class Result(
        val status: String = "RUNNING",
        val code: String = "PENDING",
        val flags: Map<String, Boolean> = emptyMap(),
        val runtimeCases: Int = 0,
        val overlayCases: List<JSONObject> = emptyList(),
        val imageWritten: Boolean = false,
        val controlsUnchanged: Boolean = false,
    )

    suspend fun run(report: (Result) -> Unit) {
        if (!RUNNING.compareAndSet(false, true)) {
            report(Result(status = "BLOCKED", code = "VERIFICATION_ALREADY_RUNNING"))
            return
        }
        val flags = linkedMapOf<String, Boolean>()
        var checked = 0
        val languageBefore = AppLanguage.choice(context)
        var overlay: OverlayCompactVerification.Result? = null
        var failure: String? = null
        try {
            val english = AppLanguage.previewContext(context, "en")
            val chinese = AppLanguage.previewContext(context, "zh")
            for (message in FIXED_MESSAGES) {
                require(!HAN.containsMatchIn(AppStrings.runtimeMessage(english, message)), "FIXED_RUNTIME_EN_NOT_TRANSLATED")
                require(AppStrings.runtimeMessage(chinese, message) == message, "FIXED_RUNTIME_ZH_CHANGED")
                checked++
            }
            flags["fixed_runtime_english_translated"] = true
            flags["fixed_runtime_chinese_unchanged"] = true
            for ((detail, code) in PAUSE_CASES) {
                val original = "已暂停 · $detail（$code）"
                val en = AppStrings.runtimeMessage(english, original)
                require(!HAN.containsMatchIn(en) && en.endsWith("($code)"), "PAUSE_CODE_OR_TRANSLATION_CHANGED")
                require(AppStrings.runtimeMessage(chinese, original) == original, "PAUSE_CHINESE_CHANGED")
                checked++
            }
            flags["pause_error_codes_preserved"] = true
            for (index in 1..2) for (message in listOf("恢复规划 $index/2", "等待 Planner 回复（$index/2）")) {
                val en = AppStrings.runtimeMessage(english, message)
                require(!HAN.containsMatchIn(en) && en.contains("$index/2"), "PLANNING_INDEX_CHANGED")
                require(AppStrings.runtimeMessage(chinese, message) == message, "PLANNING_CHINESE_CHANGED")
                checked++
            }
            flags["planner_indices_and_limit_preserved"] = true
            for ((state, chineseLabel) in STATE_LABELS) {
                require(!HAN.containsMatchIn(AppStrings.stateLabel(english, state)), "STATE_EN_NOT_TRANSLATED")
                require(AppStrings.stateLabel(chinese, state) == chineseLabel, "STATE_ZH_CHANGED")
                checked++
            }
            flags["all_state_labels_translated"] = true
            for (unknown in listOf("SYNTHETIC_USER_TEXT_不要翻译", "已暂停 · 未知动态描述（PRIVATE_CODE）")) {
                require(AppStrings.runtimeMessage(english, unknown) == unknown && AppStrings.runtimeMessage(chinese, unknown) == unknown,
                    "UNKNOWN_TEXT_REWRITTEN")
                checked++
            }
            flags["unknown_content_unchanged"] = true
            for (resource in listOf(R.string.ui_config_read_failed, R.string.ui_scope_save_failed, R.string.language_change_failed)) {
                for (source in listOf(english, chinese)) {
                    val original = source.getString(resource)
                    require(UiMessages.localize(english, original) == english.getString(resource), "STORED_UI_NOTICE_EN_CHANGED")
                    require(UiMessages.localize(chinese, original) == chinese.getString(resource), "STORED_UI_NOTICE_ZH_CHANGED")
                    checked++
                }
            }
            flags["stored_ui_notices_relocalized"] = true
            flags["preview_contexts_keep_languages_separate"] =
                AppStrings.runtimeMessage(english, "准备就绪") == "Ready" && AppStrings.runtimeMessage(chinese, "准备就绪") == "准备就绪"
            require(flags["preview_contexts_keep_languages_separate"] == true, "PREVIEW_CONTEXT_LEAK")

            OverlayCompactVerification(context, listOf("en", "zh"), "localization-synthetic-controls.png").run { overlay = it }
            val controls = checkNotNull(overlay)
            require(controls.status == "PASS", "OVERLAY_${controls.code}")
            require(controls.cases.size == 78, "OVERLAY_CASES_INCOMPLETE")
            controls.flags.forEach { (key, value) -> flags["overlay_$key"] = value }
        } catch (_: CancellationException) {
            failure = "CANCELLED"
        } catch (known: Failure) {
            failure = known.code
        } catch (_: Exception) {
            failure = "LOCALIZATION_VERIFICATION_EXCEPTION"
        } finally {
            flags["actual_language_preference_unchanged"] = AppLanguage.choice(context) == languageBefore
            if (flags["actual_language_preference_unchanged"] != true && failure == null) failure = "LANGUAGE_PREFERENCE_CHANGED"
            RUNNING.set(false)
            report(Result(status = if (failure == null) "PASS" else if (failure == "CANCELLED") "CANCELLED" else "FAIL",
                code = failure ?: "LOCALIZATION_VERIFIED", flags = flags.toMap(), runtimeCases = checked,
                overlayCases = overlay?.cases.orEmpty(), imageWritten = overlay?.imageWritten == true,
                controlsUnchanged = overlay?.controlsUnchanged == true))
        }
    }

    private class Failure(val code: String) : Exception()
    private fun require(value: Boolean, code: String) { if (!value) throw Failure(code) }

    companion object {
        private val RUNNING = AtomicBoolean(false)
        private val HAN = Regex("[\\u4e00-\\u9fff]")
        private val FIXED_MESSAGES = listOf(
            "准备就绪", "已暂停，请处理设备或模型问题", "获取当前画面", "正在结束当前操作，请等待交接确认",
            "归还检查及获取新画面", "控制权已归还", "归还检查失败，可继续手动或修复后再试",
            "设备条件不可用，可继续手动，修复后再归还", "已请求停止，仍需确认当前操作终态", "停止处理中",
            "暂停未确认，继续禁止自动操作", "人工控制，你可以自由操作", "模型判断结束；结果待独立确认",
            "模型无法继续", "已用完 GUI 额度", "任务已中断", "已停止，可继续手动操作", "等待 GUI 模型回复",
            "等待画面刷新", "正在点击", "正在长按", "正在滑动", "正在输入文字", "正在执行按键", "正在打开 App", "正在执行操作",
            "日志写入失败，任务已结束；请检查本机存储后重启应用。", "日志写入失败，正在等待当前操作终态；请结束任务并重启应用。",
            "日志写入失败，请结束当前任务并检查本机存储后重启应用。",
        )
        private val PAUSE_CASES = listOf(
            "当前输入受保护" to "INPUT_FAILED", "当前目标窗口不在授权范围" to "INPUT_FAILED",
            "设备锁屏或屏幕未就绪" to "AUTHORIZATION_UNAVAILABLE", "没有可用的当前画面或前台窗口" to "SCREENSHOT_FAILED",
            "输入结果尚未确认" to "INPUT_STATUS_UNKNOWN", "设备或当前窗口暂不可用" to "SERVICE_DISCONNECTED",
            "当前画面获取失败" to "SCREENSHOT_FAILED", "输入终态尚未确认" to "PAUSE_UNCONFIRMED",
            "操作失败，原因尚未确认" to "INPUT_FAILED", "模型请求未完成" to "TRANSPORT_ERROR",
            "模型服务返回错误" to "API_ERROR", "模型回复未通过动作协议校验" to "INVALID_OUTPUT",
            "控制入口不可用" to "STOP_ENTRY_UNAVAILABLE", "运行状态需要检查" to "INVALID_STATE",
        )
        private val STATE_LABELS = listOf(
            RunState.IDLE to "准备就绪", RunState.AUTO to "自动执行", RunState.PAUSING to "暂停中 · 请等待交接确认",
            RunState.MANUAL to "人工控制", RunState.PREPARING to "恢复准备", RunState.CHECKING to "只读查证／确认",
            RunState.STOPPING to "停止处理中", RunState.UNCERTAIN to "暂停未确认", RunState.BLOCKED to "已暂停 · 需处理", RunState.ENDED to "流程已结束",
        )
    }
}
