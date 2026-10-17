package com.tucavr.screens.adapters

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidListRow
import com.tucavr.designsystem.VoidTheme
import com.tucavr.filebrowser.MediaEntry
import com.tucavr.filebrowser.MediaType
import com.tucavr.filebrowser.NetworkThumbnailGenerator
import com.tucavr.filebrowser.ThumbnailGenerator
import com.tucavr.history.HistorySourceType
import com.tucavr.history.PlaybackHistory
import com.tucavr.history.formatDurationMs
import com.tucavr.history.watchedPercent
import com.tucavr.navigation.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Adapter de lista para a tela "Continuar Assistindo" (T9.4 / #143).
 *
 * Reusa [VoidListRow] + botão "✕" de remover, com miniaturas carregadas
 * assincronamente via [ThumbnailGenerator] (local) ou [NetworkThumbnailGenerator] (rede).
 */
class HistoryAdapter(
    private val activity: VRActivity? = null,
    private val scope: CoroutineScope? = null,
    private val onItemClick: (PlaybackHistory) -> Unit,
    private val onRemoveClick: (PlaybackHistory) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
    private var items: List<PlaybackHistory> = emptyList()

    fun submit(newItems: List<PlaybackHistory>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class ViewHolder(row: LinearLayout, val listRow: VoidListRow) : RecyclerView.ViewHolder(row) {
        var thumbnailJob: Job? = null
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val context = parent.context

        val listRow =
            VoidListRow(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        val btnRemove =
            VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = ""
                setIcon(R.drawable.icon_x)
                textSize = 16f
                minHeight = 0
                val pad = VoidTheme.dpToPx(context, 12f)
                setPadding(pad, pad, pad, pad)
            }
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
                addView(listRow)
                addView(
                    btnRemove,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).also { it.marginStart = VoidTheme.dpToPx(context, 8f) },
                )
            }

        val holder = ViewHolder(row, listRow)
        btnRemove.setOnClickListener {
            items.getOrNull(holder.adapterPosition)?.let(onRemoveClick)
        }
        return holder
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val context = holder.itemView.context
        val entry = items[position]

        val titleWithIcon =
            when (entry.sourceType) {
                HistorySourceType.LOCAL -> context.getString(R.string.common_row_video_format, entry.title).trim()
                HistorySourceType.SMB -> context.getString(R.string.network_smb_row_label_format, entry.title).trim()
                HistorySourceType.HTTP -> context.getString(R.string.history_row_http_format, entry.title).trim()
                HistorySourceType.FTP -> context.getString(R.string.history_row_ftp_format, entry.title).trim()
                HistorySourceType.SFTP -> context.getString(R.string.history_row_sftp_format, entry.title).trim()
                HistorySourceType.NFS -> context.getString(R.string.network_nfs_row_label_format, entry.title).trim()
                HistorySourceType.DLNA -> entry.title
                HistorySourceType.WEBDAV -> context.getString(R.string.network_webdav_row_label_format, entry.title).trim()
            }
        val iconRes =
            when (entry.sourceType) {
                HistorySourceType.LOCAL -> R.drawable.ic_movie
                HistorySourceType.SMB -> R.drawable.ic_storage
                HistorySourceType.HTTP -> R.drawable.ic_link
                HistorySourceType.FTP -> R.drawable.ic_broadcast
                HistorySourceType.SFTP -> R.drawable.ic_lock
                HistorySourceType.NFS -> R.drawable.ic_storage
                HistorySourceType.DLNA -> R.drawable.ic_movie
                HistorySourceType.WEBDAV -> R.drawable.ic_link
            }
        val meta =
            context.getString(
                R.string.history_row_meta_format,
                formatDurationMs(entry.positionMs),
                formatDurationMs(entry.durationMs),
                watchedPercent(entry),
            )
        holder.thumbnailJob?.cancel()
        holder.listRow.thumbnail.setImageBitmap(null)
        holder.listRow.thumbnail.visibility = View.GONE

        holder.listRow.bind(titleWithIcon, meta = meta, showThumbnailSlot = true, iconResId = iconRes)
        holder.listRow.setOnClickListener { onItemClick(entry) }

        if (scope != null && activity != null) {
            holder.thumbnailJob =
                scope.launch {
                    val bitmap: Bitmap? =
                        withContext(Dispatchers.IO) {
                            try {
                                when (entry.sourceType) {
                                    HistorySourceType.LOCAL -> {
                                        val file = File(entry.mediaPath)
                                        val mediaEntry =
                                            MediaEntry(
                                                name = entry.title,
                                                path = entry.mediaPath,
                                                sizeBytes = if (file.exists()) file.length() else 0L,
                                                lastModified = if (file.exists()) file.lastModified() else 0L,
                                                type = MediaType.VIDEO,
                                            )
                                        ThumbnailGenerator.getThumbnail(context, mediaEntry)
                                    }
                                    HistorySourceType.SMB,
                                    HistorySourceType.FTP,
                                    HistorySourceType.SFTP,
                                    HistorySourceType.NFS,
                                    HistorySourceType.WEBDAV,
                                    -> {
                                        val source = resolvePlaybackSource(context, entry)
                                        if (source != null) {
                                            NetworkThumbnailGenerator.getThumbnail(context, activity, source)
                                        } else {
                                            null
                                        }
                                    }
                                    HistorySourceType.HTTP,
                                    HistorySourceType.DLNA,
                                    -> null
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }
                    if (bitmap != null && holder.adapterPosition == position) {
                        holder.listRow.thumbnail.clearColorFilter()
                        holder.listRow.thumbnail.setImageBitmap(bitmap)
                        holder.listRow.thumbnail.visibility = View.VISIBLE
                    }
                }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.thumbnailJob?.cancel()
        holder.listRow.thumbnail.setImageBitmap(null)
        holder.listRow.thumbnail.visibility = View.GONE
    }

    override fun getItemCount() = items.size

    private suspend fun resolvePlaybackSource(
        context: android.content.Context,
        entry: PlaybackHistory,
    ): PlaybackSource? {
        val jsonString = entry.serverInfo ?: return null
        return try {
            val json = org.json.JSONObject(jsonString)
            val serverId = json.optString("serverId")
            when (entry.sourceType) {
                HistorySourceType.LOCAL -> PlaybackSource.LocalFile(entry.mediaPath)
                HistorySourceType.SMB -> {
                    val server =
                        com.tucavr.network.SmbCredentialStore(context).list().find { it.id == serverId }
                            ?: com.tucavr.network.SmbServer(
                                id = serverId,
                                name = json.optString("name", entry.title),
                                host = json.optString("host"),
                                port = json.optInt("port", 445),
                                share = json.optString("share"),
                                username = "",
                                password = "",
                                domain = json.optString("domain"),
                            )
                    PlaybackSource.Smb(server, entry.mediaPath)
                }
                HistorySourceType.FTP -> {
                    val server =
                        com.tucavr.network.FtpCredentialStore(context).list().find { it.id == serverId }
                            ?: com.tucavr.network.FtpServer(
                                id = serverId,
                                name = json.optString("name", entry.title),
                                host = json.optString("host"),
                                port = json.optInt("port", 21),
                                username = "",
                                password = "",
                            )
                    PlaybackSource.Ftp(server, entry.mediaPath)
                }
                HistorySourceType.SFTP -> {
                    val server =
                        com.tucavr.network.SftpCredentialStore(context).list().find { it.id == serverId }
                            ?: com.tucavr.network.SftpServer(
                                id = serverId,
                                name = json.optString("name", entry.title),
                                host = json.optString("host"),
                                port = json.optInt("port", 22),
                                username = "",
                                password = "",
                                privateKey = null,
                            )
                    PlaybackSource.Sftp(server, entry.mediaPath)
                }
                HistorySourceType.NFS -> {
                    val server =
                        com.tucavr.history.AppDatabase.getInstance(context).savedServerDao().getById(serverId)
                            ?: com.tucavr.network.SavedServer(
                                id = serverId,
                                name = json.optString("name", entry.title),
                                protocol = com.tucavr.network.ServerProtocol.NFS,
                                host = json.optString("host"),
                                port = json.optInt("port", 2049),
                                path = json.optString("exportPath"),
                            )
                    PlaybackSource.Nfs(server, entry.mediaPath)
                }
                HistorySourceType.WEBDAV -> {
                    val server =
                        com.tucavr.history.AppDatabase.getInstance(context).savedServerDao().getById(serverId)
                            ?: com.tucavr.network.SavedServer(
                                id = serverId,
                                name = json.optString("name", entry.title),
                                protocol = com.tucavr.network.ServerProtocol.WEBDAV,
                                host = json.optString("host"),
                                port = json.optInt("port", 80),
                                path = json.optString("basePath"),
                                extraJson = if (json.has("extraJson")) json.optString("extraJson") else null,
                            )
                    PlaybackSource.Webdav(server, entry.mediaPath)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
