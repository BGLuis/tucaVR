package com.tucavr.designsystem

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.tucavr.R

/**
 * Modal flutuante para configuração e ajustes visuais do Passthrough / Mixed Reality (Fase 0.3 Seção 2: T2.4 e T2.5).
 *
 * Exibido no 3º Quad frontal flutuante (VRModalPresentation).
 * Fornece controles para:
 * 1. Habilitar/Desabilitar Passthrough (Toggle rápido).
 * 2. Slider de Opacidade do ambiente real (0% a 100%).
 * 3. Toggle de Edge Rendering (destaque de contorno dos objetos reais).
 * 4. Botão de Redefinir Posição da Tela Virtual para a âncora frontal padrão.
 */
class PassthroughSettingsModal(
    context: Context,
    private var isPassthroughEnabled: Boolean,
    private var currentOpacity: Float,
    private var isEdgeRenderingEnabled: Boolean,
    private val onTogglePassthrough: (enabled: Boolean) -> Unit,
    private val onOpacityChanged: (opacity: Float) -> Unit,
    private val onEdgeRenderingChanged: (enabled: Boolean) -> Unit,
    private val onResetScreenPosition: () -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val opacityValueText: TextView
    private val btnTogglePassthrough: VoidButton
    private val btnToggleEdge: VoidButton

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        setOnClickListener { onDismiss() }

        val panelWidth = VoidTheme.dpToPx(context, 680f)
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
            val pad = VoidTheme.dpToPx(context, 28f)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            setOnClickListener { /* Consumir clique dentro do modal */ }
        }

        // Header: Título + Botão Fechar
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 20f)
            }
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.passthrough_modal_title)
            typeface = VoidTheme.typefaceTitle
            textSize = 22f
            setTextColor(VoidTheme.colorText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        val closeBtn = VoidIconButton(
            context,
            R.drawable.icon_x,
            VoidButtonStyle.SECONDARY,
            isCircular = true,
            isTransparent = true
        ).apply {
            val s = VoidTheme.dpToPx(context, 48f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { onDismiss() }
        }
        header.addView(closeBtn)
        panel.addView(header)

        // 1. Linha de Toggle de Passthrough
        val toggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 18f)
            }
        }
        val toggleTextContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        toggleTextContainer.addView(
            VoidText.body(context, context.getString(R.string.passthrough_toggle_label), sizeSp = 18f)
        )
        toggleTextContainer.addView(
            VoidText.body(context, context.getString(R.string.passthrough_toggle_description), sizeSp = 14f, secondary = true)
        )
        toggleRow.addView(toggleTextContainer)

        btnTogglePassthrough = VoidButton(
            context,
            if (isPassthroughEnabled) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        ).apply {
            text = if (isPassthroughEnabled) context.getString(R.string.passthrough_state_on) else context.getString(R.string.passthrough_state_off)
            setOnClickListener {
                isPassthroughEnabled = !isPassthroughEnabled
                style = if (isPassthroughEnabled) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
                text = if (isPassthroughEnabled) context.getString(R.string.passthrough_state_on) else context.getString(R.string.passthrough_state_off)
                onTogglePassthrough(isPassthroughEnabled)
            }
        }
        toggleRow.addView(btnTogglePassthrough)
        panel.addView(toggleRow)

        // Divisor sutil
        panel.addView(createDivider())

        // 2. Linha de Opacidade (Slider 0% - 100%)
        val opacityHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 14f)
                bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
        }
        val opacityLabel = VoidText.body(context, context.getString(R.string.passthrough_opacity_label), sizeSp = 18f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        opacityHeader.addView(opacityLabel)

        val initialPercent = (currentOpacity * 100f).toInt().coerceIn(0, 100)
        opacityValueText = VoidText.mono(
            context,
            context.getString(R.string.passthrough_opacity_percent, initialPercent),
            sizeSp = 18f
        ).apply {
            setTextColor(VoidTheme.colorAccent)
        }
        opacityHeader.addView(opacityValueText)
        panel.addView(opacityHeader)

        val opacitySeekBar = SeekBar(context).apply {
            max = 100
            progress = initialPercent
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 48f)
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 16f)
            }
            progressTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
            thumbTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val opacity = progress / 100f
                    currentOpacity = opacity
                    opacityValueText.text = context.getString(R.string.passthrough_opacity_percent, progress)
                    if (fromUser) {
                        onOpacityChanged(opacity)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        panel.addView(opacitySeekBar)

        // Divisor sutil
        panel.addView(createDivider())

        // 3. Linha de Edge Rendering
        val edgeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 14f)
                bottomMargin = VoidTheme.dpToPx(context, 18f)
            }
        }
        val edgeTextContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        edgeTextContainer.addView(
            VoidText.body(context, context.getString(R.string.passthrough_edge_rendering_label), sizeSp = 18f)
        )
        edgeTextContainer.addView(
            VoidText.body(context, context.getString(R.string.passthrough_edge_rendering_description), sizeSp = 14f, secondary = true)
        )
        edgeRow.addView(edgeTextContainer)

        btnToggleEdge = VoidButton(
            context,
            if (isEdgeRenderingEnabled) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        ).apply {
            text = if (isEdgeRenderingEnabled) context.getString(R.string.passthrough_state_on) else context.getString(R.string.passthrough_state_off)
            setOnClickListener {
                isEdgeRenderingEnabled = !isEdgeRenderingEnabled
                style = if (isEdgeRenderingEnabled) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
                text = if (isEdgeRenderingEnabled) context.getString(R.string.passthrough_state_on) else context.getString(R.string.passthrough_state_off)
                onEdgeRenderingChanged(isEdgeRenderingEnabled)
            }
        }
        edgeRow.addView(btnToggleEdge)
        panel.addView(edgeRow)

        // Divisor sutil
        panel.addView(createDivider())

        // 4. Seção de Posicionamento Espacial e Dica Grab & Drag (T2.5)
        val posSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 14f)
            }
        }

        val grabHintText = VoidText.body(context, context.getString(R.string.passthrough_grab_hint), sizeSp = 14f, secondary = true).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 14f))
        }
        posSection.addView(grabHintText)

        val btnResetScreen = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.passthrough_reset_screen_button)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 48f)
            )
            setOnClickListener {
                onResetScreenPosition()
                // Efeito visual rápido de confirmação
                style = VoidButtonStyle.ACTIVE
                postDelayed({
                    style = VoidButtonStyle.SECONDARY
                }, 300)
            }
        }
        posSection.addView(btnResetScreen)
        panel.addView(posSection)

        addView(panel)
    }

    private fun createDivider(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            VoidTheme.dpToPx(context, 1f)
        )
        setBackgroundColor(VoidTheme.colorBorder)
    }
}
