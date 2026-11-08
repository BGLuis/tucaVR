package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ControlsViewCache] guarding player controls view updates (Issue #152).
 */
class ControlsViewCacheTest {
    private lateinit var cache: ControlsViewCache

    @Before
    fun setUp() {
        cache = ControlsViewCache()
    }

    @Test
    fun `initial cache has all null values`() {
        assertNull(cache.lastProgress)
        assertNull(cache.lastTimeText)
        assertNull(cache.lastTotalTimeText)
        assertNull(cache.lastPlaying)
    }

    @Test
    fun `first progress update writes all components and caches values`() {
        assertTrue(cache.shouldWriteProgress(250))
        assertTrue(cache.shouldWriteTime("00:15"))
        assertTrue(cache.shouldWriteTotalTime("01:00:00"))

        assertEquals(250, cache.lastProgress)
        assertEquals("00:15", cache.lastTimeText)
        assertEquals("01:00:00", cache.lastTotalTimeText)
    }

    @Test
    fun `repeated updateProgress calls with same values produce no writes`() {
        // First tick
        assertTrue(cache.shouldWriteProgress(250))
        assertTrue(cache.shouldWriteTime("00:15"))
        assertTrue(cache.shouldWriteTotalTime("01:00:00"))

        // Subsequent ticks within the same second and same progress
        assertFalse(cache.shouldWriteProgress(250))
        assertFalse(cache.shouldWriteTime("00:15"))
        assertFalse(cache.shouldWriteTotalTime("01:00:00"))

        // Another tick still in the same second
        assertFalse(cache.shouldWriteProgress(250))
        assertFalse(cache.shouldWriteTime("00:15"))
        assertFalse(cache.shouldWriteTotalTime("01:00:00"))
    }

    @Test
    fun `updateProgress writes when second or seekbar progress changes`() {
        cache.shouldWriteProgress(250)
        cache.shouldWriteTime("00:15")
        cache.shouldWriteTotalTime("01:00:00")

        // Progress changed slightly, but same second
        assertTrue(cache.shouldWriteProgress(255))
        assertFalse(cache.shouldWriteTime("00:15"))
        assertFalse(cache.shouldWriteTotalTime("01:00:00"))

        // Next second reached
        assertTrue(cache.shouldWriteProgress(270))
        assertTrue(cache.shouldWriteTime("00:16"))
        assertFalse(cache.shouldWriteTotalTime("01:00:00"))
    }

    @Test
    fun `repeated updateMediaState with unchanged isPlaying produces no writes`() {
        // Initial state
        assertTrue(cache.shouldWritePlaying(true))
        assertEquals(true, cache.lastPlaying)

        // Subsequent calls with same state produce no writes
        assertFalse(cache.shouldWritePlaying(true))
        assertFalse(cache.shouldWritePlaying(true))

        // State changes to paused
        assertTrue(cache.shouldWritePlaying(false))
        assertEquals(false, cache.lastPlaying)
        assertFalse(cache.shouldWritePlaying(false))
    }

    @Test
    fun `after reset cached values are cleared and the first update writes again`() {
        // Populate cache
        cache.shouldWriteProgress(500)
        cache.shouldWriteTime("00:30")
        cache.shouldWriteTotalTime("02:00:00")
        cache.shouldWritePlaying(true)

        // Reset (simulates new media or resetPlaybackState)
        cache.reset()

        assertNull(cache.lastProgress)
        assertNull(cache.lastTimeText)
        assertNull(cache.lastTotalTimeText)
        assertNull(cache.lastPlaying)

        // The first update after reset must write again, even with same or reset values
        assertTrue(cache.shouldWriteProgress(0))
        assertTrue(cache.shouldWriteTime("00:00"))
        assertTrue(cache.shouldWriteTotalTime("02:00:00"))
        assertTrue(cache.shouldWritePlaying(false))

        // Subsequent updates with same values produce no writes again
        assertFalse(cache.shouldWriteProgress(0))
        assertFalse(cache.shouldWriteTime("00:00"))
        assertFalse(cache.shouldWriteTotalTime("02:00:00"))
        assertFalse(cache.shouldWritePlaying(false))
    }
}
