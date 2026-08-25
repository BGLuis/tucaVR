package com.tucavr.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidListRow
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme
import com.tucavr.navigation.Destination
import com.tucavr.network.SavedServer
import com.tucavr.network.SavedServerDao
import com.tucavr.network.ServerProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

data class DlnaItem(
    val id: String,
    val title: String,
    val isContainer: Boolean,
    val url: String?,
    val sizeBytes: Long,
    val durationSec: Double,
    val resolution: String?,
    val thumbnailUrl: String?
)

/**
 * T7.5: Tela de navegação e gerenciamento de servidores UPnP / DLNA (MediaServer).
 */
class NetworkDlnaScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val savedServerDao: SavedServerDao,
    private val onNavigate: (Destination) -> Unit,
    private val onPlayDlna: (server: SavedServer, title: String, url: String, sizeBytes: Long) -> Unit,
    private val onBack: () -> Unit
) {

    private val folderStack = ArrayDeque<Pair<String, String>>()
    private var currentServer: SavedServer? = null
    private var currentItems: List<DlnaItem> = emptyList()

    private var fileListContainer: LinearLayout? = null
    private var fileProgressBar: ProgressBar? = null
    private var fileStatusLabel: android.widget.TextView? = null

    // ---- Aba de Servidores Salvos (para NetworkHomeScreen) ----

    fun buildPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 12f)
            }
        }

        val title = VoidText.title(context, context.getString(R.string.network_dlna_header), sizeSp = 20f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(title)
        page.addView(headerRow)

        val serverListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        page.addView(serverListContainer)

        fun refreshServerList() {
            serverListContainer.removeAllViews()
            scope.launch {
                val servers = withContext(Dispatchers.IO) {
                    try {
                        savedServerDao.getByProtocol(ServerProtocol.DLNA)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                if (servers.isEmpty()) {
                    serverListContainer.addView(
                        VoidText.body(
                            context,
                            context.getString(R.string.network_dlna_empty),
                            sizeSp = 16f,
                            secondary = true
                        )
                    )
                } else {
                    for (server in servers) {
                        val row = buildSavedServerRow(server) {
                            refreshServerList()
                        }
                        serverListContainer.addView(row)
                    }
                }
            }
        }
        refreshServerList()

        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(page)
        }
    }

    private fun buildSavedServerRow(server: SavedServer, onDeleted: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
        }

        val meta = "${server.host}:${server.port}".trim()
        val listRow = VoidListRow(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            bind(server.name, meta = meta, showThumbnailSlot = false, iconResId = R.drawable.ic_movie)
            setOnClickListener {
                onNavigate(Destination.NetworkDlnaFiles(server, "0", server.name))
            }
        }

        val btnDelete = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = ""
            setIcon(R.drawable.icon_x)
            textSize = 16f
            minHeight = 0
            val pad = VoidTheme.dpToPx(context, 12f)
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                scope.launch(Dispatchers.IO) {
                    savedServerDao.delete(server.id)
                    withContext(Dispatchers.Main) {
                        onDeleted()
                    }
                }
            }
        }

        row.addView(listRow)
        row.addView(btnDelete, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = VoidTheme.dpToPx(context, 8f)
        })
        return row
    }

    // ---- Navegação de Diretórios DLNA (ContentDirectory) ----

    fun renderFiles(server: SavedServer, objectId: String = "0") {
        currentServer = server
        if (folderStack.isEmpty()) {
            folderStack.push(Pair("0", server.name))
        }

        val root = VoidPanelChrome.newRoot(context)
        val currentFolder = folderStack.peek() ?: Pair("0", server.name)

        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = currentFolder.second,
                onBack = { handleBack() }
            )
        )

        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
        }

        fileProgressBar = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                VoidTheme.dpToPx(context, 24f), VoidTheme.dpToPx(context, 24f)
            ).also {
                it.marginEnd = VoidTheme.dpToPx(context, 8f)
            }
        }
        progressRow.addView(fileProgressBar)

        fileStatusLabel = VoidText.body(context, "", sizeSp = 14f, secondary = true)
        progressRow.addView(fileStatusLabel)
        root.addView(progressRow)

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        fileListContainer = listContainer

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(listContainer)
        }
        root.addView(scroll)

        host.showScreen(root)
        loadDirectory(currentFolder.first)
    }

    private fun loadDirectory(objectId: String) {
        val server = currentServer ?: return

        fileProgressBar?.visibility = View.VISIBLE
        fileStatusLabel?.text = context.getString(R.string.network_dlna_loading)

        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                activity.nativeDlnaBrowse(server.path, objectId, 0, 200)
            }

            fileProgressBar?.visibility = View.GONE
            if (raw.startsWith("ERROR:")) {
                fileStatusLabel?.text = context.getString(R.string.network_dlna_error_format, raw.removePrefix("ERROR:"))
                currentItems = emptyList()
            } else {
                fileStatusLabel?.text = ""
                currentItems = parseDlnaItems(raw)
            }
            renderItemsList()
        }
    }

    private fun renderItemsList() {
        val container = fileListContainer ?: return
        container.removeAllViews()

        if (currentItems.isEmpty()) {
            container.addView(
                VoidText.body(
                    context,
                    context.getString(R.string.network_dlna_no_items),
                    sizeSp = 16f,
                    secondary = true
                )
            )
            return
        }

        val server = currentServer ?: return

        for (item in currentItems) {
            val row = VoidListRow(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also {
                    it.bottomMargin = VoidTheme.dpToPx(context, 8f)
                }

                val icon = if (item.isContainer) R.drawable.ic_folder else R.drawable.ic_movie
                val meta = buildItemMeta(item)
                bind(item.title, meta = meta, showThumbnailSlot = false, iconResId = icon)

                setOnClickListener {
                    if (item.isContainer) {
                        folderStack.push(Pair(item.id, item.title))
                        renderFiles(server, item.id)
                    } else if (!item.url.isNullOrBlank()) {
                        onPlayDlna(server, item.title, item.url, item.sizeBytes)
                    }
                }
            }
            container.addView(row)
        }
    }

    private fun buildItemMeta(item: DlnaItem): String {
        if (item.isContainer) {
            return context.getString(R.string.network_dlna_folder_tag)
        }

        val parts = mutableListOf<String>()
        if (!item.resolution.isNullOrBlank()) {
            parts.add("[${item.resolution}]")
        }
        if (item.durationSec > 0.0) {
            parts.add(formatSeconds(item.durationSec))
        }
        if (item.sizeBytes > 0L) {
            parts.add(formatFileSize(item.sizeBytes))
        }
        return parts.joinToString(" • ")
    }

    private fun formatSeconds(sec: Double): String {
        val totalSec = sec.toLong()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format("%.2f GB", mb / 1024.0)
        } else {
            String.format("%.1f MB", mb)
        }
    }

    fun handleBack() {
        if (folderStack.size > 1) {
            folderStack.pop()
            val parent = folderStack.peek() ?: Pair("0", currentServer?.name ?: "DLNA")
            renderFiles(currentServer ?: return, parent.first)
        } else {
            folderStack.clear()
            onBack()
        }
    }

    private fun parseDlnaItems(raw: String): List<DlnaItem> {
        if (raw.isBlank() || raw.startsWith("ERROR:")) {
            return emptyList()
        }

        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("\t")
            val id = parts.getOrNull(0) ?: return@mapNotNull null
            val title = parts.getOrNull(1) ?: "Sem título"
            val isContainer = parts.getOrNull(2) == "1"
            val url = parts.getOrNull(3)?.ifEmpty { null }
            val size = parts.getOrNull(4)?.toLongOrNull()?.takeIf { it >= 0 } ?: 0L
            val dur = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0
            val resolution = parts.getOrNull(6)?.ifEmpty { null }
            val thumb = parts.getOrNull(7)?.ifEmpty { null }

            DlnaItem(
                id = id,
                title = title,
                isContainer = isContainer,
                url = url,
                sizeBytes = size,
                durationSec = dur,
                resolution = resolution,
                thumbnailUrl = thumb
            )
        }
    }
}
