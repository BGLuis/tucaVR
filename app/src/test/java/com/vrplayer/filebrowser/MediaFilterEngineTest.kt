package com.vrplayer.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFilterEngineTest {

    @Test
    fun detects3DFormatsFromFilename() {
        assertEquals(Format3DType.SBS, MediaFilterEngine.detectFormat3DFromFilename("Avatar.3D-SBS.1080p.mkv"))
        assertEquals(Format3DType.SBS, MediaFilterEngine.detectFormat3DFromFilename("movie.half-sbs.mp4"))
        assertEquals(Format3DType.SBS, MediaFilterEngine.detectFormat3DFromFilename("clip.hsbs.mp4"))
        assertEquals(Format3DType.SBS, MediaFilterEngine.detectFormat3DFromFilename("travel_side-by-side.mp4"))

        assertEquals(Format3DType.OU, MediaFilterEngine.detectFormat3DFromFilename("Dune.3D-OU.mkv"))
        assertEquals(Format3DType.OU, MediaFilterEngine.detectFormat3DFromFilename("concert.top-bottom.mp4"))
        assertEquals(Format3DType.OU, MediaFilterEngine.detectFormat3DFromFilename("video.half-ou.mp4"))

        assertEquals(Format3DType.VR_180, MediaFilterEngine.detectFormat3DFromFilename("rollercoaster_180_sbs.mp4"))
        assertEquals(Format3DType.VR_180, MediaFilterEngine.detectFormat3DFromFilename("space_vr180.mp4"))

        assertEquals(Format3DType.VR_360, MediaFilterEngine.detectFormat3DFromFilename("skydiving_360.mp4"))
        assertEquals(Format3DType.VR_360, MediaFilterEngine.detectFormat3DFromFilename("paris_equirectangular.mp4"))
        assertEquals(Format3DType.VR_360, MediaFilterEngine.detectFormat3DFromFilename("vr360_expedition.mp4"))

        assertEquals(Format3DType.FLAT_2D, MediaFilterEngine.detectFormat3DFromFilename("plain_movie.mp4"))
    }

    @Test
    fun matchesSearchCaseInsensitiveAndMultiToken() {
        assertTrue(MediaFilterEngine.matchesSearch("Big Buck Bunny.mp4", "buck"))
        assertTrue(MediaFilterEngine.matchesSearch("Big Buck Bunny.mp4", "BUNNY"))
        assertTrue(MediaFilterEngine.matchesSearch("Big Buck Bunny.mp4", "big bunny"))
        assertTrue(MediaFilterEngine.matchesSearch("Big Buck Bunny.mp4", "bunny big"))

        // Tolerância de digitação (fuzzy)
        assertTrue(MediaFilterEngine.matchesSearch("Matrix Reloaded.mkv", "matrx"))
        assertFalse(MediaFilterEngine.matchesSearch("Big Buck Bunny.mp4", "batman"))
    }

    @Test
    fun findsHighlightRanges() {
        val text = "The Matrix Reloaded Matrix"
        val ranges = MediaFilterEngine.findHighlightRanges(text, "matrix")

        assertEquals(2, ranges.size)
        assertEquals(4 until 10, ranges[0])
        assertEquals(20 until 26, ranges[1])
    }

    @Test
    fun filtersByMediaType() {
        val video = MediaEntry("video.mp4", "/path/video.mp4", 1000, 1000, MediaType.VIDEO)
        val audio = MediaEntry("song.mp3", "/path/song.mp3", 500, 1000, MediaType.AUDIO)
        val image = MediaEntry("photo.jpg", "/path/photo.jpg", 200, 1000, MediaType.IMAGE)
        val dir = MediaEntry("folder", "/path/folder", 0, 1000, MediaType.DIRECTORY)

        assertTrue(MediaFilterEngine.matchesFilter(video, "", typeFilter = MediaTypeFilter.VIDEO))
        assertFalse(MediaFilterEngine.matchesFilter(audio, "", typeFilter = MediaTypeFilter.VIDEO))
        assertTrue(MediaFilterEngine.matchesFilter(dir, "", typeFilter = MediaTypeFilter.VIDEO)) // Pastas permitidas para navegação

        assertTrue(MediaFilterEngine.matchesFilter(audio, "", typeFilter = MediaTypeFilter.AUDIO))
        assertFalse(MediaFilterEngine.matchesFilter(video, "", typeFilter = MediaTypeFilter.AUDIO))

        assertTrue(MediaFilterEngine.matchesFilter(image, "", typeFilter = MediaTypeFilter.IMAGE))
    }

    @Test
    fun filtersByFormat3D() {
        val sbsEntry = MediaEntry("movie.sbs.mp4", "/path", 1000, 1000, MediaType.VIDEO, format3DHint = Format3DType.SBS)
        val flatEntry = MediaEntry("movie.2d.mp4", "/path", 1000, 1000, MediaType.VIDEO, format3DHint = Format3DType.FLAT_2D)
        val vr180Entry = MediaEntry("clip.180.mp4", "/path", 1000, 1000, MediaType.VIDEO, format3DHint = Format3DType.VR_180)

        assertTrue(MediaFilterEngine.matchesFilter(sbsEntry, "", format3DFilter = Format3DFilter.SBS))
        assertFalse(MediaFilterEngine.matchesFilter(flatEntry, "", format3DFilter = Format3DFilter.SBS))

        assertTrue(MediaFilterEngine.matchesFilter(vr180Entry, "", format3DFilter = Format3DFilter.VR_180))
        assertFalse(MediaFilterEngine.matchesFilter(sbsEntry, "", format3DFilter = Format3DFilter.VR_180))
    }

    @Test
    fun filtersByDate() {
        val now = 1_700_000_000_000L // Timestamp realista em millis
        val recentEntry = MediaEntry("recent.mp4", "/path", 1000, lastModified = now - 3600 * 1000L, type = MediaType.VIDEO)
        val oldEntry = MediaEntry("old.mp4", "/path", 1000, lastModified = now - 40L * 24 * 3600 * 1000L, type = MediaType.VIDEO)

        assertTrue(MediaFilterEngine.matchesFilter(recentEntry, "", dateFilter = DateFilter.RECENT_24H, nowMs = now))
        assertFalse(MediaFilterEngine.matchesFilter(oldEntry, "", dateFilter = DateFilter.RECENT_24H, nowMs = now))
        assertFalse(MediaFilterEngine.matchesFilter(oldEntry, "", dateFilter = DateFilter.LAST_30_DAYS, nowMs = now))
        assertTrue(MediaFilterEngine.matchesFilter(oldEntry, "", dateFilter = DateFilter.ALL, nowMs = now))
    }
}
