package com.tucavr.playlist

import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.SavedServer
import com.tucavr.network.ServerProtocol
import com.tucavr.network.SmbServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistItemMappingTest {

    @Test
    fun `test mapping local file source to playlist item`() = runBlocking {
        val source = PlaybackSource.LocalFile("/sdcard/Movies/clip.mp4", 1024L)
        val uri = source.toPlaylistItemUri()
        val type = source.sourceTypeString()

        assertEquals("/sdcard/Movies/clip.mp4", uri)
        assertEquals("LOCAL", type)

        val item = PlaylistItem(
            id = "item_loc",
            playlistId = "pl_1",
            mediaUri = uri,
            title = "clip.mp4",
            durationMs = 5000L,
            position = 0,
            sourceType = type
        )

        val restored = item.toPlaybackSource()
        assertNotNull(restored)
        assertEquals(PlaybackSource.LocalFile("/sdcard/Movies/clip.mp4"), restored)
    }

    @Test
    fun `test mapping http source to playlist item`() = runBlocking {
        val source = PlaybackSource.Http("https://example.com/stream.mp4")
        val uri = source.toPlaylistItemUri()
        val type = source.sourceTypeString()

        assertEquals("https://example.com/stream.mp4", uri)
        assertEquals("HTTP", type)

        val item = PlaylistItem(
            id = "item_http",
            playlistId = "pl_1",
            mediaUri = uri,
            title = "stream.mp4",
            durationMs = 10000L,
            position = 1,
            sourceType = type
        )

        val restored = item.toPlaybackSource()
        assertNotNull(restored)
        assertEquals(PlaybackSource.Http("https://example.com/stream.mp4"), restored)
    }

    @Test
    fun `test smb item returns null when server is not found without crash`() = runBlocking {
        val item = PlaylistItem(
            id = "item_smb",
            playlistId = "pl_1",
            mediaUri = "server_inexistente|share/video.mkv",
            title = "video.mkv",
            durationMs = 20000L,
            position = 2,
            sourceType = "SMB"
        )

        // Deve retornar null sem crashar (item marcado como indisponível)
        val restored = item.toPlaybackSource(smbCredentials = null)
        assertNull(restored)
    }

    @Test
    fun `test mapping webdav source to playlist item`() = runBlocking {
        val server = SavedServer(id = "srv_wd_1", name = "WebdavServer", protocol = ServerProtocol.WEBDAV, host = "nas.local", port = 5005, path = "/dav")
        val source = PlaybackSource.Webdav(server, "Movies/beach.mp4", 2048L)
        val uri = source.toPlaylistItemUri()
        val type = source.sourceTypeString()

        assertEquals("srv_wd_1|Movies/beach.mp4", uri)
        assertEquals("WEBDAV", type)

        val item = PlaylistItem(
            id = "item_wd",
            playlistId = "pl_1",
            mediaUri = uri,
            title = "beach.mp4",
            durationMs = 15000L,
            position = 3,
            sourceType = type
        )

        val dao = object : com.tucavr.network.SavedServerDao {
            override suspend fun insert(server: SavedServer) {}
            override suspend fun update(server: SavedServer) {}
            override suspend fun delete(id: String) {}
            override suspend fun updateLastConnected(id: String, timestamp: Long) {}
            override suspend fun getAll(): List<SavedServer> = listOf(server)
            override suspend fun getByProtocol(protocol: ServerProtocol): List<SavedServer> = listOf(server)
            override suspend fun getById(id: String): SavedServer? = if (id == server.id) server else null
        }

        val restored = item.toPlaybackSource(savedServerDao = dao)
        assertNotNull(restored)
        assertEquals(PlaybackSource.Webdav(server, "Movies/beach.mp4"), restored)
    }
}
