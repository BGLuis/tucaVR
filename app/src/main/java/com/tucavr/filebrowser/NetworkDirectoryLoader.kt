package com.tucavr.filebrowser

import android.content.Context
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import com.tucavr.history.PlaybackHistory
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

/** Persistent folder-has-media cache that the loader prunes and refreshes against. */
interface FolderCache {
    suspend fun invalidateFolders(
        context: Context,
        folderKeys: List<String>,
    )

    suspend fun pruneCachedEmptyFolders(
        context: Context,
        entries: List<MediaEntry>,
        folderKeyFor: (MediaEntry) -> String,
    ): List<MediaEntry>

    suspend fun probeUncachedFoldersProgressive(
        context: Context,
        sourceKind: String,
        entries: List<MediaEntry>,
        folderKeyFor: (MediaEntry) -> String,
        scanFnFor: (MediaEntry) -> suspend () -> String,
        onFolderEmpty: suspend (emptyPath: String) -> Unit,
    )
}

/** Copies resume progress and last-played time from [history] onto a video entry. */
internal fun MediaEntry.withHistory(history: PlaybackHistory?): MediaEntry {
    if (type != MediaType.VIDEO || history == null) return this
    val progress = if (history.durationMs > 0) history.positionMs.toFloat() / history.durationMs.toFloat() else null
    return copy(progressFraction = progress, lastPlayedAt = history.lastPlayedAt)
}

/** The toolbar cannot be re-synced to a different sort, so reuse needs the same sort and a root that is still on screen. */
internal fun canReuseBrowser(
    sameServer: Boolean,
    rootAttached: Boolean,
    shown: FolderConfig,
    next: FolderConfig,
): Boolean = sameServer && rootAttached && shown.sortBy == next.sortBy && shown.ascending == next.ascending

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
    private val folderCache: FolderCache = NetworkFolderProber,
) {
    private var currentRequestId: Long = 0L
    private var activeJob: Job? = null

    @Volatile
    private var userInteracted = false

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
     * Stops background pruning from removing rows of the current listing, so rows do not
     * shift under the VR pointer. Removals still apply on the next load, from the cache.
     */
    fun markUserInteracted() {
        userInteracted = true
    }

    fun trackUserInteraction(recycler: RecyclerView) {
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(
                    recyclerView: RecyclerView,
                    newState: Int,
                ) {
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) markUserInteracted()
                }
            },
        )
        recycler.addOnItemTouchListener(
            object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(
                    rv: RecyclerView,
                    e: MotionEvent,
                ): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) markUserInteracted()
                    return false
                }
            },
        )
    }

    /**
     * Initiates loading a directory.
     * Cancels any in-flight request and generates a new request token.
     * If the user navigates away or starts another request, earlier responses are discarded.
     *
     * @param forceRefresh drops the cached folder status of every subfolder in the fresh listing
     *   before pruning, so they are probed again.
     * @param historyFor resume/last-played lookup for video entries (see [withHistory]).
     */
    fun loadDirectory(
        path: String,
        fetchRaw: suspend () -> String,
        proberConfig: FolderProberConfig? = null,
        forceRefresh: Boolean = false,
        historyFor: (suspend (MediaEntry) -> PlaybackHistory?)? = null,
        onStateChange: (NetworkDirectoryState) -> Unit,
    ) {
        val requestId = ++currentRequestId
        userInteracted = false
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
                    if (forceRefresh) {
                        val subfolderKeys = entries.filter { it.type == MediaType.DIRECTORY }.map(proberConfig.folderKeyFor)
                        folderCache.invalidateFolders(proberConfig.context, subfolderKeys)
                    }
                    entries =
                        folderCache.pruneCachedEmptyFolders(
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

                if (historyFor != null) {
                    entries =
                        withContext(ioDispatcher) {
                            entries.map { it.withHistory(if (it.type == MediaType.VIDEO) historyFor(it) else null) }
                        }
                    if (requestId != currentRequestId) return@launch
                }

                onStateChange(NetworkDirectoryState.Success(entries))

                if (proberConfig != null) {
                    val currentEntries = entries.toMutableList()
                    folderCache.probeUncachedFoldersProgressive(
                        context = proberConfig.context,
                        sourceKind = proberConfig.sourceKind,
                        entries = entries,
                        folderKeyFor = proberConfig.folderKeyFor,
                        scanFnFor = proberConfig.scanFnFor,
                        onFolderEmpty = { emptyPath ->
                            if (requestId == currentRequestId && !userInteracted) {
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
