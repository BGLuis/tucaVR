package com.tucavr.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Test

// T13.3: alvo de captura ~10% da duracao (doc da fase 0.2, secao 13), clampado
// em [1s, 30s] -- mesma heuristica do lado Rust (core::thumbnail::primary_seek_target_us),
// aqui exercitada isoladamente porque o resto de ThumbnailGenerator precisa de
// MediaMetadataRetriever/Bitmap reais (ver docs/TESTING-PLAN.md).
class ThumbnailGeneratorTargetTimeTest {

    @Test
    fun tenPercentOfADurationThatFallsWithinTheClampRange() {
        val threeMinutesMs = 3 * 60 * 1000L
        assertEquals(18_000_000L, ThumbnailGenerator.targetTimeUs(threeMinutesMs))
    }

    @Test
    fun tenPercentOfATypicalMovieDurationClampsToTheSixtySecondMaximum() {
        val twoHoursMs = 2 * 60 * 60 * 1000L
        assertEquals(60_000_000L, ThumbnailGenerator.targetTimeUs(twoHoursMs))
    }

    @Test
    fun shortClipClampsToTheFiveSecondMinimum() {
        val fiveSecondsMs = 5_000L
        assertEquals(5_000_000L, ThumbnailGenerator.targetTimeUs(fiveSecondsMs))
    }

    @Test
    fun veryLongVideoClampsToTheSixtySecondMaximum() {
        val eightHoursMs = 8 * 60 * 60 * 1000L
        assertEquals(60_000_000L, ThumbnailGenerator.targetTimeUs(eightHoursMs))
    }

    @Test
    fun unknownDurationFallsBackToTheFiveSecondMinimum() {
        assertEquals(5_000_000L, ThumbnailGenerator.targetTimeUs(0L))
        assertEquals(5_000_000L, ThumbnailGenerator.targetTimeUs(-1L))
    }
}
