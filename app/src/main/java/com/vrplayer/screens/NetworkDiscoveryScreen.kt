package com.vrplayer.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.vrplayer.R
import com.vrplayer.VRActivity
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidListRow
import com.vrplayer.designsystem.VoidText
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.navigation.Destination
import com.vrplayer.network.MulticastLockManager
import com.vrplayer.network.SavedServer
import com.vrplayer.network.SavedServerDao
import com.vrplayer.network.ServerProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Representa um servidor descoberto na rede local via mDNS ou SSDP.
 */
data class DiscoveredServerItem(
    val protocol: ServerProtocol,
    val name: String,
    val host: String,
    val port: Int,
    val path: String = ""
)

/**
 * T10.4: Tela de Descoberta Automatica de Servidores na Rede Local (mDNS e SSDP).
 * Localiza e lista servidores SMB, NFS, FTP, SFTP, WebDAV e DLNA.
 */
class NetworkDiscoveryScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val savedServerDao: SavedServerDao,
    private val lockManager: MulticastLockManager,
    private val onNavigate: (Destination) -> Unit,
    private val onConfigureServer: (protocol: ServerProtocol, host: String, port: Int, name: String, path: String) -> Unit
) {

    private var discoveredServers: List<DiscoveredServerItem> = emptyList()
    private var isScanning: Boolean = false
    private var periodicScanJob: Job? = null

    private var containerView: LinearLayout? = null
    private var statusLabel: android.widget.TextView? = null
    private var progressBar: ProgressBar? = null
    private var listContainer: LinearLayout? = null

    fun buildPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        containerView = page

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
        }

        val title = VoidText.title(
            context,
            context.getString(R.string.network_discovery_header),
            sizeSp = 20f
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(title)

        val btnRescan = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.network_discovery_btn_rescan)
            setIcon(R.drawable.ic_search)
            textSize = 15f
            setOnClickListener {
                startScan()
            }
        }
        headerRow.addView(btnRescan)
        page.addView(headerRow)

        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
        }

        progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                VoidTheme.dpToPx(context, 24f), VoidTheme.dpToPx(context, 24f)
            ).also {
                it.marginEnd = VoidTheme.dpToPx(context, 8f)
            }
        }
        progressRow.addView(progressBar)

        statusLabel = VoidText.body(
            context,
            "",
            sizeSp = 14f,
            secondary = true
        )
        progressRow.addView(statusLabel)
        page.addView(progressRow)

        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        page.addView(listContainer)

        startScan()

        return ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(page)
        }
    }

    /**
     * Inicia a varredura assincrona e gerencia o MulticastLock.
     */
    fun startScan() {
        if (isScanning) return
        isScanning = true

        progressBar?.visibility = View.VISIBLE
        statusLabel?.text = context.getString(R.string.network_discovery_scanning)

        scope.launch {
            lockManager.acquire()
            try {
                val raw = withContext(Dispatchers.IO) {
                    activity.nativeDiscoveryScan(2500)
                }
                discoveredServers = parseScanResults(raw)
                renderList()
            } catch (e: Exception) {
                statusLabel?.text = context.getString(R.string.network_discovery_empty)
            } finally {
                lockManager.release()
                isScanning = false
                progressBar?.visibility = View.GONE
                if (discoveredServers.isEmpty()) {
                    statusLabel?.text = context.getString(R.string.network_discovery_empty)
                } else {
                    statusLabel?.text = ""
                }
            }
        }
    }

    private fun renderList() {
        val container = listContainer ?: return
        container.removeAllViews()

        if (discoveredServers.isEmpty()) {
            container.addView(
                VoidText.body(
                    context,
                    context.getString(R.string.network_discovery_empty),
                    sizeSp = 16f,
                    secondary = true
                )
            )
            return
        }

        for (item in discoveredServers) {
            val row = buildDiscoveredServerRow(item)
            container.addView(row)
        }
    }

    private fun buildDiscoveredServerRow(item: DiscoveredServerItem): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
        }

        val iconRes = when (item.protocol) {
            ServerProtocol.SMB -> R.drawable.ic_storage
            ServerProtocol.NFS -> R.drawable.ic_storage
            ServerProtocol.FTP -> R.drawable.ic_broadcast
            ServerProtocol.SFTP -> R.drawable.ic_lock
            ServerProtocol.DLNA -> R.drawable.ic_movie
            ServerProtocol.WEBDAV -> R.drawable.ic_link
        }

        val metaText = "[${item.protocol.name}]  ${item.host}:${item.port} ${item.path}".trim()

        val listRow = VoidListRow(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            bind(item.name, meta = metaText, showThumbnailSlot = false, iconResId = iconRes)
            setOnClickListener {
                onServerSelected(item)
            }
        }

        val actionBtn = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = if (item.protocol == ServerProtocol.NFS || item.protocol == ServerProtocol.DLNA) {
                context.getString(R.string.network_discovery_action_connect)
            } else {
                context.getString(R.string.network_discovery_action_configure)
            }
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.marginStart = VoidTheme.dpToPx(context, 8f)
            }
            setOnClickListener {
                onServerSelected(item)
            }
        }

        row.addView(listRow)
        row.addView(actionBtn)
        return row
    }

    private fun onServerSelected(item: DiscoveredServerItem) {
        when (item.protocol) {
            ServerProtocol.NFS -> {
                val server = SavedServer(
                    name = item.name,
                    protocol = ServerProtocol.NFS,
                    host = item.host,
                    port = item.port,
                    path = item.path.ifEmpty { "/" },
                    isAutoDiscovered = true,
                    lastConnectedAt = System.currentTimeMillis()
                )
                scope.launch(Dispatchers.IO) {
                    savedServerDao.insert(server)
                }
                onNavigate(Destination.NetworkNfsFiles(server, ""))
            }
            ServerProtocol.DLNA -> {
                scope.launch {
                    val rawDesc = withContext(Dispatchers.IO) {
                        activity.nativeDlnaGetDevice(item.path)
                    }
                    val parts = rawDesc.split("\t")
                    val friendlyName = if (parts.size >= 2 && parts[0] == "OK" && parts[1].isNotBlank()) parts[1] else item.name
                    val controlUrl = if (parts.size >= 3 && parts[0] == "OK") parts[2] else item.path

                    val server = SavedServer(
                        name = friendlyName,
                        protocol = ServerProtocol.DLNA,
                        host = item.host,
                        port = item.port,
                        path = controlUrl,
                        isAutoDiscovered = true,
                        lastConnectedAt = System.currentTimeMillis()
                    )
                    withContext(Dispatchers.IO) {
                        savedServerDao.insert(server)
                    }
                    onNavigate(Destination.NetworkDlnaFiles(server, "0", friendlyName))
                }
            }
            ServerProtocol.SMB, ServerProtocol.FTP, ServerProtocol.SFTP, ServerProtocol.WEBDAV -> {
                onConfigureServer(item.protocol, item.host, item.port, item.name, item.path)
            }
        }
    }

    private fun parseScanResults(raw: String): List<DiscoveredServerItem> {
        if (raw.isBlank() || raw.startsWith("ERROR:")) {
            return emptyList()
        }

        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("\t")
            val protoStr = parts.getOrNull(0)?.uppercase() ?: return@mapNotNull null
            val proto = try {
                ServerProtocol.valueOf(protoStr)
            } catch (e: Exception) {
                return@mapNotNull null
            }
            val name = parts.getOrNull(1) ?: "Server"
            val host = parts.getOrNull(2) ?: return@mapNotNull null
            val port = parts.getOrNull(3)?.toIntOrNull() ?: 0
            val path = parts.getOrNull(4) ?: ""

            DiscoveredServerItem(
                protocol = proto,
                name = name,
                host = host,
                port = port,
                path = path
            )
        }
    }

    fun onResume() {
        periodicScanJob?.cancel()
        periodicScanJob = scope.launch {
            while (isActive) {
                delay(30_000)
                startScan()
            }
        }
    }

    fun onPause() {
        periodicScanJob?.cancel()
        periodicScanJob = null
        lockManager.release()
    }
}
