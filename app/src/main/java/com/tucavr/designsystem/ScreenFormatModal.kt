package com.tucavr.designsystem

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tucavr.R
import com.tucavr.screens.ScreenFormatCatalog
import com.tucavr.screens.ScreenFormatEntry
import com.tucavr.screens.ScreenFormatGroup

/**
 * Modal flutuante de seleção de formato de tela (2D/3D/Esférico).
 *
 * Exibido como overlay sobre o `screenHost` de `VRPresentation`, organizado em 4 abas
 * (Automático, Plano, 360°, 180°) com ícones representativos para cada um dos 10 modos.
 */
class ScreenFormatModal(
    context: Context,
    private var currentMode: Int,
    private val detectedMode: Int? = null,
    private val detectionConfidence: Int = 3,
    private val onModeSelected: (modeIndex: Int) -> Unit,
    private val onUseAutoDetection: () -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private data class ModeCardViews(
        val container: LinearLayout,
        val iconView: ImageView,
        val labelView: TextView
    )

    private val modeCardMap = mutableMapOf<Int, ModeCardViews>()
    private val tabPages = mutableListOf<View>()
    private val tabRow: VoidTabRow

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.parseColor("#E6000000"))
        isClickable = true
        setOnClickListener { onDismiss() }

        val panelWidth = VoidTheme.dpToPx(context, 760f)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(panelWidth, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurface)
                cornerRadius = VoidTheme.dp(context, 16f)
                setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
            }
            val pad = VoidTheme.dpToPx(context, 24f)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            setOnClickListener { /* Consumir clique dentro do painel */ }
        }

        // Header: Título + Botão Fechar
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = VoidTheme.dpToPx(context, 16f)
            }
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.format_modal_title)
            typeface = VoidTheme.typefaceTitle
            textSize = 22f
            setTextColor(VoidTheme.colorText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        val closeBtn = VoidIconButton(context, R.drawable.icon_x, VoidButtonStyle.SECONDARY, isCircular = true, isTransparent = true).apply {
            val s = VoidTheme.dpToPx(context, 48f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { onDismiss() }
        }
        header.addView(closeBtn)
        panel.addView(header)

        // Abas
        val tabLabels = listOf(
            context.getString(R.string.format_tab_auto),
            context.getString(R.string.format_tab_flat),
            context.getString(R.string.format_tab_360),
            context.getString(R.string.format_tab_180)
        )
        val tabIcons = listOf(
            R.drawable.icon_gauge,
            R.drawable.icon_2d,
            R.drawable.icon_360,
            R.drawable.icon_180
        )

        val contentHost = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = VoidTheme.dpToPx(context, 16f)
                bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
        }

        // Construção das Páginas
        val autoPage = buildAutoPage()
        val flatPage = buildGroupPage(ScreenFormatGroup.FLAT)
        val s360Page = buildGroupPage(ScreenFormatGroup.SPHERICAL_360)
        val s180Page = buildGroupPage(ScreenFormatGroup.SPHERICAL_180)

        tabPages.add(autoPage)
        tabPages.add(flatPage)
        tabPages.add(s360Page)
        tabPages.add(s180Page)

        tabPages.forEach { page ->
            contentHost.addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        tabRow = VoidTabRow(context, tabLabels, tabIcons) { selectedTab ->
            showTab(selectedTab)
        }
        panel.addView(tabRow)
        panel.addView(contentHost)

        addView(panel)

        // Define aba inicial com base no modo ativo
        val initialTab = when {
            currentMode in 0..4 -> 1
            currentMode in listOf(5, 7, 8) -> 2
            currentMode in listOf(6, 9) -> 3
            else -> 0
        }
        tabRow.setActiveIndex(initialTab, notify = false)
        showTab(initialTab)
        updateCardsHighlight()
    }

    private fun showTab(tabIndex: Int) {
        tabPages.forEachIndexed { index, view ->
            view.visibility = if (index == tabIndex) View.VISIBLE else View.GONE
        }
    }

    private fun buildAutoPage(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = VoidTheme.dpToPx(context, 16f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurfaceAlt)
                cornerRadius = VoidTheme.dp(context, 12f)
                setStroke(VoidTheme.dpToPx(context, 1f), VoidTheme.colorBorder)
            }
        }

        val detectedModeName = if (detectedMode != null && detectedMode in 0..9) {
            val name = context.getString(ScreenFormatCatalog.getLabelResId(detectedMode))
            val confidenceSuffix = when {
                detectionConfidence >= 3 -> " (${context.getString(R.string.format_confidence_high)})"
                detectionConfidence == 2 -> " (${context.getString(R.string.format_confidence_medium)})"
                else -> " (${context.getString(R.string.format_confidence_low)})"
            }
            name + confidenceSuffix
        } else {
            context.getString(R.string.format_auto_no_media)
        }

        val activeModeName = context.getString(ScreenFormatCatalog.getLabelResId(currentMode))

        container.addView(buildInfoRow(context.getString(R.string.format_auto_detected_title), detectedModeName))
        container.addView(buildInfoRow(context.getString(R.string.format_auto_current_title), activeModeName))

        val btnReset = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.format_auto_btn_reset)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = VoidTheme.dpToPx(context, 16f)
            }
            setOnClickListener {
                onUseAutoDetection()
                onDismiss()
            }
        }
        container.addView(btnReset)

        return container
    }

    private fun buildInfoRow(label: String, value: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, VoidTheme.dpToPx(context, 6f), 0, VoidTheme.dpToPx(context, 6f))

            addView(TextView(context).apply {
                text = label
                typeface = VoidTheme.typefaceBody
                textSize = 15f
                setTextColor(VoidTheme.colorTextSecondary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.45f)
            })

            addView(TextView(context).apply {
                text = value
                typeface = VoidTheme.typefaceBody
                textSize = 15f
                setTextColor(VoidTheme.colorText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.55f)
            })
        }
    }

    private fun buildGroupPage(group: ScreenFormatGroup): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                val padV = VoidTheme.dpToPx(context, 12f)
                setPadding(0, padV, 0, padV)
            }
        }

        val modes = ScreenFormatCatalog.getByGroup(group)
        modes.forEach { entry ->
            val card = buildModeCard(entry)
            row.addView(card)
        }

        return row
    }

    private fun buildModeCard(entry: ScreenFormatEntry): View {
        val cardMargin = VoidTheme.dpToPx(context, 6f)
        val cardPad = VoidTheme.dpToPx(context, 12f)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, VoidTheme.dpToPx(context, 96f), 1f).apply {
                setMargins(cardMargin, 0, cardMargin, 0)
            }
            setPadding(cardPad, cardPad, cardPad, cardPad)
            isClickable = true
            isFocusable = true
        }

        val icon = ImageView(context).apply {
            setImageResource(entry.iconResId)
            val iconSize = VoidTheme.dpToPx(context, 36f)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        }
        card.addView(icon)

        val label = TextView(context).apply {
            text = context.getString(entry.labelResId)
            typeface = VoidTheme.typefaceBody
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, VoidTheme.dpToPx(context, 8f), 0, 0)
        }
        card.addView(label)

        card.setOnClickListener {
            currentMode = entry.index
            updateCardsHighlight()
            onModeSelected(entry.index)
            onDismiss()
        }

        modeCardMap[entry.index] = ModeCardViews(card, icon, label)
        return card
    }

    private fun updateCardsHighlight() {
        modeCardMap.forEach { (modeIndex, views) ->
            val isSelected = modeIndex == currentMode
            if (isSelected) {
                views.container.background = GradientDrawable().apply {
                    setColor(VoidTheme.colorSurfaceAlt)
                    cornerRadius = VoidTheme.dp(context, 12f)
                    setStroke(VoidTheme.dpToPx(context, 2f), VoidTheme.colorAccent)
                }
                views.iconView.setColorFilter(VoidTheme.colorAccent)
                views.labelView.setTextColor(VoidTheme.colorAccent)
            } else {
                views.container.background = RippleDrawable(
                    ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        cornerRadius = VoidTheme.dp(context, 12f)
                        setStroke(VoidTheme.dpToPx(context, 1f), VoidTheme.colorBorder)
                    },
                    null
                )
                views.iconView.setColorFilter(VoidTheme.colorText)
                views.labelView.setTextColor(VoidTheme.colorText)
            }
        }
    }
}
