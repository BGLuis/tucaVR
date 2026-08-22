package com.vrplayer.screens

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vrplayer.R
import com.vrplayer.designsystem.VoidPanelChrome
import com.vrplayer.filebrowser.DirectoryLister
import com.vrplayer.filebrowser.DirectoryNavigator
import com.vrplayer.filebrowser.MediaEntry
import com.vrplayer.filebrowser.MediaType
import com.vrplayer.filebrowser.SortBy
import com.vrplayer.filebrowser.sortMediaEntries
import com.vrplayer.navigation.Destination
import com.vrplayer.navigation.PlaybackSource
import com.vrplayer.screens.adapters.FileAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Tela de navegação de arquivos locais (T5) e tela de detalhe de arquivo.
 *
 * Gerencia o drill-down de diretório via [DirectoryNavigator]: subir e
 * descer pastas NÃO empilha novos [Destination] no AppNavigator; só muda o
 * estado interno aqui. "Voltar" na raiz do storage é que volta para o Home
 * (via [onBack]).
 */
class LocalFilesScreen(
    private val context: Context,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val dirNavigator: DirectoryNavigator,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit,
    private val onPlayLocalVideo: (MediaEntry) -> Unit
) {

    private var adapter: FileAdapter? = null

    // ---- Listagem de diretório ----

    fun renderLocalFiles() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = context.getString(R.string.browser_title_local_files),
                subtitle = dirNavigator.currentPath.absolutePath,
                onBack = { onBack() }
            )
        )

        val recycler = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            layoutManager = LinearLayoutManager(context)
        }

        val fileAdapter = FileAdapter(
            context = context,
            scope = scope,
            onUpClick = { onBack() },
            onDirectoryClick = { dir ->
                dirNavigator.enter(dir)
                renderLocalFiles()
            },
            // Single-click → detalhe; double-click → toca direto.
            onVideoClick = { entry ->
                onNavigate(
                    Destination.FileDetail(
                        source = PlaybackSource.LocalFile(entry.path, entry.sizeBytes),
                        displayName = entry.name,
                        sizeBytes = entry.sizeBytes,
                        lastModified = entry.lastModified
                    )
                )
            },
            onVideoDoubleClick = { entry -> onPlayLocalVideo(entry) }
        )
        adapter = fileAdapter
        recycler.adapter = fileAdapter
        root.addView(recycler)

        host.showScreen(root)
        loadFiles()
    }

    /** Recarrega a listagem atual — chamado por VRActivity.onResume(). */
    fun loadFiles() {
        val currentAdapter = adapter ?: return
        val dir = dirNavigator.currentPath
        val showUp = dirNavigator.canGoBack()
        scope.launch {
            val entries = DirectoryLister.listMedia(dir)
                .filter { it.type == MediaType.DIRECTORY || it.type == MediaType.VIDEO }
            val sorted = sortMediaEntries(entries, SortBy.NAME)
            currentAdapter.submit(sorted, showUp)
        }
    }

    /**
     * Sobe um nível no diretório sem sair da tela; quando na raiz, delega
     * para [onBack] que volta ao Home via AppNavigator.
     */
    fun handleBack(): Boolean {
        if (dirNavigator.canGoBack()) {
            dirNavigator.goBack()
            renderLocalFiles()
            return true
        }
        return false
    }

}
