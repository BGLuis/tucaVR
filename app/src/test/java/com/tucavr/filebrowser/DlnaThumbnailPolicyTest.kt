package com.tucavr.filebrowser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class DlnaThumbnailPolicyTest {
    @Test
    fun `accepts http and https urls`() {
        assertTrue(DlnaThumbnailPolicy.isFetchableUrl("http://192.168.1.5:8200/AlbumArt/1.jpg"))
        assertTrue(DlnaThumbnailPolicy.isFetchableUrl("HTTPS://nas.local/art.png"))
    }

    @Test
    fun `rejects non-http schemes and malformed urls`() {
        listOf(
            "file:///sdcard/secret.jpg",
            "content://media/external/images/1",
            "ftp://nas/art.jpg",
            "jar:http://nas/a.jar!/x.png",
            "javascript:alert(1)",
            "//nas/art.jpg",
            "http://",
            "http://user:pass@nas/art.jpg",
            "not a url",
            "",
            "   ",
        ).forEach { assertFalse("should reject: $it", DlnaThumbnailPolicy.isFetchableUrl(it)) }
        assertFalse(DlnaThumbnailPolicy.isFetchableUrl(null))
    }

    @Test
    fun `accepts image content types with parameters`() {
        assertTrue(DlnaThumbnailPolicy.isImageContentType("image/jpeg"))
        assertTrue(DlnaThumbnailPolicy.isImageContentType("IMAGE/PNG; charset=binary"))
    }

    @Test
    fun `rejects non-image content types`() {
        listOf("text/html", "application/octet-stream", "video/mp4", "image/svg+xml", "", "imagejpeg")
            .forEach { assertFalse("should reject: $it", DlnaThumbnailPolicy.isImageContentType(it)) }
        assertFalse(DlnaThumbnailPolicy.isImageContentType(null))
    }

    @Test
    fun `rejects a response whose declared length exceeds the cap`() {
        assertFalse(DlnaThumbnailPolicy.acceptsHeaders("image/jpeg", DlnaThumbnailPolicy.MAX_BYTES + 1L))
        assertTrue(DlnaThumbnailPolicy.acceptsHeaders("image/jpeg", DlnaThumbnailPolicy.MAX_BYTES.toLong()))
    }

    @Test
    fun `accepts unknown length so the streaming cap decides`() {
        assertTrue(DlnaThumbnailPolicy.acceptsHeaders("image/jpeg", -1L))
    }

    @Test
    fun `rejects a non-image response even when small`() {
        assertFalse(DlnaThumbnailPolicy.acceptsHeaders("text/html", 100L))
        assertFalse(DlnaThumbnailPolicy.acceptsHeaders(null, 100L))
    }

    @Test
    fun `readCapped returns the whole body at the cap`() {
        val body = ByteArray(1000) { it.toByte() }
        assertArrayEquals(body, DlnaThumbnailPolicy.readCapped(ByteArrayInputStream(body), maxBytes = 1000))
    }

    @Test
    fun `readCapped rejects a body one byte over the cap`() {
        assertNull(DlnaThumbnailPolicy.readCapped(ByteArrayInputStream(ByteArray(1001)), maxBytes = 1000))
    }

    @Test
    fun `readCapped stops reading an endless stream instead of exhausting memory`() {
        var produced = 0L
        val endless =
            object : InputStream() {
                override fun read(): Int = 0.also { produced++ }

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    produced += len
                    return len
                }
            }
        assertNull(DlnaThumbnailPolicy.readCapped(endless, maxBytes = 64 * 1024))
        assertTrue("read $produced bytes", produced <= 64 * 1024 + 8 * 1024)
    }

    @Test
    fun `rejects images that would decode to too many pixels`() {
        assertTrue(DlnaThumbnailPolicy.isDecodableSize(4000, 3000))
        assertFalse(DlnaThumbnailPolicy.isDecodableSize(30_000, 30_000))
        assertFalse(DlnaThumbnailPolicy.isDecodableSize(0, 100))
        assertFalse(DlnaThumbnailPolicy.isDecodableSize(-1, -1))
    }

    @Test
    fun `sample size keeps the decoded image at least as large as the target`() {
        assertEquals(1, DlnaThumbnailPolicy.sampleSizeFor(300, 200, 512, 288))
        assertEquals(1, DlnaThumbnailPolicy.sampleSizeFor(1000, 575, 512, 288))
        assertEquals(2, DlnaThumbnailPolicy.sampleSizeFor(1024, 576, 512, 288))
        assertEquals(8, DlnaThumbnailPolicy.sampleSizeFor(4096, 2304, 512, 288))
    }
}
