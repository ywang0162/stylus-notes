package com.stylusnotes.app

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.stylusnotes.app.databinding.ActivityNoteBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoteActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
    }

    private lateinit var binding: ActivityNoteBinding
    private lateinit var repo: NotesRepository
    private lateinit var noteId: String

    private val density get() = resources.displayMetrics.density
    private val widthsDp = floatArrayOf(2f, 4f, 8f)
    private var widthIndex = 1

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { saveNote() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = NotesRepository(this)
        noteId = intent.getStringExtra(EXTRA_NOTE_ID) ?: run {
            finish()
            return
        }

        val dv = binding.drawingView
        dv.strokeWidth = widthsDp[widthIndex] * density
        dv.stylusOnly = false // finger-first by default
        dv.onContentChanged = {
            scheduleAutosave()
            updateButtons()
        }
        dv.onViewportChanged = { updatePageIndicator() }

        val content = repo.loadContent(noteId)
        dv.loadContent(content.strokes, content.pageCount)

        wireToolbar()
        selectTool(DrawingView.Tool.PEN)
        selectColor(Color.BLACK)
        updateButtons()
        updatePageIndicator()
    }

    private fun wireToolbar() = with(binding) {
        btnHome.setOnClickListener { saveNote(); finish() }

        btnPen.setOnClickListener { selectTool(DrawingView.Tool.PEN) }
        btnEraser.setOnClickListener { selectTool(DrawingView.Tool.ERASER) }

        btnColorBlack.setOnClickListener { selectColor(Color.BLACK) }
        btnColorBlue.setOnClickListener { selectColor(Color.parseColor("#1565C0")) }
        btnColorRed.setOnClickListener { selectColor(Color.parseColor("#C62828")) }
        btnColorGreen.setOnClickListener { selectColor(Color.parseColor("#2E7D32")) }

        btnWidth.setOnClickListener { cycleWidth() }
        btnUndo.setOnClickListener { drawingView.undo() }
        btnRedo.setOnClickListener { drawingView.redo() }
        btnClear.setOnClickListener { confirmClear() }
        btnStylus.setOnClickListener { toggleStylusOnly() }
        btnExport.setOnClickListener { exportNote() }
    }

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
        toast("Pen: " + when (widthIndex) { 0 -> "Thin"; 1 -> "Medium"; else -> "Thick" })
    }

    private fun toggleStylusOnly() {
        val on = !binding.drawingView.stylusOnly
        binding.drawingView.stylusOnly = on
        binding.btnStylus.isSelected = on
        toast(if (on) "Stylus only (palm rejection on)" else "Finger drawing on")
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear note?")
            .setMessage("This erases everything in this note.")
            .setPositiveButton("Clear") { _, _ -> binding.drawingView.clearAll() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportNote() {
        val bmp = binding.drawingView.renderFull()
        if (bmp == null) {
            toast("Nothing to export yet")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val uri = repo.exportPng(bmp, "note_$stamp")
        toast(if (uri != null) "Saved to Pictures/StylusNotes" else "Export failed")
    }

    private fun updatePageIndicator() {
        binding.tvPage.text = "p.${binding.drawingView.currentPage()}/${binding.drawingView.pageCount}"
    }

    private fun updateButtons() = with(binding) {
        btnUndo.isEnabled = drawingView.canUndo
        btnRedo.isEnabled = drawingView.canRedo
        listOf<View>(btnUndo, btnRedo).forEach { it.alpha = if (it.isEnabled) 1f else 0.35f }
    }

    private fun scheduleAutosave() {
        mainHandler.removeCallbacks(autosave)
        mainHandler.postDelayed(autosave, 700)
    }

    private fun saveNote() {
        mainHandler.removeCallbacks(autosave)
        val dv = binding.drawingView
        val content = NoteContent(dv.getStrokes().toMutableList(), dv.pageCount)
        val thumb = dv.renderThumbnail((120 * density).toInt())
        repo.saveContent(noteId, content, thumb)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        saveNote()
    }
}
