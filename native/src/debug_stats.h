#pragma once

#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>

// Flag global atômica para ligar/desligar o envio e serialização do HUD de debug
inline std::atomic<bool> g_debugStatsEnabled{false};

// Sobe uma única vez quando SerializeDebugStats trunca o buffer (evita spam no logcat a
// ~10 Hz); ver hudBuffer em vr_player_input_vulkan.h / vr_player_app.cpp.
inline std::atomic<bool> g_debugStatsTruncated{false};

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
    // T-decode-present-split: frames decodificados-mas-nao-liberados na
    // video_thread agora (ver get_video_presentation_pending no bridge).
    // So populado no backend Vulkan; fica 0 no GLES (congelado, sem essa
    // reestruturacao de decode/apresentacao).
    uint32_t videoPresentationPending = 0;
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
    // R-07 (docs/reports/PHASE-0.4-08-VERIFICACAO-PROFUNDA.md): draw calls e triangulos do
    // ultimo frame completo (ambos os olhos) — ver AppState.lastFrameDrawCallCount em
    // vr_player_app_vulkan.cpp.
    uint32_t drawCallCount = 0;
    uint64_t triangleCount = 0;

    // F1 (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md):
    // D-02 — contador cumulativo de episodios de stall de video JA CONCLUIDOS, distinto de
    // stutterCount/freezeCount acima (que medem o loop de render, nao a apresentacao de
    // video). So populado no backend Vulkan — ver AppState::videoStallCount.
    uint32_t videoStallCount = 0;
    // D-04 — idade em ms desde a ultima mudanca observada no grupo de campos correspondente;
    // 0 sempre que o grupo acabou de ser atualizado. "render" e sempre 0 (computado a cada
    // frame no proprio loop de render, nunca sujeito a espera). "audio" fica 0 ate F4 dar a
    // este grupo um contador monotonico real para rastrear. So populados no backend Vulkan.
    uint32_t videoStatsAgeMs = 0;
    uint32_t networkStatsAgeMs = 0;
    uint32_t audioStatsAgeMs = 0;
    uint32_t renderStatsAgeMs = 0;

    // F4 — coleta nova na origem: contadores que ja existiam no lado Rust (PrefetchStats,
    // HwDecoder, Demuxer, AudioOutput) mas nao tinham exposicao FFI nem campo no wire.
    uint64_t networkFetchFailures = 0;
    uint32_t networkSequentialStreak = 0;
    uint32_t networkThrottled = 0;
    uint32_t audioQueueDepth = 0;
    uint64_t decodeErrorCount = 0;
    uint64_t demuxCorruptPacketCount = 0;
    uint64_t audioUnderrunCount = 0;
    // F4: fases do load_at() (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md) — antes so no
    // logcat. 0 antes do primeiro load, ou nao mudam ate a proxima troca de video/sessao.
    uint32_t loadPhaseDemuxOpenMs = 0;
    uint32_t loadPhaseDecoderReadyMs = 0;
    uint32_t loadPhaseAudioReadyMs = 0;

    // F3 — XR_META_performance_metrics (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md, secao
    // 8.1). Diagnostico apenas — especificacao PROIBE usar para governar comportamento do app.
    // perfMetricsValidMask: bit N = 1 quando o contador correspondente trouxe um valor valido
    // na ultima amostra (1Hz); "nao suportado" fica distinto de zero (D-04) checando o bit
    // antes de confiar no numero, em vez de expor um booleano por contador.
    uint32_t perfMetricsValidMask = 0;
    float perfAppCpuFrametimeMs = 0.0f;      // bit 0
    float perfAppGpuFrametimeMs = 0.0f;      // bit 1
    float perfMotionToPhotonLatencyMs = 0.0f; // bit 2
    float perfCompositorCpuFrametimeMs = 0.0f; // bit 3
    float perfCompositorGpuFrametimeMs = 0.0f; // bit 4
    uint32_t perfCompositorDroppedFrameCount = 0; // bit 5
    uint32_t perfCompositorSpacewarpMode = 0; // bit 6
    float perfDeviceCpuUtilAverage = 0.0f;   // bit 7
    float perfDeviceCpuUtilWorst = 0.0f;     // bit 8
    float perfDeviceGpuUtil = 0.0f;          // bit 9

    // F5 G2 — histograma de frame time, 8 buckets cumulativos (ver
    // kFrameTimeHistogramEdgesMs em vr_player_app_vulkan.cpp: <11.1, <16.7, <20, <33.3, <50,
    // <100, <250, >=250 ms). Acumulado a cada frame no loop de render, nao a cada amostra do
    // HUD — e assim que captura a distribuicao real a 90Hz sem transportar 90 amostras/s.
    uint32_t histBucket0 = 0;
    uint32_t histBucket1 = 0;
    uint32_t histBucket2 = 0;
    uint32_t histBucket3 = 0;
    uint32_t histBucket4 = 0;
    uint32_t histBucket5 = 0;
    uint32_t histBucket6 = 0;
    uint32_t histBucket7 = 0;
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
        "presentation_pending\t%u\n"
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
        "draw_call_count\t%u\n"
        "triangle_count\t%llu\n"
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
        "sub_offset_ms\t%d\n"
        "video_stall_count\t%u\n"
        "video_stats_age_ms\t%u\n"
        "network_stats_age_ms\t%u\n"
        "audio_stats_age_ms\t%u\n"
        "render_stats_age_ms\t%u\n"
        "network_fetch_failures\t%llu\n"
        "network_sequential_streak\t%u\n"
        "network_throttled\t%u\n"
        "audio_queue_depth\t%u\n"
        "decode_error_count\t%llu\n"
        "demux_corrupt_packet_count\t%llu\n"
        "audio_underrun_count\t%llu\n"
        "load_phase_demux_open_ms\t%u\n"
        "load_phase_decoder_ready_ms\t%u\n"
        "load_phase_audio_ready_ms\t%u\n"
        "perf_metrics_valid_mask\t%u\n"
        "perf_app_cpu_frametime_ms\t%.2f\n"
        "perf_app_gpu_frametime_ms\t%.2f\n"
        "perf_motion_to_photon_latency_ms\t%.2f\n"
        "perf_compositor_cpu_frametime_ms\t%.2f\n"
        "perf_compositor_gpu_frametime_ms\t%.2f\n"
        "perf_compositor_dropped_frame_count\t%u\n"
        "perf_compositor_spacewarp_mode\t%u\n"
        "perf_device_cpu_util_average\t%.2f\n"
        "perf_device_cpu_util_worst\t%.2f\n"
        "perf_device_gpu_util\t%.2f\n"
        "hist_bucket_0\t%u\n"
        "hist_bucket_1\t%u\n"
        "hist_bucket_2\t%u\n"
        "hist_bucket_3\t%u\n"
        "hist_bucket_4\t%u\n"
        "hist_bucket_5\t%u\n"
        "hist_bucket_6\t%u\n"
        "hist_bucket_7\t%u\n",
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
        s.videoPresentationPending,
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
        s.drawCallCount,
        (unsigned long long)s.triangleCount,
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
        s.subtitleOffsetMs,
        s.videoStallCount,
        s.videoStatsAgeMs,
        s.networkStatsAgeMs,
        s.audioStatsAgeMs,
        s.renderStatsAgeMs,
        (unsigned long long)s.networkFetchFailures,
        s.networkSequentialStreak,
        s.networkThrottled,
        s.audioQueueDepth,
        (unsigned long long)s.decodeErrorCount,
        (unsigned long long)s.demuxCorruptPacketCount,
        (unsigned long long)s.audioUnderrunCount,
        s.loadPhaseDemuxOpenMs,
        s.loadPhaseDecoderReadyMs,
        s.loadPhaseAudioReadyMs,
        s.perfMetricsValidMask,
        s.perfAppCpuFrametimeMs,
        s.perfAppGpuFrametimeMs,
        s.perfMotionToPhotonLatencyMs,
        s.perfCompositorCpuFrametimeMs,
        s.perfCompositorGpuFrametimeMs,
        s.perfCompositorDroppedFrameCount,
        s.perfCompositorSpacewarpMode,
        s.perfDeviceCpuUtilAverage,
        s.perfDeviceCpuUtilWorst,
        s.perfDeviceGpuUtil,
        s.histBucket0,
        s.histBucket1,
        s.histBucket2,
        s.histBucket3,
        s.histBucket4,
        s.histBucket5,
        s.histBucket6,
        s.histBucket7
    );
    if (written < 0) {
        buffer[0] = '\0';
        return 0;
    }
    if ((size_t)written >= bufferSize) {
        buffer[bufferSize - 1] = '\0';
        if (!g_debugStatsTruncated.exchange(true)) {
            __android_log_print(ANDROID_LOG_WARN, "DebugStats",
                "HUD truncado: %d bytes necessarios, %zu disponiveis em hudBuffer", written, bufferSize);
        }
        return bufferSize - 1;
    }
    return (size_t)written;
}
