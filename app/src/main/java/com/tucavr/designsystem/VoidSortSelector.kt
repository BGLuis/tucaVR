package com.tucavr.designsystem

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.tucavr.R
import com.tucavr.filebrowser.SortBy

/**
 * Seletor de ordenação compacto que permite alternar o campo de ordenação
 * (Nome, Data, Tamanho, Tipo, Último Reproduzido) e a direção (Crescente / Decrescente).
 */
class VoidSortSelector(
    context: Context,
    private var currentSortBy: SortBy = SortBy.NAME,
    private var currentAscending: Boolean = true,
    private val onSortChanged: (SortBy, Boolean) -> Unit
) : LinearLayout(context) {

    private val sortFieldBtn: VoidButton
    private val sortDirectionBtn: VoidIconButton

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        sortFieldBtn = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            textSize = 15f
            minHeight = VoidTheme.dpToPx(context, 48f)
            val padH = VoidTheme.dpToPx(context, 14f)
            val padV = VoidTheme.dpToPx(context, 8f)
            setPadding(padH, padV, padH, padV)
            setIcon(R.drawable.ic_sort)
            setOnClickListener {
                // Cicla entre os modos de ordenação
                val allValues = SortBy.values()
                val nextIndex = (currentSortBy.ordinal + 1) % allValues.size
                currentSortBy = allValues[nextIndex]
                updateButtons()
                onSortChanged(currentSortBy, currentAscending)
            }
        }
        addView(sortFieldBtn, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.marginEnd = VoidTheme.dpToPx(context, 6f)
        })

        sortDirectionBtn = VoidIconButton(
            context,
            if (currentAscending) R.drawable.ic_sort_asc else R.drawable.ic_sort_desc,
            VoidButtonStyle.SECONDARY,
            isCircular = false
        ).apply {
            layoutParams = LayoutParams(VoidTheme.dpToPx(context, 48f), VoidTheme.dpToPx(context, 48f))
            setOnClickListener {
                currentAscending = !currentAscending
                updateButtons()
                onSortChanged(currentSortBy, currentAscending)
            }
        }
        addView(sortDirectionBtn)

        updateButtons()
    }

    fun setSort(sortBy: SortBy, ascending: Boolean) {
        currentSortBy = sortBy
        currentAscending = ascending
        updateButtons()
    }

    private fun updateButtons() {
        val labelRes = when (currentSortBy) {
            SortBy.NAME -> R.string.browser_sort_name
            SortBy.DATE -> R.string.browser_sort_date
            SortBy.SIZE -> R.string.browser_sort_size
            SortBy.TYPE -> R.string.browser_sort_type
            SortBy.LAST_PLAYED -> R.string.browser_sort_last_played
        }
        sortFieldBtn.text = context.getString(labelRes)
        sortDirectionBtn.setImageResource(if (currentAscending) R.drawable.ic_sort_asc else R.drawable.ic_sort_desc)
    }
}
