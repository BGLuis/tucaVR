package com.tucavr.debug

import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.FtpServer
import com.tucavr.network.SavedServer
import com.tucavr.network.SftpServer
import com.tucavr.network.SmbServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTelemetryExporterTest {

    private val tsvHud = """
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
        quality_level	MEDIUM
        quality_reason	GpuOverload
        draw_call_count	9
        triangle_count	12000
    """.trimIndent()

    @Test
    fun testCsvHeaderMatchesParsedRowCount() {
        val source = PlaybackSource.LocalFile("/sdcard/Movies/sample.mp4", 1024L)
        val row = DebugTelemetryExporter.parseHudToCsvRow(
            hudText = tsvHud,
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
    fun testCsvHeaderCarriesSchemaVersionAsFirstColumn() {
        assertEquals("schema_version", DebugTelemetryExporter.CSV_HEADER.split(',').first())

        val row = DebugTelemetryExporter.parseHudToCsvRow(
            hudText = tsvHud,
            sessionId = "a1b2c3d4",
            timestampMs = 1700000000000L,
            source = PlaybackSource.LocalFile("/sdcard/Movies/sample.mp4", 1024L)
        )
        assertEquals(DebugTelemetryExporter.SCHEMA_VERSION.toString(), row.split(',').first())
    }

    @Test
    fun testParseTsvHudFormat() {
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
        assertEquals(DebugTelemetryExporter.SCHEMA_VERSION.toString(), cols[0])
        assertEquals("1700000002000", cols[1])
        assertEquals("d212b387", cols[2])
        assertEquals("157.25", cols[3])
        assertEquals("VULKAN", cols[4])
        assertEquals("Vr180SBS", cols[5])
        assertEquals("1", cols[6]) // stereo_layout
        assertEquals("1", cols[7]) // polar_180
        assertEquals("0", cols[8]) // swap_eyes
        assertEquals("ativo", cols[9]) // video_status (has_frame=1)
        assertEquals("10.9", cols[10])
        assertEquals("59.5", cols[11])
        assertEquals("60.0", cols[12])
        assertEquals("59.8", cols[13])
        assertEquals("0.2", cols[14])
        assertEquals("1.1", cols[15])
        assertEquals("24.50", cols[16])
        assertEquals("88", cols[17])
        assertEquals("150", cols[18])
        assertEquals("90.0", cols[19])
        assertEquals("11.1", cols[20])
        assertEquals("4.25", cols[21])
        assertEquals("4.10", cols[22])
        assertEquals("QUAL", cols[23])
        assertEquals("0.75", cols[24])
        assertEquals("1", cols[25]) // mqsr_enabled
        assertEquals("2", cols[26]) // stutter_count
        assertEquals("0", cols[27]) // freeze_count
        assertEquals("1", cols[28]) // thermal_level
        assertEquals("1.00", cols[29]) // scale
        assertEquals("90.0", cols[30]) // refresh_rate
        assertEquals("12.5", cols[31]) // av_drift_ms
        assertEquals("8.0", cols[32]) // net_last_fetch_ms
        assertEquals("1240", cols[33])
        assertEquals("5", cols[34])
        assertEquals("1", cols[35]) // foveation
        assertEquals("2", cols[36]) // spatial_audio
        assertEquals("1", cols[37]) // head_tracking
        assertEquals("1.00", cols[38]) // speed
        assertEquals("0.80", cols[39]) // volume
        assertEquals("1", cols[40]) // audio_track
        assertEquals("2", cols[41]) // audio_track_count
        assertEquals("0", cols[42]) // sub_track
        assertEquals("-100", cols[43]) // sub_offset_ms
        assertEquals("MEDIUM", cols[44]) // quality_level
        assertEquals("GpuOverload", cols[45]) // quality_reason
        assertEquals("9", cols[46]) // draw_call_count
        assertEquals("12000", cols[47]) // triangle_count
        // F1: tsvHud não emite video_stall_count/*_stats_age_ms — devem cair nos defaults.
        assertEquals("0", cols[48]) // video_stall_count
        assertEquals("0", cols[49]) // video_stats_age_ms
        assertEquals("0", cols[50]) // network_stats_age_ms
        assertEquals("0", cols[51]) // audio_stats_age_ms
        assertEquals("0", cols[52]) // render_stats_age_ms
        // F4: tsvHud não emite os 7 campos novos de coleta na origem — defaults.
        assertEquals("0", cols[53]) // network_fetch_failures
        assertEquals("0", cols[54]) // network_sequential_streak
        assertEquals("0", cols[55]) // network_throttled
        assertEquals("0", cols[56]) // audio_queue_depth
        assertEquals("0", cols[57]) // decode_error_count
        assertEquals("0", cols[58]) // demux_corrupt_packet_count
        assertEquals("0", cols[59]) // audio_underrun_count
        assertEquals("0", cols[60]) // load_phase_demux_open_ms
        assertEquals("0", cols[61]) // load_phase_decoder_ready_ms
        assertEquals("0", cols[62]) // load_phase_audio_ready_ms
        // F3: tsvHud não emite os campos de XR_META_performance_metrics — defaults.
        assertEquals("0", cols[63]) // perf_metrics_valid_mask
        assertEquals("0.00", cols[64]) // perf_app_cpu_frametime_ms
        assertEquals("0.00", cols[65]) // perf_app_gpu_frametime_ms
        assertEquals("0.00", cols[66]) // perf_motion_to_photon_latency_ms
        assertEquals("0.00", cols[67]) // perf_compositor_cpu_frametime_ms
        assertEquals("0.00", cols[68]) // perf_compositor_gpu_frametime_ms
        assertEquals("0", cols[69]) // perf_compositor_dropped_frame_count
        assertEquals("0", cols[70]) // perf_compositor_spacewarp_mode
        assertEquals("0.00", cols[71]) // perf_device_cpu_util_average
        assertEquals("0.00", cols[72]) // perf_device_cpu_util_worst
        assertEquals("0.00", cols[73]) // perf_device_gpu_util
        // F5 G2: tsvHud não emite os buckets de histograma — defaults.
        for (i in 74..81) {
            assertEquals("0", cols[i]) // hist_bucket_${i - 74}
        }
        assertEquals("Sftp", cols[82])
    }

    @Test
    fun testParseTsvHudFormatCarriesStallCountAndFreshnessFields() {
        val hudWithStallFields = tsvHud + "\n" +
            "video_stall_count\t3\n" +
            "video_stats_age_ms\t1200\n" +
            "network_stats_age_ms\t340\n" +
            "audio_stats_age_ms\t0\n" +
            "render_stats_age_ms\t0"

        val row = DebugTelemetryExporter.parseHudToCsvRow(
            hudText = hudWithStallFields,
            sessionId = "sess-stall",
            timestampMs = 2000L,
            source = null
        )
        val cols = row.split(',')
        assertEquals("3", cols[48]) // video_stall_count
        assertEquals("1200", cols[49]) // video_stats_age_ms
        assertEquals("340", cols[50]) // network_stats_age_ms
        assertEquals("0", cols[51]) // audio_stats_age_ms
        assertEquals("0", cols[52]) // render_stats_age_ms
    }

    @Test
    fun testParseTsvHudFormatCarriesF4SourceCollectionFields() {
        val hudWithF4Fields = tsvHud + "\n" +
            "network_fetch_failures\t2\n" +
            "network_sequential_streak\t5\n" +
            "network_throttled\t1\n" +
            "audio_queue_depth\t40\n" +
            "decode_error_count\t1\n" +
            "demux_corrupt_packet_count\t7\n" +
            "audio_underrun_count\t9"

        val row = DebugTelemetryExporter.parseHudToCsvRow(
            hudText = hudWithF4Fields,
            sessionId = "sess-f4",
            timestampMs = 3000L,
            source = null
        )
        val cols = row.split(',')
        assertEquals("2", cols[53]) // network_fetch_failures
        assertEquals("5", cols[54]) // network_sequential_streak
        assertEquals("1", cols[55]) // network_throttled
        assertEquals("40", cols[56]) // audio_queue_depth
        assertEquals("1", cols[57]) // decode_error_count
        assertEquals("7", cols[58]) // demux_corrupt_packet_count
        assertEquals("9", cols[59]) // audio_underrun_count
    }

    @Test
    fun testParseTsvHudFormatCarriesLoadPhaseTimings() {
        val hudWithLoadPhases = tsvHud + "\n" +
            "load_phase_demux_open_ms\t12\n" +
            "load_phase_decoder_ready_ms\t45\n" +
            "load_phase_audio_ready_ms\t60"

        val row = DebugTelemetryExporter.parseHudToCsvRow(
            hudText = hudWithLoadPhases,
            sessionId = "sess-load",
            timestampMs = 4000L,
            source = null
        )
        val cols = row.split(',')
        assertEquals("12", cols[60]) // load_phase_demux_open_ms
        assertEquals("45", cols[61]) // load_phase_decoder_ready_ms
        assertEquals("60", cols[62]) // load_phase_audio_ready_ms
    }

    @Test
    fun testParseTsvHudFormatCarriesPerformanceMetricsFields() {
        val hudWithPerfMetrics = tsvHud + "\n" +
            "perf_metrics_valid_mask\t1023\n" +
            "perf_app_cpu_frametime_ms\t3.10\n" +
            "perf_app_gpu_frametime_ms\t4.20\n" +
            "perf_motion_to_photon_latency_ms\t18.50\n" +
            "perf_compositor_cpu_frametime_ms\t2.00\n" +
            "perf_compositor_gpu_frametime_ms\t2.50\n" +
            "perf_compositor_dropped_frame_count\t3\n" +
            "perf_compositor_spacewarp_mode\t1\n" +
            "perf_device_cpu_util_average\t45.00\n" +
            "perf_device_cpu_util_worst\t80.00\n" +
            "perf_device_gpu_util\t60.00"

        val row = DebugTelemetryExporter.parseHudToCsvRow(
            hudText = hudWithPerfMetrics,
            sessionId = "sess-perf",
            timestampMs = 5000L,
            source = null
        )
        val cols = row.split(',')
        assertEquals("1023", cols[63]) // perf_metrics_valid_mask
        assertEquals("3.10", cols[64]) // perf_app_cpu_frametime_ms
        assertEquals("4.20", cols[65]) // perf_app_gpu_frametime_ms
        assertEquals("18.50", cols[66]) // perf_motion_to_photon_latency_ms
        assertEquals("2.00", cols[67]) // perf_compositor_cpu_frametime_ms
        assertEquals("2.50", cols[68]) // perf_compositor_gpu_frametime_ms
        assertEquals("3", cols[69]) // perf_compositor_dropped_frame_count
        assertEquals("1", cols[70]) // perf_compositor_spacewarp_mode
        assertEquals("45.00", cols[71]) // perf_device_cpu_util_average
        assertEquals("80.00", cols[72]) // perf_device_cpu_util_worst
        assertEquals("60.00", cols[73]) // perf_device_gpu_util
    }

    @Test
    fun testParseTsvHudFormatCarriesFrameTimeHistogram() {
        val hudWithHistogram = tsvHud + "\n" +
            "hist_bucket_0\t100\n" +
            "hist_bucket_1\t50\n" +
            "hist_bucket_2\t20\n" +
            "hist_bucket_3\t10\n" +
            "hist_bucket_4\t5\n" +
            "hist_bucket_5\t3\n" +
            "hist_bucket_6\t2\n" +
            "hist_bucket_7\t1"

        val row = DebugTelemetryExporter.parseHudToCsvRow(
            hudText = hudWithHistogram,
            sessionId = "sess-hist",
            timestampMs = 6000L,
            source = null
        )
        val cols = row.split(',')
        val expected = listOf("100", "50", "20", "10", "5", "3", "2", "1")
        for (i in expected.indices) {
            assertEquals(expected[i], cols[74 + i]) // hist_bucket_$i
        }
    }

    @Test
    fun testUnparsableHudProducesDefaultRowInsteadOfCrashing() {
        val row = DebugTelemetryExporter.parseHudToCsvRow(
            hudText = "ERROR: not ready",
            sessionId = "sess1",
            timestampMs = 1000L,
            source = null
        )
        val cols = row.split(',')
        assertEquals(DebugTelemetryExporter.CSV_HEADER.split(',').size, cols.size)
        assertEquals("UNKNOWN", cols[4]) // backend default
        assertEquals("inativo", cols[9]) // video_status default (hasFrame=false)
        assertEquals("Unknown", cols[cols.size - 2]) // source_type (source == null)
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
    fun testExtractSourceInfoNeverLeaksPasswordForAnyNetworkSource() {
        // D-03: VRActivity.kt's crash handler now builds "Current Source" exclusively from
        // extractSourceInfo(currentPlaybackSource) instead of interpolating the PlaybackSource
        // data class directly (whose synthesized toString() included `password` in clear).
        // This guards the actual rule (no password reaches an artifact that leaves the
        // device), not a specific string format — it must hold for every source type that
        // carries a real password field (Smb/Ftp/Sftp).
        val secretPassword = "ultra_secret_password_123"

        val sources = listOf(
            PlaybackSource.Smb(
                server = SmbServer(
                    id = "smb-1", name = "NAS", host = "192.168.1.50", port = 445,
                    share = "videos", username = "alice", password = secretPassword, domain = "WORKGROUP"
                ),
                path = "movies/avatar.mkv"
            ),
            PlaybackSource.Ftp(
                server = FtpServer(
                    id = "ftp-1", name = "FTP", host = "files.local", port = 21,
                    username = "bob", password = secretPassword
                ),
                path = "clip.mp4"
            ),
            PlaybackSource.Sftp(
                server = SftpServer(
                    id = "sftp-1", name = "NAS", host = "10.10.10.44", port = 2022,
                    username = "user", password = secretPassword, privateKey = null
                ),
                path = "videos/8k.mp4"
            )
        )

        for (source in sources) {
            val (sourceType, sourceRedacted) = DebugTelemetryExporter.extractSourceInfo(source)
            val crashLine = "Current Source: $sourceType $sourceRedacted"
            assertFalse(
                "Vazamento de senha para fonte $sourceType: $crashLine",
                crashLine.contains("password=") || crashLine.contains(secretPassword)
            )
        }
    }

    @Test
    fun testCsvEscapingWithCommasAndQuotes() {
        val source = PlaybackSource.LocalFile("/sdcard/Movies/Title, with \"quotes\" and commas.mp4")
        val row = DebugTelemetryExporter.parseHudToCsvRow(
            hudText = tsvHud,
            sessionId = "sess,1",
            timestampMs = 1000L,
            source = source
        )

        assertTrue(row.contains("\"sess,1\""))
        assertTrue(row.contains("\"/sdcard/Movies/Title, with \"\"quotes\"\" and commas.mp4\""))
    }
}
