package com.stylusnotes.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the minimal engine math. */
class InkMathTest {

    // ---- Rendering stays linear (the "effective" part) ----------------------

    @Test
    fun bakingIsLinearWithConstantPerPointWork() {
        val n = 600
        var drawn = 0
        var total = 0
        var maxPerPoint = 0
        for (size in 1..n) {
            val pending = InkMath.pendingSegments(drawn, size)
            maxPerPoint = maxOf(maxPerPoint, pending)
            total += pending
            drawn = size - 1
        }
        assertEquals("total work is linear in point count", n - 1, total)
        assertTrue("per-point work is constant (<= 1 segment)", maxPerPoint <= 1)
    }

    @Test
    fun pendingSegmentsNeverNegative() {
        assertEquals(0, InkMath.pendingSegments(5, 1))
        assertEquals(0, InkMath.pendingSegments(0, 1))
        assertEquals(1, InkMath.pendingSegments(0, 2))
    }

    @Test
    fun documentGrowsToCoverContent() {
        assertEquals(1, InkMath.pagesNeeded(50f, 100f))
        assertEquals(1, InkMath.pagesNeeded(100f, 100f))
        assertEquals(3, InkMath.pagesNeeded(250f, 100f))
    }

    // ---- Zoom / pan transform ----------------------------------------------

    @Test
    fun scaleClampsToRange() {
        assertEquals(0.3f, InkMath.coerceScale(0.1f, 0.3f, 6f), 1e-4f)
        assertEquals(6f, InkMath.coerceScale(99f, 0.3f, 6f), 1e-4f)
        assertEquals(2f, InkMath.coerceScale(2f, 0.3f, 6f), 1e-4f)
    }

    @Test
    fun narrowerThanViewportCentersHorizontally() {
        // origin-0 canvas, width 500 in a 1000px viewport -> centered at 250
        assertEquals(250f, InkMath.clampTransX(-300f, 1f, docLeft = 0f, docWidth = 500f, viewWidth = 1000), 1e-3f)
    }

    @Test
    fun widerThanViewportPinsToEdges() {
        // origin-0 canvas, content width 2000 in a 1000px viewport -> transX in [-1000, 0]
        assertEquals(0f, InkMath.clampTransX(50f, 2f, 0f, 1000f, 1000), 1e-3f)
        assertEquals(-1000f, InkMath.clampTransX(-9999f, 2f, 0f, 1000f, 1000), 1e-3f)
        assertEquals(-400f, InkMath.clampTransX(-400f, 2f, 0f, 1000f, 1000), 1e-3f)
    }

    @Test
    fun offsetCanvasCentersAroundItsMiddle() {
        // Canvas [-250, 750] (width 1000) at scale 0.5 -> content 500 fills a 500px
        // viewport with its left edge at screen 0.
        val t = InkMath.clampTransX(0f, 0.5f, docLeft = -250f, docWidth = 1000f, viewWidth = 500)
        assertEquals(125f, t, 1e-3f)
        // left edge of canvas maps to screen 0: docLeft*scale + t == 0
        assertEquals(0f, -250f * 0.5f + t, 1e-3f)
    }

    @Test
    fun zoomKeepsFocusFixed() {
        // Doubling scale (ratio 2) about focus 100 with translation 0:
        // a doc point currently at screen 100 must remain at screen 100.
        val trans = 0f
        val newTrans = InkMath.zoomTranslation(focus = 100f, trans = trans, ratio = 2f)
        // doc point under focus before: (100 - 0)/1 = 100; after scaling by 2:
        // screen = 100 * 2 + newTrans must equal 100 -> newTrans = -100
        assertEquals(-100f, newTrans, 1e-3f)
    }
}
