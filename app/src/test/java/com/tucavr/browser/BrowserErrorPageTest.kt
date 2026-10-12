package com.tucavr.browser

import com.tucavr.browser.engine.BrowserErrorKind
import com.tucavr.browser.engine.BrowserErrorPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.WebRequestError

class BrowserErrorPageTest {
    @Test
    fun `typo in the domain is reported as unknown host`() {
        val kind =
            BrowserErrorPage.kindOf(
                WebRequestError.ERROR_CATEGORY_URI,
                WebRequestError.ERROR_UNKNOWN_HOST,
            )

        assertEquals(BrowserErrorKind.UNKNOWN_HOST, kind)
    }

    @Test
    fun `network failures map to their own messages`() {
        val network = WebRequestError.ERROR_CATEGORY_NETWORK

        assertEquals(BrowserErrorKind.TIMEOUT, BrowserErrorPage.kindOf(network, WebRequestError.ERROR_NET_TIMEOUT))
        assertEquals(BrowserErrorKind.OFFLINE, BrowserErrorPage.kindOf(network, WebRequestError.ERROR_OFFLINE))
        assertEquals(
            BrowserErrorKind.CONNECTION_REFUSED,
            BrowserErrorPage.kindOf(network, WebRequestError.ERROR_CONNECTION_REFUSED),
        )
    }

    @Test
    fun `any security error is shown as a blocked connection even with an unknown code`() {
        val kind = BrowserErrorPage.kindOf(WebRequestError.ERROR_CATEGORY_SECURITY, 9999)

        assertEquals(BrowserErrorKind.CERTIFICATE, kind)
    }

    @Test
    fun `unmapped errors fall back to the generic message`() {
        val kind = BrowserErrorPage.kindOf(WebRequestError.ERROR_CATEGORY_UNKNOWN, 9999)

        assertEquals(BrowserErrorKind.GENERIC, kind)
    }

    @Test
    fun `failed url is escaped so a hostile address cannot inject markup`() {
        val html =
            BrowserErrorPage.html(
                failedUrl = "https://evil.test/\"><script>alert(1)</script>",
                title = "T",
                description = "D",
                urlLabel = "U",
                retryLabel = "R",
            )

        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("href=\"https://evil.test/&quot;&gt;"))
    }

    @Test
    fun `retry link only exists for http and https addresses`() {
        fun retryLinkFor(url: String?) = BrowserErrorPage.html(url, "T", "D", "U", "Retry").contains("class=\"retry\"")

        assertTrue(retryLinkFor("https://googlre.com/"))
        assertTrue(retryLinkFor("http://googlre.com/"))
        assertFalse(retryLinkFor("javascript:alert(1)"))
        assertFalse(retryLinkFor("about:neterror"))
        assertFalse(retryLinkFor(null))
    }
}
