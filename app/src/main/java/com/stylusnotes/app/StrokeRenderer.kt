package com.stylusnotes.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Draws strokes with quadratic-midpoint smoothing under a scale + translate
 * transform (screen = doc * sc + t). Shared by the note view and the zoom-write
 * panel so both render ink identically. The caller supplies a reusable [Paint]
 * (configured STROKE, round cap/join) and [Path] to avoid per-frame allocation.
 */
object StrokeRenderer {

    fun drawFull(
        canvas: Canvas, s: Stroke, sc: Float, tx: Float, ty: Float,
        pageColor: Int, paint: Paint, path: Path
    ) {
        val n = s.size
        if (n == 0) return
        if (n == 1) {
            drawDot(canvas, s, sc, tx, ty, pageColor, paint)
            return
        }
        for (i in 1 until n) drawSegment(canvas, s, i, sc, tx, ty, pageColor, paint, path)
        drawClosing(canvas, s, sc, tx, ty, pageColor, paint, path)
    }

    fun drawSegment(
        canvas: Canvas, s: Stroke, i: Int, sc: Float, tx: Float, ty: Float,
        pageColor: Int, paint: Paint, path: Path
    ) {
        paint.color = if (s.isEraser) pageColor else s.color
        paint.strokeWidth = (s.baseWidth * sc).coerceAtLeast(0.7f)
        val x0 = s.xs[i - 1] * sc + tx; val y0 = s.ys[i - 1] * sc + ty
        val x1 = s.xs[i] * sc + tx; val y1 = s.ys[i] * sc + ty
        val m1x = (x0 + x1) / 2f; val m1y = (y0 + y1) / 2f
        path.reset()
        if (i == 1) {
            path.moveTo(x0, y0)
            path.lineTo(m1x, m1y)
        } else {
            val xp = s.xs[i - 2] * sc + tx; val yp = s.ys[i - 2] * sc + ty
            path.moveTo((xp + x0) / 2f, (yp + y0) / 2f)
            path.quadTo(x0, y0, m1x, m1y)
        }
        canvas.drawPath(path, paint)
    }

    fun drawClosing(
        canvas: Canvas, s: Stroke, sc: Float, tx: Float, ty: Float,
        pageColor: Int, paint: Paint, path: Path
    ) {
        val n = s.size
        if (n < 2) return
        val i = n - 1
        paint.color = if (s.isEraser) pageColor else s.color
        paint.strokeWidth = (s.baseWidth * sc).coerceAtLeast(0.7f)
        val x0 = s.xs[i - 1] * sc + tx; val y0 = s.ys[i - 1] * sc + ty
        val x1 = s.xs[i] * sc + tx; val y1 = s.ys[i] * sc + ty
        path.reset()
        path.moveTo((x0 + x1) / 2f, (y0 + y1) / 2f)
        path.lineTo(x1, y1)
        canvas.drawPath(path, paint)
    }

    fun drawDot(
        canvas: Canvas, s: Stroke, sc: Float, tx: Float, ty: Float,
        pageColor: Int, paint: Paint
    ) {
        paint.color = if (s.isEraser) pageColor else s.color
        paint.style = Paint.Style.FILL
        canvas.drawCircle(
            s.xs[0] * sc + tx, s.ys[0] * sc + ty,
            (s.baseWidth * sc).coerceAtLeast(0.7f) / 2f, paint
        )
        paint.style = Paint.Style.STROKE
    }
}
