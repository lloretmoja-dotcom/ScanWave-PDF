package com.scanwavepdf.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocumentsAdapter(
    private var items: List<DocumentEntry>,
    private val onShare: (DocumentEntry) -> Unit,
    private val onRename: (DocumentEntry) -> Unit,
    private val onDelete: (DocumentEntry) -> Unit
) : RecyclerView.Adapter<DocumentsAdapter.DocumentViewHolder>() {

    fun updateItems(newItems: List<DocumentEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_document, parent, false)
        return DocumentViewHolder(view)
    }

    override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) {
        val entry = items[position]
        holder.name.text = entry.displayName
        val dateLabel = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(entry.createdAt))
        val pagesLabel = if (entry.pageCount == 1) "1 page" else "${entry.pageCount} pages"
        holder.meta.text = "$pagesLabel · $dateLabel"

        holder.share.setOnClickListener { onShare(entry) }
        holder.rename.setOnClickListener { onRename(entry) }
        holder.delete.setOnClickListener { onDelete(entry) }
    }

    override fun getItemCount(): Int = items.size

    class DocumentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.docName)
        val meta: TextView = view.findViewById(R.id.docMeta)
        val share: TextView = view.findViewById(R.id.docShare)
        val rename: TextView = view.findViewById(R.id.docRename)
        val delete: TextView = view.findViewById(R.id.docDelete)
    }
}
