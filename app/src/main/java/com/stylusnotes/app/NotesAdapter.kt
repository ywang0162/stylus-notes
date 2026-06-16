package com.stylusnotes.app

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.stylusnotes.app.databinding.ItemNoteBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotesAdapter(
    private val thumbProvider: (String) -> File,
    private val onClick: (NoteMeta) -> Unit,
    private val onLongClick: (NoteMeta) -> Unit
) : RecyclerView.Adapter<NotesAdapter.VH>() {

    private var items: List<NoteMeta> = emptyList()
    private val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    fun submit(list: List<NoteMeta>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val binding: ItemNoteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemNoteBinding.inflate(inflater, parent, false))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.title.text = item.title
        holder.binding.date.text = dateFmt.format(Date(item.updatedAt))

        val thumb = thumbProvider(item.id)
        if (thumb.exists()) {
            holder.binding.thumb.setImageBitmap(BitmapFactory.decodeFile(thumb.path))
        } else {
            holder.binding.thumb.setImageDrawable(null)
        }

        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}
