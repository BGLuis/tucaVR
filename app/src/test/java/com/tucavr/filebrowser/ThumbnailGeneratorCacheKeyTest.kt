package com.tucavr.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// T5.4: cache-key logic used by ThumbnailGenerator to name cached thumbnail files
// on disk. The rest of ThumbnailGenerator (MediaMetadataRetriever, real disk I/O,
// android.graphics.Bitmap) genuinely needs a real Android runtime and is left for
// manual/on-device testing (see docs/TESTING-PLAN.md) -- but the hashing logic
// itself is pure and was extracted into `ThumbnailGenerator.cacheKeyFor` (internal)
// specifically so it could be covered here without Robolectric.
class ThumbnailGeneratorCacheKeyTest {

    private fun entry(path: String = "/sdcard/Movies/movie.mp4", size: Long = 12345L, modified: Long = 1_700_000_000L) =
        MediaEntry(name = "movie.mp4", path = path, sizeBytes = size, lastModified = modified, type = MediaType.VIDEO)

    @Test
    fun sameEntryProducesTheSameKey() {
        val a = entry()
        val b = entry()

        assertEquals(ThumbnailGenerator.cacheKeyFor(a), ThumbnailGenerator.cacheKeyFor(b))
    }

    @Test
    fun differentPathsProduceDifferentKeys() {
        val a = entry(path = "/sdcard/Movies/movie1.mp4")
        val b = entry(path = "/sdcard/Movies/movie2.mp4")

        assertNotEquals(ThumbnailGenerator.cacheKeyFor(a), ThumbnailGenerator.cacheKeyFor(b))
    }

    @Test
    fun sameOverwrittenFileWithDifferentSizeOrTimestampGetsANewKey() {
        // A file replaced/edited at the same path must not reuse a stale thumbnail --
        // this is exactly the scenario the doc's cache-invalidation note calls out.
        val original = entry(size = 1000L, modified = 1_000L)
        val edited = entry(size = 2000L, modified = 1_000L)
        val touched = entry(size = 1000L, modified = 2_000L)

        assertNotEquals(ThumbnailGenerator.cacheKeyFor(original), ThumbnailGenerator.cacheKeyFor(edited))
        assertNotEquals(ThumbnailGenerator.cacheKeyFor(original), ThumbnailGenerator.cacheKeyFor(touched))
    }

    @Test
    fun plainRenameToAnUnrelatedPathDoesNotCollideWithAnExistingKey() {
        val movieA = entry(path = "/sdcard/Movies/a.mp4", size = 500L, modified = 999L)
        val unrelatedFileWithSameSizeAndDate = entry(path = "/sdcard/Movies/totally_different.mp4", size = 500L, modified = 999L)

        assertNotEquals(
            ThumbnailGenerator.cacheKeyFor(movieA),
            ThumbnailGenerator.cacheKeyFor(unrelatedFileWithSameSizeAndDate)
        )
    }

    @Test
    fun keyIsAHexEncodedSha256Digest() {
        val key = ThumbnailGenerator.cacheKeyFor(entry())

        assertEquals(64, key.length) // SHA-256 -> 32 bytes -> 64 hex chars
        assertTrue(key.all { it.isDigit() || it in 'a'..'f' })
    }
}
