package com.tucavr.history

import android.content.Context
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.FtpCredentialStore
import com.tucavr.network.SftpCredentialStore
import com.tucavr.network.SmbCredentialStore
import org.json.JSONObject

object HistorySourceResolver {
    /** Network entries only; null when the saved server no longer exists (credentials live in its store). */
    suspend fun resolveNetworkSource(
        context: Context,
        entry: PlaybackHistory,
    ): PlaybackSource? {
        val serverId =
            try {
                JSONObject(entry.serverInfo ?: return null).optString("serverId")
            } catch (e: Exception) {
                return null
            }
        if (serverId.isEmpty()) return null
        val sizeBytes = entry.sizeBytesFromKey()
        return when (entry.sourceType) {
            HistorySourceType.SMB ->
                SmbCredentialStore(context).list().find { it.id == serverId }
                    ?.let { PlaybackSource.Smb(it, entry.mediaPath, sizeBytes) }
            HistorySourceType.FTP ->
                FtpCredentialStore(context).list().find { it.id == serverId }
                    ?.let { PlaybackSource.Ftp(it, entry.mediaPath, sizeBytes) }
            HistorySourceType.SFTP ->
                SftpCredentialStore(context).list().find { it.id == serverId }
                    ?.let { PlaybackSource.Sftp(it, entry.mediaPath, sizeBytes) }
            HistorySourceType.NFS ->
                AppDatabase.getInstance(context).savedServerDao().getById(serverId)
                    ?.let { PlaybackSource.Nfs(it, entry.mediaPath, sizeBytes) }
            HistorySourceType.WEBDAV ->
                AppDatabase.getInstance(context).savedServerDao().getById(serverId)
                    ?.let { PlaybackSource.Webdav(it, entry.mediaPath, sizeBytes) }
            HistorySourceType.LOCAL, HistorySourceType.HTTP, HistorySourceType.DLNA -> null
        }
    }
}
