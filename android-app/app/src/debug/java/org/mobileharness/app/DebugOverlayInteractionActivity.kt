package org.mobileharness.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.mobileharness.app.verification.OverlayInteractionVerification

/** DUMP-only debug run. The overlay commands must come from actual external UI touches. */
class DebugOverlayInteractionActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 50)
            addView(TextView(this@DebugOverlayInteractionActivity).apply {
                text = "悬浮交互验证\n仅合成测试页，零网络请求；请使用悬浮控件完成接管与归还。"
                textSize = 18f
            })
            addView(Button(this@DebugOverlayInteractionActivity).apply {
                text = "停止验证"
                setOnClickListener { OverlayInteractionVerification.command("abort") }
            })
        })
        startRun()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (job?.isActive != true) startRun()
    }

    private fun startRun() {
        if (job?.isActive == true) return
        val harness = intent.getBooleanExtra("harness", true)
        // Keep the test stage and the receiver on the same Main actor.
        job = scope.launch {
            OverlayInteractionVerification(applicationContext).run(harness)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Geometry is returned only in ordered-broadcast resultData, never written to logs/files. */
class DebugOverlayInteractionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        resultData = OverlayInteractionVerification.command(intent.getStringExtra("command") ?: "probe").toString()
        resultCode = Activity.RESULT_OK
    }

    companion object {
        const val ACTION = "org.mobileharness.app.action.DEBUG_OVERLAY_INTERACTION"
    }
}
