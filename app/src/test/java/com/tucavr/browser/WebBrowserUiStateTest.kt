package com.tucavr.browser

import com.tucavr.browser.state.WebBrowserUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebBrowserUiStateTest {
    @Test
    fun `submitting the url that is already loading is detected despite the trailing slash gecko adds`() {
        val state = WebBrowserUiState(url = "https://googlre.com/", isLoading = true)

        assertTrue(state.isLoadingUrl("https://googlre.com"))
    }

    @Test
    fun `a different url is never treated as already loading`() {
        val state = WebBrowserUiState(url = "https://googlre.com/", isLoading = true)

        assertFalse(state.isLoadingUrl("https://google.com"))
    }

    @Test
    fun `the same url can be submitted again once loading has finished`() {
        val state = WebBrowserUiState(url = "https://googlre.com/", isLoading = false)

        assertFalse(state.isLoadingUrl("https://googlre.com"))
    }
}
