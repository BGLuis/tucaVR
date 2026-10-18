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
import com.tucavr.filebrowser.NetworkThumbnailGenerator
import com.tucavr.filebrowser.ViewMode
import com.tucavr.filebrowser.canReuseBrowser
import com.tucavr.filebrowser.sortMediaEntries
import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.SmbCredentialStore
import com.tucavr.network.SmbServer
import com.tucavr.screens.adapters.FileAdapter
import com.tucavr.screens.components.MediaBrowserToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Responsável pela aba SMB e pela navegação avançada de diretório SMB.
 *
 * Encapsula:
 * - Lista de servidores salvos ([buildPage])
 * - Formulário "Adicionar servidor" ([buildAddServerForm])
 * - Teste e persistência de credenciais ([testAndSave])
 * - Navegação de diretório moderna com busca, filtros, Grade/Lista e 1-Tap Play ([renderFiles])
 */
class NetworkSmbScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val credentialStore: SmbCredentialStore,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit,
    private val onPlay: (PlaybackSource) -> Unit = { source -> onNavigate(Destination.Player(source)) },
) {
    var browsingServer: SmbServer? = null
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
                label = context.getString(R.string.network_smb_form_host_label),
                hint = "192.168.1.100",
                kind = VoidFieldKind.TEXT,
                validator = FieldValidators.required(context.getString(R.string.network_smb_form_status_host_required)),
            )
        val fieldPort =
            form.field(
                host = host,
                label = context.getString(R.string.network_smb_form_port_label),
                hint = "445",
                kind = VoidFieldKind.NUMBER,
                validator = FieldValidators.port(context.getString(R.string.field_error_invalid_port)),
            )
        val fieldShare =
            form.field(
                host = host,
                label = context.getString(R.string.network_smb_form_share_label),
                hint = "Videos",
                kind = VoidFieldKind.TEXT,
            )
        val fieldName =
            form.field(
                host = host,
                label = context.getString(R.string.network_smb_form_name_label),
                hint = context.getString(R.string.network_smb_form_name_hint),
                kind = VoidFieldKind.TEXT,
            )

        val fieldUser =
            VoidTextField(
                context = context,
                host = host,
                label = context.getString(R.string.network_smb_form_user_label),
                hint = "user",
                kind = VoidFieldKind.TEXT,
            )
        val fieldPass =
            VoidTextField(
                context = context,
                host = host,
                label = context.getString(R.string.network_smb_form_pass_label),
                hint = "",
                kind = VoidFieldKind.PASSWORD,
                actions = setOf(VoidFieldAction.PASTE, VoidFieldAction.REVEAL, VoidFieldAction.CONTEXT_MENU),
            )

        val guestCheckbox =
            CheckBox(context).apply {
                text = context.getString(R.string.network_smb_form_guest_checkbox)
                setTextColor(VoidTheme.colorText)
                textSize = 14f
                typeface = VoidTheme.typefaceBody
                val pad = VoidTheme.dpToPx(context, 8f)
                setPadding(pad, pad, pad, pad)
                setOnCheckedChangeListener { _, isChecked ->
                    fieldUser.visibility = if (isChecked) View.GONE else View.VISIBLE
                    fieldPass.visibility = if (isChecked) View.GONE else View.VISIBLE
                }
            }

        form.addView(guestCheckbox)
        form.addField(fieldUser)
        form.addField(fieldPass)

        val fieldDomain =
            form.field(
                host = host,
                label = context.getString(R.string.network_smb_form_domain_label),
                hint = "WORKGROUP",
                kind = VoidFieldKind.TEXT,
            )

        if (prefill != null) {
            if (prefill.host.isNotEmpty()) fieldHost.setText(prefill.host)
            if (prefill.port > 0) fieldPort.setText(prefill.port.toString())
            if (prefill.name.isNotEmpty()) fieldName.setText(prefill.name)
            if (prefill.path.isNotEmpty()) fieldShare.setText(prefill.path.trimStart('/'))
            if (prefill.username.isNotEmpty()) fieldUser.setText(prefill.username)
            if (prefill.password.isNotEmpty()) fieldPass.setText(prefill.password)
            if (prefill.domain.isNotEmpty()) fieldDomain.setText(prefill.domain)
            if (prefill.existingServerId != null && prefill.username.isEmpty()) {
                guestCheckbox.isChecked = true
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
                        statusText.text = context.getString(R.string.network_smb_form_status_host_required)
                        return@setOnClickListener
                    }
                    val portStr = fieldPort.getText().trim()
                    val port = if (portStr.isEmpty()) 445 else (portStr.toIntOrNull() ?: 445)
                    val isGuest = guestCheckbox.isChecked
                    val user = if (isGuest) "" else fieldUser.getText().trim()
                    val pass = if (isGuest) "" else fieldPass.getText().trim()
                    val domain = fieldDomain.getText().trim()
                    statusText.text = context.getString(R.string.network_smb_form_status_connecting)
                    scope.launch {
                        val result =
                            withContext(Dispatchers.IO) {
                                activity.nativeSmbListShares(hostStr, port, user, pass, domain)
                            }
                        if (result.startsWith("ERROR:")) {
                            statusText.text =
                                context.getString(
                                    R.string.network_smb_form_status_error_format,
                                    result.removePrefix("ERROR:"),
                                )
                        } else {
                            val shares = result.split("\n").filter { it.isNotBlank() }
                            statusText.text =
                                context.resources.getQuantityString(
                                    R.plurals.network_smb_form_status_connected,
                                    shares.size,
                                    shares.size,
                                )
                            if (fieldShare.getText().trim().isEmpty() && shares.isNotEmpty()) {
                                fieldShare.setText(shares.first())
                            }
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
                    val port = if (portStr.isEmpty()) 445 else (portStr.toIntOrNull() ?: 445)
                    val share = fieldShare.getText().trim()
                    val isGuest = guestCheckbox.isChecked
                    val user = if (isGuest) "" else fieldUser.getText().trim()
                    val pass = if (isGuest) "" else fieldPass.getText().trim()
                    val domain = fieldDomain.getText().trim()

                    val serverId = prefill?.existingServerId ?: credentialStore.newId()
                    val nameText = fieldName.getText().trim()
                    val defaultName = if (share.isNotEmpty()) "$hostStr/$share" else hostStr
                    val finalName = if (nameText.isNotEmpty()) nameText else defaultName
                    val server =
                        SmbServer(
                            id = serverId,
                            name = finalName,
                            host = hostStr,
                            port = port,
                            share = share,
                            username = user,
                            password = pass,
                            domain = domain,
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

    // ---- Navegação de diretório SMB moderna ----

    fun renderFiles(server: SmbServer) {
        val sameServer = browsingServer == server && recyclerView != null
        val previousConfig = currentConfig
        browsingServer = server
        val folderKey = "smb://${server.name}/${server.share}/$browsePath"
        currentConfig = folderConfigStore.getConfigFor(folderKey)

        if (canReuseBrowser(sameServer, rootLayout?.parent != null, previousConfig, currentConfig)) {
            rootLayout?.findViewById<android.widget.TextView>(VoidPanelChrome.ID_SUBTITLE)?.text = "${server.share}/$browsePath"
            mediaToolbar?.clearSearch()
            searchQuery = ""
            mediaToolbar?.setViewMode(currentConfig.viewMode)
            updateLayoutManager()
            loadDirectory(server)
            return
        }

        val root = VoidPanelChrome.newRoot(context)
        rootLayout = root
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = server.name,
                subtitle = "${server.share}/$browsePath",
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
                    loadDirectory(server, forceRefresh = true)
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
        directoryLoader.trackUserInteraction(recycler)
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
                    onPlay(PlaybackSource.Smb(server, entry.path, entry.sizeBytes))
                },
                onFileSelected = { entry ->
                    val source = PlaybackSource.Smb(server, entry.path, entry.sizeBytes)
                    host.showFileDetail(
                        entry = entry,
                        source = source,
                        onPlay = { resumeAtMs ->
                            activity.playSmb(server, entry.path, entry.sizeBytes, resumeAtMs)
                            activity.requestUiPanelHidden()
                        },
                    )
                },
                contextRailHost = host,
                thumbnailLoader = { entry ->
                    val source = PlaybackSource.Smb(server, entry.path, entry.sizeBytes)
                    NetworkThumbnailGenerator.getThumbnail(context, activity, source)
                },
                metadataBadgeLoader = { entry ->
                    MediaMetadataReader.readCachedSummary(context, PlaybackSource.Smb(server, entry.path, entry.sizeBytes))
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

    fun handleBack(server: SmbServer): Boolean {
        if (browsePath.isNotEmpty()) {
            browsePath = browsePath.substringBeforeLast('/', missingDelimiterValue = "")
            mediaToolbar?.clearSearch()
            searchQuery = ""
            renderFiles(server)
            return true
        }
        return false
    }

    private fun loadDirectory(
        server: SmbServer,
        forceRefresh: Boolean = false,
    ) {
        directoryLoader.loadDirectory(
            forceRefresh = forceRefresh,
            historyFor = { entry -> activity.historyTracker.findExisting(PlaybackSource.Smb(server, entry.path, entry.sizeBytes)) },
            path = browsePath,
            fetchRaw = {
                activity.nativeSmbListDirectory(
                    server.host,
                    server.port,
                    server.username,
                    server.password,
                    server.domain,
                    server.share,
                    browsePath,
                )
            },
            proberConfig =
                NetworkDirectoryLoader.FolderProberConfig(
                    context = context,
                    sourceKind = "smb",
                    folderKeyFor = { entry -> CacheKeys.forFolder("smb", server.host, server.port, server.share, entry.path) },
                    scanFnFor = { entry ->
                        {
                            activity.nativeSmbScanFolderHasMedia(
                                server.host,
                                server.port,
                                server.username,
                                server.password,
                                server.domain,
                                server.share,
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
                val msg = context.getString(R.string.network_smb_form_status_error_format, state.message)
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
