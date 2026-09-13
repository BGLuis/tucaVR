package com.tucavr.debug

import android.content.Context
import com.tucavr.FeatureFlags
import com.tucavr.navigation.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exportador assíncrono de séries temporais de telemetria em formato CSV (N2).
 *
 * Salva métricas periódicas (1 Hz) em `getExternalFilesDir("debug")/session-<id>-<timestamp>.csv`.
 * Executa em thread/coroutine de I/O em background, com buffer não-bloqueante e redação
 * obrigatória de credenciais.
 */
object DebugTelemetryExporter {
    // Versão do schema do CSV (D-05): incrementar sempre que CSV_HEADER mudar de forma
    // incompatível (coluna adicionada/removida/reordenada). Sem isto, esquemas antigos e
    // novos convivem em disco sem nenhum jeito de uma ferramenta de análise distingui-los.
    // v2 (F1): + video_stall_count, video_stats_age_ms, network_stats_age_ms,
    // audio_stats_age_ms, render_stats_age_ms (D-02/D-04).
    // v3 (F4): + network_fetch_failures, network_sequential_streak, network_throttled,
    // audio_queue_depth, decode_error_count, demux_corrupt_packet_count, audio_underrun_count,
    // load_phase_demux_open_ms, load_phase_decoder_ready_ms, load_phase_audio_ready_ms.
    // v4 (F3): + perf_metrics_valid_mask e os 10 contadores XR_META_performance_metrics
    // (diagnostico apenas — NUNCA usar para governar QualityController, ver 8.1 do relatorio).
    // v5 (F5 G2): + hist_bucket_0..7 (histograma cumulativo de frame time, ver
    // kFrameTimeHistogramEdgesMs em vr_player_app_vulkan.cpp).
    const val SCHEMA_VERSION = 5

    const val CSV_HEADER =
        "schema_version,timestamp_ms,session_id,elapsed_s,backend,screen_mode,stereo_layout,polar_180,swap_eyes,video_status,frame_gap_ms,video_fps,decoded_fps,output_fps,dropped_fps,jitter_ms,net_mbs,video_q_depth,seek_ms,smoothed_fps,frame_ms,gpu_time_ms,smoothed_gpu_time_ms,upscaling_mode,upscaling_sharpness,mqsr_enabled,stutter_count,freeze_count,thermal_level,scale,refresh_rate,av_drift_ms,net_last_fetch_ms,net_blocks_fetched,net_blocks_discarded,foveation,spatial_audio,head_tracking,speed,volume,audio_track,audio_track_count,sub_track,sub_offset_ms,quality_level,quality_reason,draw_call_count,triangle_count,video_stall_count,video_stats_age_ms,network_stats_age_ms,audio_stats_age_ms,render_stats_age_ms,network_fetch_failures,network_sequential_streak,network_throttled,audio_queue_depth,decode_error_count,demux_corrupt_packet_count,audio_underrun_count,load_phase_demux_open_ms,load_phase_decoder_ready_ms,load_phase_audio_ready_ms,perf_metrics_valid_mask,perf_app_cpu_frametime_ms,perf_app_gpu_frametime_ms,perf_motion_to_photon_latency_ms,perf_compositor_cpu_frametime_ms,perf_compositor_gpu_frametime_ms,perf_compositor_dropped_frame_count,perf_compositor_spacewarp_mode,perf_device_cpu_util_average,perf_device_cpu_util_worst,perf_device_gpu_util,hist_bucket_0,hist_bucket_1,hist_bucket_2,hist_bucket_3,hist_bucket_4,hist_bucket_5,hist_bucket_6,hist_bucket_7,source_type,source_redacted"

    private const val MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024L // 20 MB limite por arquivo
    private const val SAMPLE_INTERVAL_MS = 1000L // 1 Hz amostragem

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<ExportTask>(Channel.BUFFERED)

    @Volatile
    private var lastSampleTimestampMs = 0L

    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    private var currentWriter: PrintWriter? = null

    @Volatile
    private var currentFile: File? = null

    @Volatile
    private var currentBytesWritten = 0L

    private sealed class ExportTask {
        data class WriteRow(val debugDir: File, val sessionId: String, val row: String) : ExportTask()
        object CloseSession : ExportTask()
    }

    init {
        scope.launch {
            for (task in channel) {
                when (task) {
                    is ExportTask.WriteRow -> handleWriteRow(task.debugDir, task.sessionId, task.row)
                    is ExportTask.CloseSession -> handleCloseSession()
                }
            }
        }
    }

    /**
     * Redige senhas ou tokens presentes em URLs / caminhos de rede.
     */
    fun redactSource(raw: String): String {
        if (raw.isBlank()) return ""
        val schemeIdx = raw.indexOf("://")
        if (schemeIdx != -1) {
            val scheme = raw.substring(0, schemeIdx + 3)
            val rest = raw.substring(schemeIdx + 3)
            val authority = rest.substringBefore('/')
            val atIdx = authority.lastIndexOf('@')
            if (atIdx != -1) {
                val pathPart = rest.substring(authority.length)
                val hostPart = authority.substring(atIdx + 1)
                val userPass = authority.substring(0, atIdx)
                val user = userPass.substringBefore(':')
                return if (user.isEmpty()) {
                    "${scheme}***@$hostPart$pathPart"
                } else {
                    "$scheme$user:***@$hostPart$pathPart"
                }
            }
        }
        return raw
    }

    /**
     * Extrai tipo e caminho redigido a partir do [PlaybackSource]. Toda saída desta função é
     * destinada a artefatos que saem do device (CSV de telemetria, relatório de crash — ver
     * D-03 em docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md) — por isso os 5 ramos de rede
     * abaixo passam pela mesma [redactSource] usada por Http/Dlna, em vez de montar a URL
     * manualmente sem redação (o bug original: uma coluna chamada "source_redacted" que não
     * redigia nada nesses 5 ramos). Nenhum destes ramos referencia `server.password` — a
     * senha nunca passa por aqui; o vazamento de D-03 estava em VRActivity.kt interpolando o
     * data class inteiro diretamente, sem nunca chamar esta função.
     */
    fun extractSourceInfo(source: PlaybackSource?): Pair<String, String> = when (source) {
        is PlaybackSource.LocalFile -> "LocalFile" to source.path
        is PlaybackSource.Http -> "Http" to redactSource(source.url)
        is PlaybackSource.Smb -> "Smb" to redactSource("smb://${source.server.host}:${source.server.port}/${source.server.share}/${source.path}")
        is PlaybackSource.Ftp -> "Ftp" to redactSource("ftp://${source.server.host}:${source.server.port}/${source.path}")
        is PlaybackSource.Sftp -> "Sftp" to redactSource("sftp://${source.server.host}:${source.server.port}/${source.path}")
        is PlaybackSource.Nfs -> "Nfs" to redactSource("nfs://${source.server.host}:${source.server.port}/${source.path}")
        is PlaybackSource.Dlna -> "Dlna" to redactSource(source.url)
        is PlaybackSource.Webdav -> "Webdav" to redactSource("webdav://${source.server.host}:${source.server.port}${source.server.path}/${source.path}")
        null -> "Unknown" to ""
    }

    /**
     * Converte o texto do HUD (TSV emitido por `SerializeDebugStats`) em uma linha formatada
     * de CSV. Única fonte de parsing é [DebugStatsParser] (F0): não há mais um `when(key)`
     * paralelo aqui — quem quiser mudar o contrato de um campo mexe em [DebugStatsParser] e
     * em `native/src/debug_stats.h`, só.
     */
    fun parseHudToCsvRow(
        hudText: String,
        sessionId: String,
        timestampMs: Long,
        source: PlaybackSource?,
        elapsedSeconds: Float = 0f
    ): String {
        val (sourceType, sourceRedacted) = extractSourceInfo(source)
        val stats = DebugStatsParser.parse(hudText) ?: NativeDebugStats()

        fun sanitize(s: String): String =
            if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
                "\"${s.replace("\"", "\"\"")}\""
            } else s

        fun f1(v: Float) = String.format(Locale.US, "%.1f", v)
        fun f2(v: Float) = String.format(Locale.US, "%.2f", v)
        fun b(v: Boolean) = if (v) 1 else 0

        return listOf(
            SCHEMA_VERSION.toString(),
            timestampMs.toString(),
            sanitize(sessionId),
            String.format(Locale.US, "%.2f", elapsedSeconds),
            sanitize(stats.backend),
            sanitize(stats.screenMode),
            stats.stereoLayout.toString(),
            stats.polar180.toString(),
            stats.swapEyes.toString(),
            if (stats.hasFrame) "ativo" else "inativo",
            f1(stats.frameGapMs),
            f1(stats.videoFps),
            f1(stats.decodedFps),
            f1(stats.outputFps),
            f1(stats.droppedFps),
            f1(stats.jitterMs),
            f2(stats.netMBs),
            stats.queueDepth.toString(),
            stats.seekLatencyMs.toString(),
            f1(stats.smoothedFps),
            f1(stats.frameTimeMs),
            f2(stats.gpuTimeMs),
            f2(stats.smoothedGpuTimeMs),
            sanitize(stats.upscalingMode),
            f2(stats.upscalingSharpness),
            b(stats.mqsrEnabled).toString(),
            stats.stutterCount.toString(),
            stats.freezeCount.toString(),
            stats.thermalLevel.toString(),
            f2(stats.renderScale),
            f1(stats.refreshRate),
            f1(stats.avDriftMs),
            f1(stats.netLastFetchMs),
            stats.netBlocksFetched.toString(),
            stats.netBlocksDiscarded.toString(),
            b(stats.foveationEnabled).toString(),
            stats.spatialAudioMode.toString(),
            b(stats.spatialHeadTracking).toString(),
            f2(stats.playbackSpeed),
            f2(stats.audioVolume),
            stats.audioTrackIndex.toString(),
            stats.audioTrackCount.toString(),
            stats.subtitleTrackIndex.toString(),
            stats.subtitleOffsetMs.toString(),
            sanitize(stats.qualityLevel),
            sanitize(stats.qualityReason),
            stats.drawCallCount.toString(),
            stats.triangleCount.toString(),
            stats.videoStallCount.toString(),
            stats.videoStatsAgeMs.toString(),
            stats.networkStatsAgeMs.toString(),
            stats.audioStatsAgeMs.toString(),
            stats.renderStatsAgeMs.toString(),
            stats.networkFetchFailures.toString(),
            stats.networkSequentialStreak.toString(),
            b(stats.networkThrottled).toString(),
            stats.audioQueueDepth.toString(),
            stats.decodeErrorCount.toString(),
            stats.demuxCorruptPacketCount.toString(),
            stats.audioUnderrunCount.toString(),
            stats.loadPhaseDemuxOpenMs.toString(),
            stats.loadPhaseDecoderReadyMs.toString(),
            stats.loadPhaseAudioReadyMs.toString(),
            stats.perfMetricsValidMask.toString(),
            f2(stats.perfAppCpuFrametimeMs),
            f2(stats.perfAppGpuFrametimeMs),
            f2(stats.perfMotionToPhotonLatencyMs),
            f2(stats.perfCompositorCpuFrametimeMs),
            f2(stats.perfCompositorGpuFrametimeMs),
            stats.perfCompositorDroppedFrameCount.toString(),
            stats.perfCompositorSpacewarpMode.toString(),
            f2(stats.perfDeviceCpuUtilAverage),
            f2(stats.perfDeviceCpuUtilWorst),
            f2(stats.perfDeviceGpuUtil),
            *stats.histBuckets.map { it.toString() }.toTypedArray(),
            sanitize(sourceType),
            sanitize(sourceRedacted)
        ).joinToString(",")
    }

    /**
     * Ponto de entrada chamado a partir de `VRActivity.updateDebugHud`.
     * Ignora chamadas se sessionId for nulo, vazio ou sentinela ("--------").
     */
    fun recordHudSample(
        context: Context,
        sessionId: String?,
        hudText: String,
        source: PlaybackSource?,
        elapsedSeconds: Float = 0f
    ) {
        if (!FeatureFlags.isEnabled(context, FeatureFlags.Flag.DEBUG_STATS_EXPORT)) {
            return
        }
        if (sessionId.isNullOrBlank() || sessionId == "--------") {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastSampleTimestampMs < SAMPLE_INTERVAL_MS) {
            return
        }
        lastSampleTimestampMs = now

        val debugDir = context.getExternalFilesDir("debug") ?: return
        val row = parseHudToCsvRow(hudText, sessionId, now, source, elapsedSeconds)
        channel.trySend(ExportTask.WriteRow(debugDir, sessionId, row))
    }

    /**
     * Encerra a sessão atual e descarrega os buffers de escrita.
     */
    fun onSessionEnded() {
        channel.trySend(ExportTask.CloseSession)
    }

    private fun handleWriteRow(debugDir: File, sessionId: String, row: String) {
        try {
            if (currentSessionId != sessionId || currentWriter == null || currentBytesWritten > MAX_FILE_SIZE_BYTES) {
                handleCloseSession()
                currentSessionId = sessionId
                if (!debugDir.exists()) debugDir.mkdirs()

                val timeStr = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(debugDir, "session-$sessionId-$timeStr.csv")
                val isNew = !file.exists() || file.length() == 0L
                val writer = PrintWriter(FileWriter(file, true))
                if (isNew) {
                    writer.println(CSV_HEADER)
                }
                currentFile = file
                currentWriter = writer
                currentBytesWritten = file.length()
            }

            currentWriter?.let { w ->
                w.println(row)
                w.flush()
                currentBytesWritten += row.length + 1
            }
        } catch (e: Exception) {
            VRLog.w("Erro ao gravar telemetria CSV: ${e.message}", e)
        }
    }

    private fun handleCloseSession() {
        try {
            currentWriter?.flush()
            currentWriter?.close()
        } catch (e: Exception) {
            VRLog.w("Erro ao fechar writer de telemetria: ${e.message}", e)
        } finally {
            currentWriter = null
            currentFile = null
            currentBytesWritten = 0L
        }
    }
}
