package com.tucavr.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPathFormattingTest {
    @Test
    fun `formats root SMB subtitle as share name without trailing slash`() {
        assertEquals("Videos", NetworkSmbScreen.formatSubtitle(share = "Videos", browsePath = ""))
        assertEquals("Videos", NetworkSmbScreen.formatSubtitle(share = "Videos/", browsePath = ""))
        assertEquals("Videos", NetworkSmbScreen.formatSubtitle(share = "/Videos/", browsePath = ""))
    }

    @Test
    fun `formats subpath subtitle as share slash subpath`() {
        val subtitle = NetworkSmbScreen.formatSubtitle(share = "Videos", browsePath = "Movies/4K")
        assertEquals("Videos/Movies/4K", subtitle)
    }

    @Test
    fun `formats subtitle cleanly when subpath has leading or trailing slashes`() {
        assertEquals("Videos/Movies", NetworkSmbScreen.formatSubtitle("Videos", "/Movies"))
        assertEquals("Videos/Movies", NetworkSmbScreen.formatSubtitle("Videos", "Movies/"))
        assertEquals("Videos/Movies", NetworkSmbScreen.formatSubtitle("Videos/", "/Movies/"))
        assertEquals("Videos/Movies/4K", NetworkSmbScreen.formatSubtitle("Videos", "/Movies/4K/"))
        assertEquals("Videos/Movies/4K", NetworkSmbScreen.formatSubtitle("Videos", "///Movies/4K///"))
        assertEquals("Videos/Movies/4K", NetworkSmbScreen.formatSubtitle("/Videos/", "///Movies/4K///"))
    }

    @Test
    fun `formats subtitle gracefully when share or browsePath is empty`() {
        assertEquals("Movies", NetworkSmbScreen.formatSubtitle("", "Movies"))
        assertEquals("Movies", NetworkSmbScreen.formatSubtitle("   ", "/Movies/"))
        assertEquals("", NetworkSmbScreen.formatSubtitle("", ""))
        assertEquals("", NetworkSmbScreen.formatSubtitle("   ", "   "))
    }

    @Test
    fun `formats subtitle as share name when browsePath contains only slashes or whitespace`() {
        assertEquals("Videos", NetworkSmbScreen.formatSubtitle("Videos", "/"))
        assertEquals("Videos", NetworkSmbScreen.formatSubtitle("Videos", "///"))
        assertEquals("Videos", NetworkSmbScreen.formatSubtitle("Videos", "   "))
        assertEquals("Videos", NetworkSmbScreen.formatSubtitle("Videos", "  /  "))
    }

    @Test
    fun `normalizePath strips leading and trailing slashes and whitespace`() {
        assertEquals("", NetworkSmbScreen.normalizePath(""))
        assertEquals("", NetworkSmbScreen.normalizePath("   "))
        assertEquals("", NetworkSmbScreen.normalizePath("/"))
        assertEquals("", NetworkSmbScreen.normalizePath("///"))
        assertEquals("sub", NetworkSmbScreen.normalizePath("sub"))
        assertEquals("sub", NetworkSmbScreen.normalizePath("/sub"))
        assertEquals("sub", NetworkSmbScreen.normalizePath("sub/"))
        assertEquals("sub", NetworkSmbScreen.normalizePath("/sub/"))
        assertEquals("sub/dir/nested", NetworkSmbScreen.normalizePath("/sub/dir/nested/"))
        assertEquals("sub/dir/nested", NetworkSmbScreen.normalizePath("  /sub/dir/nested/  "))
    }
}
