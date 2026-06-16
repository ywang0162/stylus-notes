package com.stylusnotes.app

/**
 * A single pen/eraser stroke. Points are stored in parallel arrays to avoid
 * allocating an object per sampled point (a handwriting stroke can contain
 * hundreds of samples).
 */
class Stroke(
    var color: Int,
    var baseWidth: Float,
    var isEraser: Boolean
) {
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    /** Normalised pen pressure for each point. 1.0 means "no pressure data". */
    val ps = ArrayList<Float>()

    val size: Int get() = xs.size

    fun addPoint(x: Float, y: Float, pressure: Float) {
        xs.add(x)
        ys.add(y)
        ps.add(pressure)
    }
}
