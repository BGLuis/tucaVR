package com.tucavr.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderPreviewGeneratorSummaryTest {
    @Test
    fun folderSummaryCorrectlyReportsCountsAndFirstVideo() {
        val entry1 = MediaEntry("vid1.mp4", "/path/vid1.mp4", 100, 100, MediaType.VIDEO, format3DHint = Format3DType.SBS)
        val entry2 = MediaEntry("vid2.mp4", "/path/vid2.mp4", 200, 100, MediaType.VIDEO, format3DHint = Format3DType.VR_180)
        val entry3 = MediaEntry("vid3.mp4", "/path/vid3.mp4", 300, 100, MediaType.VIDEO, format3DHint = Format3DType.VR_360)
        val entry4 = MediaEntry("vid4.mp4", "/path/vid4.mp4", 400, 100, MediaType.VIDEO, format3DHint = Format3DType.OU)

        val summary =
            FolderSummary(
                totalItems = 10,
                videoCount = 4,
                audioCount = 3,
                imageCount = 3,
                previewEntries = listOf(entry1, entry2, entry3, entry4),
                available3DFormats = setOf(Format3DType.SBS, Format3DType.VR_180, Format3DType.VR_360, Format3DType.OU),
                hasPlayableMediaWithinDepth = true,
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
        val summary =
            FolderSummary(
                totalItems = 0,
                videoCount = 0,
                audioCount = 0,
                imageCount = 0,
                previewEntries = emptyList(),
                available3DFormats = emptySet(),
                hasPlayableMediaWithinDepth = false,
            )

        assertNull(summary.firstVideo)
        assertEquals(0, summary.previewEntries.size)
    }

    private fun entry(
        name: String,
        type: MediaType,
    ) = MediaEntry(name, "/dir/$name", 1, 1, type)

    @Test
    fun photoOnlyFolderGetsImagePreviewEntries() {
        val photos = (1..6).map { entry("p$it.jpg", MediaType.IMAGE) }

        val selected = FolderPreviewGenerator.selectPreviewEntries(photos)

        assertEquals(photos.take(4), selected)
    }

    @Test
    fun previewPrefersVideosThenFillsWithImagesUpToCap() {
        val candidates =
            listOf(
                entry("a.jpg", MediaType.IMAGE),
                entry("b.jpg", MediaType.IMAGE),
                entry("v1.mp4", MediaType.VIDEO),
                entry("c.jpg", MediaType.IMAGE),
                entry("v2.mp4", MediaType.VIDEO),
            )

        val selected = FolderPreviewGenerator.selectPreviewEntries(candidates)

        assertEquals(listOf("v1.mp4", "v2.mp4", "a.jpg", "b.jpg"), selected.map { it.name })
    }

    @Test
    fun previewIgnoresNonVisualEntries() {
        val candidates = listOf(entry("song.mp3", MediaType.AUDIO), entry("sub", MediaType.DIRECTORY))

        assertEquals(emptyList<MediaEntry>(), FolderPreviewGenerator.selectPreviewEntries(candidates))
    }

    @Test
    fun firstVideoSkipsImagePreviewEntries() {
        val image = entry("a.jpg", MediaType.IMAGE)
        val video = entry("v.mp4", MediaType.VIDEO)
        val summary =
            FolderSummary(
                totalItems = 2,
                videoCount = 1,
                audioCount = 0,
                imageCount = 1,
                previewEntries = listOf(image, video),
                available3DFormats = emptySet(),
                hasPlayableMediaWithinDepth = true,
            )

        assertEquals(video, summary.firstVideo)
    }
}
