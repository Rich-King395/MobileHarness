package org.mobileharness.app.verification

import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.Display
import org.mobileharness.app.core.PhoneAction
import org.mobileharness.app.core.ScreenFrame
import org.mobileharness.app.platform.PlatformDiagnosticObserver

/** Only fixed predicates and dimensions leave this probe. All image/path data is borrowed in RAM. */
internal class CalculatorFidelityEvidence : PlatformDiagnosticObserver {
    private var imageFields: Map<String, Any?> = emptyMap()
    private var actionFields: Map<String, Any?> = emptyMap()
    private var expected: Pair<Float, Float>? = null
    private var expectedFrame: ScreenFrame? = null
    private var previousNativeTerminal: Long? = null

    @Synchronized override fun imageEncoded(source: Bitmap, png: ByteArray, screenshotUptimeMs: Long, requestedUptimeMs: Long) {
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
        try {
            val dimensions = decoded != null && decoded.width == source.width && decoded.height == source.height
            val a = IntArray(source.width)
            val b = IntArray(source.width)
            var mismatches = 0L
            try {
                if (dimensions) for (y in 0 until source.height) {
                    source.getPixels(a, 0, a.size, 0, y, a.size, 1)
                    decoded!!.getPixels(b, 0, b.size, 0, y, b.size, 1)
                    for (x in a.indices) if (a[x] != b[x]) mismatches++
                }
            } finally { a.fill(0); b.fill(0) }
            imageFields = mapOf(
                "image_source_png_equal" to (dimensions && mismatches == 0L),
                "image_bitmap_same_as" to (decoded != null && source.sameAs(decoded)),
                "image_argb_mismatch_pixels" to if (dimensions) mismatches else null,
                "image_source_has_alpha" to source.hasAlpha(), "image_decoded_has_alpha" to decoded?.hasAlpha(),
                "image_source_premultiplied" to source.isPremultiplied, "image_decoded_premultiplied" to decoded?.isPremultiplied,
                "image_source_color_space_id" to source.colorSpace?.id, "image_decoded_color_space_id" to decoded?.colorSpace?.id,
                "image_dimensions_equal" to dimensions,
                "source_width" to source.width, "source_height" to source.height,
                "capture_timestamp_since_request" to (screenshotUptimeMs >= requestedUptimeMs),
                "capture_after_previous_native_terminal" to previousNativeTerminal?.let { screenshotUptimeMs >= it },
                "capture_age_ms" to (SystemClock.uptimeMillis() - screenshotUptimeMs),
            )
        } finally { decoded?.recycle() }
    }

    @Synchronized fun latestImage(): Map<String, Any?> = imageFields.toMap()
    @Synchronized fun resetImage() { imageFields = emptyMap() }

    @Synchronized fun beginAction(action: PhoneAction, frame: ScreenFrame) {
        actionFields = emptyMap()
        expectedFrame = frame
        expected = if (action.name == "tap") {
            val x = integer(action.arguments["x"])
            val y = integer(action.arguments["y"])
            if (x != null && y != null) Pair((x * frame.width / 1000).toFloat(), (y * frame.height / 1000).toFloat()) else null
        } else null
    }

    @Synchronized override fun gestureDispatch(description: GestureDescription, frame: ScreenFrame) {
        actionFields = actionFields + mapOf(
            "native_probe_seen" to true,
            "native_same_frame_object" to (frame === expectedFrame),
            "native_path_matches_normalized_point" to pathMatches(description),
            "native_default_display" to (description.displayId == Display.DEFAULT_DISPLAY),
        )
    }

    @Synchronized override fun gestureTerminal(description: GestureDescription, status: String) {
        previousNativeTerminal = SystemClock.uptimeMillis()
        actionFields = actionFields + mapOf(
            "native_callback_path_matches" to pathMatches(description),
            "native_callback_completed" to (status == "completed"),
        )
    }

    @Synchronized fun endAction(): Map<String, Any?> {
        val fields = actionFields.toMap()
        expected = null
        expectedFrame = null
        actionFields = emptyMap()
        return fields
    }

    @Synchronized fun clear() {
        expected = null; expectedFrame = null; actionFields = emptyMap(); imageFields = emptyMap()
    }

    private fun pathMatches(description: GestureDescription): Boolean? {
        val point = expected ?: return null
        if (description.strokeCount != 1) return false
        val stroke = description.getStroke(0)
        val path = stroke.path.approximate(0.01f)
        return try {
            path.isNotEmpty() && path.size % 3 == 0 && path.indices.step(3).all {
                path[it + 1] == point.first && path[it + 2] == point.second
            } && stroke.duration == 100L && stroke.startTime == 0L
        } finally { path.fill(0f) }
    }

    private fun integer(value: Any?): Long? = when (value) { is Int -> value.toLong(); is Long -> value; else -> null }
        ?.takeIf { it in 0..999 }
}
