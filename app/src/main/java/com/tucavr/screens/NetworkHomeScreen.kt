package com.tucavr.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.designsystem.FieldValidators
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidFieldAction
import com.tucavr.designsystem.VoidFieldKind
import com.tucavr.designsystem.VoidListRow
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTextField
import com.tucavr.designsystem.VoidTheme
import com.tucavr.history.isResumable
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
    private val smbAddFormBuilder: (onSaved: () -> Unit) -> View,
    private val ftpAddFormBuilder: (onSaved: () -> Unit) -> View,
    private val sftpAddFormBuilder: (onSaved: () -> Unit) -> View,
    private val nfsAddFormBuilder: (onSaved: () -> Unit) -> View,
    private val webdavAddFormBuilder: (onSaved: () -> Unit) -> View,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit,
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

        data class AddForm(val protocol: ServerProtocol) : HomeMode()

        object Discovery : HomeMode()

        object Url : HomeMode()
    }

    private var mode: HomeMode = HomeMode.List

    private var discoveredServers: List<DiscoveredServerItem> = emptyList()
    private var isScanningDiscovery: Boolean = false

    /** Formulários manuais de cadastro disponíveis — DLNA não entra aqui (só via descoberta). */
    private val addFormBuilders: Map<ServerProtocol, (onSaved: () -> Unit) -> View> =
        linkedMapOf(
            ServerProtocol.SMB to smbAddFormBuilder,
            ServerProtocol.FTP to ftpAddFormBuilder,
            ServerProtocol.SFTP to sftpAddFormBuilder,
            ServerProtocol.NFS to nfsAddFormBuilder,
            ServerProtocol.WEBDAV to webdavAddFormBuilder,
        )

    /** Ponto de entrada externo (navegação vinda de outra tela) — sempre reseta para a lista. */
    fun render() {
        mode = HomeMode.List
        renderCurrent()
    }

    /** Abre o formulário de cadastro daquele protocolo (usado também pela Descoberta, com prefill). */
    fun openAddForm(protocol: ServerProtocol) {
        if (addFormBuilders.containsKey(protocol)) {
            mode = HomeMode.AddForm(protocol)
            renderCurrent()
        }
    }

    /** @return true se consumiu o "Voltar" internamente (voltou pra lista); false = deixa o AppNavigator agir. */
    fun handleBack(): Boolean {
        if (mode is HomeMode.List) return false
        mode = HomeMode.List
        renderCurrent()
        return true
    }

    private fun renderCurrent() {
        when (val m = mode) {
            is HomeMode.List -> renderList()
            is HomeMode.AddForm -> renderAddForm(m.protocol)
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
                    startInlineDiscovery(refreshUI = true)
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
        progressRow.addView(progressBar)

        val statusLabel =
            VoidText.body(
                context,
                if (isScanningDiscovery) context.getString(R.string.network_discovery_scanning) else "",
                sizeSp = 14f,
                secondary = true,
            )
        progressRow.addView(statusLabel)
        content.addView(progressRow)

        val discoveryContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(discoveryContainer)

        var currentSavedServers: List<SavedServer> = emptyList()

        fun updateDiscoveredList() {
            discoveryContainer.removeAllViews()
            if (discoveredServers.isEmpty()) {
                if (!isScanningDiscovery) {
                    discoveryContainer.addView(
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
                    discoveryContainer.addView(
                        buildDiscoveredServerRow(item, isAlreadySaved, matchedSavedServer),
                    )
                }
            }
        }

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
                updateDiscoveredList()
            }
        }
        refresh()

        if (discoveredServers.isEmpty() && !isScanningDiscovery) {
            startInlineDiscovery(refreshUI = true)
        }

        root.addView(wrapInScroll(content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        host.showScreen(root)
    }

    private fun startInlineDiscovery(refreshUI: Boolean = false) {
        if (isScanningDiscovery) return
        isScanningDiscovery = true

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
                if (mode is HomeMode.List && refreshUI) {
                    withContext(Dispatchers.Main) {
                        renderCurrent()
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

        fun addAction(
            iconResId: Int,
            label: String,
            onClick: () -> Unit,
        ) {
            row.addView(
                VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                    text = label
                    setIcon(iconResId)
                    textSize = 14f
                    setOnClickListener { onClick() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = VoidTheme.dpToPx(context, 8f)
                },
            )
        }

        addFormBuilders.keys.forEach { protocol ->
            addAction(protocol.iconRes, context.getString(protocol.labelRes)) { openAddForm(protocol) }
        }
        addAction(R.drawable.ic_link, context.getString(R.string.network_tab_url)) {
            mode = HomeMode.Url
            renderCurrent()
        }
        addAction(R.drawable.ic_search, context.getString(R.string.network_discovery_btn_rescan)) {
            startInlineDiscovery(refreshUI = true)
        }

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
                    openAddForm(item.protocol)
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

    // ---- Modo Formulário: cadastrar servidor de um protocolo específico ----

    private fun renderAddForm(protocol: ServerProtocol) {
        val builder = addFormBuilders[protocol]
        if (builder == null) {
            mode = HomeMode.List
            renderList()
            return
        }

        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(context, title = context.getString(protocol.labelRes), onBack = { handleBack() }),
        )
        val formView =
            builder {
                mode = HomeMode.List
                renderCurrent()
            }
        root.addView(wrapInScroll(formView), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
        promptResumeOrPlay(source) { resumeAtMs ->
            activity.playUrl(url, resumeAtMs)
            activity.requestUiPanelHidden()
        }
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

    private fun promptResumeOrPlay(
        source: PlaybackSource,
        onDecided: (resumeAtMs: Long?) -> Unit,
    ) {
        scope.launch {
            val existing = activity.historyTracker.findExisting(source)
            if (existing != null && existing.isResumable()) {
                onDecided(existing.positionMs)
            } else {
                onDecided(null)
            }
        }
    }

    internal fun wrapInScroll(page: View): View =
        ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(page)
        }
}
