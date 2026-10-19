package com.tucavr.browser

import com.tucavr.browser.ui.faviconCandidateUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FaviconUrlsTest {
    @Test
    fun `without an explicit favicon only the site's own favicon is tried`() {
        assertEquals(listOf("https://example.com/favicon.ico"), faviconCandidateUrls(null, "example.com"))
        assertEquals(listOf("https://example.com/favicon.ico"), faviconCandidateUrls("  ", "example.com"))
    }

    @Test
    fun `the explicit favicon is tried before the site's own favicon`() {
        assertEquals(
            listOf("https://cdn.example.com/icon.png", "https://example.com/favicon.ico"),
            faviconCandidateUrls("https://cdn.example.com/icon.png", "example.com"),
        )
    }

    @Test
    fun `a blank host or about blank yields no site favicon`() {
        assertEquals(emptyList<String>(), faviconCandidateUrls(null, ""))
        assertEquals(emptyList<String>(), faviconCandidateUrls(null, "about:blank"))
        assertEquals(listOf("https://a.com/i.png"), faviconCandidateUrls("https://a.com/i.png", ""))
    }

    @Test
    fun `no candidate ever points at a third party favicon service`() {
        val urls = faviconCandidateUrls(null, "example.com") + faviconCandidateUrls("https://a.com/i.png", "b.com")
        assertFalse(urls.any { it.contains("google") })
    }
}
