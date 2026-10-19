package com.tucavr.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tucavr.EnvironmentRegistry
import com.tucavr.R

/**
 * Modal flutuante de seleção de Ambientes Virtuais 3D (Fase 0.3 §1 e Fase 0.5 §3).
 *
 * Apresenta o catálogo de ambientes disponíveis (Void, Espaço Cósmico, Cinema IMAX,
 * Sala de Estar e Passthrough MR), destacando o ambiente atualmente selecionado.
 */
class EnvironmentSelectorModal(
    context: Context,
    private var activeEnvironmentId: String,
    private val onEnvironmentSelected: (envId: String) -> Unit,
    private val onResetAnchor: () -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    private data class EnvironmentOption(
        val id: String,
        val title: String,
        val desc: String,
        val iconRes: Int,
    )

    private val options =
        EnvironmentRegistry.getAll().map { env ->
            val titleRes =
                context.resources.getIdentifier(
                    "env_${env.id}_title",
                    "string",
                    context.packageName,
                )
            val descRes =
                context.resources.getIdentifier(
                    "env_${env.id}_desc",
                    "string",
                    context.packageName,
                )
            val title = if (titleRes != 0) context.getString(titleRes) else env.name
            val desc = if (descRes != 0) context.getString(descRes) else ""
            val iconRes = EnvironmentRegistry.resolveIcon(context, env.id)
            EnvironmentOption(
                id = env.id,
                title = title,
                desc = desc,
                iconRes = iconRes,
            )
        }

    private val cardViews = mutableListOf<Pair<String, LinearLayout>>()

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        setOnClickListener { onDismiss() }

        val panelWidth = VoidTheme.dpToPx(context, 720f)
        val panel =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LayoutParams(panelWidth, LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER
                    }
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurface)
                        cornerRadius = VoidTheme.dp(context, 16f)
                        setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
                    }
                val pad = VoidTheme.dpToPx(context, 24f)
                setPadding(pad, pad, pad, pad)
                isClickable = true
                setOnClickListener { /* Consome clique dentro do card */ }
            }

        // Header: Título + Botão Fechar
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 16f)
                    }
            }

        val title =
            TextView(context).apply {
                text = context.getString(R.string.environments_modal_title)
                typeface = VoidTheme.typefaceTitle
                textSize = 22f
                setTextColor(VoidTheme.colorText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        header.addView(title)

        val closeBtn =
            VoidIconButton(
                context,
                R.drawable.icon_x,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                val s = VoidTheme.dpToPx(context, 44f)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setOnClickListener { onDismiss() }
            }
        header.addView(closeBtn)
        panel.addView(header)

        // Lista com rolagem
        val scrollView =
            ScrollView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        VoidTheme.dpToPx(context, 380f),
                    )
                isVerticalScrollBarEnabled = false
            }

        val listContainer =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    )
            }

        for (opt in options) {
            val card = createEnvironmentCard(opt)
            cardViews.add(opt.id to card)
            listContainer.addView(card)
        }
        scrollView.addView(listContainer)
        panel.addView(scrollView)

        // Footer: Botão de Redefinir Âncora da Tela
        val footer =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = VoidTheme.dpToPx(context, 16f)
                    }
            }

        val resetAnchorBtn =
            VoidButton(
                context,
                VoidButtonStyle.SECONDARY,
            ).apply {
                text = context.getString(R.string.env_reset_anchor_button)
                setOnClickListener {
                    onResetAnchor()
                }
            }
        footer.addView(resetAnchorBtn)
        panel.addView(footer)

        addView(panel)
        updateCardsSelection()
    }

    private fun createEnvironmentCard(option: EnvironmentOption): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = VoidTheme.dpToPx(context, 8f)
                }
            val padH = VoidTheme.dpToPx(context, 16f)
            val padV = VoidTheme.dpToPx(context, 14f)
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true

            // Ícone
            val icon =
                VoidIconButton(
                    context,
                    option.iconRes,
                    VoidButtonStyle.SECONDARY,
                    isCircular = true,
                    isTransparent = true,
                ).apply {
                    val s = VoidTheme.dpToPx(context, 40f)
                    layoutParams =
                        LinearLayout.LayoutParams(s, s).apply {
                            rightMargin = VoidTheme.dpToPx(context, 16f)
                        }
                    isClickable = false
                }
            addView(icon)

            // Textos
            val textCol =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

            val titleView =
                TextView(context).apply {
                    text = option.title
                    typeface = VoidTheme.typefaceTitle
                    textSize = 17f
                    setTextColor(VoidTheme.colorText)
                }
            textCol.addView(titleView)

            if (option.desc.isNotEmpty()) {
                val descView =
                    TextView(context).apply {
                        text = option.desc
                        typeface = VoidTheme.typefaceBody
                        textSize = 13f
                        setTextColor(VoidTheme.colorTextSecondary)
                    }
                textCol.addView(descView)
            }
            addView(textCol)

            setOnClickListener {
                activeEnvironmentId = option.id
                updateCardsSelection()
                onEnvironmentSelected(option.id)
            }
        }
    }

    private fun updateCardsSelection() {
        for ((id, card) in cardViews) {
            val isActive = (id == activeEnvironmentId)
            card.background =
                GradientDrawable().apply {
                    setColor(if (isActive) VoidTheme.colorSurfaceAlt else VoidTheme.colorSurface)
                    cornerRadius = VoidTheme.dp(context, 12f)
                    if (isActive) {
                        setStroke(VoidTheme.dpToPx(context, 2f), VoidTheme.colorAccent)
                    } else {
                        setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
                    }
                }
        }
    }
}
