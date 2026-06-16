package com.stylusnotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * A continuous, vertically-scrolling handwriting surface.
 *
 * - **One finger / stylus draws.** Input is lightly smoothed and the line width
 *   is modulated by drawing speed (and pen pressure when available) so finger
 *   handwriting looks natural.
 * - **Two fingers scroll** the document up and down. Dragging past the bottom
 *   grows the note by another page, so there is no "add page" button.
 *
 * Strokes are kept in document coordinates. Only the visible window is
 * rasterised into [cache]; the in-progress stroke is drawn on top each frame.
 */
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { PEN, ERASER }
    private enum class Mode { NONE, DRAW, SCROLL }

    var tool: Tool = Tool.PEN
    var strokeColor: Int = Color.BLACK
    var strokeWidth: Float = 4f
    var eraserWidth: Float = 30f

    /** When true, finger/palm touches are ignored and only the stylus draws. */
    var stylusOnly: Boolean = false

    /** 0 = constant width, 1 = full speed/pressure-driven width variation. */
    var widthVariation: Float = 0.85f

    var onContentChanged: (() -> Unit)? = null
    var onViewportChanged: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val pageColor = Color.WHITE

    private val strokes = ArrayList<Stroke>()
    private val redoStack = ArrayList<Stroke>()
    private var current: Stroke? = null

    private var cache: Bitmap? = null
    private var cacheCanvas: Canvas? = null

    /** Height of one page in pixels; set once the view is measured. */
    var pageHeightPx = 0f
        private set
    var pageCount = 1
        private set

    /** Document-space offset of the top of the viewport. */
    var scrollY = 0f
        private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#D7DCE0")
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }

    // --- input state ---
    private var mode = Mode.NONE
    private var currentIsFinger = false
    private var lastFocalY = 0f
    private var lastSx = 0f
    private var lastSy = 0f
    private var lastT = 0L
    private var firstSample = false

    private val smoothing = 0.4f
    private val taperMin = 0.45f
    private val slowDpPerMs = 0.35f
    private val fastDpPerMs = 2.8f
    private val pageGrowthCap = 1000

    init {
        strokeWidth = 4f * density
        eraserWidth = 30f * density
        separatorPaint.strokeWidth = 1f * density
        isFocusable = true
        isHapticFeedbackEnabled = false
    }

    // ---- Public API ---------------------------------------------------------

    val canUndo: Boolean get() = strokes.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun loadContent(newStrokes: List<Stroke>, newPageCount: Int) {
        strokes.clear()
        strokes.addAll(newStrokes)
        redoStack.clear()
        current = null
        pageCount = newPageCount.coerceAtLeast(1)
        scrollY = 0f
        ensurePagesCoverStrokes()
        rebuildCache()
        onViewportChanged?.invoke()
    }

    fun getStrokes(): List<Stroke> = strokes

    fun undo() {
        if (strokes.isEmpty()) return
        redoStack.add(strokes.removeAt(strokes.lastIndex))
        rebuildCache()
        onContentChanged?.invoke()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        strokes.add(redoStack.removeAt(redoStack.lastIndex))
        rebuildCache()
        onContentChanged?.invoke()
    }

    fun clearAll() {
        strokes.clear()
        redoStack.clear()
        current = null
        pageCount = 1
        scrollY = 0f
        rebuildCache()
        onContentChanged?.invoke()
        onViewportChanged?.invoke()
    }

    /** 1-based index of the page currently centered in the viewport. */
    fun currentPage(): Int {
        if (pageHeightPx <= 0f) return 1
        val center = scrollY + height / 2f
        return (center / pageHeightPx).toInt().coerceIn(0, pageCount - 1) + 1
    }

    /** Small bitmap of the first page, for the home-screen thumbnail. */
    fun renderThumbnail(targetWidth: Int): Bitmap? {
        if (width <= 0 || pageHeightPx <= 0f) return null
        val scale = targetWidth.toFloat() / width
        val th = (pageHeightPx * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(targetWidth, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(pageColor)
        c.scale(scale, scale)
        c.clipRect(0f, 0f, width.toFloat(), pageHeightPx)
        for (s in strokes) {
            if (s.minY <= pageHeightPx) drawStroke(c, s, 0f)
        }
        return bmp
    }

    /** Full-document bitmap for PNG export (down-scaled if very tall). */
    fun renderFull(): Bitmap? {
        if (width <= 0 || pageHeightPx <= 0f) return null
        val docHeight = pageCount * pageHeightPx
        val maxDim = 8000f
        val scale = if (docHeight > maxDim) maxDim / docHeight else 1f
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (docHeight * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(pageColor)
        c.scale(scale, scale)
        for (p in 1 until pageCount) {
            val y = p * pageHeightPx
            c.drawLine(0f, y, width.toFloat(), y, separatorPaint)
        }
        for (s in strokes) drawStroke(c, s, 0f)
        return bmp
    }

    // ---- Touch handling -----------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentIsFinger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                if (stylusOnly && currentIsFinger) {
                    mode = Mode.NONE
                    return true
                }
                requestUnbufferedDispatch(event)
                redoStack.clear()
                mode = Mode.DRAW
                beginStroke(event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger means: scroll, not draw. Drop the half-drawn stroke.
                if (mode == Mode.DRAW) current = null
                mode = Mode.SCROLL
                lastFocalY = focalY(event, -1)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> {
                    val s = current ?: return true
                    for (h in 0 until event.historySize) {
                        addSample(
                            event.getHistoricalX(h),
                            event.getHistoricalY(h),
                            pressure(event.getHistoricalPressure(0, h)),
                            event.getHistoricalEventTime(h),
                            s
                        )
                    }
                    addSample(event.x, event.y, pressure(event.pressure), event.eventTime, s)
                    invalidate()
                }
                Mode.SCROLL -> {
                    val fy = focalY(event, -1)
                    applyScroll(fy - lastFocalY)
                    lastFocalY = fy
                }
                Mode.NONE -> {}
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Stay in scroll mode; recompute the focal point without the lifted finger.
                if (mode == Mode.SCROLL) {
                    lastFocalY = focalY(event, event.actionIndex)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAW) {
                    current?.let { s ->
                        if (s.size > 0) {
                            strokes.add(s)
                            cacheCanvas?.let { drawStroke(it, s, -scrollY) }
                            onContentChanged?.invoke()
                        }
                    }
                }
                current = null
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    private fun pressure(raw: Float): Float = if (raw <= 0f) 1f else raw

    private fun focalY(event: MotionEvent, skipIndex: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            sum += event.getY(i)
            n++
        }
        return if (n == 0) 0f else sum / n
    }

    private fun beginStroke(event: MotionEvent) {
        val s = Stroke(strokeColor, strokeWidth, tool == Tool.ERASER)
        firstSample = true
        addSample(event.x, event.y, pressure(event.pressure), event.eventTime, s)
        current = s
        invalidate()
    }

    private fun addSample(rawX: Float, rawY: Float, press: Float, time: Long, s: Stroke) {
        val docX = rawX
        val docY = rawY + scrollY
        val sx: Float
        val sy: Float
        if (firstSample) {
            sx = docX
            sy = docY
        } else {
            sx = lastSx + smoothing * (docX - lastSx)
            sy = lastSy + smoothing * (docY - lastSy)
        }
        val weight = computeWeight(sx, sy, press, time)
        s.addPoint(sx, sy, weight)
        lastSx = sx
        lastSy = sy
        lastT = time
        firstSample = false
    }

    private fun computeWeight(sx: Float, sy: Float, press: Float, time: Long): Float {
        if (firstSample) return 1f
        val dt = (time - lastT).coerceAtLeast(1L)
        val dist = hypot(sx - lastSx, sy - lastSy)
        val speedDp = (dist / dt) / density
        val t = ((speedDp - slowDpPerMs) / (fastDpPerMs - slowDpPerMs)).coerceIn(0f, 1f)
        val velFactor = 1f - t * (1f - taperMin)
        return if (currentIsFinger) velFactor else press.coerceIn(0f, 1f) * (0.5f + 0.5f * velFactor)
    }

    private fun maxScroll(): Float = (pageCount * pageHeightPx - height).coerceAtLeast(0f)

    private fun applyScroll(fingerDeltaY: Float) {
        if (pageHeightPx <= 0f) return
        var desired = scrollY - fingerDeltaY
        if (desired > maxScroll()) {
            // Dragging past the end grows the document downward, one page at a time.
            var guard = 0
            while (desired > maxScroll() && pageCount < pageGrowthCap && guard < 8) {
                pageCount++
                guard++
            }
            onContentChanged?.invoke()
        }
        desired = desired.coerceIn(0f, maxScroll())
        if (desired != scrollY) {
            scrollY = desired
            rebuildCache()
            onViewportChanged?.invoke()
        }
    }

    private fun ensurePagesCoverStrokes() {
        if (pageHeightPx <= 0f) return
        var maxY = 0f
        for (s in strokes) if (s.maxY > maxY) maxY = s.maxY
        val needed = ceil(maxY / pageHeightPx).toInt().coerceAtLeast(1)
        if (needed > pageCount) pageCount = needed
    }

    // ---- Rendering ----------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        pageHeightPx = h.toFloat()
        ensurePagesCoverStrokes()
        scrollY = scrollY.coerceIn(0f, maxScroll())
        cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        cacheCanvas = Canvas(cache!!)
        rebuildCache()
        onViewportChanged?.invoke()
    }

    private fun rebuildCache() {
        val c = cacheCanvas ?: return
        c.drawColor(pageColor, PorterDuff.Mode.SRC)
        val top = scrollY
        val bottom = scrollY + height
        for (p in 1 until pageCount) {
            val docY = p * pageHeightPx
            if (docY in top..bottom) {
                val y = docY - scrollY
                c.drawLine(0f, y, width.toFloat(), y, separatorPaint)
            }
        }
        for (s in strokes) {
            if (s.maxY >= top && s.minY <= bottom) drawStroke(c, s, -scrollY)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        cache?.let { canvas.drawBitmap(it, 0f, 0f, null) } ?: canvas.drawColor(pageColor)
        current?.let { drawStroke(canvas, it, -scrollY) }
    }

    private fun drawStroke(canvas: Canvas, s: Stroke, offsetY: Float) {
        val n = s.size
        if (n == 0) return
        paint.color = if (s.isEraser) pageColor else s.color
        if (n == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(s.xs[0], s.ys[0] + offsetY, widthAt(s, 0) / 2f, paint)
            paint.style = Paint.Style.STROKE
            return
        }
        for (i in 1 until n) {
            paint.strokeWidth = widthAt(s, i)
            canvas.drawLine(
                s.xs[i - 1], s.ys[i - 1] + offsetY,
                s.xs[i], s.ys[i] + offsetY,
                paint
            )
        }
    }

    private fun widthAt(s: Stroke, i: Int): Float {
        if (s.isEraser) return eraserWidth
        val weight = s.ps[i].coerceIn(0f, 1f)
        val scaled = 0.3f + 0.7f * weight
        val w = s.baseWidth * (1f - widthVariation + widthVariation * scaled)
        return w.coerceAtLeast(0.7f)
    }
}
