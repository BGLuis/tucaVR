package com.vrplayer.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FolderPreviewGeneratorSummaryTest {

    @Test
    fun folderSummaryCorrectlyReportsCountsAndFirstVideo() {
        val entry1 = MediaEntry("vid1.mp4", "/path/vid1.mp4", 100, 100, MediaType.VIDEO, format3DHint = Format3DType.SBS)
        val entry2 = MediaEntry("vid2.mp4", "/path/vid2.mp4", 200, 100, MediaType.VIDEO, format3DHint = Format3DType.VR_180)
        val entry3 = MediaEntry("vid3.mp4", "/path/vid3.mp4", 300, 100, MediaType.VIDEO, format3DHint = Format3DType.VR_360)
        val entry4 = MediaEntry("vid4.mp4", "/path/vid4.mp4", 400, 100, MediaType.VIDEO, format3DHint = Format3DType.OU)

        val summary = FolderSummary(
            totalItems = 10,
            videoCount = 4,
            audioCount = 3,
            imageCount = 3,
            previewEntries = listOf(entry1, entry2, entry3, entry4),
            available3DFormats = setOf(Format3DType.SBS, Format3DType.VR_180, Format3DType.VR_360, Format3DType.OU)
        )

        assertEquals(10, summary.totalItems)
        assertEquals(4, summary.videoCount)
        assertEquals(3, summary.audioCount)
        assertEquals(3, summary.imageCount)
        assertEquals(4, summary.previewEntries.size)
        assertEquals(entry1, summary.firstVideo)
        assertEquals(4, summary.available3DFormats.size)
    }

    @Test
    fun emptyFolderSummaryReportsNullFirstVideo() {
        val summary = FolderSummary(
            totalItems = 0,
            videoCount = 0,
            audioCount = 0,
            imageCount = 0,
            previewEntries = emptyList(),
            available3DFormats = emptySet()
        )

        assertNull(summary.firstVideo)
        assertEquals(0, summary.previewEntries.size)
    }
}
