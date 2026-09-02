package com.tucavr.debug

/**
 * Representação estruturada dos dados brutos recebidos da wire TSV nativa
 * (produzida por `native/src/debug_stats.h::SerializeDebugStats`).
 */
data class NativeDebugStats(
    val backend: String = "UNKNOWN",
    val screenMode: String = "Flat2D",
    val stereoLayout: Int = 0,
    val polar180: Int = 0,
    val swapEyes: Int = 0,
    val hasFrame: Boolean = false,
    val frameGapMs: Float = 0.0f,
    val videoFps: Float = 0.0f,
    val decodedFps: Float = 0.0f,
    val outputFps: Float = 0.0f,
    val droppedFps: Float = 0.0f,
    val jitterMs: Float = 0.0f,
    val netMBs: Float = 0.0f,
    val queueDepth: Int = 0,
    val seekLatencyMs: Int = 0,
    val smoothedFps: Float = 0.0f,
    val frameTimeMs: Float = 0.0f,
    val stutterCount: Int = 0,
    val freezeCount: Int = 0,
    val thermalLevel: Int = 0,
    val renderScale: Float = 1.0f,
    val refreshRate: Float = 90.0f,
    val avDriftMs: Float = 0.0f,
    val netLastFetchMs: Float = 0.0f,
    val netBlocksFetched: Long = 0L,
    val netBlocksDiscarded: Long = 0L,
    val foveationEnabled: Boolean = false,
    val spatialAudioMode: Int = 0,
    val spatialHeadTracking: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val audioVolume: Float = 1.0f,
    val audioTrackIndex: Int = 0,
    val audioTrackCount: Int = 0,
    val subtitleTrackIndex: Int = -1,
    val subtitleOffsetMs: Int = 0,
    val gpuTimeMs: Float = 0.0f,
    val smoothedGpuTimeMs: Float = 0.0f,
    val upscalingMode: String = "OFF",
    val upscalingSharpness: Float = 0.0f,
    val mqsrEnabled: Boolean = false,
    val qualityLevel: String = "HIGH",
    val qualityReason: String = "NONE"
)

/**
 * Parser puro e testável na JVM para a wire de estatísticas técnicas.
 */
object DebugStatsParser {

    fun parse(wire: String): NativeDebugStats? {
        if (wire.isBlank() || wire.startsWith("ERROR:")) return null

        var backend = "UNKNOWN"
        var screenMode = "Flat2D"
        var stereoLayout = 0
        var polar180 = 0
        var swapEyes = 0
        var hasFrame = false
        var frameGapMs = 0.0f
        var videoFps = 0.0f
        var decodedFps = 0.0f
        var outputFps = 0.0f
        var droppedFps = 0.0f
        var jitterMs = 0.0f
        var netMBs = 0.0f
        var queueDepth = 0
        var seekLatencyMs = 0
        var smoothedFps = 0.0f
        var frameTimeMs = 0.0f
        var gpuTimeMs = 0.0f
        var smoothedGpuTimeMs = 0.0f
        var upscalingMode = "OFF"
        var upscalingSharpness = 0.0f
        var mqsrEnabled = false
        var qualityLevel = "HIGH"
        var qualityReason = "NONE"
        var stutterCount = 0
        var freezeCount = 0
        var thermalLevel = 0
        var renderScale = 1.0f
        var refreshRate = 90.0f
        var avDriftMs = 0.0f
        var netLastFetchMs = 0.0f
        var netBlocksFetched = 0L
        var netBlocksDiscarded = 0L
        var foveationEnabled = false
        var spatialAudioMode = 0
        var spatialHeadTracking = false
        var playbackSpeed = 1.0f
        var audioVolume = 1.0f
        var audioTrackIndex = 0
        var audioTrackCount = 0
        var subtitleTrackIndex = -1
        var subtitleOffsetMs = 0

        var recognizedKeys = 0

        wire.split("\n").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val parts = trimmed.split("\t")
            if (parts.size < 2) return@forEach

            val key = parts[0]
            val value = parts[1]

            when (key) {
                "backend" -> { backend = value; recognizedKeys++ }
                "screen_mode" -> { screenMode = value; recognizedKeys++ }
                "stereo_layout" -> { stereoLayout = value.toIntOrNull() ?: 0; recognizedKeys++ }
                "polar_180" -> { polar180 = value.toIntOrNull() ?: 0; recognizedKeys++ }
                "swap_eyes" -> { swapEyes = value.toIntOrNull() ?: 0; recognizedKeys++ }
                "has_frame" -> { hasFrame = value == "1"; recognizedKeys++ }
                "frame_gap_ms" -> { frameGapMs = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "video_fps" -> { videoFps = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "decoded_fps" -> { decodedFps = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "output_fps" -> { outputFps = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "dropped_fps" -> { droppedFps = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "jitter_ms" -> { jitterMs = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "net_mbs" -> { netMBs = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "queue_depth" -> { queueDepth = value.toIntOrNull() ?: 0; recognizedKeys++ }
                "seek_latency_ms" -> { seekLatencyMs = value.toIntOrNull() ?: 0; recognizedKeys++ }
                "smoothed_fps" -> { smoothedFps = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "frame_time_ms" -> { frameTimeMs = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "gpu_time_ms" -> { gpuTimeMs = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "smoothed_gpu_time_ms" -> { smoothedGpuTimeMs = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "upscaling_mode" -> { upscalingMode = value; recognizedKeys++ }
                "upscaling_sharpness" -> { upscalingSharpness = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "mqsr_enabled" -> { mqsrEnabled = value == "1"; recognizedKeys++ }
                "quality_level" -> { qualityLevel = value; recognizedKeys++ }
                "quality_reason" -> { qualityReason = value; recognizedKeys++ }
                "stutter_count" -> { stutterCount = value.toIntOrNull() ?: 0; recognizedKeys++ }
                "freeze_count" -> { freezeCount = value.toIntOrNull() ?: 0; recognizedKeys++ }
                "thermal_level" -> { thermalLevel = value.toIntOrNull() ?: 0; recognizedKeys++ }
                "render_scale" -> { renderScale = value.toFloatOrNull() ?: 1f; recognizedKeys++ }
                "refresh_rate" -> { refreshRate = value.toFloatOrNull() ?: 90f; recognizedKeys++ }
                "av_drift_ms" -> { avDriftMs = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "net_last_fetch_ms" -> { netLastFetchMs = value.toFloatOrNull() ?: 0f; recognizedKeys++ }
                "net_blocks_fetched" -> { netBlocksFetched = value.toLongOrNull() ?: 0L; recognizedKeys++ }
                "net_blocks_discarded" -> { netBlocksDiscarded = value.toLongOrNull() ?: 0L; recognizedKeys++ }
                "foveation" -> { foveationEnabled = value == "1"; recognizedKeys++ }
                "spatial_audio" -> { spatialAudioMode = value.toIntOrNull() ?: 0; recognizedKeys++ }
                "head_tracking" -> { spatialHeadTracking = value == "1"; recognizedKeys++ }
                "speed" -> { playbackSpeed = value.toFloatOrNull() ?: 1f; recognizedKeys++ }
                "volume" -> { audioVolume = value.toFloatOrNull() ?: 1f; recognizedKeys++ }
                "audio_track" -> { audioTrackIndex = value.toIntOrNull() ?: 0; recognizedKeys++ }
                "audio_track_count" -> { audioTrackCount = value.toIntOrNull() ?: 0; recognizedKeys++ }
                "sub_track" -> { subtitleTrackIndex = value.toIntOrNull() ?: -1; recognizedKeys++ }
                "sub_offset_ms" -> { subtitleOffsetMs = value.toIntOrNull() ?: 0; recognizedKeys++ }
            }
        }

        if (recognizedKeys == 0) return null

        return NativeDebugStats(
            backend = backend,
            screenMode = screenMode,
            stereoLayout = stereoLayout,
            polar180 = polar180,
            swapEyes = swapEyes,
            hasFrame = hasFrame,
            frameGapMs = frameGapMs,
            videoFps = videoFps,
            decodedFps = decodedFps,
            outputFps = outputFps,
            droppedFps = droppedFps,
            jitterMs = jitterMs,
            netMBs = netMBs,
            queueDepth = queueDepth,
            seekLatencyMs = seekLatencyMs,
            smoothedFps = smoothedFps,
            frameTimeMs = frameTimeMs,
            stutterCount = stutterCount,
            freezeCount = freezeCount,
            thermalLevel = thermalLevel,
            renderScale = renderScale,
            refreshRate = refreshRate,
            avDriftMs = avDriftMs,
            netLastFetchMs = netLastFetchMs,
            netBlocksFetched = netBlocksFetched,
            netBlocksDiscarded = netBlocksDiscarded,
            foveationEnabled = foveationEnabled,
            spatialAudioMode = spatialAudioMode,
            spatialHeadTracking = spatialHeadTracking,
            playbackSpeed = playbackSpeed,
            audioVolume = audioVolume,
            audioTrackIndex = audioTrackIndex,
            audioTrackCount = audioTrackCount,
            subtitleTrackIndex = subtitleTrackIndex,
            subtitleOffsetMs = subtitleOffsetMs,
            gpuTimeMs = gpuTimeMs,
            smoothedGpuTimeMs = smoothedGpuTimeMs,
            upscalingMode = upscalingMode,
            upscalingSharpness = upscalingSharpness,
            mqsrEnabled = mqsrEnabled,
            qualityLevel = qualityLevel,
            qualityReason = qualityReason
        )
    }
}
