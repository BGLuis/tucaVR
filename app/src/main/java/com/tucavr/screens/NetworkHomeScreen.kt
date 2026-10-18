package com.tucavr.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.designsystem.FieldValidators
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidFieldAction
import com.tucavr.designsystem.VoidFieldKind
import com.tucavr.designsystem.VoidForm
import com.tucavr.designsystem.VoidListRow
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTextField
import com.tucavr.designsystem.VoidTheme
import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.MulticastLockManager
import com.tucavr.network.SavedServer
import com.tucavr.network.SavedServerDao
import com.tucavr.network.ServerCredentialStore
import com.tucavr.network.ServerProtocol
import com.tucavr.network.UrlHistoryStore
import com.tucavr.network.iconRes
import com.tucavr.network.labelRes
import com.tucavr.network.toFtpServer
import com.tucavr.network.toSftpServer
import com.tucavr.network.toSmbServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dados de preenchimento automático para formulários de adição/edição de servidor.
 */
data class ServerPrefill(
    val host: String = "",
    val port: Int = 0,
    val name: String = "",
    val path: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val privateKey: String? = null,
    val isAnonymousOrGuest: Boolean = false,
    val existingServerId: String? = null,
    val extraJson: String? = null,
)

/**
 * Landing da seção "Rede".
 *
 * Fase de unificação: em vez de abas por protocolo, mostra uma ÚNICA lista com
 * todos os servidores salvos (`SavedServerDao.getAll()`), de qualquer protocolo,
 * e uma seção inline inferior "Encontrados na rede" alimentada dinamicamente pelo
 * scan de descoberta (mDNS/SSDP) sem perder o foco nem navegar para outra tela.
 *
 * A exclusão de servidores salvos abre um modal de confirmação no 3º Quad.
 */
class NetworkHomeScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val urlHistory: UrlHistoryStore,
    private val savedServerDao: SavedServerDao,
    private val credentialStore: ServerCredentialStore,
    private val lockManager: MulticastLockManager? = null,
    private val discoveryPageBuilder: () -> View,
    private val smbAddFormBuilder: (onSaved: () -> Unit, prefill: ServerPrefill?) -> View,
    private val ftpAddFormBuilder: (onSaved: () -> Unit, prefill: ServerPrefill?) -> View,
    private val sftpAddFormBuilder: (onSaved: () -> Unit, prefill: ServerPrefill?) -> View,
    private val nfsAddFormBuilder: (onSaved: () -> Unit, prefill: ServerPrefill?) -> View,
    private val webdavAddFormBuilder: (onSaved: () -> Unit, prefill: ServerPrefill?) -> View,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit = {},
    private val onPlay: (PlaybackSource) -> Unit,
    private val onConfigureDiscoveredServer: (
        (
            protocol: ServerProtocol,
            host: String,
            port: Int,
            name: String,
            path: String,
        ) -> Unit
    )? = null,
) {
    private sealed class HomeMode {
        object List : HomeMode()

        object WizardSelectProtocol : HomeMode()

        data class AddForm(
            val protocol: ServerProtocol,
            val editingServerId: String? = null,
            val fromWizard: Boolean = false,
        ) : HomeMode()

        data class EditDlna(val server: SavedServer) : HomeMode()

        object Discovery : HomeMode()

        object Url : HomeMode()
    }

    private var mode: HomeMode = HomeMode.List
    private var pendingPrefill: ServerPrefill? = null

    private var discoveredServers: List<DiscoveredServerItem> = emptyList()
    private var isScanningDiscovery: Boolean = false
    private var hasRunInitialDiscovery: Boolean = false
    private var currentSavedServers: List<SavedServer> = emptyList()
    private var discoveryContainer: LinearLayout? = null
    private var discoveryProgressBar: ProgressBar? = null
    private var discoveryStatusLabel: TextView? = null

    /** Formulários manuais de cadastro disponíveis — DLNA não entra aqui (só via descoberta). */
    private val addFormBuilders: Map<ServerProtocol, (onSaved: () -> Unit, prefill: ServerPrefill?) -> View> =
        linkedMapOf(
            ServerProtocol.SMB to smbAddFormBuilder,
            ServerProtocol.FTP to ftpAddFormBuilder,
            ServerProtocol.SFTP to sftpAddFormBuilder,
            ServerProtocol.NFS to nfsAddFormBuilder,
            ServerProtocol.WEBDAV to webdavAddFormBuilder,
        )

    /** Ponto de entrada externo (navegação vinda de outra tela) — sempre reseta para a lista. */
    fun render() {
        pendingPrefill = null
        mode = HomeMode.List
        renderCurrent()
    }

    /** Abre o formulário de cadastro daquele protocolo (usado também pela Descoberta, com prefill). */
    fun openAddForm(
        protocol: ServerProtocol,
        prefill: ServerPrefill? = null,
        fromWizard: Boolean = false,
    ) {
        if (addFormBuilders.containsKey(protocol)) {
            pendingPrefill = prefill
            mode = HomeMode.AddForm(protocol, prefill?.existingServerId, fromWizard)
            renderCurrent()
        }
    }

    /** @return true se consumiu o "Voltar" internamente (voltou pra lista ou wizard); false = deixa o AppNavigator agir. */
    fun handleBack(): Boolean {
        if (mode is HomeMode.List) return false
        if (mode is HomeMode.EditDlna) {
            mode = HomeMode.List
            renderCurrent()
            return true
        }
        val currentAddForm = mode as? HomeMode.AddForm
        if (currentAddForm != null && currentAddForm.fromWizard) {
            pendingPrefill = null
            mode = HomeMode.WizardSelectProtocol
            renderCurrent()
            return true
        }
        pendingPrefill = null
        mode = HomeMode.List
        renderCurrent()
        return true
    }

    private fun renderCurrent() {
        when (val m = mode) {
            is HomeMode.List -> renderList()
            is HomeMode.WizardSelectProtocol -> renderWizardSelectProtocol()
            is HomeMode.AddForm -> renderAddForm(m.protocol, m.editingServerId)
            is HomeMode.EditDlna -> renderEditDlna(m.server)
            is HomeMode.Discovery -> renderDiscovery()
            is HomeMode.Url -> renderUrl()
        }
    }

    // ---- Modo Lista: todos os servidores salvos, cross-protocolo + Descoberta Inline ----

    private fun renderList() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(context, title = context.getString(R.string.network_title), onBack = { onBack() }),
        )

        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

        content.addView(buildActionsRow())

        // 1. Seção de Servidores Salvos
        content.addView(
            VoidText.title(context, context.getString(R.string.network_saved_servers_header), sizeSp = 20f).apply {
                setPadding(0, VoidTheme.dpToPx(context, 16f), 0, VoidTheme.dpToPx(context, 8f))
            },
        )

        val serversContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(serversContainer)

        // 2. Seção de Descoberta Inline ("Encontrados na rede")
        val discoveryHeaderRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = VoidTheme.dpToPx(context, 24f)
                        bottomMargin = VoidTheme.dpToPx(context, 8f)
                    }
            }

        val discoveryTitle =
            VoidText.title(context, context.getString(R.string.network_discovery_inline_header), sizeSp = 20f).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        discoveryHeaderRow.addView(discoveryTitle)

        val btnRescan =
            VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = context.getString(R.string.network_discovery_btn_rescan)
                setIcon(R.drawable.ic_search)
                textSize = 14f
                setOnClickListener {
                    startInlineDiscovery()
                }
            }
        discoveryHeaderRow.addView(btnRescan)
        content.addView(discoveryHeaderRow)

        val progressRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 8f)
                    }
            }

        val progressBar =
            ProgressBar(context).apply {
                isIndeterminate = true
                visibility = if (isScanningDiscovery) View.VISIBLE else View.GONE
                layoutParams =
                    LinearLayout.LayoutParams(
                        VoidTheme.dpToPx(context, 20f),
                        VoidTheme.dpToPx(context, 20f),
                    ).apply {
                        marginEnd = VoidTheme.dpToPx(context, 8f)
                    }
            }
        discoveryProgressBar = progressBar
        progressRow.addView(progressBar)

        val statusLabel =
            VoidText.body(
                context,
                if (isScanningDiscovery) context.getString(R.string.network_discovery_scanning) else "",
                sizeSp = 14f,
                secondary = true,
            )
        discoveryStatusLabel = statusLabel
        progressRow.addView(statusLabel)
        content.addView(progressRow)

        val discContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        discoveryContainer = discContainer
        content.addView(discContainer)

        fun refresh() {
            serversContainer.removeAllViews()
            scope.launch {
                val servers =
                    withContext(Dispatchers.IO) {
                        try {
                            savedServerDao.getAll()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                currentSavedServers = servers
                if (servers.isEmpty()) {
                    serversContainer.addView(
                        VoidText.body(context, context.getString(R.string.network_saved_servers_empty), sizeSp = 16f, secondary = true),
                    )
                } else {
                    servers.forEach { server ->
                        serversContainer.addView(buildServerRow(server) { refresh() })
                    }
                }
                updateDiscoveredUI()
            }
        }
        refresh()

        if (!hasRunInitialDiscovery && !isScanningDiscovery) {
            hasRunInitialDiscovery = true
            startInlineDiscovery()
        }

        root.addView(wrapInScroll(content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        host.showScreen(root)
    }

    private fun updateDiscoveredUI() {
        val container = discoveryContainer ?: return
        val pBar = discoveryProgressBar
        val sLabel = discoveryStatusLabel

        pBar?.visibility = if (isScanningDiscovery) View.VISIBLE else View.GONE
        sLabel?.text = if (isScanningDiscovery) context.getString(R.string.network_discovery_scanning) else ""

        container.removeAllViews()
        if (discoveredServers.isEmpty()) {
            if (!isScanningDiscovery) {
                container.addView(
                    VoidText.body(context, context.getString(R.string.network_discovery_empty), sizeSp = 16f, secondary = true),
                )
            }
        } else {
            discoveredServers.forEach { item ->
                val isAlreadySaved =
                    currentSavedServers.any { saved ->
                        saved.protocol == item.protocol && saved.host == item.host && (saved.port == item.port || item.port == 0)
                    }
                val matchedSavedServer =
                    currentSavedServers.find { saved ->
                        saved.protocol == item.protocol && saved.host == item.host && (saved.port == item.port || item.port == 0)
                    }
                container.addView(
                    buildDiscoveredServerRow(item, isAlreadySaved, matchedSavedServer),
                )
            }
        }
    }

    private fun startInlineDiscovery() {
        if (isScanningDiscovery) return
        isScanningDiscovery = true
        updateDiscoveredUI()

        scope.launch {
            lockManager?.acquire()
            try {
                val raw =
                    withContext(Dispatchers.IO) {
                        activity.nativeDiscoveryScan(2500)
                    }
                discoveredServers = parseScanResults(raw)
            } catch (e: Exception) {
                // Manter lista atual
            } finally {
                lockManager?.release()
                isScanningDiscovery = false
                if (mode is HomeMode.List) {
                    withContext(Dispatchers.Main) {
                        updateDiscoveredUI()
                    }
                }
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
            val proto =
                try {
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
                path = path,
            )
        }
    }

    private fun buildActionsRow(): View {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        val btnAddSource =
            VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                text = context.getString(R.string.network_btn_add_source)
                setIcon(R.drawable.ic_add)
                textSize = 15f
                setOnClickListener {
                    pendingPrefill = null
                    mode = HomeMode.WizardSelectProtocol
                    renderCurrent()
                }
            }
        row.addView(
            btnAddSource,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = VoidTheme.dpToPx(context, 10f)
            },
        )

        val btnUrl =
            VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = context.getString(R.string.network_tab_url)
                setIcon(R.drawable.ic_link)
                textSize = 14f
                setOnClickListener {
                    mode = HomeMode.Url
                    renderCurrent()
                }
            }
        row.addView(
            btnUrl,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = VoidTheme.dpToPx(context, 10f)
            },
        )

        val btnScan =
            VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = context.getString(R.string.network_discovery_btn_rescan)
                setIcon(R.drawable.ic_search)
                textSize = 14f
                setOnClickListener {
                    startInlineDiscovery()
                }
            }
        row.addView(
            btnScan,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(row)
        }
    }

    private fun buildServerRow(
        server: SavedServer,
        onChanged: () -> Unit,
    ): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }

            val meta = "${context.getString(server.protocol.labelRes)} · ${server.host}:${server.port}"
            addView(
                VoidListRow(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    bind(server.name, meta = meta, showThumbnailSlot = false, iconResId = server.protocol.iconRes)
                    setOnClickListener { connectTo(server) }
                },
            )

            val btnEdit =
                VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                    text = ""
                    setIcon(R.drawable.ic_edit)
                    textSize = 16f
                    minHeight = 0
                    val pad = VoidTheme.dpToPx(context, 12f)
                    setPadding(pad, pad, pad, pad)
                    contentDescription = context.getString(R.string.network_btn_edit)
                    setOnClickListener {
                        if (server.protocol == ServerProtocol.DLNA) {
                            mode = HomeMode.EditDlna(server)
                            renderCurrent()
                            return@setOnClickListener
                        }
                        scope.launch {
                            val password = withContext(Dispatchers.IO) { credentialStore.getPassword(server.id) }
                            val privateKey = withContext(Dispatchers.IO) { credentialStore.getPrivateKey(server.id) }
                            val prefill =
                                ServerPrefill(
                                    host = server.host,
                                    port = server.port,
                                    name = server.name,
                                    path = server.path,
                                    username = server.username,
                                    password = password,
                                    domain = server.domain,
                                    privateKey = privateKey,
                                    isAnonymousOrGuest = server.isGuest,
                                    existingServerId = server.id,
                                    extraJson = server.extraJson,
                                )
                            withContext(Dispatchers.Main) {
                                pendingPrefill = prefill
                                mode = HomeMode.AddForm(server.protocol, editingServerId = server.id)
                                renderCurrent()
                            }
                        }
                    }
                }
            addView(
                btnEdit,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = VoidTheme.dpToPx(context, 8f)
                },
            )

            addView(
                VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                    text = ""
                    setIcon(R.drawable.ic_delete)
                    textSize = 16f
                    minHeight = 0
                    val pad = VoidTheme.dpToPx(context, 12f)
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener {
                        activity.openConfirmDeleteModal(
                            title = context.getString(R.string.network_delete_server_title),
                            message = context.getString(R.string.network_delete_server_message, server.name),
                            onConfirm = {
                                scope.launch(Dispatchers.IO) {
                                    savedServerDao.delete(server.id)
                                    credentialStore.removeCredentials(server.id)
                                    when (server.protocol) {
                                        ServerProtocol.SMB -> activity.nativeSmbClosePooledSessions(server.host, server.port)
                                        ServerProtocol.SFTP -> activity.nativeSftpClosePooledSessions(server.host, server.port)
                                        else -> Unit
                                    }
                                    withContext(Dispatchers.Main) { onChanged() }
                                }
                            },
                        )
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = VoidTheme.dpToPx(context, 8f)
                },
            )
        }

    private fun buildDiscoveredServerRow(
        item: DiscoveredServerItem,
        isAlreadySaved: Boolean,
        matchedSavedServer: SavedServer?,
    ): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }

            val iconRes =
                when (item.protocol) {
                    ServerProtocol.SMB -> R.drawable.ic_storage
                    ServerProtocol.NFS -> R.drawable.ic_storage
                    ServerProtocol.FTP -> R.drawable.ic_broadcast
                    ServerProtocol.SFTP -> R.drawable.ic_lock
                    ServerProtocol.DLNA -> R.drawable.ic_movie
                    ServerProtocol.WEBDAV -> R.drawable.ic_link
                }

            val savedSuffix = if (isAlreadySaved) " · [${context.getString(R.string.network_server_saved_badge)}]" else ""
            val metaText = "[${item.protocol.name}] ${item.host}:${item.port} ${item.path}$savedSuffix".trim()

            addView(
                VoidListRow(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    bind(item.name, meta = metaText, showThumbnailSlot = false, iconResId = iconRes)
                    setOnClickListener {
                        if (isAlreadySaved && matchedSavedServer != null) {
                            connectTo(matchedSavedServer)
                        } else {
                            handleDiscoveredServerClick(item)
                        }
                    }
                },
            )

            val actionBtn =
                VoidButton(context, if (isAlreadySaved) VoidButtonStyle.SECONDARY else VoidButtonStyle.PRIMARY).apply {
                    text =
                        if (isAlreadySaved) {
                            context.getString(R.string.network_server_saved_badge)
                        } else if (item.protocol == ServerProtocol.NFS || item.protocol == ServerProtocol.DLNA) {
                            context.getString(R.string.network_discovery_action_connect)
                        } else {
                            context.getString(R.string.network_discovery_action_configure)
                        }
                    textSize = 14f
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).also {
                            it.marginStart = VoidTheme.dpToPx(context, 8f)
                        }
                    setOnClickListener {
                        if (isAlreadySaved && matchedSavedServer != null) {
                            connectTo(matchedSavedServer)
                        } else {
                            handleDiscoveredServerClick(item)
                        }
                    }
                }
            addView(actionBtn)
        }

    private fun handleDiscoveredServerClick(item: DiscoveredServerItem) {
        when (item.protocol) {
            ServerProtocol.NFS -> {
                val server =
                    SavedServer(
                        name = item.name,
                        protocol = ServerProtocol.NFS,
                        host = item.host,
                        port = item.port,
                        path = item.path.ifEmpty { "/" },
                        isAutoDiscovered = true,
                        lastConnectedAt = System.currentTimeMillis(),
                    )
                scope.launch(Dispatchers.IO) {
                    savedServerDao.insert(server)
                }
                onNavigate(Destination.NetworkNfsFiles(server, ""))
            }
            ServerProtocol.DLNA -> {
                scope.launch {
                    val rawDesc =
                        withContext(Dispatchers.IO) {
                            activity.nativeDlnaGetDevice(item.path)
                        }
                    val parts = rawDesc.split("\t")
                    val friendlyName = if (parts.size >= 2 && parts[0] == "OK" && parts[1].isNotBlank()) parts[1] else item.name
                    val controlUrl = if (parts.size >= 3 && parts[0] == "OK") parts[2] else item.path

                    val server =
                        SavedServer(
                            name = friendlyName,
                            protocol = ServerProtocol.DLNA,
                            host = item.host,
                            port = item.port,
                            path = controlUrl,
                            isAutoDiscovered = true,
                            lastConnectedAt = System.currentTimeMillis(),
                        )
                    withContext(Dispatchers.IO) {
                        savedServerDao.insert(server)
                    }
                    onNavigate(Destination.NetworkDlnaFiles(server, "0", friendlyName))
                }
            }
            ServerProtocol.SMB, ServerProtocol.FTP, ServerProtocol.SFTP, ServerProtocol.WEBDAV -> {
                if (onConfigureDiscoveredServer != null) {
                    onConfigureDiscoveredServer.invoke(item.protocol, item.host, item.port, item.name, item.path)
                } else {
                    openAddForm(
                        item.protocol,
                        ServerPrefill(host = item.host, port = item.port, name = item.name, path = item.path),
                        fromWizard = false,
                    )
                }
            }
        }
    }

    /** Monta a Destination correta por protocolo, buscando credenciais quando necessário. */
    private fun connectTo(server: SavedServer) {
        scope.launch {
            val destination =
                withContext(Dispatchers.IO) {
                    when (server.protocol) {
                        ServerProtocol.SMB ->
                            Destination.NetworkFiles(
                                server.toSmbServer(credentialStore.getPassword(server.id)),
                                "",
                            )
                        ServerProtocol.FTP ->
                            Destination.NetworkFtpFiles(
                                server.toFtpServer(credentialStore.getPassword(server.id)),
                                "",
                            )
                        ServerProtocol.SFTP ->
                            Destination.NetworkSftpFiles(
                                server.toSftpServer(credentialStore.getPassword(server.id), credentialStore.getPrivateKey(server.id)),
                                "",
                            )
                        ServerProtocol.NFS -> Destination.NetworkNfsFiles(server, "")
                        ServerProtocol.WEBDAV -> Destination.NetworkWebdavFiles(server, "")
                        ServerProtocol.DLNA -> Destination.NetworkDlnaFiles(server, "0", server.name)
                    }
                }
            onNavigate(destination)
        }
    }

    // ---- Modo Wizard: seleção de protocolo ----

    private fun renderWizardSelectProtocol() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(context, title = context.getString(R.string.network_wizard_title), onBack = {
                mode = HomeMode.List
                renderCurrent()
            }),
        )

        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

        content.addView(
            VoidText.body(context, context.getString(R.string.network_wizard_subtitle), sizeSp = 16f, secondary = true).apply {
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 16f))
            },
        )

        val protocols =
            listOf(
                ServerProtocol.SMB to R.string.network_protocol_desc_smb,
                ServerProtocol.NFS to R.string.network_protocol_desc_nfs,
                ServerProtocol.FTP to R.string.network_protocol_desc_ftp,
                ServerProtocol.SFTP to R.string.network_protocol_desc_sftp,
                ServerProtocol.WEBDAV to R.string.network_protocol_desc_webdav,
            )

        protocols.forEach { (protocol, descRes) ->
            val row =
                VoidListRow(context).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                            it.bottomMargin = VoidTheme.dpToPx(context, 8f)
                        }
                    bind(
                        title = context.getString(protocol.labelRes),
                        meta = context.getString(descRes),
                        showThumbnailSlot = false,
                        iconResId = protocol.iconRes,
                    )
                    setOnClickListener {
                        openAddForm(protocol, fromWizard = true)
                    }
                }
            content.addView(row)
        }

        root.addView(wrapInScroll(content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        host.showScreen(root)
    }

    // ---- Modo Formulário: cadastrar ou editar servidor de um protocolo específico ----

    private fun renderAddForm(
        protocol: ServerProtocol,
        editingServerId: String? = null,
    ) {
        val builder = addFormBuilders[protocol]
        if (builder == null) {
            mode = HomeMode.List
            renderList()
            return
        }

        val root = VoidPanelChrome.newRoot(context)
        val formTitle =
            if (editingServerId != null) {
                context.getString(R.string.network_edit_server_title)
            } else {
                context.getString(protocol.labelRes)
            }
        root.addView(
            VoidPanelChrome.buildHeader(context, title = formTitle, onBack = { handleBack() }),
        )
        val formView =
            builder(
                {
                    pendingPrefill = null
                    mode = HomeMode.List
                    renderCurrent()
                },
                pendingPrefill,
            )
        root.addView(wrapInScroll(formView), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        host.showScreen(root)
    }

    // ---- Modo Edição de Servidor DLNA (descoberto via UPnP/SSDP) ----

    private fun renderEditDlna(server: SavedServer) {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(context, title = context.getString(R.string.network_dlna_edit_title), onBack = { handleBack() }),
        )

        val form = VoidForm(context)

        val hostInfo = if (server.port > 0) "${server.host}:${server.port}" else server.host
        val addressText =
            VoidText.body(
                context,
                context.getString(R.string.network_dlna_edit_host_info, hostInfo),
                sizeSp = 14f,
                secondary = true,
            ).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 16f))
            }
        form.addView(addressText)

        val fieldName =
            form.field(
                host = host,
                label = context.getString(R.string.network_dlna_form_name_label),
                hint = context.getString(R.string.network_dlna_form_name_hint),
                kind = VoidFieldKind.TEXT,
            ).apply {
                setText(server.name)
            }

        val btnSave =
            VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                text = context.getString(R.string.network_btn_save)
                setIcon(R.drawable.ic_check)
                textSize = 15f
                minHeight = VoidTheme.dpToPx(context, 48f)
                setOnClickListener {
                    val nameText = fieldName.getText().trim()
                    val finalName = if (nameText.isNotEmpty()) nameText else (server.host.ifEmpty { server.name })
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            savedServerDao.update(server.copy(name = finalName))
                        }
                        withContext(Dispatchers.Main) {
                            mode = HomeMode.List
                            renderCurrent()
                        }
                    }
                }
            }

        form.onFormSubmit = { btnSave.performClick() }

        val buttonsRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = VoidTheme.dpToPx(context, 16f) }
            }
        buttonsRow.addView(
            btnSave,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        form.addView(buttonsRow)

        root.addView(wrapInScroll(form), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        host.showScreen(root)
    }

    // ---- Modo Descoberta automática ----

    private fun renderDiscovery() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(context, title = context.getString(R.string.network_tab_discovery), onBack = { handleBack() }),
        )
        // NetworkDiscoveryScreen.buildPage() já retorna um ScrollView próprio — não envolver de novo.
        root.addView(discoveryPageBuilder(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        host.showScreen(root)
    }

    // ---- Modo Reproduzir via URL (não é um "servidor salvo") ----

    private fun renderUrl() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(context, title = context.getString(R.string.network_tab_url), onBack = { handleBack() }),
        )
        // buildUrlPage() já retorna um ScrollView próprio — não envolver de novo.
        root.addView(buildUrlPage(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        host.showScreen(root)
    }

    private fun buildUrlPage(): View {
        val page =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

        val urlInput =
            VoidTextField(
                context = context,
                host = host,
                hint = context.getString(R.string.network_url_hint),
                kind = VoidFieldKind.URI,
                actions = setOf(VoidFieldAction.PASTE, VoidFieldAction.CLEAR, VoidFieldAction.CONTEXT_MENU),
                validator = FieldValidators.url(context.getString(R.string.field_error_invalid_url)),
            )
        page.addView(urlInput)

        val urlStatus =
            VoidText.body(context, "", sizeSp = 16f, secondary = true).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 16f))
            }

        val recentHeader =
            VoidText.title(
                context,
                context.getString(R.string.network_url_recent_header),
                sizeSp = 20f,
            ).apply {
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 8f))
            }

        val recentContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun refreshRecentUrls() {
            recentContainer.removeAllViews()
            val entries = urlHistory.list()
            if (entries.isEmpty()) {
                recentContainer.addView(
                    VoidText.body(context, context.getString(R.string.network_url_recent_empty), sizeSp = 16f, secondary = true),
                )
                return
            }
            entries.forEach { url ->
                val listRow =
                    VoidListRow(context).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
                        bind(
                            context.getString(R.string.network_url_recent_row_format, url).trim(),
                            showThumbnailSlot = false,
                            iconResId = R.drawable.ic_link,
                        )
                        titleView.typeface = VoidTheme.typefaceMono
                        titleView.textSize = 15f
                    }
                listRow.setOnClickListener {
                    urlInput.setText(url)
                    playUrl(url, urlStatus) { refreshRecentUrls() }
                }
                recentContainer.addView(listRow)
            }
        }

        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, VoidTheme.dpToPx(context, 16f), 0, VoidTheme.dpToPx(context, 8f))
            }

        val btnPlay =
            VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                text = context.getString(R.string.network_url_btn_play).trim()
                setIcon(R.drawable.ic_play_arrow)
                textSize = 18f
                setOnClickListener {
                    if (urlInput.validate()) {
                        playUrl(urlInput.getText().trim(), urlStatus) { refreshRecentUrls() }
                    }
                }
            }

        urlInput.onImeDone = { btnPlay.performClick() }

        row.addView(
            btnPlay,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        page.addView(row)
        page.addView(urlStatus)
        page.addView(recentHeader)
        page.addView(recentContainer)

        refreshRecentUrls()
        return wrapInScroll(page)
    }

    /**
     * Valida a URL, adiciona ao histórico, inicia probe HTTP assíncrono para
     * informar se seek vai funcionar, e inicia a reprodução direta.
     */
    private fun playUrl(
        url: String,
        statusView: android.widget.TextView,
        onHistoryChanged: () -> Unit,
    ) {
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            statusView.text = context.getString(R.string.network_url_status_invalid)
            return
        }
        statusView.text = context.getString(R.string.network_url_status_checking)
        urlHistory.add(url)
        onHistoryChanged()

        scope.launch {
            val probeResult = withContext(Dispatchers.IO) { activity.nativeProbeHttpUrl(url) }
            statusView.text = describeProbe(probeResult)
        }

        val source = PlaybackSource.Http(url)
        onPlay(source)
    }

    private fun describeProbe(result: String): String {
        if (result.startsWith("ERROR:")) {
            return context.getString(R.string.common_warning_format, result.removePrefix("ERROR:"))
        }
        val parts = result.split("\t")
        val seekable = parts.getOrNull(1) == "1"
        return if (seekable) {
            context.getString(R.string.network_url_status_seekable)
        } else {
            context.getString(R.string.network_url_status_not_seekable)
        }
    }

    // Scroll wrapper necessário: páginas de rede têm conteúdo mais alto que
    // o canvas da Presentation.
    internal fun wrapInScroll(page: View): View =
        ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(page)
        }
}
