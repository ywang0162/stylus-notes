package com.stylusnotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * A continuous, vertically-scrolling handwriting surface tuned for fast, smooth
 * finger writing.
 *
 * Performance/feel design:
 * - **Incremental baking.** Each new stroke segment is drawn straight into the
 *   committed [cache] bitmap as the finger moves, so per-frame work is constant
 *   instead of growing with stroke length. [onDraw] is just a single blit.
 * - **Low-latency smoothing.** Strokes are rendered as quadratic curves through
 *   the midpoints of the raw samples. This smooths finger jitter without the
 *   trailing lag of an averaging filter, so the ink stays under the fingertip.
 * - **Width from speed.** Line width follows drawing speed (and pen pressure for
 *   a stylus); only the width weight is smoothed, never the geometry.
 *
 * Touch model: one finger/stylus draws, two fingers scroll, and dragging past
 * the bottom grows the document by a page.
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

    var pageHeightPx = 0f
        private set
    var pageCount = 1
        private set
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
    private val segPath = Path()

    // --- input state ---
    private var mode = Mode.NONE
    private var currentIsFinger = false
    private var firstSample = false
    private var liveSegDrawn = 0
    private var lastFocalY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var lastWeight = 1f

    // Stroke continuation: stitches strokes back together when the digitizer
    // briefly drops contact (common on the Thor's secondary screen, esp. at the
    // edges), so loops like "O" don't fragment into disconnected arcs.
    private var lastStroke: Stroke? = null
    private var lastUpTime = 0L
    private val continuationMaxGapMs = 140L
    private val continuationMaxDistDp = 18f

    private val weightSmoothing = 0.5f
    private val taperMin = 0.5f
    private val slowDpPerMs = 0.35f
    private val fastDpPerMs = 3.0f
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
        lastStroke = null
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
        lastStroke = null
        rebuildCache()
        onContentChanged?.invoke()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        strokes.add(redoStack.removeAt(redoStack.lastIndex))
        lastStroke = null
        rebuildCache()
        onContentChanged?.invoke()
    }

    fun clearAll() {
        strokes.clear()
        redoStack.clear()
        current = null
        lastStroke = null
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
            if (s.minY <= pageHeightPx) drawStrokeFull(c, s, 0f)
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
        for (s in strokes) drawStrokeFull(c, s, 0f)
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
                if (shouldContinue(event)) beginStrokeContinuing(lastStroke!!, event)
                else beginStroke(event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger: stop drawing, drop the half-drawn stroke, scroll.
                if (mode == Mode.DRAW) {
                    current = null
                    rebuildCache()
                }
                lastStroke = null
                mode = Mode.SCROLL
                lastFocalY = focalY(event, -1)
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
                    bakeNewSegments(s)
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
                if (mode == Mode.SCROLL) lastFocalY = focalY(event, event.actionIndex)
            }

            MotionEvent.ACTION_UP -> {
                if (mode == Mode.DRAW) commitStroke(event)
                current = null
                mode = Mode.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAW && current != null) {
                    current = null
                    rebuildCache()
                }
                current = null
                mode = Mode.NONE
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
        lastWeight = 1f
        addSample(event.x, event.y, pressure(event.pressure), event.eventTime, s)
        liveSegDrawn = 0
        current = s
    }

    /** True if a fresh touch is close enough (in time and space) to the last
     *  stroke that it is almost certainly the same stroke resuming after a
     *  momentary contact dropout, rather than a deliberate new stroke. */
    private fun shouldContinue(event: MotionEvent): Boolean {
        val ls = lastStroke ?: return false
        if (ls.size == 0) return false
        if (event.eventTime - lastUpTime > continuationMaxGapMs) return false
        if (ls.isEraser != (tool == Tool.ERASER)) return false
        if (!ls.isEraser && ls.color != strokeColor) return false
        val ex = ls.xs[ls.size - 1]
        val ey = ls.ys[ls.size - 1]
        val distDp = hypot(event.x - ex, (event.y + scrollY) - ey) / density
        return distDp <= continuationMaxDistDp
    }

    /** Starts a new stroke seeded at the previous stroke's end point, so the
     *  bridge across the dropout is drawn and the result looks continuous. */
    private fun beginStrokeContinuing(prev: Stroke, event: MotionEvent) {
        val s = Stroke(strokeColor, strokeWidth, tool == Tool.ERASER)
        val last = prev.size - 1
        s.addPoint(prev.xs[last], prev.ys[last], prev.ps[last])
        firstSample = false
        lastX = prev.xs[last]
        lastY = prev.ys[last]
        lastT = event.eventTime
        lastWeight = prev.ps[last]
        liveSegDrawn = 0
        current = s
        addSample(event.x, event.y, pressure(event.pressure), event.eventTime, s)
        bakeNewSegments(s)
        invalidate()
    }

    private fun commitStroke(event: MotionEvent) {
        val s = current ?: return
        addSample(event.x, event.y, pressure(event.pressure), event.eventTime, s)
        bakeNewSegments(s)
        val c = cacheCanvas
        if (c != null) {
            if (s.size == 1) drawDot(c, s, -scrollY) else drawClosing(c, s, -scrollY)
        }
        if (s.size > 0) {
            strokes.add(s)
            lastStroke = s
            lastUpTime = event.eventTime
            onContentChanged?.invoke()
        }
        invalidate()
    }

    /** Stores one raw sample with a smoothed width weight (geometry stays raw). */
    private fun addSample(rawX: Float, rawY: Float, press: Float, time: Long, s: Stroke) {
        val docX = rawX
        val docY = rawY + scrollY
        val weight: Float
        if (firstSample) {
            weight = 1f
            firstSample = false
        } else {
            val dt = (time - lastT).coerceAtLeast(1L)
            val dist = hypot(docX - lastX, docY - lastY)
            val speedDp = (dist / dt) / density
            val t = ((speedDp - slowDpPerMs) / (fastDpPerMs - slowDpPerMs)).coerceIn(0f, 1f)
            val velFactor = 1f - t * (1f - taperMin)
            val raw =
                if (currentIsFinger) velFactor
                else press.coerceIn(0f, 1f) * (0.5f + 0.5f * velFactor)
            weight = lastWeight + weightSmoothing * (raw - lastWeight)
        }
        s.addPoint(docX, docY, weight)
        lastX = docX
        lastY = docY
        lastT = time
        lastWeight = weight
    }

    /** Bakes any not-yet-rendered segments of the live stroke into the cache. */
    private fun bakeNewSegments(s: Stroke) {
        val c = cacheCanvas ?: return
        while (liveSegDrawn < s.size - 1) {
            val i = liveSegDrawn + 1
            drawSegment(c, s, i, -scrollY)
            liveSegDrawn = i
        }
    }

    private fun maxScroll(): Float = (pageCount * pageHeightPx - height).coerceAtLeast(0f)

    private fun applyScroll(fingerDeltaY: Float) {
        if (pageHeightPx <= 0f) return
        var desired = scrollY - fingerDeltaY
        if (desired > maxScroll()) {
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
            if (s.maxY >= top && s.minY <= bottom) drawStrokeFull(c, s, -scrollY)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        cache?.let { canvas.drawBitmap(it, 0f, 0f, null) } ?: canvas.drawColor(pageColor)
    }

    // ---- Stroke drawing (quadratic-midpoint smoothing) ----------------------

    private fun drawStrokeFull(canvas: Canvas, s: Stroke, oy: Float) {
        val n = s.size
        if (n == 0) return
        if (n == 1) {
            drawDot(canvas, s, oy)
            return
        }
        for (i in 1 until n) drawSegment(canvas, s, i, oy)
        drawClosing(canvas, s, oy)
    }

    /** Draws the smoothed segment that ends at the midpoint of points i-1 and i. */
    private fun drawSegment(canvas: Canvas, s: Stroke, i: Int, oy: Float) {
        paint.color = if (s.isEraser) pageColor else s.color
        paint.strokeWidth = widthAt(s, i)
        val x0 = s.xs[i - 1]; val y0 = s.ys[i - 1] + oy
        val x1 = s.xs[i]; val y1 = s.ys[i] + oy
        val m1x = (x0 + x1) / 2f; val m1y = (y0 + y1) / 2f
        segPath.reset()
        if (i == 1) {
            segPath.moveTo(x0, y0)
            segPath.lineTo(m1x, m1y)
        } else {
            val xp = s.xs[i - 2]; val yp = s.ys[i - 2] + oy
            val m0x = (xp + x0) / 2f; val m0y = (yp + y0) / 2f
            segPath.moveTo(m0x, m0y)
            segPath.quadTo(x0, y0, m1x, m1y)
        }
        canvas.drawPath(segPath, paint)
    }

    /** Connects the last drawn midpoint to the true final point. */
    private fun drawClosing(canvas: Canvas, s: Stroke, oy: Float) {
        val n = s.size
        if (n < 2) return
        val i = n - 1
        paint.color = if (s.isEraser) pageColor else s.color
        paint.strokeWidth = widthAt(s, i)
        val x0 = s.xs[i - 1]; val y0 = s.ys[i - 1] + oy
        val x1 = s.xs[i]; val y1 = s.ys[i] + oy
        val m0x = (x0 + x1) / 2f; val m0y = (y0 + y1) / 2f
        segPath.reset()
        segPath.moveTo(m0x, m0y)
        segPath.lineTo(x1, y1)
        canvas.drawPath(segPath, paint)
    }

    private fun drawDot(canvas: Canvas, s: Stroke, oy: Float) {
        paint.color = if (s.isEraser) pageColor else s.color
        paint.style = Paint.Style.FILL
        canvas.drawCircle(s.xs[0], s.ys[0] + oy, widthAt(s, 0) / 2f, paint)
        paint.style = Paint.Style.STROKE
    }

    private fun widthAt(s: Stroke, i: Int): Float {
        if (s.isEraser) return eraserWidth
        val idx = i.coerceIn(0, s.size - 1)
        val weight = s.ps[idx].coerceIn(0f, 1f)
        val scaled = 0.3f + 0.7f * weight
        val w = s.baseWidth * (1f - widthVariation + widthVariation * scaled)
        return w.coerceAtLeast(0.7f)
    }
}
