package com.stylusnotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * The bottom "zoom-write" strip: a magnified window into the note's write box.
 * You write large here; strokes are recorded in document coordinates and pushed
 * to the note, where they appear at normal size inside the box. Existing ink in
 * the box region is shown (magnified) for context.
 */
class ZoomWritePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var source: DrawingView? = null

    private val density = resources.displayMetrics.density
    private val pageColor = Color.WHITE
    private val minSampleDistPx = 1.2f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E2E6EA")
        strokeWidth = 1f * density
    }

    private var current: Stroke? = null
    private var lastAddX = 0f
    private var lastAddY = 0f
    private var haveLastAdd = false

    /** Document -> panel scale, from the current box width. */
    private fun panelScale(): Float {
        val box = source?.zoomWriteBox ?: return 1f
        return if (box.width() > 0f) width / box.width() else 1f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(pageColor)
        val src = source ?: return
        val box = src.zoomWriteBox
        if (box.width() <= 0f) return

        // Writing baseline guide.
        canvas.drawLine(0f, height * 0.72f, width.toFloat(), height * 0.72f, guidePaint)

        val sc = panelScale()
        val tx = -box.left * sc
        val ty = -box.top * sc
        for (s in src.getStrokes()) {
            if (s.maxY >= box.top && s.minY <= box.bottom) {
                StrokeRenderer.drawFull(canvas, s, sc, tx, ty, pageColor, paint, path)
            }
        }
        current?.let { StrokeRenderer.drawFull(canvas, it, sc, tx, ty, pageColor, paint, path) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val src = source ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = Stroke(src.strokeColor, src.strokeWidth, src.tool == DrawingView.Tool.ERASER)
                haveLastAdd = false
                addSample(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                current ?: return true
                for (h in 0 until event.historySize) {
                    addSample(event.getHistoricalX(h), event.getHistoricalY(h))
                }
                addSample(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                current?.let {
                    addSample(event.x, event.y, force = true)
                    src.addDocStroke(it)
                }
                current = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                current = null
                invalidate()
            }
        }
        return true
    }

    /** Maps a panel touch into document space inside the box and records it. */
    private fun addSample(screenX: Float, screenY: Float, force: Boolean = false) {
        val src = source ?: return
        val s = current ?: return
        if (!force && haveLastAdd && hypot(screenX - lastAddX, screenY - lastAddY) < minSampleDistPx) {
            return
        }
        val box = src.zoomWriteBox
        val sc = panelScale()
        s.addPoint(box.left + screenX / sc, box.top + screenY / sc)
        lastAddX = screenX
        lastAddY = screenY
        haveLastAdd = true
    }
}
