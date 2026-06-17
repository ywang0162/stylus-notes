package com.stylusnotes.app

/**
 * One pen/eraser stroke in document coordinates: just a color, a width, and the
 * points the finger traced. No pressure or per-point weight — the engine simply
 * tracks and shows.
 *
 * [minY]/[maxY] cache the vertical extent so the view can skip strokes outside
 * the visible area.
 */
class Stroke(
    var color: Int,
    var baseWidth: Float,
    var isEraser: Boolean
) {
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()

    var minY = Float.MAX_VALUE
        private set
    var maxY = -Float.MAX_VALUE
        private set

    val size: Int get() = xs.size

    fun addPoint(x: Float, y: Float) {
        xs.add(x)
        ys.add(y)
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }
}
