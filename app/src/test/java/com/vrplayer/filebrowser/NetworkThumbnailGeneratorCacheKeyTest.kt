package com.vrplayer.filebrowser

import com.vrplayer.navigation.PlaybackSource
import com.vrplayer.network.FtpServer
import com.vrplayer.network.SftpServer
import com.vrplayer.network.SmbServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// T9: cache-key logic used by NetworkThumbnailGenerator to name cached network
// thumbnail files on disk -- same rationale as ThumbnailGeneratorCacheKeyTest,
// see that file. The rest (nativeSmb/Ftp/SftpGenerateThumbnail JNI calls, real
// disk I/O, android.graphics.Bitmap) needs a real device and is left for
// manual/on-device testing.
class NetworkThumbnailGeneratorCacheKeyTest {

    private fun smbServer(host: String = "192.168.1.10", share: String = "media") =
        SmbServer(id = "s1", name = "Server", host = host, port = 445, share = share, username = "", password = "", domain = "")

    private fun ftpServer(host: String = "192.168.1.10") =
        FtpServer(id = "f1", name = "Server", host = host, port = 21, username = "", password = "")

    private fun sftpServer(host: String = "192.168.1.10") =
        SftpServer(id = "sf1", name = "Server", host = host, port = 22, username = "", password = "", privateKey = null)

    @Test
    fun sameSmbSourceProducesTheSameKey() {
        val a = PlaybackSource.Smb(smbServer(), "movies/movie.mp4", 12345L)
        val b = PlaybackSource.Smb(smbServer(), "movies/movie.mp4", 12345L)

        assertEquals(NetworkThumbnailGenerator.cacheKeyFor(a), NetworkThumbnailGenerator.cacheKeyFor(b))
    }

    @Test
    fun differentPathsProduceDifferentKeys() {
        val a = PlaybackSource.Smb(smbServer(), "movies/a.mp4", 12345L)
        val b = PlaybackSource.Smb(smbServer(), "movies/b.mp4", 12345L)

        assertNotEquals(NetworkThumbnailGenerator.cacheKeyFor(a), NetworkThumbnailGenerator.cacheKeyFor(b))
    }

    @Test
    fun sameOverwrittenFileWithDifferentSizeGetsANewKey() {
        val original = PlaybackSource.Smb(smbServer(), "movies/movie.mp4", 1000L)
        val edited = PlaybackSource.Smb(smbServer(), "movies/movie.mp4", 2000L)

        assertNotEquals(NetworkThumbnailGenerator.cacheKeyFor(original), NetworkThumbnailGenerator.cacheKeyFor(edited))
    }

    @Test
    fun differentServersWithSamePathDoNotCollide() {
        val a = PlaybackSource.Smb(smbServer(host = "192.168.1.10"), "movies/movie.mp4", 1000L)
        val b = PlaybackSource.Smb(smbServer(host = "192.168.1.11"), "movies/movie.mp4", 1000L)

        assertNotEquals(NetworkThumbnailGenerator.cacheKeyFor(a), NetworkThumbnailGenerator.cacheKeyFor(b))
    }

    @Test
    fun differentSharesOnTheSameHostDoNotCollide() {
        val a = PlaybackSource.Smb(smbServer(share = "media"), "movie.mp4", 1000L)
        val b = PlaybackSource.Smb(smbServer(share = "backup"), "movie.mp4", 1000L)

        assertNotEquals(NetworkThumbnailGenerator.cacheKeyFor(a), NetworkThumbnailGenerator.cacheKeyFor(b))
    }

    @Test
    fun differentProtocolsWithOtherwiseIdenticalFieldsDoNotCollide() {
        val ftp = PlaybackSource.Ftp(ftpServer(), "movie.mp4", 1000L)
        val sftp = PlaybackSource.Sftp(sftpServer(), "movie.mp4", 1000L)

        assertNotEquals(NetworkThumbnailGenerator.cacheKeyFor(ftp), NetworkThumbnailGenerator.cacheKeyFor(sftp))
    }

    @Test
    fun keyIsAHexEncodedSha256Digest() {
        val key = NetworkThumbnailGenerator.cacheKeyFor(PlaybackSource.Ftp(ftpServer(), "movie.mp4", 1000L))

        assertEquals(64, key.length) // SHA-256 -> 32 bytes -> 64 hex chars
        assertTrue(key.all { it.isDigit() || it in 'a'..'f' })
    }
}
