package com.tucavr.debug

import com.tucavr.FeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugStatsParserTest {

    @Test
    fun parsesFullWireCorrectly() {
        val wire = "backend\tVULKAN\n" +
            "screen_mode\tSphere180\n" +
            "stereo_layout\t1\n" +
            "polar_180\t1\n" +
            "swap_eyes\t0\n" +
            "has_frame\t1\n" +
            "frame_gap_ms\t16.7\n" +
            "video_fps\t60.0\n" +
            "decoded_fps\t59.9\n" +
            "output_fps\t60.0\n" +
            "dropped_fps\t0.0\n" +
            "jitter_ms\t1.2\n" +
            "net_mbs\t12.50\n" +
            "queue_depth\t32\n" +
            "seek_latency_ms\t45\n" +
            "smoothed_fps\t90.0\n" +
            "frame_time_ms\t11.1\n" +
            "gpu_time_ms\t8.50\n" +
            "smoothed_gpu_time_ms\t8.20\n" +
            "upscaling_mode\tAUTO\n" +
            "upscaling_sharpness\t0.35\n" +
            "mqsr_enabled\t1\n" +
            "quality_level\tHIGH\n" +
            "quality_reason\tRECOVERY\n" +
            "draw_call_count\t14\n" +
            "triangle_count\t18432\n" +
            "stutter_count\t2\n" +
            "freeze_count\t0\n" +
            "thermal_level\t1\n" +
            "render_scale\t1.00\n" +
            "refresh_rate\t90.0\n" +
            "av_drift_ms\t-3.5\n" +
            "net_last_fetch_ms\t15.2\n" +
            "net_blocks_fetched\t1024\n" +
            "net_blocks_discarded\t4\n" +
            "foveation\t1\n" +
            "spatial_audio\t1\n" +
            "head_tracking\t1\n" +
            "speed\t1.25\n" +
            "volume\t0.80\n" +
            "audio_track\t0\n" +
            "audio_track_count\t2\n" +
            "sub_track\t1\n" +
            "sub_offset_ms\t200\n" +
            "video_stall_count\t3\n" +
            "video_stats_age_ms\t1200\n" +
            "network_stats_age_ms\t340\n" +
            "audio_stats_age_ms\t0\n" +
            "render_stats_age_ms\t0\n" +
            "network_fetch_failures\t2\n" +
            "network_sequential_streak\t5\n" +
            "network_throttled\t1\n" +
            "audio_queue_depth\t40\n" +
            "decode_error_count\t1\n" +
            "demux_corrupt_packet_count\t7\n" +
            "audio_underrun_count\t9\n" +
            "load_phase_demux_open_ms\t12\n" +
            "load_phase_decoder_ready_ms\t45\n" +
            "load_phase_audio_ready_ms\t60\n" +
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
            "perf_device_gpu_util\t60.00\n" +
            "hist_bucket_0\t100\n" +
            "hist_bucket_1\t50\n" +
            "hist_bucket_2\t20\n" +
            "hist_bucket_3\t10\n" +
            "hist_bucket_4\t5\n" +
            "hist_bucket_5\t3\n" +
            "hist_bucket_6\t2\n" +
            "hist_bucket_7\t1\n"

        val stats = DebugStatsParser.parse(wire)
        assertNotNull(stats)
        stats!!

        assertEquals("VULKAN", stats.backend)
        assertEquals("Sphere180", stats.screenMode)
        assertEquals(1, stats.stereoLayout)
        assertEquals(1, stats.polar180)
        assertEquals(0, stats.swapEyes)
        assertTrue(stats.hasFrame)
        assertEquals(16.7f, stats.frameGapMs, 0.01f)
        assertEquals(60.0f, stats.videoFps, 0.01f)
        assertEquals(59.9f, stats.decodedFps, 0.01f)
        assertEquals(60.0f, stats.outputFps, 0.01f)
        assertEquals(0.0f, stats.droppedFps, 0.01f)
        assertEquals(1.2f, stats.jitterMs, 0.01f)
        assertEquals(12.50f, stats.netMBs, 0.01f)
        assertEquals(32, stats.queueDepth)
        assertEquals(45, stats.seekLatencyMs)
        assertEquals(90.0f, stats.smoothedFps, 0.01f)
        assertEquals(11.1f, stats.frameTimeMs, 0.01f)
        assertEquals(8.50f, stats.gpuTimeMs, 0.01f)
        assertEquals(8.20f, stats.smoothedGpuTimeMs, 0.01f)
        assertEquals("AUTO", stats.upscalingMode)
        assertEquals(0.35f, stats.upscalingSharpness, 0.01f)
        assertTrue(stats.mqsrEnabled)
        assertEquals("HIGH", stats.qualityLevel)
        assertEquals("RECOVERY", stats.qualityReason)
        assertEquals(14, stats.drawCallCount)
        assertEquals(18432L, stats.triangleCount)
        assertEquals(2, stats.stutterCount)
        assertEquals(0, stats.freezeCount)
        assertEquals(1, stats.thermalLevel)
        assertEquals(1.0f, stats.renderScale, 0.01f)
        assertEquals(90.0f, stats.refreshRate, 0.01f)
        assertEquals(-3.5f, stats.avDriftMs, 0.01f)
        assertEquals(15.2f, stats.netLastFetchMs, 0.01f)
        assertEquals(1024L, stats.netBlocksFetched)
        assertEquals(4L, stats.netBlocksDiscarded)
        assertTrue(stats.foveationEnabled)
        assertEquals(1, stats.spatialAudioMode)
        assertTrue(stats.spatialHeadTracking)
        assertEquals(1.25f, stats.playbackSpeed, 0.01f)
        assertEquals(0.80f, stats.audioVolume, 0.01f)
        assertEquals(0, stats.audioTrackIndex)
        assertEquals(2, stats.audioTrackCount)
        assertEquals(1, stats.subtitleTrackIndex)
        assertEquals(200, stats.subtitleOffsetMs)
        assertEquals(3, stats.videoStallCount)
        assertEquals(1200, stats.videoStatsAgeMs)
        assertEquals(340, stats.networkStatsAgeMs)
        assertEquals(0, stats.audioStatsAgeMs)
        assertEquals(0, stats.renderStatsAgeMs)
        assertEquals(2L, stats.networkFetchFailures)
        assertEquals(5, stats.networkSequentialStreak)
        assertTrue(stats.networkThrottled)
        assertEquals(40, stats.audioQueueDepth)
        assertEquals(1L, stats.decodeErrorCount)
        assertEquals(7L, stats.demuxCorruptPacketCount)
        assertEquals(9L, stats.audioUnderrunCount)
        assertEquals(12, stats.loadPhaseDemuxOpenMs)
        assertEquals(45, stats.loadPhaseDecoderReadyMs)
        assertEquals(60, stats.loadPhaseAudioReadyMs)
        assertEquals(1023, stats.perfMetricsValidMask)
        assertEquals(3.10f, stats.perfAppCpuFrametimeMs, 0.01f)
        assertEquals(4.20f, stats.perfAppGpuFrametimeMs, 0.01f)
        assertEquals(18.50f, stats.perfMotionToPhotonLatencyMs, 0.01f)
        assertEquals(2.00f, stats.perfCompositorCpuFrametimeMs, 0.01f)
        assertEquals(2.50f, stats.perfCompositorGpuFrametimeMs, 0.01f)
        assertEquals(3, stats.perfCompositorDroppedFrameCount)
        assertEquals(1, stats.perfCompositorSpacewarpMode)
        assertEquals(45.00f, stats.perfDeviceCpuUtilAverage, 0.01f)
        assertEquals(80.00f, stats.perfDeviceCpuUtilWorst, 0.01f)
        assertEquals(60.00f, stats.perfDeviceGpuUtil, 0.01f)
        assertEquals(listOf(100, 50, 20, 10, 5, 3, 2, 1), stats.histBuckets)
    }

    @Test
    fun stallCountAndFreshnessFieldsDefaultToZeroWhenAbsent() {
        // D-02/D-04/F4: sessoes gravadas antes destas fases (ou o caminho GLES, que nao
        // popula estes campos) nao tem estas chaves no wire — devem cair no default, nunca
        // crashar.
        val stats = DebugStatsParser.parse("backend\tGLES\nvideo_fps\t30.0\n")
        assertNotNull(stats)
        stats!!
        assertEquals(0, stats.videoStallCount)
        assertEquals(0, stats.videoStatsAgeMs)
        assertEquals(0, stats.networkStatsAgeMs)
        assertEquals(0, stats.audioStatsAgeMs)
        assertEquals(0, stats.renderStatsAgeMs)
        assertEquals(0L, stats.networkFetchFailures)
        assertEquals(0, stats.networkSequentialStreak)
        assertFalse(stats.networkThrottled)
        assertEquals(0, stats.audioQueueDepth)
        assertEquals(0L, stats.decodeErrorCount)
        assertEquals(0L, stats.demuxCorruptPacketCount)
        assertEquals(0L, stats.audioUnderrunCount)
        assertEquals(0, stats.loadPhaseDemuxOpenMs)
        assertEquals(0, stats.loadPhaseDecoderReadyMs)
        assertEquals(0, stats.loadPhaseAudioReadyMs)
        assertEquals(0, stats.perfMetricsValidMask)
        assertEquals(0.0f, stats.perfAppCpuFrametimeMs, 0.01f)
        assertEquals(0.0f, stats.perfAppGpuFrametimeMs, 0.01f)
        assertEquals(0.0f, stats.perfMotionToPhotonLatencyMs, 0.01f)
        assertEquals(0.0f, stats.perfCompositorCpuFrametimeMs, 0.01f)
        assertEquals(0.0f, stats.perfCompositorGpuFrametimeMs, 0.01f)
        assertEquals(0, stats.perfCompositorDroppedFrameCount)
        assertEquals(0, stats.perfCompositorSpacewarpMode)
        assertEquals(0.0f, stats.perfDeviceCpuUtilAverage, 0.01f)
        assertEquals(0.0f, stats.perfDeviceCpuUtilWorst, 0.01f)
        assertEquals(0.0f, stats.perfDeviceGpuUtil, 0.01f)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0), stats.histBuckets)
    }

    @Test
    fun ignoresUnknownAndMalformedLinesWithoutFailing() {
        val wire = "backend\tGLES\n" +
            "unknown_future_field\tsome_value\n" +
            "invalid_line_without_tab\n" +
            "video_fps\t30.0\n"

        val stats = DebugStatsParser.parse(wire)
        assertNotNull(stats)
        stats!!

        assertEquals("GLES", stats.backend)
        assertEquals(30.0f, stats.videoFps, 0.01f)
    }

    @Test
    fun returnsNullOnEmptyOrError() {
        assertNull(DebugStatsParser.parse(""))
        assertNull(DebugStatsParser.parse("   \n\t\n  "))
        assertNull(DebugStatsParser.parse("ERROR: Native error"))
    }

    @Test
    fun featureFlagDefaultsToFalse() {
        assertFalse(FeatureFlags.Flag.DEBUG_STATS_PANEL.defaultEnabled)
        assertEquals("debug_stats_panel", FeatureFlags.Flag.DEBUG_STATS_PANEL.key)
    }
}
