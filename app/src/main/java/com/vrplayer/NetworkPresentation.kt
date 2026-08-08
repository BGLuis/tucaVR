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
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidListRow
import com.vrplayer.designsystem.VoidPanelChrome
import com.vrplayer.designsystem.VoidTabRow
import com.vrplayer.designsystem.VoidText
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.network.SmbCredentialStore
import com.vrplayer.network.SmbServer
import com.vrplayer.network.UrlHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T6.4 + T7.3: painel VR unico cobrindo gerenciamento de conexoes SMB
 * (adicionar servidor, listar salvos, status de conexao, navegar
 * diretorios) e input de URL HTTP(S) (campo de texto, historico, colar da
 * clipboard). Combinados num so painel (em vez de dois) para nao duplicar
 * toda a integracao VirtualDisplay+Presentation+quad OES em
 * `vr_player_app.cpp` — ver T4.1/T4.5 la para o padrao reusado.
 *
 * `activity` e a `VRActivity` de verdade (NAO `context`, que e um
 * ContextThemeWrapper do Android em cima de um display-context derivado —
 * mesma armadilha ja documentada em `VRControlsPresentation.kt`).
 *
 * Fase 1 do redesign "Void": reskin visual (paleta/tipografia/componentes
 * `Void*`) — NAO muda posicao/quantidade de quads nem a logica de
 * navegacao/estado abaixo. Este painel continua seu proprio
 * quad/VirtualDisplay, aberto pelo botao Menu do controller esquerdo (ver
 * TODO em `VRPresentation.renderNetworkPlaceholder()` sobre por que a fusao
 * com o painel Home fica pra Fase 2).
 */
class NetworkPresentation(
    outerContext: Context,
    display: Display,
    private val activity: VRActivity
) : Presentation(outerContext, display) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var credentialStore: SmbCredentialStore
    private lateinit var urlHistory: UrlHistoryStore

    // --- Views da aba URL (T7.3) ---
    private lateinit var urlInput: EditText
    private lateinit var urlStatus: android.widget.TextView
    private lateinit var recentContainer: LinearLayout

    // --- Views da aba SMB (T6.4) ---
    private lateinit var serversContainer: LinearLayout
    private lateinit var addForm: LinearLayout
    private lateinit var formHost: EditText
    private lateinit var formPort: EditText
    private lateinit var formShare: EditText
    private lateinit var formUser: EditText
    private lateinit var formPass: EditText
    private lateinit var formDomain: EditText
    private lateinit var formGuest: CheckBox
    private lateinit var formStatus: android.widget.TextView
    private lateinit var browseArea: LinearLayout
    private lateinit var breadcrumbLabel: android.widget.TextView
    private lateinit var entriesContainer: LinearLayout

    private lateinit var urlPage: View
    private lateinit var smbPage: View

    // Estado de navegacao SMB: servidor sendo navegado + caminho relativo
    // (dentro do share) atual. path vazio = raiz do share.
    private var browsingServer: SmbServer? = null
    private var browsePath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        credentialStore = SmbCredentialStore(activity)
        urlHistory = UrlHistoryStore(activity)

        val root = VoidPanelChrome.newRoot(context)
        root.addView(VoidPanelChrome.buildHeader(context, title = "Rede"))

        // --- Tabs ---
        val pageContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        urlPage = buildUrlPage()
        smbPage = buildSmbPage()
        pageContainer.addView(urlPage)
        pageContainer.addView(smbPage)

        val tabRow = VoidTabRow(context, listOf("🔗 URL", "🗄 SMB")) { index ->
            val showUrl = index == 0
            urlPage.visibility = if (showUrl) View.VISIBLE else View.GONE
            smbPage.visibility = if (showUrl) View.GONE else View.VISIBLE
        }
        tabRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }
        root.addView(tabRow)
        root.addView(pageContainer)

        setContentView(root)
        refreshRecentUrls()
        refreshServerList()
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    // ==================== ABA URL (T7.3) ====================

    private fun buildUrlPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        urlInput = buildVoidEditText("http:// ou https://...").apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        page.addView(urlInput)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, VoidTheme.dpToPx(context, 16f), 0, VoidTheme.dpToPx(context, 8f))
        }
        val btnPaste = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = "📋 Colar"
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
            text = "▶ Tocar"
            textSize = 18f
            setOnClickListener { playUrl(urlInput.text.toString().trim()) }
        }
        val btnMargin = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = VoidTheme.dpToPx(context, 12f) }
        row.addView(btnPaste, btnMargin)
        row.addView(btnPlay)
        page.addView(row)

        urlStatus = VoidText.body(context, "", sizeSp = 16f, secondary = true).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 16f))
        }
        page.addView(urlStatus)

        val recentHeader = VoidText.title(context, "Recentes", sizeSp = 20f).apply {
            setPadding(0, VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 8f))
        }
        page.addView(recentHeader)

        recentContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(recentContainer)

        return page
    }

    private fun playUrl(url: String) {
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            urlStatus.text = "⚠ URL precisa comecar com http:// ou https://"
            return
        }
        urlStatus.text = "Verificando servidor..."
        urlHistory.add(url)
        refreshRecentUrls()

        // T7.1: probe HEAD-based ANTES de tocar, so para avisar o usuario se
        // seek nao vai funcionar (doc, secao 7). NAO bloqueia o play — o
        // usuario pode tocar mesmo sem range requests, so sem poder buscar.
        scope.launch {
            val probeResult = withContext(Dispatchers.IO) { activity.nativeProbeHttpUrl(url) }
            urlStatus.text = describeProbe(probeResult)
        }
        activity.playUrl(url)
    }

    private fun describeProbe(result: String): String {
        if (result.startsWith("ERROR:")) {
            return "⚠ ${result.removePrefix("ERROR:")}"
        }
        val parts = result.split("\t")
        val seekable = parts.getOrNull(1) == "1"
        return if (seekable) "✓ Servidor suporta seek" else "⚠ Servidor NAO suporta seek (Accept-Ranges ausente)"
    }

    private fun refreshRecentUrls() {
        recentContainer.removeAllViews()
        val entries = urlHistory.list()
        if (entries.isEmpty()) {
            recentContainer.addView(VoidText.body(context, "(nenhuma URL tocada ainda)", sizeSp = 16f, secondary = true))
            return
        }
        entries.forEach { url ->
            val row = VoidListRow(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
                bind("🕓 $url", showThumbnailSlot = false)
                titleView.typeface = VoidTheme.typefaceMono
                titleView.textSize = 15f
                setOnClickListener {
                    urlInput.setText(url)
                    playUrl(url)
                }
            }
            recentContainer.addView(row)
        }
    }

    // ==================== ABA SMB (T6.4) ====================

    private fun buildSmbPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }

        val header = VoidText.title(context, "Servidores salvos", sizeSp = 20f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
        }
        page.addView(header)

        serversContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(serversContainer)

        val btnAddServer = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = "+ Adicionar servidor"
            textSize = 18f
            setOnClickListener { addForm.visibility = if (addForm.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        page.addView(btnAddServer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 8f) })

        addForm = buildAddServerForm()
        addForm.visibility = View.GONE
        page.addView(addForm)

        browseArea = buildBrowseArea()
        browseArea.visibility = View.GONE
        page.addView(browseArea)

        return page
    }

    private fun buildAddServerForm(): LinearLayout {
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

        formHost = buildVoidEditText("192.168.1.10")
        addLabeled("Host / IP", formHost)

        formPort = buildVoidEditText("445").apply {
            setText("445")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        addLabeled("Porta", formPort)

        formShare = buildVoidEditText("Videos")
        addLabeled("Share", formShare)

        formGuest = CheckBox(context).apply {
            text = "Convidado / anonimo (sem usuario/senha)"
            textSize = 16f
            setTextColor(VoidTheme.colorText)
        }
        form.addView(formGuest)

        formUser = buildVoidEditText("usuario")
        addLabeled("Usuario", formUser)

        formPass = buildVoidEditText("senha").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        addLabeled("Senha", formPass)

        formDomain = buildVoidEditText("(opcional)")
        addLabeled("Dominio", formDomain)

        formGuest.setOnCheckedChangeListener { _, checked ->
            formUser.isEnabled = !checked
            formPass.isEnabled = !checked
            if (checked) {
                formUser.setText("")
                formPass.setText("")
            }
        }

        formStatus = VoidText.body(context, "", sizeSp = 14f, secondary = true).apply {
            setPadding(0, VoidTheme.dpToPx(context, 12f), 0, VoidTheme.dpToPx(context, 8f))
        }
        form.addView(formStatus)

        val btnTestSave = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = "Testar e salvar"
            textSize = 18f
            setOnClickListener { testAndSaveServer() }
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
    private fun testAndSaveServer() {
        val host = formHost.text.toString().trim()
        val port = formPort.text.toString().toIntOrNull() ?: 445
        val share = formShare.text.toString().trim()
        val guest = formGuest.isChecked
        val username = if (guest) "" else formUser.text.toString()
        val password = if (guest) "" else formPass.text.toString()
        val domain = formDomain.text.toString().trim()

        if (host.isEmpty()) {
            formStatus.text = "⚠ Host obrigatorio"
            return
        }

        formStatus.text = "Conectando..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeSmbListShares(host, port, username, password, domain)
            }
            if (result.startsWith("ERROR:")) {
                formStatus.text = "✗ Falha: ${result.removePrefix("ERROR:")}"
                return@launch
            }

            val shares = result.split("\n").filter { it.isNotBlank() }
            formStatus.text = "✓ Conectado (${shares.size} share(s) encontrado(s))"

            val server = SmbServer(
                id = credentialStore.newId(),
                name = if (share.isNotEmpty()) "$host/$share" else host,
                host = host,
                port = port,
                share = share.ifEmpty { shares.firstOrNull() ?: "" },
                username = username,
                password = password,
                domain = domain
            )
            credentialStore.save(server)
            refreshServerList()
            addForm.visibility = View.GONE
        }
    }

    private fun refreshServerList() {
        serversContainer.removeAllViews()
        val servers = credentialStore.list()
        if (servers.isEmpty()) {
            serversContainer.addView(VoidText.body(context, "(nenhum servidor salvo)", sizeSp = 16f, secondary = true))
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
                    "🖥 ${server.name}",
                    meta = if (server.isGuest) "guest" else server.username,
                    showThumbnailSlot = false
                )
                setOnClickListener { startBrowse(server) }
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

    // ---------- Navegacao de diretorios SMB ----------

    private fun buildBrowseArea(): LinearLayout {
        val area = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(VoidTheme.colorSurface)
            val pad = VoidTheme.dpToPx(context, 16f)
            setPadding(pad, pad, pad, pad)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnBack = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = "◀"
            textSize = 16f
            minHeight = 0
            val pad = VoidTheme.dpToPx(context, 10f)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { browseUp() }
        }
        breadcrumbLabel = VoidText.mono(context, "", sizeSp = 14f).apply {
            setPadding(VoidTheme.dpToPx(context, 16f), 0, 0, 0)
        }
        row.addView(btnBack)
        row.addView(breadcrumbLabel)
        area.addView(row)

        entriesContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        area.addView(entriesContainer)

        return area
    }

    private fun startBrowse(server: SmbServer) {
        browsingServer = server
        browsePath = ""
        browseArea.visibility = View.VISIBLE
        addForm.visibility = View.GONE
        loadSmbDirectory()
    }

    private fun browseUp() {
        browsingServer ?: return
        if (browsePath.isEmpty()) {
            browseArea.visibility = View.GONE
            browsingServer = null
            return
        }
        browsePath = browsePath.substringBeforeLast('/', missingDelimiterValue = "")
        loadSmbDirectory()
    }

    private fun loadSmbDirectory() {
        val server = browsingServer ?: return
        breadcrumbLabel.text = "${server.share}/$browsePath"
        entriesContainer.removeAllViews()
        entriesContainer.addView(VoidText.body(context, "Carregando...", sizeSp = 16f, secondary = true))

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeSmbListDirectory(
                    server.host, server.port, server.username, server.password, server.domain,
                    server.share, browsePath
                )
            }
            entriesContainer.removeAllViews()

            if (result.startsWith("ERROR:")) {
                entriesContainer.addView(VoidText.body(context, "⚠ ${result.removePrefix("ERROR:")}", sizeSp = 16f))
                return@launch
            }

            val lines = result.split("\n").filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                entriesContainer.addView(VoidText.body(context, "(vazio)", sizeSp = 16f, secondary = true))
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
                    bind(if (isDir) "📁 $name" else "🎬 $name", showThumbnailSlot = false)
                    setOnClickListener {
                        val childPath = if (browsePath.isEmpty()) name else "$browsePath/$name"
                        if (isDir) {
                            browsePath = childPath
                            loadSmbDirectory()
                        } else {
                            activity.playSmb(server, childPath)
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
}
