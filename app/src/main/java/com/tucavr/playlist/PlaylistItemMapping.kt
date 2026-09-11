package com.tucavr.playlist

import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.FtpCredentialStore
import com.tucavr.network.SavedServerDao
import com.tucavr.network.SftpCredentialStore
import com.tucavr.network.SmbCredentialStore

/**
 * Funções de mapeamento bidirecional entre [PlaybackSource] e [PlaylistItem].
 */
fun PlaybackSource.toPlaylistItemUri(): String = when (this) {
    is PlaybackSource.LocalFile -> path
    is PlaybackSource.Http -> url
    is PlaybackSource.Smb -> "${server.id}|$path"
    is PlaybackSource.Ftp -> "${server.id}|$path"
    is PlaybackSource.Sftp -> "${server.id}|$path"
    is PlaybackSource.Nfs -> "${server.id}|$path"
    is PlaybackSource.Dlna -> "${server.id}|$url"
    is PlaybackSource.Webdav -> "${server.id}|$path"
}

fun PlaybackSource.sourceTypeString(): String = when (this) {
    is PlaybackSource.LocalFile -> "LOCAL"
    is PlaybackSource.Http -> "HTTP"
    is PlaybackSource.Smb -> "SMB"
    is PlaybackSource.Ftp -> "FTP"
    is PlaybackSource.Sftp -> "SFTP"
    is PlaybackSource.Nfs -> "NFS"
    is PlaybackSource.Dlna -> "DLNA"
    is PlaybackSource.Webdav -> "WEBDAV"
}

/**
 * Reconstrói o [PlaybackSource] a partir de um [PlaylistItem].
 * Retorna `null` se o servidor ou recurso não for encontrado (ex.: servidor removido ou offline).
 */
suspend fun PlaylistItem.toPlaybackSource(
    smbCredentials: SmbCredentialStore? = null,
    ftpCredentials: FtpCredentialStore? = null,
    sftpCredentials: SftpCredentialStore? = null,
    savedServerDao: SavedServerDao? = null
): PlaybackSource? {
    return when (sourceType) {
        "LOCAL" -> PlaybackSource.LocalFile(mediaUri)
        "HTTP" -> PlaybackSource.Http(mediaUri)
        "SMB" -> {
            val parts = mediaUri.split("|", limit = 2)
            if (parts.size != 2) return null
            val serverId = parts[0]
            val path = parts[1]
            val server = smbCredentials?.list()?.find { it.id == serverId } ?: return null
            PlaybackSource.Smb(server, path)
        }
        "FTP" -> {
            val parts = mediaUri.split("|", limit = 2)
            if (parts.size != 2) return null
            val serverId = parts[0]
            val path = parts[1]
            val server = ftpCredentials?.list()?.find { it.id == serverId } ?: return null
            PlaybackSource.Ftp(server, path)
        }
        "SFTP" -> {
            val parts = mediaUri.split("|", limit = 2)
            if (parts.size != 2) return null
            val serverId = parts[0]
            val path = parts[1]
            val server = sftpCredentials?.list()?.find { it.id == serverId } ?: return null
            PlaybackSource.Sftp(server, path)
        }
        "NFS" -> {
            val parts = mediaUri.split("|", limit = 2)
            if (parts.size != 2) return null
            val serverId = parts[0]
            val path = parts[1]
            val server = savedServerDao?.getById(serverId) ?: return null
            PlaybackSource.Nfs(server, path)
        }
        "DLNA" -> {
            val parts = mediaUri.split("|", limit = 2)
            if (parts.size != 2) return null
            val serverId = parts[0]
            val url = parts[1]
            val server = savedServerDao?.getById(serverId) ?: return null
            PlaybackSource.Dlna(server, title, url)
        }
        "WEBDAV" -> {
            val parts = mediaUri.split("|", limit = 2)
            if (parts.size != 2) return null
            val serverId = parts[0]
            val path = parts[1]
            val server = savedServerDao?.getById(serverId) ?: return null
            PlaybackSource.Webdav(server, path)
        }
        else -> null
    }
}
