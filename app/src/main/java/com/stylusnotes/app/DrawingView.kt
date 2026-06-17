package com.stylusnotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A minimal handwriting surface: one finger (or stylus) draws, two fingers
 * pinch to zoom and drag to pan. Nothing fancier — it tracks the finger and
 * shows the ink.
 *
 * Strokes live in document coordinates. A view transform (scale + translate)
 * maps document space to the screen. Committed strokes are rasterised once into
 * a viewport-sized [cache] bitmap; while drawing, only the new segment is baked
 * in, so per-frame cost stays flat and [onDraw] is a single blit.
 */
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { PEN, ERASER }
    private enum class Mode { NONE, DRAW, TRANSFORM, BOXMOVE, BOXRESIZE }

    var tool: Tool = Tool.PEN
    var strokeColor: Int = Color.BLACK
    var strokeWidth: Float = 4f
    var eraserWidth: Float = 30f

    /** When true, finger/palm touches are ignored and only the stylus draws. */
    var stylusOnly: Boolean = false

    var onContentChanged: (() -> Unit)? = null
    var onViewportChanged: (() -> Unit)? = null

    // Zoom-write: a movable box on the page that the writing panel projects into.
    var zoomWriteMode = false
        private set
    val zoomWriteBox = RectF()
    var onZoomBoxChanged: (() -> Unit)? = null

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

    // View transform: screen = doc * scale + trans.
    private var scale = 1f
    private var transX = 0f
    private var transY = 0f
    private val minScale = 0.3f
    private val maxScale = 6f
    private val pageGrowthCap = 1000

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
    private val boxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2962FF")
    }
    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#142962FF")
    }
    private val boxHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2962FF")
    }
    private val boxHandleHitPx = 28f * density
    private val boxHandleRadiusPx = 8f * density
    private var boxAspect = 1f

    private var mode = Mode.NONE
    private var liveSegDrawn = 0
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var boxDragX = 0f
    private var boxDragY = 0f

    // Finger optimization: ignore samples closer than this (in screen px) to the
    // last one. Drops finger micro-jitter and point bloat without adding lag, so
    // lines stay clean on the small screen. Filtering in screen space means high
    // zoom still captures fine detail.
    private val minSampleDistPx = 1.2f * density
    private var lastAddX = 0f
    private var lastAddY = 0f
    private var haveLastAdd = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean = true
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val newScale = InkMath.coerceScale(scale * d.scaleFactor, minScale, maxScale)
                val ratio = newScale / scale
                transX = InkMath.zoomTranslation(d.focusX, transX, ratio)
                transY = InkMath.zoomTranslation(d.focusY, transY, ratio)
                scale = newScale
                clampTransform()
                return true
            }
        }
    )

    init {
        strokeWidth = 4f * density
        eraserWidth = 30f * density
        separatorPaint.strokeWidth = 1f * density
        boxStrokePaint.strokeWidth = 2f * density
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
        scale = 1f
        transX = 0f
        transY = 0f
        ensurePagesCoverStrokes()
        clampTransform()
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
        scale = 1f
        transX = 0f
        transY = 0f
        clampTransform()
        rebuildCache()
        onContentChanged?.invoke()
        onViewportChanged?.invoke()
    }

    /** 1-based index of the page currently centered in the viewport. */
    fun currentPage(): Int {
        if (pageHeightPx <= 0f) return 1
        val centerDocY = (height / 2f - transY) / scale
        return (centerDocY / pageHeightPx).toInt().coerceIn(0, pageCount - 1) + 1
    }

    /** Current zoom as a percentage (100 = actual size). */
    fun zoomPercent(): Int = (scale * 100f).roundToInt()

    /** Zoom in/out by [factor] about the viewport center (for top-bar buttons). */
    fun zoomBy(factor: Float) = applyZoom(scale * factor, width / 2f, height / 2f)

    /** Reset to 100% (actual size). */
    fun resetZoom() = applyZoom(1f, width / 2f, height / 2f)

    private fun applyZoom(targetScale: Float, focusX: Float, focusY: Float) {
        val newScale = InkMath.coerceScale(targetScale, minScale, maxScale)
        if (newScale == scale) return
        val ratio = newScale / scale
        transX = InkMath.zoomTranslation(focusX, transX, ratio)
        transY = InkMath.zoomTranslation(focusY, transY, ratio)
        scale = newScale
        clampTransform()
        rebuildCache()
        invalidate()
        onViewportChanged?.invoke()
    }

    /** Small bitmap of the first page, for the home-screen thumbnail. */
    fun renderThumbnail(targetWidth: Int): Bitmap? {
        if (width <= 0 || pageHeightPx <= 0f) return null
        val sc = targetWidth.toFloat() / width
        val th = (pageHeightPx * sc).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(targetWidth, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(pageColor)
        c.clipRect(0f, 0f, targetWidth.toFloat(), th.toFloat())
        for (s in strokes) if (s.minY <= pageHeightPx) {
            StrokeRenderer.drawFull(c, s, sc, 0f, 0f, pageColor, paint, segPath)
        }
        return bmp
    }

    /** Full-document bitmap for PNG export (down-scaled if very tall). */
    fun renderFull(): Bitmap? {
        if (width <= 0 || pageHeightPx <= 0f) return null
        val docHeight = pageCount * pageHeightPx
        val maxDim = 8000f
        val sc = if (docHeight > maxDim) maxDim / docHeight else 1f
        val w = (width * sc).toInt().coerceAtLeast(1)
        val h = (docHeight * sc).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(pageColor)
        for (p in 1 until pageCount) {
            val y = p * pageHeightPx * sc
            c.drawLine(0f, y, w.toFloat(), y, separatorPaint)
        }
        for (s in strokes) StrokeRenderer.drawFull(c, s, sc, 0f, 0f, pageColor, paint, segPath)
        return bmp
    }

    // ---- Touch handling -----------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (zoomWriteMode) {
                    // In zoom-write mode the page positions/resizes the box, not draws.
                    val brx = zoomWriteBox.right * scale + transX
                    val bry = zoomWriteBox.bottom * scale + transY
                    mode = if (hypot(event.x - brx, event.y - bry) <= boxHandleHitPx) {
                        Mode.BOXRESIZE
                    } else {
                        Mode.BOXMOVE
                    }
                    boxDragX = event.x
                    boxDragY = event.y
                    return true
                }
                if (stylusOnly && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                    mode = Mode.NONE
                    return true
                }
                requestUnbufferedDispatch(event)
                redoStack.clear()
                mode = Mode.DRAW
                beginStroke(event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger: stop drawing, drop the half-drawn stroke, zoom/pan.
                if (mode == Mode.DRAW) {
                    current = null
                    rebuildCache()
                }
                mode = Mode.TRANSFORM
                lastFocusX = focalX(event, -1)
                lastFocusY = focalY(event, -1)
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> if (event.pointerCount == 1) {
                    val s = current ?: return true
                    for (h in 0 until event.historySize) {
                        addSample(event.getHistoricalX(h), event.getHistoricalY(h), s)
                    }
                    addSample(event.x, event.y, s)
                    bakeNewSegments(s)
                    invalidate()
                }
                Mode.TRANSFORM -> {
                    val fx = focalX(event, -1)
                    val fy = focalY(event, -1)
                    pan(fx - lastFocusX, fy - lastFocusY)
                    lastFocusX = fx
                    lastFocusY = fy
                }
                Mode.BOXMOVE -> if (event.pointerCount == 1) {
                    moveZoomBox((event.x - boxDragX) / scale, (event.y - boxDragY) / scale)
                    boxDragX = event.x
                    boxDragY = event.y
                }
                Mode.BOXRESIZE -> if (event.pointerCount == 1) {
                    resizeZoomBox(event.x, event.y)
                }
                Mode.NONE -> {}
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.TRANSFORM) {
                    lastFocusX = focalX(event, event.actionIndex)
                    lastFocusY = focalY(event, event.actionIndex)
                }
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

    private fun focalX(event: MotionEvent, skipIndex: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            sum += event.getX(i)
            n++
        }
        return if (n == 0) 0f else sum / n
    }

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
        haveLastAdd = false
        addSample(event.x, event.y, s)
        liveSegDrawn = 0
        current = s
    }

    private fun commitStroke(event: MotionEvent) {
        val s = current ?: return
        addSample(event.x, event.y, s, force = true)
        bakeNewSegments(s)
        val c = cacheCanvas
        if (c != null) {
            if (s.size == 1) StrokeRenderer.drawDot(c, s, scale, transX, transY, pageColor, paint)
            else StrokeRenderer.drawClosing(c, s, scale, transX, transY, pageColor, paint, segPath)
        }
        if (s.size > 0) {
            strokes.add(s)
            onContentChanged?.invoke()
        }
        invalidate()
    }

    /** Maps a screen touch to document space and records the point, skipping
     *  samples too close to the previous one (jitter/point-bloat filter). */
    private fun addSample(screenX: Float, screenY: Float, s: Stroke, force: Boolean = false) {
        if (!force && haveLastAdd && hypot(screenX - lastAddX, screenY - lastAddY) < minSampleDistPx) {
            return
        }
        s.addPoint((screenX - transX) / scale, (screenY - transY) / scale)
        lastAddX = screenX
        lastAddY = screenY
        haveLastAdd = true
    }

    private fun bakeNewSegments(s: Stroke) {
        val c = cacheCanvas ?: return
        repeat(InkMath.pendingSegments(liveSegDrawn, s.size)) {
            val i = liveSegDrawn + 1
            StrokeRenderer.drawSegment(c, s, i, scale, transX, transY, pageColor, paint, segPath)
            liveSegDrawn = i
        }
    }

    // ---- Pan / zoom ---------------------------------------------------------

    private fun pan(dx: Float, dy: Float) {
        if (pageHeightPx <= 0f) return
        transX += dx
        var ty = transY + dy
        // Dragging past the bottom grows the document one page at a time.
        var guard = 0
        while (ty < height - pageCount * pageHeightPx * scale &&
            pageCount < pageGrowthCap && guard < 8
        ) {
            pageCount++
            guard++
        }
        transY = ty
        clampTransform()
        rebuildCache()
        onViewportChanged?.invoke()
    }

    private fun clampTransform() {
        if (width <= 0 || height <= 0) return
        transX = InkMath.clampTransX(transX, scale, width.toFloat(), width)
        val contentH = pageCount * pageHeightPx * scale
        transY = if (contentH <= height) 0f else transY.coerceIn(height - contentH, 0f)
    }

    private fun ensurePagesCoverStrokes() {
        if (pageHeightPx <= 0f) return
        var maxY = 0f
        for (s in strokes) if (s.maxY > maxY) maxY = s.maxY
        val needed = InkMath.pagesNeeded(maxY, pageHeightPx)
        if (needed > pageCount) pageCount = needed
    }

    // ---- Rendering ----------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        pageHeightPx = h.toFloat()
        ensurePagesCoverStrokes()
        clampTransform()
        cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        cacheCanvas = Canvas(cache!!)
        rebuildCache()
        onViewportChanged?.invoke()
    }

    private fun rebuildCache() {
        val c = cacheCanvas ?: return
        c.drawColor(pageColor, PorterDuff.Mode.SRC)
        val topDoc = -transY / scale
        val botDoc = (height - transY) / scale
        for (p in 1 until pageCount) {
            val docY = p * pageHeightPx
            if (docY in topDoc..botDoc) {
                val y = docY * scale + transY
                c.drawLine(0f, y, width.toFloat(), y, separatorPaint)
            }
        }
        for (s in strokes) {
            if (s.maxY >= topDoc && s.minY <= botDoc) {
                StrokeRenderer.drawFull(c, s, scale, transX, transY, pageColor, paint, segPath)
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        cache?.let { canvas.drawBitmap(it, 0f, 0f, null) } ?: canvas.drawColor(pageColor)
        // The cache is baked only up to the last segment midpoint. Draw the live
        // tip out to the latest tracked point each frame so the ink reaches the
        // finger instead of trailing half a segment behind it.
        val s = current
        if (mode == Mode.DRAW && s != null) {
            if (s.size == 1) StrokeRenderer.drawDot(canvas, s, scale, transX, transY, pageColor, paint)
            else StrokeRenderer.drawClosing(canvas, s, scale, transX, transY, pageColor, paint, segPath)
        }
        if (zoomWriteMode) drawZoomBox(canvas)
    }

    // ---- Zoom-write mode ----------------------------------------------------

    /** Turns on the movable write box, centered on the current view. [panelAspect]
     *  is the writing panel's height/width, so the box matches its shape. */
    fun enterZoomWrite(panelAspect: Float) {
        if (width <= 0) return
        boxAspect = panelAspect
        val boxW = width * 0.4f
        val boxH = boxW * panelAspect
        val cx = (width / 2f - transX) / scale
        val cy = (height / 2f - transY) / scale
        val l = (cx - boxW / 2f).coerceIn(0f, (width - boxW).coerceAtLeast(0f))
        val maxT = (pageCount * pageHeightPx - boxH).coerceAtLeast(0f)
        val t = (cy - boxH / 2f).coerceIn(0f, maxT)
        zoomWriteBox.set(l, t, l + boxW, t + boxH)
        zoomWriteMode = true
        invalidate()
        onZoomBoxChanged?.invoke()
    }

    fun exitZoomWrite() {
        zoomWriteMode = false
        invalidate()
    }

    /** Resizes the box by dragging its bottom-right corner toward [screenX,screenY].
     *  Aspect stays locked to the panel and the top-left corner is the anchor.
     *  Smaller box = more magnification; larger box = less. */
    private fun resizeZoomBox(screenX: Float, screenY: Float) {
        if (boxAspect <= 0f) return
        val leftScreen = zoomWriteBox.left * scale + transX
        val minW = width * 0.15f
        val fitW = (width - zoomWriteBox.left)
        val fitH = ((pageCount * pageHeightPx - zoomWriteBox.top) / boxAspect)
        val maxW = minOf(width * 0.95f, fitW, fitH).coerceAtLeast(minW)
        val newW = ((screenX - leftScreen) / scale).coerceIn(minW, maxW)
        val newH = newW * boxAspect
        zoomWriteBox.set(
            zoomWriteBox.left, zoomWriteBox.top,
            zoomWriteBox.left + newW, zoomWriteBox.top + newH
        )
        invalidate()
        onZoomBoxChanged?.invoke()
    }

    /** Moves the write box by a document-space delta, clamped to the page. */
    private fun moveZoomBox(docDx: Float, docDy: Float) {
        val w = zoomWriteBox.width()
        val h = zoomWriteBox.height()
        val l = (zoomWriteBox.left + docDx).coerceIn(0f, (width - w).coerceAtLeast(0f))
        val maxT = (pageCount * pageHeightPx - h).coerceAtLeast(0f)
        val t = (zoomWriteBox.top + docDy).coerceIn(0f, maxT)
        zoomWriteBox.set(l, t, l + w, t + h)
        invalidate()
        onZoomBoxChanged?.invoke()
    }

    /** Adds a finished stroke (document coordinates) from the write panel. */
    fun addDocStroke(s: Stroke) {
        if (s.size == 0) return
        strokes.add(s)
        redoStack.clear()
        ensurePagesCoverStrokes()
        clampTransform()
        rebuildCache()
        onContentChanged?.invoke()
    }

    private fun drawZoomBox(canvas: Canvas) {
        val l = zoomWriteBox.left * scale + transX
        val t = zoomWriteBox.top * scale + transY
        val r = zoomWriteBox.right * scale + transX
        val b = zoomWriteBox.bottom * scale + transY
        canvas.drawRect(l, t, r, b, boxFillPaint)
        canvas.drawRect(l, t, r, b, boxStrokePaint)
        // Resize handle at the bottom-right corner.
        canvas.drawCircle(r, b, boxHandleRadiusPx, boxHandlePaint)
    }
}
