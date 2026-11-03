package org.mobileharness.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.mobileharness.app.R
import org.mobileharness.app.ui.AppLanguage

/** Manifest-private receiver. Never authenticate a broadcast using Binder.getCallingUid(). */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val taskId = intent.getStringExtra(EXTRA_TASK) ?: return
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        HarnessAccessibilityService.instance?.handleNotificationCommand(taskId, token, command)
    }

    companion object {
        const val ACTION = "org.mobileharness.app.INTERNAL_CONTROL"
        const val EXTRA_TASK = "task_id"
        const val EXTRA_TOKEN = "state_token"
        const val EXTRA_COMMAND = "command"
    }
}

internal enum class ControlCommand(val wire: String, val label: String) {
    TAKEOVER("takeover", "接管"),
    RETURN("return", "归还控制"),
    STOP("stop", "停止任务"),
    RETRY("retry", "重试"),
    RECHECK("recheck", "重新检查暂停"),
    OPEN("open", "打开应用");

    /** Wire names and legacy labels remain stable; only the visible label is localized. */
    fun localizedLabel(context: Context): String = AppLanguage.context(context).getString(when (this) {
        TAKEOVER -> R.string.platform_command_takeover
        RETURN -> R.string.platform_command_return
        STOP -> R.string.platform_command_stop
        RETRY -> R.string.platform_command_retry
        RECHECK -> R.string.platform_command_recheck
        OPEN -> R.string.platform_command_open
    })
}
