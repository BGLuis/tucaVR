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
import com.tucavr.designsystem.VoidFieldAction
import com.tucavr.designsystem.VoidIconButton
import com.tucavr.designsystem.VoidListRow
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTextField
import com.tucavr.designsystem.VoidTheme
import com.tucavr.navigation.Destination
import com.tucavr.playlist.Playlist
import com.tucavr.playlist.PlaylistDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * T9.2: Tela de gerenciamento de playlists (CRUD na UI).
 *
 * Permite listar todas as playlists criadas, criar novas playlists através de
 * modal overlay com [VoidTextField], navegar para a visualização detalhada de cada
 * playlist e excluir playlists.
 */
class PlaylistsScreen(
    private val context: Context,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val playlistDao: PlaylistDao,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit
) {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: View

    fun render() {
        val root = VoidPanelChrome.newRoot(context)

        // Cabeçalho com botão Voltar
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = context.getString(R.string.playlists_title),
                onBack = { onBack() }
            )
        )

        // Barra de Ações: Botão "+ Nova Playlist"
        val actionsBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = VoidTheme.dpToPx(context, 16f) }
        }

        val btnNewPlaylist = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.playlists_new).trim()
            setIcon(R.drawable.ic_movie)
            textSize = 18f
            setOnClickListener { showCreatePlaylistDialog() }
        }
        actionsBar.addView(btnNewPlaylist)
        root.addView(actionsBar)

        // Contêiner de rolagem com a lista
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

        // View de lista vazia
        emptyView = VoidText.body(
            context,
            context.getString(R.string.playlists_empty),
            sizeSp = 18f,
            secondary = true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, VoidTheme.dpToPx(context, 48f), 0, 0)
            visibility = View.GONE
        }
        root.addView(emptyView)

        host.showScreen(root)
        loadPlaylists()
    }

    private fun loadPlaylists() {
        scope.launch {
            val playlists = withContext(Dispatchers.IO) {
                playlistDao.getAllPlaylists()
            }
            listContainer.removeAllViews()

            if (playlists.isEmpty()) {
                emptyView.visibility = View.VISIBLE
            } else {
                emptyView.visibility = View.GONE
                playlists.forEach { playlist ->
                    listContainer.addView(buildPlaylistRow(playlist))
                }
            }
        }
    }

    private fun buildPlaylistRow(playlist: Playlist): View {
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
            val itemCountText = if (playlist.itemCount == 1) {
                context.getString(R.string.playlists_item_count_singular, playlist.itemCount)
            } else {
                context.getString(R.string.playlists_item_count_plural, playlist.itemCount)
            }
            bind(
                title = playlist.name,
                meta = itemCountText,
                showThumbnailSlot = false,
                iconResId = R.drawable.ic_view_list
            )
            setOnClickListener {
                onNavigate(Destination.PlaylistDetail(playlist.id))
            }
        }

        val btnDelete = VoidIconButton(
            context,
            R.drawable.icon_x,
            VoidButtonStyle.SECONDARY,
            isCircular = true
        ).apply {
            val s = VoidTheme.dpToPx(context, 40f)
            layoutParams = LinearLayout.LayoutParams(s, s).also {
                it.marginEnd = VoidTheme.dpToPx(context, 12f)
            }
            setOnClickListener {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        playlistDao.deletePlaylist(playlist.id)
                    }
                    loadPlaylists()
                }
            }
        }

        rowContainer.addView(row)
        rowContainer.addView(btnDelete)
        return rowContainer
    }

    /**
     * Exibe o diálogo overlay para criação de nova playlist com [VoidTextField].
     */
    private fun showCreatePlaylistDialog() {
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
            val w = VoidTheme.dpToPx(context, 520f)
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

        val titleView = VoidText.title(context, context.getString(R.string.playlists_create_dialog_title), sizeSp = 22f)
        dialogCard.addView(titleView)

        val inputField = VoidTextField(
            context = context,
            host = host,
            hint = context.getString(R.string.playlists_name_hint),
            label = context.getString(R.string.playlists_name_hint),
            actions = setOf(VoidFieldAction.CLEAR, VoidFieldAction.PASTE)
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.topMargin = VoidTheme.dpToPx(context, 16f)
                it.bottomMargin = VoidTheme.dpToPx(context, 24f)
            }
        }
        dialogCard.addView(inputField)

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

        val btnCreate = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.playlists_create_btn)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = VoidTheme.dpToPx(context, 12f) }
            setOnClickListener {
                val name = inputField.getText().trim()
                if (name.isNotEmpty()) {
                    host.hideOverlay(overlay)
                    createPlaylist(name)
                }
            }
        }

        buttonsRow.addView(btnCancel)
        buttonsRow.addView(btnCreate)
        dialogCard.addView(buttonsRow)

        overlay.addView(dialogCard)
        host.showOverlay(overlay)
        inputField.editText.requestFocus()
    }

    private fun createPlaylist(name: String) {
        scope.launch {
            val newPlaylist = Playlist(
                id = UUID.randomUUID().toString(),
                name = name,
                createdAt = System.currentTimeMillis(),
                itemCount = 0
            )
            withContext(Dispatchers.IO) {
                playlistDao.insertPlaylist(newPlaylist)
            }
            loadPlaylists()
        }
    }
}
