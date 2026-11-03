package org.mobileharness.app.platform

import org.junit.Assert.*
import org.junit.Test

class OverlayGeometryTest {
    private val safe = OverlayGeometry.Area(4, 24, 356, 760)
    private val left = OverlayGeometry.Side.LEFT
    private val right = OverlayGeometry.Side.RIGHT

    @Test fun docksInsideSystemInsetsAndClampsExpandedPanelAtBottom() {
        assertEquals(OverlayGeometry.Area(4, 24, 104, 76), OverlayGeometry.dock(safe, 100, 52, -10, left))
        assertEquals(OverlayGeometry.Area(132, 580, 356, 760), OverlayGeometry.dock(safe, 224, 180, 900, right))
    }

    @Test fun rotationAndSmallScreenConstrainWholeWindow() {
        val landscape = OverlayGeometry.Area(40, 4, 760, 336)
        val dock = OverlayGeometry.dock(landscape, 224, 232, 580, right)
        assertEquals(OverlayGeometry.Area(536, 104, 760, 336), dock)
        val tiny = OverlayGeometry.Area(8, 20, 88, 65)
        assertEquals(tiny, OverlayGeometry.dock(tiny, 224, 180, 500, left))
        // An oversized compact window cannot offer an input-safe relocation.
        assertNull(OverlayGeometry.clearDock(tiny, 100, 52, 20, left, 10, 30, 10, 30))
    }

    @Test fun detectsTapCrossingAndConservativeEdgeContact() {
        val box = OverlayGeometry.Area(10, 10, 30, 30)
        assertTrue(OverlayGeometry.intersects(box, 20, 20, 20, 20))
        assertTrue(OverlayGeometry.intersects(box, 30, 20, 30, 20))
        assertTrue(OverlayGeometry.intersects(box, 0, 20, 50, 20))
        assertTrue(OverlayGeometry.intersects(box, 0, 0, 10, 10))
        assertFalse(OverlayGeometry.intersects(box, 31, 20, 31, 20))
    }

    @Test fun usesActualLineRatherThanItsLargerBoundingRectangle() {
        val box = OverlayGeometry.Area(10, 10, 30, 30)
        assertFalse(OverlayGeometry.intersects(box, 0, 15, 15, 0))
        assertFalse(OverlayGeometry.intersects(box, 15, 0, 0, 15))
        assertTrue(OverlayGeometry.intersects(box, 0, 40, 40, 0))
        assertTrue(OverlayGeometry.intersects(box, 40, 0, 0, 40))
    }

    @Test fun relocationPrefersOppositeEdgeWithoutChangingGesture() {
        val candidate = OverlayGeometry.clearDock(safe, 100, 52, 140, left, 20, 160, 20, 160)
        assertNotNull(candidate)
        assertEquals(right, candidate!!.first)
        assertEquals(OverlayGeometry.Area(256, 140, 356, 192), candidate.second)
        assertFalse(OverlayGeometry.intersects(candidate.second, 20, 160, 20, 160))
    }

    @Test fun horizontalSwipeCanUseAnotherVerticalDock() {
        val candidate = OverlayGeometry.clearDock(safe, 100, 52, 140, left, 0, 160, 360, 160)
        assertNotNull(candidate)
        assertEquals(right, candidate!!.first)
        assertEquals(24, candidate.second.top)
        assertFalse(OverlayGeometry.intersects(candidate.second, 0, 160, 360, 160))
    }

    @Test fun refusesWhenAllDocksIntersectInsteadOfHidingControls() {
        val shortScreen = OverlayGeometry.Area(0, 0, 100, 52)
        assertNull(OverlayGeometry.clearDock(shortScreen, 100, 52, 0, left, 50, 10, 50, 10))
    }
}
