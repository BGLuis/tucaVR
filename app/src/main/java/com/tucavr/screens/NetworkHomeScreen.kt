package com.tucavr.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
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
import com.tucavr.history.isResumable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Landing da seção "Rede".
 *
 * Fase de unificação: em vez de abas por protocolo, mostra uma ÚNICA lista com
 * todos os servidores salvos (`SavedServerDao.getAll()`), de qualquer protocolo.
 * A separação por protocolo só aparece no fluxo de CADASTRAR um novo servidor
 * (fileira de ícones no topo da lista) — reaproveita, sem reescrever, o
 * `buildAddServerForm()` já existente em cada `NetworkXScreen` (SMB/FTP/SFTP/
 * NFS/WebDAV). DLNA não tem formulário manual — só é adicionado via descoberta
 * automática (`NetworkDiscoveryScreen`).
 *
 * Não há Compose/NavHost neste app (View system puro) — o "modo" atual
 * ([HomeMode]) é estado local simples, trocado chamando [host.showScreen] de
 * novo, no mesmo padrão já usado por `browsingServer`/`browsePath` nas telas
 * de protocolo.
 */
class NetworkHomeScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val urlHistory: UrlHistoryStore,
    private val savedServerDao: SavedServerDao,
    private val credentialStore: ServerCredentialStore,
    private val discoveryPageBuilder: () -> View,
    private val smbAddFormBuilder: (onSaved: () -> Unit) -> View,
    private val ftpAddFormBuilder: (onSaved: () -> Unit) -> View,
    private val sftpAddFormBuilder: (onSaved: () -> Unit) -> View,
    private val nfsAddFormBuilder: (onSaved: () -> Unit) -> View,
    private val webdavAddFormBuilder: (onSaved: () -> Unit) -> View,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit
) {

    private sealed class HomeMode {
        object List : HomeMode()
        data class AddForm(val protocol: ServerProtocol) : HomeMode()
        object Discovery : HomeMode()
        object Url : HomeMode()
    }

    private var mode: HomeMode = HomeMode.List

    /** Formulários manuais de cadastro disponíveis — DLNA não entra aqui (só via descoberta). */
    private val addFormBuilders: Map<ServerProtocol, (onSaved: () -> Unit) -> View> = linkedMapOf(
        ServerProtocol.SMB to smbAddFormBuilder,
        ServerProtocol.FTP to ftpAddFormBuilder,
        ServerProtocol.SFTP to sftpAddFormBuilder,
        ServerProtocol.NFS to nfsAddFormBuilder,
        ServerProtocol.WEBDAV to webdavAddFormBuilder
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

    // ---- Modo Lista: todos os servidores salvos, cross-protocolo ----

    private fun renderList() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(context, title = context.getString(R.string.network_title), onBack = { onBack() })
        )

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        content.addView(buildActionsRow())

        content.addView(
            VoidText.title(context, context.getString(R.string.network_saved_servers_header), sizeSp = 20f).apply {
                setPadding(0, VoidTheme.dpToPx(context, 16f), 0, VoidTheme.dpToPx(context, 8f))
            }
        )

        val serversContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(serversContainer)

        fun refresh() {
            serversContainer.removeAllViews()
            scope.launch {
                val servers = withContext(Dispatchers.IO) {
                    try {
                        savedServerDao.getAll()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                if (servers.isEmpty()) {
                    serversContainer.addView(
                        VoidText.body(context, context.getString(R.string.network_saved_servers_empty), sizeSp = 16f, secondary = true)
                    )
                } else {
                    servers.forEach { server ->
                        serversContainer.addView(buildServerRow(server) { refresh() })
                    }
                }
            }
        }
        refresh()

        root.addView(wrapInScroll(content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        host.showScreen(root)
    }

    private fun buildActionsRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun addAction(iconResId: Int, label: String, onClick: () -> Unit) {
            row.addView(
                VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                    text = label
                    setIcon(iconResId)
                    textSize = 14f
                    setOnClickListener { onClick() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = VoidTheme.dpToPx(context, 8f)
                }
            )
        }

        addFormBuilders.keys.forEach { protocol ->
            addAction(protocol.iconRes, context.getString(protocol.labelRes)) { openAddForm(protocol) }
        }
        addAction(R.drawable.ic_search, context.getString(R.string.network_tab_discovery)) {
            mode = HomeMode.Discovery
            renderCurrent()
        }
        addAction(R.drawable.ic_link, context.getString(R.string.network_tab_url)) {
            mode = HomeMode.Url
            renderCurrent()
        }

        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(row)
        }
    }

    private fun buildServerRow(server: SavedServer, onChanged: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }

        val meta = "${context.getString(server.protocol.labelRes)} · ${server.host}:${server.port}"
        addView(
            VoidListRow(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                bind(server.name, meta = meta, showThumbnailSlot = false, iconResId = server.protocol.iconRes)
                setOnClickListener { connectTo(server) }
            }
        )

        addView(
            VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = ""
                setIcon(R.drawable.icon_x)
                textSize = 16f
                minHeight = 0
                val pad = VoidTheme.dpToPx(context, 12f)
                setPadding(pad, pad, pad, pad)
                setOnClickListener {
                    scope.launch(Dispatchers.IO) {
                        savedServerDao.delete(server.id)
                        credentialStore.removeCredentials(server.id)
                        withContext(Dispatchers.Main) { onChanged() }
                    }
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = VoidTheme.dpToPx(context, 8f)
            }
        )
    }

    /** Monta a Destination correta por protocolo, buscando credenciais quando necessário. */
    private fun connectTo(server: SavedServer) {
        scope.launch {
            val destination = withContext(Dispatchers.IO) {
                when (server.protocol) {
                    ServerProtocol.SMB -> Destination.NetworkFiles(
                        server.toSmbServer(credentialStore.getPassword(server.id)), ""
                    )
                    ServerProtocol.FTP -> Destination.NetworkFtpFiles(
                        server.toFtpServer(credentialStore.getPassword(server.id)), ""
                    )
                    ServerProtocol.SFTP -> Destination.NetworkSftpFiles(
                        server.toSftpServer(credentialStore.getPassword(server.id), credentialStore.getPrivateKey(server.id)), ""
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
            VoidPanelChrome.buildHeader(context, title = context.getString(protocol.labelRes), onBack = { handleBack() })
        )
        val formView = builder {
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
            VoidPanelChrome.buildHeader(context, title = context.getString(R.string.network_tab_discovery), onBack = { handleBack() })
        )
        // NetworkDiscoveryScreen.buildPage() já retorna um ScrollView próprio — não envolver de novo.
        root.addView(discoveryPageBuilder(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        host.showScreen(root)
    }

    // ---- Modo Reproduzir via URL (não é um "servidor salvo") ----

    private fun renderUrl() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(context, title = context.getString(R.string.network_tab_url), onBack = { handleBack() })
        )
        // buildUrlPage() já retorna um ScrollView próprio — não envolver de novo.
        root.addView(buildUrlPage(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        host.showScreen(root)
    }

    private fun buildUrlPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val urlInput = VoidTextField(
            context = context,
            host = host,
            hint = context.getString(R.string.network_url_hint),
            kind = VoidFieldKind.URI,
            actions = setOf(VoidFieldAction.PASTE, VoidFieldAction.CLEAR, VoidFieldAction.CONTEXT_MENU),
            validator = FieldValidators.url(context.getString(R.string.field_error_invalid_url))
        )
        page.addView(urlInput)

        val urlStatus = VoidText.body(context, "", sizeSp = 16f, secondary = true).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 16f))
        }

        val recentHeader = VoidText.title(
            context, context.getString(R.string.network_url_recent_header), sizeSp = 20f
        ).apply {
            setPadding(0, VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 8f))
        }

        val recentContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun refreshRecentUrls() {
            recentContainer.removeAllViews()
            val entries = urlHistory.list()
            if (entries.isEmpty()) {
                recentContainer.addView(
                    VoidText.body(context, context.getString(R.string.network_url_recent_empty), sizeSp = 16f, secondary = true)
                )
                return
            }
            entries.forEach { url ->
                val listRow = VoidListRow(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
                    bind(context.getString(R.string.network_url_recent_row_format, url).trim(), showThumbnailSlot = false, iconResId = R.drawable.ic_link)
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

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, VoidTheme.dpToPx(context, 16f), 0, VoidTheme.dpToPx(context, 8f))
        }

        val btnPlay = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
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

        row.addView(btnPlay, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        page.addView(row)
        page.addView(urlStatus)
        page.addView(recentHeader)
        page.addView(recentContainer)

        refreshRecentUrls()
        return wrapInScroll(page)
    }

    /**
     * Valida a URL, adiciona ao histórico, inicia probe HTTP assíncrono para
     * informar se seek vai funcionar, e navega para o Player.
     *
     * T7.1: o probe HEAD-based NÃO bloqueia o play — o usuário pode tocar
     * mesmo sem range requests, só sem poder buscar.
     */
    private fun playUrl(url: String, statusView: android.widget.TextView, onHistoryChanged: () -> Unit) {
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
            onNavigate(Destination.Player(source))
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

    private fun promptResumeOrPlay(source: PlaybackSource, onDecided: (resumeAtMs: Long?) -> Unit) {
        scope.launch {
            val existing = activity.historyTracker.findExisting(source)
            if (existing != null && existing.isResumable()) {
                // Reusar ResumePromptScreen seria ideal; aqui delegamos
                // direto para o caller via onDecided sem mostrar prompt,
                // pois o prompt é responsabilidade de VRPresentation.
                onDecided(existing.positionMs)
            } else {
                onDecided(null)
            }
        }
    }

    // Scroll wrapper necessário: páginas de rede têm conteúdo mais alto que
    // o canvas de 1024×768 da Presentation. Ver wrapNetworkPageInScroll no
    // original para o raciocínio completo.
    internal fun wrapInScroll(page: View): View =
        ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(page)
        }
}
