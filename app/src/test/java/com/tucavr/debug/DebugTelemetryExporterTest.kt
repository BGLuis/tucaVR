package com.tucavr.debug

import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.SavedServer
import com.tucavr.network.SmbServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTelemetryExporterTest {

    @Test
    fun testCsvHeaderMatchesParsedRowCount() {
        val hudText = "VULKAN | 2D | stereoLayout=0 polar180=0 swap=0 | video=ativo vidGap=16ms vidFps=60.0 decFps=59.8 outFps=59.8 drop=0.0 jitter=1.2ms | net=15.4MB/s q=8 seekMs=45 | 90fps 11.1ms stutter=0 freeze=0 | thermal=1 scale=1.00"
        val source = PlaybackSource.LocalFile("/sdcard/Movies/sample.mp4", 1024L)
        val row = DebugTelemetryExporter.parseHudToCsvRow(
            hudText = hudText,
            sessionId = "a1b2c3d4",
            timestampMs = 1700000000000L,
            source = source,
            elapsedSeconds = 10.5f
        )

        val headerCols = DebugTelemetryExporter.CSV_HEADER.split(',').size
        val rowCols = row.split(',').size

        assertEquals("O número de colunas do cabeçalho e da linha deve ser idêntico", headerCols, rowCols)
    }

    @Test
    fun testParseVulkanHudFields() {
        val hudText = "VULKAN | SIDE_BY_SIDE | stereoLayout=1 polar180=0 swap=0 | video=ativo vidGap=16ms vidFps=59.9 decFps=60.0 outFps=60.0 drop=2.0 jitter=3.5ms | net=22.5MB/s q=12 seekMs=120 | 72fps 13.8ms stutter=4 freeze=1 | thermal=2 scale=0.85"
        val row = DebugTelemetryExporter.parseHudToCsvRow(
            hudText = hudText,
            sessionId = "deadbeef",
            timestampMs = 1700000001000L,
            source = PlaybackSource.Http("https://stream.local/live.m3u8"),
            elapsedSeconds = 42.0f
        )

        val cols = row.split(',')
        assertEquals("1700000001000", cols[0])
        assertEquals("deadbeef", cols[1])
        assertEquals("42.00", cols[2])
        assertEquals("VULKAN", cols[3])
        assertEquals("SIDE_BY_SIDE", cols[4])
        assertEquals("ativo", cols[5])
        assertEquals("59.9", cols[6])
        assertEquals("60.0", cols[7])
        assertEquals("60.0", cols[8])
        assertEquals("2.0", cols[9])
        assertEquals("3.5", cols[10])
        assertEquals("22.50", cols[11])
        assertEquals("12", cols[12])
        assertEquals("120", cols[13])
        assertEquals("72.0", cols[14])
        assertEquals("13.8", cols[15])
        assertEquals("4", cols[16])
        assertEquals("1", cols[17])
        assertEquals("2", cols[18])
        assertEquals("0.85", cols[19])
        assertEquals("Http", cols[20])
        assertEquals("https://stream.local/live.m3u8", cols[21])
    }

    @Test
    fun testRedactSourceCredentials() {
        // SMB com credenciais
        val smbRedacted = DebugTelemetryExporter.redactSource("smb://john:supersecret@nas.home/share/vid.mkv")
        assertEquals("smb://john:***@nas.home/share/vid.mkv", smbRedacted)
        assertFalse("Não deve conter senha", smbRedacted.contains("supersecret"))

        // FTP com senha contendo arroba e caracteres especiais
        val ftpRedacted = DebugTelemetryExporter.redactSource("ftp://admin:p@ssw0rd!@files.local:21/movie.mp4")
        assertEquals("ftp://admin:***@files.local:21/movie.mp4", ftpRedacted)
        assertFalse(ftpRedacted.contains("p@ssw0rd!"))

        // SFTP com chave/senha
        val sftpRedacted = DebugTelemetryExporter.redactSource("sftp://user:privkey@secure.org:2222/video.mp4")
        assertEquals("sftp://user:***@secure.org:2222/video.mp4", sftpRedacted)

        // URL sem credencial permanece inalterada
        val httpPlain = DebugTelemetryExporter.redactSource("http://cdn.example.com/stream.mp4")
        assertEquals("http://cdn.example.com/stream.mp4", httpPlain)

        // Arquivo local permanece inalterado
        val localPath = DebugTelemetryExporter.redactSource("/sdcard/Movies/clip.mp4")
        assertEquals("/sdcard/Movies/clip.mp4", localPath)
    }

    @Test
    fun testExtractSourceInfoRedactsSensibleData() {
        val smbSource = PlaybackSource.Smb(
            server = SmbServer(
                id = "smb-1",
                name = "MyNAS",
                host = "192.168.1.50",
                port = 445,
                share = "videos",
                username = "alice",
                password = "ultra_secret_password",
                domain = "WORKGROUP"
            ),
            path = "movies/avatar.mkv"
        )

        val (type, redactedPath) = DebugTelemetryExporter.extractSourceInfo(smbSource)
        assertEquals("Smb", type)
        assertEquals("smb://192.168.1.50:445/videos/movies/avatar.mkv", redactedPath)
        assertFalse("Não deve conter a senha do servidor", redactedPath.contains("ultra_secret_password"))
    }

    @Test
    fun testCsvEscapingWithCommasAndQuotes() {
        val hudText = "GLES | 2D | flat=0 esfera=0 polar180=0 swap=0 | video=ativo vidGap=0ms vidFps=30.0 decFps=30.0 outFps=30.0 drop=0.0 jitter=0.0ms | net=0.0MB/s q=0 seekMs=0 | 60fps 16.6ms stutter=0 freeze=0 | thermal=0"
        val source = PlaybackSource.LocalFile("/sdcard/Movies/Title, with \"quotes\" and commas.mp4")
        val row = DebugTelemetryExporter.parseHudToCsvRow(
            hudText = hudText,
            sessionId = "sess,1",
            timestampMs = 1000L,
            source = source
        )

        assertTrue(row.contains("\"sess,1\""))
        assertTrue(row.contains("\"/sdcard/Movies/Title, with \"\"quotes\"\" and commas.mp4\""))
    }
}
