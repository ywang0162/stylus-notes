package com.stylusnotes.app

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stylusnotes.app.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: NoteStorage

    private lateinit var pages: MutableList<File>
    private var pageIndex = 0

    private val density get() = resources.displayMetrics.density
    private val widthsDp = floatArrayOf(2f, 4f, 8f)
    private var widthIndex = 1

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { saveCurrentPage() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = NoteStorage(this)
        pages = storage.listPages().toMutableList()
        pageIndex = 0

        val dv = binding.drawingView
        dv.strokeWidth = widthsDp[widthIndex] * density
        dv.onContentChanged = {
            scheduleAutosave()
            updateButtons()
        }

        wireToolbar()
        loadCurrentPage()
        selectTool(DrawingView.Tool.PEN)
        selectColor(Color.BLACK)
    }

    private fun wireToolbar() = with(binding) {
        btnPen.setOnClickListener { selectTool(DrawingView.Tool.PEN) }
        btnEraser.setOnClickListener { selectTool(DrawingView.Tool.ERASER) }

        btnColorBlack.setOnClickListener { selectColor(Color.BLACK) }
        btnColorBlue.setOnClickListener { selectColor(Color.parseColor("#1565C0")) }
        btnColorRed.setOnClickListener { selectColor(Color.parseColor("#C62828")) }
        btnColorGreen.setOnClickListener { selectColor(Color.parseColor("#2E7D32")) }

        btnWidth.setOnClickListener { cycleWidth() }
        btnUndo.setOnClickListener { drawingView.undo() }
        btnRedo.setOnClickListener { drawingView.redo() }
        btnClear.setOnClickListener { drawingView.clearPage() }

        btnStylus.setOnClickListener { toggleStylusOnly() }

        btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        btnNewPage.setOnClickListener { addPage() }
        btnExport.setOnClickListener { exportCurrentPage() }
    }

    // ---- Tools --------------------------------------------------------------

    private fun selectTool(tool: DrawingView.Tool) {
        binding.drawingView.tool = tool
        binding.btnPen.isSelected = tool == DrawingView.Tool.PEN
        binding.btnEraser.isSelected = tool == DrawingView.Tool.ERASER
    }

    private fun selectColor(color: Int) {
        binding.drawingView.strokeColor = color
        selectTool(DrawingView.Tool.PEN)
        with(binding) {
            btnColorBlack.isSelected = color == Color.BLACK
            btnColorBlue.isSelected = color == Color.parseColor("#1565C0")
            btnColorRed.isSelected = color == Color.parseColor("#C62828")
            btnColorGreen.isSelected = color == Color.parseColor("#2E7D32")
        }
    }

    private fun cycleWidth() {
        widthIndex = (widthIndex + 1) % widthsDp.size
        binding.drawingView.strokeWidth = widthsDp[widthIndex] * density
        val label = when (widthIndex) { 0 -> "Thin"; 1 -> "Medium"; else -> "Thick" }
        toast("Pen: $label")
    }

    private fun toggleStylusOnly() {
        val on = !binding.drawingView.stylusOnly
        binding.drawingView.stylusOnly = on
        binding.btnStylus.isSelected = on
        toast(if (on) "Stylus only (palm rejection on)" else "Finger drawing allowed")
    }

    // ---- Pages --------------------------------------------------------------

    private fun loadCurrentPage() {
        binding.drawingView.setStrokes(storage.loadPage(pages[pageIndex]))
        updatePageIndicator()
        updateButtons()
    }

    private fun saveCurrentPage() {
        mainHandler.removeCallbacks(autosave)
        if (pageIndex in pages.indices) {
            storage.savePage(pages[pageIndex], binding.drawingView.getStrokes())
        }
    }

    private fun scheduleAutosave() {
        mainHandler.removeCallbacks(autosave)
        mainHandler.postDelayed(autosave, 700)
    }

    private fun goToPage(index: Int) {
        if (index !in pages.indices || index == pageIndex) return
        saveCurrentPage()
        pageIndex = index
        loadCurrentPage()
    }

    private fun addPage() {
        saveCurrentPage()
        val file = storage.createPage()
        pages.add(file)
        pageIndex = pages.lastIndex
        loadCurrentPage()
        toast("New page")
    }

    private fun updatePageIndicator() {
        binding.tvPage.text = "${pageIndex + 1}/${pages.size}"
    }

    // ---- Export -------------------------------------------------------------

    private fun exportCurrentPage() {
        val bmp = binding.drawingView.snapshot()
        if (bmp == null) {
            toast("Nothing to export yet")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val uri = storage.exportPng(bmp, "note_${pageIndex + 1}_$stamp")
        toast(if (uri != null) "Saved to Pictures/StylusNotes" else "Export failed")
    }

    // ---- Misc ---------------------------------------------------------------

    private fun updateButtons() = with(binding) {
        btnUndo.isEnabled = drawingView.canUndo
        btnRedo.isEnabled = drawingView.canRedo
        btnPrev.isEnabled = pageIndex > 0
        btnNext.isEnabled = pageIndex < pages.size - 1
        listOf<View>(btnUndo, btnRedo, btnPrev, btnNext).forEach {
            it.alpha = if (it.isEnabled) 1f else 0.35f
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPage()
    }
}
