package com.tucavr.browser

import com.tucavr.browser.controller.BrowserMediaController
import com.tucavr.browser.data.BrowserBookmark
import com.tucavr.browser.data.BrowserBookmarkDao
import com.tucavr.browser.domain.BrowserBookmarkManager
import com.tucavr.browser.state.MediaCommand
import com.tucavr.browser.state.WebBrowserEvent
import com.tucavr.browser.viewmodel.WebBrowserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebBrowserArchitectureTest {

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
    private lateinit var bookmarkManager: BrowserBookmarkManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakeBrowserBookmarkDao()
        bookmarkManager = BrowserBookmarkManager(
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
    fun `test BrowserMediaController emits and receives commands`() = runTest {
        val controller = BrowserMediaController()
        val emitted = mutableListOf<MediaCommand>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.commands.collect { emitted.add(it) }
        }

        controller.emitCommand(MediaCommand.Play)
        controller.emitCommand(MediaCommand.Skip(15))

        assertEquals(2, emitted.size)
        assertEquals(MediaCommand.Play, emitted[0])
        assertEquals(MediaCommand.Skip(15), emitted[1])
    }

    @Test
    fun `test WebBrowserViewModel url normalization on event`() = runTest {
        val controller = BrowserMediaController()
        val viewModel = WebBrowserViewModel(controller, bookmarkManager)

        viewModel.handleEvent(WebBrowserEvent.OnUrlSubmitted("example.com"))
        assertEquals("https://example.com", viewModel.uiState.value.url)

        viewModel.handleEvent(WebBrowserEvent.OnUrlSubmitted("http://test.org"))
        assertEquals("http://test.org", viewModel.uiState.value.url)
    }

    @Test
    fun `test WebBrowserViewModel updates webview state`() = runTest {
        val controller = BrowserMediaController()
        val viewModel = WebBrowserViewModel(controller, bookmarkManager)

        viewModel.updateWebViewState(
            url = "https://kotlinlang.org",
            isLoading = true,
            loadingProgress = 50,
            pageTitle = "Kotlin Programming Language",
            canGoBack = true,
            canGoForward = false,
            errorMessage = "Timeout"
        )

        val state = viewModel.uiState.value
        assertEquals("https://kotlinlang.org", state.url)
        assertTrue(state.isLoading)
        assertEquals(50, state.loadingProgress)
        assertEquals("Kotlin Programming Language", state.pageTitle)
        assertTrue(state.canGoBack)
        assertFalse(state.canGoForward)
        assertEquals("Timeout", state.errorMessage)

        viewModel.handleEvent(WebBrowserEvent.ClearError)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `test WebBrowserViewModel bookmark and modal events`() = runTest {
        val controller = BrowserMediaController()
        val viewModel = WebBrowserViewModel(controller, bookmarkManager)

        viewModel.handleEvent(WebBrowserEvent.OpenAddBookmarkModal)
        assertTrue(viewModel.uiState.value.showEditBookmarkModal)
        assertNull(viewModel.uiState.value.editingBookmark)

        viewModel.handleEvent(WebBrowserEvent.DismissBookmarkModal)
        assertFalse(viewModel.uiState.value.showEditBookmarkModal)

        viewModel.handleEvent(WebBrowserEvent.ToggleGeometry)
        assertTrue(viewModel.uiState.value.isCurvedGeometry)

        viewModel.handleEvent(WebBrowserEvent.GoHome)
        assertEquals("about:blank", viewModel.uiState.value.url)
    }
}
