package com.tucavr.history

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryFilterTest {

    private fun history(positionMs: Long, durationMs: Long) = PlaybackHistory(
        historyKey = "key",
        title = "title",
        mediaPath = "/path/test.mp4",
        positionMs = positionMs,
        durationMs = durationMs,
        lastPlayedAt = 1000L,
        thumbnailPath = null,
        sourceType = HistorySourceType.LOCAL,
        serverInfo = null
    )

    @Test
    fun nonResumableWhenNearStart() {
        // Abaixo de 5s não é considerado retomável
        val entry = history(positionMs = 3_000L, durationMs = 60_000L)
        assertFalse(entry.isResumable())
    }

    @Test
    fun nonResumableWhenNearEnd() {
        // Acima de 97% da duração é considerado terminado
        val entry = history(positionMs = 98_000L, durationMs = 100_000L)
        assertFalse(entry.isResumable())
    }

    @Test
    fun nonResumableWhenDurationIsZeroOrNegative() {
        val entry1 = history(positionMs = 10_000L, durationMs = 0L)
        val entry2 = history(positionMs = 10_000L, durationMs = -1L)
        assertFalse(entry1.isResumable())
        assertFalse(entry2.isResumable())
    }

    @Test
    fun resumableWhenInProgress() {
        // Ex: assistiu 30s de 100s (30%)
        val entry = history(positionMs = 30_000L, durationMs = 100_000L)
        assertTrue(entry.isResumable())
    }
}
