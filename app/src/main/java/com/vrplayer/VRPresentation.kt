package com.vrplayer

import android.app.Presentation
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidListRow
import com.vrplayer.designsystem.VoidPanelChrome
import com.vrplayer.designsystem.VoidTabRow
import com.vrplayer.designsystem.VoidText
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.filebrowser.DirectoryLister
import com.vrplayer.filebrowser.DirectoryNavigator
import com.vrplayer.filebrowser.MediaEntry
import com.vrplayer.filebrowser.MediaType
import com.vrplayer.filebrowser.SortBy
import com.vrplayer.filebrowser.ThumbnailGenerator
import com.vrplayer.filebrowser.mediaTypeForExtension
import com.vrplayer.filebrowser.sortMediaEntries
import com.vrplayer.history.HistorySourceType
import com.vrplayer.history.PlaybackHistory
import com.vrplayer.history.formatDurationMs
import com.vrplayer.history.isResumable
import com.vrplayer.history.watchedPercent
import com.vrplayer.navigation.AppNavigator
import com.vrplayer.navigation.Destination
import com.vrplayer.navigation.PlaybackSource
import com.vrplayer.network.FtpCredentialStore
import com.vrplayer.network.FtpServer
import com.vrplayer.network.SftpCredentialStore
import com.vrplayer.network.SftpServer
import com.vrplayer.network.SmbCredentialStore
import com.vrplayer.network.SmbServer
import com.vrplayer.network.UrlHistoryStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Fase 2 do redesign "Void" (ver `com.vrplayer.navigation.Destination` pro
 * contexto completo): este e o unico quad/painel de UI que sobra no espaco
 * VR alem da tela de video e do painel de Controles — hospeda o fluxo
 * completo Home -> (Arquivos locais | Rede) -> Player. Antes da Fase 2 a
 * secao "Rede" abria num segundo quad/VirtualDisplay independente
 * (`NetworkPresentation`, agora removida); a logica daquele painel (abas
 * URL/SMB, `SmbCredentialStore`, `UrlHistoryStore`, navegacao de
 * diretorio SMB) foi movida pra dentro deste, como mais um conjunto de telas
 * do mesmo [AppNavigator] — ver `renderNetworkHome()`/`renderNetworkFiles()`
 * abaixo.
 *
 * A navegacao entre essas telas e conduzida por [AppNavigator] (Kotlin puro,
 * testavel sem Android — ver esse arquivo). O botao fisico B/Y continua, por
 * enquanto, so alternando a VISIBILIDADE do quad inteiro (comportamento
 * nativo inalterado); o botao "Voltar" Void desenhado dentro do painel e
 * quem efetivamente anda pelo back-stack do [AppNavigator]. Unificar as duas
 * coisas (B/Y = sempre "voltar" de verdade) fica pra uma fase futura.
 */
class VRPresentation(
    outerContext: Context,
    display: Display,
    // Mesma armadilha documentada em VRControlsPresentation: `context`
    // (herdado de Presentation/Dialog) NAO e a Activity real, e um
    // ContextThemeWrapper derivado do display-context. Guardamos a Activity
    // de verdade para poder chamar nativeX()/playFile()/playUrl() etc.
    private val activity: VRActivity
) : Presentation(outerContext, display) {

    private val appNav = AppNavigator()

    // Estado de navegacao dentro dos arquivos locais (T5): reaproveitado tal
    // e qual existia antes — so quem desenha a UI em cima dele mudou. O
    // back-stack "de app" (Home/Player/etc, no AppNavigator acima) e
    // deliberadamente um mecanismo separado deste (drill-down de diretorio):
    // ver `handleBack()`.
    private val dirNavigator = DirectoryNavigator(android.os.Environment.getExternalStorageDirectory())

    // Estado equivalente para a navegacao de diretorios dentro de um share
    // SMB (ex-`NetworkPresentation.browsingServer`/`browsePath`): mesmo
    // padrao do `dirNavigator` acima — drill-down por subpasta NAO empilha
    // um novo [Destination.NetworkFiles] no [AppNavigator]; so muda esse
    // estado local e re-renderiza. "Voltar" na raiz do share e que volta pra
    // tela anterior (lista de servidores), via `handleBack()`.
    private var networkBrowsingServer: SmbServer? = null
    private var networkBrowsePath: String = ""

    // T6.4: mesmo padrao acima, estado equivalente para navegacao de
    // diretorio FTP (drill-down por subpasta nao empilha um novo
    // [Destination.NetworkFtpFiles], so muda esse estado local).
    private var networkBrowsingFtpServer: FtpServer? = null
    private var networkBrowseFtpPath: String = ""

    // Mesmo padrao acima, para navegacao de diretorio SFTP.
    private var networkBrowsingSftpServer: SftpServer? = null
    private var networkBrowseSftpPath: String = ""

    // Qual aba (URL=0 / SMB=1 / FTP=2 / SFTP=3) estava ativa da ultima vez
    // que o usuario esteve em Destination.NetworkHome — preservado entre
    // navegacoes (Home -> Rede -> Arquivos -> Voltar -> Rede continua na
    // mesma aba).
    private var networkActiveTabIndex = 0

    private lateinit var credentialStore: SmbCredentialStore
    private lateinit var ftpCredentialStore: FtpCredentialStore
    private lateinit var sftpCredentialStore: SftpCredentialStore
    private lateinit var urlHistory: UrlHistoryStore

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var screenHost: FrameLayout
    private var localFileAdapter: FileAdapter? = null

    // ==================== Teclado (ver secao "TECLADO NATIVO" apos buildVoidEditText) ====================
    // So precisa rastrear qual EditText esta com foco pra saber quando
    // esconder o teclado real ao trocar de tela (`showScreen`) — o teclado em
    // si (real, do sistema/Meta Quest) e inteiramente gerenciado por
    // `activity.showNativeKeyboardFor`/`hideNativeKeyboard` (VRActivity.kt),
    // que vive FORA desta Presentation (ver essa secao para o porque).
    private var keyboardTarget: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialStore = SmbCredentialStore(activity)
        ftpCredentialStore = FtpCredentialStore(activity)
        sftpCredentialStore = SftpCredentialStore(activity)
        urlHistory = UrlHistoryStore(activity)
        screenHost = FrameLayout(context).apply {
            setBackgroundColor(VoidTheme.colorBackground)
        }
        setContentView(screenHost)
        render()
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    /** Chamado por `VRActivity.onResume()` — recarrega a listagem atual, caso
     * o usuario tenha adicionado/removido arquivos enquanto o app estava em
     * background. No-op em qualquer tela que nao seja a listagem local. */
    fun loadFiles() {
        if (appNav.current is Destination.LocalFiles) {
            loadLocalFiles()
        }
    }

    // ==================== Maquina de telas ====================

    private fun render() {
        when (val destination = appNav.current) {
            is Destination.Home -> renderHome()
            is Destination.LocalFiles -> renderLocalFiles()
            is Destination.NetworkHome -> renderNetworkHome()
            is Destination.NetworkFiles -> renderNetworkFiles(destination.server)
            is Destination.NetworkFtpFiles -> renderNetworkFtpFiles(destination.server)
            is Destination.NetworkSftpFiles -> renderNetworkSftpFiles(destination.server)
            is Destination.ContinueWatching -> renderContinueWatching()
            is Destination.Player -> renderPlayer(destination.source)
        }
    }

    private fun navigateTo(destination: Destination) {
        appNav.navigateTo(destination)
        render()
    }

    /**
     * Unico ponto de "Voltar" do painel. Trata a listagem local e a
     * navegacao de diretorios SMB como casos especiais: se ainda da pra subir
     * de nivel (`dirNavigator`/`networkBrowsePath`), sobe um nivel SEM sair
     * da tela nem tocar no back-stack do [AppNavigator]; so quando ja estamos
     * na raiz que "Voltar" volta pra tela anterior. Isso reproduz a UX de
     * qualquer explorador de arquivos ("voltar" sobe pasta por pasta antes de
     * sair do app-de-arquivos), enquanto ainda garante que apertar "Voltar" o
     * suficiente sempre termina no Home.
     */
    private fun handleBack() {
        if (appNav.current is Destination.LocalFiles && dirNavigator.canGoBack()) {
            dirNavigator.goBack()
            renderLocalFiles()
            return
        }
        val currentNetworkFiles = appNav.current as? Destination.NetworkFiles
        if (currentNetworkFiles != null && networkBrowsePath.isNotEmpty()) {
            networkBrowsePath = networkBrowsePath.substringBeforeLast('/', missingDelimiterValue = "")
            renderNetworkFiles(currentNetworkFiles.server)
            return
        }
        val currentNetworkFtpFiles = appNav.current as? Destination.NetworkFtpFiles
        if (currentNetworkFtpFiles != null && networkBrowseFtpPath.isNotEmpty()) {
            networkBrowseFtpPath = networkBrowseFtpPath.substringBeforeLast('/', missingDelimiterValue = "")
            renderNetworkFtpFiles(currentNetworkFtpFiles.server)
            return
        }
        val currentNetworkSftpFiles = appNav.current as? Destination.NetworkSftpFiles
        if (currentNetworkSftpFiles != null && networkBrowseSftpPath.isNotEmpty()) {
            networkBrowseSftpPath = networkBrowseSftpPath.substringBeforeLast('/', missingDelimiterValue = "")
            renderNetworkSftpFiles(currentNetworkSftpFiles.server)
            return
        }
        if (appNav.back()) {
            render()
        }
    }

    private fun showScreen(view: View) {
        // O EditText focado (se algum) vive dentro da tela que esta prestes a
        // ser destruida por `removeAllViews()` — sem isto o teclado real
        // ficaria aberto apontando pra um campo ja desanexado.
        keyboardTarget = null
        activity.hideNativeKeyboard()
        screenHost.removeAllViews()
        screenHost.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    // ==================== HOME ====================

    private fun renderHome() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(VoidPanelChrome.buildHeader(context, title = context.getString(R.string.home_title)))

        val bigButtonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 20f) }

        val btnLocal = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.home_btn_local_files)
            textSize = 24f
            setOnClickListener {
                navigateTo(Destination.LocalFiles(dirNavigator.currentPath.absolutePath))
            }
        }
        val btnNetwork = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.home_btn_network)
            textSize = 24f
            setOnClickListener { navigateTo(Destination.NetworkHome) }
        }
        // T9.4: historico de reproducao implementado (com.vrplayer.history) —
        // botao habilitado de verdade agora. A tela em si (renderContinueWatching)
        // lida com a lista vazia mostrando uma mensagem, entao nao ha
        // necessidade de consultar o Room aqui so para decidir se o botao
        // deve estar habilitado.
        val btnContinueWatching = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.home_btn_continue_watching)
            textSize = 20f
            setOnClickListener { navigateTo(Destination.ContinueWatching) }
        }

        root.addView(btnLocal, bigButtonParams)
        root.addView(btnNetwork, bigButtonParams)
        root.addView(btnContinueWatching, bigButtonParams)

        showScreen(root)
    }

    // ==================== ARQUIVOS LOCAIS (T5) ====================

    private fun renderLocalFiles() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = context.getString(R.string.browser_title_local_files),
                subtitle = dirNavigator.currentPath.absolutePath,
                onBack = { handleBack() }
            )
        )

        val recycler = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            layoutManager = LinearLayoutManager(context)
        }
        val adapter = FileAdapter(
            context = context,
            scope = scope,
            onUpClick = { handleBack() },
            onDirectoryClick = { dir ->
                dirNavigator.enter(dir)
                renderLocalFiles()
            },
            onVideoClick = { entry -> playLocalVideo(entry) }
        )
        localFileAdapter = adapter
        recycler.adapter = adapter
        root.addView(recycler)

        showScreen(root)
        loadLocalFiles()
    }

    private fun loadLocalFiles() {
        val adapter = localFileAdapter ?: return
        val dir = dirNavigator.currentPath
        val showUp = dirNavigator.canGoBack()
        scope.launch {
            val entries = DirectoryLister.listMedia(dir)
                .filter { it.type == MediaType.DIRECTORY || it.type == MediaType.VIDEO }
            val sorted = sortMediaEntries(entries, SortBy.NAME)
            adapter.submit(sorted, showUp)
        }
    }

    private fun playLocalVideo(entry: MediaEntry) {
        val source = PlaybackSource.LocalFile(entry.path, entry.sizeBytes)
        promptResumeOrPlay(source) { resumeAtMs ->
            activity.playFile(entry.path, entry.sizeBytes, resumeAtMs)
            navigateTo(Destination.Player(source))
        }
    }

    // ==================== REDE: LANDING (abas URL / SMB) ====================
    //
    // Fase 2: esta tela substitui o antigo painel/quad `NetworkPresentation`
    // (removido). A logica de estado (credenciais, historico de URL,
    // navegacao de diretorio SMB) e a MESMA de antes, so a casca visual (Void
    // + este painel unico) mudou. Ver `SmbCredentialStore`/`UrlHistoryStore`.

    private fun renderNetworkHome() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(context, title = context.getString(R.string.network_title), onBack = { handleBack() })
        )

        val pageContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val urlPage = buildNetworkUrlPage()
        val smbPage = buildNetworkSmbPage()
        val ftpPage = buildNetworkFtpPage()
        val sftpPage = buildNetworkSftpPage()
        val pages = listOf(urlPage, smbPage, ftpPage, sftpPage)
        pages.forEachIndexed { index, page ->
            page.visibility = if (index == networkActiveTabIndex) View.VISIBLE else View.GONE
            pageContainer.addView(page)
        }

        val tabRow = VoidTabRow(
            context,
            listOf(
                context.getString(R.string.network_tab_url),
                context.getString(R.string.network_tab_smb),
                context.getString(R.string.network_tab_ftp),
                context.getString(R.string.network_tab_sftp)
            )
        ) { index ->
            networkActiveTabIndex = index
            pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        }
        tabRow.setActiveIndex(networkActiveTabIndex, notify = false)
        tabRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

        root.addView(tabRow)
        root.addView(pageContainer)

        showScreen(root)
    }

    // ---------- Aba URL (ex-T7.3, movida de NetworkPresentation) ----------

    private fun buildNetworkUrlPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val urlInput = buildVoidEditText(context.getString(R.string.network_url_hint)).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        page.addView(urlInput)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, VoidTheme.dpToPx(context, 16f), 0, VoidTheme.dpToPx(context, 8f))
        }

        val urlStatus = VoidText.body(context, "", sizeSp = 16f, secondary = true).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 16f))
        }

        val recentHeader = VoidText.title(context, context.getString(R.string.network_url_recent_header), sizeSp = 20f).apply {
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
                    bind(context.getString(R.string.network_url_recent_row_format, url), showThumbnailSlot = false)
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

        val btnPaste = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.network_url_btn_paste)
            textSize = 18f
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(activity).toString()
                    urlInput.setText(text)
                    urlInput.setSelection(text.length)
                }
            }
        }
        val btnPlay = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.network_url_btn_play)
            textSize = 18f
            setOnClickListener {
                playUrl(urlInput.text.toString().trim(), urlStatus) { refreshRecentUrls() }
            }
        }
        val btnMargin = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = VoidTheme.dpToPx(context, 12f) }
        row.addView(btnPaste, btnMargin)
        row.addView(btnPlay)

        page.addView(row)
        page.addView(urlStatus)
        page.addView(recentHeader)
        page.addView(recentContainer)

        refreshRecentUrls()
        return page
    }

    private fun playUrl(url: String, statusView: android.widget.TextView, onHistoryChanged: () -> Unit) {
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            statusView.text = context.getString(R.string.network_url_status_invalid)
            return
        }
        statusView.text = context.getString(R.string.network_url_status_checking)
        urlHistory.add(url)
        onHistoryChanged()

        // T7.1: probe HEAD-based ANTES de tocar, so para avisar o usuario se
        // seek nao vai funcionar. NAO bloqueia o play — o usuario pode tocar
        // mesmo sem range requests, so sem poder buscar.
        scope.launch {
            val probeResult = withContext(Dispatchers.IO) { activity.nativeProbeHttpUrl(url) }
            statusView.text = describeProbe(probeResult)
        }

        val source = PlaybackSource.Http(url)
        promptResumeOrPlay(source) { resumeAtMs ->
            activity.playUrl(url, resumeAtMs)
            navigateTo(Destination.Player(source))
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

    // ---------- Aba SMB: servidores salvos (ex-T6.4, movida de NetworkPresentation) ----------

    private fun buildNetworkSmbPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = VoidText.title(context, context.getString(R.string.network_smb_saved_servers_header), sizeSp = 20f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
        }
        page.addView(header)

        val serversContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(serversContainer)

        fun refreshServerList() {
            serversContainer.removeAllViews()
            val servers = credentialStore.list()
            if (servers.isEmpty()) {
                serversContainer.addView(
                    VoidText.body(context, context.getString(R.string.network_smb_empty), sizeSp = 16f, secondary = true)
                )
                return
            }
            servers.forEach { server ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f))
                }
                val label = VoidListRow(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    bind(
                        context.getString(R.string.network_smb_row_label_format, server.name),
                        meta = if (server.isGuest) context.getString(R.string.network_smb_guest_label) else server.username,
                        showThumbnailSlot = false
                    )
                    setOnClickListener {
                        networkBrowsingServer = server
                        networkBrowsePath = ""
                        navigateTo(Destination.NetworkFiles(server, ""))
                    }
                }
                val btnRemove = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                    text = "✕"
                    textSize = 16f
                    minHeight = 0
                    val pad = VoidTheme.dpToPx(context, 12f)
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener {
                        credentialStore.remove(server.id)
                        refreshServerList()
                    }
                }
                row.addView(label)
                row.addView(btnRemove, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = VoidTheme.dpToPx(context, 8f) })
                serversContainer.addView(row)
            }
        }

        val addForm = buildAddServerForm { refreshServerList() }
        addForm.visibility = View.GONE

        val btnAddServer = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.network_smb_btn_add_server)
            textSize = 18f
            setOnClickListener { addForm.visibility = if (addForm.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        page.addView(btnAddServer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 8f) })

        page.addView(addForm)

        refreshServerList()
        return page
    }

    /**
     * @param onSaved chamado apos salvar com sucesso (pra quem construiu o
     * formulario poder atualizar a lista de servidores exibida).
     */
    private fun buildAddServerForm(onSaved: () -> Unit): LinearLayout {
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(VoidTheme.colorSurface)
            val pad = VoidTheme.dpToPx(context, 16f)
            setPadding(pad, pad, pad, pad)
        }

        fun addLabeled(text: String, field: EditText) {
            form.addView(VoidText.mono(context, text, sizeSp = 13f).apply {
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, 0)
            })
            form.addView(field)
            form.addView(buildPasteButton(field), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = VoidTheme.dpToPx(context, 4f) })
        }

        val formHost = buildVoidEditText("192.168.1.10")
        addLabeled(context.getString(R.string.network_smb_form_host_label), formHost)

        val formPort = buildVoidEditText("445").apply {
            setText("445")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        addLabeled(context.getString(R.string.network_smb_form_port_label), formPort)

        val formShare = buildVoidEditText(context.getString(R.string.network_smb_form_share_hint))
        addLabeled(context.getString(R.string.network_smb_form_share_label), formShare)

        val formGuest = CheckBox(context).apply {
            text = context.getString(R.string.network_smb_form_guest_checkbox)
            textSize = 16f
            setTextColor(VoidTheme.colorText)
        }
        form.addView(formGuest)

        val formUser = buildVoidEditText(context.getString(R.string.network_smb_form_user_hint))
        addLabeled(context.getString(R.string.network_smb_form_user_label), formUser)

        val formPass = buildVoidEditText(context.getString(R.string.network_smb_form_pass_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        addLabeled(context.getString(R.string.network_smb_form_pass_label), formPass)

        val formDomain = buildVoidEditText(context.getString(R.string.network_smb_form_domain_hint))
        addLabeled(context.getString(R.string.network_smb_form_domain_label), formDomain)

        formGuest.setOnCheckedChangeListener { _, checked ->
            formUser.isEnabled = !checked
            formPass.isEnabled = !checked
            if (checked) {
                formUser.setText("")
                formPass.setText("")
            }
        }

        val formStatus = VoidText.body(context, "", sizeSp = 14f, secondary = true).apply {
            setPadding(0, VoidTheme.dpToPx(context, 12f), 0, VoidTheme.dpToPx(context, 8f))
        }
        form.addView(formStatus)

        val btnTestSave = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.network_smb_btn_test_save)
            textSize = 18f
            setOnClickListener {
                testAndSaveServer(
                    host = formHost.text.toString().trim(),
                    port = formPort.text.toString().toIntOrNull() ?: 445,
                    share = formShare.text.toString().trim(),
                    guest = formGuest.isChecked,
                    username = formUser.text.toString(),
                    password = formPass.text.toString(),
                    domain = formDomain.text.toString().trim(),
                    statusView = formStatus,
                    onSaved = {
                        onSaved()
                        form.visibility = View.GONE
                    }
                )
            }
        }
        form.addView(btnTestSave)

        return form
    }

    /**
     * Conecta de verdade (via `smb_list_shares`, T6.1) antes de salvar — so
     * persiste a credencial se a conexao/autenticacao funcionarem. Chamada
     * BLOQUEANTE do lado Rust, por isso roda em `Dispatchers.IO`; a UI thread
     * (`Dispatchers.Main`, aqui) so atualiza o texto de status.
     */
    private fun testAndSaveServer(
        host: String,
        port: Int,
        share: String,
        guest: Boolean,
        username: String,
        password: String,
        domain: String,
        statusView: android.widget.TextView,
        onSaved: () -> Unit
    ) {
        val effectiveUsername = if (guest) "" else username
        val effectivePassword = if (guest) "" else password

        if (host.isEmpty()) {
            statusView.text = context.getString(R.string.network_smb_form_status_host_required)
            return
        }

        statusView.text = context.getString(R.string.network_smb_form_status_connecting)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeSmbListShares(host, port, effectiveUsername, effectivePassword, domain)
            }
            if (result.startsWith("ERROR:")) {
                statusView.text = context.getString(
                    R.string.network_smb_form_status_error_format, result.removePrefix("ERROR:")
                )
                return@launch
            }

            val shares = result.split("\n").filter { it.isNotBlank() }
            // T8.5 / cuidados do doc: uso real de <plurals> (nao forcado) —
            // "N share(s) encontrado(s))" varia genuinamente em contagem.
            statusView.text = context.resources.getQuantityString(
                R.plurals.network_smb_form_status_connected, shares.size, shares.size
            )

            val server = SmbServer(
                id = credentialStore.newId(),
                name = if (share.isNotEmpty()) "$host/$share" else host,
                host = host,
                port = port,
                share = share.ifEmpty { shares.firstOrNull() ?: "" },
                username = effectiveUsername,
                password = effectivePassword,
                domain = domain
            )
            credentialStore.save(server)
            onSaved()
        }
    }

    // ==================== REDE: NAVEGACAO DE DIRETORIO SMB ====================

    private fun renderNetworkFiles(server: SmbServer) {
        networkBrowsingServer = server
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = server.name,
                subtitle = "${server.share}/$networkBrowsePath",
                onBack = { handleBack() }
            )
        )

        val entriesContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(entriesContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroller)

        showScreen(root)
        loadNetworkDirectory(server, entriesContainer)
    }

    // So diretorios e arquivos de video aparecem na navegacao de rede — o
    // usuario nao consegue abrir aqui nada alem de video, entao listar o
    // resto so polui a tela (mesmo criterio do DirectoryLister local).
    private fun isVisibleNetworkEntry(name: String, isDir: Boolean): Boolean {
        return isDir || mediaTypeForExtension(name.substringAfterLast('.', "")) == MediaType.VIDEO
    }

    private fun loadNetworkDirectory(server: SmbServer, entriesContainer: LinearLayout) {
        entriesContainer.removeAllViews()
        entriesContainer.addView(
            VoidText.body(context, context.getString(R.string.network_files_loading), sizeSp = 16f, secondary = true)
        )

        val requestedPath = networkBrowsePath
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeSmbListDirectory(
                    server.host, server.port, server.username, server.password, server.domain,
                    server.share, requestedPath
                )
            }
            // Usuario pode ter navegado pra outro nivel/servidor enquanto a
            // chamada de rede estava em voo — descarta resultado obsoleto.
            if (networkBrowsingServer != server || networkBrowsePath != requestedPath) {
                return@launch
            }
            entriesContainer.removeAllViews()

            if (result.startsWith("ERROR:")) {
                entriesContainer.addView(
                    VoidText.body(
                        context,
                        context.getString(R.string.common_warning_format, result.removePrefix("ERROR:")),
                        sizeSp = 16f
                    )
                )
                return@launch
            }

            val lines = result.split("\n")
                .filter { it.isNotBlank() }
                .filter { line ->
                    val parts = line.split("\t")
                    val name = parts.getOrNull(0) ?: return@filter false
                    isVisibleNetworkEntry(name, isDir = parts.getOrNull(1) == "1")
                }
            if (lines.isEmpty()) {
                entriesContainer.addView(
                    VoidText.body(context, context.getString(R.string.network_files_empty), sizeSp = 16f, secondary = true)
                )
                return@launch
            }

            lines.forEach { line ->
                // T9: 3o campo (tamanho, sempre presente na resposta real do
                // Rust — ver `rust/bridge/src/lib.rs::smb_list_directory`,
                // formato "nome\t{0|1}\ttamanho") era ignorado ate agora;
                // agora usado para compor a chave estavel do historico (ver
                // aviso do doc, secao 9, "URI estabilidade").
                val parts = line.split("\t")
                val name = parts.getOrElse(0) { return@forEach }
                val isDir = parts.getOrNull(1) == "1"
                val sizeBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val row = VoidListRow(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
                    bind(
                        if (isDir) {
                            context.getString(R.string.common_row_dir_format, name)
                        } else {
                            context.getString(R.string.common_row_video_format, name)
                        },
                        showThumbnailSlot = false
                    )
                    setOnClickListener {
                        val childPath = if (requestedPath.isEmpty()) name else "$requestedPath/$name"
                        if (isDir) {
                            networkBrowsePath = childPath
                            renderNetworkFiles(server)
                        } else {
                            val source = PlaybackSource.Smb(server, childPath, sizeBytes)
                            promptResumeOrPlay(source) { resumeAtMs ->
                                activity.playSmb(server, childPath, sizeBytes, resumeAtMs)
                                navigateTo(Destination.Player(source))
                            }
                        }
                    }
                }
                entriesContainer.addView(row)
            }
        }
    }

    // ---------- Aba FTP: servidores salvos (T6.4) ----------
    // Espelha buildNetworkSmbPage/buildAddServerForm/testAndSaveServer acima,
    // com os campos que FTP realmente tem (sem share/dominio; "anonimo" no
    // lugar de "guest").

    private fun buildNetworkFtpPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = VoidText.title(context, context.getString(R.string.network_ftp_saved_servers_header), sizeSp = 20f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
        }
        page.addView(header)

        val serversContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(serversContainer)

        fun refreshServerList() {
            serversContainer.removeAllViews()
            val servers = ftpCredentialStore.list()
            if (servers.isEmpty()) {
                serversContainer.addView(
                    VoidText.body(context, context.getString(R.string.network_ftp_empty), sizeSp = 16f, secondary = true)
                )
                return
            }
            servers.forEach { server ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f))
                }
                val label = VoidListRow(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    bind(
                        context.getString(R.string.network_ftp_row_label_format, server.name),
                        meta = if (server.isAnonymous) context.getString(R.string.network_ftp_anonymous_label) else server.username,
                        showThumbnailSlot = false
                    )
                    setOnClickListener {
                        networkBrowsingFtpServer = server
                        networkBrowseFtpPath = ""
                        navigateTo(Destination.NetworkFtpFiles(server, ""))
                    }
                }
                val btnRemove = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                    text = "✕"
                    textSize = 16f
                    minHeight = 0
                    val pad = VoidTheme.dpToPx(context, 12f)
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener {
                        ftpCredentialStore.remove(server.id)
                        refreshServerList()
                    }
                }
                row.addView(label)
                row.addView(btnRemove, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = VoidTheme.dpToPx(context, 8f) })
                serversContainer.addView(row)
            }
        }

        val addForm = buildAddFtpServerForm { refreshServerList() }
        addForm.visibility = View.GONE

        val btnAddServer = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.network_ftp_btn_add_server)
            textSize = 18f
            setOnClickListener { addForm.visibility = if (addForm.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        page.addView(btnAddServer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 8f) })

        page.addView(addForm)

        refreshServerList()
        return page
    }

    private fun buildAddFtpServerForm(onSaved: () -> Unit): LinearLayout {
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(VoidTheme.colorSurface)
            val pad = VoidTheme.dpToPx(context, 16f)
            setPadding(pad, pad, pad, pad)
        }

        fun addLabeled(text: String, field: EditText) {
            form.addView(VoidText.mono(context, text, sizeSp = 13f).apply {
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, 0)
            })
            form.addView(field)
            form.addView(buildPasteButton(field), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = VoidTheme.dpToPx(context, 4f) })
        }

        val formHost = buildVoidEditText("192.168.1.10")
        addLabeled(context.getString(R.string.network_ftp_form_host_label), formHost)

        val formPort = buildVoidEditText("21").apply {
            setText("21")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        addLabeled(context.getString(R.string.network_ftp_form_port_label), formPort)

        val formAnonymous = CheckBox(context).apply {
            text = context.getString(R.string.network_ftp_form_anonymous_checkbox)
            textSize = 16f
            setTextColor(VoidTheme.colorText)
        }
        form.addView(formAnonymous)

        val formUser = buildVoidEditText(context.getString(R.string.network_ftp_form_user_hint))
        addLabeled(context.getString(R.string.network_ftp_form_user_label), formUser)

        val formPass = buildVoidEditText(context.getString(R.string.network_ftp_form_pass_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        addLabeled(context.getString(R.string.network_ftp_form_pass_label), formPass)

        formAnonymous.setOnCheckedChangeListener { _, checked ->
            formUser.isEnabled = !checked
            formPass.isEnabled = !checked
            if (checked) {
                formUser.setText("")
                formPass.setText("")
            }
        }

        val formStatus = VoidText.body(context, "", sizeSp = 14f, secondary = true).apply {
            setPadding(0, VoidTheme.dpToPx(context, 12f), 0, VoidTheme.dpToPx(context, 8f))
        }
        form.addView(formStatus)

        val btnTestSave = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.network_ftp_btn_test_save)
            textSize = 18f
            setOnClickListener {
                testAndSaveFtpServer(
                    host = formHost.text.toString().trim(),
                    port = formPort.text.toString().toIntOrNull() ?: 21,
                    anonymous = formAnonymous.isChecked,
                    username = formUser.text.toString(),
                    password = formPass.text.toString(),
                    statusView = formStatus,
                    onSaved = {
                        onSaved()
                        form.visibility = View.GONE
                    }
                )
            }
        }
        form.addView(btnTestSave)

        return form
    }

    /**
     * Conecta de verdade (via `ftp_list_directory`, T6.1) antes de salvar —
     * mesmo raciocinio de `testAndSaveServer` (SMB): so persiste a
     * credencial se autenticacao/listagem funcionarem.
     */
    private fun testAndSaveFtpServer(
        host: String,
        port: Int,
        anonymous: Boolean,
        username: String,
        password: String,
        statusView: android.widget.TextView,
        onSaved: () -> Unit
    ) {
        val effectiveUsername = if (anonymous) "" else username
        val effectivePassword = if (anonymous) "" else password

        if (host.isEmpty()) {
            statusView.text = context.getString(R.string.network_ftp_form_status_host_required)
            return
        }

        statusView.text = context.getString(R.string.network_ftp_form_status_connecting)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeFtpListDirectory(host, port, effectiveUsername, effectivePassword, "")
            }
            if (result.startsWith("ERROR:")) {
                statusView.text = context.getString(
                    R.string.network_ftp_form_status_error_format, result.removePrefix("ERROR:")
                )
                return@launch
            }

            statusView.text = context.getString(R.string.network_ftp_form_status_connected)

            val server = FtpServer(
                id = ftpCredentialStore.newId(),
                name = host,
                host = host,
                port = port,
                username = effectiveUsername,
                password = effectivePassword
            )
            ftpCredentialStore.save(server)
            onSaved()
        }
    }

    // ==================== REDE: NAVEGACAO DE DIRETORIO FTP ====================

    private fun renderNetworkFtpFiles(server: FtpServer) {
        networkBrowsingFtpServer = server
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = server.name,
                subtitle = "/$networkBrowseFtpPath",
                onBack = { handleBack() }
            )
        )

        val entriesContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(entriesContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroller)

        showScreen(root)
        loadNetworkFtpDirectory(server, entriesContainer)
    }

    private fun loadNetworkFtpDirectory(server: FtpServer, entriesContainer: LinearLayout) {
        entriesContainer.removeAllViews()
        entriesContainer.addView(
            VoidText.body(context, context.getString(R.string.network_files_loading), sizeSp = 16f, secondary = true)
        )

        val requestedPath = networkBrowseFtpPath
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeFtpListDirectory(server.host, server.port, server.username, server.password, requestedPath)
            }
            if (networkBrowsingFtpServer != server || networkBrowseFtpPath != requestedPath) {
                return@launch
            }
            entriesContainer.removeAllViews()

            if (result.startsWith("ERROR:")) {
                entriesContainer.addView(
                    VoidText.body(
                        context,
                        context.getString(R.string.common_warning_format, result.removePrefix("ERROR:")),
                        sizeSp = 16f
                    )
                )
                return@launch
            }

            val lines = result.split("\n")
                .filter { it.isNotBlank() }
                .filter { line ->
                    val parts = line.split("\t")
                    val name = parts.getOrNull(0) ?: return@filter false
                    isVisibleNetworkEntry(name, isDir = parts.getOrNull(1) == "1")
                }
            if (lines.isEmpty()) {
                entriesContainer.addView(
                    VoidText.body(context, context.getString(R.string.network_files_empty), sizeSp = 16f, secondary = true)
                )
                return@launch
            }

            lines.forEach { line ->
                val parts = line.split("\t")
                val name = parts.getOrElse(0) { return@forEach }
                val isDir = parts.getOrNull(1) == "1"
                val sizeBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val row = VoidListRow(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
                    bind(
                        if (isDir) {
                            context.getString(R.string.common_row_dir_format, name)
                        } else {
                            context.getString(R.string.common_row_video_format, name)
                        },
                        showThumbnailSlot = false
                    )
                    setOnClickListener {
                        val childPath = if (requestedPath.isEmpty()) name else "$requestedPath/$name"
                        if (isDir) {
                            networkBrowseFtpPath = childPath
                            renderNetworkFtpFiles(server)
                        } else {
                            val source = PlaybackSource.Ftp(server, childPath, sizeBytes)
                            promptResumeOrPlay(source) { resumeAtMs ->
                                activity.playFtp(server, childPath, sizeBytes, resumeAtMs)
                                navigateTo(Destination.Player(source))
                            }
                        }
                    }
                }
                entriesContainer.addView(row)
            }
        }
    }

    // ---------- Aba SFTP: servidores salvos (T6.4/T6.2) ----------
    // Espelha a aba FTP acima, com autenticacao por chave privada (T6.2) no
    // lugar de "anonimo" (SFTP nao tem essa convencao — ver validate() em
    // rust/protocols/src/sftp/uri.rs).

    private fun buildNetworkSftpPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val header = VoidText.title(context, context.getString(R.string.network_sftp_saved_servers_header), sizeSp = 20f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
        }
        page.addView(header)

        val serversContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(serversContainer)

        fun refreshServerList() {
            serversContainer.removeAllViews()
            val servers = sftpCredentialStore.list()
            if (servers.isEmpty()) {
                serversContainer.addView(
                    VoidText.body(context, context.getString(R.string.network_sftp_empty), sizeSp = 16f, secondary = true)
                )
                return
            }
            servers.forEach { server ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f))
                }
                val label = VoidListRow(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    bind(
                        context.getString(R.string.network_sftp_row_label_format, server.name),
                        meta = if (server.usesKeyAuth) context.getString(R.string.network_sftp_key_auth_label) else server.username,
                        showThumbnailSlot = false
                    )
                    setOnClickListener {
                        networkBrowsingSftpServer = server
                        networkBrowseSftpPath = ""
                        navigateTo(Destination.NetworkSftpFiles(server, ""))
                    }
                }
                val btnRemove = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                    text = "✕"
                    textSize = 16f
                    minHeight = 0
                    val pad = VoidTheme.dpToPx(context, 12f)
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener {
                        sftpCredentialStore.remove(server.id)
                        refreshServerList()
                    }
                }
                row.addView(label)
                row.addView(btnRemove, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = VoidTheme.dpToPx(context, 8f) })
                serversContainer.addView(row)
            }
        }

        val addForm = buildAddSftpServerForm { refreshServerList() }
        addForm.visibility = View.GONE

        val btnAddServer = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.network_sftp_btn_add_server)
            textSize = 18f
            setOnClickListener { addForm.visibility = if (addForm.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        page.addView(btnAddServer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 8f) })

        page.addView(addForm)

        refreshServerList()
        return page
    }

    private fun buildAddSftpServerForm(onSaved: () -> Unit): LinearLayout {
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(VoidTheme.colorSurface)
            val pad = VoidTheme.dpToPx(context, 16f)
            setPadding(pad, pad, pad, pad)
        }

        fun addLabeled(text: String, field: EditText) {
            form.addView(VoidText.mono(context, text, sizeSp = 13f).apply {
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, 0)
            })
            form.addView(field)
            form.addView(buildPasteButton(field), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = VoidTheme.dpToPx(context, 4f) })
        }

        val formHost = buildVoidEditText("192.168.1.10")
        addLabeled(context.getString(R.string.network_sftp_form_host_label), formHost)

        val formPort = buildVoidEditText("22").apply {
            setText("22")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        addLabeled(context.getString(R.string.network_sftp_form_port_label), formPort)

        val formUser = buildVoidEditText(context.getString(R.string.network_sftp_form_user_hint))
        addLabeled(context.getString(R.string.network_sftp_form_user_label), formUser)

        val formPass = buildVoidEditText(context.getString(R.string.network_sftp_form_pass_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val passLabel = VoidText.mono(context, context.getString(R.string.network_sftp_form_pass_label), sizeSp = 13f).apply {
            setPadding(0, VoidTheme.dpToPx(context, 8f), 0, 0)
        }
        form.addView(passLabel)
        form.addView(formPass)
        form.addView(buildPasteButton(formPass), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 4f) })

        // T6.2: chave privada — colada como texto PEM (nao ha seletor de
        // arquivo aqui; "avancado" per o doc, T6.4). Multi-linha pra caber
        // um PEM real; botao de colar reusa o mesmo padrao da aba URL
        // (buildNetworkUrlPage/btnPaste).
        val keyLabel = VoidText.mono(context, context.getString(R.string.network_sftp_form_key_label), sizeSp = 13f).apply {
            setPadding(0, VoidTheme.dpToPx(context, 8f), 0, 0)
            visibility = View.GONE
        }
        val formKey = buildVoidEditText(context.getString(R.string.network_sftp_form_key_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            typeface = VoidTheme.typefaceMono
            visibility = View.GONE
        }
        val btnPasteKey = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.network_sftp_form_btn_paste_key)
            textSize = 16f
            visibility = View.GONE
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    formKey.setText(clip.getItemAt(0).coerceToText(activity).toString())
                }
            }
        }
        form.addView(keyLabel)
        form.addView(formKey)
        form.addView(btnPasteKey, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 4f) })

        val formUseKey = CheckBox(context).apply {
            text = context.getString(R.string.network_sftp_form_use_key_checkbox)
            textSize = 16f
            setTextColor(VoidTheme.colorText)
        }
        form.addView(formUseKey, form.indexOfChild(passLabel))
        formUseKey.setOnCheckedChangeListener { _, useKey ->
            passLabel.visibility = if (useKey) View.GONE else View.VISIBLE
            formPass.visibility = if (useKey) View.GONE else View.VISIBLE
            keyLabel.visibility = if (useKey) View.VISIBLE else View.GONE
            formKey.visibility = if (useKey) View.VISIBLE else View.GONE
            btnPasteKey.visibility = if (useKey) View.VISIBLE else View.GONE
        }

        val formStatus = VoidText.body(context, "", sizeSp = 14f, secondary = true).apply {
            setPadding(0, VoidTheme.dpToPx(context, 12f), 0, VoidTheme.dpToPx(context, 8f))
        }
        form.addView(formStatus)

        val btnTestSave = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.network_sftp_btn_test_save)
            textSize = 18f
            setOnClickListener {
                testAndSaveSftpServer(
                    host = formHost.text.toString().trim(),
                    port = formPort.text.toString().toIntOrNull() ?: 22,
                    username = formUser.text.toString(),
                    password = if (formUseKey.isChecked) "" else formPass.text.toString(),
                    privateKey = if (formUseKey.isChecked) formKey.text.toString().trim() else null,
                    statusView = formStatus,
                    onSaved = {
                        onSaved()
                        form.visibility = View.GONE
                    }
                )
            }
        }
        form.addView(btnTestSave)

        return form
    }

    /**
     * Conecta de verdade (via `sftp_list_directory`, T6.2) antes de salvar —
     * mesmo raciocinio de `testAndSaveFtpServer`.
     */
    private fun testAndSaveSftpServer(
        host: String,
        port: Int,
        username: String,
        password: String,
        privateKey: String?,
        statusView: android.widget.TextView,
        onSaved: () -> Unit
    ) {
        if (host.isEmpty()) {
            statusView.text = context.getString(R.string.network_sftp_form_status_host_required)
            return
        }
        if (password.isEmpty() && privateKey.isNullOrEmpty()) {
            statusView.text = context.getString(R.string.network_sftp_form_status_auth_required)
            return
        }

        statusView.text = context.getString(R.string.network_sftp_form_status_connecting)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeSftpListDirectory(host, port, username, password, privateKey ?: "", "")
            }
            if (result.startsWith("ERROR:")) {
                statusView.text = context.getString(
                    R.string.network_sftp_form_status_error_format, result.removePrefix("ERROR:")
                )
                return@launch
            }

            statusView.text = context.getString(R.string.network_sftp_form_status_connected)

            val server = SftpServer(
                id = sftpCredentialStore.newId(),
                name = host,
                host = host,
                port = port,
                username = username,
                password = password,
                privateKey = privateKey
            )
            sftpCredentialStore.save(server)
            onSaved()
        }
    }

    // ==================== REDE: NAVEGACAO DE DIRETORIO SFTP ====================

    private fun renderNetworkSftpFiles(server: SftpServer) {
        networkBrowsingSftpServer = server
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = server.name,
                subtitle = "/$networkBrowseSftpPath",
                onBack = { handleBack() }
            )
        )

        val entriesContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(entriesContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroller)

        showScreen(root)
        loadNetworkSftpDirectory(server, entriesContainer)
    }

    private fun loadNetworkSftpDirectory(server: SftpServer, entriesContainer: LinearLayout) {
        entriesContainer.removeAllViews()
        entriesContainer.addView(
            VoidText.body(context, context.getString(R.string.network_files_loading), sizeSp = 16f, secondary = true)
        )

        val requestedPath = networkBrowseSftpPath
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeSftpListDirectory(
                    server.host, server.port, server.username, server.password,
                    server.privateKey ?: "", requestedPath
                )
            }
            if (networkBrowsingSftpServer != server || networkBrowseSftpPath != requestedPath) {
                return@launch
            }
            entriesContainer.removeAllViews()

            if (result.startsWith("ERROR:")) {
                entriesContainer.addView(
                    VoidText.body(
                        context,
                        context.getString(R.string.common_warning_format, result.removePrefix("ERROR:")),
                        sizeSp = 16f
                    )
                )
                return@launch
            }

            val lines = result.split("\n")
                .filter { it.isNotBlank() }
                .filter { line ->
                    val parts = line.split("\t")
                    val name = parts.getOrNull(0) ?: return@filter false
                    isVisibleNetworkEntry(name, isDir = parts.getOrNull(1) == "1")
                }
            if (lines.isEmpty()) {
                entriesContainer.addView(
                    VoidText.body(context, context.getString(R.string.network_files_empty), sizeSp = 16f, secondary = true)
                )
                return@launch
            }

            lines.forEach { line ->
                val parts = line.split("\t")
                val name = parts.getOrElse(0) { return@forEach }
                val isDir = parts.getOrNull(1) == "1"
                val sizeBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val row = VoidListRow(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
                    bind(
                        if (isDir) {
                            context.getString(R.string.common_row_dir_format, name)
                        } else {
                            context.getString(R.string.common_row_video_format, name)
                        },
                        showThumbnailSlot = false
                    )
                    setOnClickListener {
                        val childPath = if (requestedPath.isEmpty()) name else "$requestedPath/$name"
                        if (isDir) {
                            networkBrowseSftpPath = childPath
                            renderNetworkSftpFiles(server)
                        } else {
                            val source = PlaybackSource.Sftp(server, childPath, sizeBytes)
                            promptResumeOrPlay(source) { resumeAtMs ->
                                activity.playSftp(server, childPath, sizeBytes, resumeAtMs)
                                navigateTo(Destination.Player(source))
                            }
                        }
                    }
                }
                entriesContainer.addView(row)
            }
        }
    }

    /**
     * Unico ponto de construcao de EditText no painel inteiro (arquivos
     * locais nao tem busca por texto ainda; URL, SMB, FTP e SFTP passam todos
     * por aqui) — por isso e o unico lugar que precisa amarrar o teclado
     * nativo (ver secao "TECLADO NATIVO" abaixo). `isFocusableInTouchMode` e
     * `isFocusable` sao `true` por padrao pra EditText, entao nao precisam
     * ser forcados aqui.
     */
    private fun buildVoidEditText(hint: String): EditText = EditText(context).apply {
        this.hint = hint
        setHintTextColor(VoidTheme.colorTextSecondary)
        setTextColor(VoidTheme.colorText)
        typeface = VoidTheme.typefaceBody
        textSize = 18f
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(VoidTheme.colorSurface)
            cornerRadius = VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
            setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
        }
        // Alvo de toque generoso (mesma razao do VoidButton — feedback de
        // usuario em validacao real: hitbox pequena demais em VR).
        val padH = VoidTheme.dpToPx(context, 16f)
        val padV = VoidTheme.dpToPx(context, 20f)
        setPadding(padH, padV, padH, padV)
        minimumHeight = VoidTheme.dpToPx(context, 76f)

        // Bug reportado: toque sintetico (raycast do controller -> UV ->
        // dispatchVRTouch, ver VRActivity.kt) chega como MotionEvent real e
        // ja aciona `requestFocusFromTouch()`/foco normalmente (o cursor
        // pisca) — o que faltava era o teclado aparecer, porque este campo
        // vive numa VirtualDisplay (ver secao "TECLADO NATIVO" abaixo pro
        // raciocinio completo). `showNativeKeyboardFor`/`hideNativeKeyboard`
        // (VRActivity.kt) resolvem isso abrindo o teclado REAL do sistema
        // (com suporte a digitacao por toque de dedo via hand tracking, nao
        // so raycast do controller — pedido explicito do usuario).
        setOnFocusChangeListener { view, hasFocus ->
            val editText = view as EditText
            if (hasFocus) {
                keyboardTarget = editText
                activity.showNativeKeyboardFor(editText)
            } else if (keyboardTarget === editText) {
                keyboardTarget = null
                activity.hideNativeKeyboard()
            }
        }
    }

    /**
     * Bolha nativa "Colar" do long-press em EditText nunca funciona aqui: e
     * uma janela popup separada da Presentation (mesmo esta sendo composta
     * na mesma VirtualDisplay, entao aparece no quad 3D), e o toque
     * sintetico do controller (`dispatchVRTouch` em VRActivity.kt) so
     * alcanca a janela da propria Presentation via `dispatchTouchEvent` —
     * mesma classe de restricao da secao "TECLADO NATIVO" abaixo. Por isso
     * todo EditText do painel ganha este botao "Colar" explicito (View
     * normal dentro da hierarquia da Presentation, entao recebe o toque
     * normalmente), lendo o ClipboardManager direto — padrao que ja existia
     * isolado em btnPaste (aba URL) e btnPasteKey (SFTP), centralizado aqui.
     */
    private fun buildPasteButton(field: EditText): VoidButton = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
        text = context.getString(R.string.network_url_btn_paste)
        textSize = 16f
        setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(activity).toString()
                field.setText(text)
                field.setSelection(text.length)
            }
        }
    }

    // ==================== TECLADO NATIVO (bug: sem IME em VirtualDisplay) ====================
    //
    // Causa raiz confirmada por leitura da documentacao AOSP (nao apenas
    // suposicao): "the system won't show an IME on virtual displays that
    // aren't owned by the system" — restricao de seguranca deliberada (evita
    // que um app malicioso crie uma virtual display forjada pra capturar
    // previsoes de digitacao/senhas de outro app), nao um bug de configuracao.
    // Ver https://source.android.com/docs/core/display/multi_display/ime-support.
    // `VRPresentation` roda inteira sobre uma `VirtualDisplay` criada pelo
    // proprio app (`DisplayManager.createVirtualDisplay`, ver
    // `VRActivity.setupVirtualDisplay`) — exatamente o caso "nao owned pelo
    // sistema" — entao um `EditText` DAQUI nunca vai receber o IME real,
    // nao importa a combinacao de flags/`requestFocus`/`showSoftInput`.
    //
    // Solucao (pedido explicito do usuario: quer o teclado NATIVO do Meta
    // Quest, nao um customizado — digitar com os dedos via hand tracking e
    // mais pratico que so raycast): a amostra oficial da Meta
    // (`sdk/meta-openxr-sdk/Samples/XrSamples/XrOverlayKeyboard`) mostra que
    // o teclado overlay do sistema NAO e uma extensao OpenXR que o app
    // precisa renderizar — e o IME normal do Android, mostrado como overlay
    // 3D pelo proprio Horizon OS, desde que o `EditText` focado esteja numa
    // janela REAL da Activity (nao numa VirtualDisplay) e o manifest declare
    // `oculus.software.overlay_keyboard` (ver AndroidManifest.xml). Por isso
    // a implementacao real fica em `VRActivity` (dona da janela de verdade),
    // nao aqui: `activity.showNativeKeyboardFor(editText)` foca um `EditText`
    // PROXY real, oculto da cena 3D (apps `vr_only` nao compositam Views 2D
    // extras da Activity no headset — so a superficie OpenXR e mostrada),
    // espelhando o texto digitado de volta pro campo focado deste painel via
    // `TextWatcher`. Ver VRActivity.kt para a implementacao e a ressalva
    // "nunca validado em headset real" completa.

    // ==================== HISTORICO DE REPRODUCAO (T9) ====================

    /**
     * T9.3: consulta `historyTracker.findExisting` (Room, `Dispatchers.IO`
     * por baixo) e, se houver uma entrada retomavel para [source], mostra o
     * prompt "Retomar de XX:XX?" ANTES de tocar; senão toca direto do zero.
     * [onDecided] recebe `resumeAtMs` (posicao salva) ou `null` (comecar do
     * zero) e e responsavel por de fato chamar `activity.playX(...)` e
     * navegar para [Destination.Player] — cada um dos 3 call sites (arquivo
     * local, URL, SMB) sabe como tocar sua propria fonte, entao essa funcao
     * so decide "perguntar ou nao", nunca toca midia ela mesma.
     */
    private fun promptResumeOrPlay(source: PlaybackSource, onDecided: (resumeAtMs: Long?) -> Unit) {
        scope.launch {
            val existing = activity.historyTracker.findExisting(source)
            if (existing != null && existing.isResumable()) {
                showResumePrompt(
                    entry = existing,
                    onResume = { onDecided(existing.positionMs) },
                    onRestart = { onDecided(null) }
                )
            } else {
                onDecided(null)
            }
        }
    }

    /**
     * Tela transitoria "Retomar de XX:XX?" (T9.3) — desenhada com
     * `showScreen()` direto (NAO `navigateTo`/`AppNavigator`) de proposito:
     * e uma decisao pontual, nao um novo nivel de navegacao "de verdade". O
     * back-stack do [AppNavigator] continua exatamente onde estava (ex.:
     * ainda em `LocalFiles`), entao "Voltar" aqui so re-renderiza a tela de
     * onde o usuario veio (`render()`), e depois de escolher Retomar/
     * Comecar do zero o fluxo normal de `navigateTo(Destination.Player(...))`
     * empilha a partir dessa mesma tela — como se o prompt nunca tivesse
     * existido no historico de navegacao.
     */
    private fun showResumePrompt(entry: PlaybackHistory, onResume: () -> Unit, onRestart: () -> Unit) {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(context, title = context.getString(R.string.history_resume_prompt_title), onBack = { render() })
        )
        root.addView(VoidText.body(context, entry.title, sizeSp = 20f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
        })
        root.addView(VoidText.mono(
            context,
            context.getString(
                R.string.history_resume_prompt_watched_format,
                formatDurationMs(entry.positionMs),
                formatDurationMs(entry.durationMs),
                watchedPercent(entry)
            ),
            sizeSp = 16f
        ).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 24f))
        })

        val btnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 12f) }

        val btnResume = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.history_resume_prompt_btn_resume_format, formatDurationMs(entry.positionMs))
            textSize = 20f
            setOnClickListener { onResume() }
        }
        val btnRestart = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.history_resume_prompt_btn_restart)
            textSize = 18f
            setOnClickListener { onRestart() }
        }
        root.addView(btnResume, btnParams)
        root.addView(btnRestart, btnParams)

        showScreen(root)
    }

    // ---------- T9.4: tela "Continuar assistindo" ----------

    private fun renderContinueWatching() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(context, title = context.getString(R.string.history_continue_watching_title), onBack = { handleBack() })
        )

        val recycler = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(context)
        }
        val emptyText = VoidText.body(context, context.getString(R.string.history_empty), sizeSp = 16f, secondary = true).apply {
            visibility = View.GONE
        }

        lateinit var adapter: HistoryAdapter

        fun refresh() {
            scope.launch {
                val items = activity.historyTracker.listRecent()
                adapter.submit(items)
                recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        adapter = HistoryAdapter(
            onItemClick = { entry -> resumeFromHistory(entry) },
            onRemoveClick = { entry ->
                scope.launch {
                    activity.historyTracker.delete(entry.historyKey)
                    refresh()
                }
            }
        )
        recycler.adapter = adapter

        root.addView(recycler)
        root.addView(emptyText)
        showScreen(root)
        refresh()
    }

    /**
     * Retoma direto da tela "Continuar assistindo" — diferente do prompt de
     * T9.3 (que pergunta), aqui a intencao do usuario ja e explicita
     * (clicou numa entrada de "continuar assistindo"), entao toca direto na
     * posicao salva, sem perguntar de novo.
     */
    private fun resumeFromHistory(entry: PlaybackHistory) {
        when (entry.sourceType) {
            HistorySourceType.LOCAL -> {
                val source = PlaybackSource.LocalFile(entry.mediaPath)
                activity.playFile(entry.mediaPath, resumeAtMs = entry.positionMs)
                navigateTo(Destination.Player(source))
            }
            HistorySourceType.HTTP -> {
                val source = PlaybackSource.Http(entry.mediaPath)
                activity.playUrl(entry.mediaPath, resumeAtMs = entry.positionMs)
                navigateTo(Destination.Player(source))
            }
            HistorySourceType.SMB -> {
                val server = resolveSmbServerFromHistory(entry.serverInfo)
                if (server == null) {
                    // Servidor foi removido/renomeado desde a ultima vez —
                    // nao ha credencial pra reconectar. Falha silenciosa e
                    // segura (nao navega pra lugar nenhum) em vez de crash;
                    // "não precisa ser sofisticado" (escopo do T9.3/T9.4).
                    return
                }
                val source = PlaybackSource.Smb(server, entry.mediaPath)
                activity.playSmb(server, entry.mediaPath, resumeAtMs = entry.positionMs)
                navigateTo(Destination.Player(source))
            }
            HistorySourceType.FTP -> {
                val server = resolveFtpServerFromHistory(entry.serverInfo)
                if (server == null) {
                    // Mesmo raciocinio do branch SMB acima: servidor
                    // removido/renomeado desde a ultima vez, falha silenciosa.
                    return
                }
                val source = PlaybackSource.Ftp(server, entry.mediaPath)
                activity.playFtp(server, entry.mediaPath, resumeAtMs = entry.positionMs)
                navigateTo(Destination.Player(source))
            }
            HistorySourceType.SFTP -> {
                val server = resolveSftpServerFromHistory(entry.serverInfo)
                if (server == null) {
                    // Mesmo raciocinio dos branches acima.
                    return
                }
                val source = PlaybackSource.Sftp(server, entry.mediaPath)
                activity.playSftp(server, entry.mediaPath, resumeAtMs = entry.positionMs)
                navigateTo(Destination.Player(source))
            }
        }
    }

    /** Resolve o [SmbServer] salvo (com credenciais) a partir do `serverId`
     * gravado em [PlaybackHistory.serverInfo] — a credencial em si NUNCA foi
     * duplicada no historico (ver `PlaybackHistoryMapping.serverInfoJson`),
     * so a referencia pro servidor salvo em `SmbCredentialStore` (T6.4). */
    private fun resolveSmbServerFromHistory(serverInfoJson: String?): SmbServer? {
        if (serverInfoJson == null) return null
        return try {
            val serverId = JSONObject(serverInfoJson).getString("serverId")
            credentialStore.list().find { it.id == serverId }
        } catch (e: Exception) {
            null
        }
    }

    /** Ver [resolveSmbServerFromHistory] — mesma logica para [FtpServer]. */
    private fun resolveFtpServerFromHistory(serverInfoJson: String?): FtpServer? {
        if (serverInfoJson == null) return null
        return try {
            val serverId = JSONObject(serverInfoJson).getString("serverId")
            ftpCredentialStore.list().find { it.id == serverId }
        } catch (e: Exception) {
            null
        }
    }

    /** Ver [resolveSmbServerFromHistory] — mesma logica para [SftpServer]. */
    private fun resolveSftpServerFromHistory(serverInfoJson: String?): SftpServer? {
        if (serverInfoJson == null) return null
        return try {
            val serverId = JSONObject(serverInfoJson).getString("serverId")
            sftpCredentialStore.list().find { it.id == serverId }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Linha de lista para T9.4: reusa [VoidListRow] (mesmo componente do
     * File Browser/listagem SMB, T5/T6) + um botao "✕" de remover, no mesmo
     * padrao ja usado pela lista de servidores SMB salvos
     * (`buildNetworkSmbPage`). Sem thumbnails (T9 nao gera/salva
     * `thumbnailPath` nesta implementacao — campo existe no Room para uso
     * futuro, mas fica `null` por enquanto).
     */
    private inner class HistoryAdapter(
        private val onItemClick: (PlaybackHistory) -> Unit,
        private val onRemoveClick: (PlaybackHistory) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        private var items: List<PlaybackHistory> = emptyList()

        fun submit(newItems: List<PlaybackHistory>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(row: LinearLayout, val listRow: VoidListRow) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val listRow = VoidListRow(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnRemove = VoidButton(parent.context, VoidButtonStyle.SECONDARY).apply {
                text = parent.context.getString(R.string.history_btn_remove)
                textSize = 16f
                minHeight = 0
                val pad = VoidTheme.dpToPx(parent.context, 12f)
                setPadding(pad, pad, pad, pad)
            }
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = VoidTheme.dpToPx(parent.context, 8f) }
                addView(listRow)
                addView(btnRemove, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = VoidTheme.dpToPx(parent.context, 8f) })
            }
            val holder = ViewHolder(row, listRow)
            btnRemove.setOnClickListener {
                items.getOrNull(holder.adapterPosition)?.let(onRemoveClick)
            }
            return holder
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            val titleWithIcon = when (entry.sourceType) {
                HistorySourceType.LOCAL -> context.getString(R.string.common_row_video_format, entry.title)
                HistorySourceType.SMB -> context.getString(R.string.network_smb_row_label_format, entry.title)
                HistorySourceType.HTTP -> context.getString(R.string.history_row_http_format, entry.title)
                HistorySourceType.FTP -> context.getString(R.string.history_row_ftp_format, entry.title)
                HistorySourceType.SFTP -> context.getString(R.string.history_row_sftp_format, entry.title)
            }
            val meta = context.getString(
                R.string.history_row_meta_format,
                formatDurationMs(entry.positionMs),
                formatDurationMs(entry.durationMs),
                watchedPercent(entry)
            )
            holder.listRow.bind(titleWithIcon, meta = meta, showThumbnailSlot = false)
            holder.listRow.setOnClickListener { onItemClick(entry) }
        }

        override fun getItemCount() = items.size
    }

    // ==================== PLAYER (estado apos selecionar midia) ====================

    private fun renderPlayer(source: PlaybackSource) {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = context.getString(R.string.player_title),
                onBack = { handleBack() }
            )
        )
        val label = when (source) {
            is PlaybackSource.LocalFile -> source.path
            is PlaybackSource.Http -> source.url
            // Interpolacao com 2 argumentos posicionais (T8.5): permite
            // reordenar "servidor/caminho" em idiomas onde isso fizesse
            // sentido, embora nem PT-BR nem EN precisem reordenar aqui.
            is PlaybackSource.Smb -> context.getString(
                R.string.player_label_smb_source_format, source.server.name, source.path
            )
            is PlaybackSource.Ftp -> context.getString(
                R.string.player_label_ftp_source_format, source.server.name, source.path
            )
            is PlaybackSource.Sftp -> context.getString(
                R.string.player_label_sftp_source_format, source.server.name, source.path
            )
        }
        root.addView(VoidText.mono(context, label, sizeSp = 16f))
        root.addView(VoidText.body(
            context,
            context.getString(R.string.player_label_controls_hint),
            sizeSp = 16f,
            secondary = true
        ).apply {
            setPadding(0, VoidTheme.dpToPx(context, 16f), 0, 0)
        })
        showScreen(root)
    }

    // Adapter roda em cima de MediaEntry (T5.3) em vez de java.io.File cru, e usa o
    // ThumbnailGenerator (T5.4) de forma preguicosa/assincrona por item visivel — nunca
    // gera thumbnails para a pasta inteira de uma vez (travaria a UI, ver cuidados do T5).
    private class FileAdapter(
        private val context: Context,
        private val scope: CoroutineScope,
        private val onUpClick: () -> Unit,
        private val onDirectoryClick: (File) -> Unit,
        private val onVideoClick: (MediaEntry) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

        private sealed class Row {
            object Up : Row()
            data class Item(val entry: MediaEntry) : Row()
        }

        private var rows: List<Row> = emptyList()

        fun submit(entries: List<MediaEntry>, showUp: Boolean) {
            rows = buildList {
                if (showUp) add(Row.Up)
                entries.forEach { add(Row.Item(it)) }
            }
            notifyDataSetChanged()
        }

        inner class ViewHolder(val row: VoidListRow) : RecyclerView.ViewHolder(row) {
            var thumbnailJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val row = VoidListRow(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = VoidTheme.dpToPx(parent.context, 8f) }
            }
            return ViewHolder(row)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.thumbnailJob?.cancel()
            holder.row.thumbnail.setImageBitmap(null)

            when (val row = rows[position]) {
                is Row.Up -> {
                    holder.row.bind(context.getString(R.string.browser_row_up), showThumbnailSlot = false)
                    holder.itemView.setOnClickListener { onUpClick() }
                }
                is Row.Item -> {
                    val entry = row.entry
                    val label = if (entry.type == MediaType.DIRECTORY) {
                        context.getString(R.string.common_row_dir_format, entry.name)
                    } else {
                        context.getString(R.string.common_row_video_format, entry.name)
                    }
                    val meta = if (entry.type == MediaType.VIDEO) formatSize(entry.sizeBytes) else null
                    holder.row.bind(label, meta = meta, showThumbnailSlot = entry.type == MediaType.VIDEO)
                    holder.itemView.setOnClickListener {
                        if (entry.type == MediaType.DIRECTORY) {
                            onDirectoryClick(File(entry.path))
                        } else {
                            onVideoClick(entry)
                        }
                    }
                    if (entry.type == MediaType.VIDEO) {
                        holder.thumbnailJob = scope.launch {
                            val bitmap = ThumbnailGenerator.getThumbnail(context, entry)
                            if (bitmap != null && holder.adapterPosition == position) {
                                holder.row.thumbnail.setImageBitmap(bitmap)
                                holder.row.thumbnail.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }

        override fun getItemCount() = rows.size

        private fun formatSize(bytes: Long): String {
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024) {
                context.getString(R.string.browser_label_size_gb_format, mb / 1024.0)
            } else {
                context.getString(R.string.browser_label_size_mb_format, mb)
            }
        }
    }
}
