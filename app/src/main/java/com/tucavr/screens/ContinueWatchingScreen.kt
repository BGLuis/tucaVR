package com.tucavr.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.designsystem.VoidFilterChip
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme
import com.tucavr.history.HistorySourceType
import com.tucavr.history.PlaybackHistory
import com.tucavr.history.isResumable
import com.tucavr.navigation.Destination
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.FtpCredentialStore
import com.tucavr.network.SftpCredentialStore
import com.tucavr.network.SmbCredentialStore
import com.tucavr.screens.adapters.HistoryAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Tela "Continuar Assistindo" (T9.4) e lógica de retomada a partir do histórico.
 * Inclui filtro rápido entre mídias em andamento ("Não finalizados", T12.4) e histórico completo.
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

    private var filterOnlyResumable: Boolean = true

    fun render() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = context.getString(R.string.history_continue_watching_title),
                onBack = { onBack() }
            )
        )

        // Filtro T12.4: Não-finalizados vs Todos
        val filterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = VoidTheme.dpToPx(context, 12f)
            }
        }

        lateinit var chipResumable: VoidFilterChip
        lateinit var chipAll: VoidFilterChip

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
                val allItems = activity.historyTracker.listRecent()
                val items = if (filterOnlyResumable) {
                    allItems.filter { it.isResumable() }
                } else {
                    allItems
                }
                adapter.submit(items)
                recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        chipResumable = VoidFilterChip(context, context.getString(R.string.history_filter_resumable), isSelectedChip = filterOnlyResumable).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = VoidTheme.dpToPx(context, 8f)
            }
            setOnClickListener {
                filterOnlyResumable = true
                chipResumable.setSelectedState(true)
                chipAll.setSelectedState(false)
                refresh()
            }
        }
        filterRow.addView(chipResumable)

        chipAll = VoidFilterChip(context, context.getString(R.string.history_filter_all), isSelectedChip = !filterOnlyResumable).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                filterOnlyResumable = false
                chipResumable.setSelectedState(false)
                chipAll.setSelectedState(true)
                refresh()
            }
        }
        filterRow.addView(chipAll)

        root.addView(filterRow)

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
     * Retoma a reprodução diretamente na posição salva.
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
            HistorySourceType.NFS -> {
                val server = resolveServer(entry.serverInfo) { id ->
                    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            com.tucavr.history.AppDatabase.getInstance(context).savedServerDao().getById(id)
                        } catch (e: Exception) {
                            null
                        }
                    }
                } ?: return
                val source = PlaybackSource.Nfs(server, entry.mediaPath)
                activity.playNfs(server, entry.mediaPath, resumeAtMs = entry.positionMs)
                onNavigate(Destination.Player(source))
            }
            HistorySourceType.DLNA -> {
                val server = resolveServer(entry.serverInfo) { id ->
                    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            com.tucavr.history.AppDatabase.getInstance(context).savedServerDao().getById(id)
                        } catch (e: Exception) {
                            null
                        }
                    }
                } ?: com.tucavr.network.SavedServer(name = entry.title, protocol = com.tucavr.network.ServerProtocol.DLNA, host = "", port = 0, path = "")
                val source = PlaybackSource.Dlna(server, entry.title, entry.mediaPath)
                activity.playDlna(server, entry.title, entry.mediaPath, resumeAtMs = entry.positionMs)
                onNavigate(Destination.Player(source))
            }
        }
    }

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
