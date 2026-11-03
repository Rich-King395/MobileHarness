package org.mobileharness.app.verification

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.mobileharness.app.R
import org.mobileharness.app.core.RunSnapshot
import org.mobileharness.app.core.RunState
import org.mobileharness.app.core.RuntimeControls
import org.mobileharness.app.platform.ControlCommand
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.platform.OverlayControlPanel
import org.mobileharness.app.platform.OverlayGeometry
import org.mobileharness.app.ui.AppLanguage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * An isolated real View/WindowManager presentation check. It never configures a session,
 * swaps live controls, reads target accessibility nodes, captures the display or calls a model.
 * The optional PNG is root.draw(Canvas) of our synthetic controls only, never a device frame.
 */
class OverlayCompactVerification(
    context: Context,
    private val previewLocales: List<String> = emptyList(),
    private val imageFileName: String = "overlay-compact-synthetic.png",
) {
    private val context = context.applicationContext

    data class Result(
        val status: String = "RUNNING",
        val code: String = "PENDING",
        val flags: Map<String, Boolean> = emptyMap(),
        val cases: List<JSONObject> = emptyList(),
        val imageWritten: Boolean = false,
        val controlsUnchanged: Boolean = false,
    )

    private data class Case(
        val name: String,
        val state: RunState,
        val command: ControlCommand?,
        val used: Boolean = false,
        val message: String = PRIVATE_SENTINEL,
        val expectedStatus: Int,
    )

    suspend fun run(report: (Result) -> Unit) {
        if (!RUNNING.compareAndSet(false, true)) {
            report(Result(status = "BLOCKED", code = "VERIFICATION_ALREADY_RUNNING"))
            return
        }
        val flags = linkedMapOf<String, Boolean>()
        val results = mutableListOf<JSONObject>()
        val drawings = mutableListOf<Pair<String, Bitmap>>()
        var service: HarnessAccessibilityService? = null
        var oldControls: RuntimeControls? = null
        var panel: OverlayControlPanel? = null
        var imageWritten = false
        var controlsUnchanged = false
        var failure: String? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                val live = HarnessAccessibilityService.instance ?: fail("SERVICE_UNAVAILABLE")
                val controls = live.controls ?: fail("CONTROLS_UNAVAILABLE")
                requireIdle(live, controls)
                service = live
                oldControls = controls
            }
            val live = checkNotNull(service)
            val controls = checkNotNull(oldControls)
            val languages = if (previewLocales.isEmpty()) listOf<String?>(null) else previewLocales
            for (languageTag in languages) for (scale in listOf(1.0f, 1.5f, 2.0f)) {
                val commands = mutableListOf<ControlCommand>()
                withContext(Dispatchers.Main.immediate) {
                    requireIdle(live, controls)
                    val configuration = Configuration(live.resources.configuration).apply { fontScale = scale }
                    val resources = live.createConfigurationContext(configuration).resources
                    val presentationContext = object : ContextWrapper(live) {
                        override fun getResources(): Resources = resources
                    }
                    val localized = if (languageTag == null) presentationContext else AppLanguage.previewContext(presentationContext, languageTag)
                    panel = OverlayControlPanel(localized, { commands.add(it) }, {})
                    checkNotNull(panel).attach()
                }
                settle()
                val owned = checkNotNull(panel)
                for (case in CASES) {
                    withContext(Dispatchers.Main.immediate) {
                        requireIdle(live, controls)
                        owned.setCaptureHidden(true)
                        owned.setCaptureHidden(false)
                        owned.render(RunSnapshot(state = case.state, handoffUsed = case.used,
                            guiUsed = 17, plannerUsed = 2, budget = 30, message = case.message))
                        (field(owned, "ball") as View).performClick()
                    }
                    settle()
                    withContext(Dispatchers.Main.immediate) {
                        requireIdle(live, controls)
                        val root = field(owned, "root") as LinearLayout
                        val ball = field(owned, "ball") as Button
                        val primary = field(owned, "primary") as Button
                        val stop = field(owned, "stop") as Button
                        val density = root.resources.displayMetrics.density
                        val minTouch = (48 * density).roundToInt()
                        val gap = (8 * density).roundToInt()
                        val views = listOf(ball, primary, stop)
                        require(root.childCount == 3 && (0 until root.childCount).all { root.getChildAt(it) === views[it] }, "THREE_COMPONENT_STRUCTURE")
                        require(views.all { it.visibility == View.VISIBLE && it.isShown }, "THREE_VISIBLE_COMPONENTS")
                        require(views.all { it.width >= minTouch && it.height >= minTouch }, "TOUCH_TARGET_TOO_SMALL")
                        require((primary.tag as? ControlCommand) == case.command, "PRIMARY_COMMAND_MISMATCH")
                        require(primary.isEnabled == (case.command != null), "PRIMARY_ENABLED_MISMATCH")
                        val active = case.state !in setOf(RunState.IDLE, RunState.ENDED)
                        require(stop.isEnabled == (active && case.state != RunState.STOPPING), "STOP_ENABLED_MISMATCH")
                        require(stop.tag == ControlCommand.STOP, "STOP_COMMAND_MISMATCH")
                        val visibleText = descendants(root).filterIsInstance<TextView>().joinToString("|") { it.text.toString() }
                        val descriptions = descendants(root).joinToString("|") { it.contentDescription?.toString().orEmpty() }
                        require(!visibleText.contains(PRIVATE_SENTINEL) && !descriptions.contains(PRIVATE_SENTINEL), "DYNAMIC_MESSAGE_LEAK")
                        require(!visibleText.contains("17/30") && !visibleText.contains("Planner") && !visibleText.contains("2/2"), "COUNTS_STILL_VISIBLE")
                        require(!visibleText.contains("结果待独立确认") && !visibleText.contains("画面采集已关闭"), "EXPLANATION_STILL_VISIBLE")
                        require(views.all { !it.contentDescription.isNullOrBlank() }, "CONTROL_DESCRIPTION_MISSING")
                        val expectedContext = AppLanguage.context(root.context)
                        require(ball.text.toString() == expectedContext.getString(case.expectedStatus), "STATUS_OR_LANGUAGE_MISMATCH")
                        if (languageTag == "en") require(!HAN.containsMatchIn(visibleText + descriptions), "ENGLISH_CONTROL_HAS_CHINESE")
                        val rects = views.map { Rect().also(it::getHitRect) }
                        require(rects.all { it.left >= 0 && it.top >= 0 && it.right <= root.width && it.bottom <= root.height }, "CONTROL_CLIPPED")
                        for ((first, second) in rects.zipWithNext()) {
                            require(second.left - first.right >= gap || second.top - first.bottom >= gap, "CONTROL_GAP_TOO_SMALL")
                        }
                        val safe = safeArea(owned)
                        val bounds = owned.bounds() ?: fail("OVERLAY_NOT_VISIBLE")
                        require(bounds.left >= safe.left && bounds.top >= safe.top && bounds.right <= safe.right && bounds.bottom <= safe.bottom, "CURRENT_INSETS_NOT_RESPECTED")
                        if (scale == 1.0f) require(root.height <= (64 * density).roundToInt(), "STANDARD_CONTROL_TOO_TALL")

                        if (scale == 1.0f && case.name in DRAWN_CASES) {
                            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                            root.draw(Canvas(bitmap))
                            drawings.add("${languageTag ?: "current"}/${case.name}" to bitmap)
                        }
                        val before = commands.size
                        if (primary.isEnabled) {
                            primary.performClick()
                            require(commands.size == before + 1 && commands.last() == case.command, "PRIMARY_DISPATCH_MISMATCH")
                        } else {
                            primary.performClick()
                            require(commands.size == before, "DISABLED_PRIMARY_DISPATCHED")
                        }
                        // Capture-hidden state must remain invisible even when a stale click or
                        // render is delivered. No AccessibilityService screenshot is taken here.
                        owned.setCaptureHidden(true)
                        val hiddenCommands = commands.size
                        ball.performClick()
                        primary.performClick()
                        stop.performClick()
                        require(root.visibility == View.INVISIBLE && !owned.available && commands.size == hiddenCommands, "CAPTURE_HIDDEN_INPUT_LEAK")
                        owned.setCaptureHidden(false)
                        require(primary.visibility == View.GONE, "CAPTURE_RESTORE_NOT_COMPACT")
                        if (active) require(stop.visibility == View.VISIBLE, "COMPACT_STOP_NOT_AVAILABLE")
                        results.add(JSONObject().put("id", case.name).put("font_scale", scale)
                            .put("language_tag", languageTag ?: "current")
                            .put("status", "PASS").put("visible_components", 3)
                            .put("primary_command", case.command?.name ?: "NONE")
                            .put("primary_enabled", case.command != null)
                            .put("expanded_width_dp", (bounds.width() / density).roundToInt())
                            .put("expanded_height_dp", (bounds.height() / density).roundToInt())
                            .put("layout", if (rects[1].top > rects[0].bottom) "VERTICAL" else "HORIZONTAL"))
                    }
                }
                withContext(Dispatchers.Main.immediate) { owned.detach(); panel = null }
            }
            flags["three_components_all_states"] = true
            flags["no_counts_or_dynamic_explanation"] = true
            flags["minimum_48dp_touch_targets"] = true
            flags["minimum_8dp_component_gaps"] = true
            flags["primary_command_and_disabled_states"] = true
            flags["pausing_does_not_offer_return"] = true
            flags["used_handoff_does_not_offer_second_takeover"] = true
            flags["direct_stop_preserved_in_compact_active_state"] = true
            flags["status_completion_stop_and_other_end_distinct"] = true
            flags["current_safe_insets_respected"] = true
            flags["standard_font_single_compact_row"] = true
            flags["font_scale_1_5_and_2_checked_without_device_setting_changes"] = true
            flags["hidden_root_rejects_stale_clicks"] = true
            flags["capture_restore_is_compact"] = true
            if (previewLocales.isNotEmpty()) flags["preview_locales_without_preference_changes"] = true
            withContext(Dispatchers.Main.immediate) { requireIdle(live, controls) }
            imageWritten = writeSyntheticSheet(drawings)
            if (!imageWritten) fail("SYNTHETIC_DRAWING_NOT_WRITTEN")
        } catch (cancelled: CancellationException) {
            failure = "CANCELLED"
        } catch (known: Failure) {
            failure = known.code
        } catch (_: Exception) {
            failure = "VERIFICATION_EXCEPTION"
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                panel?.detach()
                controlsUnchanged = service != null && service?.controls === oldControls
            }
            drawings.forEach { it.second.recycle() }
            flags["temporary_panel_detached"] = panel?.installed != true
            flags["runtime_controls_unchanged"] = controlsUnchanged
            RUNNING.set(false)
            report(Result(status = if (failure == null && controlsUnchanged) "PASS" else if (failure == "CANCELLED") "CANCELLED" else "FAIL",
                code = failure ?: if (controlsUnchanged) "COMPACT_CONTROL_VERIFIED" else "CONTROLS_CHANGED",
                flags = flags.toMap(), cases = results.toList(), imageWritten = imageWritten, controlsUnchanged = controlsUnchanged))
        }
    }

    private fun requireIdle(service: HarnessAccessibilityService, controls: RuntimeControls) {
        if (!service.connected || service.inputInFlight || service.controls !== controls ||
            controls.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED) || service.captureEnabled) fail("RUNTIME_NOT_IDLE")
    }

    private fun field(panel: OverlayControlPanel, name: String): Any = OverlayControlPanel::class.java.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(panel) ?: fail("OWN_CONTROL_FIELD_UNAVAILABLE")
    }

    private fun safeArea(panel: OverlayControlPanel): OverlayGeometry.Area =
        OverlayControlPanel::class.java.getDeclaredMethod("safeArea").let {
            it.isAccessible = true
            it.invoke(panel) as OverlayGeometry.Area
        }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private suspend fun settle() = withContext(Dispatchers.Main.immediate) {
        withTimeout(2_000) {
            repeat(2) { suspendCancellableCoroutine { continuation ->
                val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
                Choreographer.getInstance().postFrameCallback(callback)
                continuation.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
            } }
        }
    }

    private fun writeSyntheticSheet(drawings: List<Pair<String, Bitmap>>): Boolean {
        if (drawings.isEmpty()) return false
        val padding = 28
        val labelHeight = 34
        val width = drawings.maxOf { it.second.width } + padding * 2
        val height = drawings.sumOf { it.second.height + labelHeight + padding }
        val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.rgb(243, 246, 249))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(58, 68, 78); textSize = 22f }
            var y = 0
            for ((label, drawing) in drawings) {
                canvas.drawText(label, padding.toFloat(), (y + labelHeight - 8).toFloat(), paint)
                canvas.drawBitmap(drawing, padding.toFloat(), (y + labelHeight).toFloat(), null)
                y += drawing.height + labelHeight + padding
            }
            require(imageFileName in setOf("overlay-compact-synthetic.png", "localization-synthetic-controls.png"), "INVALID_SYNTHETIC_IMAGE_PATH")
            File(context.noBackupFilesDir, imageFileName).outputStream().use {
                return sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { sheet.recycle() }
    }

    private class Failure(val code: String) : Exception()
    private fun fail(code: String): Nothing = throw Failure(code)
    private fun require(value: Boolean, code: String) { if (!value) fail(code) }

    companion object {
        private const val PRIVATE_SENTINEL = "PRIVATE_DYNAMIC_EXPLANATION_17_30_PLANNER_2_2"
        private val RUNNING = AtomicBoolean(false)
        private val HAN = Regex("[\\u4e00-\\u9fff]")
        private val DRAWN_CASES = setOf("AUTO", "PAUSING", "MANUAL", "AUTO_RETURN_USED", "BLOCKED", "ENDED_MODEL", "ENDED_STOP")
        private val CASES = listOf(
            Case("IDLE", RunState.IDLE, ControlCommand.OPEN, expectedStatus = R.string.platform_ready),
            Case("AUTO", RunState.AUTO, ControlCommand.TAKEOVER, expectedStatus = R.string.platform_running),
            Case("PAUSING", RunState.PAUSING, null, expectedStatus = R.string.platform_pausing),
            Case("MANUAL", RunState.MANUAL, ControlCommand.RETURN, expectedStatus = R.string.platform_manual),
            Case("PREPARING", RunState.PREPARING, null, expectedStatus = R.string.platform_preparing),
            Case("CHECKING", RunState.CHECKING, null, expectedStatus = R.string.platform_checking),
            Case("STOPPING", RunState.STOPPING, null, expectedStatus = R.string.platform_stopping),
            Case("UNCERTAIN", RunState.UNCERTAIN, ControlCommand.RECHECK, expectedStatus = R.string.platform_uncertain),
            Case("BLOCKED", RunState.BLOCKED, ControlCommand.RETRY, expectedStatus = R.string.platform_blocked),
            Case("AUTO_RETURN_USED", RunState.AUTO, null, used = true, expectedStatus = R.string.platform_running),
            Case("ENDED_MODEL", RunState.ENDED, ControlCommand.OPEN, message = "模型判断结束；结果待独立确认", expectedStatus = R.string.platform_completed),
            Case("ENDED_STOP", RunState.ENDED, ControlCommand.OPEN, message = "已停止，可继续手动操作", expectedStatus = R.string.platform_stopped),
            Case("ENDED_BUDGET", RunState.ENDED, ControlCommand.OPEN, message = "已用完 GUI 额度", expectedStatus = R.string.platform_ended),
        )
    }
}
