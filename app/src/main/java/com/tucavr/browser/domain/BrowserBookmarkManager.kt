package com.tucavr.browser.domain

import com.tucavr.browser.data.BrowserBookmark
import com.tucavr.browser.data.BrowserBookmarkDao
import com.tucavr.debug.VRLog
import com.tucavr.history.AppDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manager responsible for business logic, reactive state management, and database operations
 * for VR browser bookmarks and shortcuts.
 *
 * Improves unit testability and isolates persistence from the UI presentation layer.
 */
class BrowserBookmarkManager(
    private val bookmarkDao: BrowserBookmarkDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {

    private val _bookmarks = MutableStateFlow<List<BrowserBookmark>>(emptyList())
    val bookmarks: StateFlow<List<BrowserBookmark>> = _bookmarks.asStateFlow()

    init {
        VRLog.d("[WEB-Browser] Initializing BrowserBookmarkManager")
        observeBookmarks()
    }

    private fun observeBookmarks() {
        scope.launch {
            bookmarkDao.getAllBookmarksFlow().collectLatest { list ->
                VRLog.d("[WEB-Browser] Observing bookmarks ${list.size} entries")
                if (list.isEmpty()) {
                    withContext(ioDispatcher) {
                        bookmarkDao.insertAll(AppDatabase.DEFAULT_PRESETS)
                    }
                } else {
                    _bookmarks.value = list
                }
            }
        }
    }

    /**
     * Normalizes and validates a user-provided URL.
     */
    fun normalizeUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.isEmpty()) return ""
        if (url == "about:blank") return "about:blank"
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("about:")) {
            url = "https://$url"
        }
        return url
    }

    /**
     * Adds a new bookmark.
     */
    fun addBookmark(title: String, rawUrl: String, onComplete: (() -> Unit)? = null) {
        val url = normalizeUrl(rawUrl)
        if (url.isEmpty() || url == "https://" || url == "about:blank") return

        val finalTitle = title.trim().ifBlank { url }
        val currentList = _bookmarks.value

        scope.launch(ioDispatcher) {
            bookmarkDao.insertBookmark(
                BrowserBookmark(
                    title = finalTitle,
                    url = url,
                    displayOrder = currentList.size,
                    isPreset = false
                )
            )
            onComplete?.let {
                withContext(mainDispatcher) { it() }
            }
        }
    }

    /**
     * Updates an existing bookmark.
     */
    fun updateBookmark(target: BrowserBookmark, title: String, rawUrl: String, onComplete: (() -> Unit)? = null) {
        val url = normalizeUrl(rawUrl)
        if (url.isEmpty() || url == "https://" || url == "about:blank") return

        val finalTitle = title.trim().ifBlank { url }

        scope.launch(ioDispatcher) {
            bookmarkDao.updateBookmark(target.copy(title = finalTitle, url = url))
            onComplete?.let {
                withContext(mainDispatcher) { it() }
            }
        }
    }

    /**
     * Deletes a bookmark.
     */
    fun deleteBookmark(bookmark: BrowserBookmark) {
        scope.launch(ioDispatcher) {
            bookmarkDao.deleteBookmark(bookmark)
        }
    }

    /**
     * Reorders bookmarks by swapping an item's index.
     */
    fun reorderBookmark(fromIndex: Int, toIndex: Int) {
        val currentList = _bookmarks.value
        if (fromIndex !in currentList.indices || toIndex !in currentList.indices) return

        val mutableList = currentList.toMutableList()
        val item = mutableList.removeAt(fromIndex)
        mutableList.add(toIndex, item)

        scope.launch(ioDispatcher) {
            bookmarkDao.reorderBookmarks(mutableList.map { it.id })
        }
    }

    /**
     * Checks whether a URL is already bookmarked.
     */
    suspend fun findBookmarkByUrl(url: String): BrowserBookmark? {
        if (url.isBlank() || url == "about:blank") return null
        return withContext(ioDispatcher) {
            val all = bookmarkDao.getAllBookmarks()
            all.firstOrNull { it.url == url }
        }
    }

    /**
     * Toggles a URL's bookmark state (adds it if absent, deletes it if present).
     */
    fun toggleBookmark(url: String, pageTitle: String, onResult: ((isBookmarked: Boolean, entry: BrowserBookmark?) -> Unit)? = null) {
        if (url.isBlank() || url == "about:blank") return

        scope.launch(ioDispatcher) {
            val existing = findBookmarkByUrl(url)
            if (existing != null) {
                bookmarkDao.deleteBookmark(existing)
                onResult?.let {
                    withContext(mainDispatcher) { it(false, null) }
                }
            } else {
                val newBookmark = BrowserBookmark(
                    title = pageTitle.ifBlank { url },
                    url = url,
                    displayOrder = _bookmarks.value.size,
                    isPreset = false
                )
                val id = bookmarkDao.insertBookmark(newBookmark)
                val inserted = newBookmark.copy(id = id)
                onResult?.let {
                    withContext(mainDispatcher) { it(true, inserted) }
                }
            }
        }
    }
}
