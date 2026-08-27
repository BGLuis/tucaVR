package com.tucavr.history

import com.tucavr.navigation.PlaybackSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackHistoryTrackerTest {

    private class FakePlaybackHistoryDao : PlaybackHistoryDao {
        val saved = mutableListOf<PlaybackHistory>()

        override suspend fun upsert(entry: PlaybackHistory) {
            saved.removeAll { it.historyKey == entry.historyKey }
            saved.add(entry)
        }

        override suspend fun findByKey(key: String): PlaybackHistory? {
            return saved.firstOrNull { it.historyKey == key }
        }

        override suspend fun listRecentFirst(): List<PlaybackHistory> {
            return saved.sortedByDescending { it.lastPlayedAt }
        }

        override suspend fun deleteByKey(key: String) {
            saved.removeAll { it.historyKey == key }
        }
    }

    private class FakeClock(var nowMs: Long = 0L) {
        fun advance(deltaMs: Long) { nowMs += deltaMs }
    }

    @Test
    fun `flushProgress forces save even when throttle is closed`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeDao = FakePlaybackHistoryDao()
        val fakeClock = FakeClock(1000L)
        val throttle = PlaybackProgressThrottle(minIntervalMs = 10_000L, nowMs = { fakeClock.nowMs })

        val tracker = PlaybackHistoryTracker(fakeDao, testScope, throttle)
        val source = PlaybackSource.LocalFile("/sdcard/Movies/test.mp4", 1024L)

        tracker.startTracking(source, "Test Movie")

        // Primeiro onProgress consome a janela inicial do throttle
        tracker.onProgress(1.0f, 100.0f)
        testScheduler.advanceUntilIdle()
        assertEquals(1, fakeDao.saved.size)
        assertEquals(1000L, fakeDao.saved.first().positionMs)

        // Avança apenas 2 segundos (throttle fechado para nova gravação por 10s)
        fakeClock.advance(2000L)
        tracker.onProgress(3.0f, 100.0f)
        testScheduler.advanceUntilIdle()
        // Posição continua 1000ms porque o throttle bloqueou
        assertEquals(1000L, fakeDao.saved.first().positionMs)

        // flushProgress deve ignorar o throttle e salvar a posição atual (ex: 3.5s)
        tracker.flushProgress(3.5f, 100.0f)
        testScheduler.advanceUntilIdle()
        assertEquals(3500L, fakeDao.saved.first().positionMs)
        assertEquals(100_000L, fakeDao.saved.first().durationMs)
    }

    @Test
    fun `stopTracking clears current tracking and ignores further onProgress`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeDao = FakePlaybackHistoryDao()
        val fakeClock = FakeClock(1000L)
        val throttle = PlaybackProgressThrottle(minIntervalMs = 10_000L, nowMs = { fakeClock.nowMs })

        val tracker = PlaybackHistoryTracker(fakeDao, testScope, throttle)
        val source = PlaybackSource.LocalFile("/sdcard/Movies/test2.mp4", 2048L)

        tracker.startTracking(source, "Test Movie 2")
        tracker.stopTracking()

        // Com tracking encerrado, onProgress e flushProgress não devem gravar nada
        tracker.onProgress(5.0f, 100.0f)
        tracker.flushProgress(5.0f, 100.0f)
        testScheduler.advanceUntilIdle()

        assertEquals(0, fakeDao.saved.size)
    }

    @Test
    fun `flushProgress does not save when totalSec is zero or negative`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeDao = FakePlaybackHistoryDao()
        val throttle = PlaybackProgressThrottle()

        val tracker = PlaybackHistoryTracker(fakeDao, testScope, throttle)
        val source = PlaybackSource.LocalFile("/sdcard/Movies/test3.mp4", 1024L)

        tracker.startTracking(source, "Test Movie 3")
        tracker.flushProgress(10.0f, 0.0f)
        testScheduler.advanceUntilIdle()

        assertEquals(0, fakeDao.saved.size)
    }
}
