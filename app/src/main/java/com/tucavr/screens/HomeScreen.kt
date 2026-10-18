package com.tucavr.screens

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.designsystem.VoidTheme
import com.tucavr.filebrowser.DirectoryLister
import com.tucavr.filebrowser.MediaEntry
import com.tucavr.filebrowser.MediaType
import com.tucavr.filebrowser.NetworkThumbnailGenerator
import com.tucavr.filebrowser.ThumbnailGenerator
import com.tucavr.history.HistorySourceResolver
import com.tucavr.history.HistorySourceType
import com.tucavr.history.PlaybackHistory
import com.tucavr.history.isResumable
import com.tucavr.history.watchedPercent
import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.SavedServer
import com.tucavr.network.SavedServerDao
import com.tucavr.network.ServerProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Painel de resumo exibido na zona central de conteúdo (1200 px).
 * Apresenta:
 * 1. Linha horizontal "Continuar assistindo" com miniaturas e progresso do [com.tucavr.history.PlaybackHistoryTracker].
 * 2. Linha horizontal "Fontes recentes" (Arquivos locais, servidores configurados e navegador Web VR).
 * 3. Linha horizontal "Adicionados recentemente" com os vídeos mais novos no armazenamento local.
 */
class HomeScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val savedServerDao: SavedServerDao,
    private val onNavigate: (Destination) -> Unit,
    private val onPlaySource: (PlaybackSource) -> Unit,
    private val onOpenBrowser: () -> Unit = {},
) {
    fun render() {
        val rootScroll =
            ScrollView(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                isFillViewport = true
            }

        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = VoidTheme.dpToPx(context, 24f)
                setPadding(pad, pad, pad, pad)
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }

        // Título principal do Dashboard
        val titleView =
            TextView(context).apply {
                text = context.getString(R.string.home_title)
                typeface = VoidTheme.typefaceTitle
                textSize = 28f
                setTextColor(VoidTheme.colorText)
                val bottomPad = VoidTheme.dpToPx(context, 20f)
                setPadding(0, 0, 0, bottomPad)
            }
        content.addView(titleView)

        // Seção 1: Continuar Assistindo
        buildContinueWatchingSection(content)

        // Seção 2: Fontes Recentes
        buildRecentSourcesSection(content)

        // Seção 3: Adicionados Recentemente
        buildRecentlyAddedSection(content)

        rootScroll.addView(content)
        host.showScreen(rootScroll)
    }

    private fun buildContinueWatchingSection(parent: LinearLayout) {
        val sectionHeader = createSectionHeader(context.getString(R.string.home_section_continue_watching))
        parent.addView(sectionHeader)

        val cardsRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }

        val emptyText = createEmptyTextView(context.getString(R.string.home_no_recent_videos))

        val hScroll =
            HorizontalScrollView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 24f)
                    }
                clipChildren = false
                clipToPadding = false
                addView(cardsRow)
            }

        parent.addView(emptyText)
        parent.addView(hScroll)

        scope.launch {
            val allRecent =
                withContext(Dispatchers.IO) {
                    activity.historyTracker.listRecent()
                }
            val resumable = allRecent.filter { it.isResumable() }.take(10)
            val items = if (resumable.isNotEmpty()) resumable else allRecent.take(10)

            if (items.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                hScroll.visibility = View.GONE
            } else {
                emptyText.visibility = View.GONE
                hScroll.visibility = View.VISIBLE
                for (item in items) {
                    val card = buildHistoryCard(item)
                    cardsRow.addView(card)
                }
            }
        }
    }

    private fun buildHistoryCard(entry: PlaybackHistory): View {
        val cardWidth = VoidTheme.dpToPx(context, 260f)
        val cardHeight = VoidTheme.dpToPx(context, 170f)
        val cornerRadius = VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
        val strokeWidth = VoidTheme.dpToPx(context, VoidTheme.borderWidthDp)

        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        this.cornerRadius = cornerRadius
                        setStroke(strokeWidth, VoidTheme.colorBorder)
                    }
                layoutParams =
                    LinearLayout.LayoutParams(cardWidth, cardHeight).apply {
                        rightMargin = VoidTheme.dpToPx(context, 14f)
                    }
                clipToOutline = true
                setOnClickListener {
                    resumeFromHistory(entry)
                }
            }

        val thumbFrame =
            FrameLayout(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        VoidTheme.dpToPx(context, 110f),
                    )
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorBackground)
                    }
            }

        val thumbImg =
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
        thumbFrame.addView(thumbImg)

        // Barra de progresso assistido na base da miniatura
        val percent = watchedPercent(entry)
        val progressBar =
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = percent
                progressTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
                progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#44000000"))
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        VoidTheme.dpToPx(context, 4f),
                        Gravity.BOTTOM,
                    )
            }
        thumbFrame.addView(progressBar)
        card.addView(thumbFrame)

        loadHistoryThumbnail(entry, thumbImg)

        val titleView =
            TextView(context).apply {
                text = entry.title
                typeface = VoidTheme.typefaceBody
                textSize = 14f
                setTextColor(VoidTheme.colorText)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                val pad = VoidTheme.dpToPx(context, 8f)
                setPadding(pad, pad, pad, pad)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }
        card.addView(titleView)

        return card
    }

    private fun buildRecentSourcesSection(parent: LinearLayout) {
        val sectionHeader = createSectionHeader(context.getString(R.string.home_section_recent_sources))
        parent.addView(sectionHeader)

        val cardsRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }

        val hScroll =
            HorizontalScrollView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 24f)
                    }
                clipChildren = false
                clipToPadding = false
                addView(cardsRow)
            }
        parent.addView(hScroll)

        // Card Arquivos Locais (sempre presente)
        val localCard =
            buildSourceCard(
                title = context.getString(R.string.home_btn_local_files),
                subtitle = context.getString(R.string.sidebar_item_files),
                iconRes = R.drawable.ic_folder,
            ) {
                onNavigate(Destination.LocalFiles(Environment.getExternalStorageDirectory().absolutePath))
            }
        cardsRow.addView(localCard)

        // Card Navegador Web VR
        val browserCard =
            buildSourceCard(
                title = context.getString(R.string.home_btn_web_browser),
                subtitle = context.getString(R.string.sidebar_item_network),
                iconRes = R.drawable.ic_browser_home,
            ) {
                onOpenBrowser()
            }
        cardsRow.addView(browserCard)

        // Servidores salvos do Room
        scope.launch {
            val servers =
                withContext(Dispatchers.IO) {
                    savedServerDao.getAll()
                }
            for (server in servers.take(6)) {
                val protoLabel = server.protocol.name
                val card =
                    buildSourceCard(
                        title = server.name,
                        subtitle = protoLabel,
                        iconRes =
                            when (server.protocol) {
                                ServerProtocol.SMB -> R.drawable.ic_network
                                ServerProtocol.NFS -> R.drawable.ic_storage
                                ServerProtocol.DLNA -> R.drawable.ic_broadcast
                                ServerProtocol.FTP, ServerProtocol.SFTP -> R.drawable.ic_computer
                                ServerProtocol.WEBDAV -> R.drawable.ic_link
                            },
                    ) {
                        navigateToSavedServer(server)
                    }
                cardsRow.addView(card)
            }
        }
    }

    private fun buildSourceCard(
        title: String,
        subtitle: String,
        iconRes: Int,
        onClick: () -> Unit,
    ): View {
        val cardWidth = VoidTheme.dpToPx(context, 200f)
        val cardHeight = VoidTheme.dpToPx(context, 100f)
        val cornerRadius = VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
        val strokeWidth = VoidTheme.dpToPx(context, VoidTheme.borderWidthDp)

        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = VoidTheme.dpToPx(context, 14f)
                setPadding(pad, pad, pad, pad)
                val baseBg =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        this.cornerRadius = cornerRadius
                        setStroke(strokeWidth, VoidTheme.colorBorder)
                    }
                val mask =
                    GradientDrawable().apply {
                        setColor(Color.WHITE)
                        this.cornerRadius = cornerRadius
                    }
                background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), baseBg, mask)
                layoutParams =
                    LinearLayout.LayoutParams(cardWidth, cardHeight).apply {
                        rightMargin = VoidTheme.dpToPx(context, 14f)
                    }
                setOnClickListener { onClick() }
            }

        val iconView =
            ImageView(context).apply {
                setImageResource(iconRes)
                val size = VoidTheme.dpToPx(context, 32f)
                layoutParams =
                    LinearLayout.LayoutParams(size, size).apply {
                        rightMargin = VoidTheme.dpToPx(context, 12f)
                    }
                setColorFilter(VoidTheme.colorAccent)
            }
        card.addView(iconView)

        val textCol =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

        val nameView =
            TextView(context).apply {
                text = title
                typeface = VoidTheme.typefaceBody
                textSize = 15f
                setTextColor(VoidTheme.colorText)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        textCol.addView(nameView)

        val subView =
            TextView(context).apply {
                text = subtitle
                typeface = VoidTheme.typefaceBody
                textSize = 12f
                setTextColor(VoidTheme.colorTextSecondary)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        textCol.addView(subView)

        card.addView(textCol)
        return card
    }

    private fun buildRecentlyAddedSection(parent: LinearLayout) {
        val sectionHeader = createSectionHeader(context.getString(R.string.home_section_recently_added))
        parent.addView(sectionHeader)

        val cardsRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }

        val emptyText = createEmptyTextView(context.getString(R.string.home_no_recent_files))

        val hScroll =
            HorizontalScrollView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 24f)
                    }
                clipChildren = false
                clipToPadding = false
                addView(cardsRow)
            }

        parent.addView(emptyText)
        parent.addView(hScroll)

        scope.launch {
            val recentMedia =
                withContext(Dispatchers.IO) {
                    try {
                        val rootDir = Environment.getExternalStorageDirectory()
                        val candidateDirs =
                            listOf(
                                rootDir,
                                File(rootDir, "Movies"),
                                File(rootDir, "Download"),
                                File(rootDir, "DCIM"),
                            ).filter { it.exists() && it.isDirectory }

                        val found = mutableListOf<MediaEntry>()
                        for (dir in candidateDirs) {
                            val entries = DirectoryLister.listMedia(dir).filter { it.type == MediaType.VIDEO }
                            found.addAll(entries)
                        }
                        found.distinctBy { it.path }
                            .sortedByDescending { it.lastModified }
                            .take(10)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }

            if (recentMedia.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                hScroll.visibility = View.GONE
            } else {
                emptyText.visibility = View.GONE
                hScroll.visibility = View.VISIBLE
                for (media in recentMedia) {
                    val card = buildRecentMediaCard(media)
                    cardsRow.addView(card)
                }
            }
        }
    }

    private fun buildRecentMediaCard(entry: MediaEntry): View {
        val cardWidth = VoidTheme.dpToPx(context, 260f)
        val cardHeight = VoidTheme.dpToPx(context, 170f)
        val cornerRadius = VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
        val strokeWidth = VoidTheme.dpToPx(context, VoidTheme.borderWidthDp)

        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        this.cornerRadius = cornerRadius
                        setStroke(strokeWidth, VoidTheme.colorBorder)
                    }
                layoutParams =
                    LinearLayout.LayoutParams(cardWidth, cardHeight).apply {
                        rightMargin = VoidTheme.dpToPx(context, 14f)
                    }
                clipToOutline = true
                setOnClickListener {
                    val source = PlaybackSource.LocalFile(entry.path, entry.sizeBytes)
                    onPlaySource(source)
                }
            }

        val thumbFrame =
            FrameLayout(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        VoidTheme.dpToPx(context, 110f),
                    )
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorBackground)
                    }
            }

        val thumbImg =
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
        thumbFrame.addView(thumbImg)
        card.addView(thumbFrame)

        scope.launch {
            val bitmap =
                withContext(Dispatchers.IO) {
                    ThumbnailGenerator.getThumbnail(context, entry)
                }
            if (bitmap != null) {
                thumbImg.clearColorFilter()
                thumbImg.setImageBitmap(bitmap)
            }
        }

        val titleView =
            TextView(context).apply {
                text = entry.name
                typeface = VoidTheme.typefaceBody
                textSize = 14f
                setTextColor(VoidTheme.colorText)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                val pad = VoidTheme.dpToPx(context, 8f)
                setPadding(pad, pad, pad, pad)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }
        card.addView(titleView)

        return card
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(context).apply {
            text = title
            typeface = VoidTheme.typefaceTitle
            textSize = 20f
            setTextColor(VoidTheme.colorText)
            val topPad = VoidTheme.dpToPx(context, 12f)
            val bottomPad = VoidTheme.dpToPx(context, 12f)
            setPadding(0, topPad, 0, bottomPad)
        }
    }

    private fun createEmptyTextView(textMessage: String): TextView {
        return TextView(context).apply {
            text = textMessage
            typeface = VoidTheme.typefaceBody
            textSize = 14f
            setTextColor(VoidTheme.colorTextSecondary)
            val pad = VoidTheme.dpToPx(context, 12f)
            setPadding(0, 0, 0, pad)
            visibility = View.GONE
        }
    }

    private fun loadHistoryThumbnail(
        entry: PlaybackHistory,
        imageView: ImageView,
    ) {
        scope.launch {
            val bitmap =
                withContext(Dispatchers.IO) {
                    try {
                        when (entry.sourceType) {
                            HistorySourceType.LOCAL -> {
                                val file = File(entry.mediaPath)
                                if (file.exists()) {
                                    ThumbnailGenerator.getThumbnail(
                                        context,
                                        MediaEntry(
                                            name = file.name,
                                            path = file.absolutePath,
                                            sizeBytes = file.length(),
                                            lastModified = file.lastModified(),
                                            type = MediaType.VIDEO,
                                        ),
                                    )
                                } else {
                                    null
                                }
                            }
                            HistorySourceType.SMB,
                            HistorySourceType.FTP,
                            HistorySourceType.SFTP,
                            HistorySourceType.NFS,
                            HistorySourceType.WEBDAV,
                            -> {
                                HistorySourceResolver.resolveNetworkSource(context, entry)?.let {
                                    NetworkThumbnailGenerator.getThumbnail(context, activity, it)
                                }
                            }
                            HistorySourceType.HTTP, HistorySourceType.DLNA -> null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            if (bitmap != null) {
                imageView.clearColorFilter()
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun resumeFromHistory(entry: PlaybackHistory) {
        when (entry.sourceType) {
            HistorySourceType.LOCAL -> {
                activity.playFile(entry.mediaPath, resumeAtMs = entry.positionMs)
            }
            HistorySourceType.HTTP -> {
                activity.playUrl(entry.mediaPath, resumeAtMs = entry.positionMs)
            }
            HistorySourceType.SMB -> {
                val server =
                    resolveServer(entry.serverInfo) { id ->
                        com.tucavr.network.SmbCredentialStore(activity).list().find { it.id == id }
                    } ?: return
                activity.playSmb(server, entry.mediaPath, resumeAtMs = entry.positionMs)
            }
            HistorySourceType.FTP -> {
                val server =
                    resolveServer(entry.serverInfo) { id ->
                        com.tucavr.network.FtpCredentialStore(activity).list().find { it.id == id }
                    } ?: return
                activity.playFtp(server, entry.mediaPath, resumeAtMs = entry.positionMs)
            }
            HistorySourceType.SFTP -> {
                val server =
                    resolveServer(entry.serverInfo) { id ->
                        com.tucavr.network.SftpCredentialStore(activity).list().find { it.id == id }
                    } ?: return
                activity.playSftp(server, entry.mediaPath, resumeAtMs = entry.positionMs)
            }
            HistorySourceType.NFS -> {
                scope.launch {
                    val server = resolveServerAsync(entry.serverInfo) { id -> savedServerDao.getById(id) } ?: return@launch
                    withContext(Dispatchers.Main) {
                        activity.playNfs(server, entry.mediaPath, resumeAtMs = entry.positionMs)
                        activity.requestUiPanelHidden()
                    }
                }
            }
            HistorySourceType.DLNA -> {
                scope.launch {
                    val server =
                        resolveServerAsync(entry.serverInfo) { id -> savedServerDao.getById(id) }
                            ?: SavedServer(name = entry.title, protocol = ServerProtocol.DLNA, host = "", port = 0, path = "")
                    withContext(Dispatchers.Main) {
                        activity.playDlna(server, entry.title, entry.mediaPath, resumeAtMs = entry.positionMs)
                        activity.requestUiPanelHidden()
                    }
                }
            }
            HistorySourceType.WEBDAV -> {
                scope.launch {
                    val server = resolveServerAsync(entry.serverInfo) { id -> savedServerDao.getById(id) } ?: return@launch
                    withContext(Dispatchers.Main) {
                        activity.playWebdav(server, entry.mediaPath, resumeAtMs = entry.positionMs)
                        activity.requestUiPanelHidden()
                    }
                }
            }
        }
        activity.requestUiPanelHidden()
    }

    private fun navigateToSavedServer(server: SavedServer) {
        when (server.protocol) {
            ServerProtocol.SMB -> {
                val smbServer = com.tucavr.network.SmbCredentialStore(activity).list().find { it.id == server.id }
                if (smbServer != null) {
                    onNavigate(Destination.NetworkFiles(smbServer, ""))
                } else {
                    onNavigate(Destination.NetworkHome)
                }
            }
            ServerProtocol.NFS -> onNavigate(Destination.NetworkNfsFiles(server, server.path))
            ServerProtocol.DLNA -> onNavigate(Destination.NetworkDlnaFiles(server, "0", server.name))
            ServerProtocol.FTP -> {
                val ftpServer = com.tucavr.network.FtpCredentialStore(activity).list().find { it.id == server.id }
                if (ftpServer != null) {
                    onNavigate(Destination.NetworkFtpFiles(ftpServer, ""))
                } else {
                    onNavigate(Destination.NetworkHome)
                }
            }
            ServerProtocol.SFTP -> {
                val sftpServer = com.tucavr.network.SftpCredentialStore(activity).list().find { it.id == server.id }
                if (sftpServer != null) {
                    onNavigate(Destination.NetworkSftpFiles(sftpServer, ""))
                } else {
                    onNavigate(Destination.NetworkHome)
                }
            }
            ServerProtocol.WEBDAV -> onNavigate(Destination.NetworkWebdavFiles(server, server.path))
        }
    }

    private fun <T> resolveServer(
        serverInfoJson: String?,
        finder: (String) -> T?,
    ): T? {
        if (serverInfoJson.isNullOrEmpty()) return null
        return try {
            val json = org.json.JSONObject(serverInfoJson)
            val serverId = json.optString("serverId", "")
            if (serverId.isNotEmpty()) finder(serverId) else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun <T> resolveServerAsync(
        serverInfoJson: String?,
        finder: suspend (String) -> T?,
    ): T? {
        if (serverInfoJson.isNullOrEmpty()) return null
        return try {
            val json = org.json.JSONObject(serverInfoJson)
            val serverId = json.optString("serverId", "")
            if (serverId.isNotEmpty()) withContext(Dispatchers.IO) { finder(serverId) } else null
        } catch (e: Exception) {
            null
        }
    }
}
