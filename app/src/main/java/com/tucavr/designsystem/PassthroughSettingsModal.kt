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
    private var currentMaskMode: Int = 0,
    private val isDetectedPackedAlpha: Boolean = false,
    private var isChromaKeyEnabled: Boolean = (currentMaskMode == 1),
    private var currentChromaColor: Int = 0x00FF00,
    private var currentChromaSimilarity: Float = 0.35f,
    private var currentChromaSmoothness: Float = 0.10f,
    private var currentPackedAlphaOpacityMultiplier: Float = 1.5f,
    private var currentPackedAlphaCutoff: Float = 0.06f,
    private var currentPackedAlphaChoke: Int = 65,
    private val onTogglePassthrough: (enabled: Boolean) -> Unit,
    private val onOpacityChanged: (opacity: Float) -> Unit,
    private val onEdgeRenderingChanged: (enabled: Boolean) -> Unit,
    private val onMaskModeChanged: (mode: Int) -> Unit = {},
    private val onToggleChromaKey: (enabled: Boolean) -> Unit = {},
    private val onChromaColorChanged: (color: Int) -> Unit = {},
    private val onChromaSimilarityChanged: (similarity: Float) -> Unit = {},
    private val onChromaSmoothnessChanged: (smoothness: Float) -> Unit = {},
    private val onPackedAlphaOpacityChanged: (multiplier: Float) -> Unit = {},
    private val onPackedAlphaCutoffChanged: (cutoff: Float) -> Unit = {},
    private val onPackedAlphaChokeChanged: (choke: Int) -> Unit = {},
    private val onAutoDetectChroma: ((callback: (com.tucavr.chroma.ChromaDetectionResult?) -> Unit) -> Unit)? = null,
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

        // 4. Seção de Máscara e Recorte de Fundo (Chroma Key & Packed Alpha)
        val maskHeader = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 14f)
                bottomMargin = VoidTheme.dpToPx(context, 10f)
            }
        }
        maskHeader.addView(
            VoidText.body(context, context.getString(R.string.mask_mode_section_title), sizeSp = 18f)
        )
        maskHeader.addView(
            VoidText.body(context, context.getString(R.string.mask_mode_description), sizeSp = 14f, secondary = true)
        )
        panel.addView(maskHeader)

        if (isDetectedPackedAlpha) {
            val detectedBadge = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(0x2844AAFF.toInt())
                    cornerRadius = VoidTheme.dp(context, 8f)
                    setStroke(VoidTheme.dpToPx(context, 1f), 0x6644AAFF.toInt())
                }
                val padH = VoidTheme.dpToPx(context, 12f)
                val padV = VoidTheme.dpToPx(context, 8f)
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = VoidTheme.dpToPx(context, 12f)
                }
            }
            detectedBadge.addView(
                VoidText.body(context, context.getString(R.string.mask_mode_packed_alpha_detected), sizeSp = 14f).apply {
                    setTextColor(0xFF88CCFF.toInt())
                }
            )
            panel.addView(detectedBadge)
        }

        val packedAlphaDetailsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (currentMaskMode == 2) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 14f)
            }
            background = GradientDrawable().apply {
                setColor(0x2244AAFF.toInt())
                cornerRadius = VoidTheme.dp(context, 12f)
                setStroke(VoidTheme.dpToPx(context, 1f), 0x5544AAFF.toInt())
            }
            val pad = VoidTheme.dpToPx(context, 16f)
            setPadding(pad, pad, pad, pad)
        }
        packedAlphaDetailsContainer.addView(
            VoidText.body(context, "✂️ " + context.getString(R.string.mask_mode_packed_alpha), sizeSp = 16f).apply {
                setTextColor(0xFF88CCFF.toInt())
            }
        )
        packedAlphaDetailsContainer.addView(
            VoidText.body(context, context.getString(R.string.mask_mode_packed_alpha_info), sizeSp = 14f, secondary = true).apply {
                setPadding(0, VoidTheme.dpToPx(context, 4f), 0, 0)
            }
        )

        // 1. Slider de Reforço de Opacidade do Packed Alpha (1.0x a 2.5x, padrão 1.5x)
        val alphaOpacityHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 10f)
                bottomMargin = VoidTheme.dpToPx(context, 6f)
            }
        }
        alphaOpacityHeader.addView(
            VoidText.body(context, context.getString(R.string.packed_alpha_opacity_multiplier_label), sizeSp = 15f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        val alphaOpacityValueText = VoidText.mono(
            context,
            context.getString(R.string.packed_alpha_opacity_multiplier_value, currentPackedAlphaOpacityMultiplier),
            sizeSp = 15f
        ).apply {
            setTextColor(VoidTheme.colorAccent)
        }
        alphaOpacityHeader.addView(alphaOpacityValueText)
        packedAlphaDetailsContainer.addView(alphaOpacityHeader)

        val alphaOpacitySeekBar = SeekBar(context).apply {
            max = 15 // 0 a 15 => 1.0x a 2.5x com passos de 0.1x
            progress = ((currentPackedAlphaOpacityMultiplier - 1.0f) * 10f).toInt().coerceIn(0, 15)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 40f)
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
            progressTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
            thumbTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val mult = 1.0f + progress * 0.1f
                    currentPackedAlphaOpacityMultiplier = mult
                    alphaOpacityValueText.text = context.getString(R.string.packed_alpha_opacity_multiplier_value, mult)
                    if (fromUser) {
                        onPackedAlphaOpacityChanged(mult)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        packedAlphaDetailsContainer.addView(alphaOpacitySeekBar)

        // 2. Slider de Corte de Fundo / Black Cutoff (1% a 10%, padrão 3%)
        val alphaCutoffHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 4f)
                bottomMargin = VoidTheme.dpToPx(context, 6f)
            }
        }
        alphaCutoffHeader.addView(
            VoidText.body(context, context.getString(R.string.packed_alpha_black_cutoff_label), sizeSp = 15f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        val cutoffPercent = (currentPackedAlphaCutoff * 100f).toInt().coerceIn(1, 20)
        val alphaCutoffValueText = VoidText.mono(
            context,
            context.getString(R.string.packed_alpha_black_cutoff_percent, cutoffPercent),
            sizeSp = 15f
        ).apply {
            setTextColor(VoidTheme.colorAccent)
        }
        alphaCutoffHeader.addView(alphaCutoffValueText)
        packedAlphaDetailsContainer.addView(alphaCutoffHeader)

        val alphaCutoffSeekBar = SeekBar(context).apply {
            max = 20
            progress = cutoffPercent
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 40f)
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
            progressTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
            thumbTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val actualProgress = progress.coerceAtLeast(1)
                    val cutoff = actualProgress / 100f
                    currentPackedAlphaCutoff = cutoff
                    alphaCutoffValueText.text = context.getString(R.string.packed_alpha_black_cutoff_percent, actualProgress)
                    if (fromUser) {
                        onPackedAlphaCutoffChanged(cutoff)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        packedAlphaDetailsContainer.addView(alphaCutoffSeekBar)

        // 3. Slider de Desbaste de Borda / Matte Choke (0% a 100%, padrão 65%)
        val alphaChokeHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 4f)
                bottomMargin = VoidTheme.dpToPx(context, 6f)
            }
        }
        alphaChokeHeader.addView(
            VoidText.body(context, context.getString(R.string.packed_alpha_choke_label), sizeSp = 15f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        val alphaChokeValueText = VoidText.mono(
            context,
            context.getString(R.string.packed_alpha_choke_percent, currentPackedAlphaChoke),
            sizeSp = 15f
        ).apply {
            setTextColor(VoidTheme.colorAccent)
        }
        alphaChokeHeader.addView(alphaChokeValueText)
        packedAlphaDetailsContainer.addView(alphaChokeHeader)

        val alphaChokeSeekBar = SeekBar(context).apply {
            max = 100
            progress = currentPackedAlphaChoke.coerceIn(0, 100)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 40f)
            )
            progressTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
            thumbTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    currentPackedAlphaChoke = progress
                    alphaChokeValueText.text = context.getString(R.string.packed_alpha_choke_percent, progress)
                    if (fromUser) {
                        onPackedAlphaChokeChanged(progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        packedAlphaDetailsContainer.addView(alphaChokeSeekBar)

        val chromaDetailsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (currentMaskMode == 1) View.VISIBLE else View.GONE
        }

        val modeSelectorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 14f)
            }
        }

        val btnModeOff = VoidButton(
            context,
            if (currentMaskMode == 0) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY,
            isCompact = true
        ).apply {
            text = context.getString(R.string.mask_mode_off)
            layoutParams = LinearLayout.LayoutParams(0, VoidTheme.dpToPx(context, 44f), 1f).apply {
                marginEnd = VoidTheme.dpToPx(context, 8f)
            }
        }

        val btnModePackedAlpha = VoidButton(
            context,
            if (currentMaskMode == 2) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY,
            isCompact = true
        ).apply {
            text = context.getString(R.string.mask_mode_packed_alpha)
            layoutParams = LinearLayout.LayoutParams(0, VoidTheme.dpToPx(context, 44f), 1f).apply {
                marginEnd = VoidTheme.dpToPx(context, 8f)
            }
        }

        val btnModeChroma = VoidButton(
            context,
            if (currentMaskMode == 1) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY,
            isCompact = true
        ).apply {
            text = context.getString(R.string.mask_mode_chroma_key)
            layoutParams = LinearLayout.LayoutParams(0, VoidTheme.dpToPx(context, 44f), 1f)
        }

        val updateModeButtons = {
            btnModeOff.style = if (currentMaskMode == 0) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
            btnModePackedAlpha.style = if (currentMaskMode == 2) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
            btnModeChroma.style = if (currentMaskMode == 1) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY

            packedAlphaDetailsContainer.visibility = if (currentMaskMode == 2) View.VISIBLE else View.GONE
            chromaDetailsContainer.visibility = if (currentMaskMode == 1) View.VISIBLE else View.GONE
        }

        btnModeOff.setOnClickListener {
            currentMaskMode = 0
            isChromaKeyEnabled = false
            updateModeButtons()
            onMaskModeChanged(0)
            onToggleChromaKey(false)
        }

        btnModePackedAlpha.setOnClickListener {
            currentMaskMode = 2
            isChromaKeyEnabled = false
            updateModeButtons()
            onMaskModeChanged(2)
        }

        btnModeChroma.setOnClickListener {
            currentMaskMode = 1
            isChromaKeyEnabled = true
            updateModeButtons()
            onMaskModeChanged(1)
            onToggleChromaKey(true)
        }

        modeSelectorRow.addView(btnModeOff)
        modeSelectorRow.addView(btnModePackedAlpha)
        modeSelectorRow.addView(btnModeChroma)
        panel.addView(modeSelectorRow)
        panel.addView(packedAlphaDetailsContainer)

        // Sub-controles de Chroma Key (Auto-Detect, Paleta de Cores, Sliders RGB, Similaridade e Suavidade)

        // 1. Botão de Auto-Detecção
        val autoDetectContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 4f)
                bottomMargin = VoidTheme.dpToPx(context, 12f)
            }
        }

        val autoDetectStatusText = VoidText.body(context, "", sizeSp = 14f, secondary = true).apply {
            visibility = View.GONE
            setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f))
        }

        val btnAutoDetect = VoidButton(context, VoidButtonStyle.SECONDARY, isCompact = true).apply {
            text = "🪄 " + context.getString(R.string.chroma_key_auto_detect_button)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 44f)
            )
        }
        autoDetectContainer.addView(btnAutoDetect)
        autoDetectContainer.addView(autoDetectStatusText)
        chromaDetailsContainer.addView(autoDetectContainer)

        // 2. Paleta de Cores de Estúdio (Presets)
        val paletteLabel = VoidText.body(context, context.getString(R.string.chroma_key_color_label), sizeSp = 16f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
        }
        chromaDetailsContainer.addView(paletteLabel)

        val presetVerde = 0x00FF00
        val presetAzul = 0x0000FF
        val presetVermelho = 0xFF0000
        val presetCinza = 0x808080
        val presetPreto = 0x000000
        val presetBranco = 0xFFFFFF

        val paletteRow1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
        }

        val paletteRow2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 14f)
            }
        }

        var btnPVerde: VoidButton? = null
        var btnPAzul: VoidButton? = null
        var btnPVermelho: VoidButton? = null
        var btnPCinza: VoidButton? = null
        var btnPPreto: VoidButton? = null
        var btnPBranco: VoidButton? = null

        var isInternalRgbUpdate = false
        var seekR: SeekBar? = null
        var seekG: SeekBar? = null
        var seekB: SeekBar? = null
        var textR: TextView? = null
        var textG: TextView? = null
        var textB: TextView? = null
        var hexCodeText: TextView? = null
        var colorPreviewSwatch: View? = null
        var simSeekBar: SeekBar? = null
        var simValueText: TextView? = null

        fun updateColorUi(color: Int, notify: Boolean = true) {
            currentChromaColor = color
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            colorPreviewSwatch?.background = GradientDrawable().apply {
                setColor(Color.rgb(r, g, b))
                cornerRadius = VoidTheme.dp(context, 6f)
                setStroke(VoidTheme.dpToPx(context, 1.5f), VoidTheme.colorBorder)
            }
            hexCodeText?.text = String.format("#%06X", color)

            isInternalRgbUpdate = true
            seekR?.progress = r
            seekG?.progress = g
            seekB?.progress = b
            textR?.text = context.getString(R.string.chroma_key_channel_r, r)
            textG?.text = context.getString(R.string.chroma_key_channel_g, g)
            textB?.text = context.getString(R.string.chroma_key_channel_b, b)
            isInternalRgbUpdate = false

            btnPVerde?.style = if (color == presetVerde) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
            btnPAzul?.style = if (color == presetAzul) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
            btnPVermelho?.style = if (color == presetVermelho) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
            btnPCinza?.style = if (color == presetCinza) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
            btnPPreto?.style = if (color == presetPreto) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
            btnPBranco?.style = if (color == presetBranco) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY

            if (notify) {
                onChromaColorChanged(color)
            }
        }

        fun createPaletteBtn(presetColor: Int, labelRes: Int): VoidButton {
            return VoidButton(
                context,
                if (currentChromaColor == presetColor) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY,
                isCompact = true
            ).apply {
                text = context.getString(labelRes)
                layoutParams = LinearLayout.LayoutParams(0, VoidTheme.dpToPx(context, 40f), 1f).apply {
                    setMargins(VoidTheme.dpToPx(context, 3f), 0, VoidTheme.dpToPx(context, 3f), 0)
                }
                setOnClickListener {
                    updateColorUi(presetColor, notify = true)
                }
            }
        }

        btnPVerde = createPaletteBtn(presetVerde, R.string.chroma_key_color_green)
        btnPAzul = createPaletteBtn(presetAzul, R.string.chroma_key_color_blue)
        btnPVermelho = createPaletteBtn(presetVermelho, R.string.chroma_key_color_red)
        paletteRow1.addView(btnPVerde)
        paletteRow1.addView(btnPAzul)
        paletteRow1.addView(btnPVermelho)
        chromaDetailsContainer.addView(paletteRow1)

        btnPCinza = createPaletteBtn(presetCinza, R.string.chroma_key_color_gray)
        btnPPreto = createPaletteBtn(presetPreto, R.string.chroma_key_color_black)
        btnPBranco = createPaletteBtn(presetBranco, R.string.chroma_key_color_white)
        paletteRow2.addView(btnPCinza)
        paletteRow2.addView(btnPPreto)
        paletteRow2.addView(btnPBranco)
        chromaDetailsContainer.addView(paletteRow2)

        // 3. Ajuste Fino de Cor (RGB)
        val rgbHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 8f)
            }
        }
        val rgbTitle = VoidText.body(context, context.getString(R.string.chroma_key_custom_color_title), sizeSp = 16f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        rgbHeader.addView(rgbTitle)

        colorPreviewSwatch = View(context).apply {
            val sw = VoidTheme.dpToPx(context, 32f)
            val sh = VoidTheme.dpToPx(context, 24f)
            layoutParams = LinearLayout.LayoutParams(sw, sh).apply {
                rightMargin = VoidTheme.dpToPx(context, 8f)
            }
        }
        rgbHeader.addView(colorPreviewSwatch)

        hexCodeText = VoidText.mono(context, String.format("#%06X", currentChromaColor), sizeSp = 16f).apply {
            setTextColor(VoidTheme.colorAccent)
        }
        rgbHeader.addView(hexCodeText)
        chromaDetailsContainer.addView(rgbHeader)

        fun createRgbSliderRow(labelRes: Int, initialValue: Int, onChannelChanged: (Int) -> Unit): Pair<SeekBar, TextView> {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = VoidTheme.dpToPx(context, 4f)
                }
            }
            val valueText = VoidText.mono(context, context.getString(labelRes, initialValue), sizeSp = 14f).apply {
                layoutParams = LinearLayout.LayoutParams(VoidTheme.dpToPx(context, 72f), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val seekBar = SeekBar(context).apply {
                max = 255
                progress = initialValue
                layoutParams = LinearLayout.LayoutParams(0, VoidTheme.dpToPx(context, 38f), 1f)
                progressTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
                thumbTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        valueText.text = context.getString(labelRes, progress)
                        if (fromUser && !isInternalRgbUpdate) {
                            onChannelChanged(progress)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            row.addView(valueText)
            row.addView(seekBar)
            chromaDetailsContainer.addView(row)
            return Pair(seekBar, valueText)
        }

        val initR = (currentChromaColor shr 16) and 0xFF
        val initG = (currentChromaColor shr 8) and 0xFF
        val initB = currentChromaColor and 0xFF

        val pairR = createRgbSliderRow(R.string.chroma_key_channel_r, initR) { newR ->
            val color = (newR shl 16) or ((seekG?.progress ?: 0) shl 8) or (seekB?.progress ?: 0)
            updateColorUi(color, notify = true)
        }
        seekR = pairR.first
        textR = pairR.second

        val pairG = createRgbSliderRow(R.string.chroma_key_channel_g, initG) { newG ->
            val color = (seekR.progress shl 16) or (newG shl 8) or (seekB?.progress ?: 0)
            updateColorUi(color, notify = true)
        }
        seekG = pairG.first
        textG = pairG.second

        val pairB = createRgbSliderRow(R.string.chroma_key_channel_b, initB) { newB ->
            val color = (seekR.progress shl 16) or (seekG.progress shl 8) or newB
            updateColorUi(color, notify = true)
        }
        seekB = pairB.first
        textB = pairB.second

        updateColorUi(currentChromaColor, notify = false)

        btnAutoDetect.setOnClickListener {
            btnAutoDetect.isEnabled = false
            autoDetectStatusText.visibility = View.VISIBLE
            autoDetectStatusText.text = context.getString(R.string.chroma_key_auto_detect_status_detecting)

            onAutoDetectChroma?.invoke { result ->
                post {
                    btnAutoDetect.isEnabled = true
                    if (result != null) {
                        val hex = String.format("#%06X", result.colorRgb)
                        autoDetectStatusText.text = context.getString(R.string.chroma_key_auto_detect_status_success, hex)
                        updateColorUi(result.colorRgb, notify = true)

                        currentChromaSimilarity = result.suggestedSimilarity
                        val simPercent = (result.suggestedSimilarity * 100f).toInt().coerceIn(1, 100)
                        simSeekBar?.progress = simPercent
                        simValueText?.text = context.getString(R.string.chroma_key_similarity_percent, simPercent)
                        onChromaSimilarityChanged(result.suggestedSimilarity)
                    } else {
                        autoDetectStatusText.text = context.getString(R.string.chroma_key_auto_detect_status_failed)
                    }
                }
            }
        }

        // 4. Slider de Similaridade / Tolerância
        val simHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 10f)
                bottomMargin = VoidTheme.dpToPx(context, 6f)
            }
        }
        simHeader.addView(VoidText.body(context, context.getString(R.string.chroma_key_similarity_label), sizeSp = 16f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val simPercent = (currentChromaSimilarity * 100f).toInt().coerceIn(1, 100)
        simValueText = VoidText.mono(context, context.getString(R.string.chroma_key_similarity_percent, simPercent), sizeSp = 16f).apply {
            setTextColor(VoidTheme.colorAccent)
        }
        simHeader.addView(simValueText)
        chromaDetailsContainer.addView(simHeader)

        simSeekBar = SeekBar(context).apply {
            max = 100
            progress = simPercent
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 44f)
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 10f)
            }
            progressTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
            thumbTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sim = progress / 100f
                    currentChromaSimilarity = sim
                    simValueText.text = context.getString(R.string.chroma_key_similarity_percent, progress)
                    if (fromUser) {
                        onChromaSimilarityChanged(sim)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        chromaDetailsContainer.addView(simSeekBar)

        // 5. Slider de Suavidade
        val smoothHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 6f)
                bottomMargin = VoidTheme.dpToPx(context, 6f)
            }
        }
        smoothHeader.addView(VoidText.body(context, context.getString(R.string.chroma_key_smoothness_label), sizeSp = 16f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val smoothPercent = (currentChromaSmoothness * 100f).toInt().coerceIn(1, 100)
        val smoothValueText = VoidText.mono(context, context.getString(R.string.chroma_key_smoothness_percent, smoothPercent), sizeSp = 16f).apply {
            setTextColor(VoidTheme.colorAccent)
        }
        smoothHeader.addView(smoothValueText)
        chromaDetailsContainer.addView(smoothHeader)

        val smoothSeekBar = SeekBar(context).apply {
            max = 50
            progress = smoothPercent
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 44f)
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 14f)
            }
            progressTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
            thumbTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val smooth = progress / 100f
                    currentChromaSmoothness = smooth
                    smoothValueText.text = context.getString(R.string.chroma_key_smoothness_percent, progress)
                    if (fromUser) {
                        onChromaSmoothnessChanged(smooth)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        chromaDetailsContainer.addView(smoothSeekBar)

        panel.addView(chromaDetailsContainer)

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
