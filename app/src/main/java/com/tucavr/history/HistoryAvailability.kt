package com.tucavr.history

import com.tucavr.VRActivity
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.ServerCredentialStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object HistoryAvailability {
    private const val REMOTE_CHECK_TIMEOUT_MS = 3_000L
    private const val MAX_CONCURRENT_LISTINGS = 4

    // Hides without deleting: a revoked storage permission or an unplugged drive also looks like a missing file.
    suspend fun filterAvailable(
        activity: VRActivity,
        entries: List<PlaybackHistory>,
    ): List<PlaybackHistory> =
        filterAvailableInternal(
            entries = entries,
            localFileExists = { path -> File(path).exists() },
            resolveRemote = { entry -> HistorySourceResolver.resolveNetworkSource(activity, entry) },
            listDirectory = { source, dir -> listRemoteDirectory(activity, source, dir) },
        )

    internal suspend fun filterAvailableInternal(
        entries: List<PlaybackHistory>,
        localFileExists: (String) -> Boolean,
        resolveRemote: suspend (PlaybackHistory) -> PlaybackSource?,
        listDirectory: suspend (PlaybackSource, String) -> String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        timeoutMs: Long = REMOTE_CHECK_TIMEOUT_MS,
    ): List<PlaybackHistory> =
        withContext(ioDispatcher) {
            val resolved =
                entries.filter { it.isStoredOnServer() }
                    .mapNotNull { entry -> resolveRemote(entry)?.let { entry to it } }
            val resolvedKeys = resolved.map { (entry, _) -> entry.historyKey }.toSet()
            val listings = ConcurrentHashMap<RemoteDir, Set<String>>()
            val gate = Semaphore(MAX_CONCURRENT_LISTINGS)

            // Native listings cannot be interrupted, so they run outside this scope: otherwise the
            // timeout below would still wait for an offline server's full connect timeout.
            val listingScope = CoroutineScope(ioDispatcher + SupervisorJob())
            val jobs =
                resolved.distinctBy { (entry, _) -> entry.remoteDir() }.map { (entry, source) ->
                    val dir = entry.remoteDir()
                    listingScope.launch {
                        gate.withPermit {
                            val raw = runCatching { listDirectory(source, dir.path) }.getOrNull()
                            raw?.let(::parseListedNames)?.let { listings[dir] = it }
                        }
                    }
                }
            withTimeoutOrNull(timeoutMs) { jobs.joinAll() }
            jobs.forEach { it.cancel() }

            entries.filter { entry ->
                when {
                    entry.sourceType == HistorySourceType.LOCAL -> localFileExists(entry.mediaPath)
                    !entry.isStoredOnServer() -> true
                    entry.historyKey !in resolvedKeys -> false
                    // No listing means offline or failed, which says nothing about the file itself.
                    else -> listings[entry.remoteDir()]?.contains(entry.mediaPath.substringAfterLast('/')) ?: true
                }
            }
        }

    private data class RemoteDir(val serverInfo: String?, val path: String)

    private fun PlaybackHistory.remoteDir() = RemoteDir(serverInfo, mediaPath.substringBeforeLast('/', missingDelimiterValue = ""))

    private fun PlaybackHistory.isStoredOnServer(): Boolean =
        when (sourceType) {
            HistorySourceType.SMB,
            HistorySourceType.FTP,
            HistorySourceType.SFTP,
            HistorySourceType.NFS,
            HistorySourceType.WEBDAV,
            -> true
            // A URL has no parent directory to list, and a HEAD failure cannot tell "gone" from "expired or refused".
            HistorySourceType.LOCAL,
            HistorySourceType.HTTP,
            HistorySourceType.DLNA,
            -> false
        }

    private fun parseListedNames(raw: String): Set<String>? {
        if (raw.startsWith("ERROR:")) return null
        return raw.lineSequence().filter { it.isNotBlank() }.map { it.substringBefore('\t') }.toSet()
    }

    private fun listRemoteDirectory(
        activity: VRActivity,
        source: PlaybackSource,
        dir: String,
    ): String =
        when (source) {
            is PlaybackSource.Smb ->
                activity.nativeSmbListDirectory(
                    source.server.host,
                    source.server.port,
                    source.server.username,
                    source.server.password,
                    source.server.domain,
                    source.server.share,
                    dir,
                )
            is PlaybackSource.Ftp ->
                activity.nativeFtpListDirectory(
                    source.server.host,
                    source.server.port,
                    source.server.username,
                    source.server.password,
                    dir,
                )
            is PlaybackSource.Sftp ->
                activity.nativeSftpListDirectory(
                    source.server.host,
                    source.server.port,
                    source.server.username,
                    source.server.password,
                    source.server.privateKey ?: "",
                    source.server.hostKeyFingerprint ?: "",
                    dir,
                )
            is PlaybackSource.Nfs ->
                activity.nativeNfsListDirectory(source.server.host, source.server.port, source.server.path, dir, 3)
            is PlaybackSource.Webdav -> {
                val extra = source.server.extraJson?.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                activity.nativeWebdavListDirectory(
                    source.server.host,
                    source.server.port,
                    source.server.path,
                    dir,
                    source.server.username,
                    ServerCredentialStore(activity).getPassword(source.server.id),
                    extra?.optBoolean("useHttps", false) ?: false,
                    extra?.optBoolean("acceptInvalidCerts", false) ?: false,
                )
            }
            is PlaybackSource.LocalFile, is PlaybackSource.Http, is PlaybackSource.Dlna -> "ERROR:not a server file"
        }
}
