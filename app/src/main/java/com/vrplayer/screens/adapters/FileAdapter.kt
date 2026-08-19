package com.vrplayer.screens.adapters

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.vrplayer.R
import com.vrplayer.designsystem.VoidListRow
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.filebrowser.MediaEntry
import com.vrplayer.filebrowser.MediaType
import com.vrplayer.filebrowser.ThumbnailGenerator
import com.vrplayer.history.formatDurationMs
import com.vrplayer.screens.formatFileSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Adapter de lista para a tela de Arquivos Locais (T5.3).
 *
 * Usa [MediaEntry] em vez de `java.io.File` cru. Carrega thumbnails de forma
 * lazy/assíncrona por item visível — nunca gera para a pasta inteira de uma
 * vez (travaria a UI, ver cuidados do T5).
 *
 * Distingue single-tap (abre detalhe) de double-tap (toca direto) via
 * [GestureDetector] — um único setOnClickListener não consegue esperar pelo
 * segundo toque.
 *
 * Extraído de VRPresentation (static inner class FileAdapter) para isolar
 * responsabilidade de apresentação de arquivos locais.
 */
class FileAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onUpClick: () -> Unit,
    private val onDirectoryClick: (File) -> Unit,
    /** Single-click em vídeo → abre detalhe (thumbnail + metadados). */
    private val onVideoClick: (MediaEntry) -> Unit,
    /** Double-click em vídeo → toca direto, sem passar pelo detalhe. */
    private val onVideoDoubleClick: (MediaEntry) -> Unit
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    private sealed class Row {
        object Up : Row()
        data class Item(val entry: MediaEntry) : Row()
    }

    private var rows: List<Row> = emptyList()

    fun submit(entries: List<MediaEntry>, showUp: Boolean) {
        rows = buildList {
            if (showUp) add(Row.Up)
            entries.forEach { add(Row.Item(it)) }
        }
        notifyDataSetChanged()
    }

    inner class ViewHolder(val row: VoidListRow) : RecyclerView.ViewHolder(row) {
        var thumbnailJob: Job? = null
        // Entrada vinculada ao holder — lida pelo GestureDetector no momento
        // do toque, sempre refletindo o item mais recente após reciclagem.
        var boundEntry: MediaEntry? = null
        val gestureDetector = GestureDetector(row.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                boundEntry?.let(onVideoClick)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                boundEntry?.let(onVideoDoubleClick)
                return true
            }
        })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val row = VoidListRow(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = VoidTheme.dpToPx(parent.context, 8f) }
        }
        return ViewHolder(row)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.thumbnailJob?.cancel()
        holder.row.thumbnail.setImageBitmap(null)
        holder.boundEntry = null
        holder.itemView.setOnTouchListener(null)

        when (val row = rows[position]) {
            is Row.Up -> {
                holder.row.bind(context.getString(R.string.browser_row_up).trim(), showThumbnailSlot = false, iconResId = R.drawable.ic_arrow_up)
                holder.itemView.setOnClickListener { onUpClick() }
            }
            is Row.Item -> {
                val entry = row.entry
                val label = if (entry.type == MediaType.DIRECTORY) {
                    context.getString(R.string.common_row_dir_format, entry.name).trim()
                } else {
                    context.getString(R.string.common_row_video_format, entry.name).trim()
                }
                val iconRes = if (entry.type == MediaType.DIRECTORY) R.drawable.ic_folder else R.drawable.ic_movie
                val meta = if (entry.type == MediaType.VIDEO) formatFileSize(context, entry.sizeBytes) else null
                holder.row.bind(label, meta = meta, showThumbnailSlot = entry.type == MediaType.VIDEO, iconResId = iconRes)

                if (entry.type == MediaType.DIRECTORY) {
                    holder.itemView.setOnClickListener { onDirectoryClick(File(entry.path)) }
                } else {
                    // Vídeo: single-tap (detalhe) vs. double-tap (toca direto)
                    // distinguidos via GestureDetector.
                    holder.itemView.setOnClickListener(null)
                    holder.boundEntry = entry
                    holder.itemView.setOnTouchListener { _, event ->
                        holder.gestureDetector.onTouchEvent(event)
                        true
                    }
                }

                if (entry.type == MediaType.VIDEO) {
                    holder.thumbnailJob = scope.launch {
                        val bitmap = ThumbnailGenerator.getThumbnail(context, entry)
                        if (bitmap != null && holder.adapterPosition == position) {
                            holder.row.thumbnail.setImageBitmap(bitmap)
                            holder.row.thumbnail.visibility = android.view.View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    override fun getItemCount() = rows.size
}
