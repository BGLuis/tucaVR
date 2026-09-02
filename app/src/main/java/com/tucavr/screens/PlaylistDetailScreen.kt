package com.tucavr.screens

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.tucavr.R
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidIconButton
import com.tucavr.designsystem.VoidListRow
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme
import com.tucavr.history.formatDurationMs
import com.tucavr.playlist.Playlist
import com.tucavr.playlist.PlaylistDao
import com.tucavr.playlist.PlaylistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T9.2: Tela de detalhe da playlist.
 *
 * Exibe a lista de itens ordenados por posição ascendente, permite iniciar a reprodução
 * ("Reproduzir Tudo" ou toque no item), reordenar posições com botões ↑ e ↓, remover itens
 * e excluir a playlist por completo.
 */
class PlaylistDetailScreen(
    private val context: Context,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val playlistDao: PlaylistDao,
    private val onPlayPlaylist: (playlist: Playlist, items: List<PlaylistItem>, startIndex: Int) -> Unit,
    private val onBack: () -> Unit
) {

    private var currentPlaylist: Playlist? = null
    private var currentItems: List<PlaylistItem> = emptyList()

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: View
    private lateinit var btnPlayAll: VoidButton

    fun render(playlistId: String) {
        val root = VoidPanelChrome.newRoot(context)

        scope.launch {
            val playlist = withContext(Dispatchers.IO) {
                playlistDao.getPlaylistById(playlistId)
            }
            if (playlist == null) {
                onBack()
                return@launch
            }
            currentPlaylist = playlist

            val subtitleText = if (playlist.itemCount == 1) {
                context.getString(R.string.playlists_item_count_singular, playlist.itemCount)
            } else {
                context.getString(R.string.playlists_item_count_plural, playlist.itemCount)
            }

            root.addView(
                VoidPanelChrome.buildHeader(
                    context,
                    title = playlist.name,
                    subtitle = subtitleText,
                    onBack = { onBack() }
                )
            )

            // Barra de Ações: "Reproduzir Tudo" + "Excluir Playlist"
            val actionsBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = VoidTheme.dpToPx(context, 16f) }
            }

            btnPlayAll = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                text = context.getString(R.string.playlists_play_all).trim()
                setIcon(R.drawable.ic_play_arrow)
                textSize = 18f
                setOnClickListener {
                    if (currentItems.isNotEmpty()) {
                        onPlayPlaylist(playlist, currentItems, 0)
                    }
                }
            }
            actionsBar.addView(btnPlayAll)

            val btnDeletePlaylist = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = context.getString(R.string.playlists_delete).trim()
                setIcon(R.drawable.icon_x)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = VoidTheme.dpToPx(context, 16f) }
                setOnClickListener {
                    showDeleteConfirmationDialog(playlist)
                }
            }
            actionsBar.addView(btnDeletePlaylist)
            root.addView(actionsBar)

            // Contêiner com rolagem dos itens
            listContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val scroller = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                addView(listContainer)
            }
            root.addView(scroller)

            emptyView = VoidText.body(
                context,
                context.getString(R.string.playlists_detail_empty),
                sizeSp = 18f,
                secondary = true
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, VoidTheme.dpToPx(context, 48f), 0, 0)
                visibility = View.GONE
            }
            root.addView(emptyView)

            host.showScreen(root)
            loadItems(playlistId)
        }
    }

    private fun loadItems(playlistId: String) {
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                playlistDao.getItemsForPlaylist(playlistId)
            }
            currentItems = items
            listContainer.removeAllViews()

            btnPlayAll.isEnabled = items.isNotEmpty()
            btnPlayAll.alpha = if (items.isNotEmpty()) 1.0f else 0.5f

            if (items.isEmpty()) {
                emptyView.visibility = View.VISIBLE
            } else {
                emptyView.visibility = View.GONE
                items.forEachIndexed { index, item ->
                    listContainer.addView(buildItemRow(item, index, items.size))
                }
            }
        }
    }

    private fun buildItemRow(item: PlaylistItem, index: Int, totalItems: Int): View {
        val rowContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = VoidTheme.dpToPx(context, 8f) }
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurface)
                cornerRadius = VoidTheme.dp(context, 10f)
            }
        }

        val row = VoidListRow(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val durationText = if (item.durationMs > 0) formatDurationMs(item.durationMs) else ""
            val metaInfo = listOfNotNull(
                durationText.ifEmpty { null },
                item.sourceType
            ).joinToString(" · ")

            bind(
                title = "${index + 1}. ${item.title}",
                meta = metaInfo,
                showThumbnailSlot = false,
                iconResId = R.drawable.ic_movie
            )
            setOnClickListener {
                currentPlaylist?.let { pl ->
                    onPlayPlaylist(pl, currentItems, index)
                }
            }
        }
        rowContainer.addView(row)

        // Botão Mover para Cima ↑
        val btnUp = VoidIconButton(
            context,
            R.drawable.ic_arrow_up,
            VoidButtonStyle.SECONDARY,
            isCircular = true
        ).apply {
            val s = VoidTheme.dpToPx(context, 38f)
            layoutParams = LinearLayout.LayoutParams(s, s).also {
                it.marginEnd = VoidTheme.dpToPx(context, 6f)
            }
            isEnabled = index > 0
            alpha = if (index > 0) 1.0f else 0.3f
            setOnClickListener {
                if (index > 0) {
                    val prevItem = currentItems[index - 1]
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            playlistDao.swapItemPositions(item, prevItem)
                        }
                        loadItems(item.playlistId)
                    }
                }
            }
        }
        rowContainer.addView(btnUp)

        // Botão Mover para Baixo ↓
        val btnDown = VoidIconButton(
            context,
            R.drawable.ic_arrow_up,
            VoidButtonStyle.SECONDARY,
            isCircular = true
        ).apply {
            rotation = 180f
            val s = VoidTheme.dpToPx(context, 38f)
            layoutParams = LinearLayout.LayoutParams(s, s).also {
                it.marginEnd = VoidTheme.dpToPx(context, 8f)
            }
            isEnabled = index < totalItems - 1
            alpha = if (index < totalItems - 1) 1.0f else 0.3f
            setOnClickListener {
                if (index < totalItems - 1) {
                    val nextItem = currentItems[index + 1]
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            playlistDao.swapItemPositions(item, nextItem)
                        }
                        loadItems(item.playlistId)
                    }
                }
            }
        }
        rowContainer.addView(btnDown)

        // Botão Remover Item da Playlist
        val btnDelete = VoidIconButton(
            context,
            R.drawable.icon_x,
            VoidButtonStyle.SECONDARY,
            isCircular = true
        ).apply {
            val s = VoidTheme.dpToPx(context, 38f)
            layoutParams = LinearLayout.LayoutParams(s, s).also {
                it.marginEnd = VoidTheme.dpToPx(context, 12f)
            }
            setOnClickListener {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        playlistDao.removeItemAndReorder(item)
                    }
                    loadItems(item.playlistId)
                }
            }
        }
        rowContainer.addView(btnDelete)

        return rowContainer
    }

    private fun showDeleteConfirmationDialog(playlist: Playlist) {
        val overlay = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            isClickable = true
        }

        val dialogCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val w = VoidTheme.dpToPx(context, 500f)
            layoutParams = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurface)
                cornerRadius = VoidTheme.dp(context, 16f)
                setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
            }
            val pad = VoidTheme.dpToPx(context, 24f)
            setPadding(pad, pad, pad, pad)
            isClickable = true
        }

        val titleView = VoidText.title(context, context.getString(R.string.playlists_delete), sizeSp = 22f)
        val msgView = VoidText.body(context, context.getString(R.string.playlists_delete_confirm), sizeSp = 16f).apply {
            setPadding(0, VoidTheme.dpToPx(context, 16f), 0, VoidTheme.dpToPx(context, 24f))
        }
        dialogCard.addView(titleView)
        dialogCard.addView(msgView)

        val buttonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnCancel = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.playlists_cancel_btn)
            setOnClickListener { host.hideOverlay(overlay) }
        }

        val btnConfirm = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.playlists_delete)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = VoidTheme.dpToPx(context, 12f) }
            setOnClickListener {
                host.hideOverlay(overlay)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        playlistDao.deletePlaylist(playlist.id)
                    }
                    onBack()
                }
            }
        }

        buttonsRow.addView(btnCancel)
        buttonsRow.addView(btnConfirm)
        dialogCard.addView(buttonsRow)

        overlay.addView(dialogCard)
        host.showOverlay(overlay)
    }
}
