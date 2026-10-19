package com.tucavr.screens.components

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidFilterChip
import com.tucavr.designsystem.VoidIconButton
import com.tucavr.designsystem.VoidSearchBar
import com.tucavr.designsystem.VoidSortSelector
import com.tucavr.designsystem.VoidTheme
import com.tucavr.filebrowser.DateFilter
import com.tucavr.filebrowser.Format3DFilter
import com.tucavr.filebrowser.MediaTypeFilter
import com.tucavr.filebrowser.SortBy
import com.tucavr.filebrowser.ViewMode
import com.tucavr.screens.ScreenHost
import kotlinx.coroutines.CoroutineScope

/**
 * Barra de ferramentas compartilhada para navegação de mídia (arquivos locais e redes).
 * Agrupa:
 * 1. Campo de busca com debounce (VoidSearchBar)
 * 2. Seletor de ordenação e direção (VoidSortSelector)
 * 3. Alternador de modo Lista / Grade (VoidIconButton)
 * 4. Chips de filtro horizontalmente roláveis (tipo de mídia, formato 3D e data opcional)
 */
class MediaBrowserToolbar(
    context: Context,
    host: ScreenHost,
    activity: VRActivity,
    scope: CoroutineScope,
    initialSortBy: SortBy,
    initialAscending: Boolean,
    initialViewMode: ViewMode,
    initialTypeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
    initialFormat3DFilter: Format3DFilter = Format3DFilter.ALL,
    initialDateFilter: DateFilter = DateFilter.ALL,
    showDateFilter: Boolean = false,
    private val onQueryChanged: (String) -> Unit,
    private val onSortChanged: (SortBy, Boolean) -> Unit,
    private val onViewModeChanged: (ViewMode) -> Unit,
    private val onFilterChanged: (MediaTypeFilter, Format3DFilter, DateFilter) -> Unit,
    private val onRefresh: (() -> Unit)? = null,
) : LinearLayout(context) {
    private val searchBar: VoidSearchBar
    private val sortSelector: VoidSortSelector
    private val viewModeBtn: VoidIconButton

    private var currentViewMode: ViewMode = initialViewMode
    private var currentTypeFilter: MediaTypeFilter = initialTypeFilter
    private var currentFormat3DFilter: Format3DFilter = initialFormat3DFilter
    private var currentDateFilter: DateFilter = initialDateFilter

    private val typeChips = mutableListOf<Pair<MediaTypeFilter, VoidFilterChip>>()
    private val format3DChips = mutableListOf<Pair<Format3DFilter, VoidFilterChip>>()
    private val dateChips = mutableListOf<Pair<DateFilter, VoidFilterChip>>()

    init {
        orientation = VERTICAL
        layoutParams =
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )

        // 1. Linha Superior: Busca + Ordenação + Toggle Grade/Lista
        val topRow =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).also {
                        it.bottomMargin = VoidTheme.dpToPx(context, 10f)
                    }
            }

        searchBar =
            VoidSearchBar(
                context = context,
                host = host,
                scope = scope,
                hintText = context.getString(R.string.browser_search_hint),
                activity = activity,
                onQueryChanged = { query ->
                    onQueryChanged(query)
                },
            ).apply {
                layoutParams =
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                        it.marginEnd = VoidTheme.dpToPx(context, 8f)
                    }
            }
        topRow.addView(searchBar)

        sortSelector =
            VoidSortSelector(
                context = context,
                currentSortBy = initialSortBy,
                currentAscending = initialAscending,
                onSortChanged = { newSort, newAscending ->
                    onSortChanged(newSort, newAscending)
                },
            ).apply {
                layoutParams =
                    LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).also {
                        it.marginEnd = VoidTheme.dpToPx(context, 8f)
                    }
            }
        topRow.addView(sortSelector)

        viewModeBtn =
            VoidIconButton(
                context,
                if (currentViewMode == ViewMode.GRID) R.drawable.ic_view_list else R.drawable.ic_view_grid,
                VoidButtonStyle.SECONDARY,
                isCircular = false,
            ).apply {
                layoutParams =
                    LayoutParams(
                        VoidTheme.dpToPx(context, 48f),
                        VoidTheme.dpToPx(context, 48f),
                    )
                setOnClickListener {
                    val nextMode = if (currentViewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
                    currentViewMode = nextMode
                    setImageResource(if (nextMode == ViewMode.GRID) R.drawable.ic_view_list else R.drawable.ic_view_grid)
                    onViewModeChanged(nextMode)
                }
            }
        topRow.addView(viewModeBtn)

        if (onRefresh != null) {
            val refreshBtn =
                VoidIconButton(
                    context,
                    R.drawable.ic_browser_refresh,
                    VoidButtonStyle.SECONDARY,
                    isCircular = false,
                ).apply {
                    contentDescription = context.getString(R.string.browser_btn_refresh)
                    layoutParams =
                        LayoutParams(
                            VoidTheme.dpToPx(context, 48f),
                            VoidTheme.dpToPx(context, 48f),
                        ).also {
                            it.marginStart = VoidTheme.dpToPx(context, 8f)
                        }
                    setOnClickListener {
                        onRefresh.invoke()
                    }
                }
            topRow.addView(refreshBtn)
        }

        addView(topRow)

        // 2. Linha Inferior: Chips de Filtro
        val filterScrollView =
            HorizontalScrollView(context).apply {
                layoutParams =
                    LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).also {
                        it.bottomMargin = VoidTheme.dpToPx(context, 8f)
                    }
                isHorizontalScrollBarEnabled = false
            }

        val filterChipRow =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        // Chips de Tipo de Mídia
        listOf(
            MediaTypeFilter.ALL to R.string.browser_filter_all,
            MediaTypeFilter.VIDEO to R.string.browser_filter_video,
            MediaTypeFilter.AUDIO to R.string.browser_filter_audio,
            MediaTypeFilter.IMAGE to R.string.browser_filter_image,
        ).forEach { (type, res) ->
            val chip =
                VoidFilterChip(context, context.getString(res), isSelectedChip = currentTypeFilter == type).apply {
                    layoutParams =
                        LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).also {
                            it.marginEnd = VoidTheme.dpToPx(context, 8f)
                        }
                }
            typeChips.add(type to chip)
            chip.setOnClickListener {
                currentTypeFilter = type
                typeChips.forEach { (t, c) -> c.setSelectedState(t == currentTypeFilter) }
                notifyFilterChanged()
            }
            filterChipRow.addView(chip)
        }

        // Chips de Formato 3D
        listOf(
            Format3DFilter.ALL to R.string.browser_filter_3d_all,
            Format3DFilter.SBS to R.string.browser_filter_3d_sbs,
            Format3DFilter.OU to R.string.browser_filter_3d_ou,
            Format3DFilter.VR_180 to R.string.browser_filter_3d_180,
            Format3DFilter.VR_360 to R.string.browser_filter_3d_360,
        ).forEach { (f3d, res) ->
            val chip =
                VoidFilterChip(context, context.getString(res), isSelectedChip = currentFormat3DFilter == f3d).apply {
                    layoutParams =
                        LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).also {
                            it.marginEnd = VoidTheme.dpToPx(context, 8f)
                        }
                }
            format3DChips.add(f3d to chip)
            chip.setOnClickListener {
                currentFormat3DFilter = f3d
                format3DChips.forEach { (f, c) -> c.setSelectedState(f == currentFormat3DFilter) }
                notifyFilterChanged()
            }
            filterChipRow.addView(chip)
        }

        // Chips de Data Opcionais
        if (showDateFilter) {
            listOf(
                DateFilter.ALL to R.string.browser_filter_date_all,
                DateFilter.RECENT_24H to R.string.browser_filter_date_recent,
                DateFilter.LAST_7_DAYS to R.string.browser_filter_date_week,
                DateFilter.LAST_30_DAYS to R.string.browser_filter_date_month,
            ).forEach { (date, res) ->
                val chip =
                    VoidFilterChip(context, context.getString(res), isSelectedChip = currentDateFilter == date).apply {
                        layoutParams =
                            LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).also {
                                it.marginEnd = VoidTheme.dpToPx(context, 8f)
                            }
                    }
                dateChips.add(date to chip)
                chip.setOnClickListener {
                    currentDateFilter = date
                    dateChips.forEach { (d, c) -> c.setSelectedState(d == currentDateFilter) }
                    notifyFilterChanged()
                }
                filterChipRow.addView(chip)
            }
        }

        filterScrollView.addView(filterChipRow)
        addView(filterScrollView)
    }

    private fun notifyFilterChanged() {
        onFilterChanged(currentTypeFilter, currentFormat3DFilter, currentDateFilter)
    }

    fun clearSearch() {
        searchBar.clear()
    }

    fun resetFilters() {
        searchBar.clear()
        currentTypeFilter = MediaTypeFilter.ALL
        currentFormat3DFilter = Format3DFilter.ALL
        currentDateFilter = DateFilter.ALL
        typeChips.forEach { (t, c) -> c.setSelectedState(t == MediaTypeFilter.ALL) }
        format3DChips.forEach { (f, c) -> c.setSelectedState(f == Format3DFilter.ALL) }
        dateChips.forEach { (d, c) -> c.setSelectedState(d == DateFilter.ALL) }
    }

    fun setViewMode(mode: ViewMode) {
        currentViewMode = mode
        viewModeBtn.setImageResource(if (mode == ViewMode.GRID) R.drawable.ic_view_list else R.drawable.ic_view_grid)
    }
}
