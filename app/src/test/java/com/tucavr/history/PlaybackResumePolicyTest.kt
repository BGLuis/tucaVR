package com.tucavr.history

import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.FtpServer
import com.tucavr.network.SavedServer
import com.tucavr.network.ServerProtocol
import com.tucavr.network.SftpServer
import com.tucavr.network.SmbServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying playback resume policy across all local and network sources (#88).
 */
class PlaybackResumePolicyTest {
    private val smbServer =
        SmbServer(
            id = "srv-1",
            name = "nas",
            host = "192.168.1.100",
            port = 445,
            share = "videos",
            username = "user",
            password = "pass",
            domain = "",
        )
    private val ftpServer =
        FtpServer(
            id = "srv-2",
            name = "ftp_box",
            host = "192.168.1.101",
            port = 21,
            username = "user",
            password = "pass",
        )
    private val sftpServer =
        SftpServer(
            id = "srv-3",
            name = "sftp_box",
            host = "192.168.1.102",
            port = 22,
            username = "user",
            password = "pass",
            privateKey = null,
        )
    private val nfsServer = SavedServer("srv-4", "nfs_box", ServerProtocol.NFS, "192.168.1.103", 2049, "/shares/movies")
    private val webdavServer = SavedServer("srv-5", "webdav_box", ServerProtocol.WEBDAV, "192.168.1.104", 8080, "/webdav")
    private val dlnaServer = SavedServer("srv-6", "dlna_box", ServerProtocol.DLNA, "192.168.1.105", 8200, "")

    @Test
    fun `isResumable accurately classifies progress across network sources`() {
        // Source duration: 100,000 ms (100 seconds)
        val durationMs = 100_000L

        // Under 5 seconds is treated as start of video -> false
        val earlyEntry =
            createEntry(
                source = PlaybackSource.Smb(smbServer, "movie.mp4", 1024L),
                positionMs = 4_999L,
                durationMs = durationMs,
            )
        assertFalse(earlyEntry.isResumable())

        // Between 5s and 97% is resumable -> true
        val middleEntry =
            createEntry(
                source = PlaybackSource.Nfs(nfsServer, "clip.mkv", 2048L),
                positionMs = 45_000L,
                durationMs = durationMs,
            )
        assertTrue(middleEntry.isResumable())

        // Over 97% is treated as finished -> false
        val nearEndEntry =
            createEntry(
                source = PlaybackSource.Webdav(webdavServer, "video.mp4", 4096L),
                positionMs = 97_500L,
                durationMs = durationMs,
            )
        assertFalse(nearEndEntry.isResumable())

        // Unknown or invalid duration -> false
        val invalidDurationEntry =
            createEntry(
                source = PlaybackSource.Ftp(ftpServer, "stream.ts", 8192L),
                positionMs = 10_000L,
                durationMs = 0L,
            )
        assertFalse(invalidDurationEntry.isResumable())
    }

    @Test
    fun `all network sources produce unique, non-colliding history keys`() {
        val sources =
            listOf(
                PlaybackSource.LocalFile("/sdcard/Movies/sample.mp4", 1000L),
                PlaybackSource.Http("https://example.com/stream.m3u8"),
                PlaybackSource.Smb(smbServer, "folder/sample.mp4", 1000L),
                PlaybackSource.Ftp(ftpServer, "folder/sample.mp4", 1000L),
                PlaybackSource.Sftp(sftpServer, "folder/sample.mp4", 1000L),
                PlaybackSource.Nfs(nfsServer, "folder/sample.mp4", 1000L),
                PlaybackSource.Dlna(dlnaServer, "sample", "http://192.168.1.105:8200/media/1", 1000L),
                PlaybackSource.Webdav(webdavServer, "folder/sample.mp4", 1000L),
            )

        val keys = sources.map { it.historyKey() }
        assertEquals(keys.toSet().size, keys.size)
        assertTrue(keys.all { it.isNotBlank() })
    }

    private fun createEntry(
        source: PlaybackSource,
        positionMs: Long,
        durationMs: Long,
    ): PlaybackHistory {
        return PlaybackHistory(
            historyKey = source.historyKey(),
            title = "Test Video",
            mediaPath = source.mediaPath(),
            positionMs = positionMs,
            durationMs = durationMs,
            lastPlayedAt = System.currentTimeMillis(),
            thumbnailPath = null,
            sourceType = source.historySourceType(),
            serverInfo = null,
        )
    }
}
