package org.mobileharness.app.platform

/** Screen-space geometry for our own control window. Does not inspect target app content. */
internal object OverlayGeometry {
    enum class Side { LEFT, RIGHT }
    data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
    }

    fun dock(safe: Area, width: Int, height: Int, preferredY: Int, side: Side): Area {
        val w = width.coerceIn(0, safe.width)
        val h = height.coerceIn(0, safe.height)
        val x = if (side == Side.LEFT) safe.left else safe.right - w
        val y = preferredY.coerceIn(safe.top, (safe.bottom - h).coerceAtLeast(safe.top))
        return Area(x, y, x + w, y + h)
    }

    /** Inclusive edges are intentional: a gesture grazing the control is not safe to dispatch. */
    fun intersects(area: Area, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        var enter = 0.0
        var leave = 1.0
        val dx = x2.toDouble() - x1
        val dy = y2.toDouble() - y1
        fun clip(p: Double, q: Double): Boolean {
            if (p == 0.0) return q >= 0.0
            val ratio = q / p
            if (p < 0.0) {
                if (ratio > leave) return false
                if (ratio > enter) enter = ratio
            } else {
                if (ratio < enter) return false
                if (ratio < leave) leave = ratio
            }
            return true
        }
        return clip(-dx, x1.toDouble() - area.left) &&
            clip(dx, area.right.toDouble() - x1) &&
            clip(-dy, y1.toDouble() - area.top) &&
            clip(dy, area.bottom.toDouble() - y1)
    }

    /** Try the other edge first, then other vertical docks; never changes gesture coordinates. */
    fun clearDock(
        safe: Area, width: Int, height: Int, preferredY: Int, currentSide: Side,
        x1: Int, y1: Int, x2: Int, y2: Int,
    ): Pair<Side, Area>? {
        if (width > safe.width || height > safe.height || width <= 0 || height <= 0) return null
        val other = if (currentSide == Side.LEFT) Side.RIGHT else Side.LEFT
        val bottomY = safe.bottom - height
        val ys = listOf(preferredY, safe.top, bottomY, safe.top + (safe.height - height) / 2).distinct()
        for (side in listOf(other, currentSide)) {
            for (y in ys) {
                val candidate = dock(safe, width, height, y, side)
                if (!intersects(candidate, x1, y1, x2, y2)) return side to candidate
            }
        }
        return null
    }
}
