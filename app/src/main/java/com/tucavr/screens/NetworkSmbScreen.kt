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
import com.tucavr.filebrowser.MediaType
import com.tucavr.filebrowser.MediaTypeFilter
import com.tucavr.filebrowser.NetworkFolderProber
import com.tucavr.filebrowser.NetworkThumbnailGenerator
import com.tucavr.filebrowser.ViewMode
import com.tucavr.filebrowser.mediaTypeForExtension
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

    private val folderConfigStore = FolderConfigStore(context)
    private var adapter: FileAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var countLabel: android.widget.TextView? = null
    private var emptyContainer: LinearLayout? = null
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

        if (prefill != null) {
            if (prefill.host.isNotEmpty()) fieldHost.setText(prefill.host)
            if (prefill.port > 0) fieldPort.setText(prefill.port.toString())
            if (prefill.path.isNotEmpty()) fieldShare.setText(prefill.path.trimStart('/'))
        }

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

        val statusText =
            VoidText.body(context, "", sizeSp = 14f, secondary = true).apply {
                setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f))
            }

        val btnSave =
            VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                text = context.getString(R.string.network_smb_btn_test_save)
                textSize = 16f
                minHeight = VoidTheme.dpToPx(context, 48f)
                setOnClickListener {
                    if (!form.validate()) return@setOnClickListener

                    val hostStr = fieldHost.getText().trim()
                    val portStr = fieldPort.getText().trim()
                    val port = if (portStr.isEmpty()) 445 else (portStr.toIntOrNull() ?: 445)
                    val share = fieldShare.getText().trim()
                    val isGuest = guestCheckbox.isChecked
                    val user = fieldUser.getText().trim()
                    val pass = fieldPass.getText().trim()
                    val domain = fieldDomain.getText().trim()
                    testAndSave(hostStr, port, share, isGuest, user, pass, domain, statusText) {
                        form.clearAll()
                        statusText.text = context.resources.getQuantityString(R.plurals.network_smb_form_status_connected, 1, 1)
                        onSaved()
                    }
                }
            }

        form.onFormSubmit = { btnSave.performClick() }

        form.addView(statusText)
        form.addView(
            btnSave,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = VoidTheme.dpToPx(context, 8f) },
        )

        return form
    }

    private fun testAndSave(
        host: String,
        port: Int,
        share: String,
        guest: Boolean,
        username: String,
        password: String,
        domain: String,
        statusView: android.widget.TextView,
        onSaved: () -> Unit,
    ) {
        val effectiveUser = if (guest) "" else username
        val effectivePass = if (guest) "" else password

        if (host.isEmpty()) {
            statusView.text = context.getString(R.string.network_smb_form_status_host_required)
            return
        }
        statusView.text = context.getString(R.string.network_smb_form_status_connecting)
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    activity.nativeSmbListShares(host, port, effectiveUser, effectivePass, domain)
                }
            if (result.startsWith("ERROR:")) {
                statusView.text =
                    context.getString(
                        R.string.network_smb_form_status_error_format, result.removePrefix("ERROR:"),
                    )
                return@launch
            }
            val shares = result.split("\n").filter { it.isNotBlank() }
            statusView.text =
                context.resources.getQuantityString(
                    R.plurals.network_smb_form_status_connected, shares.size, shares.size,
                )
            val server =
                SmbServer(
                    id = credentialStore.newId(),
                    name = if (share.isNotEmpty()) "$host/$share" else host,
                    host = host,
                    port = port,
                    share = share.ifEmpty { shares.firstOrNull() ?: "" },
                    username = effectiveUser,
                    password = effectivePass,
                    domain = domain,
                )
            credentialStore.save(server)
            onSaved()
        }
    }

    // ---- Navegação de diretório SMB moderna ----

    fun renderFiles(server: SmbServer) {
        browsingServer = server
        val folderKey = "smb://${server.name}/${server.share}/$browsePath"
        currentConfig = folderConfigStore.getConfigFor(folderKey)

        val root = VoidPanelChrome.newRoot(context)
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
                showDateFilter = false,
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
        emptyContainer =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                visibility = View.GONE

                addView(
                    VoidText.body(context, context.getString(R.string.network_files_empty), sizeSp = 16f, secondary = true).apply {
                        gravity = Gravity.CENTER
                    },
                )

                addView(
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
                    },
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

    private fun loadDirectory(server: SmbServer) {
        val requestedPath = browsePath
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    activity.nativeSmbListDirectory(
                        server.host,
                        server.port,
                        server.username,
                        server.password,
                        server.domain,
                        server.share,
                        requestedPath,
                    )
                }
            if (browsingServer != server || browsePath != requestedPath) return@launch

            if (result.startsWith("ERROR:")) {
                cachedRawEntries = emptyList()
                applyFiltersAndSort()
                return@launch
            }

            val lines = result.split("\n").filter { it.isNotBlank() }
            val entries =
                lines.mapNotNull { line ->
                    val parts = line.split("\t")
                    val name = parts.getOrNull(0) ?: return@mapNotNull null
                    val isDir = parts.getOrNull(1) == "1"
                    val sizeBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    val childPath = if (requestedPath.isEmpty()) name else "$requestedPath/$name"

                    val type =
                        if (isDir) {
                            MediaType.DIRECTORY
                        } else {
                            mediaTypeForExtension(name.substringAfterLast('.', "")) ?: MediaType.VIDEO
                        }
                    val f3d =
                        if (type == MediaType.VIDEO) {
                            MediaFilterEngine.detectFormat3DFromFilename(
                                name,
                            )
                        } else {
                            com.tucavr.filebrowser.Format3DType.FLAT_2D
                        }

                    MediaEntry(
                        name = name,
                        path = childPath,
                        sizeBytes = sizeBytes,
                        lastModified = 0L,
                        type = type,
                        format3DHint = f3d,
                    )
                }

            // Poda pastas sem mídia reprodutível (ver NetworkFolderProber) -- espera
            // resolver na primeira visita, cache-first nas seguintes.
            val pruned =
                NetworkFolderProber.pruneEmptyFolders(
                    context = context,
                    sourceKind = "smb",
                    entries = entries,
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
                )
            if (browsingServer != server || browsePath != requestedPath) return@launch

            cachedRawEntries = pruned
            applyFiltersAndSort()
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
            context.getString(
                R.string.browser_results_count_format, sorted.size, cachedRawEntries.size,
            )

        val isEmpty = sorted.isEmpty() && !showUp
        recyclerView?.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyContainer?.visibility = if (isEmpty) View.GONE else View.GONE
    }
}
