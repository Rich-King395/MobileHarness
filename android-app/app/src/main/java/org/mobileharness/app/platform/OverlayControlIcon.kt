package org.mobileharness.app.platform

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Small vector controls, drawn without font glyphs, bitmaps or external assets. */
internal class OverlayControlIcon(context: Context, private val symbol: Symbol, color: Int) : Drawable() {
    enum class Symbol { DOT, HAND, PLAY, STOP, REFRESH, OPEN, CHECK }
    private val iconSize = 20 * context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        val size = minOf(bounds.width().toFloat(), bounds.height().toFloat(), iconSize)
        val saved = canvas.save()
        canvas.translate(bounds.exactCenterX() - size / 2, bounds.exactCenterY() - size / 2)
        canvas.scale(size / 24, size / 24)
        paint.style = Paint.Style.STROKE
        when (symbol) {
            Symbol.DOT -> { paint.style = Paint.Style.FILL; canvas.drawCircle(12f, 12f, 6f, paint) }
            Symbol.STOP -> { paint.style = Paint.Style.FILL; canvas.drawRoundRect(5f, 5f, 19f, 19f, 2f, 2f, paint) }
            Symbol.PLAY -> {
                val path = Path().apply { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }
                canvas.drawPath(path, paint)
            }
            Symbol.CHECK -> canvas.drawPath(Path().apply { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 7f) }, paint)
            Symbol.REFRESH -> {
                canvas.drawArc(5f, 5f, 19f, 19f, 25f, 300f, false, paint)
                canvas.drawPath(Path().apply { moveTo(15f, 4f); lineTo(20f, 5f); lineTo(19f, 10f) }, paint)
            }
            Symbol.OPEN -> {
                canvas.drawPath(Path().apply { moveTo(10f, 5f); lineTo(5f, 5f); lineTo(5f, 19f); lineTo(19f, 19f); lineTo(19f, 14f) }, paint)
                canvas.drawPath(Path().apply { moveTo(13f, 5f); lineTo(19f, 5f); lineTo(19f, 11f); moveTo(10f, 14f); lineTo(19f, 5f) }, paint)
            }
            Symbol.HAND -> canvas.drawPath(Path().apply {
                moveTo(7f, 12f); lineTo(7f, 6f); quadTo(7f, 3f, 10f, 5f)
                lineTo(10f, 11f); lineTo(10f, 4f); quadTo(12f, 1f, 13f, 4f)
                lineTo(13f, 11f); lineTo(13f, 5f); quadTo(16f, 3f, 16f, 6f)
                lineTo(16f, 12f); lineTo(16f, 8f); quadTo(19f, 6f, 19f, 10f)
                lineTo(19f, 15f); quadTo(19f, 21f, 13f, 21f)
                lineTo(11f, 21f); quadTo(8f, 21f, 6f, 18f)
                lineTo(3f, 13f); quadTo(3f, 10f, 6f, 12f); lineTo(8f, 15f)
            }, paint)
        }
        canvas.restoreToCount(saved)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity is not used")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
