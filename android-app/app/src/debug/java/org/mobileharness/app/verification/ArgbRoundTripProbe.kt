package org.mobileharness.app.verification

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.SystemClock
import org.mobileharness.app.platform.PlatformDiagnosticObserver
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

/** Same-frame, local-only color diagnostics. Controls are disposable copies, never model inputs. */
internal class ArgbRoundTripProbe : PlatformDiagnosticObserver {
    private var regions: List<ArgbDifferenceAnalyzer.Region> = emptyList()
    private var result: Map<String, Any?> = emptyMap()
    private var generation = 0L

    @Synchronized fun prepare(regions: List<ArgbDifferenceAnalyzer.Region>) {
        generation++; this.regions = regions.toList(); result = emptyMap()
    }
    @Synchronized fun latest(): Map<String, Any?> = result.toMap()
    @Synchronized fun clear() { generation++; regions = emptyList(); result = emptyMap() }
    override fun imageEncoded(source: Bitmap, png: ByteArray, screenshotUptimeMs: Long, requestedUptimeMs: Long) {
        val ticket = synchronized(this) { generation to regions.toList() }
        val inspected = inspect(source, png, ticket.second) + mapOf(
            "capture_timestamp_since_request" to (screenshotUptimeMs >= requestedUptimeMs),
            "capture_age_at_probe_end_ms" to (SystemClock.uptimeMillis() - screenshotUptimeMs),
        )
        synchronized(this) { if (generation == ticket.first) result = inspected }
    }
    fun analyzeSynthetic(source: Bitmap): Map<String, Any?> {
        val capturedRegions = synchronized(this) { regions.toList() }
        val png = ByteArrayOutputStream().use { out ->
            check(source.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
        }
        return try { inspect(source, png, capturedRegions) } finally { png.fill(0) }
    }

    private fun inspect(source: Bitmap, png: ByteArray, capturedRegions: List<ArgbDifferenceAnalyzer.Region>): Map<String, Any?> {
        val start = SystemClock.uptimeMillis()
        val beforeGeneration = source.generationId
        val pngDigest = MessageDigest.getInstance("SHA-256").digest(png)
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("PNG_DECODE_FAILED")
        val pixels = try { IntArray(source.width * source.height) }
            catch (failure: Throwable) { decoded.recycle(); pngDigest.fill(0); throw failure }
        val other = try { IntArray(pixels.size) }
            catch (failure: Throwable) { pixels.fill(0); decoded.recycle(); pngDigest.fill(0); throw failure }
        try {
            check(decoded.width == source.width && decoded.height == source.height) { "GEOMETRY_MISMATCH" }
            source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            decoded.getPixels(other, 0, source.width, 0, 0, source.width, source.height)
            val activeRegions = capturedRegions.filter { it.right <= source.width && it.bottom <= source.height }
            val primary = ArgbDifferenceAnalyzer.analyze(pixels, other, source.width, source.height, activeRegions)
            val structure = thresholdMasks(pixels, other, source.width, activeRegions)
            val native = nativeRows(source, decoded)
            val copyControl = decoded.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
            val controls = linkedMapOf<String, Any?>()
            if (copyControl != null) try {
                copyControl.getPixels(other, 0, source.width, 0, 0, source.width, source.height)
                controls["mutable_copy_before_retag"] = compact(pixels, other) + nativeRows(source, copyControl)
                source.colorSpace?.let { originalSpace ->
                    copyControl.setColorSpace(originalSpace)
                    copyControl.getPixels(other, 0, source.width, 0, 0, source.width, source.height)
                    controls["copy_retagged_with_source_java_color_space"] = compact(pixels, other) + nativeRows(source, copyControl)
                }
            } finally { copyControl.recycle() }
            val preferred = BitmapFactory.Options().apply {
                inPreferredConfig = source.config ?: Bitmap.Config.ARGB_8888
                inPreferredColorSpace = source.colorSpace
            }
            val preferredBitmap = BitmapFactory.decodeByteArray(png, 0, png.size, preferred)
            if (preferredBitmap != null) try {
                preferredBitmap.getPixels(other, 0, source.width, 0, 0, source.width, source.height)
                controls["decode_preferred_source_color_space"] = compact(pixels, other) + nativeRows(source, preferredBitmap)
            } finally { preferredBitmap.recycle() }
            val srgbOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            val srgb = BitmapFactory.decodeByteArray(png, 0, png.size, srgbOptions)
            if (srgb != null) try {
                srgb.getPixels(other, 0, source.width, 0, 0, source.width, source.height)
                controls["decode_preferred_srgb"] = compact(pixels, other)
            } finally { srgb.recycle() }
            val afterDigest = MessageDigest.getInstance("SHA-256").digest(png)
            val unchanged = try { MessageDigest.isEqual(pngDigest, afterDigest) } finally { afterDigest.fill(0) }
            return linkedMapOf(
                "status" to "ANALYZED", "same_frame_comparison" to true,
                "width" to source.width, "height" to source.height,
                "source_config" to source.config?.name, "decoded_config" to decoded.config?.name,
                "source_row_bytes" to source.rowBytes, "decoded_row_bytes" to decoded.rowBytes,
                "source_color_space" to colorMetadata(source.colorSpace), "decoded_color_space" to colorMetadata(decoded.colorSpace),
                "java_color_spaces_equal" to (source.colorSpace == decoded.colorSpace),
                "source_has_alpha" to source.hasAlpha(), "decoded_has_alpha" to decoded.hasAlpha(),
                "source_premultiplied" to source.isPremultiplied, "decoded_premultiplied" to decoded.isPremultiplied,
                "bitmap_same_as" to source.sameAs(decoded), "native_storage" to native,
                "argb_readback" to primary, "threshold_mask_proxies" to structure,
                "regions_requested" to capturedRegions.size, "regions_accepted" to activeRegions.size,
                "regions_dropped_names" to capturedRegions.filterNot { it in activeRegions }.map { it.name },
                "diagnostic_controls" to controls, "controls_used_as_model_input" to false,
                "source_generation_unchanged" to (source.generationId == beforeGeneration),
                "original_png_bytes_unchanged" to unchanged,
                "png_metadata" to pngMetadata(png), "probe_elapsed_ms" to (SystemClock.uptimeMillis() - start),
            )
        } finally { pixels.fill(0); other.fill(0); pngDigest.fill(0); decoded.recycle() }
    }

    private fun compact(source: IntArray, target: IntArray): Map<String, Any?> {
        var changed = 0L; val maximum = IntArray(4)
        var dark = 0L
        for (i in source.indices) {
            if (source[i] == target[i]) continue
            changed++
            for (channel in 0..3) {
                val shift = 24 - channel * 8
                maximum[channel] = max(maximum[channel], abs(((source[i] ushr shift) and 255) - ((target[i] ushr shift) and 255)))
            }
            if ((luma(source[i]) < 128_000) != (luma(target[i]) < 128_000)) dark++
        }
        return mapOf("argb_mismatch_pixels" to changed, "max_abs_delta_a_r_g_b" to maximum.toList(), "dark_mask_flip_pixels" to dark)
    }

    private fun nativeRows(source: Bitmap, target: Bitmap): Map<String, Any?> {
        val bytesPerPixel = when (source.config?.name) { "ARGB_8888", "RGBA_1010102" -> 4; "RGBA_F16" -> 8; "RGB_565" -> 2; "ALPHA_8" -> 1; else -> null }
        if (bytesPerPixel == null || source.config != target.config || source.width != target.width || source.height != target.height) {
            return mapOf("native_rows_comparable" to false)
        }
        val a = ByteBuffer.allocate(source.byteCount)
        val b = ByteBuffer.allocate(target.byteCount)
        return try {
            source.copyPixelsToBuffer(a); target.copyPixelsToBuffer(b)
            val widthBytes = source.width * bytesPerPixel
            check(source.rowBytes >= widthBytes && target.rowBytes >= widthBytes)
            var pixels = 0L; var bytes = 0L; var maxDelta = 0
            for (y in 0 until source.height) for (x in 0 until source.width) {
                var changed = false
                for (part in 0 until bytesPerPixel) {
                    val av = a.array()[y * source.rowBytes + x * bytesPerPixel + part].toInt() and 255
                    val bv = b.array()[y * target.rowBytes + x * bytesPerPixel + part].toInt() and 255
                    if (av != bv) { changed = true; bytes++; maxDelta = max(maxDelta, abs(av - bv)) }
                }
                if (changed) pixels++
            }
            mapOf("native_rows_comparable" to true, "native_payload_equal" to (bytes == 0L),
                "native_mismatch_pixels" to pixels, "native_mismatch_bytes" to bytes,
                "native_max_abs_byte_delta" to maxDelta, "row_padding_excluded" to true)
        } finally { a.array().fill(0); b.array().fill(0) }
    }

    private fun thresholdMasks(source: IntArray, target: IntArray, width: Int, regions: List<ArgbDifferenceAnalyzer.Region>): Map<String, Any?> {
        val levels = intArrayOf(32, 64, 96, 128, 160, 192, 224)
        fun inRegion(index: Int, r: ArgbDifferenceAnalyzer.Region): Boolean {
            val x = index % width; val y = index / width
            return x >= r.left && x < r.right && y >= r.top && y < r.bottom
        }
        val all = LongArray(levels.size)
        val local = regions.map { LongArray(levels.size) }
        for (i in source.indices) {
            if (source[i] == target[i]) continue
            val a = luma(source[i]); val b = luma(target[i])
            for (t in levels.indices) if ((a < levels[t] * 1000) != (b < levels[t] * 1000)) {
                all[t]++; regions.forEachIndexed { r, region -> if (inRegion(i, region)) local[r][t]++ }
            }
        }
        return mapOf("luma_thresholds" to levels.toList(), "global_flip_pixels" to all.toList(),
            "regions" to regions.mapIndexed { i, region -> mapOf("name" to region.name, "flip_pixels" to local[i].toList()) },
            "is_ocr_or_model_recognition_proof" to false)
    }
    private fun luma(pixel: Int): Int = ((pixel ushr 16) and 255) * 299 + ((pixel ushr 8) and 255) * 587 + (pixel and 255) * 114

    private fun colorMetadata(space: ColorSpace?): Map<String, Any?> {
        if (space == null) return mapOf("id" to null)
        val rgb = space as? ColorSpace.Rgb
        val p = rgb?.transferParameters
        return mapOf("id" to space.id, "model" to space.model.name, "is_srgb" to space.isSrgb, "wide_gamut" to space.isWideGamut,
            "primaries" to rgb?.primaries?.toList(), "white_point" to rgb?.whitePoint?.toList(), "transform" to rgb?.transform?.toList(),
            "transfer_a_b_c_d_e_f_g" to p?.let { listOf(it.a, it.b, it.c, it.d, it.e, it.f, it.g) })
    }
    private fun pngMetadata(png: ByteArray): Map<String, Any?> {
        val buffer = ByteBuffer.wrap(png)
        var offset = 8; var iccp = false; var srgb = false; var gamma = false; var chroma = false
        var iccpBytes = 0; var depth: Int? = null; var type: Int? = null
        while (offset + 12 <= png.size) {
            val length = buffer.getInt(offset)
            check(length >= 0 && offset.toLong() + length + 12 <= png.size)
            when (buffer.getInt(offset + 4)) {
                0x49484452 -> { check(length == 13); depth = png[offset + 16].toInt() and 255; type = png[offset + 17].toInt() and 255 }
                0x69434350 -> { iccp = true; iccpBytes = length }
                0x73524742 -> srgb = true
                0x67414d41 -> gamma = true
                0x6348524d -> chroma = true
            }
            offset += length + 12
        }
        return mapOf("ihdr_bit_depth" to depth, "ihdr_color_type" to type, "has_iccp" to iccp,
            "iccp_chunk_bytes" to iccpBytes, "has_srgb" to srgb, "has_gama" to gamma, "has_chrm" to chroma)
    }
}
