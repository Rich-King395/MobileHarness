package org.mobileharness.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.AtomicFile
import android.view.View
import org.json.JSONObject
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.platform.OverlayControlPanel
import java.io.File

/**
 * Explicit-component, DUMP-protected debug probe. It does not open an Activity, alter controls,
 * read target accessibility/window/package state, or capture pixels. Only our own readiness
 * flags and our own overlay View state are inspected, on the main thread.
 */
class DebugOverlayReadinessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val result = JSONObject().put("status", "BLOCKED").put("code", "SERVICE_UNAVAILABLE")
        if (Looper.myLooper() != Looper.getMainLooper()) {
            result.put("status", "FAIL").put("code", "NOT_MAIN_THREAD")
            write(context, result)
            return
        }
        val service = HarnessAccessibilityService.instance
        result.put("service_instance_present", service != null)
        if (service != null) {
            result.put("service_connected", service.connected)
            val app = context.applicationContext as HarnessApplication
            result.put("controls_match_runtime", service.controls === app.runtime)
                .put("runtime_state", app.runtime.snapshot.value.state.name)
            val readiness = service.readiness.value
            result.put("readiness_connected", readiness.connected)
                .put("readiness_unlocked", readiness.unlocked)
                .put("readiness_overlay_stop_available", readiness.overlayStopAvailable)
                .put("readiness_notification_control_available", readiness.notificationControlAvailable)
                .put("readiness_sensitive_page_visible", readiness.sensitivePageVisible)
            try {
                val panel = HarnessAccessibilityService::class.java.getDeclaredField("overlay").let {
                    it.isAccessible = true
                    it.get(service) as? OverlayControlPanel
                }
                result.put("panel_present", panel != null)
                if (panel == null) {
                    result.put("status", "BLOCKED").put("code", "OVERLAY_UNAVAILABLE")
                } else {
                    result.put("panel_available", panel.available).put("panel_installed", panel.installed)
                    for ((field, key) in listOf("attached" to "panel_attached_flag", "hiddenForCapture" to "panel_hidden_for_capture")) {
                        result.put(key, OverlayControlPanel::class.java.getDeclaredField(field).let {
                            it.isAccessible = true
                            it.getBoolean(panel)
                        })
                    }
                    val root = OverlayControlPanel::class.java.getDeclaredField("root").let {
                        it.isAccessible = true
                        it.get(panel) as View
                    }
                    result.put("root_is_shown", root.isShown)
                        .put("root_is_visible", root.visibility == View.VISIBLE)
                        .put("root_is_invisible", root.visibility == View.INVISIBLE)
                        .put("root_is_gone", root.visibility == View.GONE)
                        .put("root_attached_to_window", root.isAttachedToWindow)
                        .put("status", "PASS").put("code", "READINESS_FLAGS_SAMPLED")
                }
            } catch (_: Exception) {
                result.put("status", "FAIL").put("code", "OVERLAY_REFLECTION_FAILED")
            }
        }
        write(context, result)
    }

    private fun write(context: Context, result: JSONObject) {
        val file = AtomicFile(File(context.noBackupFilesDir, "overlay-readiness-verification.json"))
        val stream = file.startWrite()
        try { stream.write(result.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
    }

    companion object {
        const val ACTION = "org.mobileharness.app.action.DEBUG_READ_OVERLAY_READINESS"
    }
}
