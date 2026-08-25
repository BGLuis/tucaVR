package com.tucavr.history

import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.SmbServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9.1/T9.3 — cobre a chave estavel do historico (`historyKey`), o mapeamento
 * `PlaybackSource` -> `HistorySourceType`/`mediaPath`, e a regra de
 * "vale a pena oferecer retomar" (`isResumable`). Tudo aqui e Kotlin puro
 * (sem Android/Room), roda direto na JVM — mesmo padrao de
 * `MediaSorterTest`/`ThumbnailGeneratorCacheKeyTest` (ver docs/TESTING-PLAN.md).
 */
class PlaybackHistoryMappingTest {

    private fun smbServer(name: String = "nas", share: String = "Videos") = SmbServer(
        id = "server-1",
        name = name,
        host = "192.168.1.10",
        port = 445,
        share = share,
        username = "user",
        password = "pass",
        domain = ""
    )

    // ---------- historyKey: estabilidade (aviso da secao 9 do doc) ----------

    @Test
    fun `local file key includes path and size`() {
        val key = PlaybackSource.LocalFile("/sdcard/Movies/foo.mp4", 12345L).historyKey()
        assertEquals("local|/sdcard/Movies/foo.mp4|12345", key)
    }

    @Test
    fun `local file key changes when size changes (file replaced at same path)`() {
        val a = PlaybackSource.LocalFile("/sdcard/Movies/foo.mp4", 100L).historyKey()
        val b = PlaybackSource.LocalFile("/sdcard/Movies/foo.mp4", 200L).historyKey()
        assertNotEquals(a, b)
    }

    @Test
    fun `http key is just the url`() {
        assertEquals("http|https://example.com/video.mp4", PlaybackSource.Http("https://example.com/video.mp4").historyKey())
    }

    @Test
    fun `smb key does NOT depend on host or port`() {
        // Aviso do doc: "URIs de SMB podem mudar se o IP do servidor mudar".
        // A chave usa server.name (rotulo estavel escolhido pelo usuario),
        // NAO server.host/port.
        val serverOldIp = smbServer().copy(host = "192.168.1.10")
        val serverNewIp = smbServer().copy(host = "10.0.0.99")

        val keyOldIp = PlaybackSource.Smb(serverOldIp, "Filmes/foo.mkv", 999L).historyKey()
        val keyNewIp = PlaybackSource.Smb(serverNewIp, "Filmes/foo.mkv", 999L).historyKey()

        assertEquals(keyOldIp, keyNewIp)
    }

    @Test
    fun `smb key changes when server name, share, path or size differ`() {
        val base = PlaybackSource.Smb(smbServer(), "Filmes/foo.mkv", 999L).historyKey()

        assertNotEquals(base, PlaybackSource.Smb(smbServer(name = "outro-nas"), "Filmes/foo.mkv", 999L).historyKey())
        assertNotEquals(base, PlaybackSource.Smb(smbServer(share = "Series"), "Filmes/foo.mkv", 999L).historyKey())
        assertNotEquals(base, PlaybackSource.Smb(smbServer(), "Filmes/bar.mkv", 999L).historyKey())
        assertNotEquals(base, PlaybackSource.Smb(smbServer(), "Filmes/foo.mkv", 1000L).historyKey())
    }

    @Test
    fun `different source types never collide even with similar-looking data`() {
        val local = PlaybackSource.LocalFile("foo", 1L).historyKey()
        val http = PlaybackSource.Http("foo").historyKey()
        assertNotEquals(local, http)
    }

    // ---------- historySourceType / mediaPath ----------

    @Test
    fun `historySourceType maps each PlaybackSource branch correctly`() {
        assertEquals(HistorySourceType.LOCAL, PlaybackSource.LocalFile("/a").historySourceType())
        assertEquals(HistorySourceType.HTTP, PlaybackSource.Http("http://a").historySourceType())
        assertEquals(HistorySourceType.SMB, PlaybackSource.Smb(smbServer(), "a").historySourceType())
        val dlnaServer = com.tucavr.network.SavedServer(name = "Plex", protocol = com.tucavr.network.ServerProtocol.DLNA, host = "192.168.1.5", port = 32469, path = "http://192.168.1.5:32469/control/ContentDirectory")
        assertEquals(HistorySourceType.DLNA, PlaybackSource.Dlna(dlnaServer, "Movie", "http://192.168.1.5:32469/media/1.mp4").historySourceType())
    }

    @Test
    fun `mediaPath is the raw playable reference, distinct from the composite historyKey`() {
        assertEquals("/sdcard/Movies/foo.mp4", PlaybackSource.LocalFile("/sdcard/Movies/foo.mp4", 100L).mediaPath())
        assertEquals("https://example.com/x.mp4", PlaybackSource.Http("https://example.com/x.mp4").mediaPath())
        assertEquals("Filmes/foo.mkv", PlaybackSource.Smb(smbServer(), "Filmes/foo.mkv", 100L).mediaPath())
        val dlnaServer = com.tucavr.network.SavedServer(name = "Plex", protocol = com.tucavr.network.ServerProtocol.DLNA, host = "192.168.1.5", port = 32469, path = "http://192.168.1.5:32469/control/ContentDirectory")
        assertEquals("http://192.168.1.5:32469/media/1.mp4", PlaybackSource.Dlna(dlnaServer, "Movie", "http://192.168.1.5:32469/media/1.mp4").mediaPath())
    }

    // ---------- isResumable ----------

    private fun historyOf(positionMs: Long, durationMs: Long) = PlaybackHistory(
        historyKey = "k",
        title = "t",
        mediaPath = "p",
        positionMs = positionMs,
        durationMs = durationMs,
        lastPlayedAt = 0L,
        thumbnailPath = null,
        sourceType = HistorySourceType.LOCAL,
        serverInfo = null
    )

    @Test
    fun `not resumable when barely started`() {
        assertFalse(historyOf(positionMs = 1_000L, durationMs = 3_600_000L).isResumable())
    }

    @Test
    fun `resumable in the middle of playback`() {
        assertTrue(historyOf(positionMs = 600_000L, durationMs = 3_600_000L).isResumable())
    }

    @Test
    fun `not resumable when essentially finished`() {
        assertFalse(historyOf(positionMs = 3_599_000L, durationMs = 3_600_000L).isResumable())
    }

    @Test
    fun `not resumable when duration is unknown (zero)`() {
        assertFalse(historyOf(positionMs = 10_000L, durationMs = 0L).isResumable())
    }
}
