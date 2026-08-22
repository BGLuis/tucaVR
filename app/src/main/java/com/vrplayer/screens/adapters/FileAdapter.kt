package com.vrplayer.screens.adapters

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.vrplayer.R
import com.vrplayer.designsystem.VoidGridCard
import com.vrplayer.designsystem.VoidListRow
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.filebrowser.FolderPreviewGenerator
import com.vrplayer.filebrowser.MediaEntry
import com.vrplayer.filebrowser.MediaFilterEngine
import com.vrplayer.filebrowser.MediaType
import com.vrplayer.filebrowser.ThumbnailGenerator
import com.vrplayer.filebrowser.ViewMode
import com.vrplayer.screens.formatFileSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Adapter unificado e adaptativo para a listagem de arquivos locais e de rede.
 * Suporta modo Lista (`VoidListRow`) e modo Grade (`VoidGridCard`), com highlight de busca,
 * badges 3D/tipo, mosaico de até 4 miniaturas em pastas, indicativo destacado de pasta e barra de progresso.
 */
class FileAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onUpClick: () -> Unit,
    private val onDirectoryClick: (MediaEntry) -> Unit,
    /** Single-click em vídeo → inicia reprodução direta / retoma. */
    private val onVideoClick: (MediaEntry) -> Unit,
    /** Double-click em vídeo → toca direto. */
    private val onVideoDoubleClick: (MediaEntry) -> Unit = onVideoClick,
    private val thumbnailLoader: (suspend (MediaEntry) -> Bitmap?)? = null,
    private val folderMosaicLoader: (suspend (MediaEntry) -> Bitmap?)? = null
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    private sealed class Row {
        object Up : Row()
        data class Item(val entry: MediaEntry) : Row()
    }

    private var rows: List<Row> = emptyList()
    private var currentViewMode: ViewMode = ViewMode.GRID
    private var currentSearchQuery: String = ""

    companion object {
        private const val VIEW_TYPE_UP = 0
        private const val VIEW_TYPE_LIST_ITEM = 1
        private const val VIEW_TYPE_GRID_ITEM = 2
    }

    fun submit(
        entries: List<MediaEntry>,
        showUp: Boolean,
        viewMode: ViewMode = currentViewMode,
        searchQuery: String = ""
    ) {
        currentViewMode = viewMode
        currentSearchQuery = searchQuery

        rows = buildList {
            if (showUp) add(Row.Up)
            entries.forEach { add(Row.Item(it)) }
        }
        notifyDataSetChanged()
    }

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        var thumbnailJob: Job? = null
        var boundEntry: MediaEntry? = null
        val gestureDetector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
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

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is Row.Up -> VIEW_TYPE_UP
            is Row.Item -> if (currentViewMode == ViewMode.LIST) VIEW_TYPE_LIST_ITEM else VIEW_TYPE_GRID_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view: View = when (viewType) {
            VIEW_TYPE_UP, VIEW_TYPE_LIST_ITEM -> {
                VoidListRow(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = VoidTheme.dpToPx(parent.context, 8f) }
                }
            }
            VIEW_TYPE_GRID_ITEM -> {
                VoidGridCard(parent.context).apply {
                    val cardMargin = VoidTheme.dpToPx(parent.context, 6f)
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(cardMargin, cardMargin, cardMargin, cardMargin)
                    }
                }
            }
            else -> VoidListRow(parent.context)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.thumbnailJob?.cancel()
        holder.boundEntry = null
        holder.itemView.setOnTouchListener(null)
        holder.itemView.setOnClickListener(null)

        when (val row = rows[position]) {
            is Row.Up -> bindUpRow(holder)
            is Row.Item -> bindItemRow(holder, row.entry, position)
        }
    }

    private fun bindUpRow(holder: ViewHolder) {
        if (holder.view is VoidListRow) {
            holder.view.thumbnail.setImageBitmap(null)
            holder.view.bind(
                context.getString(R.string.browser_row_up).trim(),
                showThumbnailSlot = false,
                iconResId = R.drawable.ic_arrow_up
            )
        }
        holder.itemView.setOnClickListener { onUpClick() }
    }

    private fun bindItemRow(holder: ViewHolder, entry: MediaEntry, position: Int) {
        val isDir = entry.type == MediaType.DIRECTORY
        val isVideo = entry.type == MediaType.VIDEO

        // Highlight no nome se houver busca ativa
        val displayName = buildHighlightedText(entry.name, currentSearchQuery)

        val iconRes = when (entry.type) {
            MediaType.DIRECTORY -> R.drawable.ic_folder
            MediaType.VIDEO -> R.drawable.ic_movie
            MediaType.AUDIO -> R.drawable.ic_audio
            MediaType.IMAGE -> R.drawable.ic_image
        }

        val meta = when {
            isDir -> entry.itemCount?.let { context.getString(R.string.browser_folder_summary_format, "", it).removePrefix(" · ") }
            isVideo -> formatFileSize(context, entry.sizeBytes)
            entry.sizeBytes > 0 -> formatFileSize(context, entry.sizeBytes)
            else -> null
        }

        if (holder.view is VoidListRow) {
            holder.view.thumbnail.setImageBitmap(null)
            holder.view.bind(
                title = displayName.toString(),
                meta = meta,
                showThumbnailSlot = isVideo || isDir,
                iconResId = iconRes
            )
            holder.view.titleView.text = displayName
        } else if (holder.view is VoidGridCard) {
            holder.view.thumbnail.setImageBitmap(null)
            holder.view.bind(
                title = displayName,
                meta = meta,
                format3D = entry.format3DHint,
                iconResId = iconRes,
                isFolder = isDir,
                progressFraction = entry.progressFraction
            )
        }

        // Configuração de cliques e carregamento assíncrono de miniaturas
        if (isDir) {
            holder.itemView.setOnClickListener { onDirectoryClick(entry) }

            // Carregamento de mosaico e estatísticas da pasta em segundo plano
            holder.thumbnailJob = scope.launch {
                val summary = FolderPreviewGenerator.getSummary(entry.path)
                if (summary != null && holder.adapterPosition == position) {
                    val summaryMeta = if (summary.videoCount > 0) {
                        "${summary.totalItems} itens · ${summary.videoCount} vídeos"
                    } else {
                        "${summary.totalItems} itens"
                    }

                    if (holder.view is VoidListRow) {
                        holder.view.metaView.text = summaryMeta
                        holder.view.metaView.visibility = View.VISIBLE
                    } else if (holder.view is VoidGridCard) {
                        holder.view.metaView.text = summaryMeta
                        holder.view.metaView.visibility = View.VISIBLE
                    }
                }

                val folderMosaic = if (folderMosaicLoader != null) {
                    folderMosaicLoader.invoke(entry)
                } else {
                    FolderPreviewGenerator.getFolderMosaic(context, entry.path, thumbnailLoader)
                }

                if (folderMosaic != null && holder.adapterPosition == position) {
                    if (holder.view is VoidListRow) {
                        holder.view.thumbnail.setImageBitmap(folderMosaic)
                        holder.view.thumbnail.visibility = View.VISIBLE
                    } else if (holder.view is VoidGridCard) {
                        holder.view.thumbnail.setImageBitmap(folderMosaic)
                    }
                }
            }
        } else if (isVideo) {
            holder.boundEntry = entry
            holder.itemView.setOnTouchListener { _, event ->
                holder.gestureDetector.onTouchEvent(event)
                true
            }

            // Carregamento de miniatura do vídeo
            holder.thumbnailJob = scope.launch {
                val bitmap = if (thumbnailLoader != null) {
                    thumbnailLoader.invoke(entry)
                } else {
                    ThumbnailGenerator.getThumbnail(context, entry)
                }

                if (bitmap != null && holder.adapterPosition == position) {
                    if (holder.view is VoidListRow) {
                        holder.view.thumbnail.setImageBitmap(bitmap)
                        holder.view.thumbnail.visibility = View.VISIBLE
                    } else if (holder.view is VoidGridCard) {
                        holder.view.thumbnail.setImageBitmap(bitmap)
                    }
                }
            }
        } else {
            // Áudio ou Imagem
            holder.itemView.setOnClickListener { onVideoClick(entry) }
        }
    }

    private fun buildHighlightedText(text: String, query: String): CharSequence {
        if (query.isBlank()) return text
        val ranges = MediaFilterEngine.findHighlightRanges(text, query)
        if (ranges.isEmpty()) return text

        val spannable = SpannableString(text)
        val accentColor = android.graphics.Color.parseColor("#E5A93C")
        ranges.forEach { range ->
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(0, text.length)
            if (start < end) {
                spannable.setSpan(ForegroundColorSpan(accentColor), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return spannable
    }

    override fun getItemCount(): Int = rows.size
}
