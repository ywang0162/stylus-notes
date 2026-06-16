package com.stylusnotes.app

/**
 * A single pen/eraser stroke in document (not screen) coordinates. Points are
 * stored in parallel arrays to avoid allocating an object per sampled point.
 *
 * [ps] holds a per-point width weight in 0..1 (derived from pen pressure and/or
 * drawing speed at input time), where 1.0 means full width.
 *
 * [minY]/[maxY] cache the vertical extent so the view can skip strokes that are
 * outside the visible scroll window.
 */
class Stroke(
    var color: Int,
    var baseWidth: Float,
    var isEraser: Boolean
) {
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    val ps = ArrayList<Float>()

    var minY = Float.MAX_VALUE
        private set
    var maxY = -Float.MAX_VALUE
        private set

    val size: Int get() = xs.size

    fun addPoint(x: Float, y: Float, weight: Float) {
        xs.add(x)
        ys.add(y)
        ps.add(weight)
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }
}
