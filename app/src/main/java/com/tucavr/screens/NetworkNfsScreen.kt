package com.tucavr.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.designsystem.FieldValidators
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidFieldKind
import com.tucavr.designsystem.VoidForm
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme
import com.tucavr.filebrowser.CacheKeys
import com.tucavr.filebrowser.DateFilter
import com.tucavr.filebrowser.FolderConfig
import com.tucavr.filebrowser.FolderConfigStore
import com.tucavr.filebrowser.Format3DFilter
import com.tucavr.filebrowser.Format3DType
import com.tucavr.filebrowser.MediaEntry
import com.tucavr.filebrowser.MediaFilterEngine
import com.tucavr.filebrowser.MediaType
import com.tucavr.filebrowser.MediaTypeFilter
import com.tucavr.filebrowser.NetworkFolderProber
import com.tucavr.filebrowser.ViewMode
import com.tucavr.filebrowser.mediaTypeForExtension
import com.tucavr.filebrowser.sortMediaEntries
import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.SavedServer
import com.tucavr.network.SavedServerDao
import com.tucavr.network.ServerProtocol
import com.tucavr.screens.adapters.FileAdapter
import com.tucavr.screens.components.MediaBrowserToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T5.4: Responsavel pela aba NFS e pela navegacao de diretorio NFS.
 * Permite explorar exports, testar conexoes, buscar e filtrar arquivos e reproduzir diretamente.
 */
class NetworkNfsScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val savedServerDao: SavedServerDao,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit,
    private val onPlay: (PlaybackSource) -> Unit = { source -> onNavigate(Destination.Player(source)) },
) {
    var browsingServer: SavedServer? = null
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

        val hostInput =
            form.field(
                host = host,
                label = context.getString(R.string.network_nfs_form_host_label),
                hint = "192.168.1.100",
                kind = VoidFieldKind.TEXT,
                validator = FieldValidators.required(context.getString(R.string.network_nfs_form_status_host_required)),
            )

        val portInput =
            form.field(
                host = host,
                label = context.getString(R.string.network_nfs_form_port_label),
                hint = "2049",
                kind = VoidFieldKind.NUMBER,
                validator = FieldValidators.port(context.getString(R.string.field_error_invalid_port)),
            ).apply {
                setText("2049")
            }

        val exportInput =
            form.field(
                host = host,
                label = context.getString(R.string.network_nfs_form_export_label),
                hint = context.getString(R.string.network_nfs_form_export_hint),
                kind = VoidFieldKind.TEXT,
                validator = FieldValidators.required(context.getString(R.string.network_nfs_form_status_export_required)),
            )

        if (prefill != null) {
            if (prefill.host.isNotEmpty()) hostInput.setText(prefill.host)
            if (prefill.port > 0) portInput.setText(prefill.port.toString())
            if (prefill.path.isNotEmpty()) exportInput.setText(prefill.path)
        }

        val nameInput =
            form.field(
                host = host,
                label = context.getString(R.string.network_nfs_form_name_label),
                hint = context.getString(R.string.network_nfs_form_name_hint),
                kind = VoidFieldKind.TEXT,
            )

        val statusText =
            VoidText.body(context, "", sizeSp = 14f, secondary = true).apply {
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 8f))
            }

        val btnRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, 0)
            }

        val btnBrowseExports =
            VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = context.getString(R.string.network_nfs_form_btn_find_exports)
                setIcon(R.drawable.ic_search)
                textSize = 16f
                setOnClickListener {
                    val h = hostInput.getText().trim()
                    val p = portInput.getText().toIntOrNull() ?: 2049
                    if (h.isEmpty()) {
                        hostInput.setError(context.getString(R.string.network_nfs_form_status_host_required))
                        statusText.text = context.getString(R.string.network_nfs_form_status_host_required)
                        return@setOnClickListener
                    }
                    statusText.text = context.getString(R.string.network_nfs_form_status_connecting)
                    scope.launch {
                        val result =
                            withContext(Dispatchers.IO) {
                                activity.nativeNfsListExports(h, p)
                            }
                        if (result.startsWith("ERROR:")) {
                            statusText.text =
                                context.getString(
                                    R.string.network_nfs_form_status_error_format,
                                    result.removePrefix("ERROR:"),
                                )
                        } else {
                            val firstExport = result.lines().firstOrNull { it.isNotBlank() }
                            if (firstExport != null) {
                                exportInput.setText(firstExport)
                                statusText.text = context.getString(R.string.network_nfs_form_status_connected)
                            } else {
                                statusText.text = result
                            }
                        }
                    }
                }
            }

        val btnSave =
            VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                text = context.getString(R.string.network_nfs_btn_test_save)
                setIcon(R.drawable.ic_check)
                textSize = 16f
                setOnClickListener {
                    if (!form.validate()) return@setOnClickListener

                    val h = hostInput.getText().trim()
                    val p = portInput.getText().toIntOrNull() ?: 2049
                    val exp = exportInput.getText().trim()
                    val name = nameInput.getText().trim()
                    testAndSave(h, p, exp, name, statusText) {
                        form.clearAll()
                        portInput.setText("2049")
                        statusText.text = context.getString(R.string.network_nfs_form_status_connected)
                        onSaved()
                    }
                }
            }

        form.onFormSubmit = { btnSave.performClick() }

        val margin =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginEnd = VoidTheme.dpToPx(context, 8f)
            }
        btnRow.addView(btnBrowseExports, margin)
        btnRow.addView(btnSave, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        form.addView(statusText)
        form.addView(btnRow)
        return form
    }

    private fun testAndSave(
        h: String,
        p: Int,
        exp: String,
        name: String,
        statusView: android.widget.TextView,
        onSaved: () -> Unit,
    ) {
        val n = name.ifEmpty { h }

        statusView.text = context.getString(R.string.network_nfs_form_status_connecting)

        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    activity.nativeNfsListDirectory(h, p, exp, "", 3)
                }
            if (result.startsWith("ERROR:")) {
                statusView.text = context.getString(R.string.network_nfs_form_status_error_format, result.removePrefix("ERROR:"))
                return@launch
            }

            val server =
                SavedServer(
                    name = n,
                    protocol = ServerProtocol.NFS,
                    host = h,
                    port = p,
                    path = exp,
                    isAutoDiscovered = false,
                    lastConnectedAt = System.currentTimeMillis(),
                )

            withContext(Dispatchers.IO) {
                savedServerDao.insert(server)
            }

            statusView.text = context.getString(R.string.network_nfs_form_status_connected)
            onSaved()
        }
    }

    // ---- Navegacao de arquivos NFS ----

    fun renderFiles(
        server: SavedServer,
        subPath: String = browsePath,
    ) {
        browsingServer = server
        browsePath = subPath
        val folderKey = folderKey(server, subPath)
        currentConfig = folderConfigStore.getConfigFor(folderKey)

        val root = VoidPanelChrome.newRoot(context)
        val titleText =
            if (subPath.isEmpty()) {
                "${server.name} (${server.path})"
            } else {
                subPath.substringAfterLast('/')
            }

        val header = VoidPanelChrome.buildHeader(context, title = titleText, onBack = { onBack() })
        root.addView(header)

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

        // Recycler com FileAdapter
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
                    renderFiles(server, browsePath)
                },
                onVideoClick = { entry ->
                    onPlay(PlaybackSource.Nfs(server, entry.path, entry.sizeBytes))
                },
                onFileSelected = { entry ->
                    val source = PlaybackSource.Nfs(server, entry.path, entry.sizeBytes)
                    host.showFileDetail(
                        entry = entry,
                        source = source,
                        onPlay = { resumeAtMs ->
                            activity.playNfs(server, entry.path, entry.sizeBytes, resumeAtMs)
                            activity.requestUiPanelHidden()
                        },
                    )
                },
                contextRailHost = host,
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
            }
        root.addView(emptyContainer)

        host.showScreen(root)
        loadDirectory(server, subPath)
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

    private fun loadDirectory(
        server: SavedServer,
        subPath: String,
    ) {
        scope.launch {
            val raw =
                withContext(Dispatchers.IO) {
                    activity.nativeNfsListDirectory(server.host, server.port, server.path, subPath, 3)
                }

            if (raw.startsWith("ERROR:")) {
                countLabel?.text = context.getString(R.string.network_nfs_form_status_error_format, raw.removePrefix("ERROR:"))
                cachedRawEntries = emptyList()
                applyFiltersAndSort()
                return@launch
            }

            val lines = raw.split("\n").filter { it.isNotBlank() }
            val entries =
                lines.mapNotNull { line ->
                    val parts = line.split("\t")
                    val name = parts.getOrNull(0) ?: return@mapNotNull null
                    val isDir = parts.getOrNull(1) == "1"
                    val sizeBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    val type =
                        if (isDir) {
                            MediaType.DIRECTORY
                        } else {
                            mediaTypeForExtension(name.substringAfterLast('.', "")) ?: MediaType.VIDEO
                        }
                    val f3d =
                        if (type == MediaType.VIDEO) {
                            MediaFilterEngine.detectFormat3DFromFilename(name)
                        } else {
                            Format3DType.FLAT_2D
                        }
                    val childPath = if (subPath.isEmpty()) name else "$subPath/$name"

                    MediaEntry(
                        name = name,
                        path = childPath,
                        sizeBytes = sizeBytes,
                        lastModified = 0L,
                        type = type,
                        format3DHint = f3d,
                    )
                }

            val pruned =
                NetworkFolderProber.pruneEmptyFolders(
                    context = context,
                    sourceKind = "nfs",
                    entries = entries,
                    folderKeyFor = { entry -> CacheKeys.forFolder("nfs", server.host, server.port, server.path, entry.path) },
                    scanFnFor = { entry ->
                        { activity.nativeNfsScanFolderHasMedia(server.host, server.port, server.path, entry.path, 3) }
                    },
                )

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
            context.resources.getQuantityString(
                R.plurals.browser_results_count_format,
                cachedRawEntries.size,
                sorted.size,
                cachedRawEntries.size,
            )

        val isEmpty = sorted.isEmpty() && !showUp
        recyclerView?.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyContainer?.visibility = if (isEmpty) View.GONE else View.GONE
    }

    fun handleBack(server: SavedServer? = browsingServer): Boolean {
        if (browsePath.isNotEmpty() && server != null) {
            browsePath = browsePath.substringBeforeLast('/', missingDelimiterValue = "")
            mediaToolbar?.clearSearch()
            searchQuery = ""
            renderFiles(server, browsePath)
            return true
        }
        return false
    }

    private fun folderKey(
        server: SavedServer,
        subPath: String,
    ) = "nfs://${server.host}:${server.port}${server.path}/$subPath"
}
