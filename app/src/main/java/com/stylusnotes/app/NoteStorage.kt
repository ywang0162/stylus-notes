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

/**
 * Persists the notebook as a folder of one-JSON-file-per-page under the app's
 * private storage, and exports rendered pages as PNGs into the shared gallery.
 *
 * Strokes are stored as vectors (not pixels) so pages stay small and could be
 * re-rendered at any resolution later.
 */
class NoteStorage(context: Context) {

    private val appContext = context.applicationContext
    private val pagesDir = File(appContext.filesDir, "pages").apply { mkdirs() }

    /** All page files, ordered. Guarantees at least one page exists. */
    fun listPages(): List<File> {
        val files = pagesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (files.isEmpty()) {
            return listOf(createPage())
        }
        return files
    }

    /** Creates a new, empty page file with the next sequential name. */
    fun createPage(): File {
        var n = 1
        val existing = pagesDir.listFiles()?.map { it.name } ?: emptyList()
        while (existing.contains(pageName(n))) n++
        val file = File(pagesDir, pageName(n))
        file.writeText(JSONObject().put("strokes", JSONArray()).toString())
        return file
    }

    fun deletePage(file: File) {
        file.delete()
    }

    private fun pageName(n: Int) = "page_%04d.json".format(n)

    fun savePage(file: File, strokes: List<Stroke>) {
        val arr = JSONArray()
        for (s in strokes) {
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
        file.writeText(JSONObject().put("strokes", arr).toString())
    }

    fun loadPage(file: File): List<Stroke> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("strokes") ?: return emptyList()
            val result = ArrayList<Stroke>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val stroke = Stroke(
                    color = o.getInt("c"),
                    baseWidth = o.getDouble("w").toFloat(),
                    isEraser = o.getBoolean("e")
                )
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
                result.add(stroke)
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Writes [bitmap] to Pictures/StylusNotes in the shared gallery.
     * Returns the new image Uri, or null on failure.
     */
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
