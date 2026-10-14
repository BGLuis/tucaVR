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
import com.tucavr.filebrowser.Format3DType
import com.tucavr.filebrowser.MediaEntry
import com.tucavr.filebrowser.MediaMetadataReader
import com.tucavr.filebrowser.MediaType
import com.tucavr.filebrowser.NetworkThumbnailGenerator
import com.tucavr.filebrowser.ThumbnailGenerator
import com.tucavr.history.formatDurationMs
import com.tucavr.history.isResumable
import com.tucavr.navigation.PlaybackSource
import com.tucavr.playlist.PlaylistItem
import com.tucavr.screens.formatFileSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Painel direito do layout em 3 zonas (zona 3, 432 px).
 * Exibe o card "Now Playing" quando há vídeo em reprodução com miniatura,
 * progresso, botão "Back to video" e fila a seguir, ou o card de detalhes
 * do arquivo selecionado com atalhos de reprodução.
 */
@SuppressLint("ViewConstructor")
class ContextRailView(
    context: Context,
    private val activity: VRActivity,
    private val scope: CoroutineScope,
    private val onBackToVideo: () -> Unit = { activity.requestUiPanelHidden() },
) : LinearLayout(context) {
    data class FileDetailData(
        val entry: MediaEntry,
        val source: PlaybackSource,
        val onPlay: (resumeAtMs: Long?) -> Unit,
    )

    enum class RailTab {
        NOW_PLAYING,
        FILE_DETAIL,
    }

    private val contentLayout: LinearLayout
    private var progressLabel: TextView? = null
    private var progressBar: ProgressBar? = null
    private var lastLoadedSourceKey: String? = null
    private var selectedDetail: FileDetailData? = null
    private var activeTab: RailTab = RailTab.NOW_PLAYING

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

    fun showFileDetail(
        entry: MediaEntry,
        source: PlaybackSource,
        onPlay: (resumeAtMs: Long?) -> Unit,
    ) {
        selectedDetail = FileDetailData(entry, source, onPlay)
        activeTab = RailTab.FILE_DETAIL
        updateState()
    }

    fun clearFileDetail() {
        selectedDetail = null
        activeTab = RailTab.NOW_PLAYING
        updateState()
    }

    fun updateState() {
        contentLayout.removeAllViews()
        progressLabel = null
        progressBar = null

        val currentSource = activity.currentPlaybackSource
        val detail = selectedDetail

        if (currentSource != null && detail != null) {
            buildTabHeader()
            if (activeTab == RailTab.FILE_DETAIL) {
                buildFileDetailCard(detail)
            } else {
                buildActivePlaybackCard(currentSource)
            }
        } else if (detail != null) {
            buildFileDetailCard(detail)
        } else if (currentSource != null) {
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
                    onBackToVideo()
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

    private fun buildTabHeader() {
        val tabContainer =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams =
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 12f)
                    }
            }

        val btnNowPlaying =
            VoidButton(
                context,
                if (activeTab == RailTab.NOW_PLAYING) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY,
                isCompact = true,
            ).apply {
                text = context.getString(R.string.context_rail_now_playing)
                textSize = 13f
                layoutParams =
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = VoidTheme.dpToPx(context, 4f)
                    }
                setOnClickListener {
                    activeTab = RailTab.NOW_PLAYING
                    updateState()
                }
            }

        val btnDetails =
            VoidButton(
                context,
                if (activeTab == RailTab.FILE_DETAIL) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY,
                isCompact = true,
            ).apply {
                text = context.getString(R.string.context_rail_tab_details)
                textSize = 13f
                layoutParams =
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = VoidTheme.dpToPx(context, 4f)
                    }
                setOnClickListener {
                    activeTab = RailTab.FILE_DETAIL
                    updateState()
                }
            }

        tabContainer.addView(btnNowPlaying)
        tabContainer.addView(btnDetails)
        contentLayout.addView(tabContainer)
    }

    private fun buildFileDetailCard(detail: FileDetailData) {
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

        // Cabeçalho do Card (Título + Botão Fechar ✕)
        val headerRow =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 12f)
                    }
            }
        val headerTitle =
            TextView(context).apply {
                text = context.getString(R.string.context_rail_file_details)
                typeface = VoidTheme.typefaceTitle
                textSize = 18f
                setTextColor(VoidTheme.colorAccent)
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        val btnClose =
            VoidButton(context, VoidButtonStyle.SECONDARY, isCompact = true).apply {
                text = "✕"
                textSize = 13f
                setOnClickListener {
                    clearFileDetail()
                }
            }
        headerRow.addView(headerTitle)
        headerRow.addView(btnClose)
        card.addView(headerRow)

        // Thumbnail grande (180dp)
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
                val fallbackRes =
                    when (detail.entry.type) {
                        MediaType.IMAGE -> R.drawable.ic_image
                        MediaType.AUDIO -> R.drawable.ic_audio
                        else -> R.drawable.ic_movie
                    }
                setImageResource(fallbackRes)
                setColorFilter(VoidTheme.colorTextSecondary)
            }
        thumbFrame.addView(thumbView)
        card.addView(thumbFrame)

        loadThumbnail(detail.source, thumbView, detail.entry)

        // Nome do arquivo
        val nameView =
            TextView(context).apply {
                text = detail.entry.name
                typeface = VoidTheme.typefaceBody
                textSize = 16f
                setTextColor(VoidTheme.colorText)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                val bottomPad = VoidTheme.dpToPx(context, 10f)
                setPadding(0, 0, 0, bottomPad)
            }
        card.addView(nameView)

        // Metadados: tamanho e formato 3D
        val metaColumn =
            LinearLayout(context).apply {
                orientation = VERTICAL
                val bottomPad = VoidTheme.dpToPx(context, 14f)
                setPadding(0, 0, 0, bottomPad)
            }

        if (detail.entry.sizeBytes > 0L) {
            val sizeRow =
                TextView(context).apply {
                    text =
                        context.getString(
                            R.string.file_detail_row_format,
                            context.getString(R.string.file_detail_label_size),
                            formatFileSize(context, detail.entry.sizeBytes),
                        )
                    typeface = VoidTheme.typefaceMono
                    textSize = 13f
                    setTextColor(VoidTheme.colorTextSecondary)
                    val bPad = VoidTheme.dpToPx(context, 2f)
                    setPadding(0, 0, 0, bPad)
                }
            metaColumn.addView(sizeRow)
        }

        if (detail.entry.format3DHint != Format3DType.FLAT_2D) {
            val format3dText =
                when (detail.entry.format3DHint) {
                    Format3DType.SBS -> "3D SBS"
                    Format3DType.OU -> "3D OU"
                    Format3DType.VR_180 -> "180° VR"
                    Format3DType.VR_360 -> "360° VR"
                    Format3DType.FLAT_2D -> ""
                }
            val formatRow =
                TextView(context).apply {
                    text =
                        context.getString(
                            R.string.file_detail_row_format,
                            context.getString(R.string.file_detail_label_format3d),
                            format3dText,
                        )
                    typeface = VoidTheme.typefaceMono
                    textSize = 13f
                    setTextColor(VoidTheme.colorAccent)
                    val bPad = VoidTheme.dpToPx(context, 2f)
                    setPadding(0, 0, 0, bPad)
                }
            metaColumn.addView(formatRow)
        }

        val resolutionRow =
            TextView(context).apply {
                typeface = VoidTheme.typefaceMono
                textSize = 13f
                setTextColor(VoidTheme.colorTextSecondary)
                visibility = View.GONE
            }
        metaColumn.addView(resolutionRow)
        card.addView(metaColumn)

        // Carrega resolução e duração em background a partir do cache
        scope.launch {
            val summary =
                withContext(Dispatchers.IO) {
                    MediaMetadataReader.readCachedSummary(context, detail.source)
                }
            if (summary != null) {
                val parts = mutableListOf<String>()
                if (summary.videoWidth > 0 && summary.videoHeight > 0) {
                    parts.add(
                        context.getString(
                            R.string.file_detail_row_format,
                            context.getString(R.string.file_detail_label_resolution),
                            "${summary.videoWidth}x${summary.videoHeight}",
                        ),
                    )
                }
                if (summary.durationMs > 0) {
                    parts.add(
                        context.getString(
                            R.string.file_detail_row_format,
                            context.getString(R.string.file_detail_label_duration),
                            formatDurationMs(summary.durationMs),
                        ),
                    )
                }
                if (parts.isNotEmpty()) {
                    resolutionRow.text = parts.joinToString("\n")
                    resolutionRow.visibility = View.VISIBLE
                }
            }
        }

        // Botões de Ação: "Play/Resume" e "From start"
        val buttonColumn =
            LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams =
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }

        val btnPlayResume =
            VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                setIcon(R.drawable.ic_play_arrow)
                text = context.getString(R.string.file_detail_btn_play)
                textSize = 15f
                minHeight = VoidTheme.dpToPx(context, 48f)
                layoutParams =
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 8f)
                    }
                setOnClickListener {
                    val playAction = detail.onPlay
                    clearFileDetail()
                    playAction(null)
                    activity.requestUiPanelHidden()
                }
            }
        buttonColumn.addView(btnPlayResume)

        val btnFromStart =
            VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                setIcon(R.drawable.ic_browser_refresh)
                text = context.getString(R.string.context_rail_btn_from_start)
                textSize = 14f
                minHeight = VoidTheme.dpToPx(context, 44f)
                layoutParams =
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                setOnClickListener {
                    val playAction = detail.onPlay
                    clearFileDetail()
                    playAction(0L)
                    activity.requestUiPanelHidden()
                }
            }
        buttonColumn.addView(btnFromStart)

        scope.launch {
            val existing =
                withContext(Dispatchers.IO) {
                    activity.historyTracker.findExisting(detail.source)
                }
            if (existing != null && existing.isResumable()) {
                val resumeTime = formatDurationMs(existing.positionMs)
                btnPlayResume.text = context.getString(R.string.history_resume_prompt_btn_resume_format, resumeTime)
                btnPlayResume.setOnClickListener {
                    val playAction = detail.onPlay
                    clearFileDetail()
                    playAction(existing.positionMs)
                    activity.requestUiPanelHidden()
                }
            }
        }

        card.addView(buttonColumn)
        contentLayout.addView(card)
    }

    private fun loadThumbnail(
        source: PlaybackSource,
        imageView: ImageView,
        mediaEntry: MediaEntry? = null,
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
                                    val entry =
                                        mediaEntry ?: run {
                                            val size = if (source.sizeBytes > 0L) source.sizeBytes else file.length()
                                            val detectedType =
                                                com.tucavr.filebrowser.mediaTypeForExtension(file.extension)
                                                    ?: MediaType.VIDEO
                                            MediaEntry(
                                                name = file.name,
                                                path = file.absolutePath,
                                                sizeBytes = size,
                                                lastModified = file.lastModified(),
                                                type = detectedType,
                                            )
                                        }
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
