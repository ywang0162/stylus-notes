package com.stylusnotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A low-latency handwriting surface.
 *
 * Committed strokes are rasterised once into [cacheBitmap]; only the in-progress
 * stroke is re-rendered each frame. Pen pressure drives line width, and a
 * "stylus only" mode rejects finger/palm touches so you can rest your hand on
 * the screen while writing.
 */
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { PEN, ERASER }

    var tool: Tool = Tool.PEN
    var strokeColor: Int = Color.BLACK
    var strokeWidth: Float = 3f
    var eraserWidth: Float = 26f

    /** When true, finger and palm touches are ignored; only the stylus draws. */
    var stylusOnly: Boolean = true

    /** 0 = constant line width, 1 = fully pressure-driven width. */
    var pressureSensitivity: Float = 0.8f

    /** Invoked after a stroke is finished, an undo/redo, or a page clear. */
    var onContentChanged: (() -> Unit)? = null

    private val pageColor = Color.WHITE

    private val strokes = ArrayList<Stroke>()
    private val redoStack = ArrayList<Stroke>()
    private var current: Stroke? = null

    private var cacheBitmap: Bitmap? = null
    private var cacheCanvas: Canvas? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        val d = resources.displayMetrics.density
        strokeWidth = 3f * d
        eraserWidth = 26f * d
        isFocusable = true
        isHapticFeedbackEnabled = false
    }

    // ---- Public API ---------------------------------------------------------

    val canUndo: Boolean get() = strokes.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val isEmpty: Boolean get() = strokes.isEmpty() && current == null

    fun undo() {
        if (strokes.isEmpty()) return
        redoStack.add(strokes.removeAt(strokes.lastIndex))
        rebuildCache()
        onContentChanged?.invoke()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val s = redoStack.removeAt(redoStack.lastIndex)
        strokes.add(s)
        cacheCanvas?.let { drawStroke(it, s) }
        invalidate()
        onContentChanged?.invoke()
    }

    fun clearPage() {
        strokes.clear()
        redoStack.clear()
        current = null
        rebuildCache()
        onContentChanged?.invoke()
    }

    /** Replaces all content (used when loading a saved page). */
    fun setStrokes(newStrokes: List<Stroke>) {
        strokes.clear()
        strokes.addAll(newStrokes)
        redoStack.clear()
        current = null
        rebuildCache()
    }

    fun getStrokes(): List<Stroke> = strokes

    /** A copy of the rendered page, for PNG export. */
    fun snapshot(): Bitmap? = cacheBitmap?.copy(Bitmap.Config.ARGB_8888, false)

    // ---- Touch handling -----------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isFinger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        if (stylusOnly && isFinger) return true // palm rejection

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Ask the platform for unbuffered input to cut stroke latency.
                requestUnbufferedDispatch(event)
                redoStack.clear()
                val s = Stroke(strokeColor, strokeWidth, tool == Tool.ERASER)
                s.addPoint(event.x, event.y, pressureOf(event))
                current = s
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val s = current ?: return true
                // Replay batched historical samples first for smooth, dense lines.
                for (h in 0 until event.historySize) {
                    s.addPoint(
                        event.getHistoricalX(h),
                        event.getHistoricalY(h),
                        historicalPressure(event, h)
                    )
                }
                s.addPoint(event.x, event.y, pressureOf(event))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { s ->
                    s.addPoint(event.x, event.y, pressureOf(event))
                    strokes.add(s)
                    cacheCanvas?.let { drawStroke(it, s) }
                    current = null
                    onContentChanged?.invoke()
                }
                invalidate()
            }
        }
        return true
    }

    private fun pressureOf(e: MotionEvent): Float {
        val p = e.pressure
        return if (p <= 0f) 1f else p
    }

    private fun historicalPressure(e: MotionEvent, h: Int): Float {
        val p = e.getHistoricalPressure(0, h)
        return if (p <= 0f) 1f else p
    }

    // ---- Rendering ----------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        cacheBitmap = bmp
        cacheCanvas = Canvas(bmp)
        rebuildCache()
    }

    private fun rebuildCache() {
        val c = cacheCanvas ?: return
        c.drawColor(pageColor, PorterDuff.Mode.SRC)
        for (s in strokes) drawStroke(c, s)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        cacheBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) } ?: canvas.drawColor(pageColor)
        current?.let { drawStroke(canvas, it) }
    }

    private fun drawStroke(canvas: Canvas, s: Stroke) {
        val n = s.size
        if (n == 0) return
        paint.color = if (s.isEraser) pageColor else s.color

        if (n == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(s.xs[0], s.ys[0], widthAt(s, 0) / 2f, paint)
            paint.style = Paint.Style.STROKE
            return
        }
        for (i in 1 until n) {
            paint.strokeWidth = widthAt(s, i)
            canvas.drawLine(s.xs[i - 1], s.ys[i - 1], s.xs[i], s.ys[i], paint)
        }
    }

    private fun widthAt(s: Stroke, i: Int): Float {
        if (s.isEraser) return eraserWidth
        val p = s.ps[i].coerceIn(0f, 1f)
        val pressured = s.baseWidth * (0.3f + 0.7f * p)
        val w = s.baseWidth + (pressured - s.baseWidth) * pressureSensitivity
        return w.coerceAtLeast(0.7f)
    }
}
