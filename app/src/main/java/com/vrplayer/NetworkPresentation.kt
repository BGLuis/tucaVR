package com.vrplayer

import android.app.Presentation
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
    private lateinit var urlStatus: TextView
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
    private lateinit var formStatus: TextView
    private lateinit var browseArea: LinearLayout
    private lateinit var breadcrumbLabel: TextView
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

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(context).apply {
            text = "🌐 Rede"
            textSize = 32f
            setTextColor(Color.WHITE)
            setPadding(8, 0, 8, 16)
        }
        root.addView(title)

        // --- Tabs ---
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        val tabButtonParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        val btnTabUrl = Button(context).apply {
            text = "🔗 URL"
            textSize = 24f
        }
        val btnTabSmb = Button(context).apply {
            text = "🗄 SMB"
            textSize = 24f
        }
        tabRow.addView(btnTabUrl, tabButtonParams)
        tabRow.addView(btnTabSmb, tabButtonParams)
        root.addView(tabRow)

        val pageContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        urlPage = buildUrlPage()
        smbPage = buildSmbPage()
        pageContainer.addView(urlPage)
        pageContainer.addView(smbPage)
        root.addView(pageContainer)

        fun showTab(showUrl: Boolean) {
            urlPage.visibility = if (showUrl) View.VISIBLE else View.GONE
            smbPage.visibility = if (showUrl) View.GONE else View.VISIBLE
        }
        btnTabUrl.setOnClickListener { showTab(true) }
        btnTabSmb.setOnClickListener { showTab(false) }
        showTab(true)

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

        urlInput = EditText(context).apply {
            hint = "http:// ou https://..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 22f
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        page.addView(urlInput)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 8)
        }
        val btnPaste = Button(context).apply {
            text = "📋 Colar"
            textSize = 20f
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
        val btnPlay = Button(context).apply {
            text = "▶ Tocar"
            textSize = 20f
            setOnClickListener { playUrl(urlInput.text.toString().trim()) }
        }
        row.addView(btnPaste)
        row.addView(btnPlay)
        page.addView(row)

        urlStatus = TextView(context).apply {
            text = ""
            textSize = 18f
            setTextColor(Color.YELLOW)
            setPadding(0, 0, 0, 16)
        }
        page.addView(urlStatus)

        val recentHeader = TextView(context).apply {
            text = "Recentes"
            textSize = 22f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 8)
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
            recentContainer.addView(TextView(context).apply {
                text = "(nenhuma URL tocada ainda)"
                textSize = 18f
                setTextColor(Color.GRAY)
            })
            return
        }
        entries.forEach { url ->
            recentContainer.addView(TextView(context).apply {
                text = "🕓 $url"
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(8, 12, 8, 12)
                setOnClickListener {
                    urlInput.setText(url)
                    playUrl(url)
                }
            })
        }
    }

    // ==================== ABA SMB (T6.4) ====================

    private fun buildSmbPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }

        val header = TextView(context).apply {
            text = "Servidores salvos"
            textSize = 22f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 8)
        }
        page.addView(header)

        serversContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(serversContainer)

        val btnAddServer = Button(context).apply {
            text = "+ Adicionar servidor"
            textSize = 20f
            setOnClickListener { addForm.visibility = if (addForm.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        page.addView(btnAddServer)

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
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(16, 16, 16, 16)
        }

        fun addLabeled(text: String, field: EditText) {
            form.addView(TextView(context).apply {
                this.text = text
                textSize = 16f
                setTextColor(Color.GRAY)
                setPadding(0, 8, 0, 0)
            })
            form.addView(field)
        }

        formHost = EditText(context).apply { hint = "192.168.1.10"; textSize = 20f; setTextColor(Color.WHITE); setSingleLine(true) }
        addLabeled("Host / IP", formHost)

        formPort = EditText(context).apply {
            hint = "445"
            setText("445")
            textSize = 20f
            setTextColor(Color.WHITE)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        addLabeled("Porta", formPort)

        formShare = EditText(context).apply { hint = "Videos"; textSize = 20f; setTextColor(Color.WHITE); setSingleLine(true) }
        addLabeled("Share", formShare)

        formGuest = CheckBox(context).apply {
            text = "Convidado / anonimo (sem usuario/senha)"
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        form.addView(formGuest)

        formUser = EditText(context).apply { hint = "usuario"; textSize = 20f; setTextColor(Color.WHITE); setSingleLine(true) }
        addLabeled("Usuario", formUser)

        formPass = EditText(context).apply {
            hint = "senha"
            textSize = 20f
            setTextColor(Color.WHITE)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        addLabeled("Senha", formPass)

        formDomain = EditText(context).apply { hint = "(opcional)"; textSize = 20f; setTextColor(Color.WHITE); setSingleLine(true) }
        addLabeled("Dominio", formDomain)

        formGuest.setOnCheckedChangeListener { _, checked ->
            formUser.isEnabled = !checked
            formPass.isEnabled = !checked
            if (checked) {
                formUser.setText("")
                formPass.setText("")
            }
        }

        formStatus = TextView(context).apply {
            text = ""
            textSize = 16f
            setTextColor(Color.YELLOW)
            setPadding(0, 12, 0, 8)
        }
        form.addView(formStatus)

        val btnTestSave = Button(context).apply {
            text = "Testar e salvar"
            textSize = 20f
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
            serversContainer.addView(TextView(context).apply {
                text = "(nenhum servidor salvo)"
                textSize = 18f
                setTextColor(Color.GRAY)
            })
            return
        }
        servers.forEach { server ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 12, 8, 12)
            }
            val label = TextView(context).apply {
                text = "🖥 ${server.name}  (${if (server.isGuest) "guest" else server.username})"
                textSize = 20f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                setOnClickListener { startBrowse(server) }
            }
            val btnRemove = Button(context).apply {
                text = "✕"
                textSize = 18f
                setOnClickListener {
                    credentialStore.remove(server.id)
                    refreshServerList()
                }
            }
            row.addView(label)
            row.addView(btnRemove)
            serversContainer.addView(row)
        }
    }

    // ---------- Navegacao de diretorios SMB ----------

    private fun buildBrowseArea(): LinearLayout {
        val area = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(16, 16, 16, 16)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnBack = Button(context).apply {
            text = "◀"
            textSize = 20f
            setOnClickListener { browseUp() }
        }
        breadcrumbLabel = TextView(context).apply {
            text = ""
            textSize = 18f
            setTextColor(Color.LTGRAY)
            setPadding(16, 0, 0, 0)
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
        entriesContainer.addView(TextView(context).apply {
            text = "Carregando..."
            textSize = 18f
            setTextColor(Color.GRAY)
        })

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeSmbListDirectory(
                    server.host, server.port, server.username, server.password, server.domain,
                    server.share, browsePath
                )
            }
            entriesContainer.removeAllViews()

            if (result.startsWith("ERROR:")) {
                entriesContainer.addView(TextView(context).apply {
                    text = "⚠ ${result.removePrefix("ERROR:")}"
                    textSize = 18f
                    setTextColor(Color.YELLOW)
                })
                return@launch
            }

            val lines = result.split("\n").filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                entriesContainer.addView(TextView(context).apply {
                    text = "(vazio)"
                    textSize = 18f
                    setTextColor(Color.GRAY)
                })
                return@launch
            }

            lines.forEach { line ->
                val parts = line.split("\t")
                val name = parts.getOrElse(0) { return@forEach }
                val isDir = parts.getOrNull(1) == "1"
                val row = TextView(context).apply {
                    text = if (isDir) "📁 $name" else "🎬 $name"
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    setPadding(8, 12, 8, 12)
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
}
