package org.mobileharness.app.platform

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import org.mobileharness.app.R
import org.mobileharness.app.core.RunSnapshot
import org.mobileharness.app.core.RunState
import org.mobileharness.app.ui.AppLanguage
import org.mobileharness.app.ui.AppStrings
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Only this small window consumes touches; there is no full-screen transparent layer. */
internal class OverlayControlPanel(
    private val context: Context,
    private val onCommand: (ControlCommand) -> Unit,
    private val onAvailabilityChanged: () -> Unit,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = rounded(Color.WHITE, 28, Color.rgb(211, 222, 230))
        elevation = dp(4).toFloat()
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val ball = controlButton("MH").apply {
        textSize = 12f
        includeFontPadding = false
        setPadding(dp(2), 0, dp(2), 0)
        setTypeface(typeface, Typeface.BOLD)
    }
    private val primary = controlButton(text(R.string.platform_overlay_takeover)).apply {
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        compoundDrawablePadding = dp(6)
        background = buttonSurface(Color.rgb(230, 242, 255), 20)
    }
    private val stop = controlButton("").apply {
        tag = ControlCommand.STOP
        background = buttonSurface(Color.rgb(255, 239, 239), 20)
        setCompoundDrawables(null, null, null, null)
        // A centered vector, independent of font glyphs and system emoji rendering.
        foreground = OverlayControlIcon(context, OverlayControlIcon.Symbol.STOP, Color.rgb(180, 35, 44))
    }
    private val params = WindowManager.LayoutParams(
        dp(56), dp(56), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.LEFT }
    private var side = OverlayGeometry.Side.LEFT
    private var expanded = false
    private var attached = false
    private var hiddenForCapture = false
    private var hiddenEpoch = 0L
    private var dragging = false
    private var snapshot = RunSnapshot()
    private var statusPresentation: Triple<String, Tone, Boolean>? = null
    private var preferredY = dp(140)

    val available: Boolean get() = attached && !hiddenForCapture && root.isShown
    val installed: Boolean get() = attached && root.isAttachedToWindow

    init {
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = onAvailabilityChanged()
            override fun onViewDetachedFromWindow(view: View) {
                attached = false
                hiddenEpoch++
                onAvailabilityChanged()
            }
        })
        root.addView(ball, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(primary, LinearLayout.LayoutParams(dp(84), dp(48)))
        root.addView(stop, LinearLayout.LayoutParams(dp(48), dp(48)))
        ball.setOnClickListener {
            if (attached && !hiddenForCapture) {
                expanded = !expanded
                updateLayout()
            }
        }
        primary.setOnClickListener {
            if (primary.isEnabled && primary.isShown) (primary.tag as? ControlCommand)?.let(::dispatch)
        }
        stop.setOnClickListener { if (stop.isEnabled && stop.isShown) dispatch(ControlCommand.STOP) }
        root.setOnApplyWindowInsetsListener { _, insets ->
            updateLayout()
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateLayout() }
        installDrag()
    }

    fun attach() {
        if (attached) return
        hiddenForCapture = false
        expanded = false
        root.visibility = View.VISIBLE
        updateLayout()
        try {
            manager.addView(root, params)
            attached = true
            render(snapshot)
        } catch (_: Exception) {
            attached = false
        }
        onAvailabilityChanged()
    }

    fun detach() {
        hiddenEpoch++
        if (attached) try { manager.removeViewImmediate(root) } catch (_: Exception) { }
        attached = false
        onAvailabilityChanged()
    }

    fun setCaptureHidden(hidden: Boolean) {
        // A stale drag/click or queued layout callback cannot reveal a capture-hidden root.
        hiddenEpoch++
        dragging = false
        hiddenForCapture = hidden
        expanded = false
        root.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        updateLayout()
        onAvailabilityChanged()
    }

    fun bounds(): Rect? {
        if (!available) return null
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + root.width, location[1] + root.height)
    }

    fun conflictsWithGesture(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val box = bounds() ?: return false
        return OverlayGeometry.intersects(OverlayGeometry.Area(box.left, box.top, box.right, box.bottom), x1, y1, x2, y2)
    }

    /** Caller must await layout frames and recheck its dispatch gate and actual bounds. */
    fun relocateAwayFrom(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        if (!available || snapshot.state !in setOf(RunState.AUTO, RunState.PREPARING, RunState.CHECKING)) return false
        hiddenEpoch++
        dragging = false
        expanded = false
        updateLayout()
        val candidate = OverlayGeometry.clearDock(safeArea(), params.width, params.height, preferredY, side, x1, y1, x2, y2)
            ?: return false
        side = candidate.first
        preferredY = candidate.second.top
        updateLayout()
        return installed && !hiddenForCapture
    }

    fun render(value: RunSnapshot) {
        if (snapshot.state != value.state) {
            hiddenEpoch++
            dragging = false
            expanded = false
        }
        snapshot = value
        val active = value.state != RunState.IDLE && value.state != RunState.ENDED
        stop.isEnabled = active && value.state != RunState.STOPPING
        stop.alpha = if (stop.isEnabled) 1f else 0.45f
        stop.contentDescription = when {
            !active -> text(R.string.platform_stop_inactive)
            value.state == RunState.UNCERTAIN -> text(R.string.platform_stop_uncertain)
            value.state == RunState.STOPPING -> text(R.string.platform_stop_stopping)
            else -> text(R.string.platform_stop_direct)
        }
        val command = when (value.state) {
            RunState.AUTO -> ControlCommand.TAKEOVER.takeUnless { value.handoffUsed }
            RunState.MANUAL -> ControlCommand.RETURN
            RunState.UNCERTAIN -> ControlCommand.RECHECK
            RunState.BLOCKED -> ControlCommand.RETRY
            RunState.IDLE, RunState.ENDED -> ControlCommand.OPEN
            else -> null
        }
        primary.tag = command
        primary.isEnabled = command != null
        primary.alpha = if (primary.isEnabled) 1f else 0.42f
        primary.text = when (command) {
            ControlCommand.RETURN -> text(R.string.platform_overlay_return)
            ControlCommand.RETRY -> text(R.string.platform_overlay_retry)
            ControlCommand.RECHECK -> text(R.string.platform_overlay_check)
            ControlCommand.OPEN -> text(R.string.platform_overlay_open)
            else -> when {
                value.state == RunState.PAUSING -> text(R.string.platform_overlay_wait)
                value.handoffUsed -> text(R.string.platform_overlay_returned)
                else -> text(R.string.platform_overlay_takeover)
            }
        }
        primary.contentDescription = command?.localizedLabel(context) ?: when {
            value.state == RunState.PAUSING -> text(R.string.platform_wait_before_return)
            value.handoffUsed -> text(R.string.platform_handoff_used)
            else -> text(R.string.platform_takeover_unavailable)
        }
        primary.setTextColor(Color.rgb(22, 89, 147))
        val symbol = when (command) {
            ControlCommand.RETURN -> OverlayControlIcon.Symbol.PLAY
            ControlCommand.RETRY, ControlCommand.RECHECK -> OverlayControlIcon.Symbol.REFRESH
            ControlCommand.OPEN -> OverlayControlIcon.Symbol.OPEN
            else -> OverlayControlIcon.Symbol.HAND
        }
        primary.setCompoundDrawables(icon(symbol, Color.rgb(22, 89, 147), 18), null, null, null)
        updateLayout()
    }

    /** Refresh presentation without recreating the window or changing control ownership. */
    fun refreshLanguage() {
        statusPresentation = null
        render(snapshot)
    }

    private fun dispatch(command: ControlCommand) {
        if (!available) return
        expanded = false
        updateLayout()
        onCommand(command)
    }

    private fun safeArea(): OverlayGeometry.Area {
        val metrics = manager.maximumWindowMetrics
        val display = metrics.bounds
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val keyboard = root.rootWindowInsets?.getInsets(WindowInsets.Type.ime())
        val margin = dp(4)
        val left = display.left + max(bars.left, keyboard?.left ?: 0) + margin
        val top = display.top + max(bars.top, keyboard?.top ?: 0) + margin
        val right = (display.right - max(bars.right, keyboard?.right ?: 0) - margin).coerceAtLeast(left + 1)
        val bottom = (display.bottom - max(bars.bottom, keyboard?.bottom ?: 0) - margin).coerceAtLeast(top + 1)
        return OverlayGeometry.Area(left, top, right, bottom)
    }

    private fun updateLayout() {
        if (dragging) return
        val safe = safeArea()
        val active = snapshot.state != RunState.IDLE && snapshot.state != RunState.ENDED
        primary.visibility = if (expanded) View.VISIBLE else View.GONE
        stop.visibility = if (expanded || active) View.VISIBLE else View.GONE
        renderStatus()
        val controlHeight = max(dp(48), ceil(max(ball.paint.fontSpacing, primary.paint.fontSpacing) + dp(20)).toInt())
        var statusWidth = max(dp(if (expanded) 100 else 48), ceil(ball.paint.measureText(ball.text.toString())).toInt() + dp(if (expanded) 38 else 18))
        var primaryWidth = max(dp(84), ceil(primary.paint.measureText(primary.text.toString())).toInt() + dp(42))
        val horizontalWidth = statusWidth + (if (expanded) primaryWidth + dp(8) else 0) + (if (expanded || active) dp(56) else 0) + dp(8)
        val vertical = expanded && horizontalWidth > safe.width && safe.height >= controlHeight * 3 + dp(24)
        if (expanded && !vertical && horizontalWidth > safe.width) {
            // A short IME-safe region may prevent a vertical layout. Reserve the
            // stop target first, compress labels only, and keep all touch targets.
            val labelSpace = safe.width - dp(72) // stop + two gaps + outer padding
            if (labelSpace < dp(96)) {
                // Three 48dp targets cannot fit: retain the compact stop entry.
                expanded = false
                updateLayout()
                return
            }
            statusWidth = (labelSpace.toLong() * statusWidth / (statusWidth + primaryWidth)).toInt()
                .coerceIn(dp(48), labelSpace - dp(48))
            primaryWidth = labelSpace - statusWidth
        }
        root.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val width = (if (vertical) max(statusWidth, primaryWidth) + dp(8) else horizontalWidth).coerceAtMost(safe.width)
        val height = (if (vertical) controlHeight * 3 + dp(24) else controlHeight + dp(8)).coerceAtMost(safe.height)
        fun size(view: View, requestedWidth: Int, gap: Boolean) {
            val viewWidth = if (vertical) width - dp(8) else requestedWidth
            val next = LinearLayout.LayoutParams(viewWidth, controlHeight).apply {
                marginStart = if (!vertical && gap) dp(8) else 0
                topMargin = if (vertical && gap) dp(8) else 0
            }
            val old = view.layoutParams as LinearLayout.LayoutParams
            if (old.width != next.width || old.height != next.height || old.marginStart != next.marginStart || old.topMargin != next.topMargin) view.layoutParams = next
        }
        size(ball, statusWidth, false)
        size(primary, primaryWidth, true)
        size(stop, dp(48), true)
        val dock = OverlayGeometry.dock(safe, width, height, preferredY, side)
        val changed = params.x != dock.left || params.y != dock.top || params.width != width || params.height != height
        params.x = dock.left
        params.y = dock.top
        params.width = width
        params.height = height
        val accessibleStatus = if (snapshot.state == RunState.ENDED && snapshot.message == "模型判断结束；结果待独立确认")
            text(R.string.platform_model_ended_accessible) else ball.text.toString()
        ball.contentDescription = text(if (expanded) R.string.platform_collapse_controls else R.string.platform_expand_controls, accessibleStatus)
        // Never changes visibility here: only setCaptureHidden owns capture visibility.
        if (attached && changed) try { manager.updateViewLayout(root, params) } catch (_: Exception) {
            onAvailabilityChanged()
        }
    }

    /** Fixed presentation only: never render counters, task content or runtime explanations. */
    private fun renderStatus() {
        val label = when (snapshot.state) {
            RunState.IDLE -> if (expanded) text(R.string.platform_ready) else "MH"
            RunState.AUTO -> text(if (expanded) R.string.platform_running else R.string.platform_running_compact)
            RunState.PAUSING -> text(if (expanded) R.string.platform_pausing else R.string.platform_pausing_compact)
            RunState.MANUAL -> text(R.string.platform_manual)
            RunState.PREPARING -> text(if (expanded) R.string.platform_preparing else R.string.platform_preparing_compact)
            RunState.CHECKING -> text(if (expanded) R.string.platform_checking else R.string.platform_checking_compact)
            RunState.STOPPING -> text(if (expanded) R.string.platform_stopping else R.string.platform_stopping_compact)
            RunState.UNCERTAIN -> text(if (expanded) R.string.platform_uncertain else R.string.platform_uncertain_compact)
            RunState.BLOCKED -> text(if (expanded) R.string.platform_blocked else R.string.platform_blocked_compact)
            RunState.ENDED -> when (snapshot.message) {
                // Exact fixed runtime messages, never arbitrary error or task text.
                "模型判断结束；结果待独立确认" -> text(R.string.platform_completed)
                "已停止，可继续手动操作" -> text(if (expanded) R.string.platform_stopped else R.string.platform_stopped_compact)
                else -> if (AppStrings.runtimeFailureCode(snapshot.message) != null)
                    text(R.string.platform_failed) else text(if (expanded) R.string.platform_ended else R.string.platform_ended_compact)
            }
        }
        val tone = when (snapshot.state) {
            RunState.AUTO -> Tone.RUNNING
            RunState.PAUSING, RunState.MANUAL, RunState.UNCERTAIN, RunState.BLOCKED -> Tone.HUMAN
            RunState.STOPPING -> Tone.STOPPED
            RunState.ENDED -> when (snapshot.message) {
                "模型判断结束；结果待独立确认" -> Tone.RUNNING
                "已停止，可继续手动操作" -> Tone.STOPPED
                else -> if (AppStrings.runtimeFailureCode(snapshot.message) != null) Tone.STOPPED else Tone.NEUTRAL
            }
            RunState.PREPARING, RunState.CHECKING -> Tone.PREPARING
            RunState.IDLE -> Tone.NEUTRAL
        }
        val key = Triple(label, tone, expanded)
        if (statusPresentation == key) return
        statusPresentation = key
        ball.text = label
        ball.setTextColor(tone.ink)
        ball.background = buttonSurface(tone.surface, 20)
        ball.compoundDrawablePadding = dp(if (expanded) 6 else 4)
        ball.setCompoundDrawables(icon(if (snapshot.state == RunState.ENDED && snapshot.message == "模型判断结束；结果待独立确认") OverlayControlIcon.Symbol.CHECK else OverlayControlIcon.Symbol.DOT,
            tone.ink, if (expanded) 16 else 10), null, null, null)
    }

    private enum class Tone(val ink: Int, val surface: Int) {
        RUNNING(Color.rgb(22, 101, 62), Color.rgb(234, 247, 239)),
        HUMAN(Color.rgb(138, 82, 6), Color.rgb(255, 246, 222)),
        PREPARING(Color.rgb(28, 91, 154), Color.rgb(234, 243, 254)),
        STOPPED(Color.rgb(180, 35, 44), Color.rgb(255, 239, 239)),
        NEUTRAL(Color.rgb(72, 86, 101), Color.rgb(242, 245, 248)),
    }

    private fun icon(symbol: OverlayControlIcon.Symbol, color: Int, size: Int) = OverlayControlIcon(context, symbol, color).apply {
        setBounds(0, 0, dp(size), dp(size))
    }

    private fun text(resource: Int, vararg arguments: Any): String = AppLanguage.context(context).getString(resource, *arguments)

    private fun installDrag() {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        var touchEpoch = 0L
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        ball.setOnTouchListener { _, event ->
            if (!attached || hiddenForCapture) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchEpoch = hiddenEpoch
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> if (touchEpoch == hiddenEpoch) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        dragging = true
                        val safe = safeArea()
                        params.x = (startX + dx.roundToInt()).coerceIn(safe.left, (safe.right - params.width).coerceAtLeast(safe.left))
                        params.y = (startY + dy.roundToInt()).coerceIn(safe.top, (safe.bottom - params.height).coerceAtLeast(safe.top))
                        try { manager.updateViewLayout(root, params) } catch (_: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP -> if (touchEpoch == hiddenEpoch) {
                    dragging = false
                    if (moved) {
                        val safe = safeArea()
                        side = if (params.x + params.width / 2 < safe.left + safe.width / 2) OverlayGeometry.Side.LEFT else OverlayGeometry.Side.RIGHT
                        preferredY = params.y
                        updateLayout()
                    } else ball.performClick()
                }
                MotionEvent.ACTION_CANCEL -> if (touchEpoch == hiddenEpoch) {
                    dragging = false
                    updateLayout()
                }
            }
            true
        }
    }

    private fun controlButton(label: String) = Button(context, null, 0).apply {
        text = label
        gravity = Gravity.CENTER
        includeFontPadding = false
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(4), 0, dp(4), 0)
        setTextColor(Color.rgb(17, 31, 42))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = buttonSurface(Color.TRANSPARENT, 24)
    }

    private fun buttonSurface(color: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(44, 50, 130, 200)),
        rounded(color, radius), rounded(Color.WHITE, radius),
    )

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
