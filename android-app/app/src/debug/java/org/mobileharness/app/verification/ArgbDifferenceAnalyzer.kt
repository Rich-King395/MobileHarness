package org.mobileharness.app.verification

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * In-memory analysis of packed ARGB readbacks. The result contains only scalar statistics and
 * bounding boxes, never pixel values, image masks, image hashes, or a replayable color raster.
 * Region names must be fixed enum-style identifiers. Overlapping regions are measured independently;
 * their counters must not be summed as if the regions were a partition. Regions are not clipped.
 */
internal object ArgbDifferenceAnalyzer {
    data class Region(val name: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun analyze(
        source: IntArray,
        decoded: IntArray,
        width: Int,
        height: Int,
        regions: List<Region> = emptyList(),
    ): Map<String, Any?> {
        require(width > 0 && height > 0) { "Dimensions must be positive" }
        val count = width.toLong() * height.toLong()
        require(count <= Int.MAX_VALUE && source.size.toLong() == count && decoded.size.toLong() == count) {
            "Pixel array length must match dimensions without overflow"
        }
        require(regions.map { it.name }.distinct().size == regions.size) { "Region names must be unique" }
        regions.forEach {
            require(it.name.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) { "Region name must be a fixed enum-style identifier" }
            require(it.left >= 0 && it.top >= 0 && it.left < it.right && it.top < it.bottom &&
                it.right <= width && it.bottom <= height) { "Region must be nonempty and inside the frame" }
        }

        val alpha = Channel()
        val red = Channel()
        val green = Channel()
        val blue = Channel()
        val regionStats = regions.map(::RegionStats)
        val changed = BooleanArray(count.toInt())
        val globalBox = Box()
        var mismatchCount = 0
        var alphaOnly = 0L
        var rgbOnly = 0L
        var alphaAndRgb = 0L
        var sourceNonopaque = 0L
        var sourceOpaque = 0L
        var bothOpaque = 0L
        var darkFlips = 0L
        var edgeChanges = 0L
        var largeRgb = 0L

        for (index in source.indices) {
            val before = source[index]
            val after = decoded[index]
            val sourceA = before ushr 24
            val decodedA = after ushr 24
            val da = decodedA - sourceA
            val dr = ((after ushr 16) and 255) - ((before ushr 16) and 255)
            val dg = ((after ushr 8) and 255) - ((before ushr 8) and 255)
            val db = (after and 255) - (before and 255)
            alpha.add(da); red.add(dr); green.add(dg); blue.add(db)
            if (before == after) continue

            changed[index] = true
            mismatchCount++
            val x = index % width
            val y = index / width
            globalBox.add(x, y)
            val rgbChanged = dr != 0 || dg != 0 || db != 0
            when {
                da != 0 && rgbChanged -> alphaAndRgb++
                da != 0 -> alphaOnly++
                else -> rgbOnly++
            }
            if (sourceA < 255) sourceNonopaque++ else sourceOpaque++
            if (sourceA == 255 && decodedA == 255) bothOpaque++
            val beforeLuma = luma1000(before)
            val darkFlip = (beforeLuma < 128_000) != (luma1000(after) < 128_000)
            if (darkFlip) darkFlips++
            val edge = isSourceEdge(source, index, width, height, beforeLuma)
            if (edge) edgeChanges++
            val maxRgb = max(abs(dr), max(abs(dg), abs(db)))
            if (maxRgb > 8) largeRgb++
            regionStats.forEach { region ->
                if (region.contains(x, y)) region.add(x, y, abs(da), maxRgb, darkFlip, edge, sourceA < 255)
            }
        }

        val components = components(changed, mismatchCount, width, height)
        return linkedMapOf(
            "compared_pixels" to count,
            "mismatch_pixels" to mismatchCount.toLong(),
            "mismatch_bbox" to globalBox.value(),
            "channels" to linkedMapOf("A" to alpha.value(), "R" to red.value(), "G" to green.value(), "B" to blue.value()),
            "alpha_only_pixels" to alphaOnly,
            "rgb_only_pixels" to rgbOnly,
            "alpha_and_rgb_pixels" to alphaAndRgb,
            "changed_source_alpha_lt_255_pixels" to sourceNonopaque,
            "changed_source_opaque_pixels" to sourceOpaque,
            "changed_both_opaque_pixels" to bothOpaque,
            "dark_mask_flip_pixels" to darkFlips,
            "dark_mask_luma_threshold" to 128,
            "source_edge_changed_pixels" to edgeChanges,
            "source_edge_luma_contrast_threshold" to 24,
            "rgb_delta_gt8_pixels" to largeRgb,
            "regions_overlap_counted_independently" to true,
            "regions" to regionStats.map { it.value() },
            "component_connectivity" to 4,
            "component_count" to components.first,
            "largest_components" to components.second.map { it.value() },
            "largest_components_limit" to 16,
        )
    }

    private class Channel {
        private var maximum = 0
        private var signedSum = 0L
        private var absoluteSum = 0L
        private var nonzero = 0L
        private val histogram = LongArray(10)
        fun add(delta: Int) {
            val magnitude = abs(delta)
            maximum = max(maximum, magnitude)
            signedSum += delta
            absoluteSum += magnitude
            if (magnitude != 0) nonzero++
            histogram[when (magnitude) {
                0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3
                in 4..7 -> 4; in 8..15 -> 5; in 16..31 -> 6
                in 32..63 -> 7; in 64..127 -> 8; else -> 9
            }]++
        }
        fun value(): Map<String, Any?> = linkedMapOf(
            "max_abs_delta" to maximum, "signed_delta_sum" to signedSum,
            "absolute_delta_sum" to absoluteSum, "nonzero_pixels" to nonzero,
            "histogram" to HISTOGRAM_LABELS.mapIndexed { index, label -> label to histogram[index] }.toMap(),
        )
    }

    private class Box {
        private var left = Int.MAX_VALUE
        private var top = Int.MAX_VALUE
        private var right = -1
        private var bottom = -1
        fun add(x: Int, y: Int) { left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y) }
        fun value(): List<Int>? = if (right < 0) null else listOf(left, top, right + 1, bottom + 1)
    }

    private class RegionStats(private val region: Region) {
        private val box = Box()
        private var count = 0L
        private var alpha = 0
        private var rgb = 0
        private var darkFlips = 0L
        private var edges = 0L
        private var largeRgb = 0L
        private var nonopaque = 0L
        fun contains(x: Int, y: Int) = x >= region.left && x < region.right && y >= region.top && y < region.bottom
        fun add(x: Int, y: Int, alphaMagnitude: Int, rgbMagnitude: Int, darkFlip: Boolean, edge: Boolean, sourceNonopaque: Boolean) {
            count++; box.add(x, y)
            alpha = max(alpha, alphaMagnitude); rgb = max(rgb, rgbMagnitude)
            if (darkFlip) darkFlips++
            if (edge) edges++
            if (rgbMagnitude > 8) largeRgb++
            if (sourceNonopaque) nonopaque++
        }
        fun value(): Map<String, Any?> = linkedMapOf(
            "name" to region.name,
            "bounds" to listOf(region.left, region.top, region.right, region.bottom),
            "compared_pixels" to (region.right - region.left).toLong() * (region.bottom - region.top),
            "mismatch_pixels" to count, "mismatch_bbox" to box.value(),
            "max_abs_alpha_delta" to alpha, "max_abs_rgb_delta" to rgb,
            "dark_mask_flip_pixels" to darkFlips, "source_edge_changed_pixels" to edges,
            "rgb_delta_gt8_pixels" to largeRgb, "changed_source_alpha_lt_255_pixels" to nonopaque,
        )
    }

    private data class Component(val pixels: Int, val box: List<Int>) {
        fun value(): Map<String, Any?> = mapOf("pixels" to pixels, "bbox" to box)
    }

    private fun components(mask: BooleanArray, count: Int, width: Int, height: Int): Pair<Int, List<Component>> {
        if (count == 0) return 0 to emptyList()
        // Only a sparse flood queue is allocated; no boxed set of full-frame pixel indices.
        val queue = IntArray(count)
        val largest = mutableListOf<Component>()
        var componentCount = 0
        val ordering = compareByDescending<Component> { it.pixels }.thenBy { it.box[1] }.thenBy { it.box[0] }
        try {
            for (start in mask.indices) {
                if (!mask[start]) continue
                componentCount++
                var head = 0
                var tail = 0
                queue[tail++] = start
                mask[start] = false
                val box = Box()
                fun enqueue(index: Int) { if (mask[index]) { mask[index] = false; queue[tail++] = index } }
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % width
                    val y = index / width
                    box.add(x, y)
                    if (x > 0) enqueue(index - 1)
                    if (x + 1 < width) enqueue(index + 1)
                    if (y > 0) enqueue(index - width)
                    if (y + 1 < height) enqueue(index + width)
                }
                largest.add(Component(tail, checkNotNull(box.value())))
                largest.sortWith(ordering)
                if (largest.size > 16) largest.removeAt(16)
            }
        } finally { queue.fill(0); mask.fill(false) }
        return componentCount to largest.toList()
    }

    private fun luma1000(color: Int): Int = ((color ushr 16) and 255) * 299 + ((color ushr 8) and 255) * 587 + (color and 255) * 114
    private fun isSourceEdge(source: IntArray, index: Int, width: Int, height: Int, center: Int): Boolean {
        val x = index % width
        val y = index / width
        return (x > 0 && abs(center - luma1000(source[index - 1])) >= 24_000) ||
            (x + 1 < width && abs(center - luma1000(source[index + 1])) >= 24_000) ||
            (y > 0 && abs(center - luma1000(source[index - width])) >= 24_000) ||
            (y + 1 < height && abs(center - luma1000(source[index + width])) >= 24_000)
    }
    private val HISTOGRAM_LABELS = listOf("0", "1", "2", "3", "4_7", "8_15", "16_31", "32_63", "64_127", "128_255")
}
