package com.tucavr.history

import org.junit.Assert.assertEquals
import org.junit.Test

/** T9.3/T9.4 — formatacao usada no prompt "Retomar de XX:XX?" e na lista de "Continuar assistindo". */
class PlaybackHistoryFormatTest {

    @Test
    fun `formats under a minute`() {
        assertEquals("00:00", formatDurationMs(0L))
        assertEquals("00:05", formatDurationMs(5_000L))
    }

    @Test
    fun `formats minutes and seconds under an hour`() {
        assertEquals("01:05", formatDurationMs(65_000L))
        assertEquals("59:59", formatDurationMs(59 * 60_000L + 59_000L))
    }

    @Test
    fun `formats hours once duration reaches one hour`() {
        assertEquals("1:00:00", formatDurationMs(3_600_000L))
        assertEquals("2:03:04", formatDurationMs(2 * 3_600_000L + 3 * 60_000L + 4_000L))
    }

    @Test
    fun `negative values clamp to zero instead of throwing or going negative`() {
        assertEquals("00:00", formatDurationMs(-500L))
    }

    private fun historyOf(positionMs: Long, durationMs: Long) = PlaybackHistory(
        historyKey = "k",
        title = "t",
        mediaPath = "p",
        positionMs = positionMs,
        durationMs = durationMs,
        lastPlayedAt = 0L,
        thumbnailPath = null,
        sourceType = HistorySourceType.LOCAL,
        serverInfo = null
    )

    @Test
    fun `watchedPercent computes a rounded-down percentage`() {
        assertEquals(50, watchedPercent(historyOf(500L, 1000L)))
        assertEquals(0, watchedPercent(historyOf(0L, 1000L)))
        assertEquals(99, watchedPercent(historyOf(999L, 1000L)))
    }

    @Test
    fun `watchedPercent is zero when duration is unknown, never divides by zero`() {
        assertEquals(0, watchedPercent(historyOf(500L, 0L)))
    }

    @Test
    fun `watchedPercent clamps to 100 even if position somehow exceeds duration`() {
        assertEquals(100, watchedPercent(historyOf(1500L, 1000L)))
    }
}
