package com.stylusnotes.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.stylusnotes.app.databinding.ActivityHomeBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var repo: NotesRepository
    private lateinit var adapter: NotesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = NotesRepository(this)
        adapter = NotesAdapter(
            thumbProvider = repo::thumbFile,
            onClick = ::openNote,
            onLongClick = ::showNoteMenu
        )

        binding.recycler.layoutManager = GridLayoutManager(this, computeSpanCount())
        binding.recycler.adapter = adapter

        binding.fab.setOnClickListener { createAndOpen() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val notes = repo.listNotes()
        adapter.submit(notes)
        binding.empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun computeSpanCount(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (widthDp / 170f).toInt().coerceAtLeast(2)
    }

    private fun openNote(meta: NoteMeta) {
        startActivity(
            Intent(this, NoteActivity::class.java)
                .putExtra(NoteActivity.EXTRA_NOTE_ID, meta.id)
        )
    }

    private fun createAndOpen() {
        val title = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
        val meta = repo.createNote(title)
        openNote(meta)
    }

    private fun showNoteMenu(meta: NoteMeta) {
        AlertDialog.Builder(this)
            .setTitle(meta.title)
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> renameNote(meta)
                    1 -> deleteNote(meta)
                }
            }
            .show()
    }

    private fun renameNote(meta: NoteMeta) {
        val input = EditText(this).apply {
            setText(meta.title)
            setSelection(meta.title.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename note")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { meta.title }
                repo.renameNote(meta.id, name)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteNote(meta: NoteMeta) {
        AlertDialog.Builder(this)
            .setTitle("Delete note?")
            .setMessage("\"${meta.title}\" will be permanently deleted.")
            .setPositiveButton("Delete") { _, _ ->
                repo.deleteNote(meta.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
