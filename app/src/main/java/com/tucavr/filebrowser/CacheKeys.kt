package com.tucavr.filebrowser

import com.tucavr.navigation.PlaybackSource
import java.security.MessageDigest

/**
 * Chave de cache compartilhada entre os caches de mídia do app (thumbnail em disco,
 * `media_metadata_cache`/`folder_media_status` no Room) — extraída de
 * `ThumbnailGenerator.cacheKeyFor`/`NetworkThumbnailGenerator.cacheKeyFor` (que continuam
 * existindo, delegando pra cá) pra não duplicar a mesma lógica de hash em três lugares.
 *
 * [forLocalEntry] inclui `lastModified` (mtime real disponível via [MediaEntry]) — é a chave
 * mais precisa, usada pelo cache de thumbnail local. [forSource] é usada pelos caches
 * keyados por [PlaybackSource] (que não carrega mtime, só path/size) — local aqui usa
 * só path+size, igual ao esquema de rede (sem mtime confiável); ver
 * `NetworkThumbnailGenerator.cacheKeyFor` pro raciocínio original desse trade-off em rede.
 */
object CacheKeys {

    fun forLocalEntry(entry: MediaEntry): String =
        sha256("${entry.path}|${entry.sizeBytes}|${entry.lastModified}")

    fun forSource(source: PlaybackSource): String {
        val raw = when (source) {
            is PlaybackSource.LocalFile -> "local|${source.path}|${source.sizeBytes}"
            is PlaybackSource.Http -> "http|${source.url}"
            is PlaybackSource.Smb ->
                "smb|${source.server.host}|${source.server.port}|${source.server.share}|${source.path}|${source.sizeBytes}"
            is PlaybackSource.Ftp ->
                "ftp|${source.server.host}|${source.server.port}|${source.path}|${source.sizeBytes}"
            is PlaybackSource.Sftp ->
                "sftp|${source.server.host}|${source.server.port}|${source.path}|${source.sizeBytes}"
            is PlaybackSource.Nfs ->
                "nfs|${source.server.host}|${source.path}|${source.sizeBytes}"
            is PlaybackSource.Dlna ->
                "dlna|${source.server.host}|${source.url}|${source.sizeBytes}"
            is PlaybackSource.Webdav ->
                "webdav|${source.server.host}|${source.path}|${source.sizeBytes}"
        }
        return sha256(raw)
    }

    /**
     * Chave para `folder_media_status`: mesma família de esquema de [forSource], mas para
     * PASTAS (sem `sizeBytes`, que não se aplica). `host`/`port`/`share` continuam entrando
     * pois um mesmo `path` relativo pode existir em servidores/shares diferentes.
     */
    fun forFolder(sourceKind: String, host: String, port: Int, share: String?, path: String): String =
        sha256("$sourceKind|$host|$port|${share.orEmpty()}|$path")

    internal fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
