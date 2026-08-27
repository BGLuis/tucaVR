package com.tucavr.screens

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
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
import com.tucavr.designsystem.VoidListRow
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidSearchBar
import com.tucavr.designsystem.VoidSortSelector
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTextField
import com.tucavr.designsystem.VoidTheme
import com.tucavr.filebrowser.DateFilter
import com.tucavr.filebrowser.FolderConfig
import com.tucavr.filebrowser.FolderConfigStore
import com.tucavr.filebrowser.Format3DFilter
import com.tucavr.filebrowser.MediaEntry
import com.tucavr.filebrowser.MediaFilterEngine
import com.tucavr.filebrowser.MediaType
import com.tucavr.filebrowser.MediaTypeFilter
import com.tucavr.filebrowser.NetworkThumbnailGenerator
import com.tucavr.filebrowser.ViewMode
import com.tucavr.filebrowser.mediaTypeForExtension
import com.tucavr.filebrowser.sortMediaEntries
import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.FtpCredentialStore
import com.tucavr.network.FtpServer
import com.tucavr.screens.adapters.FileAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Responsável pela aba FTP e pela navegação avançada de diretório FTP (T6.4).
 * Inclui formulário de adição, busca com debounce, filtros de mídia/3D,
 * alternância Grade/Lista, ordenação e reprodução com 1 toque.
 */
class NetworkFtpScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val credentialStore: FtpCredentialStore,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit
) {

    var browsingServer: FtpServer? = null
    var browsePath: String = ""

    private val folderConfigStore = FolderConfigStore(context)
    private var adapter: FileAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var countLabel: android.widget.TextView? = null
    private var emptyContainer: LinearLayout? = null
    private var searchBar: VoidSearchBar? = null

    // Estados de busca e filtros de rede
    private var searchQuery = ""
    private var currentTypeFilter = MediaTypeFilter.ALL
    private var currentFormat3DFilter = Format3DFilter.ALL
    private var currentDateFilter = DateFilter.ALL
    private var currentConfig = FolderConfig()
    private var cachedRawEntries: List<MediaEntry> = emptyList()

    // ---- Aba FTP: lista de servidores ----

    fun buildPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        page.addView(VoidText.title(context, context.getString(R.string.network_ftp_saved_servers_header), sizeSp = 20f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
        })

        val serversContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(serversContainer)

        fun refreshServerList() {
            serversContainer.removeAllViews()
            val servers = credentialStore.list()
            if (servers.isEmpty()) {
                serversContainer.addView(
                    VoidText.body(context, context.getString(R.string.network_ftp_empty), sizeSp = 16f, secondary = true)
                )
                return
            }
            servers.forEach { server ->
                val row = buildServerRow(
                    labelText = context.getString(R.string.network_ftp_row_label_format, server.name),
                    metaText = if (server.isAnonymous) context.getString(R.string.network_ftp_anonymous_label) else server.username,
                    onConnect = {
                        browsingServer = server
                        browsePath = ""
                        onNavigate(Destination.NetworkFtpFiles(server, ""))
                    },
                    onRemove = {
                        credentialStore.remove(server.id)
                        refreshServerList()
                    }
                )
                serversContainer.addView(row)
            }
        }
        refreshServerList()

        page.addView(VoidText.title(context, context.getString(R.string.network_ftp_btn_add_server), sizeSp = 20f).apply {
            setPadding(0, VoidTheme.dpToPx(context, 16f), 0, VoidTheme.dpToPx(context, 8f))
        })
        page.addView(buildAddServerForm { refreshServerList() })

        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(page)
        }
        return scroller
    }

    private fun buildAddServerForm(onSaved: () -> Unit): View {
        val form = VoidForm(context)

        val fieldHost = form.field(
            host = host,
            label = context.getString(R.string.network_ftp_form_host_label),
            hint = "ftp.example.com",
            kind = VoidFieldKind.TEXT,
            validator = FieldValidators.required(context.getString(R.string.network_ftp_form_status_host_required))
        )
        val fieldPort = form.field(
            host = host,
            label = context.getString(R.string.network_ftp_form_port_label),
            hint = "21",
            kind = VoidFieldKind.NUMBER,
            validator = FieldValidators.port(context.getString(R.string.field_error_invalid_port))
        )

        val fieldUser = VoidTextField(
            context = context,
            host = host,
            label = context.getString(R.string.network_ftp_form_user_label),
            hint = "anonymous",
            kind = VoidFieldKind.TEXT
        )
        val fieldPass = VoidTextField(
            context = context,
            host = host,
            label = context.getString(R.string.network_ftp_form_pass_label),
            hint = "",
            kind = VoidFieldKind.PASSWORD,
            actions = setOf(VoidFieldAction.PASTE, VoidFieldAction.REVEAL, VoidFieldAction.CONTEXT_MENU)
        )

        val anonCheckbox = CheckBox(context).apply {
            text = context.getString(R.string.network_ftp_form_anonymous_checkbox)
            setTextColor(VoidTheme.colorText)
            textSize = 14f
            typeface = VoidTheme.typefaceBody
            isChecked = true
            val pad = VoidTheme.dpToPx(context, 8f)
            setPadding(pad, pad, pad, pad)
            setOnCheckedChangeListener { _, isChecked ->
                fieldUser.visibility = if (isChecked) View.GONE else View.VISIBLE
                fieldPass.visibility = if (isChecked) View.GONE else View.VISIBLE
            }
        }
        fieldUser.visibility = View.GONE
        fieldPass.visibility = View.GONE

        form.addView(anonCheckbox)
        form.addField(fieldUser)
        form.addField(fieldPass)

        val statusText = VoidText.body(context, "", sizeSp = 14f, secondary = true).apply {
            setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f))
        }

        val btnSave = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.network_ftp_btn_test_save)
            textSize = 16f
            minHeight = VoidTheme.dpToPx(context, 48f)
            setOnClickListener {
                if (!form.validate()) return@setOnClickListener

                val hostStr = fieldHost.getText().trim()
                val portStr = fieldPort.getText().trim()
                val port = if (portStr.isEmpty()) 21 else (portStr.toIntOrNull() ?: 21)
                val isAnon = anonCheckbox.isChecked
                val user = if (isAnon) "anonymous" else fieldUser.getText().trim()
                val pass = if (isAnon) "guest@example.com" else fieldPass.getText().trim()
                testAndSave(hostStr, port, isAnon, user, pass, statusText) {
                    form.clearAll()
                    statusText.text = context.getString(R.string.network_ftp_form_status_connected)
                    onSaved()
                }
            }
        }

        form.onFormSubmit = { btnSave.performClick() }

        form.addView(statusText)
        form.addView(btnSave, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 8f) })

        return form
    }

    private fun testAndSave(
        host: String, port: Int, isAnon: Boolean,
        username: String, password: String,
        statusView: android.widget.TextView, onSaved: () -> Unit
    ) {
        if (host.isEmpty()) {
            statusView.text = context.getString(R.string.network_ftp_form_status_host_required)
            return
        }
        statusView.text = context.getString(R.string.network_ftp_form_status_connecting)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeFtpListDirectory(host, port, username, password, "")
            }
            if (result.startsWith("ERROR:")) {
                statusView.text = context.getString(
                    R.string.network_ftp_form_status_error_format, result.removePrefix("ERROR:")
                )
                return@launch
            }
            statusView.text = context.getString(R.string.network_ftp_form_status_connected)
            val server = FtpServer(
                id       = credentialStore.newId(),
                name     = host,
                host     = host,
                port     = port,
                username = username,
                password = password
            )
            credentialStore.save(server)
            onSaved()
        }
    }

    // ---- Navegação de diretório FTP moderna ----

    fun renderFiles(server: FtpServer) {
        browsingServer = server
        val folderKey = "ftp://${server.name}/$browsePath"
        currentConfig = folderConfigStore.getConfigFor(folderKey)

        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title    = server.name,
                subtitle = if (browsePath.isEmpty()) "/" else browsePath,
                onBack = { onBack() }
            )
        )

        // Barra de Ferramentas
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

        // Contador de Resultados
        val counterView = VoidText.mono(context, "", sizeSp = 13f, secondary = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 6f)
            }
        }
        countLabel = counterView
        root.addView(counterView)

        // Recycler com FileAdapter universal
        val recycler = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        recyclerView = recycler
        updateLayoutManager()

        val fileAdapter = FileAdapter(
            context = context,
            scope = scope,
            onUpClick = { onBack() },
            onDirectoryClick = { entry ->
                browsePath = entry.path
                searchBar?.clear()
                searchQuery = ""
                renderFiles(server)
            },
            onVideoClick = { entry ->
                val source = PlaybackSource.Ftp(server, entry.path, entry.sizeBytes)
                activity.playFtp(server, entry.path, resumeAtMs = 0L)
                onNavigate(Destination.Player(source))
            },
            thumbnailLoader = { entry ->
                val source = PlaybackSource.Ftp(server, entry.path, entry.sizeBytes)
                NetworkThumbnailGenerator.getThumbnail(context, activity, source)
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

            addView(VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = context.getString(R.string.browser_btn_clear_filters)
                textSize = 15f
                minHeight = VoidTheme.dpToPx(context, 48f)
                val padH = VoidTheme.dpToPx(context, 16f)
                val padV = VoidTheme.dpToPx(context, 10f)
                setPadding(padH, padV, padH, padV)
                setOnClickListener {
                    searchBar?.clear()
                    searchQuery = ""
                    currentTypeFilter = MediaTypeFilter.ALL
                    currentFormat3DFilter = Format3DFilter.ALL
                    currentDateFilter = DateFilter.ALL
                    typeChips.forEach { (t, c) -> c.setSelectedState(t == MediaTypeFilter.ALL) }
                    format3DChips.forEach { (f, c) -> c.setSelectedState(f == Format3DFilter.ALL) }
                    applyFiltersAndSort()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = VoidTheme.dpToPx(context, 12f)
            })
        }
        root.addView(emptyContainer)

        host.showScreen(root)
        loadDirectory(server)
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

    fun handleBack(server: FtpServer): Boolean {
        if (browsePath.isNotEmpty()) {
            browsePath = browsePath.substringBeforeLast('/', missingDelimiterValue = "")
            searchBar?.clear()
            searchQuery = ""
            renderFiles(server)
            return true
        }
        return false
    }

    private fun loadDirectory(server: FtpServer) {
        val requestedPath = browsePath
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeFtpListDirectory(
                    server.host, server.port, server.username, server.password, requestedPath
                )
            }
            if (browsingServer != server || browsePath != requestedPath) return@launch

            if (result.startsWith("ERROR:")) {
                cachedRawEntries = emptyList()
                applyFiltersAndSort()
                return@launch
            }

            val lines = result.split("\n").filter { it.isNotBlank() }
            val entries = lines.mapNotNull { line ->
                val parts = line.split("\t")
                val name = parts.getOrNull(0) ?: return@mapNotNull null
                val isDir = parts.getOrNull(1) == "1"
                val sizeBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val childPath = if (requestedPath.isEmpty()) name else "$requestedPath/$name"

                val type = if (isDir) MediaType.DIRECTORY else (mediaTypeForExtension(name.substringAfterLast('.', "")) ?: MediaType.VIDEO)
                val f3d = if (type == MediaType.VIDEO) MediaFilterEngine.detectFormat3DFromFilename(name) else com.tucavr.filebrowser.Format3DType.FLAT_2D

                MediaEntry(
                    name = name,
                    path = childPath,
                    sizeBytes = sizeBytes,
                    lastModified = 0L,
                    type = type,
                    format3DHint = f3d
                )
            }

            cachedRawEntries = entries
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
        emptyContainer?.visibility = if (isEmpty) View.GONE else View.GONE
    }

    private fun buildServerRow(
        labelText: String,
        metaText: String,
        onConnect: () -> Unit,
        onRemove: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f))

        addView(VoidListRow(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            bind(labelText, meta = metaText, showThumbnailSlot = false, iconResId = R.drawable.ic_storage)
            setOnClickListener { onConnect() }
        })

        addView(VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = ""
            setIcon(R.drawable.icon_x)
            textSize = 16f
            minHeight = 0
            val pad = VoidTheme.dpToPx(context, 12f)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onRemove() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = VoidTheme.dpToPx(context, 8f)
        })
    }
}
