package com.tucavr.screens

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.tucavr.R
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme
import com.tucavr.download.DiskSpaceManager
import com.tucavr.download.Download
import com.tucavr.download.DownloadRepository
import com.tucavr.download.DownloadStatus
import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Tela do Gerenciador de Downloads Offline (Fase 0.4 Seção 4 / T4.5-T4.7).
 *
 * Exibe a fila e histórico de downloads com barra de progresso visual, velocidade em tempo real,
 * espaço livre no Quest 3 e controles de pausar/retomar/cancelar e reproduzir mídias baixadas.
 */
class DownloadsScreen(
    private val context: Context,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val repository: DownloadRepository,
    private val diskSpaceManager: DiskSpaceManager = DiskSpaceManager(),
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit
) {

    private lateinit var listContainer: LinearLayout
    private lateinit var storageInfoText: View
    private lateinit var emptyView: View
    private var livePollJob: Job? = null

    fun render() {
        val root = VoidPanelChrome.newRoot(context)

        // Cabeçalho com botão Voltar
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = context.getString(R.string.downloads_title),
                onBack = {
                    livePollJob?.cancel()
                    onBack()
                }
            )
        )

        // Seção de armazenamento livre no Quest 3
        val storageSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                0,
                VoidTheme.dpToPx(context, 8f),
                0,
                VoidTheme.dpToPx(context, 12f)
            )
        }

        val downloadDir = DownloadRepository.getDefaultDownloadDir()
        val spaceInfo = diskSpaceManager.getDiskSpaceInfo(downloadDir)
        val freeStr = DiskSpaceManager.formatBytes(spaceInfo.availableBytes)
        val totalStr = DiskSpaceManager.formatBytes(spaceInfo.totalBytes)

        val storageSummary = context.getString(R.string.downloads_storage_format, freeStr, totalStr)
        storageInfoText = VoidText.body(context, storageSummary, sizeSp = 16f, secondary = true)
        storageSection.addView(storageInfoText)

        if (diskSpaceManager.isLowStorage(downloadDir)) {
            val warning = VoidText.body(
                context,
                context.getString(R.string.downloads_storage_low_warning),
                sizeSp = 14f
            ).apply {
                setTextColor(Color.parseColor("#FFCC00")) // Amarelo de alerta
                setPadding(0, VoidTheme.dpToPx(context, 4f), 0, 0)
            }
            storageSection.addView(warning)
        }

        root.addView(storageSection)

        // Lista rolável de downloads
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }

        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        emptyView = VoidText.body(
            context,
            context.getString(R.string.downloads_empty),
            sizeSp = 18f,
            secondary = true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, VoidTheme.dpToPx(context, 48f), 0, VoidTheme.dpToPx(context, 48f))
            visibility = View.GONE
        }
        listContainer.addView(emptyView)
        scrollView.addView(listContainer)
        root.addView(scrollView)

        // Rodapé de ações globais
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, VoidTheme.dpToPx(context, 12f), 0, 0)
        }

        val btnClearCompleted = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.downloads_btn_clear_completed)
            setOnClickListener {
                scope.launch {
                    repository.clearCompleted()
                    loadDownloads()
                }
            }
        }
        footer.addView(btnClearCompleted)
        root.addView(footer)

        host.showScreen(root)

        loadDownloads()
        startLivePolling()
    }

    private fun loadDownloads() {
        scope.launch {
            val downloads = repository.listAll()
            withContext(Dispatchers.Main) {
                renderItems(downloads)
            }
        }
    }

    private fun startLivePolling() {
        livePollJob?.cancel()
        livePollJob = scope.launch {
            while (isActive) {
                repository.syncActiveDownloads()
                val downloads = repository.listAll()
                withContext(Dispatchers.Main) {
                    renderItems(downloads)
                }
                delay(1000)
            }
        }
    }

    private fun renderItems(downloads: List<Download>) {
        listContainer.removeAllViews()
        if (downloads.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            listContainer.addView(emptyView)
            return
        }
        emptyView.visibility = View.GONE

        for (item in downloads) {
            val liveStats = repository.getStats(item.id)
            val currentBytes = liveStats?.downloadedBytes ?: item.downloadedBytes
            val totalBytes = if (item.totalBytes > 0) item.totalBytes else liveStats?.totalBytes ?: 0L
            val state = liveStats?.state ?: item.state
            val speedBps = liveStats?.speedBps ?: 0L

            val card = buildDownloadCard(item, currentBytes, totalBytes, state, speedBps)
            listContainer.addView(card)
        }
    }

    private fun buildDownloadCard(
        item: Download,
        currentBytes: Long,
        totalBytes: Long,
        state: String,
        speedBps: Long
    ): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 12f)
            }
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurfaceAlt)
                cornerRadius = VoidTheme.dp(context, 8f)
            }
            setPadding(
                VoidTheme.dpToPx(context, 16f),
                VoidTheme.dpToPx(context, 12f),
                VoidTheme.dpToPx(context, 16f),
                VoidTheme.dpToPx(context, 12f)
            )
        }

        // Top row: Nome do arquivo + Badge de Estado
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameView = VoidText.body(context, item.displayName, sizeSp = 18f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val stateLabel = when (state) {
            DownloadStatus.QUEUED -> context.getString(R.string.downloads_status_queued)
            DownloadStatus.DOWNLOADING -> context.getString(R.string.downloads_status_downloading)
            DownloadStatus.PAUSED -> context.getString(R.string.downloads_status_paused)
            DownloadStatus.COMPLETED -> context.getString(R.string.downloads_status_completed)
            DownloadStatus.FAILED -> context.getString(R.string.downloads_status_failed)
            DownloadStatus.CANCELLED -> context.getString(R.string.downloads_status_failed)
            else -> state
        }

        val stateColor = when (state) {
            DownloadStatus.COMPLETED -> Color.parseColor("#4CAF50") // Verde
            DownloadStatus.DOWNLOADING -> Color.parseColor("#00E5FF") // Void Cyan
            DownloadStatus.PAUSED -> Color.parseColor("#FFCC00") // Amarelo
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> Color.parseColor("#F44336") // Vermelho
            else -> VoidTheme.colorTextSecondary
        }

        val stateBadge = VoidText.body(context, stateLabel, sizeSp = 14f).apply {
            setTextColor(stateColor)
            setPadding(VoidTheme.dpToPx(context, 8f), 0, 0, 0)
        }

        topRow.addView(nameView)
        topRow.addView(stateBadge)
        card.addView(topRow)

        // Barra de progresso horizontal
        val percent = if (totalBytes > 0) {
            ((currentBytes.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 8f)
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 8f)
                bottomMargin = VoidTheme.dpToPx(context, 6f)
            }
            max = 100
            this.progress = percent
            isIndeterminate = (state == DownloadStatus.DOWNLOADING && totalBytes == 0L)
        }
        card.addView(progressBar)

        // Linha de métricas: baixados / total, velocidade e ETA
        val speedStr = if (speedBps > 0) {
            val mb = speedBps.toDouble() / (1024.0 * 1024.0)
            String.format(Locale.US, "%.1f MB/s", mb)
        } else ""

        val etaStr = if (speedBps > 0 && totalBytes > currentBytes) {
            val remainingSecs = (totalBytes - currentBytes) / speedBps
            formatEta(remainingSecs)
        } else ""

        val currFormatted = DiskSpaceManager.formatBytes(currentBytes)
        val totalFormatted = if (totalBytes > 0) DiskSpaceManager.formatBytes(totalBytes) else "—"

        val detailsBuilder = StringBuilder("$currFormatted / $totalFormatted ($percent%)")
        if (speedStr.isNotEmpty()) {
            detailsBuilder.append(" • $speedStr")
        }
        if (etaStr.isNotEmpty()) {
            detailsBuilder.append(" • $etaStr")
        }

        val detailsView = VoidText.body(context, detailsBuilder.toString(), sizeSp = 14f, secondary = true)
        card.addView(detailsView)

        // Botões de ação contextuais por tarefa
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 10f)
            }
        }

        when (state) {
            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                val btnPause = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                    text = context.getString(R.string.downloads_btn_pause)
                    textSize = 14f
                    setOnClickListener {
                        scope.launch {
                            repository.pause(item.id)
                            loadDownloads()
                        }
                    }
                }
                val btnCancel = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                    text = context.getString(R.string.downloads_btn_cancel)
                    textSize = 14f
                    setOnClickListener {
                        scope.launch {
                            repository.cancel(item.id)
                            loadDownloads()
                        }
                    }
                }
                actionsRow.addView(btnPause)
                actionsRow.addView(btnCancel)
            }
            DownloadStatus.PAUSED -> {
                val btnResume = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                    text = context.getString(R.string.downloads_btn_resume)
                    textSize = 14f
                    setOnClickListener {
                        scope.launch {
                            repository.resume(item.id)
                            loadDownloads()
                        }
                    }
                }
                val btnCancel = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                    text = context.getString(R.string.downloads_btn_cancel)
                    textSize = 14f
                    setOnClickListener {
                        scope.launch {
                            repository.cancel(item.id)
                            loadDownloads()
                        }
                    }
                }
                actionsRow.addView(btnResume)
                actionsRow.addView(btnCancel)
            }
            DownloadStatus.COMPLETED -> {
                val btnPlay = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                    text = context.getString(R.string.downloads_btn_play)
                    textSize = 14f
                    setOnClickListener {
                        livePollJob?.cancel()
                        onNavigate(Destination.Player(PlaybackSource.LocalFile(item.destinationPath, item.totalBytes)))
                    }
                }
                val btnDelete = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                    text = context.getString(R.string.playlists_delete)
                    textSize = 14f
                    setOnClickListener {
                        scope.launch {
                            repository.delete(item.id)
                            loadDownloads()
                        }
                    }
                }
                actionsRow.addView(btnPlay)
                actionsRow.addView(btnDelete)
            }
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                val btnRetry = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                    text = context.getString(R.string.downloads_btn_retry)
                    textSize = 14f
                    setOnClickListener {
                        scope.launch {
                            repository.resume(item.id)
                            loadDownloads()
                        }
                    }
                }
                val btnDelete = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                    text = context.getString(R.string.playlists_delete)
                    textSize = 14f
                    setOnClickListener {
                        scope.launch {
                            repository.delete(item.id)
                            loadDownloads()
                        }
                    }
                }
                actionsRow.addView(btnRetry)
                actionsRow.addView(btnDelete)
            }
        }

        card.addView(actionsRow)
        return card
    }

    private fun formatEta(seconds: Long): String {
        return if (seconds < 60) {
            "${seconds}s"
        } else {
            val mins = seconds / 60
            val secs = seconds % 60
            "${mins}m ${secs}s"
        }
    }
}
