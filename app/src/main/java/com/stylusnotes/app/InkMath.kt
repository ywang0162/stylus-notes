package com.stylusnotes.app

import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Pure handwriting-engine math, deliberately separated from the Android [View]
 * so it can be unit-tested on the JVM (no device required).
 *
 * These are the functions that decide how the engine *performs* (how much work
 * each new point costs) and how it *feels/behaves* (line width vs. speed, stroke
 * stitching across contact dropouts, page growth). The unit tests pin these down
 * so the "slow and not smooth" fixes are demonstrable, not just claimed.
 */
object InkMath {

    /**
     * Segments that still need rendering for a live stroke. This is the crux of
     * the performance fix: per added point this returns at most 1, so the total
     * work to draw a stroke of N points is O(N) — not the O(N^2) of redrawing
     * the whole stroke every frame.
     */
    fun pendingSegments(alreadyDrawn: Int, size: Int): Int =
        (size - 1 - alreadyDrawn).coerceAtLeast(0)

    /** Maps per-sample speed (dp/ms) to a 0..1 width weight: slow=1, fast=taperMin. */
    fun velocityWeight(speedDpPerMs: Float, taperMin: Float, slow: Float, fast: Float): Float {
        val t = ((speedDpPerMs - slow) / (fast - slow)).coerceIn(0f, 1f)
        return 1f - t * (1f - taperMin)
    }

    /**
     * Combines pen pressure and velocity into a width weight. A finger reports no
     * usable pressure, so finger strokes ride on velocity alone.
     */
    fun strokeWeight(isFinger: Boolean, pressure: Float, velocityWeight: Float): Float =
        if (isFinger) velocityWeight
        else pressure.coerceIn(0f, 1f) * (0.5f + 0.5f * velocityWeight)

    /** Exponential smoothing of the width weight (stroke geometry is never smoothed). */
    fun smoothWeight(previous: Float, target: Float, alpha: Float): Float =
        previous + alpha * (target - previous)

    /** Final stroke width from a base width and a 0..1 weight. */
    fun widthFor(baseWidth: Float, weight: Float, variation: Float): Float {
        val w = weight.coerceIn(0f, 1f)
        val scaled = 0.3f + 0.7f * w
        return (baseWidth * (1f - variation + variation * scaled)).coerceAtLeast(0.7f)
    }

    /** Per-sample speed in dp/ms between two points. */
    fun speedDpPerMs(dx: Float, dy: Float, dtMs: Long, density: Float): Float {
        val dt = dtMs.coerceAtLeast(1L)
        return (hypot(dx, dy) / dt) / density
    }

    /**
     * Whether a fresh touch should resume the previous stroke instead of starting
     * a new one — used to stitch strokes back together when the digitizer briefly
     * drops contact mid-letter.
     */
    fun shouldContinue(
        gapMs: Long,
        distDp: Float,
        sameStyle: Boolean,
        maxGapMs: Long,
        maxDistDp: Float
    ): Boolean = sameStyle && gapMs in 0..maxGapMs && distDp <= maxDistDp

    /** Number of pages needed to contain content reaching down to [maxY]. */
    fun pagesNeeded(maxY: Float, pageHeight: Float): Int {
        if (pageHeight <= 0f) return 1
        return ceil(maxY / pageHeight).toInt().coerceAtLeast(1)
    }

    /** Maximum scroll offset for a document of [pageCount] pages in a viewport. */
    fun maxScroll(pageCount: Int, pageHeight: Float, viewportHeight: Int): Float =
        (pageCount * pageHeight - viewportHeight).coerceAtLeast(0f)
}
