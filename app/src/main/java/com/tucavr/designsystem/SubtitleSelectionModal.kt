package com.tucavr.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tucavr.R

/**
 * Modal flutuante de seleção de faixas de legendas e ajuste fino de sincronização (Offset).
 *
 * Exibido no 3º Quad frontal flutuante (VRModalPresentation) a 1,35 m à frente do usuário.
 * Fundo transparente com card centralizado escuro, fechamento via backdrop, botão X ou B/Y nativo.
 */
class SubtitleSelectionModal(
    context: Context,
    private val trackCount: Int,
    private var currentTrack: Int,
    private var currentOffsetMs: Long,
    private val onTrackSelected: (trackIndex: Int) -> Unit,
    private val onOffsetChanged: (newOffsetMs: Long) -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val trackButtons = mutableListOf<Pair<Int, LinearLayout>>()
    private val offsetValueText: TextView

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
            val pad = VoidTheme.dpToPx(context, 24f)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            setOnClickListener { /* Consumir clique dentro do card */ }
        }

        // Header: Título + Botão Fechar
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 16f)
            }
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.subtitles_modal_title)
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

        // Lista de Faixas com rolagem se houver muitas faixas
        val trackListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Opção Desativado (track = -1)
        val offBtn = buildTrackRow(context.getString(R.string.subtitles_option_off), -1)
        trackListContainer.addView(offBtn)
        trackButtons.add(-1 to offBtn)

        // Faixas embutidas/carregadas
        for (i in 0 until trackCount) {
            val label = context.getString(R.string.subtitles_track_format, i + 1, "Track ${i + 1}")
            val trackRow = buildTrackRow(label, i)
            trackListContainer.addView(trackRow)
            trackButtons.add(i to trackRow)
        }

        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 240f)
            )
            isFillViewport = true
            addView(trackListContainer)
        }
        panel.addView(scroller)

        // Seção de Ajuste de Sincronização (Offset)
        val syncSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = VoidTheme.dpToPx(context, 20f)
            }
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurfaceAlt)
                cornerRadius = VoidTheme.dp(context, 12f)
                setStroke(VoidTheme.dpToPx(context, 1f), VoidTheme.colorBorder)
            }
            val p = VoidTheme.dpToPx(context, 16f)
            setPadding(p, p, p, p)
        }

        val syncLabel = TextView(context).apply {
            text = context.getString(R.string.subtitles_sync_label)
            typeface = VoidTheme.typefaceBody
            textSize = 16f
            setTextColor(VoidTheme.colorTextSecondary)
            gravity = Gravity.CENTER
        }
        syncSection.addView(syncLabel)

        val offsetSec = currentOffsetMs / 1000.0
        offsetValueText = TextView(context).apply {
            text = context.getString(R.string.subtitles_sync_value_format, offsetSec)
            typeface = VoidTheme.typefaceMono
            textSize = 22f
            setTextColor(VoidTheme.colorAccent)
            gravity = Gravity.CENTER
            setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 12f))
        }
        syncSection.addView(offsetValueText)

        val syncBtnsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun addOffsetBtn(label: String, deltaMs: Long) {
            val btn = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = label
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    val m = VoidTheme.dpToPx(context, 3f)
                    setMargins(m, 0, m, 0)
                }
                setOnClickListener {
                    val newOffset = if (deltaMs == 0L) 0L else currentOffsetMs + deltaMs
                    currentOffsetMs = newOffset
                    offsetValueText.text = context.getString(
                        R.string.subtitles_sync_value_format,
                        currentOffsetMs / 1000.0
                    )
                    onOffsetChanged(newOffset)
                }
            }
            syncBtnsRow.addView(btn)
        }

        addOffsetBtn(context.getString(R.string.subtitles_sync_minus_500), -500L)
        addOffsetBtn(context.getString(R.string.subtitles_sync_minus_100), -100L)
        addOffsetBtn(context.getString(R.string.subtitles_sync_reset), 0L)
        addOffsetBtn(context.getString(R.string.subtitles_sync_plus_100), 100L)
        addOffsetBtn(context.getString(R.string.subtitles_sync_plus_500), 500L)

        syncSection.addView(syncBtnsRow)
        panel.addView(syncSection)

        addView(panel)
        refreshTrackHighlight()
    }

    private fun buildTrackRow(label: String, trackIndex: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 52f)
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 4f)
            }
            val padH = VoidTheme.dpToPx(context, 16f)
            setPadding(padH, 0, padH, 0)
            isClickable = true

            val icon = ImageView(context).apply {
                setImageResource(R.drawable.icon_subtitles)
                val s = VoidTheme.dpToPx(context, 20f)
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    rightMargin = VoidTheme.dpToPx(context, 12f)
                }
            }
            addView(icon)

            val text = TextView(context).apply {
                this.text = label
                typeface = VoidTheme.typefaceBody
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(text)

            val checkIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                val s = VoidTheme.dpToPx(context, 18f)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setColorFilter(VoidTheme.colorAccent)
            }
            addView(checkIcon)

            setOnClickListener {
                currentTrack = trackIndex
                refreshTrackHighlight()
                onTrackSelected(trackIndex)
            }
        }
    }

    private fun refreshTrackHighlight() {
        for ((idx, row) in trackButtons) {
            val isSelected = (idx == currentTrack)
            val icon = row.getChildAt(0) as? ImageView
            val text = row.getChildAt(1) as? TextView
            val check = row.getChildAt(2) as? ImageView

            if (isSelected) {
                row.background = GradientDrawable().apply {
                    setColor(VoidTheme.colorSurfaceAlt)
                    cornerRadius = VoidTheme.dp(context, 8f)
                    setStroke(VoidTheme.dpToPx(context, 1.5f), VoidTheme.colorAccent)
                }
                text?.setTextColor(VoidTheme.colorAccent)
                icon?.setColorFilter(VoidTheme.colorAccent)
                check?.visibility = View.VISIBLE
            } else {
                row.background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = VoidTheme.dp(context, 8f)
                }
                text?.setTextColor(VoidTheme.colorText)
                icon?.setColorFilter(VoidTheme.colorTextSecondary)
                check?.visibility = View.GONE
            }
        }
    }
}
