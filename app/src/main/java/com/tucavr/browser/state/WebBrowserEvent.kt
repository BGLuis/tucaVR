package com.tucavr.browser.state

import com.tucavr.browser.data.BrowserBookmark

/**
 * Events emitted by the web browser UI.
 */
sealed interface WebBrowserEvent {
    data class OnUrlSubmitted(val url: String) : WebBrowserEvent
    data object GoBack : WebBrowserEvent
    data object GoForward : WebBrowserEvent
    data object Reload : WebBrowserEvent
    data object GoHome : WebBrowserEvent
    data object ToggleBookmark : WebBrowserEvent
    data object ToggleGeometry : WebBrowserEvent
    data object CloseBrowser : WebBrowserEvent
    data object ClearError : WebBrowserEvent
    data object OpenAddBookmarkModal : WebBrowserEvent
    data class OpenEditBookmarkModal(val bookmark: BrowserBookmark) : WebBrowserEvent
    data object DismissBookmarkModal : WebBrowserEvent
    data class SaveBookmark(val title: String, val url: String) : WebBrowserEvent
    data class DeleteBookmark(val bookmark: BrowserBookmark) : WebBrowserEvent
}
