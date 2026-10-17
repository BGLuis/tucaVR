package com.tucavr.filebrowser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkDirectoryLoaderTest {
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
}
