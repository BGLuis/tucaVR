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
import com.tucavr.filebrowser.TrackInfo
import com.tucavr.filebrowser.TrackKind

/**
 * Modal flutuante de seleção de trilha de áudio.
 *
 * Exibido no 3º Quad frontal flutuante (VRModalPresentation) a 1,35 m à frente do usuário.
 * Apresenta as faixas de áudio disponíveis com idioma, codec, canais e título, com realce da trilha ativa.
 */
class AudioTrackModal(
    context: Context,
    private val tracks: List<TrackInfo>,
    private var activeOrdinal: Int,
    private val onTrackSelected: (ordinal: Int) -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val trackButtons = mutableListOf<Pair<Int, LinearLayout>>()

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
            text = context.getString(R.string.audio_tracks_modal_title)
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

        val audioTracks = tracks.filter { it.kind == TrackKind.AUDIO }

        if (audioTracks.isEmpty()) {
            val emptyText = TextView(context).apply {
                text = context.getString(R.string.audio_tracks_empty)
                typeface = VoidTheme.typefaceBody
                textSize = 16f
                setTextColor(VoidTheme.colorTextSecondary)
                gravity = Gravity.CENTER
                setPadding(0, VoidTheme.dpToPx(context, 32f), 0, VoidTheme.dpToPx(context, 32f))
            }
            panel.addView(emptyText)
        } else {
            val trackListContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            audioTracks.forEach { track ->
                val trackRow = buildAudioTrackRow(track)
                trackListContainer.addView(trackRow)
                trackButtons.add(track.ordinal to trackRow)
            }

            val scroller = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    VoidTheme.dpToPx(context, 300f)
                )
                isFillViewport = true
                addView(trackListContainer)
            }
            panel.addView(scroller)
        }

        addView(panel)
        refreshTrackHighlight()
    }

    private fun buildAudioTrackRow(track: TrackInfo): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 60f)
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 6f)
            }
            val padH = VoidTheme.dpToPx(context, 16f)
            setPadding(padH, 0, padH, 0)
            isClickable = true

            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_audio)
                val s = VoidTheme.dpToPx(context, 22f)
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    rightMargin = VoidTheme.dpToPx(context, 14f)
                }
            }
            addView(icon)

            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val trackTitle = TextView(context).apply {
                val codecPart = if (track.codec.isNotEmpty()) " · ${track.codec}" else ""
                text = context.getString(R.string.file_detail_track_audio_format, track.ordinal + 1, codecPart.trimStart(' ', '·'))
                typeface = VoidTheme.typefaceBody
                textSize = 16f
            }
            textColumn.addView(trackTitle)

            val descParts = mutableListOf<String>()
            if (track.language.isNotEmpty()) descParts.add(track.language.uppercase())
            if (track.title.isNotEmpty()) descParts.add(track.title)
            if (track.channels > 0) descParts.add("${track.channels} ch")

            if (descParts.isNotEmpty()) {
                val trackDesc = TextView(context).apply {
                    text = descParts.joinToString(" · ")
                    typeface = VoidTheme.typefaceMono
                    textSize = 13f
                    setTextColor(VoidTheme.colorTextSecondary)
                }
                textColumn.addView(trackDesc)
            }
            addView(textColumn)

            val checkIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                val s = VoidTheme.dpToPx(context, 20f)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setColorFilter(VoidTheme.colorAccent)
            }
            addView(checkIcon)

            setOnClickListener {
                activeOrdinal = track.ordinal
                refreshTrackHighlight()
                onTrackSelected(track.ordinal)
            }
        }
    }

    private fun refreshTrackHighlight() {
        for ((ordinal, row) in trackButtons) {
            val isSelected = (ordinal == activeOrdinal)
            val icon = row.getChildAt(0) as? ImageView
            val textColumn = row.getChildAt(1) as? LinearLayout
            val title = textColumn?.getChildAt(0) as? TextView
            val check = row.getChildAt(2) as? ImageView

            if (isSelected) {
                row.background = GradientDrawable().apply {
                    setColor(VoidTheme.colorSurfaceAlt)
                    cornerRadius = VoidTheme.dp(context, 8f)
                    setStroke(VoidTheme.dpToPx(context, 1.5f), VoidTheme.colorAccent)
                }
                title?.setTextColor(VoidTheme.colorAccent)
                icon?.setColorFilter(VoidTheme.colorAccent)
                check?.visibility = View.VISIBLE
            } else {
                row.background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = VoidTheme.dp(context, 8f)
                }
                title?.setTextColor(VoidTheme.colorText)
                icon?.setColorFilter(VoidTheme.colorTextSecondary)
                check?.visibility = View.GONE
            }
        }
    }
}
