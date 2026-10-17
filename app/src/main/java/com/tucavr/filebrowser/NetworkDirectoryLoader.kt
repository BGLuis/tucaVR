package com.tucavr.filebrowser

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface NetworkDirectoryState {
    data object Loading : NetworkDirectoryState

    data class Error(val message: String) : NetworkDirectoryState

    data object Empty : NetworkDirectoryState

    data class Success(val entries: List<MediaEntry>) : NetworkDirectoryState
}

/**
 * Shared directory loader for network file browsers (SMB, FTP, SFTP, NFS, WebDAV).
 *
 * Responsibilities:
 * - Decodes TSV wire format: `name\tisDir\tsize[\tmtime]` per line (or `ERROR:<message>`).
 * - Manages sequential request tokens, discarding stale responses when the user navigates away.
 * - Emits clean sealed UI states: [NetworkDirectoryState.Loading], [NetworkDirectoryState.Error],
 *   [NetworkDirectoryState.Empty], and [NetworkDirectoryState.Success].
 * - Supports non-blocking progressive folder probing via [FolderProberConfig].
 */
class NetworkDirectoryLoader(
    private val scope: CoroutineScope,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private var currentRequestId: Long = 0L
    private var activeJob: Job? = null

    data class FolderProberConfig(
        val context: Context,
        val sourceKind: String,
        val folderKeyFor: (MediaEntry) -> String,
        val scanFnFor: (MediaEntry) -> suspend () -> String,
    )

    /**
     * Parses a tab-separated listing string:
     * Format: `name\tisDir\tsize[\tmtime]` per line (or `ERROR:<message>`).
     */
    fun parseTsv(
        raw: String,
        basePath: String,
    ): Result<List<MediaEntry>> {
        if (raw.startsWith("ERROR:")) {
            return Result.failure(IllegalStateException(raw.removePrefix("ERROR:").trim()))
        }
        val lines = raw.split("\n").filter { it.isNotBlank() }
        val entries =
            lines.mapNotNull { line ->
                val parts = line.split("\t")
                val name = parts.getOrNull(0) ?: return@mapNotNull null
                val isDir = parts.getOrNull(1) == "1"
                val sizeBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val mtimeSec = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                val childPath = if (basePath.isEmpty()) name else "$basePath/$name"

                val type =
                    if (isDir) {
                        MediaType.DIRECTORY
                    } else {
                        mediaTypeForExtension(name.substringAfterLast('.', "")) ?: MediaType.VIDEO
                    }
                val f3d =
                    if (type == MediaType.VIDEO) {
                        MediaFilterEngine.detectFormat3DFromFilename(name)
                    } else {
                        Format3DType.FLAT_2D
                    }

                MediaEntry(
                    name = name,
                    path = childPath,
                    sizeBytes = sizeBytes,
                    lastModified = mtimeSec * 1000L,
                    type = type,
                    format3DHint = f3d,
                )
            }
        return Result.success(entries)
    }

    /**
     * Initiates loading a directory.
     * Cancels any in-flight request and generates a new request token.
     * If the user navigates away or starts another request, earlier responses are discarded.
     */
    fun loadDirectory(
        path: String,
        fetchRaw: suspend () -> String,
        proberConfig: FolderProberConfig? = null,
        onStateChange: (NetworkDirectoryState) -> Unit,
    ) {
        val requestId = ++currentRequestId
        activeJob?.cancel()
        onStateChange(NetworkDirectoryState.Loading)

        activeJob =
            scope.launch {
                val raw =
                    withContext(ioDispatcher) {
                        try {
                            fetchRaw()
                        } catch (e: Exception) {
                            "ERROR:${e.message ?: "Network error"}"
                        }
                    }
                if (requestId != currentRequestId) return@launch

                val parseResult = parseTsv(raw, path)
                if (parseResult.isFailure) {
                    val errorMsg = parseResult.exceptionOrNull()?.message ?: "Unknown error"
                    onStateChange(NetworkDirectoryState.Error(errorMsg))
                    return@launch
                }

                var entries = parseResult.getOrThrow()
                if (entries.isEmpty()) {
                    onStateChange(NetworkDirectoryState.Empty)
                    return@launch
                }

                if (proberConfig != null) {
                    entries =
                        NetworkFolderProber.pruneCachedEmptyFolders(
                            context = proberConfig.context,
                            entries = entries,
                            folderKeyFor = proberConfig.folderKeyFor,
                        )
                }

                if (requestId != currentRequestId) return@launch

                if (entries.isEmpty()) {
                    onStateChange(NetworkDirectoryState.Empty)
                    return@launch
                }

                onStateChange(NetworkDirectoryState.Success(entries))

                if (proberConfig != null) {
                    val currentEntries = entries.toMutableList()
                    NetworkFolderProber.probeUncachedFoldersProgressive(
                        context = proberConfig.context,
                        sourceKind = proberConfig.sourceKind,
                        entries = entries,
                        folderKeyFor = proberConfig.folderKeyFor,
                        scanFnFor = proberConfig.scanFnFor,
                        onFolderEmpty = { emptyPath ->
                            if (requestId == currentRequestId) {
                                currentEntries.removeAll { it.path == emptyPath }
                                if (currentEntries.isEmpty()) {
                                    onStateChange(NetworkDirectoryState.Empty)
                                } else {
                                    onStateChange(NetworkDirectoryState.Success(currentEntries.toList()))
                                }
                            }
                        },
                    )
                }
            }
    }
}
