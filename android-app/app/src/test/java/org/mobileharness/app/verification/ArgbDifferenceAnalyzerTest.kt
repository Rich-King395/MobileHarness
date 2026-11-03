package org.mobileharness.app.verification

import org.junit.Assert.*
import org.junit.Test

class ArgbDifferenceAnalyzerTest {
    @Test fun identicalImagesHaveZeroChangesAndNoBoundingBoxes() {
        val image = intArrayOf(color(255, 0, 0, 0), color(3, 255, 12, 2))
        val report = ArgbDifferenceAnalyzer.analyze(image, image.copyOf(), 2, 1,
            listOf(ArgbDifferenceAnalyzer.Region("WHOLE", 0, 0, 2, 1)))
        assertEquals(2L, report["compared_pixels"])
        assertEquals(0L, report["mismatch_pixels"])
        assertNull(report["mismatch_bbox"])
        assertEquals(0, report["component_count"])
        assertEquals(emptyList<Any>(), report["largest_components"])
        assertEquals(2L, histogram(report, "A")["0"])
        assertEquals(0, channel(report, "R")["max_abs_delta"])
        assertNull(regions(report)[0]["mismatch_bbox"])
    }

    @Test fun channelHistogramsDistinguishMagnitudeFromSignedCancellation() {
        val magnitudes = intArrayOf(0, 1, 2, 3, 4, 7, 8, 15, 16, 31, 32, 63, 64, 127, 128, 255)
        val source = magnitudes.map { color(255, 0, 0, 0) }.toIntArray()
        val target = magnitudes.map { color(255, it, 0, 0) }.toIntArray()
        val report = ArgbDifferenceAnalyzer.analyze(source, target, source.size, 1)
        assertEquals(255, channel(report, "R")["max_abs_delta"])
        assertEquals(magnitudes.sum().toLong(), channel(report, "R")["signed_delta_sum"])
        assertEquals(mapOf("0" to 1L, "1" to 1L, "2" to 1L, "3" to 1L, "4_7" to 2L,
            "8_15" to 2L, "16_31" to 2L, "32_63" to 2L, "64_127" to 2L, "128_255" to 2L), histogram(report, "R"))
        val cancellation = ArgbDifferenceAnalyzer.analyze(
            intArrayOf(color(255, 0, 0, 0), color(255, 255, 0, 0)),
            intArrayOf(color(255, 255, 0, 0), color(255, 0, 0, 0)), 2, 1)
        assertEquals(0L, channel(cancellation, "R")["signed_delta_sum"])
        assertEquals(510L, channel(cancellation, "R")["absolute_delta_sum"])
        assertEquals(2L, channel(cancellation, "R")["nonzero_pixels"])
    }

    @Test fun alphaAndRgbChangesRemainSeparateAndThresholdIsStrictlyGreaterThanEight() {
        val source = intArrayOf(color(255, 0, 0, 0), color(255, 0, 0, 0), color(128, 0, 0, 0), color(255, 0, 0, 0))
        val decoded = intArrayOf(color(0, 0, 0, 0), color(255, 8, 0, 0), color(127, 0, 9, 0), color(255, 0, 0, 255))
        val report = ArgbDifferenceAnalyzer.analyze(source, decoded, 2, 2)
        assertEquals(1L, report["alpha_only_pixels"])
        assertEquals(2L, report["rgb_only_pixels"])
        assertEquals(1L, report["alpha_and_rgb_pixels"])
        assertEquals(1L, report["changed_source_alpha_lt_255_pixels"])
        assertEquals(3L, report["changed_source_opaque_pixels"])
        assertEquals(2L, report["changed_both_opaque_pixels"])
        assertEquals(2L, report["rgb_delta_gt8_pixels"])
        assertEquals(listOf(0, 0, 2, 2), report["mismatch_bbox"])
    }

    @Test fun luminanceMaskAndEdgeProxyDoNotTreatAlphaAsColorContrast() {
        val source = intArrayOf(color(255, 0, 0, 0), color(255, 127, 127, 127), color(255, 127, 127, 127))
        val decoded = intArrayOf(color(0, 0, 0, 0), color(255, 128, 128, 128), color(255, 128, 128, 128))
        val report = ArgbDifferenceAnalyzer.analyze(source, decoded, 3, 1)
        assertEquals(2L, report["dark_mask_flip_pixels"])
        assertEquals(2L, report["source_edge_changed_pixels"])
        assertEquals(0L, report["rgb_delta_gt8_pixels"])
    }

    @Test fun overlappingRegionsAreIndependentAndUseExclusiveRightBottomEdges() {
        val source = IntArray(6) { color(255, 0, 0, 0) }
        val target = source.copyOf().apply { this[0] = color(255, 1, 0, 0); this[4] = color(254, 0, 10, 0) }
        val report = ArgbDifferenceAnalyzer.analyze(source, target, 3, 2, listOf(
            ArgbDifferenceAnalyzer.Region("WHOLE", 0, 0, 3, 2),
            ArgbDifferenceAnalyzer.Region("LEFT", 0, 0, 1, 2),
            ArgbDifferenceAnalyzer.Region("LOWER", 0, 1, 3, 2),
        ))
        val rows = regions(report).associateBy { it["name"] }
        assertEquals(true, report["regions_overlap_counted_independently"])
        assertEquals(2L, rows["WHOLE"]!!["mismatch_pixels"])
        assertEquals(1L, rows["LEFT"]!!["mismatch_pixels"])
        assertEquals(listOf(0, 0, 1, 1), rows["LEFT"]!!["mismatch_bbox"])
        assertEquals(listOf(1, 1, 2, 2), rows["LOWER"]!!["mismatch_bbox"])
        assertEquals(1, rows["LOWER"]!!["max_abs_alpha_delta"])
        assertEquals(10, rows["LOWER"]!!["max_abs_rgb_delta"])
        assertEquals(listOf(0, 0, 2, 2), report["mismatch_bbox"])
        assertArrayEquals(source, IntArray(6) { color(255, 0, 0, 0) })
        assertEquals(color(254, 0, 10, 0), target[4])
    }

    @Test fun fourConnectivityExcludesDiagonalAndRowWrappingAndRetainsOnlyLargestSixteen() {
        val source = IntArray(12) { color(255, 0, 0, 0) }
        val target = source.copyOf().apply { listOf(2, 3, 7, 11).forEach { this[it] = color(255, 1, 0, 0) } }
        val report = ArgbDifferenceAnalyzer.analyze(source, target, 3, 4)
        assertEquals(4, report["component_count"])
        assertEquals(4, components(report).size)
        assertTrue(components(report).all { it["pixels"] == 1 })
        val manySource = IntArray(81) { color(255, 0, 0, 0) }
        val manyTarget = manySource.copyOf().apply { for (y in 0..8) for (x in 0..8) if ((x + y) % 2 == 0) this[y * 9 + x] = color(255, 1, 0, 0) }
        val limited = ArgbDifferenceAnalyzer.analyze(manySource, manyTarget, 9, 9)
        assertEquals(41, limited["component_count"])
        assertEquals(16, components(limited).size)
        assertEquals(listOf(0, 0, 1, 1), components(limited)[0]["bbox"])
    }

    @Test fun connectedComponentCountsAndBoundingBoxCoverAllFourDirections() {
        val source = IntArray(9) { color(255, 0, 0, 0) }
        val target = source.copyOf().apply { listOf(1, 3, 4, 5, 7).forEach { this[it] = color(255, 1, 0, 0) } }
        val report = ArgbDifferenceAnalyzer.analyze(source, target, 3, 3)
        assertEquals(1, report["component_count"])
        assertEquals(5, components(report).single()["pixels"])
        assertEquals(listOf(0, 0, 3, 3), components(report).single()["bbox"])
    }

    @Test fun invalidDimensionsArraysAndUnclippedRegionsAreRejected() {
        invalid { ArgbDifferenceAnalyzer.analyze(intArrayOf(), intArrayOf(), 0, 1) }
        invalid { ArgbDifferenceAnalyzer.analyze(intArrayOf(), intArrayOf(), -1, -1) }
        invalid { ArgbDifferenceAnalyzer.analyze(intArrayOf(), intArrayOf(), Int.MAX_VALUE, 2) }
        invalid { ArgbDifferenceAnalyzer.analyze(intArrayOf(0), intArrayOf(0, 0), 1, 1) }
        invalid { ArgbDifferenceAnalyzer.analyze(intArrayOf(0), intArrayOf(0), 1, 1, listOf(ArgbDifferenceAnalyzer.Region("BAD", -1, 0, 1, 1))) }
        invalid { ArgbDifferenceAnalyzer.analyze(intArrayOf(0), intArrayOf(0), 1, 1, listOf(ArgbDifferenceAnalyzer.Region("BAD", 0, 0, 2, 1))) }
        invalid { ArgbDifferenceAnalyzer.analyze(intArrayOf(0), intArrayOf(0), 1, 1, listOf(ArgbDifferenceAnalyzer.Region("BAD", 0, 0, 0, 1))) }
        invalid { ArgbDifferenceAnalyzer.analyze(intArrayOf(0), intArrayOf(0), 1, 1, listOf(ArgbDifferenceAnalyzer.Region("not free text", 0, 0, 1, 1))) }
        invalid { ArgbDifferenceAnalyzer.analyze(intArrayOf(0), intArrayOf(0), 1, 1, listOf(ArgbDifferenceAnalyzer.Region("SAME", 0, 0, 1, 1), ArgbDifferenceAnalyzer.Region("SAME", 0, 0, 1, 1))) }
    }

    @Suppress("UNCHECKED_CAST") private fun channel(report: Map<String, Any?>, name: String) = (report["channels"] as Map<String, Map<String, Any?>>).getValue(name)
    @Suppress("UNCHECKED_CAST") private fun histogram(report: Map<String, Any?>, name: String) = channel(report, name)["histogram"] as Map<String, Long>
    @Suppress("UNCHECKED_CAST") private fun regions(report: Map<String, Any?>) = report["regions"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST") private fun components(report: Map<String, Any?>) = report["largest_components"] as List<Map<String, Any?>>
    private fun color(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun invalid(block: () -> Unit) { try { block(); fail("Expected invalid analyzer input to be rejected") } catch (_: IllegalArgumentException) { } }
}
