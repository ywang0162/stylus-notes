package com.stylusnotes.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Objective, device-free evidence that the handwriting engine is faster and
 * behaves correctly. These run on the JVM in CI.
 */
class InkMathTest {

    // ---- Performance: the core fix for "slow" ------------------------------

    /**
     * Demonstrates the O(n^2) -> O(n) fix. The old engine redrew the whole
     * in-progress stroke every frame, so drawing an N-point stroke cost
     * 0+1+...+(N-1) ~ N^2/2 segment draws. The new engine bakes each segment
     * once: total work is exactly N-1, and each added point costs at most one
     * segment (constant per-point work).
     */
    @Test
    fun bakingIsLinearWithConstantPerPointWork() {
        val n = 600
        var drawn = 0
        var totalSegments = 0
        var maxPerPoint = 0
        for (size in 1..n) {
            val pending = InkMath.pendingSegments(drawn, size)
            maxPerPoint = maxOf(maxPerPoint, pending)
            totalSegments += pending
            drawn = size - 1 // we just drew everything pending
        }
        assertEquals("total work must be linear in point count", n - 1, totalSegments)
        assertTrue("per-point work must be constant (<= 1 segment)", maxPerPoint <= 1)
    }

    @Test
    fun newEngineDoesFarLessWorkThanOldRedrawEveryFrame() {
        val n = 600
        var oldWork = 0L
        for (size in 1..n) oldWork += (size - 1).toLong() // redraw whole stroke each frame

        var newWork = 0L
        var drawn = 0
        for (size in 1..n) {
            newWork += InkMath.pendingSegments(drawn, size).toLong()
            drawn = size - 1
        }
        assertEquals((n - 1).toLong(), newWork)
        assertTrue("new engine should do orders of magnitude less work", oldWork > newWork * 100)
    }

    @Test
    fun pendingSegmentsNeverNegative() {
        assertEquals(0, InkMath.pendingSegments(5, 1))
        assertEquals(0, InkMath.pendingSegments(0, 0))
        assertEquals(0, InkMath.pendingSegments(0, 1))
        assertEquals(1, InkMath.pendingSegments(0, 2))
    }

    // ---- Feel: speed -> line width -----------------------------------------

    @Test
    fun slowStrokesAreFullWidthFastStrokesTaper() {
        val slow = InkMath.velocityWeight(0f, 0.5f, 0.35f, 3.0f)
        val fast = InkMath.velocityWeight(10f, 0.5f, 0.35f, 3.0f)
        assertEquals(1f, slow, 1e-4f)
        assertEquals(0.5f, fast, 1e-4f) // clamped to taperMin
        assertTrue("slower must be thicker than faster", slow > fast)
    }

    @Test
    fun velocityWeightIsMonotonic() {
        var prev = Float.MAX_VALUE
        var v = 0f
        while (v <= 4f) {
            val w = InkMath.velocityWeight(v, 0.5f, 0.35f, 3.0f)
            assertTrue("weight must not increase as speed increases", w <= prev + 1e-5f)
            prev = w
            v += 0.25f
        }
    }

    @Test
    fun fingerWidthRidesOnVelocityNotPressure() {
        // Finger reports pressure ~1.0; weight should equal the velocity weight.
        assertEquals(0.7f, InkMath.strokeWeight(isFinger = true, pressure = 1f, velocityWeight = 0.7f), 1e-4f)
        // Stylus blends pressure in.
        val light = InkMath.strokeWeight(isFinger = false, pressure = 0.2f, velocityWeight = 1f)
        val heavy = InkMath.strokeWeight(isFinger = false, pressure = 1.0f, velocityWeight = 1f)
        assertTrue("heavier pressure must be thicker", heavy > light)
    }

    @Test
    fun widthForMapsWeightWithinBounds() {
        val base = 10f
        val full = InkMath.widthFor(base, 1f, 0.85f)
        val light = InkMath.widthFor(base, 0.0f, 0.85f)
        assertEquals("weight 1 yields base width", base, full, 1e-3f)
        assertTrue("lighter weight is thinner", light < full)
        assertTrue("width never collapses to zero", InkMath.widthFor(0.1f, 0f, 1f) >= 0.7f)
    }

    @Test
    fun smoothWeightMovesTowardTarget() {
        val mid = InkMath.smoothWeight(0f, 1f, 0.5f)
        assertEquals(0.5f, mid, 1e-4f)
    }

    // ---- Behavior: stroke stitching across contact dropouts ----------------

    @Test
    fun stitchesNearbyRecentSameStyleTouches() {
        assertTrue(InkMath.shouldContinue(gapMs = 40, distDp = 6f, sameStyle = true, maxGapMs = 140, maxDistDp = 18f))
    }

    @Test
    fun doesNotStitchFarOrOldOrDifferentTouches() {
        assertFalse("too far", InkMath.shouldContinue(40, 40f, true, 140, 18f))
        assertFalse("too old", InkMath.shouldContinue(500, 6f, true, 140, 18f))
        assertFalse("different tool/color", InkMath.shouldContinue(40, 6f, false, 140, 18f))
    }

    // ---- Behavior: scrolling pages -----------------------------------------

    @Test
    fun documentGrowsToCoverContent() {
        assertEquals(1, InkMath.pagesNeeded(50f, 100f))
        assertEquals(1, InkMath.pagesNeeded(100f, 100f))
        assertEquals(3, InkMath.pagesNeeded(250f, 100f)) // ceil(250/100)
    }

    @Test
    fun scrollClampsAtTopAndGrowsWithPages() {
        assertEquals("single page that fits cannot scroll", 0f, InkMath.maxScroll(1, 100f, 200), 1e-4f)
        assertEquals("two 150px pages in a 200px viewport", 100f, InkMath.maxScroll(2, 150f, 200), 1e-4f)
    }
}
