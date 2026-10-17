package com.tucavr.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.designsystem.FieldValidators
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidFieldAction
import com.tucavr.designsystem.VoidFieldKind
import com.tucavr.designsystem.VoidForm
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTextField
import com.tucavr.designsystem.VoidTheme
import com.tucavr.filebrowser.CacheKeys
import com.tucavr.filebrowser.DateFilter
import com.tucavr.filebrowser.FolderConfig
import com.tucavr.filebrowser.FolderConfigStore
import com.tucavr.filebrowser.Format3DFilter
import com.tucavr.filebrowser.MediaEntry
import com.tucavr.filebrowser.MediaFilterEngine
import com.tucavr.filebrowser.MediaMetadataReader
import com.tucavr.filebrowser.MediaTypeFilter
import com.tucavr.filebrowser.NetworkDirectoryLoader
import com.tucavr.filebrowser.NetworkDirectoryState
import com.tucavr.filebrowser.NetworkFolderProber
import com.tucavr.filebrowser.NetworkThumbnailGenerator
import com.tucavr.filebrowser.ViewMode
import com.tucavr.filebrowser.sortMediaEntries
import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.SftpCredentialStore
import com.tucavr.network.SftpServer
import com.tucavr.screens.adapters.FileAdapter
import com.tucavr.screens.components.MediaBrowserToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Responsável pela aba SFTP e pela navegação avançada de diretório SFTP (T6.4).
 * Inclui formulário de adição com suporte a chave privada, busca com debounce,
 * filtros de mídia/3D, alternância Grade/Lista, ordenação e reprodução com 1 toque.
 */
class NetworkSftpScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val credentialStore: SftpCredentialStore,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit,
    private val onPlay: (PlaybackSource) -> Unit = { source -> onNavigate(Destination.Player(source)) },
) {
    var browsingServer: SftpServer? = null
    var browsePath: String = ""

    private val directoryLoader = NetworkDirectoryLoader(scope)
    private val folderConfigStore = FolderConfigStore(context)
    private var adapter: FileAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var rootLayout: LinearLayout? = null
    private var countLabel: android.widget.TextView? = null
    private var emptyContainer: LinearLayout? = null
    private var emptyLabel: android.widget.TextView? = null
    private var clearFiltersButton: VoidButton? = null
    private var mediaToolbar: MediaBrowserToolbar? = null

    // Estados de busca e filtros de rede
    private var searchQuery = ""
    private var currentTypeFilter = MediaTypeFilter.ALL
    private var currentFormat3DFilter = Format3DFilter.ALL
    private var currentDateFilter = DateFilter.ALL
    private var currentConfig = FolderConfig()
    private var cachedRawEntries: List<MediaEntry> = emptyList()

    // ---- Formulario "Adicionar servidor" (usado pela lista unificada em NetworkHomeScreen) ----

    fun buildAddServerForm(
        onSaved: () -> Unit,
        prefill: ServerPrefill? = null,
    ): View {
        val form = VoidForm(context)

        val fieldHost =
            form.field(
                host = host,
                label = context.getString(R.string.network_sftp_form_host_label),
                hint = "sftp.example.com",
                kind = VoidFieldKind.TEXT,
                validator = FieldValidators.required(context.getString(R.string.network_sftp_form_status_host_required)),
            )
        val fieldPort =
            form.field(
                host = host,
                label = context.getString(R.string.network_sftp_form_port_label),
                hint = "22",
                kind = VoidFieldKind.NUMBER,
                validator = FieldValidators.port(context.getString(R.string.field_error_invalid_port)),
            )
        val fieldName =
            form.field(
                host = host,
                label = context.getString(R.string.network_sftp_form_name_label),
                hint = context.getString(R.string.network_sftp_form_name_hint),
                kind = VoidFieldKind.TEXT,
            )

        val fieldUser =
            form.field(
                host = host,
                label = context.getString(R.string.network_sftp_form_user_label),
                hint = "user",
                kind = VoidFieldKind.TEXT,
                validator = FieldValidators.required(context.getString(R.string.field_error_required)),
            )
        val fieldPass =
            VoidTextField(
                context = context,
                host = host,
                label = context.getString(R.string.network_sftp_form_pass_label),
                hint = "••••••••",
                kind = VoidFieldKind.PASSWORD,
                actions = setOf(VoidFieldAction.PASTE, VoidFieldAction.REVEAL, VoidFieldAction.CONTEXT_MENU),
            )
        val fieldKey =
            VoidTextField(
                context = context,
                host = host,
                label = context.getString(R.string.network_sftp_form_key_label),
                hint = context.getString(R.string.network_sftp_form_key_hint),
                kind = VoidFieldKind.MULTILINE,
                actions = setOf(VoidFieldAction.PASTE, VoidFieldAction.CLEAR, VoidFieldAction.CONTEXT_MENU),
            )

        val keyCheckbox =
            CheckBox(context).apply {
                text = context.getString(R.string.network_sftp_form_use_key_checkbox)
                setTextColor(VoidTheme.colorText)
                textSize = 14f
                typeface = VoidTheme.typefaceBody
                val pad = VoidTheme.dpToPx(context, 8f)
                setPadding(pad, pad, pad, pad)
                setOnCheckedChangeListener { _, isChecked ->
                    fieldPass.visibility = if (isChecked) View.GONE else View.VISIBLE
                    fieldKey.visibility = if (isChecked) View.VISIBLE else View.GONE
                }
            }
        fieldKey.visibility = View.GONE

        form.addView(keyCheckbox)
        form.addField(fieldPass)
        form.addField(fieldKey)

        if (prefill != null) {
            if (prefill.host.isNotEmpty()) fieldHost.setText(prefill.host)
            if (prefill.port > 0) fieldPort.setText(prefill.port.toString())
            if (prefill.name.isNotEmpty()) fieldName.setText(prefill.name)
            if (prefill.username.isNotEmpty()) fieldUser.setText(prefill.username)
            if (!prefill.privateKey.isNullOrEmpty()) {
                keyCheckbox.isChecked = true
                fieldKey.setText(prefill.privateKey)
                fieldPass.visibility = View.GONE
                fieldKey.visibility = View.VISIBLE
            } else if (prefill.password.isNotEmpty()) {
                keyCheckbox.isChecked = false
                fieldPass.setText(prefill.password)
                fieldPass.visibility = View.VISIBLE
                fieldKey.visibility = View.GONE
            }
        }

        val statusText =
            VoidText.body(context, "", sizeSp = 14f, secondary = true).apply {
                setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f))
            }

        val buttonsRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = VoidTheme.dpToPx(context, 8f) }
            }

        val btnTest =
            VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = context.getString(R.string.network_btn_test_connection)
                setIcon(R.drawable.ic_network)
                textSize = 15f
                minHeight = VoidTheme.dpToPx(context, 48f)
                setOnClickListener {
                    val hostStr = fieldHost.getText().trim()
                    if (hostStr.isEmpty()) {
                        statusText.text = context.getString(R.string.network_sftp_form_status_host_required)
                        return@setOnClickListener
                    }
                    val portStr = fieldPort.getText().trim()
                    val port = if (portStr.isEmpty()) 22 else (portStr.toIntOrNull() ?: 22)
                    val user = fieldUser.getText().trim()
                    val useKey = keyCheckbox.isChecked
                    val pass = if (useKey) "" else fieldPass.getText().trim()
                    val key = if (useKey) fieldKey.getText().trim() else ""

                    if (useKey && key.isEmpty()) {
                        fieldKey.setError(context.getString(R.string.network_sftp_form_status_auth_required))
                        return@setOnClickListener
                    }
                    statusText.text = context.getString(R.string.network_sftp_form_status_connecting)
                    scope.launch {
                        val result =
                            withContext(Dispatchers.IO) {
                                activity.nativeSftpListDirectory(hostStr, port, user, pass, key, "")
                            }
                        if (result.startsWith("ERROR:")) {
                            statusText.text =
                                context.getString(
                                    R.string.network_sftp_form_status_error_format,
                                    result.removePrefix("ERROR:"),
                                )
                        } else {
                            statusText.text = context.getString(R.string.network_sftp_form_status_connected)
                        }
                    }
                }
            }

        val btnSave =
            VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                text = context.getString(R.string.network_btn_save)
                setIcon(R.drawable.ic_check)
                textSize = 15f
                minHeight = VoidTheme.dpToPx(context, 48f)
                setOnClickListener {
                    if (!form.validate()) return@setOnClickListener

                    val hostStr = fieldHost.getText().trim()
                    val portStr = fieldPort.getText().trim()
                    val port = if (portStr.isEmpty()) 22 else (portStr.toIntOrNull() ?: 22)
                    val user = fieldUser.getText().trim()
                    val useKey = keyCheckbox.isChecked
                    val pass = if (useKey) "" else fieldPass.getText().trim()
                    val key = if (useKey) fieldKey.getText().trim() else ""
                    val nameText = fieldName.getText().trim()
                    val finalName = if (nameText.isNotEmpty()) nameText else hostStr

                    if (useKey && key.isEmpty()) {
                        fieldKey.setError(context.getString(R.string.network_sftp_form_status_auth_required))
                        return@setOnClickListener
                    }

                    val serverId = prefill?.existingServerId ?: credentialStore.newId()
                    val server =
                        SftpServer(
                            id = serverId,
                            name = finalName,
                            host = hostStr,
                            port = port,
                            username = user,
                            password = pass,
                            privateKey = if (useKey) key else null,
                        )
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            credentialStore.save(server)
                        }
                        form.clearAll()
                        onSaved()
                    }
                }
            }

        form.onFormSubmit = { btnSave.performClick() }

        buttonsRow.addView(
            btnTest,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = VoidTheme.dpToPx(context, 8f)
            },
        )
        buttonsRow.addView(
            btnSave,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        form.addView(statusText)
        form.addView(buttonsRow)

        return form
    }

    // ---- Navegação de diretório SFTP moderna ----

    fun renderFiles(server: SftpServer) {
        val sameServer = (browsingServer == server && rootLayout != null && recyclerView != null)
        browsingServer = server
        val folderKey = "sftp://${server.name}/$browsePath"
        currentConfig = folderConfigStore.getConfigFor(folderKey)

        if (sameServer) {
            val subtitleText = if (browsePath.isEmpty()) "/" else browsePath
            rootLayout?.findViewById<android.widget.TextView>(VoidPanelChrome.ID_SUBTITLE)?.text = subtitleText
            mediaToolbar?.clearSearch()
            searchQuery = ""
            loadDirectory(server)
            return
        }

        val root = VoidPanelChrome.newRoot(context)
        rootLayout = root
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = server.name,
                subtitle = if (browsePath.isEmpty()) "/" else browsePath,
                onBack = { onBack() },
            ),
        )

        // Barra de Ferramentas Compartilhada (Busca + Ordenação + Visualização + Filtros)
        val toolbar =
            MediaBrowserToolbar(
                context = context,
                host = host,
                activity = activity,
                scope = scope,
                initialSortBy = currentConfig.sortBy,
                initialAscending = currentConfig.ascending,
                initialViewMode = currentConfig.viewMode,
                initialTypeFilter = currentTypeFilter,
                initialFormat3DFilter = currentFormat3DFilter,
                initialDateFilter = currentDateFilter,
                showDateFilter = true,
                onQueryChanged = { query ->
                    searchQuery = query
                    applyFiltersAndSort()
                },
                onSortChanged = { newSort, newAscending ->
                    currentConfig = currentConfig.copy(sortBy = newSort, ascending = newAscending)
                    folderConfigStore.saveConfigFor(folderKey, currentConfig)
                    applyFiltersAndSort()
                },
                onViewModeChanged = { nextMode ->
                    currentConfig = currentConfig.copy(viewMode = nextMode)
                    folderConfigStore.saveConfigFor(folderKey, currentConfig)
                    updateLayoutManager()
                    applyFiltersAndSort()
                },
                onFilterChanged = { newType, newFormat3D, newDate ->
                    currentTypeFilter = newType
                    currentFormat3DFilter = newFormat3D
                    currentDateFilter = newDate
                    applyFiltersAndSort()
                },
                onRefresh = {
                    reloadDirectory(server, forceRefresh = true)
                },
            )
        mediaToolbar = toolbar
        root.addView(toolbar)

        // Contador de Resultados
        val counterView =
            VoidText.mono(context, "", sizeSp = 13f, secondary = true).apply {
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                        it.bottomMargin = VoidTheme.dpToPx(context, 6f)
                    }
            }
        countLabel = counterView
        root.addView(counterView)

        // Recycler com FileAdapter universal
        val recycler =
            RecyclerView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        recyclerView = recycler
        updateLayoutManager()

        val fileAdapter =
            FileAdapter(
                context = context,
                scope = scope,
                onUpClick = { onBack() },
                onDirectoryClick = { entry ->
                    browsePath = entry.path
                    mediaToolbar?.clearSearch()
                    searchQuery = ""
                    renderFiles(server)
                },
                onVideoClick = { entry ->
                    onPlay(PlaybackSource.Sftp(server, entry.path, entry.sizeBytes))
                },
                onFileSelected = { entry ->
                    val source = PlaybackSource.Sftp(server, entry.path, entry.sizeBytes)
                    host.showFileDetail(
                        entry = entry,
                        source = source,
                        onPlay = { resumeAtMs ->
                            activity.playSftp(server, entry.path, entry.sizeBytes, resumeAtMs)
                            activity.requestUiPanelHidden()
                        },
                    )
                },
                contextRailHost = host,
                thumbnailLoader = { entry ->
                    val source = PlaybackSource.Sftp(server, entry.path, entry.sizeBytes)
                    NetworkThumbnailGenerator.getThumbnail(context, activity, source)
                },
                metadataBadgeLoader = { entry ->
                    MediaMetadataReader.readCachedSummary(context, PlaybackSource.Sftp(server, entry.path, entry.sizeBytes))
                },
            )
        adapter = fileAdapter
        recycler.adapter = fileAdapter
        root.addView(recycler)

        // Empty State View
        val label =
            VoidText.body(context, context.getString(R.string.network_files_empty), sizeSp = 16f, secondary = true).apply {
                gravity = Gravity.CENTER
            }
        emptyLabel = label

        val clearBtn =
            VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = context.getString(R.string.browser_btn_clear_filters)
                textSize = 15f
                minHeight = VoidTheme.dpToPx(context, 48f)
                val padH = VoidTheme.dpToPx(context, 16f)
                val padV = VoidTheme.dpToPx(context, 10f)
                setPadding(padH, padV, padH, padV)
                setOnClickListener {
                    mediaToolbar?.resetFilters()
                    searchQuery = ""
                    currentTypeFilter = MediaTypeFilter.ALL
                    currentFormat3DFilter = Format3DFilter.ALL
                    currentDateFilter = DateFilter.ALL
                    applyFiltersAndSort()
                }
            }
        clearFiltersButton = clearBtn

        emptyContainer =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                visibility = View.GONE
                addView(label)
                addView(
                    clearBtn,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = VoidTheme.dpToPx(context, 12f)
                    },
                )
            }
        root.addView(emptyContainer)

        host.showScreen(root)
        loadDirectory(server)
    }

    private fun updateLayoutManager() {
        val recycler = recyclerView ?: return
        if (currentConfig.viewMode == ViewMode.GRID) {
            val gridLayout =
                GridLayoutManager(context, 3).apply {
                    spanSizeLookup =
                        object : GridLayoutManager.SpanSizeLookup() {
                            override fun getSpanSize(position: Int): Int {
                                return if (position == 0 && browsePath.isNotEmpty()) 3 else 1
                            }
                        }
                }
            recycler.layoutManager = gridLayout
        } else {
            recycler.layoutManager = LinearLayoutManager(context)
        }
    }

    fun handleBack(server: SftpServer): Boolean {
        if (browsePath.isNotEmpty()) {
            browsePath = browsePath.substringBeforeLast('/', missingDelimiterValue = "")
            mediaToolbar?.clearSearch()
            searchQuery = ""
            renderFiles(server)
            return true
        }
        return false
    }

    private fun reloadDirectory(
        server: SftpServer,
        forceRefresh: Boolean = false,
    ) {
        if (forceRefresh) {
            val keyPrefix = CacheKeys.forFolder("sftp", server.host, server.port, null, "")
            scope.launch {
                NetworkFolderProber.clearFolderCache(context, keyPrefix)
                loadDirectory(server)
            }
        } else {
            loadDirectory(server)
        }
    }

    private fun loadDirectory(server: SftpServer) {
        directoryLoader.loadDirectory(
            path = browsePath,
            fetchRaw = {
                activity.nativeSftpListDirectory(
                    server.host,
                    server.port,
                    server.username,
                    server.password,
                    server.privateKey ?: "",
                    browsePath,
                )
            },
            proberConfig =
                NetworkDirectoryLoader.FolderProberConfig(
                    context = context,
                    sourceKind = "sftp",
                    folderKeyFor = { entry -> CacheKeys.forFolder("sftp", server.host, server.port, null, entry.path) },
                    scanFnFor = { entry ->
                        {
                            activity.nativeSftpScanFolderHasMedia(
                                server.host,
                                server.port,
                                server.username,
                                server.password,
                                server.privateKey ?: "",
                                entry.path,
                            )
                        }
                    },
                ),
            onStateChange = { state ->
                renderState(state)
            },
        )
    }

    private fun renderState(state: NetworkDirectoryState) {
        when (state) {
            is NetworkDirectoryState.Loading -> {
                // Mantém a visualização atual durante carregamento
            }
            is NetworkDirectoryState.Error -> {
                cachedRawEntries = emptyList()
                val msg = context.getString(R.string.network_sftp_form_status_error_format, state.message)
                countLabel?.text = msg
                emptyLabel?.text = msg
                clearFiltersButton?.visibility = View.GONE
                recyclerView?.visibility = View.GONE
                emptyContainer?.visibility = View.VISIBLE
            }
            is NetworkDirectoryState.Empty -> {
                cachedRawEntries = emptyList()
                applyFiltersAndSort()
            }
            is NetworkDirectoryState.Success -> {
                cachedRawEntries = state.entries
                applyFiltersAndSort()
            }
        }
    }

    private fun applyFiltersAndSort() {
        val currentAdapter = adapter ?: return
        val showUp = browsePath.isNotEmpty()

        val filtered =
            cachedRawEntries.filter { entry ->
                MediaFilterEngine.matchesFilter(
                    entry = entry,
                    query = searchQuery,
                    typeFilter = currentTypeFilter,
                    format3DFilter = currentFormat3DFilter,
                    dateFilter = currentDateFilter,
                )
            }

        val sorted = sortMediaEntries(filtered, currentConfig.sortBy, currentConfig.ascending)
        currentAdapter.submit(sorted, showUp, currentConfig.viewMode, searchQuery)

        countLabel?.text =
            context.resources.getQuantityString(
                R.plurals.browser_results_count_format,
                cachedRawEntries.size,
                sorted.size,
                cachedRawEntries.size,
            )

        val hasActiveFilters =
            searchQuery.isNotEmpty() ||
                currentTypeFilter != MediaTypeFilter.ALL ||
                currentFormat3DFilter != Format3DFilter.ALL ||
                currentDateFilter != DateFilter.ALL

        emptyLabel?.text =
            context.getString(
                if (hasActiveFilters) R.string.browser_empty_search else R.string.network_files_empty,
            )
        clearFiltersButton?.visibility = if (hasActiveFilters) View.VISIBLE else View.GONE

        val isEmpty = sorted.isEmpty() && !showUp
        recyclerView?.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyContainer?.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }
}
