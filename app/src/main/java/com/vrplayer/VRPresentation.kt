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
import com.vrplayer.filebrowser.sortMediaEntries
import com.vrplayer.navigation.AppNavigator
import com.vrplayer.navigation.Destination
import com.vrplayer.navigation.PlaybackSource
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

    // Qual aba (URL=0 / SMB=1) estava ativa da ultima vez que o usuario
    // esteve em Destination.NetworkHome — preservado entre navegacoes
    // (Home -> Rede -> Arquivos -> Voltar -> Rede continua na mesma aba).
    private var networkActiveTabIndex = 0

    private lateinit var credentialStore: SmbCredentialStore
    private lateinit var urlHistory: UrlHistoryStore

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var screenHost: FrameLayout
    private var localFileAdapter: FileAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialStore = SmbCredentialStore(activity)
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
        if (appNav.back()) {
            render()
        }
    }

    private fun showScreen(view: View) {
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
        // T9 (roadmap, ver docs/phases): "Continuar assistindo" depende de um
        // historico de reproducao que ainda nao existe. Placeholder
        // DESABILITADO (nao escondido) de proposito: sinaliza pro usuario que
        // a funcionalidade existe/esta a caminho, em vez de sumir sem
        // explicacao.
        val btnContinueWatching = VoidButton(context, VoidButtonStyle.DISABLED).apply {
            text = context.getString(R.string.home_btn_continue_watching)
            textSize = 20f
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
        activity.playFile(entry.path)
        navigateTo(Destination.Player(PlaybackSource.LocalFile(entry.path)))
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
        urlPage.visibility = if (networkActiveTabIndex == 0) View.VISIBLE else View.GONE
        smbPage.visibility = if (networkActiveTabIndex == 0) View.GONE else View.VISIBLE
        pageContainer.addView(urlPage)
        pageContainer.addView(smbPage)

        val tabRow = VoidTabRow(
            context,
            listOf(context.getString(R.string.network_tab_url), context.getString(R.string.network_tab_smb))
        ) { index ->
            networkActiveTabIndex = index
            val showUrl = index == 0
            urlPage.visibility = if (showUrl) View.VISIBLE else View.GONE
            smbPage.visibility = if (showUrl) View.GONE else View.VISIBLE
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
        activity.playUrl(url)
        navigateTo(Destination.Player(PlaybackSource.Http(url)))
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

            val lines = result.split("\n").filter { it.isNotBlank() }
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
                            activity.playSmb(server, childPath)
                            navigateTo(Destination.Player(PlaybackSource.Smb(server, childPath)))
                        }
                    }
                }
                entriesContainer.addView(row)
            }
        }
    }

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
        val padH = VoidTheme.dpToPx(context, 16f)
        val padV = VoidTheme.dpToPx(context, 12f)
        setPadding(padH, padV, padH, padV)
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
