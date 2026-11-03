package org.mobileharness.app.ui

import android.content.Context
import android.content.res.Configuration
import org.mobileharness.app.R
import java.util.Locale

/** Re-resolves fixed UI notices already held in RAM when the display language changes. */
internal object UiMessages {
    /**
     * [context] is the current localized UI context (provided by AppLanguage).
     * Only exact English/Chinese values from this closed resource list are translated.
     * Call at UI-notice/error outlets, never on task text, configuration names or model content.
     * No message, locale lookup or matched value is written to storage or a log.
     */
    fun localize(context: Context, message: String): String {
        if (message.isEmpty()) return message
        val english = comparisonContext(context, Locale.ENGLISH)
        val chinese = comparisonContext(context, Locale.SIMPLIFIED_CHINESE)
        for (resource in NOTICE_RESOURCES) {
            if (message == english.getString(resource) || message == chinese.getString(resource)) {
                return context.getString(resource)
            }
        }
        return message
    }

    private fun comparisonContext(context: Context, locale: Locale): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(locale)
        })

    // These messages have no format arguments. Never expand this to all UI resources or
    // perform substring matching: a notice must match one fixed, content-free value.
    private val NOTICE_RESOURCES = intArrayOf(
        R.string.language_change_failed,
        R.string.record_delete_failed,
        R.string.ui_startup_cancelled,
        R.string.ui_startup_unavailable,
        R.string.ui_default_home_required,
        R.string.ui_opening_home,
        R.string.ui_home_not_ready,
        R.string.ui_home_not_ready_toast,
        R.string.ui_home_open_failed,
        R.string.ui_config_read_failed,
        R.string.ui_config_update_failed,
        R.string.ui_notifications_granted,
        R.string.ui_notifications_not_granted,
        R.string.ui_export_selection_expired,
        R.string.ui_export_running_cancelled,
        R.string.ui_export_success,
        R.string.ui_export_no_records,
        R.string.ui_export_failed,
        R.string.ui_session_deleted,
        R.string.ui_session_delete_unavailable,
        R.string.ui_all_records_deleted,
        R.string.ui_assessment_saved,
        R.string.ui_scope_read_failed,
        R.string.ui_scope_save_failed,
        R.string.ui_overlay_unavailable,
        R.string.ui_verification_controls_unavailable,
        R.string.ui_scope_saving,
        R.string.ui_scope_loading,
        R.string.ui_task_preparing,
        R.string.ui_enter_task,
        R.string.ui_set_scope,
        R.string.ui_choose_required_models,
        R.string.ui_connect_required_controls,
        R.string.ui_valid_budget_required,
        R.string.ui_confirm_remote_send,
    )
}
