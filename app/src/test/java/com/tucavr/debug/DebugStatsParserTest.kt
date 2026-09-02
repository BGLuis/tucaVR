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
            "sub_offset_ms\t200\n"

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
