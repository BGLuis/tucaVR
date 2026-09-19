package com.tucavr.screens

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.tucavr.designsystem.VoidFilterChip
import com.tucavr.designsystem.VoidForm
import com.tucavr.designsystem.VoidIconButton
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidSearchBar
import com.tucavr.designsystem.VoidSortSelector
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTextField
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
import com.tucavr.network.ServerCredentialStore
import com.tucavr.network.ServerProtocol
import com.tucavr.screens.adapters.FileAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * T3.4: Responsável pela aba WebDAV e pela navegação de arquivos remotos via PROPFIND.
 * Suporta configuração de servidores salvos (HTTP/HTTPS, caminhos base, credenciais,
 * certificados auto-assinados), navegação em pastas, busca/filtros e reprodução contínua.
 */
class NetworkWebdavScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val savedServerDao: SavedServerDao,
    private val credentialStore: ServerCredentialStore,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit
) {

    var browsingServer: SavedServer? = null
    var browsePath: String = ""

    private val folderConfigStore = FolderConfigStore(context)
    private var adapter: FileAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var countLabel: android.widget.TextView? = null
    private var emptyContainer: LinearLayout? = null
    private var searchBar: VoidSearchBar? = null

    // Campos do formulário para preenchimento via descoberta automática
    private var formHostField: VoidTextField? = null
    private var formPortField: VoidTextField? = null
    private var formNameField: VoidTextField? = null
    private var formBasePathField: VoidTextField? = null

    // Estados de busca e filtros de rede
    private var searchQuery = ""
    private var currentTypeFilter = MediaTypeFilter.ALL
    private var currentFormat3DFilter = Format3DFilter.ALL
    private var currentDateFilter = DateFilter.ALL
    private var currentConfig = FolderConfig()
    private var cachedRawEntries: List<MediaEntry> = emptyList()

    // ---- Formulario "Adicionar servidor" (usado pela lista unificada em NetworkHomeScreen) ----

    fun buildAddServerForm(onSaved: () -> Unit): View {
        val form = VoidForm(context)

        val hostInput = form.field(
            host = host,
            label = context.getString(R.string.network_webdav_form_host_label),
            hint = "192.168.1.100",
            kind = VoidFieldKind.TEXT,
            validator = FieldValidators.required(context.getString(R.string.network_webdav_form_status_host_required)),
            actions = setOf(VoidFieldAction.PASTE, VoidFieldAction.CLEAR, VoidFieldAction.CONTEXT_MENU)
        )
        formHostField = hostInput

        val portInput = form.field(
            host = host,
            label = context.getString(R.string.network_webdav_form_port_label),
            hint = "5005",
            kind = VoidFieldKind.NUMBER,
            validator = FieldValidators.port(context.getString(R.string.field_error_invalid_port))
        ).apply { setText("5005") }
        formPortField = portInput

        val nameInput = form.field(
            host = host,
            label = context.getString(R.string.network_webdav_form_name_label),
            hint = context.getString(R.string.network_webdav_form_name_hint),
            kind = VoidFieldKind.TEXT
        )
        formNameField = nameInput

        val basePathInput = form.field(
            host = host,
            label = context.getString(R.string.network_webdav_form_base_path_label),
            hint = context.getString(R.string.network_webdav_form_base_path_hint),
            kind = VoidFieldKind.TEXT
        ).apply { setText("/") }
        formBasePathField = basePathInput

        val userInput = form.field(
            host = host,
            label = context.getString(R.string.network_webdav_form_user_label),
            hint = context.getString(R.string.network_webdav_form_user_hint),
            kind = VoidFieldKind.TEXT
        )

        val passInput = form.field(
            host = host,
            label = context.getString(R.string.network_webdav_form_pass_label),
            hint = context.getString(R.string.network_webdav_form_pass_hint),
            kind = VoidFieldKind.PASSWORD,
            actions = setOf(VoidFieldAction.PASTE, VoidFieldAction.REVEAL, VoidFieldAction.CONTEXT_MENU)
        )

        // Checkbox HTTPS
        var useHttps = false
        val httpsCheckbox = CheckBox(context).apply {
            text = context.getString(R.string.network_webdav_form_https_toggle)
            setTextColor(VoidTheme.colorText)
            textSize = 15f
            val pad = VoidTheme.dpToPx(context, 8f)
            setPadding(pad, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = VoidTheme.dpToPx(context, 8f)
                it.bottomMargin = VoidTheme.dpToPx(context, 4f)
            }
            setOnCheckedChangeListener { _, isChecked ->
                useHttps = isChecked
                if (isChecked && portInput.getText() == "5005") {
                    portInput.setText("5006")
                } else if (!isChecked && portInput.getText() == "5006") {
                    portInput.setText("5005")
                }
            }
        }
        form.addView(httpsCheckbox)

        // Checkbox Certificados Auto-assinados
        var acceptInvalidCerts = false
        val selfSignedCheckbox = CheckBox(context).apply {
            text = context.getString(R.string.network_webdav_form_self_signed_toggle)
            setTextColor(VoidTheme.colorText)
            textSize = 14f
            val pad = VoidTheme.dpToPx(context, 8f)
            setPadding(pad, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = VoidTheme.dpToPx(context, 4f)
                it.bottomMargin = VoidTheme.dpToPx(context, 2f)
            }
            setOnCheckedChangeListener { _, isChecked ->
                acceptInvalidCerts = isChecked
            }
        }
        form.addView(selfSignedCheckbox)

        val warningText = VoidText.body(
            context,
            context.getString(R.string.network_webdav_form_self_signed_warning),
            sizeSp = 12f,
            secondary = true
        ).apply {
            setPadding(VoidTheme.dpToPx(context, 32f), 0, 0, VoidTheme.dpToPx(context, 8f))
        }
        form.addView(warningText)

        val statusText = VoidText.body(context, "", sizeSp = 14f, secondary = true).apply {
            setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f))
        }
        form.addView(statusText)

        val btnSave = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.network_webdav_btn_test_save)
            textSize = 16f
            minHeight = VoidTheme.dpToPx(context, 48f)
            setOnClickListener {
                if (!form.validate()) return@setOnClickListener

                val hostText = hostInput.getText().trim()
                if (hostText.isEmpty()) {
                    statusText.text = context.getString(R.string.network_webdav_form_status_host_required)
                    return@setOnClickListener
                }

                val portVal = portInput.getText().toIntOrNull() ?: if (useHttps) 5006 else 5005
                val basePathVal = basePathInput.getText().trim().ifEmpty { "/" }
                val userVal = userInput.getText().trim()
                val passVal = passInput.getText()
                val nameVal = nameInput.getText().trim()

                testAndSaveWebdavServer(
                    h = hostText,
                    p = portVal,
                    basePath = basePathVal,
                    user = userVal,
                    pass = passVal,
                    useHttps = useHttps,
                    acceptInvalidCerts = acceptInvalidCerts,
                    name = nameVal,
                    statusView = statusText,
                    onSaved = {
                        form.clearAll()
                        basePathInput.setText("/")
                        portInput.setText(if (useHttps) "5006" else "5005")
                        statusText.text = context.getString(R.string.network_webdav_form_status_connected)
                        onSaved()
                    }
                )
            }
        }

        form.onFormSubmit = { btnSave.performClick() }

        form.addView(btnSave, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 8f) })

        return form
    }

    private fun testAndSaveWebdavServer(
        h: String,
        p: Int,
        basePath: String,
        user: String,
        pass: String,
        useHttps: Boolean,
        acceptInvalidCerts: Boolean,
        name: String,
        statusView: android.widget.TextView,
        onSaved: () -> Unit
    ) {
        val n = name.ifEmpty { h }
        statusView.text = context.getString(R.string.network_webdav_form_status_connecting)

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeWebdavListDirectory(h, p, basePath, "", user, pass, useHttps, acceptInvalidCerts)
            }

            if (result.startsWith("ERROR:")) {
                statusView.text = context.getString(R.string.network_webdav_form_status_error_format, result.removePrefix("ERROR:"))
                return@launch
            }

            val extraJson = JSONObject().apply {
                put("useHttps", useHttps)
                put("acceptInvalidCerts", acceptInvalidCerts)
            }.toString()

            val server = SavedServer(
                name = n,
                protocol = ServerProtocol.WEBDAV,
                host = h,
                port = p,
                path = basePath,
                username = user,
                isAutoDiscovered = false,
                lastConnectedAt = System.currentTimeMillis(),
                extraJson = extraJson
            )

            withContext(Dispatchers.IO) {
                savedServerDao.insert(server)
                if (pass.isNotEmpty()) {
                    credentialStore.saveCredentials(server.id, pass)
                }
            }

            statusView.text = context.getString(R.string.network_webdav_form_status_connected)
            onSaved()
        }
    }

    fun prefill(host: String, port: Int, name: String, path: String) {
        formHostField?.setText(host)
        if (port > 0) formPortField?.setText(port.toString())
        if (name.isNotEmpty()) formNameField?.setText(name)
        if (path.isNotEmpty()) formBasePathField?.setText(path)
    }

    // ---- Navegação de arquivos WebDAV ----

    fun renderFiles(server: SavedServer, subPath: String = browsePath) {
        browsingServer = server
        browsePath = subPath
        val folderKey = folderKey(server, subPath)
        currentConfig = folderConfigStore.getConfigFor(folderKey)

        val root = VoidPanelChrome.newRoot(context)
        val titleText = if (subPath.isEmpty()) {
            "${server.name} (${server.path})"
        } else {
            subPath.substringAfterLast('/')
        }

        val header = VoidPanelChrome.buildHeader(context, title = titleText, onBack = { if (!handleBack(server)) onBack() })
        root.addView(header)

        // Toolbar de busca e ordenação
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 10f)
            }
        }

        searchBar = VoidSearchBar(
            context = context,
            host = host,
            scope = scope,
            hintText = context.getString(R.string.browser_search_hint),
            activity = activity,
            onQueryChanged = { query ->
                searchQuery = query
                applyFiltersAndSort()
            }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginEnd = VoidTheme.dpToPx(context, 8f)
            }
        }
        toolbar.addView(searchBar)

        val sortSelector = VoidSortSelector(
            context = context,
            currentSortBy = currentConfig.sortBy,
            currentAscending = currentConfig.ascending,
            onSortChanged = { newSort, newAscending ->
                currentConfig = currentConfig.copy(sortBy = newSort, ascending = newAscending)
                folderConfigStore.saveConfigFor(folderKey, currentConfig)
                applyFiltersAndSort()
            }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = VoidTheme.dpToPx(context, 8f)
            }
        }
        toolbar.addView(sortSelector)

        val viewModeBtn = VoidIconButton(
            context,
            if (currentConfig.viewMode == ViewMode.GRID) R.drawable.ic_view_list else R.drawable.ic_view_grid,
            VoidButtonStyle.SECONDARY,
            isCircular = false
        ).apply {
            layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 48f), VoidTheme.dpToPx(context, 48f))
            setOnClickListener {
                val nextMode = if (currentConfig.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
                currentConfig = currentConfig.copy(viewMode = nextMode)
                folderConfigStore.saveConfigFor(folderKey, currentConfig)
                setImageResource(if (nextMode == ViewMode.GRID) R.drawable.ic_view_list else R.drawable.ic_view_grid)
                updateLayoutManager()
                applyFiltersAndSort()
            }
        }
        toolbar.addView(viewModeBtn)
        root.addView(toolbar)

        // Chips de Filtro
        val filterScrollView = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
            isHorizontalScrollBarEnabled = false
        }
        val filterChipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Chips de Tipo
        val typeChips = mutableListOf<Pair<MediaTypeFilter, VoidFilterChip>>()
        listOf(
            MediaTypeFilter.ALL to R.string.browser_filter_all,
            MediaTypeFilter.VIDEO to R.string.browser_filter_video,
            MediaTypeFilter.AUDIO to R.string.browser_filter_audio,
            MediaTypeFilter.IMAGE to R.string.browser_filter_image
        ).forEach { (type, res) ->
            val chip = VoidFilterChip(context, context.getString(res), isSelectedChip = currentTypeFilter == type).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                    it.marginEnd = VoidTheme.dpToPx(context, 8f)
                }
            }
            typeChips.add(type to chip)
            chip.setOnClickListener {
                currentTypeFilter = type
                typeChips.forEach { (t, c) -> c.setSelectedState(t == currentTypeFilter) }
                applyFiltersAndSort()
            }
            filterChipRow.addView(chip)
        }

        // Chips de Formato 3D
        val format3DChips = mutableListOf<Pair<Format3DFilter, VoidFilterChip>>()
        listOf(
            Format3DFilter.ALL to R.string.browser_filter_3d_all,
            Format3DFilter.SBS to R.string.browser_filter_3d_sbs,
            Format3DFilter.OU to R.string.browser_filter_3d_ou,
            Format3DFilter.VR_180 to R.string.browser_filter_3d_180,
            Format3DFilter.VR_360 to R.string.browser_filter_3d_360
        ).forEach { (f3d, res) ->
            val chip = VoidFilterChip(context, context.getString(res), isSelectedChip = currentFormat3DFilter == f3d).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                    it.marginEnd = VoidTheme.dpToPx(context, 8f)
                }
            }
            format3DChips.add(f3d to chip)
            chip.setOnClickListener {
                currentFormat3DFilter = f3d
                format3DChips.forEach { (f, c) -> c.setSelectedState(f == currentFormat3DFilter) }
                applyFiltersAndSort()
            }
            filterChipRow.addView(chip)
        }

        filterScrollView.addView(filterChipRow)
        root.addView(filterScrollView)

        // Contador de Itens
        val counterView = VoidText.body(context, context.getString(R.string.network_files_loading), sizeSp = 13f, secondary = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 6f)
            }
        }
        countLabel = counterView
        root.addView(counterView)

        // Recycler com FileAdapter
        val recycler = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        recyclerView = recycler
        updateLayoutManager()

        val fileAdapter = FileAdapter(
            context = context,
            scope = scope,
            onUpClick = { if (!handleBack(server)) onBack() },
            onDirectoryClick = { entry ->
                browsePath = entry.path
                searchBar?.clear()
                searchQuery = ""
                renderFiles(server, browsePath)
            },
            onVideoClick = { entry ->
                val source = PlaybackSource.Webdav(server, entry.path, entry.sizeBytes)
                activity.playWebdav(server, entry.path, sizeBytes = entry.sizeBytes, resumeAtMs = 0L)
                onNavigate(Destination.Player(source))
            }
        )
        adapter = fileAdapter
        recycler.adapter = fileAdapter
        root.addView(recycler)

        // Empty State View
        emptyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            visibility = View.GONE

            addView(VoidText.body(context, context.getString(R.string.network_files_empty), sizeSp = 16f, secondary = true).apply {
                gravity = Gravity.CENTER
            })
        }
        root.addView(emptyContainer)

        host.showScreen(root)
        loadDirectory(server, subPath)
    }

    private fun updateLayoutManager() {
        val recycler = recyclerView ?: return
        if (currentConfig.viewMode == ViewMode.GRID) {
            val gridLayout = GridLayoutManager(context, 3).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
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

    private fun loadDirectory(server: SavedServer, subPath: String) {
        scope.launch {
            val password = withContext(Dispatchers.IO) {
                credentialStore.getPassword(server.id)
            }

            var useHttps = false
            var acceptInvalidCerts = false
            if (!server.extraJson.isNullOrEmpty()) {
                try {
                    val json = JSONObject(server.extraJson)
                    useHttps = json.optBoolean("useHttps", false)
                    acceptInvalidCerts = json.optBoolean("acceptInvalidCerts", false)
                } catch (e: Exception) {
                    // ignore
                }
            }

            val raw = withContext(Dispatchers.IO) {
                activity.nativeWebdavListDirectory(
                    server.host,
                    server.port,
                    server.path,
                    subPath,
                    server.username,
                    password,
                    useHttps,
                    acceptInvalidCerts
                )
            }

            if (raw.startsWith("ERROR:")) {
                countLabel?.text = context.getString(R.string.network_webdav_form_status_error_format, raw.removePrefix("ERROR:"))
                cachedRawEntries = emptyList()
                applyFiltersAndSort()
                return@launch
            }

            val lines = raw.split("\n").filter { it.isNotBlank() }
            val entries = lines.mapNotNull { line ->
                val parts = line.split("\t")
                val name = parts.getOrNull(0) ?: return@mapNotNull null
                val isDir = parts.getOrNull(1) == "1"
                val sizeBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val type = if (isDir) MediaType.DIRECTORY else (mediaTypeForExtension(name.substringAfterLast('.', "")) ?: MediaType.VIDEO)
                val f3d = if (type == MediaType.VIDEO) MediaFilterEngine.detectFormat3DFromFilename(name) else Format3DType.FLAT_2D
                val childPath = if (subPath.isEmpty()) name else "$subPath/$name"

                MediaEntry(
                    name = name,
                    path = childPath,
                    sizeBytes = sizeBytes,
                    lastModified = 0L,
                    type = type,
                    format3DHint = f3d
                )
            }

            val pruned = NetworkFolderProber.pruneEmptyFolders(
                context = context,
                sourceKind = "webdav",
                entries = entries,
                folderKeyFor = { entry -> CacheKeys.forFolder("webdav", server.host, server.port, server.path, entry.path) },
                scanFnFor = { entry ->
                    {
                        activity.nativeWebdavScanFolderHasMedia(
                            server.host, server.port, server.path, entry.path,
                            server.username, password, useHttps, acceptInvalidCerts
                        )
                    }
                }
            )

            cachedRawEntries = pruned
            applyFiltersAndSort()
        }
    }

    private fun applyFiltersAndSort() {
        val currentAdapter = adapter ?: return
        val showUp = browsePath.isNotEmpty()

        val filtered = cachedRawEntries.filter { entry ->
            MediaFilterEngine.matchesFilter(
                entry = entry,
                query = searchQuery,
                typeFilter = currentTypeFilter,
                format3DFilter = currentFormat3DFilter,
                dateFilter = currentDateFilter
            )
        }

        val sorted = sortMediaEntries(filtered, currentConfig.sortBy, currentConfig.ascending)
        currentAdapter.submit(sorted, showUp, currentConfig.viewMode, searchQuery)

        countLabel?.text = context.getString(
            R.string.browser_results_count_format, sorted.size, cachedRawEntries.size
        )

        val isEmpty = sorted.isEmpty() && !showUp
        recyclerView?.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyContainer?.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    fun handleBack(server: SavedServer): Boolean {
        if (browsePath.isNotEmpty()) {
            val parentPath = if (browsePath.contains('/')) browsePath.substringBeforeLast('/') else ""
            browsePath = parentPath
            searchBar?.clear()
            searchQuery = ""
            renderFiles(server, parentPath)
            return true
        }
        return false
    }


    private fun folderKey(server: SavedServer, subPath: String): String =
        "webdav_${server.id}_$subPath"
}
