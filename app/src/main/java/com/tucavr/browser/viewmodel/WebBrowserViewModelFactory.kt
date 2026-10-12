package com.tucavr.browser.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tucavr.browser.controller.BrowserMediaController
import com.tucavr.browser.domain.BrowserBookmarkManager

/**
 * Factory for WebBrowserViewModel, which takes constructor arguments and has no default factory.
 */
class WebBrowserViewModelFactory(
    private val mediaController: BrowserMediaController,
    private val bookmarkManager: BrowserBookmarkManager,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WebBrowserViewModel::class.java)) {
            return WebBrowserViewModel(mediaController, bookmarkManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
