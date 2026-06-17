package com.stylusnotes.app

import kotlin.math.ceil

/**
 * Pure engine math, kept out of the Android [View] so it can be unit-tested on
 * the JVM. Small on purpose — this is the minimal "track and show" engine plus
 * the zoom/pan transform helpers.
 */
object InkMath {

    /**
     * Segments still needing render for a live stroke. At most 1 per added
     * point, so drawing an N-point stroke is O(N), not O(N^2).
     */
    fun pendingSegments(alreadyDrawn: Int, size: Int): Int =
        (size - 1 - alreadyDrawn).coerceAtLeast(0)

    /** Pages needed to contain content reaching down to [maxY]. */
    fun pagesNeeded(maxY: Float, pageHeight: Float): Int {
        if (pageHeight <= 0f) return 1
        return ceil(maxY / pageHeight).toInt().coerceAtLeast(1)
    }

    /** Clamps a zoom factor to the allowed range. */
    fun coerceScale(scale: Float, min: Float, max: Float): Float = scale.coerceIn(min, max)

    /**
     * Horizontal translation that keeps the canvas within view. The canvas spans
     * document x in [docLeft, docLeft + docWidth]; it is centered when narrower
     * than the viewport, otherwise pinned so its edges can't drift inside.
     */
    fun clampTransX(transX: Float, scale: Float, docLeft: Float, docWidth: Float, viewWidth: Int): Float {
        val contentW = docWidth * scale
        return if (contentW <= viewWidth) {
            (viewWidth - contentW) / 2f - docLeft * scale
        } else {
            val docRight = docLeft + docWidth
            transX.coerceIn(viewWidth - docRight * scale, -docLeft * scale)
        }
    }

    /**
     * New translation after scaling so the gesture focus stays fixed on screen.
     * [ratio] is newScale / oldScale.
     */
    fun zoomTranslation(focus: Float, trans: Float, ratio: Float): Float =
        focus - (focus - trans) * ratio
}
