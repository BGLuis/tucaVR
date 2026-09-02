#pragma once

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>

// Flag global atômica para ligar/desligar o envio e serialização do HUD de debug
inline std::atomic<bool> g_debugStatsEnabled{false};

struct DebugStats {
    const char* backend = "UNKNOWN"; // "GLES" ou "VULKAN"
    const char* screenMode = "Flat2D";
    int stereoLayout = 0;
    int polar180 = 0;
    int swapEyes = 0;
    int hasActiveFrame = 0;
    float msSinceLastVideoFrame = 0.0f;
    float videoFps = 0.0f;
    float decodedFps = 0.0f;
    float outputFps = 0.0f;
    float droppedFps = 0.0f;
    float videoJitterMs = 0.0f;
    float netMBs = 0.0f;
    uint32_t videoQueueDepth = 0;
    uint32_t seekLatencyMs = 0;
    float smoothedFps = 0.0f;
    float lastFrameMs = 0.0f;
    int stutterCount = 0;
    int freezeCount = 0;
    uint32_t thermalLevel = 0;
    float renderResolutionScale = 1.0f;
    float displayRefreshRate = 0.0f;
    float avDriftMs = 0.0f;
    float netLastFetchMs = 0.0f;
    uint64_t netBlocksFetched = 0;
    uint64_t netBlocksDiscarded = 0;
    int foveationEnabled = 0;
    int spatialAudioMode = 0;
    int spatialHeadTracking = 0;
    float playbackSpeed = 1.0f;
    float audioVolume = 1.0f;
    int audioTrackIndex = 0;
    int audioTrackCount = 0;
    int subtitleTrackIndex = -1;
    int32_t subtitleOffsetMs = 0;
    float gpuTimeMs = 0.0f;
    float smoothedGpuTimeMs = 0.0f;
    const char* upscalingMode = "OFF";
    float upscalingSharpness = 0.0f;
    int mqsrEnabled = 0;
    const char* qualityLevel = "HIGH";
    const char* qualityReason = "NONE";
};

// Serializa a struct em formato TSV estruturado (chave\tvalor\n).
// Retorna o número de bytes escritos (excluindo null-terminator).
inline size_t SerializeDebugStats(const DebugStats& s, char* buffer, size_t bufferSize) {
    if (!buffer || bufferSize == 0) return 0;
    int written = snprintf(buffer, bufferSize,
        "backend\t%s\n"
        "screen_mode\t%s\n"
        "stereo_layout\t%d\n"
        "polar_180\t%d\n"
        "swap_eyes\t%d\n"
        "has_frame\t%d\n"
        "frame_gap_ms\t%.1f\n"
        "video_fps\t%.1f\n"
        "decoded_fps\t%.1f\n"
        "output_fps\t%.1f\n"
        "dropped_fps\t%.1f\n"
        "jitter_ms\t%.1f\n"
        "net_mbs\t%.2f\n"
        "queue_depth\t%u\n"
        "seek_latency_ms\t%u\n"
        "smoothed_fps\t%.1f\n"
        "frame_time_ms\t%.1f\n"
        "gpu_time_ms\t%.2f\n"
        "smoothed_gpu_time_ms\t%.2f\n"
        "upscaling_mode\t%s\n"
        "upscaling_sharpness\t%.2f\n"
        "mqsr_enabled\t%d\n"
        "quality_level\t%s\n"
        "quality_reason\t%s\n"
        "stutter_count\t%d\n"
        "freeze_count\t%d\n"
        "thermal_level\t%u\n"
        "render_scale\t%.2f\n"
        "refresh_rate\t%.1f\n"
        "av_drift_ms\t%.1f\n"
        "net_last_fetch_ms\t%.1f\n"
        "net_blocks_fetched\t%llu\n"
        "net_blocks_discarded\t%llu\n"
        "foveation\t%d\n"
        "spatial_audio\t%d\n"
        "head_tracking\t%d\n"
        "speed\t%.2f\n"
        "volume\t%.2f\n"
        "audio_track\t%d\n"
        "audio_track_count\t%d\n"
        "sub_track\t%d\n"
        "sub_offset_ms\t%d\n",
        s.backend ? s.backend : "UNKNOWN",
        s.screenMode ? s.screenMode : "Flat2D",
        s.stereoLayout,
        s.polar180,
        s.swapEyes,
        s.hasActiveFrame,
        s.msSinceLastVideoFrame,
        s.videoFps,
        s.decodedFps,
        s.outputFps,
        s.droppedFps,
        s.videoJitterMs,
        s.netMBs,
        s.videoQueueDepth,
        s.seekLatencyMs,
        s.smoothedFps,
        s.lastFrameMs,
        s.gpuTimeMs,
        s.smoothedGpuTimeMs,
        s.upscalingMode ? s.upscalingMode : "OFF",
        s.upscalingSharpness,
        s.mqsrEnabled,
        s.qualityLevel ? s.qualityLevel : "HIGH",
        s.qualityReason ? s.qualityReason : "NONE",
        s.stutterCount,
        s.freezeCount,
        s.thermalLevel,
        s.renderResolutionScale,
        s.displayRefreshRate,
        s.avDriftMs,
        s.netLastFetchMs,
        (unsigned long long)s.netBlocksFetched,
        (unsigned long long)s.netBlocksDiscarded,
        s.foveationEnabled,
        s.spatialAudioMode,
        s.spatialHeadTracking,
        s.playbackSpeed,
        s.audioVolume,
        s.audioTrackIndex,
        s.audioTrackCount,
        s.subtitleTrackIndex,
        s.subtitleOffsetMs
    );
    if (written < 0) {
        buffer[0] = '\0';
        return 0;
    }
    if ((size_t)written >= bufferSize) {
        buffer[bufferSize - 1] = '\0';
        return bufferSize - 1;
    }
    return (size_t)written;
}
