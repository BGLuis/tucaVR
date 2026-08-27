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
        assertEquals("1", cols[5]) // stereo_layout
        assertEquals("0", cols[6]) // polar_180
        assertEquals("0", cols[7]) // swap_eyes
        assertEquals("ativo", cols[8])
        assertEquals("16.0", cols[9]) // frame_gap_ms
        assertEquals("59.9", cols[10])
        assertEquals("60.0", cols[11])
        assertEquals("60.0", cols[12])
        assertEquals("2.0", cols[13])
        assertEquals("3.5", cols[14])
        assertEquals("22.50", cols[15])
        assertEquals("12", cols[16])
        assertEquals("120", cols[17])
        assertEquals("72.0", cols[18])
        assertEquals("13.8", cols[19])
        assertEquals("4", cols[25]) // stutter_count
        assertEquals("1", cols[26]) // freeze_count
        assertEquals("2", cols[27]) // thermal_level
        assertEquals("0.85", cols[28]) // scale
        assertEquals("Http", cols[43])
        assertEquals("https://stream.local/live.m3u8", cols[44])
    }

    @Test
    fun testParseTsvHudFormat() {
        val tsvHud = """
            backend	VULKAN
            screen_mode	Vr180SBS
            stereo_layout	1
            polar_180	1
            swap_eyes	0
            has_frame	1
            frame_gap_ms	10.9
            video_fps	59.5
            decoded_fps	60.0
            output_fps	59.8
            dropped_fps	0.2
            jitter_ms	1.1
            net_mbs	24.50
            queue_depth	88
            seek_latency_ms	150
            smoothed_fps	90.0
            frame_time_ms	11.1
            gpu_time_ms	4.25
            smoothed_gpu_time_ms	4.10
            upscaling_mode	QUAL
            upscaling_sharpness	0.75
            mqsr_enabled	1
            stutter_count	2
            freeze_count	0
            thermal_level	1
            render_scale	1.00
            refresh_rate	90.0
            av_drift_ms	12.5
            net_last_fetch_ms	8.0
            net_blocks_fetched	1240
            net_blocks_discarded	5
            foveation	1
            spatial_audio	2
            head_tracking	1
            speed	1.00
            volume	0.80
            audio_track	1
            audio_track_count	2
            sub_track	0
            sub_offset_ms	-100
        """.trimIndent()

        val source = PlaybackSource.Sftp(
            server = com.tucavr.network.SftpServer(
                id = "sftp-1",
                name = "NAS",
                host = "10.10.10.44",
                port = 2022,
                username = "user",
                password = "secret",
                privateKey = null
            ),
            path = "videos/8k.mp4"
        )

        val row = DebugTelemetryExporter.parseHudToCsvRow(
            hudText = tsvHud,
            sessionId = "d212b387",
            timestampMs = 1700000002000L,
            source = source,
            elapsedSeconds = 157.25f
        )

        val cols = row.split(',')
        assertEquals(DebugTelemetryExporter.CSV_HEADER.split(',').size, cols.size)
        assertEquals("d212b387", cols[1])
        assertEquals("157.25", cols[2])
        assertEquals("VULKAN", cols[3])
        assertEquals("Vr180SBS", cols[4])
        assertEquals("1", cols[5])
        assertEquals("1", cols[6])
        assertEquals("0", cols[7])
        assertEquals("ativo", cols[8])
        assertEquals("10.9", cols[9])
        assertEquals("59.5", cols[10])
        assertEquals("60.0", cols[11])
        assertEquals("59.8", cols[12])
        assertEquals("0.2", cols[13])
        assertEquals("1.1", cols[14])
        assertEquals("24.50", cols[15])
        assertEquals("88", cols[16])
        assertEquals("150", cols[17])
        assertEquals("90.0", cols[18])
        assertEquals("11.1", cols[19])
        assertEquals("4.25", cols[20])
        assertEquals("4.10", cols[21])
        assertEquals("QUAL", cols[22])
        assertEquals("0.75", cols[23])
        assertEquals("1", cols[24])
        assertEquals("2", cols[25])
        assertEquals("0", cols[26])
        assertEquals("1", cols[27])
        assertEquals("1.00", cols[28])
        assertEquals("90.0", cols[29])
        assertEquals("12.5", cols[30])
        assertEquals("8.0", cols[31])
        assertEquals("1240", cols[32])
        assertEquals("5", cols[33])
        assertEquals("1", cols[34])
        assertEquals("2", cols[35])
        assertEquals("1", cols[36])
        assertEquals("1.00", cols[37])
        assertEquals("0.80", cols[38])
        assertEquals("1", cols[39])
        assertEquals("2", cols[40])
        assertEquals("0", cols[41])
        assertEquals("-100", cols[42])
        assertEquals("Sftp", cols[43])
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
