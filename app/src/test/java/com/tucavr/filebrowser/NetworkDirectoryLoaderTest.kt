package com.tucavr.filebrowser

import android.content.Context
import android.content.ContextWrapper
import com.tucavr.history.HistorySourceType
import com.tucavr.history.PlaybackHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

private class FakeFolderCache(
    private val cachedEmptyPaths: Set<String> = emptySet(),
    private val probedEmptyPaths: List<String> = emptyList(),
) : FolderCache {
    val calls = mutableListOf<String>()
    val invalidatedKeys = mutableListOf<String>()

    override suspend fun invalidateFolders(
        context: Context,
        folderKeys: List<String>,
    ) {
        calls += "invalidate"
        invalidatedKeys += folderKeys
    }

    override suspend fun pruneCachedEmptyFolders(
        context: Context,
        entries: List<MediaEntry>,
        folderKeyFor: (MediaEntry) -> String,
    ): List<MediaEntry> {
        calls += "prune"
        return entries.filterNot { it.path in cachedEmptyPaths }
    }

    override suspend fun probeUncachedFoldersProgressive(
        context: Context,
        sourceKind: String,
        entries: List<MediaEntry>,
        folderKeyFor: (MediaEntry) -> String,
        scanFnFor: (MediaEntry) -> suspend () -> String,
        onFolderEmpty: suspend (emptyPath: String) -> Unit,
    ) {
        calls += "probe"
        probedEmptyPaths.forEach { onFolderEmpty(it) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkDirectoryLoaderTest {
    private val listing = "movies\t1\t0\t0\nseries\t1\t0\t0\nclip.mp4\t0\t10\t0\n"

    private fun proberConfig() =
        NetworkDirectoryLoader.FolderProberConfig(
            context = ContextWrapper(null),
            sourceKind = "smb",
            folderKeyFor = { "key:${it.path}" },
            scanFnFor = { { "1\t1" } },
        )

    private fun history(
        positionMs: Long,
        durationMs: Long,
        lastPlayedAt: Long,
    ) = PlaybackHistory(
        historyKey = "k",
        title = "t",
        mediaPath = "p",
        positionMs = positionMs,
        durationMs = durationMs,
        lastPlayedAt = lastPlayedAt,
        thumbnailPath = null,
        sourceType = HistorySourceType.SMB,
        serverInfo = null,
    )

    @Test
    fun parseTsv_parsesBasicAndMtimeColumns() {
        val testDispatcher = StandardTestDispatcher()
        val scope = TestScope(testDispatcher)
        val loader = NetworkDirectoryLoader(scope)

        val raw = "movie.mp4\t0\t1048576\t1710000000\nphotos\t1\t0\t0\nclip.mkv\t0\t2048\n"
        val result = loader.parseTsv(raw, "videos")
        assertTrue(result.isSuccess)

        val entries = result.getOrThrow()
        assertEquals(3, entries.size)

        val movie = entries[0]
        assertEquals("movie.mp4", movie.name)
        assertEquals("videos/movie.mp4", movie.path)
        assertEquals(1048576L, movie.sizeBytes)
        assertEquals(1710000000000L, movie.lastModified)
        assertEquals(MediaType.VIDEO, movie.type)

        val folder = entries[1]
        assertEquals("photos", folder.name)
        assertEquals("videos/photos", folder.path)
        assertEquals(MediaType.DIRECTORY, folder.type)
        assertEquals(0L, folder.lastModified)

        val clip = entries[2]
        assertEquals("clip.mkv", clip.name)
        assertEquals("videos/clip.mkv", clip.path)
        assertEquals(MediaType.VIDEO, clip.type)
        assertEquals(0L, clip.lastModified)
    }

    @Test
    fun loadDirectory_errorResponse_exposesErrorState() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val loader = NetworkDirectoryLoader(this, dispatcher)
            val states = mutableListOf<NetworkDirectoryState>()

            loader.loadDirectory(
                path = "root",
                fetchRaw = { "ERROR: Invalid credentials" },
                onStateChange = { states.add(it) },
            )
            advanceUntilIdle()

            assertEquals(2, states.size)
            assertEquals(NetworkDirectoryState.Loading, states[0])
            assertTrue(states[1] is NetworkDirectoryState.Error)
            assertEquals("Invalid credentials", (states[1] as NetworkDirectoryState.Error).message)
        }

    @Test
    fun loadDirectory_emptyListing_exposesEmptyState() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val loader = NetworkDirectoryLoader(this, dispatcher)
            val states = mutableListOf<NetworkDirectoryState>()

            loader.loadDirectory(
                path = "emptyFolder",
                fetchRaw = { "" },
                onStateChange = { states.add(it) },
            )
            advanceUntilIdle()

            assertEquals(2, states.size)
            assertEquals(NetworkDirectoryState.Loading, states[0])
            assertEquals(NetworkDirectoryState.Empty, states[1])
        }

    @Test
    fun loadDirectory_staleResponseDiscarded_whenNewerRequestCompletesFirst() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val loader = NetworkDirectoryLoader(this, dispatcher)
            val states = mutableListOf<NetworkDirectoryState>()
            val deferredSlowRequest = CompletableDeferred<String>()

            // Request 1: slow/stale response
            loader.loadDirectory(
                path = "folder1",
                fetchRaw = { deferredSlowRequest.await() },
                onStateChange = { states.add(it) },
            )

            // Request 2: user navigates to folder2 immediately
            loader.loadDirectory(
                path = "folder2",
                fetchRaw = { "movie2.mp4\t0\t500\t1700000000" },
                onStateChange = { states.add(it) },
            )
            advanceUntilIdle()

            // Request 2 should have completed
            val lastState = states.last()
            assertTrue(lastState is NetworkDirectoryState.Success)
            assertEquals("folder2/movie2.mp4", (lastState as NetworkDirectoryState.Success).entries.first().path)

            // Now Request 1 finishes late
            deferredSlowRequest.complete("movie1.mp4\t0\t100\t1600000000")
            advanceUntilIdle()

            // Request 1 must NOT overwrite Request 2
            val finalState = states.last()
            assertTrue(finalState is NetworkDirectoryState.Success)
            assertEquals("folder2/movie2.mp4", (finalState as NetworkDirectoryState.Success).entries.first().path)
            // Ensure no extra state emissions occurred after Request 1 completed
            assertEquals(lastState, finalState)
        }

    @Test
    fun loadDirectory_exceptionInFetchRaw_exposesErrorState() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val loader = NetworkDirectoryLoader(this, dispatcher)
            val states = mutableListOf<NetworkDirectoryState>()

            loader.loadDirectory(
                path = "failing",
                fetchRaw = { throw IllegalStateException("Connection timed out") },
                onStateChange = { states.add(it) },
            )
            advanceUntilIdle()

            assertEquals(2, states.size)
            assertEquals(NetworkDirectoryState.Loading, states[0])
            assertTrue(states[1] is NetworkDirectoryState.Error)
            assertEquals("Connection timed out", (states[1] as NetworkDirectoryState.Error).message)
        }

    @Test(timeout = 10_000)
    fun loadDirectory_blockingStaleFetchFinishingLate_isDiscarded() {
        // Production fetches are blocking JNI calls that coroutine cancellation cannot interrupt.
        val ioPool = Executors.newFixedThreadPool(2)
        try {
            runBlocking {
                val loader = NetworkDirectoryLoader(this, ioPool.asCoroutineDispatcher())
                val states = mutableListOf<NetworkDirectoryState>()
                val firstFetchStarted = CountDownLatch(1)
                val releaseFirstFetch = CountDownLatch(1)

                loader.loadDirectory(
                    path = "folder1",
                    fetchRaw = {
                        firstFetchStarted.countDown()
                        releaseFirstFetch.await()
                        "movie1.mp4\t0\t100\t1600000000"
                    },
                    onStateChange = { states.add(it) },
                )
                withContext(Dispatchers.IO) {
                    firstFetchStarted.await()
                }
                loader.loadDirectory(
                    path = "folder2",
                    fetchRaw = { "movie2.mp4\t0\t500\t1700000000" },
                    onStateChange = { states.add(it) },
                )
                while (states.none { it is NetworkDirectoryState.Success }) delay(5)

                releaseFirstFetch.countDown()
                coroutineContext[Job]!!.children.forEach { it.join() }

                val successes = states.filterIsInstance<NetworkDirectoryState.Success>()
                assertEquals(1, successes.size)
                assertEquals("folder2/movie2.mp4", successes.single().entries.single().path)
            }
        } finally {
            ioPool.shutdownNow()
        }
    }

    @Test
    fun loadDirectory_forceRefresh_invalidatesEverySubfolderOfRawListingBeforePruning() =
        runTest {
            val cache = FakeFolderCache(cachedEmptyPaths = setOf("root/series"))
            val loader = NetworkDirectoryLoader(this, StandardTestDispatcher(testScheduler), cache)

            loader.loadDirectory(
                path = "root",
                fetchRaw = { listing },
                proberConfig = proberConfig(),
                forceRefresh = true,
                onStateChange = {},
            )
            advanceUntilIdle()

            assertEquals(listOf("key:root/movies", "key:root/series"), cache.invalidatedKeys)
            assertEquals(listOf("invalidate", "prune", "probe"), cache.calls)
        }

    @Test
    fun loadDirectory_withoutForceRefresh_keepsCachedFolderStatus() =
        runTest {
            val cache = FakeFolderCache()
            val loader = NetworkDirectoryLoader(this, StandardTestDispatcher(testScheduler), cache)

            loader.loadDirectory(
                path = "root",
                fetchRaw = { listing },
                proberConfig = proberConfig(),
                onStateChange = {},
            )
            advanceUntilIdle()

            assertTrue(cache.invalidatedKeys.isEmpty())
            assertEquals(listOf("prune", "probe"), cache.calls)
        }

    @Test
    fun loadDirectory_backgroundPruningRemovesRowsUntilUserInteracts() =
        runTest {
            val cache = FakeFolderCache(probedEmptyPaths = listOf("root/movies"))
            val loader = NetworkDirectoryLoader(this, StandardTestDispatcher(testScheduler), cache)

            val untouched = mutableListOf<NetworkDirectoryState>()
            loader.loadDirectory("root", { listing }, proberConfig(), onStateChange = { untouched.add(it) })
            advanceUntilIdle()
            val afterPruning = untouched.last() as NetworkDirectoryState.Success
            assertEquals(listOf("series", "clip.mp4"), afterPruning.entries.map { it.name })

            val touched = mutableListOf<NetworkDirectoryState>()
            loader.loadDirectory(
                path = "root",
                fetchRaw = { listing },
                proberConfig = proberConfig(),
                onStateChange = { state ->
                    touched.add(state)
                    if (state is NetworkDirectoryState.Success) loader.markUserInteracted()
                },
            )
            advanceUntilIdle()
            val frozen = touched.last() as NetworkDirectoryState.Success
            assertEquals(listOf("movies", "series", "clip.mp4"), frozen.entries.map { it.name })
        }

    @Test
    fun loadDirectory_historyFor_enrichesVideosOnly() =
        runTest {
            val loader = NetworkDirectoryLoader(this, StandardTestDispatcher(testScheduler))
            val queried = mutableListOf<String>()
            var result: NetworkDirectoryState? = null

            loader.loadDirectory(
                path = "root",
                fetchRaw = { listing },
                historyFor = { entry ->
                    queried.add(entry.path)
                    history(positionMs = 30_000, durationMs = 120_000, lastPlayedAt = 999L)
                },
                onStateChange = { result = it },
            )
            advanceUntilIdle()

            assertEquals(listOf("root/clip.mp4"), queried)
            val entries = (result as NetworkDirectoryState.Success).entries
            val clip = entries.single { it.name == "clip.mp4" }
            assertEquals(0.25f, clip.progressFraction!!, 0.0001f)
            assertEquals(999L, clip.lastPlayedAt)
            assertNull(entries.single { it.name == "movies" }.lastPlayedAt)
        }

    @Test
    fun withHistory_noHistoryOrZeroDuration_leavesProgressUnset() {
        val video = MediaEntry("a.mp4", "a.mp4", 1, 0, MediaType.VIDEO)

        assertEquals(video, video.withHistory(null))
        val noDuration = video.withHistory(history(positionMs = 5, durationMs = 0, lastPlayedAt = 7L))
        assertNull(noDuration.progressFraction)
        assertEquals(7L, noDuration.lastPlayedAt)
    }

    @Test
    fun lastPlayedSort_ordersNetworkEntriesByHistoryAndPutsNeverPlayedLast() {
        val loader = NetworkDirectoryLoader(TestScope())
        val parsed = loader.parseTsv("old.mp4\t0\t1\t0\nnever.mp4\t0\t1\t0\nrecent.mp4\t0\t1\t0\n", "").getOrThrow()
        val lastPlayedByName = mapOf("old.mp4" to 100L, "recent.mp4" to 900L)

        val enriched =
            parsed.map { it.withHistory(lastPlayedByName[it.name]?.let { at -> history(10, 100, at) }) }
        val sorted = sortMediaEntries(enriched, SortBy.LAST_PLAYED, ascending = false)

        assertEquals(listOf("recent.mp4", "old.mp4", "never.mp4"), sorted.map { it.name })
    }

    @Test
    fun dateSort_ordersNetworkEntriesByParsedMtime() {
        val loader = NetworkDirectoryLoader(TestScope())
        val parsed = loader.parseTsv("b.mp4\t0\t1\t1710000000\na.mp4\t0\t1\t1700000000\nc.mp4\t0\t1\t1720000000\n", "").getOrThrow()

        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4"), sortMediaEntries(parsed, SortBy.DATE, ascending = true).map { it.name })
        assertEquals(listOf("c.mp4", "b.mp4", "a.mp4"), sortMediaEntries(parsed, SortBy.DATE, ascending = false).map { it.name })
    }

    @Test
    fun canReuseBrowser_requiresAttachedRootAndSameSort() {
        val shown = FolderConfig(sortBy = SortBy.NAME, ascending = true, viewMode = ViewMode.GRID)

        assertTrue(canReuseBrowser(true, true, shown, shown.copy(viewMode = ViewMode.LIST)))
        assertFalse("root detached after leaving the screen", canReuseBrowser(true, false, shown, shown))
        assertFalse("different server", canReuseBrowser(false, true, shown, shown))
        assertFalse("toolbar would show stale sort", canReuseBrowser(true, true, shown, shown.copy(sortBy = SortBy.DATE)))
        assertFalse("toolbar would show stale direction", canReuseBrowser(true, true, shown, shown.copy(ascending = false)))
    }
}
