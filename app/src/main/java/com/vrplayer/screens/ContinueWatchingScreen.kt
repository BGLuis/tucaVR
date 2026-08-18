package com.vrplayer.screens

import android.content.Context
import android.view.View
import android.view.ViewGroup
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
import com.vrplayer.history.HistorySourceType
import com.vrplayer.history.PlaybackHistory
import com.vrplayer.navigation.Destination
import com.vrplayer.navigation.PlaybackSource
import com.vrplayer.network.FtpCredentialStore
import com.vrplayer.network.SftpCredentialStore
import com.vrplayer.network.SmbCredentialStore
import com.vrplayer.screens.adapters.HistoryAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Tela "Continuar Assistindo" (T9.4) e lógica de retomada a partir do histórico.
 *
 * Gerencia a consulta ao [historyTracker], a exibição da lista e a resolução
 * de servidores de rede a partir do JSON de [PlaybackHistory.serverInfo].
 *
 * Retomada via esta tela é direta (sem prompt "Retomar de XX:XX?") pois a
 * intenção do usuário já é explícita — ele clicou numa entrada de histórico.
 */
class ContinueWatchingScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val smbCredentials: SmbCredentialStore,
    private val ftpCredentials: FtpCredentialStore,
    private val sftpCredentials: SftpCredentialStore,
    private val onNavigate: (Destination) -> Unit,
    private val onBack: () -> Unit
) {

    fun render() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = context.getString(R.string.history_continue_watching_title),
                onBack = { onBack() }
            )
        )

        val recycler = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(context)
        }
        val emptyText = VoidText.body(
            context, context.getString(R.string.history_empty), sizeSp = 16f, secondary = true
        ).apply { visibility = View.GONE }

        lateinit var adapter: HistoryAdapter

        fun refresh() {
            scope.launch {
                val items = activity.historyTracker.listRecent()
                adapter.submit(items)
                recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        adapter = HistoryAdapter(
            onItemClick    = { entry -> resumeFromHistory(entry) },
            onRemoveClick  = { entry ->
                scope.launch {
                    activity.historyTracker.delete(entry.historyKey)
                    refresh()
                }
            }
        )
        recycler.adapter = adapter

        root.addView(recycler)
        root.addView(emptyText)
        host.showScreen(root)
        refresh()
    }

    /**
     * Retoma a reprodução diretamente na posição salva — sem prompt
     * intermediário, pois o clique aqui já é a confirmação de intenção.
     * Servidores removidos desde a última sessão geram falha silenciosa
     * (não há credencial para reconectar — "não precisa ser sofisticado",
     * escopo T9.3/T9.4).
     */
    private fun resumeFromHistory(entry: PlaybackHistory) {
        when (entry.sourceType) {
            HistorySourceType.LOCAL -> {
                val source = PlaybackSource.LocalFile(entry.mediaPath)
                activity.playFile(entry.mediaPath, resumeAtMs = entry.positionMs)
                onNavigate(Destination.Player(source))
            }
            HistorySourceType.HTTP -> {
                val source = PlaybackSource.Http(entry.mediaPath)
                activity.playUrl(entry.mediaPath, resumeAtMs = entry.positionMs)
                onNavigate(Destination.Player(source))
            }
            HistorySourceType.SMB -> {
                val server = resolveServer(entry.serverInfo) { id ->
                    smbCredentials.list().find { it.id == id }
                } ?: return
                val source = PlaybackSource.Smb(server, entry.mediaPath)
                activity.playSmb(server, entry.mediaPath, resumeAtMs = entry.positionMs)
                onNavigate(Destination.Player(source))
            }
            HistorySourceType.FTP -> {
                val server = resolveServer(entry.serverInfo) { id ->
                    ftpCredentials.list().find { it.id == id }
                } ?: return
                val source = PlaybackSource.Ftp(server, entry.mediaPath)
                activity.playFtp(server, entry.mediaPath, resumeAtMs = entry.positionMs)
                onNavigate(Destination.Player(source))
            }
            HistorySourceType.SFTP -> {
                val server = resolveServer(entry.serverInfo) { id ->
                    sftpCredentials.list().find { it.id == id }
                } ?: return
                val source = PlaybackSource.Sftp(server, entry.mediaPath)
                activity.playSftp(server, entry.mediaPath, resumeAtMs = entry.positionMs)
                onNavigate(Destination.Player(source))
            }
        }
    }

    /**
     * Resolve um servidor salvo a partir do JSON de [PlaybackHistory.serverInfo].
     * A credencial nunca foi duplicada no histórico — apenas a referência ao
     * servidor salvo no respectivo CredentialStore.
     */
    private fun <T> resolveServer(serverInfoJson: String?, finder: (String) -> T?): T? {
        if (serverInfoJson == null) return null
        return try {
            val serverId = JSONObject(serverInfoJson).getString("serverId")
            finder(serverId)
        } catch (e: Exception) {
            null
        }
    }
}
