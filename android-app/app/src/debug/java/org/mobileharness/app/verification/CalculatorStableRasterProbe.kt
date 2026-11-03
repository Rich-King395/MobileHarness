package org.mobileharness.app.verification

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mobileharness.app.core.ScreenFrame
import org.mobileharness.app.platform.HarnessAccessibilityService
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Independent, later compositor snapshot of an unchanged Calculator page. It neither calls
 * PhonePort.observe nor changes the production frame token, capture gate, overlay or actions.
 * Only fixed predicates/counts leave this probe. Both bitmaps and all pixel arrays are RAM-only.
 */
internal object CalculatorStableRasterProbe {
    suspend fun compare(
        service: HarnessAccessibilityService,
        frame: ScreenFrame,
        formulaBounds: Rect,
        keyBounds: Collection<Rect>,
        canRead: () -> Boolean,
    ): Map<String, Any?> {
        val base = mapOf<String, Any?>(
            "reference_snapshot_added_delay_ms" to 350,
            "source_capture_timing_unaltered" to true,
            "model_request_delayed_for_reference" to true,
        )
        if (!canRead()) return base + mapOf("raster_reference_status" to "OWNERSHIP_LOST")
        delay(350) // Planned single reference; no screenshot/model/action retry.
        if (!canRead()) return base + mapOf("raster_reference_status" to "OWNERSHIP_LOST")
        return try {
            val reference = withTimeoutOrNull(5_000) { snapshot(service, canRead) }
                ?: return base + mapOf("raster_reference_status" to "UNAVAILABLE")
            try {
                if (!canRead()) return base + mapOf("raster_reference_status" to "OWNERSHIP_LOST")
                withContext(Dispatchers.Default) {
                    val original = BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size)
                        ?: return@withContext base + mapOf("raster_reference_status" to "ORIGINAL_DECODE_FAILED")
                    try {
                        val geometry = original.width == frame.width && original.height == frame.height &&
                            reference.bitmap.width == frame.width && reference.bitmap.height == frame.height
                        if (!geometry) return@withContext base + mapOf("raster_reference_status" to "GEOMETRY_MISMATCH",
                            "raster_reference_dimensions_equal" to false)
                        if (!canRead()) return@withContext base + mapOf("raster_reference_status" to "OWNERSHIP_LOST")
                        val formula = compareRect(original, reference.bitmap, formulaBounds)
                        // Central half contains labels and excludes most rounded edges/ripples.
                        val keys = keyBounds.mapNotNull { bounds ->
                            val center = Rect(bounds).apply { inset(width() / 4, height() / 4) }
                            compareRect(original, reference.bitmap, center)
                        }
                        base + mapOf(
                            "raster_reference_status" to "COMPARED",
                            "raster_reference_dimensions_equal" to true,
                            "raster_reference_timestamp_since_request" to (reference.timestamp >= reference.requested),
                            "raster_reference_age_ms" to (SystemClock.uptimeMillis() - reference.timestamp),
                            "formula_raster_region_available" to (formula != null),
                            "formula_raster_pixels" to formula?.pixels,
                            "formula_raster_rgb_gt8_mismatch_pixels" to formula?.rgbMismatch,
                            "formula_raster_dark_mask_mismatch_pixels" to formula?.darkMismatch,
                            "formula_raster_rgb_within8" to formula?.let { it.rgbMismatch == 0 },
                            "formula_raster_dark_mask_exact" to formula?.let { it.darkMismatch == 0 },
                            "formula_raster_rgb_within8_995_permille" to formula?.let { it.rgbMismatch.toLong() * 1000 <= it.pixels.toLong() * 5 },
                            "keypad_raster_regions_requested" to keyBounds.size,
                            "keypad_raster_regions_compared" to keys.size,
                            "keypad_raster_rgb_within8_regions" to keys.count { it.rgbMismatch == 0 },
                            "keypad_raster_dark_mask_exact_regions" to keys.count { it.darkMismatch == 0 },
                            "keypad_raster_pixels" to keys.sumOf { it.pixels.toLong() },
                            "keypad_raster_rgb_gt8_mismatch_pixels" to keys.sumOf { it.rgbMismatch.toLong() },
                            "keypad_raster_dark_mask_mismatch_pixels" to keys.sumOf { it.darkMismatch.toLong() },
                        )
                    } finally { original.recycle() }
                }
            } finally { reference.bitmap.recycle() }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { base + mapOf("raster_reference_status" to "PROBE_FAILED") }
    }

    private data class Snapshot(val bitmap: Bitmap, val requested: Long, val timestamp: Long)
    private suspend fun snapshot(service: HarnessAccessibilityService, canRead: () -> Boolean): Snapshot? =
        suspendCancellableCoroutine { continuation ->
            if (!canRead()) { continuation.resume(null); return@suspendCancellableCoroutine }
            val requested = SystemClock.uptimeMillis()
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, Dispatchers.Default.asExecutor(), object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        var wrapped: Bitmap? = null
                        var software: Bitmap? = null
                        try {
                            if (!continuation.isActive || !canRead()) {
                                if (continuation.isActive) continuation.resume(null)
                                return
                            }
                            wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            val pixels = software
                            if (pixels == null || !canRead() || !continuation.isActive) {
                                if (continuation.isActive) continuation.resume(null)
                                return
                            }
                            continuation.resume(Snapshot(pixels, requested, screenshot.timestamp)) { _, value, _ -> value?.bitmap?.recycle() }
                            software = null // continuation owns this short-lived reference
                        } catch (_: Exception) { if (continuation.isActive) continuation.resume(null)
                        } finally { software?.recycle(); wrapped?.recycle(); buffer.close() }
                    }
                    override fun onFailure(errorCode: Int) { if (continuation.isActive) continuation.resume(null) }
                })
            } catch (_: Exception) { if (continuation.isActive) continuation.resume(null) }
        }

    private data class Difference(val pixels: Int, val rgbMismatch: Int, val darkMismatch: Int)
    private fun compareRect(original: Bitmap, reference: Bitmap, bounds: Rect): Difference? {
        if (bounds.isEmpty || bounds.left < 0 || bounds.top < 0 || bounds.right > original.width || bounds.bottom > original.height) return null
        val width = bounds.width()
        val height = bounds.height()
        val before = IntArray(width * height)
        val after = IntArray(width * height)
        try {
            original.getPixels(before, 0, width, bounds.left, bounds.top, width, height)
            reference.getPixels(after, 0, width, bounds.left, bounds.top, width, height)
            var rgb = 0
            var dark = 0
            for (index in before.indices) {
                val a = before[index]
                val b = after[index]
                if (abs(Color.red(a) - Color.red(b)) > 8 || abs(Color.green(a) - Color.green(b)) > 8 || abs(Color.blue(a) - Color.blue(b)) > 8) rgb++
                if (isDark(a) != isDark(b)) dark++
            }
            return Difference(before.size, rgb, dark)
        } finally { before.fill(0); after.fill(0) }
    }
    private fun isDark(color: Int): Boolean = Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114 < 128_000
}
