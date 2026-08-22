package com.vrplayer.screens.adapters

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.vrplayer.R
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidListRow
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.history.PlaybackHistory
import com.vrplayer.history.HistorySourceType
import com.vrplayer.history.formatDurationMs
import com.vrplayer.history.watchedPercent

/**
 * Adapter de lista para a tela "Continuar Assistindo" (T9.4).
 *
 * Reusa [VoidListRow] + botão "✕" de remover, no mesmo padrão da lista de
 * servidores SMB salvos. Thumbnails não são exibidos — o campo existe no
 * Room para uso futuro, mas fica null por enquanto.
 *
 * Extraído de VRPresentation (inner class HistoryAdapter) para isolar
 * responsabilidade de apresentação de histórico.
 */
class HistoryAdapter(
    private val onItemClick: (PlaybackHistory) -> Unit,
    private val onRemoveClick: (PlaybackHistory) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var items: List<PlaybackHistory> = emptyList()

    fun submit(newItems: List<PlaybackHistory>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class ViewHolder(row: LinearLayout, val listRow: VoidListRow) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context

        val listRow = VoidListRow(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnRemove = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = ""
            setIcon(R.drawable.icon_x)
            textSize = 16f
            minHeight = 0
            val pad = VoidTheme.dpToPx(context, 12f)
            setPadding(pad, pad, pad, pad)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
            addView(listRow)
            addView(btnRemove, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = VoidTheme.dpToPx(context, 8f) })
        }

        val holder = ViewHolder(row, listRow)
        btnRemove.setOnClickListener {
            items.getOrNull(holder.adapterPosition)?.let(onRemoveClick)
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context = holder.itemView.context
        val entry = items[position]

        val titleWithIcon = when (entry.sourceType) {
            HistorySourceType.LOCAL -> context.getString(R.string.common_row_video_format, entry.title).trim()
            HistorySourceType.SMB   -> context.getString(R.string.network_smb_row_label_format, entry.title).trim()
            HistorySourceType.HTTP  -> context.getString(R.string.history_row_http_format, entry.title).trim()
            HistorySourceType.FTP   -> context.getString(R.string.history_row_ftp_format, entry.title).trim()
            HistorySourceType.SFTP  -> context.getString(R.string.history_row_sftp_format, entry.title).trim()
            HistorySourceType.NFS   -> context.getString(R.string.network_nfs_row_label_format, entry.title).trim()
        }
        val iconRes = when (entry.sourceType) {
            HistorySourceType.LOCAL -> R.drawable.ic_movie
            HistorySourceType.SMB   -> R.drawable.ic_storage
            HistorySourceType.HTTP  -> R.drawable.ic_link
            HistorySourceType.FTP   -> R.drawable.ic_broadcast
            HistorySourceType.SFTP  -> R.drawable.ic_lock
            HistorySourceType.NFS   -> R.drawable.ic_storage
        }
        val meta = context.getString(
            R.string.history_row_meta_format,
            formatDurationMs(entry.positionMs),
            formatDurationMs(entry.durationMs),
            watchedPercent(entry)
        )
        holder.listRow.bind(titleWithIcon, meta = meta, showThumbnailSlot = false, iconResId = iconRes)
        holder.listRow.setOnClickListener { onItemClick(entry) }
    }

    override fun getItemCount() = items.size
}
