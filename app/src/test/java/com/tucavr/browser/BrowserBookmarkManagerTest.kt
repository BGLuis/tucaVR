package com.tucavr.browser

import com.tucavr.browser.data.BrowserBookmark
import com.tucavr.browser.data.BrowserBookmarkDao
import com.tucavr.browser.domain.BrowserBookmarkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserBookmarkManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private class FakeBrowserBookmarkDao : BrowserBookmarkDao {
        val flow = MutableStateFlow<List<BrowserBookmark>>(emptyList())
        val items = mutableListOf<BrowserBookmark>()
        private var nextId = 1L

        override fun getAllBookmarksFlow(): Flow<List<BrowserBookmark>> = flow

        override suspend fun getAllBookmarks(): List<BrowserBookmark> = items.toList()

        override suspend fun getBookmarkById(id: Long): BrowserBookmark? = items.find { it.id == id }

        override suspend fun insertBookmark(bookmark: BrowserBookmark): Long {
            val assignedId = if (bookmark.id == 0L) nextId++ else bookmark.id
            val inserted = bookmark.copy(id = assignedId)
            items.removeAll { it.id == assignedId }
            items.add(inserted)
            flow.value = items.toList()
            return assignedId
        }

        override suspend fun insertAll(bookmarks: List<BrowserBookmark>) {
            bookmarks.forEach {
                val assignedId = if (it.id == 0L) nextId++ else it.id
                items.add(it.copy(id = assignedId))
            }
            flow.value = items.toList()
        }

        override suspend fun updateBookmark(bookmark: BrowserBookmark) {
            val index = items.indexOfFirst { it.id == bookmark.id }
            if (index != -1) {
                items[index] = bookmark
                flow.value = items.toList()
            }
        }

        override suspend fun deleteBookmark(bookmark: BrowserBookmark) {
            items.removeAll { it.id == bookmark.id }
            flow.value = items.toList()
        }

        override suspend fun deleteById(id: Long) {
            items.removeAll { it.id == id }
            flow.value = items.toList()
        }

        override suspend fun updateDisplayOrder(id: Long, order: Int) {
            val index = items.indexOfFirst { it.id == id }
            if (index != -1) {
                items[index] = items[index].copy(displayOrder = order)
                flow.value = items.toList()
            }
        }
    }

    private lateinit var fakeDao: FakeBrowserBookmarkDao
    private lateinit var manager: BrowserBookmarkManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakeBrowserBookmarkDao()
        manager = BrowserBookmarkManager(
            bookmarkDao = fakeDao,
            scope = testScope,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `normalizeUrl prefixes http and https correctly`() {
        assertEquals("https://google.com", manager.normalizeUrl("google.com"))
        assertEquals("http://example.com", manager.normalizeUrl("http://example.com"))
        assertEquals("https://youtube.com", manager.normalizeUrl("https://youtube.com"))
        assertEquals("about:blank", manager.normalizeUrl("about:blank"))
        assertEquals("", manager.normalizeUrl("   "))
    }

    @Test
    fun `addBookmark inserts new item when valid`() = runTest(testDispatcher) {
        testScheduler.advanceUntilIdle()

        val initialCount = fakeDao.items.size

        manager.addBookmark("My Site", "example.com")
        testScheduler.advanceUntilIdle()

        assertEquals(initialCount + 1, fakeDao.items.size)
        val added = fakeDao.items.last()
        assertEquals("My Site", added.title)
        assertEquals("https://example.com", added.url)
    }

    @Test
    fun `toggleBookmark adds and removes bookmark as toggled`() = runTest(testDispatcher) {
        testScheduler.advanceUntilIdle()

        val url = "https://customsite.org"
        var isBookmarked = false

        manager.toggleBookmark(url, "Custom Site") { bookmarked, _ ->
            isBookmarked = bookmarked
        }
        testScheduler.advanceUntilIdle()

        assertEquals(true, isBookmarked)

        // Toggle again should delete it
        manager.toggleBookmark(url, "Custom Site") { bookmarked, _ ->
            isBookmarked = bookmarked
        }
        testScheduler.advanceUntilIdle()

        assertEquals(false, isBookmarked)
    }
}
