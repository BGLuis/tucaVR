package com.tucavr.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Test

// T5.6: sortMediaEntries — nome/data/tamanho/tipo, diretorios sempre antes de
// arquivos (qualquer que seja o modo/direcao de ordenacao).
class MediaSorterTest {

    private fun entry(
        name: String,
        type: MediaType = MediaType.VIDEO,
        sizeBytes: Long = 0,
        lastModified: Long = 0
    ) = MediaEntry(name = name, path = "/root/$name", sizeBytes = sizeBytes, lastModified = lastModified, type = type)

    @Test
    fun sortsByNameAscendingCaseInsensitive() {
        val entries = listOf(entry("Banana.mp4"), entry("apple.mp4"), entry("cherry.mp4"))

        val sorted = sortMediaEntries(entries, SortBy.NAME, ascending = true)

        assertEquals(listOf("apple.mp4", "Banana.mp4", "cherry.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortsByNameDescending() {
        val entries = listOf(entry("apple.mp4"), entry("Banana.mp4"), entry("cherry.mp4"))

        val sorted = sortMediaEntries(entries, SortBy.NAME, ascending = false)

        assertEquals(listOf("cherry.mp4", "Banana.mp4", "apple.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortsByDateAscending() {
        val entries = listOf(
            entry("newest.mp4", lastModified = 300),
            entry("oldest.mp4", lastModified = 100),
            entry("middle.mp4", lastModified = 200)
        )

        val sorted = sortMediaEntries(entries, SortBy.DATE, ascending = true)

        assertEquals(listOf("oldest.mp4", "middle.mp4", "newest.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortsByDateDescending() {
        val entries = listOf(
            entry("newest.mp4", lastModified = 300),
            entry("oldest.mp4", lastModified = 100),
            entry("middle.mp4", lastModified = 200)
        )

        val sorted = sortMediaEntries(entries, SortBy.DATE, ascending = false)

        assertEquals(listOf("newest.mp4", "middle.mp4", "oldest.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortsBySizeAscending() {
        val entries = listOf(entry("big.mp4", sizeBytes = 3000), entry("small.mp4", sizeBytes = 10), entry("medium.mp4", sizeBytes = 500))

        val sorted = sortMediaEntries(entries, SortBy.SIZE, ascending = true)

        assertEquals(listOf("small.mp4", "medium.mp4", "big.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortsByType() {
        val entries = listOf(
            entry("song.mp3", type = MediaType.AUDIO),
            entry("clip.mp4", type = MediaType.VIDEO),
            entry("pic.jpg", type = MediaType.IMAGE)
        )

        val sorted = sortMediaEntries(entries, SortBy.TYPE, ascending = true)

        // Alphabetical by enum name: AUDIO < IMAGE < VIDEO.
        assertEquals(listOf("song.mp3", "pic.jpg", "clip.mp4"), sorted.map { it.name })
    }

    @Test
    fun directoriesAlwaysComeBeforeFilesRegardlessOfSortMode() {
        val entries = listOf(
            entry("zzz_file.mp4", type = MediaType.VIDEO),
            entry("aaa_dir", type = MediaType.DIRECTORY),
            entry("mmm_file.mp4", type = MediaType.VIDEO)
        )

        val sorted = sortMediaEntries(entries, SortBy.NAME, ascending = true)

        assertEquals("aaa_dir", sorted.first().name)
    }

    @Test
    fun directoriesStayFirstEvenWhenDescending() {
        val entries = listOf(
            entry("zzz_dir", type = MediaType.DIRECTORY),
            entry("aaa_file.mp4", type = MediaType.VIDEO)
        )

        val sorted = sortMediaEntries(entries, SortBy.NAME, ascending = false)

        // Descending by name would normally put "zzz_dir" before "aaa_file.mp4" anyway
        // here, so also check with names that would reverse the expected order if
        // directories-first weren't enforced.
        val entries2 = listOf(
            entry("aaa_dir", type = MediaType.DIRECTORY),
            entry("zzz_file.mp4", type = MediaType.VIDEO)
        )
        val sorted2 = sortMediaEntries(entries2, SortBy.NAME, ascending = false)

        assertEquals("zzz_dir", sorted.first().name)
        assertEquals("aaa_dir", sorted2.first().name)
    }

    @Test
    fun multipleDirectoriesAreThemselvesSortedAmongEachOther() {
        val entries = listOf(
            entry("zeta_dir", type = MediaType.DIRECTORY),
            entry("alpha_dir", type = MediaType.DIRECTORY),
            entry("file.mp4", type = MediaType.VIDEO)
        )

        val sorted = sortMediaEntries(entries, SortBy.NAME, ascending = true)

        assertEquals(listOf("alpha_dir", "zeta_dir", "file.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortsByLastPlayedAscendingAndDescending() {
        val entries = listOf(
            MediaEntry("old_played.mp4", "/path/1", 100, 100, MediaType.VIDEO, lastPlayedAt = 1000L),
            MediaEntry("recent_played.mp4", "/path/2", 100, 100, MediaType.VIDEO, lastPlayedAt = 5000L),
            MediaEntry("never_played.mp4", "/path/3", 100, 100, MediaType.VIDEO, lastPlayedAt = null),
            MediaEntry("my_dir", "/path/dir", 0, 100, MediaType.DIRECTORY)
        )

        val sortedDesc = sortMediaEntries(entries, SortBy.LAST_PLAYED, ascending = false)
        assertEquals(listOf("my_dir", "recent_played.mp4", "old_played.mp4", "never_played.mp4"), sortedDesc.map { it.name })

        val sortedAsc = sortMediaEntries(entries, SortBy.LAST_PLAYED, ascending = true)
        assertEquals(listOf("my_dir", "never_played.mp4", "old_played.mp4", "recent_played.mp4"), sortedAsc.map { it.name })
    }
}
