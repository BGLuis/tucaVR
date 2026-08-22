package com.vrplayer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaItemParsingTest {

    data class DlnaItem(
        val id: String,
        val title: String,
        val isContainer: Boolean,
        val url: String?,
        val sizeBytes: Long,
        val durationSec: Double,
        val resolution: String?,
        val thumbnailUrl: String?
    )

    private fun parseDlnaItems(raw: String): List<DlnaItem> {
        if (raw.isBlank() || raw.startsWith("ERROR:")) {
            return emptyList()
        }

        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("\t")
            val id = parts.getOrNull(0) ?: return@mapNotNull null
            val title = parts.getOrNull(1) ?: "Sem título"
            val isContainer = parts.getOrNull(2) == "1"
            val url = parts.getOrNull(3)?.ifEmpty { null }
            val size = parts.getOrNull(4)?.toLongOrNull()?.takeIf { it >= 0 } ?: 0L
            val dur = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0
            val resolution = parts.getOrNull(6)?.ifEmpty { null }
            val thumb = parts.getOrNull(7)?.ifEmpty { null }

            DlnaItem(
                id = id,
                title = title,
                isContainer = isContainer,
                url = url,
                sizeBytes = size,
                durationSec = dur,
                resolution = resolution,
                thumbnailUrl = thumb
            )
        }
    }

    @Test
    fun testParseDlnaItems() {
        val raw = "1$0\tVideos\t1\t\t-1\t\t\t\n" +
                "1$14\tBig Buck Bunny\t0\thttp://192.168.1.100:8200/MediaItems/14.mp4\t52428800\t596.00\t1920x1080\thttp://192.168.1.100:8200/MediaItems/14.jpg\n"

        val items = parseDlnaItems(raw)
        assertEquals(2, items.size)

        val folder = items[0]
        assertEquals("1$0", folder.id)
        assertEquals("Videos", folder.title)
        assertTrue(folder.isContainer)
        assertEquals(null, folder.url)

        val video = items[1]
        assertEquals("1$14", video.id)
        assertEquals("Big Buck Bunny", video.title)
        assertFalse(video.isContainer)
        assertEquals("http://192.168.1.100:8200/MediaItems/14.mp4", video.url)
        assertEquals(52428800L, video.sizeBytes)
        assertEquals(596.00, video.durationSec, 0.01)
        assertEquals("1920x1080", video.resolution)
        assertEquals("http://192.168.1.100:8200/MediaItems/14.jpg", video.thumbnailUrl)
    }

    @Test
    fun testParseErrorResponse() {
        val raw = "ERROR:Failed to connect to DLNA server"
        val items = parseDlnaItems(raw)
        assertTrue(items.isEmpty())
    }
}
