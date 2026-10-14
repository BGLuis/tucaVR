package com.tucavr.screens.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.VRControlsPresentation
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidTheme
import com.tucavr.filebrowser.MediaEntry
import com.tucavr.filebrowser.MediaType
import com.tucavr.filebrowser.NetworkThumbnailGenerator
import com.tucavr.filebrowser.ThumbnailGenerator
import com.tucavr.navigation.PlaybackSource
import com.tucavr.playlist.PlaylistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Painel direito do layout em 3 zonas (zona 3, 432 px).
 * Exibe o card "Now Playing" quando há vídeo em reprodução com miniatura,
 * progresso, botão "Back to video" e fila a seguir.
 */
@SuppressLint("ViewConstructor")
class ContextRailView(
    context: Context,
    private val activity: VRActivity,
    private val scope: CoroutineScope,
    private val onBackToVideo: (PlaybackSource) -> Unit,
) : LinearLayout(context) {
    private val contentLayout: LinearLayout
    private var progressLabel: TextView? = null
    private var progressBar: ProgressBar? = null
    private var lastLoadedSourceKey: String? = null

    init {
        orientation = HORIZONTAL
        layoutParams =
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        setBackgroundColor(VoidTheme.colorSurface)

        // Borda divisória à esquerda
        val leftBorder =
            View(context).apply {
                layoutParams = LayoutParams(VoidTheme.dpToPx(context, 1f), ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(VoidTheme.colorBorder)
            }
        addView(leftBorder)

        val scroll =
            ScrollView(context).apply {
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                isFillViewport = true
            }

        contentLayout =
            LinearLayout(context).apply {
                orientation = VERTICAL
                val pad = VoidTheme.dpToPx(context, 16f)
                setPadding(pad, pad, pad, pad)
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }

        scroll.addView(contentLayout)
        addView(scroll)

        updateState()
    }

    fun updateState() {
        contentLayout.removeAllViews()
        progressLabel = null
        progressBar = null

        val currentSource = activity.currentPlaybackSource
        if (currentSource != null) {
            buildActivePlaybackCard(currentSource)
        } else {
            buildIdleStateCard()
        }
    }

    fun updateProgress(
        currentSec: Float,
        totalSec: Float,
    ) {
        val label = progressLabel
        val bar = progressBar
        if (label != null && bar != null) {
            val formatted =
                VRControlsPresentation.formatPlaybackTime(currentSec, totalSec) +
                    " / " + VRControlsPresentation.formatPlaybackTime(totalSec, totalSec)
            label.text = formatted
            if (totalSec > 0f) {
                bar.progress = ((currentSec / totalSec) * 1000).toInt().coerceIn(0, 1000)
            }
        }
    }

    private fun buildActivePlaybackCard(source: PlaybackSource) {
        val cornerRadius = VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
        val strokeWidth = VoidTheme.dpToPx(context, VoidTheme.borderWidthDp)

        val card =
            LinearLayout(context).apply {
                orientation = VERTICAL
                val pad = VoidTheme.dpToPx(context, 16f)
                setPadding(pad, pad, pad, pad)
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        this.cornerRadius = cornerRadius
                        setStroke(strokeWidth, VoidTheme.colorBorder)
                    }
                layoutParams =
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }

        // Título da seção
        val sectionTitle =
            TextView(context).apply {
                text = context.getString(R.string.context_rail_now_playing)
                typeface = VoidTheme.typefaceTitle
                textSize = 18f
                setTextColor(VoidTheme.colorAccent)
                val bottomPad = VoidTheme.dpToPx(context, 12f)
                setPadding(0, 0, 0, bottomPad)
            }
        card.addView(sectionTitle)

        // Miniatura
        val thumbFrame =
            FrameLayout(context).apply {
                layoutParams =
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        VoidTheme.dpToPx(context, 180f),
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 12f)
                    }
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorBackground)
                        this.cornerRadius = cornerRadius
                    }
                clipToOutline = true
            }

        val thumbView =
            ImageView(context).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.ic_movie)
                setColorFilter(VoidTheme.colorTextSecondary)
            }
        thumbFrame.addView(thumbView)
        card.addView(thumbFrame)

        loadThumbnail(source, thumbView)

        // Título do vídeo
        val videoTitle =
            TextView(context).apply {
                text = activity.resolveSourceTitle(source)
                typeface = VoidTheme.typefaceBody
                textSize = 16f
                setTextColor(VoidTheme.colorText)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                val bottomPad = VoidTheme.dpToPx(context, 10f)
                setPadding(0, 0, 0, bottomPad)
            }
        card.addView(videoTitle)

        // Linha de tempo
        val curSec = activity.lastMediaProgressCurrent
        val totSec = activity.lastMediaProgressTotal
        val timeText =
            VRControlsPresentation.formatPlaybackTime(curSec, totSec) +
                " / " + VRControlsPresentation.formatPlaybackTime(totSec, totSec)

        val timeLabel =
            TextView(context).apply {
                text = timeText
                typeface = VoidTheme.typefaceMono
                textSize = 13f
                setTextColor(VoidTheme.colorTextSecondary)
                val bottomPad = VoidTheme.dpToPx(context, 6f)
                setPadding(0, 0, 0, bottomPad)
            }
        progressLabel = timeLabel
        card.addView(timeLabel)

        // Barra de progresso
        val pBar =
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = if (totSec > 0f) ((curSec / totSec) * 1000).toInt().coerceIn(0, 1000) else 0
                progressTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
                progressBackgroundTintList = ColorStateList.valueOf(VoidTheme.colorSurface)
                layoutParams =
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        VoidTheme.dpToPx(context, 6f),
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 16f)
                    }
            }
        progressBar = pBar
        card.addView(pBar)

        // Botão "Back to video"
        val btnBack =
            VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                text = context.getString(R.string.context_rail_back_to_video)
                setIcon(R.drawable.ic_play_arrow)
                textSize = 15f
                minHeight = VoidTheme.dpToPx(context, 48f)
                setOnClickListener {
                    onBackToVideo(source)
                }
                layoutParams =
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }
        card.addView(btnBack)

        contentLayout.addView(card)

        // Fila / Up Next
        buildQueueSection()
    }

    private fun buildQueueSection() {
        val queueTitle =
            TextView(context).apply {
                text = context.getString(R.string.context_rail_queue_title)
                typeface = VoidTheme.typefaceTitle
                textSize = 16f
                setTextColor(VoidTheme.colorText)
                val topPad = VoidTheme.dpToPx(context, 20f)
                val bottomPad = VoidTheme.dpToPx(context, 8f)
                setPadding(0, topPad, 0, bottomPad)
            }
        contentLayout.addView(queueTitle)

        val queueManager = activity.playlistQueueManager
        val items = queueManager.getItems()

        if (items.isEmpty()) {
            val emptyText =
                TextView(context).apply {
                    text = context.getString(R.string.context_rail_empty_queue)
                    typeface = VoidTheme.typefaceBody
                    textSize = 14f
                    setTextColor(VoidTheme.colorTextSecondary)
                    val pad = VoidTheme.dpToPx(context, 8f)
                    setPadding(0, pad, 0, pad)
                }
            contentLayout.addView(emptyText)
        } else {
            val currentIdx = queueManager.getCurrentIndex()
            for ((idx, item) in items.withIndex()) {
                val isCurrent = idx == currentIdx
                val row = buildQueueItemRow(item, idx, isCurrent)
                contentLayout.addView(row)
            }
        }
    }

    private fun buildQueueItemRow(
        item: PlaylistItem,
        index: Int,
        isCurrent: Boolean,
    ): View {
        val row =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = VoidTheme.dpToPx(context, 8f)
                setPadding(pad, pad, pad, pad)
                val cornerRadius = VoidTheme.dp(context, 8f)
                background =
                    GradientDrawable().apply {
                        setColor(if (isCurrent) VoidTheme.colorSurfaceAlt else Color.TRANSPARENT)
                        this.cornerRadius = cornerRadius
                    }
                layoutParams =
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 4f)
                    }
                setOnClickListener {
                    activity.playlistQueueManager.skipTo(index)
                }
            }

        val indexLabel =
            TextView(context).apply {
                text = String.format(java.util.Locale.getDefault(), "%d", index + 1)
                typeface = VoidTheme.typefaceMono
                textSize = 13f
                setTextColor(if (isCurrent) VoidTheme.colorAccent else VoidTheme.colorTextSecondary)
                val rPad = VoidTheme.dpToPx(context, 10f)
                setPadding(0, 0, rPad, 0)
            }
        row.addView(indexLabel)

        val nameLabel =
            TextView(context).apply {
                text = item.title
                typeface = VoidTheme.typefaceBody
                textSize = 14f
                setTextColor(if (isCurrent) VoidTheme.colorAccent else VoidTheme.colorText)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        row.addView(nameLabel)

        return row
    }

    private fun buildIdleStateCard() {
        val cornerRadius = VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
        val strokeWidth = VoidTheme.dpToPx(context, VoidTheme.borderWidthDp)

        val card =
            LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = VoidTheme.dpToPx(context, 24f)
                setPadding(pad, pad, pad, pad)
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        this.cornerRadius = cornerRadius
                        setStroke(strokeWidth, VoidTheme.colorBorder)
                    }
                layoutParams =
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = VoidTheme.dpToPx(context, 16f)
                    }
            }

        val icon =
            ImageView(context).apply {
                setImageResource(R.drawable.ic_movie)
                val size = VoidTheme.dpToPx(context, 44f)
                layoutParams =
                    LayoutParams(size, size).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 12f)
                    }
                setColorFilter(VoidTheme.colorTextSecondary)
            }
        card.addView(icon)

        val title =
            TextView(context).apply {
                text = context.getString(R.string.context_rail_no_video)
                typeface = VoidTheme.typefaceBody
                textSize = 15f
                setTextColor(VoidTheme.colorTextSecondary)
                gravity = Gravity.CENTER
            }
        card.addView(title)

        contentLayout.addView(card)
    }

    private fun loadThumbnail(
        source: PlaybackSource,
        imageView: ImageView,
    ) {
        val key =
            when (source) {
                is PlaybackSource.LocalFile -> source.path
                is PlaybackSource.Http -> source.url
                is PlaybackSource.Smb -> "${source.server.host}:${source.path}"
                is PlaybackSource.Ftp -> "${source.server.host}:${source.path}"
                is PlaybackSource.Sftp -> "${source.server.host}:${source.path}"
                is PlaybackSource.Nfs -> "${source.server.host}:${source.path}"
                is PlaybackSource.Dlna -> source.url
                is PlaybackSource.Webdav -> "${source.server.host}:${source.path}"
            }
        lastLoadedSourceKey = key

        scope.launch {
            val bitmap: Bitmap? =
                withContext(Dispatchers.IO) {
                    try {
                        when (source) {
                            is PlaybackSource.LocalFile -> {
                                val file = File(source.path)
                                if (file.exists()) {
                                    val size = if (source.sizeBytes > 0L) source.sizeBytes else file.length()
                                    val entry =
                                        MediaEntry(
                                            name = file.name,
                                            path = file.absolutePath,
                                            sizeBytes = size,
                                            lastModified = file.lastModified(),
                                            type = MediaType.VIDEO,
                                        )
                                    ThumbnailGenerator.getThumbnail(context, entry)
                                } else {
                                    null
                                }
                            }
                            is PlaybackSource.Smb,
                            is PlaybackSource.Ftp,
                            is PlaybackSource.Sftp,
                            -> {
                                NetworkThumbnailGenerator.getThumbnail(context, activity, source)
                            }
                            else -> null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

            if (bitmap != null && lastLoadedSourceKey == key) {
                imageView.clearColorFilter()
                imageView.setImageBitmap(bitmap)
            }
        }
    }
}
