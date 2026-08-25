package com.tucavr.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidFilterChip
import com.tucavr.designsystem.VoidIconButton
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidSearchBar
import com.tucavr.designsystem.VoidSortSelector
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme
import com.tucavr.filebrowser.DateFilter
import com.tucavr.filebrowser.DirectoryLister
import com.tucavr.filebrowser.DirectoryNavigator
import com.tucavr.filebrowser.FolderConfig
import com.tucavr.filebrowser.FolderConfigStore
import com.tucavr.filebrowser.FolderPreviewGenerator
import com.tucavr.filebrowser.FolderSummary
import com.tucavr.filebrowser.Format3DFilter
import com.tucavr.filebrowser.MediaEntry
import com.tucavr.filebrowser.MediaFilterEngine
import com.tucavr.filebrowser.MediaType
import com.tucavr.filebrowser.MediaTypeFilter
import com.tucavr.filebrowser.SortBy
import com.tucavr.filebrowser.ViewMode
import com.tucavr.filebrowser.sortMediaEntries
import com.tucavr.history.isResumable
import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
import com.tucavr.screens.adapters.FileAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tela de navegação de arquivos locais da biblioteca.
 * Inclui barra de pesquisa com debounce de 500ms, chips de filtro rápido, seletor de ordenação,
 * alternância entre Grade e Lista, configuração persistente por pasta (.vrplayer_folder_config.json),
 * mosaico de até 4 miniaturas em pastas, filtragem inteligente de pastas e reprodução direta com 1 toque.
 */
class LocalFilesScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val dirNavigator: DirectoryNavigator,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit,
    private val onPlayLocalVideo: (MediaEntry) -> Unit
) {

    private val folderConfigStore = FolderConfigStore(context)
    private var adapter: FileAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var countLabel: android.widget.TextView? = null
    private var emptyContainer: LinearLayout? = null
    private var searchBar: VoidSearchBar? = null

    // Estados de filtro e busca
    private var searchQuery = ""
    private var currentTypeFilter = MediaTypeFilter.ALL
    private var currentFormat3DFilter = Format3DFilter.ALL
    private var currentDateFilter = DateFilter.ALL
    private var currentConfig = FolderConfig()

    // Cache de mídias brutas do diretório atual e resumos de subpastas
    private var cachedRawEntries: List<MediaEntry> = emptyList()
    private val folderSummaries = mutableMapOf<String, FolderSummary>()

    fun renderLocalFiles() {
        val currentDir = dirNavigator.currentPath
        currentConfig = folderConfigStore.getConfigFor(currentDir.absolutePath)

        val root = VoidPanelChrome.newRoot(context)

        // 1. Cabeçalho de Navegação
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = context.getString(R.string.browser_title_local_files),
                subtitle = currentDir.absolutePath,
                onBack = { onBack() }
            )
        )

        // 2. Barra de Ferramentas: Busca + Ordenação + Toggle Grade/Lista
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
                folderConfigStore.saveConfigFor(currentDir.absolutePath, currentConfig)
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
                folderConfigStore.saveConfigFor(currentDir.absolutePath, currentConfig)
                setImageResource(if (nextMode == ViewMode.GRID) R.drawable.ic_view_list else R.drawable.ic_view_grid)
                updateLayoutManager()
                applyFiltersAndSort()
            }
        }
        toolbar.addView(viewModeBtn)

        root.addView(toolbar)

        // 3. Barra de Chips de Filtro
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

        // Chips de Tipo de Mídia
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

        // 4. Contador de Resultados
        val counterView = VoidText.mono(context, "", sizeSp = 13f, secondary = true).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 6f)
            }
        }
        countLabel = counterView
        root.addView(counterView)

        // 5. Container de Lista/Grade
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
                dirNavigator.enter(File(entry.path))
                searchBar?.clear()
                searchQuery = ""
                renderLocalFiles()
            },
            onVideoClick = { entry -> onPlayLocalVideo(entry) }
        )
        adapter = fileAdapter
        recycler.adapter = fileAdapter
        root.addView(recycler)

        // 6. Empty State View
        emptyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            visibility = View.GONE

            addView(VoidText.body(context, context.getString(R.string.browser_empty_search), sizeSp = 16f, secondary = true).apply {
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
        loadFiles()
    }

    private fun updateLayoutManager() {
        val recycler = recyclerView ?: return
        if (currentConfig.viewMode == ViewMode.GRID) {
            val gridLayout = GridLayoutManager(context, 3).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return if (position == 0 && dirNavigator.canGoBack()) 3 else 1
                    }
                }
            }
            recycler.layoutManager = gridLayout
        } else {
            recycler.layoutManager = LinearLayoutManager(context)
        }
    }

    /** Carrega os arquivos do diretório atual e enriquece com histórico e resumos de pastas. */
    fun loadFiles() {
        val dir = dirNavigator.currentPath
        scope.launch {
            val entries = DirectoryLister.listMedia(dir)

            // Enriquece cada entrada de vídeo com formato 3D/histórico e pastas com contagens
            val enriched = withContext(Dispatchers.IO) {
                entries.map { entry ->
                    when (entry.type) {
                        MediaType.VIDEO -> {
                            val history = activity.historyTracker.findExisting(PlaybackSource.LocalFile(entry.path, entry.sizeBytes))
                            val progressFraction = if (history != null && history.durationMs > 0) {
                                history.positionMs.toFloat() / history.durationMs.toFloat()
                            } else null
                            val lastPlayedAt = history?.lastPlayedAt
                            val f3d = MediaFilterEngine.detectFormat3DFromFilename(entry.name)

                            entry.copy(
                                format3DHint = f3d,
                                progressFraction = progressFraction,
                                lastPlayedAt = lastPlayedAt
                            )
                        }
                        MediaType.DIRECTORY -> {
                            val summary = FolderPreviewGenerator.getSummary(entry.path)
                            if (summary != null) {
                                folderSummaries[entry.path] = summary
                                entry.copy(itemCount = summary.totalItems)
                            } else entry
                        }
                        else -> entry
                    }
                }
            }

            cachedRawEntries = enriched
            applyFiltersAndSort()
        }
    }

    /** Aplica a busca, os filtros de tipo/3D/data com poda inteligente de pastas e ordenação. */
    private fun applyFiltersAndSort() {
        val currentAdapter = adapter ?: return
        val showUp = dirNavigator.canGoBack()

        val filtered = cachedRawEntries.filter { entry ->
            val summary = if (entry.type == MediaType.DIRECTORY) folderSummaries[entry.path] else null
            MediaFilterEngine.matchesFilter(
                entry = entry,
                query = searchQuery,
                typeFilter = currentTypeFilter,
                format3DFilter = currentFormat3DFilter,
                dateFilter = currentDateFilter,
                folderSummary = summary
            )
        }

        val sorted = sortMediaEntries(filtered, currentConfig.sortBy, currentConfig.ascending)

        currentAdapter.submit(sorted, showUp, currentConfig.viewMode, searchQuery)

        // Atualização do contador de resultados e do empty state
        countLabel?.text = context.getString(
            R.string.browser_results_count_format, sorted.size, cachedRawEntries.size
        )

        val isEmpty = sorted.isEmpty() && !showUp
        recyclerView?.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyContainer?.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    fun handleBack(): Boolean {
        if (dirNavigator.canGoBack()) {
            dirNavigator.goBack()
            searchBar?.clear()
            searchQuery = ""
            renderLocalFiles()
            return true
        }
        return false
    }
}
