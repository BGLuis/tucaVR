package com.tucavr.history

import android.content.Context
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.FtpCredentialStore
import com.tucavr.network.FtpServer
import com.tucavr.network.SavedServer
import com.tucavr.network.SftpCredentialStore
import com.tucavr.network.SftpServer
import com.tucavr.network.SmbCredentialStore
import com.tucavr.network.SmbServer
import org.json.JSONObject

object HistorySourceResolver {
    /** Network entries only; null when the saved server no longer exists (credentials live in its store). */
    suspend fun resolveNetworkSource(
        context: Context,
        entry: PlaybackHistory,
    ): PlaybackSource? =
        resolveNetworkSourceInternal(
            entry = entry,
            findSmb = { id -> SmbCredentialStore(context).list().find { it.id == id } },
            findFtp = { id -> FtpCredentialStore(context).list().find { it.id == id } },
            findSftp = { id -> SftpCredentialStore(context).list().find { it.id == id } },
            findNfs = { id -> AppDatabase.getInstance(context).savedServerDao().getById(id) },
            findWebdav = { id -> AppDatabase.getInstance(context).savedServerDao().getById(id) },
        )

    internal suspend fun resolveNetworkSourceInternal(
        entry: PlaybackHistory,
        findSmb: suspend (String) -> SmbServer?,
        findFtp: suspend (String) -> FtpServer?,
        findSftp: suspend (String) -> SftpServer?,
        findNfs: suspend (String) -> SavedServer?,
        findWebdav: suspend (String) -> SavedServer?,
    ): PlaybackSource? {
        when (entry.sourceType) {
            HistorySourceType.SMB,
            HistorySourceType.FTP,
            HistorySourceType.SFTP,
            HistorySourceType.NFS,
            HistorySourceType.WEBDAV,
            -> Unit
            HistorySourceType.LOCAL,
            HistorySourceType.HTTP,
            HistorySourceType.DLNA,
            -> return null
        }

        val serverId = extractServerId(entry.serverInfo) ?: return null
        val sizeBytes = entry.sizeBytesFromKey()
        return when (entry.sourceType) {
            HistorySourceType.SMB ->
                findSmb(serverId)?.let { PlaybackSource.Smb(it, entry.mediaPath, sizeBytes) }
            HistorySourceType.FTP ->
                findFtp(serverId)?.let { PlaybackSource.Ftp(it, entry.mediaPath, sizeBytes) }
            HistorySourceType.SFTP ->
                findSftp(serverId)?.let { PlaybackSource.Sftp(it, entry.mediaPath, sizeBytes) }
            HistorySourceType.NFS ->
                findNfs(serverId)?.let { PlaybackSource.Nfs(it, entry.mediaPath, sizeBytes) }
            HistorySourceType.WEBDAV ->
                findWebdav(serverId)?.let { PlaybackSource.Webdav(it, entry.mediaPath, sizeBytes) }
            HistorySourceType.LOCAL, HistorySourceType.HTTP, HistorySourceType.DLNA -> null
        }
    }

    internal fun extractServerId(serverInfo: String?): String? {
        if (serverInfo.isNullOrBlank()) return null
        return try {
            val json = JSONObject(serverInfo)
            if (json.isNull("serverId")) return null
            json.optString("serverId").trim().takeIf { it.isNotEmpty() && it != "null" }
        } catch (e: Exception) {
            null
        }
    }
}
