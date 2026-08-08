package com.vrplayer

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidListRow
import com.vrplayer.designsystem.VoidPanelChrome
import com.vrplayer.designsystem.VoidText
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.filebrowser.DirectoryLister
import com.vrplayer.filebrowser.DirectoryNavigator
import com.vrplayer.filebrowser.MediaEntry
import com.vrplayer.filebrowser.MediaType
import com.vrplayer.filebrowser.SortBy
import com.vrplayer.filebrowser.ThumbnailGenerator
import com.vrplayer.filebrowser.sortMediaEntries
import com.vrplayer.navigation.AppNavigator
import com.vrplayer.navigation.Destination
import com.vrplayer.navigation.PlaybackSource
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Fase 1 do redesign "Void" (ver `com.vrplayer.navigation.Destination` pro
 * contexto completo da consolidacao): este e o painel esquerdo, aberto/
 * fechado pelo botao fisico B/Y (ver `vr_player_app.cpp`, T4.4) — hoje
 * continua sendo seu proprio quad/VirtualDisplay independente (Fase 2,
 * nativa, ainda nao feita), mas por dentro ele agora hospeda o fluxo
 * completo Home -> Arquivos locais -> Player, em vez de abrir direto na
 * listagem de arquivos como antes.
 *
 * A navegacao entre essas telas e conduzida por [AppNavigator] (Kotlin puro,
 * testavel sem Android — ver esse arquivo). O botao fisico B/Y continua, por
 * enquanto, so alternando a VISIBILIDADE do quad inteiro (comportamento
 * nativo inalterado); o botao "Voltar" Void desenhado dentro do painel e
 * quem efetivamente anda pelo back-stack do [AppNavigator]. Unificar as duas
 * coisas (B/Y = sempre "voltar" de verdade) e trabalho da Fase 2.
 */
class VRPresentation(
    outerContext: Context,
    display: Display,
    // Mesma armadilha documentada em VRControlsPresentation/NetworkPresentation:
    // `context` (herdado de Presentation/Dialog) NAO e a Activity real, e um
    // ContextThemeWrapper derivado do display-context. Guardamos a Activity
    // de verdade para poder chamar nativeX()/playFile()/playUrl() etc.
    private val activity: VRActivity
) : Presentation(outerContext, display) {

    private val appNav = AppNavigator()

    // Estado de navegacao dentro dos arquivos locais (T5): reaproveitado tal
    // e qual existia antes — so quem desenha a UI em cima dele mudou. O
    // back-stack "de app" (Home/Player/etc, no AppNavigator acima) e
    // deliberadamente um mecanismo separado deste (drill-down de diretorio):
    // ver `handleBack()`.
    private val dirNavigator = DirectoryNavigator(android.os.Environment.getExternalStorageDirectory())

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var screenHost: FrameLayout
    private var localFileAdapter: FileAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenHost = FrameLayout(context).apply {
            setBackgroundColor(VoidTheme.colorBackground)
        }
        setContentView(screenHost)
        render()
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    /** Chamado por `VRActivity.onResume()` — recarrega a listagem atual, caso
     * o usuario tenha adicionado/removido arquivos enquanto o app estava em
     * background. No-op em qualquer tela que nao seja a listagem local. */
    fun loadFiles() {
        if (appNav.current is Destination.LocalFiles) {
            loadLocalFiles()
        }
    }

    // ==================== Maquina de telas ====================

    private fun render() {
        when (val destination = appNav.current) {
            is Destination.Home -> renderHome()
            is Destination.LocalFiles -> renderLocalFiles()
            is Destination.NetworkHome -> renderNetworkPlaceholder()
            is Destination.NetworkFiles -> renderNetworkPlaceholder()
            is Destination.Player -> renderPlayer(destination.source)
        }
    }

    private fun navigateTo(destination: Destination) {
        appNav.navigateTo(destination)
        render()
    }

    /**
     * Unico ponto de "Voltar" do painel. Trata a listagem local como um caso
     * especial: se ainda da pra subir de diretorio (`dirNavigator`), sobe um
     * nivel de diretorio SEM sair da tela LocalFiles nem tocar no back-stack
     * do [AppNavigator]; so quando ja estamos na raiz que "Voltar" volta pra
     * tela anterior (Home). Isso reproduz exatamente a UX de qualquer
     * explorador de arquivos ("voltar" sobe pasta por pasta antes de sair do
     * app-de-arquivos), enquanto ainda garante que apertar "Voltar" o
     * suficiente sempre termina no Home, como pedido.
     */
    private fun handleBack() {
        if (appNav.current is Destination.LocalFiles && dirNavigator.canGoBack()) {
            dirNavigator.goBack()
            renderLocalFiles()
            return
        }
        if (appNav.back()) {
            render()
        }
    }

    private fun showScreen(view: View) {
        screenHost.removeAllViews()
        screenHost.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    // ==================== HOME ====================

    private fun renderHome() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(VoidPanelChrome.buildHeader(context, title = "VR Player"))

        val bigButtonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 20f) }

        val btnLocal = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = "📁  Arquivos locais"
            textSize = 24f
            setOnClickListener {
                navigateTo(Destination.LocalFiles(dirNavigator.currentPath.absolutePath))
            }
        }
        val btnNetwork = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = "🌐  Rede"
            textSize = 24f
            setOnClickListener { navigateTo(Destination.NetworkHome) }
        }
        // T9 (roadmap, ver docs/phases): "Continuar assistindo" depende de um
        // historico de reproducao que ainda nao existe. Placeholder
        // DESABILITADO (nao escondido) de proposito: sinaliza pro usuario que
        // a funcionalidade existe/esta a caminho, em vez de sumir sem
        // explicacao.
        val btnContinueWatching = VoidButton(context, VoidButtonStyle.DISABLED).apply {
            text = "▶  Continuar assistindo"
            textSize = 20f
        }

        root.addView(btnLocal, bigButtonParams)
        root.addView(btnNetwork, bigButtonParams)
        root.addView(btnContinueWatching, bigButtonParams)

        showScreen(root)
    }

    // ==================== ARQUIVOS LOCAIS (T5) ====================

    private fun renderLocalFiles() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = "Arquivos locais",
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
        val adapter = FileAdapter(
            context = context,
            scope = scope,
            onUpClick = { handleBack() },
            onDirectoryClick = { dir ->
                dirNavigator.enter(dir)
                renderLocalFiles()
            },
            onVideoClick = { entry -> playLocalVideo(entry) }
        )
        localFileAdapter = adapter
        recycler.adapter = adapter
        root.addView(recycler)

        showScreen(root)
        loadLocalFiles()
    }

    private fun loadLocalFiles() {
        val adapter = localFileAdapter ?: return
        val dir = dirNavigator.currentPath
        val showUp = dirNavigator.canGoBack()
        scope.launch {
            val entries = DirectoryLister.listMedia(dir)
                .filter { it.type == MediaType.DIRECTORY || it.type == MediaType.VIDEO }
            val sorted = sortMediaEntries(entries, SortBy.NAME)
            adapter.submit(sorted, showUp)
        }
    }

    private fun playLocalVideo(entry: MediaEntry) {
        activity.playFile(entry.path)
        navigateTo(Destination.Player(PlaybackSource.LocalFile(entry.path)))
    }

    // ==================== REDE (placeholder, ver TODO) ====================

    /**
     * TODO(Fase 2 - nativa): a UI real de Rede (abas URL/SMB, formularios,
     * navegacao de compartilhamento) continua vivendo inteira em
     * `NetworkPresentation`, seu proprio quad/VirtualDisplay, aberto pelo
     * botao Menu do controller esquerdo — NAO duplicada aqui.
     *
     * Por que: `NetworkPresentation` e bem maior/mais statefull que o file
     * browser (EncryptedSharedPreferences de credenciais, formulario de
     * servidor SMB, navegacao de diretorio remoto, probe HTTP) e mover essa
     * logica pra dentro deste painel exigiria ou (a) duplicar estado entre
     * dois quads simultaneos, ou (b) already fazer a fusao dos quads nativos
     * — que e exatamente o trabalho reservado pra Fase 2 (`vr_player_app.cpp`,
     * fora do escopo desta mudanca, exige validacao em headset fisico).
     * Antecipar so a metade (mover a UI sem mover os quads) criaria dois
     * lugares onde o usuario pode abrir "Rede" com estado potencialmente
     * dessincronizado — pior do que a tela abaixo, que e honesta sobre a
     * limitacao atual.
     *
     * Esta tela serve de placeholder navegavel (Home -> Rede existe no fluxo,
     * como pedido) e instrui o usuario a usar o botao Menu por enquanto.
     */
    private fun renderNetworkPlaceholder() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = "Rede",
                onBack = { handleBack() }
            )
        )
        root.addView(VoidText.body(
            context,
            "A navegacao de rede (URL / SMB) ainda abre no seu proprio painel, " +
                "pelo botao Menu do controle esquerdo.",
            sizeSp = 18f,
            secondary = true
        ).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 12f))
        })
        root.addView(VoidText.mono(
            context,
            "A fusao dos paineis em um unico fluxo acontece na Fase 2 (nativa).",
            sizeSp = 14f
        ))
        showScreen(root)
    }

    // ==================== PLAYER (estado apos selecionar midia) ====================

    private fun renderPlayer(source: PlaybackSource) {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = "Reproduzindo",
                onBack = { handleBack() }
            )
        )
        val label = when (source) {
            is PlaybackSource.LocalFile -> source.path
            is PlaybackSource.Http -> source.url
            is PlaybackSource.Smb -> "${source.server.name}/${source.path}"
        }
        root.addView(VoidText.mono(context, label, sizeSp = 16f))
        root.addView(VoidText.body(
            context,
            "Os controles de reproducao (play/pause/seek/volume) aparecem ao apontar para a tela de video.",
            sizeSp = 16f,
            secondary = true
        ).apply {
            setPadding(0, VoidTheme.dpToPx(context, 16f), 0, 0)
        })
        showScreen(root)
    }

    // Adapter roda em cima de MediaEntry (T5.3) em vez de java.io.File cru, e usa o
    // ThumbnailGenerator (T5.4) de forma preguicosa/assincrona por item visivel — nunca
    // gera thumbnails para a pasta inteira de uma vez (travaria a UI, ver cuidados do T5).
    private class FileAdapter(
        private val context: Context,
        private val scope: CoroutineScope,
        private val onUpClick: () -> Unit,
        private val onDirectoryClick: (File) -> Unit,
        private val onVideoClick: (MediaEntry) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

        private sealed class Row {
            object Up : Row()
            data class Item(val entry: MediaEntry) : Row()
        }

        private var rows: List<Row> = emptyList()

        fun submit(entries: List<MediaEntry>, showUp: Boolean) {
            rows = buildList {
                if (showUp) add(Row.Up)
                entries.forEach { add(Row.Item(it)) }
            }
            notifyDataSetChanged()
        }

        inner class ViewHolder(val row: VoidListRow) : RecyclerView.ViewHolder(row) {
            var thumbnailJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val row = VoidListRow(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = VoidTheme.dpToPx(parent.context, 8f) }
            }
            return ViewHolder(row)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.thumbnailJob?.cancel()
            holder.row.thumbnail.setImageBitmap(null)

            when (val row = rows[position]) {
                is Row.Up -> {
                    holder.row.bind("⬆ Subir um nivel", showThumbnailSlot = false)
                    holder.itemView.setOnClickListener { onUpClick() }
                }
                is Row.Item -> {
                    val entry = row.entry
                    val icon = if (entry.type == MediaType.DIRECTORY) "📁" else "🎬"
                    val meta = if (entry.type == MediaType.VIDEO) formatSize(entry.sizeBytes) else null
                    holder.row.bind("$icon ${entry.name}", meta = meta, showThumbnailSlot = entry.type == MediaType.VIDEO)
                    holder.itemView.setOnClickListener {
                        if (entry.type == MediaType.DIRECTORY) {
                            onDirectoryClick(File(entry.path))
                        } else {
                            onVideoClick(entry)
                        }
                    }
                    if (entry.type == MediaType.VIDEO) {
                        holder.thumbnailJob = scope.launch {
                            val bitmap = ThumbnailGenerator.getThumbnail(context, entry)
                            if (bitmap != null && holder.adapterPosition == position) {
                                holder.row.thumbnail.setImageBitmap(bitmap)
                                holder.row.thumbnail.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }

        override fun getItemCount() = rows.size

        private fun formatSize(bytes: Long): String {
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
        }
    }
}
