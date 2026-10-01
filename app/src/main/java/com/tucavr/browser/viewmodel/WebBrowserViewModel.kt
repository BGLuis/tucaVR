package com.tucavr.browser.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tucavr.browser.controller.BrowserMediaController
import com.tucavr.browser.data.BrowserBookmark
import com.tucavr.browser.domain.BrowserBookmarkManager
import com.tucavr.browser.state.MediaCommand
import com.tucavr.browser.state.WebBrowserEvent
import com.tucavr.browser.state.WebBrowserUiState
import com.tucavr.debug.VRLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hilt-injected ViewModel for the web browser.
 * Holds UI state, observes cross-presentation media commands, and manages bookmarks.
 */
@HiltViewModel
class WebBrowserViewModel @Inject constructor(
    mediaController: BrowserMediaController,
    private val bookmarkManager: BrowserBookmarkManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebBrowserUiState())
    val uiState: StateFlow<WebBrowserUiState> = _uiState.asStateFlow()

    val mediaCommands: SharedFlow<MediaCommand> = mediaController.commands

    init {
        observeBookmarks()
    }

    private fun observeBookmarks() {
        VRLog.w("[WEB-Browser] Observing bookmarks")
        viewModelScope.launch {
            bookmarkManager.bookmarks.collectLatest { list ->
                _uiState.update { current ->
                    current.copy(
                        bookmarks = list,
                        isBookmarked = checkIfBookmarked(current.url, list)
                    )
                }
            }
        }
    }

    fun handleEvent(event: WebBrowserEvent) {
        VRLog.i("[WEB-Browser] Handling event: $event")
        when (event) {
            is WebBrowserEvent.OnUrlSubmitted -> {
                val formattedUrl = bookmarkManager.normalizeUrl(event.url)
                _uiState.update { current ->
                    current.copy(
                        url = formattedUrl,
                        errorMessage = null,
                        isBookmarked = checkIfBookmarked(formattedUrl, current.bookmarks)
                    )
                }
            }
            is WebBrowserEvent.GoBack -> { /* Tratado no GeckoSession */ }
            is WebBrowserEvent.GoForward -> { /* Tratado no GeckoSession */ }
            is WebBrowserEvent.Reload -> { /* Tratado no GeckoSession */ }
            is WebBrowserEvent.GoHome -> {
                _uiState.update { current ->
                    current.copy(
                        url = "about:blank",
                        errorMessage = null,
                        isBookmarked = false,
                        isFullScreen = false
                    )
                }
            }
            is WebBrowserEvent.ToggleBookmark -> {
                val currentUrl = _uiState.value.url
                val pageTitle = _uiState.value.pageTitle
                if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                    bookmarkManager.toggleBookmark(currentUrl, pageTitle) { isBookmarked, _ ->
                        _uiState.update { it.copy(isBookmarked = isBookmarked) }
                    }
                }
            }
            is WebBrowserEvent.ToggleGeometry -> {
                _uiState.update { it.copy(isCurvedGeometry = !it.isCurvedGeometry) }
            }
            is WebBrowserEvent.CloseBrowser -> {
                _uiState.update { it.copy(url = "about:blank") }
            }
            is WebBrowserEvent.ClearError -> {
                _uiState.update { it.copy(errorMessage = null) }
            }
            is WebBrowserEvent.OpenAddBookmarkModal -> {
                _uiState.update { it.copy(showEditBookmarkModal = true, editingBookmark = null) }
            }
            is WebBrowserEvent.OpenEditBookmarkModal -> {
                _uiState.update { it.copy(showEditBookmarkModal = true, editingBookmark = event.bookmark) }
            }
            is WebBrowserEvent.DismissBookmarkModal -> {
                _uiState.update { it.copy(showEditBookmarkModal = false, editingBookmark = null) }
            }
            is WebBrowserEvent.SaveBookmark -> {
                val editing = _uiState.value.editingBookmark
                if (editing != null) {
                    bookmarkManager.updateBookmark(editing, event.title, event.url)
                } else {
                    bookmarkManager.addBookmark(event.title, event.url)
                }
                _uiState.update { it.copy(showEditBookmarkModal = false, editingBookmark = null) }
            }
            is WebBrowserEvent.DeleteBookmark -> {
                bookmarkManager.deleteBookmark(event.bookmark)
            }
        }
    }

    fun updateWebViewState(
        url: String? = null,
        isLoading: Boolean? = null,
        loadingProgress: Int? = null,
        pageTitle: String? = null,
        canGoBack: Boolean? = null,
        canGoForward: Boolean? = null,
        isFullScreen: Boolean? = null,
        errorMessage: String? = null
    ) {
        _uiState.update { current ->
            val updatedUrl = url ?: current.url
            current.copy(
                url = updatedUrl,
                isLoading = isLoading ?: current.isLoading,
                loadingProgress = loadingProgress ?: current.loadingProgress,
                pageTitle = pageTitle ?: current.pageTitle,
                canGoBack = canGoBack ?: current.canGoBack,
                canGoForward = canGoForward ?: current.canGoForward,
                isFullScreen = isFullScreen ?: current.isFullScreen,
                isBookmarked = checkIfBookmarked(updatedUrl, current.bookmarks),
                errorMessage = if (errorMessage != null) errorMessage else current.errorMessage
            )
        }
    }

    private fun checkIfBookmarked(url: String, bookmarks: List<BrowserBookmark>): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        return bookmarks.any { it.url == url }
    }

    override fun onCleared() {
        VRLog.i("[WEB-Browser] ViewModel cleared")
        super.onCleared()
    }
}
