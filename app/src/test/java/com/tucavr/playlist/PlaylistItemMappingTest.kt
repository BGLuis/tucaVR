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
}
