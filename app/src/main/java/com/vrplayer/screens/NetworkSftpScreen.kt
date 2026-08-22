package com.vrplayer.screens

import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vrplayer.R
import com.vrplayer.VRActivity
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidListRow
import com.vrplayer.designsystem.VoidPanelChrome
import com.vrplayer.designsystem.VoidText
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.filebrowser.MediaType
import com.vrplayer.filebrowser.NetworkThumbnailGenerator
import com.vrplayer.filebrowser.mediaTypeForExtension
import com.vrplayer.navigation.Destination
import com.vrplayer.navigation.PlaybackSource
import com.vrplayer.network.SftpCredentialStore
import com.vrplayer.network.SftpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Responsável pela aba SFTP e pela navegação de diretório SFTP (T6.4/T6.2).
 *
 * Espelha [NetworkFtpScreen] com autenticação por chave privada (T6.2)
 * no lugar de "anônimo" (SFTP não tem essa convenção).
 *
 * Estado de drill-down ([browsingServer], [browsePath]) isolado aqui.
 */
class NetworkSftpScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val credentialStore: SftpCredentialStore,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit
) {

    var browsingServer: SftpServer? = null
    var browsePath: String = ""

    // ---- Aba SFTP: lista de servidores ----

    fun buildPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        page.addView(VoidText.title(context, context.getString(R.string.network_sftp_saved_servers_header), sizeSp = 20f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
        })

        val serversContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(serversContainer)

        fun refreshServerList() {
            serversContainer.removeAllViews()
            val servers = credentialStore.list()
            if (servers.isEmpty()) {
                serversContainer.addView(
                    VoidText.body(context, context.getString(R.string.network_sftp_empty), sizeSp = 16f, secondary = true)
                )
                return
            }
            servers.forEach { server ->
                val row = buildServerRow(
                    labelText = context.getString(R.string.network_sftp_row_label_format, server.name),
                    metaText  = if (server.usesKeyAuth) context.getString(R.string.network_sftp_key_auth_label) else server.username,
                    onConnect = {
                        browsingServer = server
                        browsePath = ""
                        onNavigate(Destination.NetworkSftpFiles(server, ""))
                    },
                    onRemove = {
                        credentialStore.remove(server.id)
                        refreshServerList()
                    }
                )
                serversContainer.addView(row)
            }
        }

        val addForm = buildAddServerForm { refreshServerList() }
        addForm.visibility = View.GONE

        val btnAdd = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.network_sftp_btn_add_server)
            textSize = 18f
            setOnClickListener { addForm.visibility = if (addForm.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        page.addView(btnAdd, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 8f) })
        page.addView(addForm)

        refreshServerList()
        return wrapInScroll(page)
    }

    private fun buildAddServerForm(onSaved: () -> Unit): LinearLayout {
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(VoidTheme.colorSurface)
            val pad = VoidTheme.dpToPx(context, 16f); setPadding(pad, pad, pad, pad)
        }

        fun addLabeled(label: String, field: android.widget.EditText) {
            form.addView(VoidText.mono(context, label, sizeSp = 13f).apply {
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, 0)
            })
            form.addView(field)
            form.addView(
                UiHelpers.buildPasteButton(context, activity, field),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = VoidTheme.dpToPx(context, 4f) }
            )
        }

        val fHost = UiHelpers.buildVoidEditText(context, "192.168.1.10", host)
        addLabeled(context.getString(R.string.network_sftp_form_host_label), fHost)

        val fPort = UiHelpers.buildVoidEditText(context, "22", host).apply {
            setText("22"); inputType = InputType.TYPE_CLASS_NUMBER
        }
        addLabeled(context.getString(R.string.network_sftp_form_port_label), fPort)

        val fUser = UiHelpers.buildVoidEditText(context, context.getString(R.string.network_sftp_form_user_hint), host)
        addLabeled(context.getString(R.string.network_sftp_form_user_label), fUser)

        val fPass = UiHelpers.buildVoidEditText(context, context.getString(R.string.network_sftp_form_pass_hint), host).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val passLabel = VoidText.mono(context, context.getString(R.string.network_sftp_form_pass_label), sizeSp = 13f).apply {
            setPadding(0, VoidTheme.dpToPx(context, 8f), 0, 0)
        }
        form.addView(passLabel)
        form.addView(fPass)
        form.addView(
            UiHelpers.buildPasteButton(context, activity, fPass),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = VoidTheme.dpToPx(context, 4f) }
        )

        // T6.2: chave privada colada como texto PEM. Multi-linha para caber
        // um PEM real; botão de colar reusa o mesmo padrão das outras abas.
        val keyLabel = VoidText.mono(context, context.getString(R.string.network_sftp_form_key_label), sizeSp = 13f).apply {
            setPadding(0, VoidTheme.dpToPx(context, 8f), 0, 0)
            visibility = View.GONE
        }
        val fKey = UiHelpers.buildVoidEditText(context, context.getString(R.string.network_sftp_form_key_hint), host).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; maxLines = 6
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
                    fKey.setText(clip.getItemAt(0).coerceToText(activity).toString())
                }
            }
        }
        form.addView(keyLabel)
        form.addView(fKey)
        form.addView(btnPasteKey, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 4f) })

        val fUseKey = CheckBox(context).apply {
            text = context.getString(R.string.network_sftp_form_use_key_checkbox)
            textSize = 16f; setTextColor(VoidTheme.colorText)
        }
        form.addView(fUseKey, form.indexOfChild(passLabel))
        fUseKey.setOnCheckedChangeListener { _, useKey ->
            passLabel.visibility   = if (useKey) View.GONE else View.VISIBLE
            fPass.visibility       = if (useKey) View.GONE else View.VISIBLE
            keyLabel.visibility    = if (useKey) View.VISIBLE else View.GONE
            fKey.visibility        = if (useKey) View.VISIBLE else View.GONE
            btnPasteKey.visibility = if (useKey) View.VISIBLE else View.GONE
        }

        val formStatus = VoidText.body(context, "", sizeSp = 14f, secondary = true).apply {
            setPadding(0, VoidTheme.dpToPx(context, 12f), 0, VoidTheme.dpToPx(context, 8f))
        }
        form.addView(formStatus)

        form.addView(VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.network_sftp_btn_test_save)
            textSize = 18f
            setOnClickListener {
                testAndSave(
                    host       = fHost.text.toString().trim(),
                    port       = fPort.text.toString().toIntOrNull() ?: 22,
                    username   = fUser.text.toString(),
                    password   = if (fUseKey.isChecked) "" else fPass.text.toString(),
                    privateKey = if (fUseKey.isChecked) fKey.text.toString().trim() else null,
                    statusView = formStatus,
                    onSaved    = { onSaved(); form.visibility = View.GONE }
                )
            }
        })

        return form
    }

    /**
     * Conecta ao servidor SFTP antes de salvar (mesmo raciocínio de SMB/FTP).
     * Valida que pelo menos uma forma de autenticação foi fornecida.
     */
    private fun testAndSave(
        host: String, port: Int, username: String, password: String,
        privateKey: String?, statusView: android.widget.TextView, onSaved: () -> Unit
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
                id         = credentialStore.newId(),
                name       = host,
                host       = host,
                port       = port,
                username   = username,
                password   = password,
                privateKey = privateKey
            )
            credentialStore.save(server)
            onSaved()
        }
    }

    // ---- Navegação de diretório SFTP ----

    fun renderFiles(server: SftpServer) {
        browsingServer = server
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title    = server.name,
                subtitle = "/$browsePath",
                onBack = { onBack() }
            )
        )

        val entriesContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(entriesContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroller)
        host.showScreen(root)
        loadDirectory(server, entriesContainer)
    }

    fun handleBack(server: SftpServer): Boolean {
        if (browsePath.isNotEmpty()) {
            browsePath = browsePath.substringBeforeLast('/', missingDelimiterValue = "")
            renderFiles(server)
            return true
        }
        return false
    }

    private fun loadDirectory(server: SftpServer, entriesContainer: LinearLayout) {
        entriesContainer.removeAllViews()
        entriesContainer.addView(
            VoidText.body(context, context.getString(R.string.network_files_loading), sizeSp = 16f, secondary = true)
        )

        val requestedPath = browsePath
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeSftpListDirectory(
                    server.host, server.port, server.username, server.password,
                    server.privateKey ?: "", requestedPath
                )
            }
            if (browsingServer != server || browsePath != requestedPath) return@launch
            entriesContainer.removeAllViews()

            if (result.startsWith("ERROR:")) {
                entriesContainer.addView(
                    VoidText.body(context, context.getString(R.string.common_warning_format, result.removePrefix("ERROR:")), sizeSp = 16f)
                )
                return@launch
            }

            val lines = result.split("\n")
                .filter { it.isNotBlank() }
                .filter { line ->
                    val parts = line.split("\t")
                    val name = parts.getOrNull(0) ?: return@filter false
                    isVisibleEntry(name, isDir = parts.getOrNull(1) == "1")
                }

            if (lines.isEmpty()) {
                entriesContainer.addView(
                    VoidText.body(context, context.getString(R.string.network_files_empty), sizeSp = 16f, secondary = true)
                )
                return@launch
            }

            lines.forEach { line ->
                val parts     = line.split("\t")
                val name      = parts.getOrElse(0) { return@forEach }
                val isDir     = parts.getOrNull(1) == "1"
                val sizeBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val childPath = if (requestedPath.isEmpty()) name else "$requestedPath/$name"

                val row = VoidListRow(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
                    bind(
                        if (isDir) context.getString(R.string.common_row_dir_format, name).trim()
                        else       context.getString(R.string.common_row_video_format, name).trim(),
                        showThumbnailSlot = !isDir,
                        iconResId = if (isDir) R.drawable.ic_folder else R.drawable.ic_movie
                    )
                    setOnClickListener {
                        if (isDir) {
                            browsePath = childPath
                            renderFiles(server)
                        } else {
                            val source = PlaybackSource.Sftp(server, childPath, sizeBytes)
                            onNavigate(Destination.FileDetail(source, name, sizeBytes))
                        }
                    }
                }
                entriesContainer.addView(row)

                if (!isDir) {
                    val source = PlaybackSource.Sftp(server, childPath, sizeBytes)
                    scope.launch {
                        val bitmap = NetworkThumbnailGenerator.getThumbnail(context, activity, source)
                        if (bitmap != null && browsingServer == server && browsePath == requestedPath) {
                            row.thumbnail.setImageBitmap(bitmap)
                            row.thumbnail.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun isVisibleEntry(name: String, isDir: Boolean): Boolean =
        isDir || mediaTypeForExtension(name.substringAfterLast('.', "")) == MediaType.VIDEO

    private fun buildServerRow(
        labelText: String, metaText: String,
        onConnect: () -> Unit, onRemove: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f))

        addView(VoidListRow(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            bind(labelText, meta = metaText, showThumbnailSlot = false, iconResId = R.drawable.ic_lock)
            setOnClickListener { onConnect() }
        })
        addView(VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = ""
            setIcon(R.drawable.icon_x); textSize = 16f; minHeight = 0
            val pad = VoidTheme.dpToPx(context, 12f); setPadding(pad, pad, pad, pad)
            setOnClickListener { onRemove() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = VoidTheme.dpToPx(context, 8f) })
    }

    private fun wrapInScroll(page: View): View =
        ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(page)
        }
}
