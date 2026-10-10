package com.tucavr.browser.state

import com.tucavr.browser.data.BrowserBookmark

/**
 * UI state for the 2D web browser in VR.
 */
data class WebBrowserUiState(
    val url: String = "about:blank",
    val isLoading: Boolean = false,
    val loadingProgress: Int = 0,
    val pageTitle: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isBookmarked: Boolean = false,
    val isCurvedGeometry: Boolean = false,
    val isFullScreen: Boolean = false,
    val errorMessage: String? = null,
    val bookmarks: List<BrowserBookmark> = emptyList(),
    val showEditBookmarkModal: Boolean = false,
    val editingBookmark: BrowserBookmark? = null
)
