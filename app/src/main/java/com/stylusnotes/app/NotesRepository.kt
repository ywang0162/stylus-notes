package com.stylusnotes.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Metadata for one note (shown on the home screen). */
data class NoteMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** The drawable content of one note. */
class NoteContent(
    val strokes: MutableList<Stroke> = mutableListOf(),
    var pageCount: Int = 1
)

/**
 * Stores each note as its own folder under the app's private storage:
 *
 *   notes/<id>/meta.json      title + timestamps
 *   notes/<id>/content.json   strokes (vector) + page count
 *   notes/<id>/thumb.png      first-page thumbnail for the home grid
 */
class NotesRepository(context: Context) {

    private val appContext = context.applicationContext
    private val notesDir = File(appContext.filesDir, "notes").apply { mkdirs() }

    fun listNotes(): List<NoteMeta> {
        val dirs = notesDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { readMeta(it.name) }
            .sortedByDescending { it.updatedAt }
    }

    fun createNote(title: String): NoteMeta {
        val id = "note_" + UUID.randomUUID().toString().take(8)
        File(notesDir, id).mkdirs()
        val now = System.currentTimeMillis()
        val meta = NoteMeta(id, title, now, now)
        writeMeta(meta)
        saveContent(id, NoteContent(), null)
        return meta
    }

    fun deleteNote(id: String) {
        File(notesDir, id).deleteRecursively()
    }

    fun renameNote(id: String, title: String) {
        val meta = readMeta(id) ?: return
        writeMeta(meta.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    fun thumbFile(id: String): File = File(File(notesDir, id), "thumb.png")

    // ---- Content ------------------------------------------------------------

    fun loadContent(id: String): NoteContent {
        val file = File(File(notesDir, id), "content.json")
        if (!file.exists()) return NoteContent()
        return try {
            val root = JSONObject(file.readText())
            val content = NoteContent(pageCount = root.optInt("pageCount", 1).coerceAtLeast(1))
            val arr = root.optJSONArray("strokes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val stroke = Stroke(o.getInt("c"), o.getDouble("w").toFloat(), o.getBoolean("e"))
                val pts = o.getJSONArray("pts")
                var j = 0
                while (j + 2 < pts.length()) {
                    stroke.addPoint(
                        pts.getDouble(j).toFloat(),
                        pts.getDouble(j + 1).toFloat(),
                        pts.getDouble(j + 2).toFloat()
                    )
                    j += 3
                }
                content.strokes.add(stroke)
            }
            content
        } catch (e: Exception) {
            NoteContent()
        }
    }

    fun saveContent(id: String, content: NoteContent, thumbnail: Bitmap?) {
        val dir = File(notesDir, id).apply { mkdirs() }
        val arr = JSONArray()
        for (s in content.strokes) {
            val pts = JSONArray()
            for (i in 0 until s.size) {
                pts.put(s.xs[i].toDouble())
                pts.put(s.ys[i].toDouble())
                pts.put(s.ps[i].toDouble())
            }
            arr.put(
                JSONObject()
                    .put("c", s.color)
                    .put("w", s.baseWidth.toDouble())
                    .put("e", s.isEraser)
                    .put("pts", pts)
            )
        }
        File(dir, "content.json").writeText(
            JSONObject().put("pageCount", content.pageCount).put("strokes", arr).toString()
        )
        readMeta(id)?.let { writeMeta(it.copy(updatedAt = System.currentTimeMillis())) }
        if (thumbnail != null) {
            try {
                File(dir, "thumb.png").outputStream().use {
                    thumbnail.compress(Bitmap.CompressFormat.PNG, 90, it)
                }
            } catch (e: Exception) {
                // A missing thumbnail is non-fatal; the grid falls back to a placeholder.
            }
        }
    }

    // ---- Meta ---------------------------------------------------------------

    private fun readMeta(id: String): NoteMeta? {
        val file = File(File(notesDir, id), "meta.json")
        if (!file.exists()) return null
        return try {
            val o = JSONObject(file.readText())
            NoteMeta(
                id = o.optString("id", id),
                title = o.optString("title", "Untitled"),
                createdAt = o.optLong("createdAt", 0L),
                updatedAt = o.optLong("updatedAt", 0L)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun writeMeta(meta: NoteMeta) {
        val dir = File(notesDir, meta.id).apply { mkdirs() }
        File(dir, "meta.json").writeText(
            JSONObject()
                .put("id", meta.id)
                .put("title", meta.title)
                .put("createdAt", meta.createdAt)
                .put("updatedAt", meta.updatedAt)
                .toString()
        )
    }

    // ---- Export -------------------------------------------------------------

    /** Writes [bitmap] to Pictures/StylusNotes; returns its Uri or null. */
    fun exportPng(bitmap: Bitmap, displayName: String): Uri? {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StylusNotes")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
