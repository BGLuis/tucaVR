package com.vrplayer.screens

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vrplayer.R
import com.vrplayer.VRActivity
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidPanelChrome
import com.vrplayer.designsystem.VoidText
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.filebrowser.DirectoryLister
import com.vrplayer.filebrowser.DirectoryNavigator
import com.vrplayer.filebrowser.MediaEntry
import com.vrplayer.filebrowser.MediaType
import com.vrplayer.filebrowser.SortBy
import com.vrplayer.filebrowser.ThumbnailGenerator
import com.vrplayer.filebrowser.VideoMetadataReader
import com.vrplayer.filebrowser.sortMediaEntries
import com.vrplayer.history.formatDurationMs
import com.vrplayer.navigation.Destination
import com.vrplayer.navigation.PlaybackSource
import com.vrplayer.screens.adapters.FileAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    private val activity: VRActivity,
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
                onBack = { handleBack() }
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
            onUpClick = { handleBack() },
            onDirectoryClick = { dir ->
                dirNavigator.enter(dir)
                renderLocalFiles()
            },
            // Single-click → detalhe; double-click → toca direto.
            onVideoClick = { entry -> onNavigate(Destination.LocalFileDetail(entry)) },
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

    // ---- Detalhe de arquivo local ----

    /**
     * Tela intermediária entre a listagem e o Player: thumbnail grande +
     * metadados (tamanho, data, duração, resolução, caminho) + botão Reproduzir.
     *
     * Thumbnail e metadados de vídeo chegam de forma assíncrona — são
     * adicionados à tela já visível (MediaMetadataRetriever não pode rodar
     * na main thread).
     */
    fun renderLocalFileDetail(entry: MediaEntry) {
        val root = VoidPanelChrome.newRoot(context)
        // Voltar do detalhe sempre retorna para a listagem da pasta atual,
        // não usa onBack() global (que faria appNav.back() e poria o AppNavigator
        // em estado incorreto — o Destination.LocalFileDetail ainda está na pilha).
        root.addView(VoidPanelChrome.buildHeader(context, title = entry.name, onBack = { onBack() }))

        val thumbnailView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, VoidTheme.dpToPx(context, 200f)
            ).apply { bottomMargin = VoidTheme.dpToPx(context, 20f) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(VoidTheme.colorSurfaceAlt)
                cornerRadius = VoidTheme.dp(context, 10f)
            }
            clipToOutline = true
        }
        root.addView(thumbnailView)

        val metaContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(metaContainer)

        fun addMetaRow(label: String, value: String) {
            metaContainer.addView(
                VoidText.body(
                    context,
                    context.getString(R.string.file_detail_row_format, label, value),
                    sizeSp = 16f,
                    secondary = true
                ).apply { setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f)) }
            )
        }

        addMetaRow(context.getString(R.string.file_detail_label_size), formatFileSize(context, entry.sizeBytes))
        addMetaRow(context.getString(R.string.file_detail_label_modified), formatModifiedDate(entry.lastModified))
        addMetaRow(context.getString(R.string.file_detail_label_path), entry.path)

        val btnPlay = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.file_detail_btn_play)
            textSize = 22f
            setOnClickListener { onPlayLocalVideo(entry) }
        }
        root.addView(btnPlay, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 20f) })

        host.showScreen(root)

        // Thumbnail e metadados de vídeo carregados em background.
        scope.launch {
            val bitmap = ThumbnailGenerator.getThumbnail(context, entry)
            if (bitmap != null) thumbnailView.setImageBitmap(bitmap)

            val metadata = VideoMetadataReader.read(entry.path)
            if (metadata != null) {
                if (metadata.durationMs > 0L) {
                    addMetaRow(context.getString(R.string.file_detail_label_duration), formatDurationMs(metadata.durationMs))
                }
                if (metadata.width > 0 && metadata.height > 0) {
                    addMetaRow(context.getString(R.string.file_detail_label_resolution), "${metadata.width}×${metadata.height}")
                }
            }
        }
    }
}
