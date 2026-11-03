package com.tucavr.history

import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.FtpServer
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryAvailabilityTest {
    private val server = FtpServer(id = "ftp-1", name = "NAS", host = "10.0.0.1", port = 21, username = "u", password = "p")

    private fun entry(
        key: String,
        sourceType: HistorySourceType,
        mediaPath: String,
        serverInfo: String? = """{"serverId":"ftp-1"}""",
    ) = PlaybackHistory(
        historyKey = key,
        title = key,
        mediaPath = mediaPath,
        positionMs = 10_000L,
        durationMs = 60_000L,
        lastPlayedAt = 1L,
        thumbnailPath = null,
        sourceType = sourceType,
        serverInfo = serverInfo,
    )

    private fun ftp(
        key: String,
        path: String,
    ) = entry(key, HistorySourceType.FTP, path)

    private suspend fun TestScope.filter(
        entries: List<PlaybackHistory>,
        localFileExists: (String) -> Boolean = { true },
        resolveRemote: suspend (PlaybackHistory) -> PlaybackSource? = { PlaybackSource.Ftp(server, it.mediaPath, 0L) },
        listDirectory: suspend (PlaybackSource, String) -> String = { _, _ -> "" },
    ): List<String> =
        HistoryAvailability.filterAvailableInternal(
            entries = entries,
            localFileExists = localFileExists,
            resolveRemote = resolveRemote,
            listDirectory = listDirectory,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ).map { it.historyKey }

    @Test
    fun `hides a local entry whose file was deleted and keeps the one still on disk`() =
        runTest {
            val entries =
                listOf(
                    entry("gone", HistorySourceType.LOCAL, "/sdcard/gone.mp4", serverInfo = null),
                    entry("kept", HistorySourceType.LOCAL, "/sdcard/kept.mp4", serverInfo = null),
                )

            assertEquals(listOf("kept"), filter(entries, localFileExists = { it == "/sdcard/kept.mp4" }))
        }

    @Test
    fun `hides a remote entry missing from a successful listing of its folder`() =
        runTest {
            val entries = listOf(ftp("gone", "movies/gone.mp4"), ftp("kept", "movies/kept.mp4"))

            val visible = filter(entries, listDirectory = { _, _ -> "kept.mp4\t0\t100\t5\nother.mkv\t0\t1" })

            assertEquals(listOf("kept"), visible)
        }

    @Test
    fun `hides a remote entry when its folder lists successfully but empty`() =
        runTest {
            assertEquals(emptyList<String>(), filter(listOf(ftp("gone", "movies/gone.mp4")), listDirectory = { _, _ -> "" }))
        }

    @Test
    fun `hides a remote entry whose saved server was removed, since resuming it is impossible`() =
        runTest {
            assertEquals(emptyList<String>(), filter(listOf(ftp("orphan", "movies/a.mp4")), resolveRemote = { null }))
        }

    @Test
    fun `keeps a remote entry when the server answers with an error`() =
        runTest {
            val visible = filter(listOf(ftp("kept", "movies/a.mp4")), listDirectory = { _, _ -> "ERROR:connection refused" })

            assertEquals(listOf("kept"), visible)
        }

    @Test
    fun `keeps a remote entry when the listing throws`() =
        runTest {
            val visible = filter(listOf(ftp("kept", "movies/a.mp4")), listDirectory = { _, _ -> error("jni failure") })

            assertEquals(listOf("kept"), visible)
        }

    @Test
    fun `keeps a remote entry when the server does not answer before the deadline`() =
        runTest {
            val visible = filter(listOf(ftp("kept", "movies/a.mp4")), listDirectory = { _, _ -> awaitCancellation() })

            assertEquals(listOf("kept"), visible)
        }

    @Test
    fun `lists each remote folder once, using the parent path of the media`() =
        runTest {
            val listedDirs = mutableListOf<String>()
            val entries = listOf(ftp("a", "movies/a.mp4"), ftp("b", "movies/b.mp4"), ftp("c", "c.mp4"))

            val visible =
                filter(entries, listDirectory = { _, dir ->
                    listedDirs += dir
                    "a.mp4\t0\t1\nb.mp4\t0\t1\nc.mp4\t0\t1"
                })

            assertEquals(listOf("a", "b", "c"), visible)
            assertEquals(listOf("movies", ""), listedDirs)
        }

    @Test
    fun `never checks http or dlna entries and preserves the recency order`() =
        runTest {
            val entries =
                listOf(
                    entry("http", HistorySourceType.HTTP, "https://host/a.mp4", serverInfo = null),
                    entry("local", HistorySourceType.LOCAL, "/sdcard/a.mp4", serverInfo = null),
                    entry("dlna", HistorySourceType.DLNA, "http://host/b.mp4"),
                )

            val visible = filter(entries, resolveRemote = { error("must not resolve") }, listDirectory = { _, _ -> error("must not list") })

            assertEquals(listOf("http", "local", "dlna"), visible)
        }
}
