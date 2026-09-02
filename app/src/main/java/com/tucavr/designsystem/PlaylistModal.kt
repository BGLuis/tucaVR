package com.tucavr.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tucavr.R
import com.tucavr.history.formatDurationMs
import com.tucavr.playlist.PlaybackMode
import com.tucavr.playlist.PlaylistQueueManager

/**
 * T9.4: Modal flutuante de fila de reprodução e modos sequenciais.
 *
 * Exibido no 3º Quad frontal flutuante (VRModalPresentation) durante a reprodução.
 * Mostra a lista de itens da fila com destaque para a faixa atual, permite pular direto
 * para qualquer item e alternar o modo de reprodução (Normal, Repetir Tudo, Repetir Uma, Aleatório).
 */
class PlaylistModal(
    context: Context,
    private val queueManager: PlaylistQueueManager,
    private val onPlayIndex: (Int) -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val btnMode: VoidButton
    private val itemsContainer: LinearLayout

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        setOnClickListener { onDismiss() }

        val panelWidth = VoidTheme.dpToPx(context, 700f)
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

        // Header: Título + Botão de Modo + Botão Fechar
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

        val playlistTitle = queueManager.currentPlaylist?.name ?: context.getString(R.string.playlists_queue_title)
        val title = TextView(context).apply {
            text = playlistTitle
            typeface = VoidTheme.typefaceTitle
            textSize = 22f
            setTextColor(VoidTheme.colorText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        // Botão de alternância do modo de reprodução
        btnMode = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = VoidTheme.dpToPx(context, 12f) }
            updateModeText(queueManager.playbackMode)
            setOnClickListener {
                val nextMode = queueManager.cyclePlaybackMode()
                updateModeText(nextMode)
            }
        }
        header.addView(btnMode)

        val closeBtn = VoidIconButton(
            context,
            R.drawable.icon_x,
            VoidButtonStyle.SECONDARY,
            isCircular = true,
            isTransparent = true
        ).apply {
            val s = VoidTheme.dpToPx(context, 44f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { onDismiss() }
        }
        header.addView(closeBtn)
        panel.addView(header)

        // Contêiner de Itens com rolagem
        itemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                VoidTheme.dpToPx(context, 320f)
            )
            addView(itemsContainer)
        }
        panel.addView(scroller)

        addView(panel)
        populateItems()
    }

    private fun updateModeText(mode: PlaybackMode) {
        val label = when (mode) {
            PlaybackMode.NORMAL -> context.getString(R.string.playlists_mode_normal)
            PlaybackMode.REPEAT_ALL -> context.getString(R.string.playlists_mode_repeat_all)
            PlaybackMode.REPEAT_ONE -> context.getString(R.string.playlists_mode_repeat_one)
            PlaybackMode.SHUFFLE -> context.getString(R.string.playlists_mode_shuffle)
        }
        btnMode.text = label
    }

    private fun populateItems() {
        itemsContainer.removeAllViews()
        val items = queueManager.getItems()
        val currentIdx = queueManager.getCurrentIndex()

        if (items.isEmpty()) {
            val emptyText = VoidText.body(
                context,
                context.getString(R.string.playlists_detail_empty),
                sizeSp = 16f,
                secondary = true
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, VoidTheme.dpToPx(context, 32f), 0, VoidTheme.dpToPx(context, 32f))
            }
            itemsContainer.addView(emptyText)
            return
        }

        items.forEachIndexed { index, item ->
            val isCurrent = index == currentIdx
            val row = VoidListRow(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = VoidTheme.dpToPx(context, 6f) }

                val durationText = if (item.durationMs > 0) formatDurationMs(item.durationMs) else ""
                val metaText = listOfNotNull(
                    durationText.ifEmpty { null },
                    item.sourceType
                ).joinToString(" · ")

                val prefix = if (isCurrent) "▶  " else "${index + 1}. "
                bind(
                    title = prefix + item.title,
                    meta = metaText,
                    showThumbnailSlot = false,
                    iconResId = if (isCurrent) R.drawable.ic_play_arrow else R.drawable.ic_movie
                )

                if (isCurrent) {
                    background = GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        cornerRadius = VoidTheme.dp(context, 10f)
                        setStroke(VoidTheme.dpToPx(context, 1.5f), VoidTheme.colorAccent)
                    }
                }

                setOnClickListener {
                    onPlayIndex(index)
                    onDismiss()
                }
            }
            itemsContainer.addView(row)
        }
    }
}
