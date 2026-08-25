package com.vrplayer.screens

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
import com.vrplayer.R
import com.vrplayer.VRActivity
import com.vrplayer.designsystem.FieldValidators
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidFieldAction
import com.vrplayer.designsystem.VoidFieldKind
import com.vrplayer.designsystem.VoidFilterChip
import com.vrplayer.designsystem.VoidForm
import com.vrplayer.designsystem.VoidIconButton
import com.vrplayer.designsystem.VoidListRow
import com.vrplayer.designsystem.VoidPanelChrome
import com.vrplayer.designsystem.VoidSearchBar
import com.vrplayer.designsystem.VoidSortSelector
import com.vrplayer.designsystem.VoidText
import com.vrplayer.designsystem.VoidTextField
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.filebrowser.DateFilter
import com.vrplayer.filebrowser.FolderConfig
import com.vrplayer.filebrowser.FolderConfigStore
import com.vrplayer.filebrowser.Format3DFilter
import com.vrplayer.filebrowser.MediaEntry
import com.vrplayer.filebrowser.MediaFilterEngine
import com.vrplayer.filebrowser.MediaType
import com.vrplayer.filebrowser.MediaTypeFilter
import com.vrplayer.filebrowser.NetworkThumbnailGenerator
import com.vrplayer.filebrowser.ViewMode
import com.vrplayer.filebrowser.mediaTypeForExtension
import com.vrplayer.filebrowser.sortMediaEntries
import com.vrplayer.navigation.Destination
import com.vrplayer.navigation.PlaybackSource
import com.vrplayer.network.SmbCredentialStore
import com.vrplayer.network.SmbServer
import com.vrplayer.screens.adapters.FileAdapter
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
    private val onBack: () -> Unit
) {

    var browsingServer: SmbServer? = null
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

    // ---- Aba SMB: lista de servidores ----

    fun buildPage(): View {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        page.addView(VoidText.title(context, context.getString(R.string.network_smb_saved_servers_header), sizeSp = 20f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
        })

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
                val row = buildServerRow(
                    labelText = context.getString(R.string.network_smb_row_label_format, server.name),
                    metaText = if (server.isGuest) context.getString(R.string.network_smb_guest_label) else server.username,
                    onConnect = {
                        browsingServer = server
                        browsePath = ""
                        onNavigate(Destination.NetworkFiles(server, ""))
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

        page.addView(VoidText.title(context, context.getString(R.string.network_smb_btn_add_server), sizeSp = 20f).apply {
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
            label = context.getString(R.string.network_smb_form_host_label),
            hint = "192.168.1.100",
            kind = VoidFieldKind.TEXT,
            validator = FieldValidators.required(context.getString(R.string.network_smb_form_status_host_required))
        )
        val fieldPort = form.field(
            host = host,
            label = context.getString(R.string.network_smb_form_port_label),
            hint = "445",
            kind = VoidFieldKind.NUMBER,
            validator = FieldValidators.port(context.getString(R.string.field_error_invalid_port))
        )
        val fieldShare = form.field(
            host = host,
            label = context.getString(R.string.network_smb_form_share_label),
            hint = "Videos",
            kind = VoidFieldKind.TEXT
        )

        val fieldUser = VoidTextField(
            context = context,
            host = host,
            label = context.getString(R.string.network_smb_form_user_label),
            hint = "user",
            kind = VoidFieldKind.TEXT
        )
        val fieldPass = VoidTextField(
            context = context,
            host = host,
            label = context.getString(R.string.network_smb_form_pass_label),
            hint = "",
            kind = VoidFieldKind.PASSWORD,
            actions = setOf(VoidFieldAction.PASTE, VoidFieldAction.REVEAL, VoidFieldAction.CONTEXT_MENU)
        )

        val guestCheckbox = CheckBox(context).apply {
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

        val fieldDomain = form.field(
            host = host,
            label = context.getString(R.string.network_smb_form_domain_label),
            hint = "WORKGROUP",
            kind = VoidFieldKind.TEXT
        )

        val statusText = VoidText.body(context, "", sizeSp = 14f, secondary = true).apply {
            setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f))
        }

        val btnSave = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
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
        form.addView(btnSave, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 8f) })

        return form
    }

    private fun testAndSave(
        host: String, port: Int, share: String, guest: Boolean,
        username: String, password: String, domain: String,
        statusView: android.widget.TextView, onSaved: () -> Unit
    ) {
        val effectiveUser = if (guest) "" else username
        val effectivePass = if (guest) "" else password

        if (host.isEmpty()) {
            statusView.text = context.getString(R.string.network_smb_form_status_host_required)
            return
        }
        statusView.text = context.getString(R.string.network_smb_form_status_connecting)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeSmbListShares(host, port, effectiveUser, effectivePass, domain)
            }
            if (result.startsWith("ERROR:")) {
                statusView.text = context.getString(
                    R.string.network_smb_form_status_error_format, result.removePrefix("ERROR:")
                )
                return@launch
            }
            val shares = result.split("\n").filter { it.isNotBlank() }
            statusView.text = context.resources.getQuantityString(
                R.plurals.network_smb_form_status_connected, shares.size, shares.size
            )
            val server = SmbServer(
                id       = credentialStore.newId(),
                name     = if (share.isNotEmpty()) "$host/$share" else host,
                host     = host,
                port     = port,
                share    = share.ifEmpty { shares.firstOrNull() ?: "" },
                username = effectiveUser,
                password = effectivePass,
                domain   = domain
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
                title    = server.name,
                subtitle = "${server.share}/$browsePath",
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
                val source = PlaybackSource.Smb(server, entry.path, entry.sizeBytes)
                activity.playSmb(server, entry.path, resumeAtMs = 0L)
                onNavigate(Destination.Player(source))
            },
            thumbnailLoader = { entry ->
                val source = PlaybackSource.Smb(server, entry.path, entry.sizeBytes)
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

    fun handleBack(server: SmbServer): Boolean {
        if (browsePath.isNotEmpty()) {
            browsePath = browsePath.substringBeforeLast('/', missingDelimiterValue = "")
            searchBar?.clear()
            searchQuery = ""
            renderFiles(server)
            return true
        }
        return false
    }

    private fun loadDirectory(server: SmbServer) {
        val requestedPath = browsePath
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.nativeSmbListDirectory(
                    server.host, server.port, server.username, server.password,
                    server.domain, server.share, requestedPath
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
                val f3d = if (type == MediaType.VIDEO) MediaFilterEngine.detectFormat3DFromFilename(name) else com.vrplayer.filebrowser.Format3DType.FLAT_2D

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
