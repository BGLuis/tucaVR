package com.vrplayer.designsystem

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Fileira de tabs com sublinhado ambar (com leve glow) no item ativo — usada
 * hoje pelas abas "URL"/"SMB" de `NetworkPresentation`. Views puras (sem
 * XML), mesmo padrao do resto do projeto.
 *
 * O "glow" e aproximado com duas barras sobrepostas (uma mais larga e
 * translucida atras, uma fina e opaca na frente) em vez de shadow layers —
 * mais barato e suficiente pro efeito visual pedido, sem depender de canvas
 * customizado.
 */
class VoidTabRow(
    context: Context,
    labels: List<String>,
    private val onTabSelected: (index: Int) -> Unit
) : LinearLayout(context) {

    private val tabLabels = mutableListOf<TextView>()
    private val tabUnderlineCores = mutableListOf<View>()
    private val tabUnderlineGlows = mutableListOf<View>()
    private var activeIndex = 0

    init {
        orientation = HORIZONTAL
        labels.forEachIndexed { index, label ->
            addView(buildTab(label, index), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        styleAll()
    }

    private fun buildTab(label: String, index: Int): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val text = VoidText.body(context, label, sizeSp = 20f).apply {
            gravity = Gravity.CENTER
            val padV = VoidTheme.dpToPx(context, 12f)
            setPadding(0, padV, 0, padV)
        }
        tabLabels.add(text)
        column.addView(text)

        val underlineHeight = VoidTheme.dpToPx(context, 6f)
        val underline = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, underlineHeight)
        }
        val glow = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, underlineHeight).apply {
                gravity = Gravity.CENTER
            }
            setBackgroundColor(VoidTheme.accentWithAlpha(70))
        }
        val core = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 2f)
            ).apply { gravity = Gravity.CENTER }
            setBackgroundColor(VoidTheme.colorAccent)
        }
        tabUnderlineGlows.add(glow)
        tabUnderlineCores.add(core)
        underline.addView(glow)
        underline.addView(core)
        column.addView(underline)

        column.setOnClickListener { setActiveIndex(index) }
        return column
    }

    fun setActiveIndex(index: Int, notify: Boolean = true) {
        activeIndex = index
        styleAll()
        if (notify) onTabSelected(index)
    }

    private fun styleAll() {
        tabLabels.forEachIndexed { i, tv ->
            val active = i == activeIndex
            tv.setTextColor(if (active) VoidTheme.colorAccent else VoidTheme.colorTextSecondary)
            tabUnderlineCores[i].visibility = if (active) View.VISIBLE else View.INVISIBLE
            tabUnderlineGlows[i].visibility = if (active) View.VISIBLE else View.INVISIBLE
        }
    }
}
