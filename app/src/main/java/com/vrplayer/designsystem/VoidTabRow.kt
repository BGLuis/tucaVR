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
    private val iconResIds: List<Int>? = null,
    private val onTabSelected: (index: Int) -> Unit
) : LinearLayout(context) {

    private val tabLabels = mutableListOf<TextView>()
    private val tabUnderlineCores = mutableListOf<View>()
    private val tabUnderlineGlows = mutableListOf<View>()
    private var activeIndex = 0

    init {
        orientation = HORIZONTAL
        labels.forEachIndexed { index, label ->
            val iconId = iconResIds?.getOrNull(index) ?: 0
            addView(buildTab(label, index, iconId), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        styleAll()
    }

    private fun buildTab(label: String, index: Int, iconResId: Int): LinearLayout {
        // Alvo de toque generoso (mesma razao do VoidButton/VoidListRow —
        // feedback de usuario em validacao real: hitbox pequena demais pra
        // apontar com precisao via raycast em VR).
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = VoidTheme.dpToPx(context, 76f)
        }

        val text = VoidText.body(context, label, sizeSp = 20f).apply {
            gravity = Gravity.CENTER
            val padV = VoidTheme.dpToPx(context, 24f)
            setPadding(0, padV, 0, padV)
            
            if (iconResId != 0) {
                val icon = context.getDrawable(iconResId)?.mutate()
                icon?.colorFilter = android.graphics.PorterDuffColorFilter(VoidTheme.colorTextSecondary, android.graphics.PorterDuff.Mode.SRC_IN)
                if (icon != null) {
                    val size = VoidTheme.dpToPx(context, 20f)
                    icon.setBounds(0, 0, size, size)
                    setCompoundDrawables(icon, null, null, null)
                    compoundDrawablePadding = VoidTheme.dpToPx(context, 8f)
                }
            }
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
            val color = if (active) VoidTheme.colorAccent else VoidTheme.colorTextSecondary
            tv.setTextColor(color)
            tv.compoundDrawables[0]?.let { drawable ->
                drawable.colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
            }
            tabUnderlineCores[i].visibility = if (active) View.VISIBLE else View.INVISIBLE
            tabUnderlineGlows[i].visibility = if (active) View.VISIBLE else View.INVISIBLE
        }
    }
}
