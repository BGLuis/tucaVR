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
    const val CSV_HEADER =
        "timestamp_ms,session_id,elapsed_s,backend,screen_mode,stereo_layout,polar_180,swap_eyes,video_status,frame_gap_ms,video_fps,decoded_fps,output_fps,dropped_fps,jitter_ms,net_mbs,video_q_depth,seek_ms,smoothed_fps,frame_ms,gpu_time_ms,smoothed_gpu_time_ms,upscaling_mode,upscaling_sharpness,mqsr_enabled,stutter_count,freeze_count,thermal_level,scale,refresh_rate,av_drift_ms,net_last_fetch_ms,net_blocks_fetched,net_blocks_discarded,foveation,spatial_audio,head_tracking,speed,volume,audio_track,audio_track_count,sub_track,sub_offset_ms,source_type,source_redacted"

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
     * Extrai tipo e caminho redigido a partir do [PlaybackSource].
     */
    fun extractSourceInfo(source: PlaybackSource?): Pair<String, String> = when (source) {
        is PlaybackSource.LocalFile -> "LocalFile" to source.path
        is PlaybackSource.Http -> "Http" to redactSource(source.url)
        is PlaybackSource.Smb -> "Smb" to "smb://${source.server.host}:${source.server.port}/${source.server.share}/${source.path}"
        is PlaybackSource.Ftp -> "Ftp" to "ftp://${source.server.host}:${source.server.port}/${source.path}"
        is PlaybackSource.Sftp -> "Sftp" to "sftp://${source.server.host}:${source.server.port}/${source.path}"
        is PlaybackSource.Nfs -> "Nfs" to "nfs://${source.server.host}:${source.server.port}/${source.path}"
        is PlaybackSource.Dlna -> "Dlna" to redactSource(source.url)
        null -> "Unknown" to ""
    }

    /**
     * Converte o texto do HUD (TSV moderno ou pipe-separated legado) em uma linha formatada de CSV.
     */
    fun parseHudToCsvRow(
        hudText: String,
        sessionId: String,
        timestampMs: Long,
        source: PlaybackSource?,
        elapsedSeconds: Float = 0f
    ): String {
        val (sourceType, sourceRedacted) = extractSourceInfo(source)

        fun sanitize(s: String): String =
            if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
                "\"${s.replace("\"", "\"\"")}\""
            } else s

        var backend = "UNKNOWN"
        var screenMode = "UNKNOWN"
        var stereoLayout = 0
        var polar180 = 0
        var swapEyes = 0
        var videoStatus = "ativo"
        var frameGapMs = 0f
        var videoFps = 0f
        var decodedFps = 0f
        var outputFps = 0f
        var droppedFps = 0f
        var jitterMs = 0f
        var netMbs = 0f
        var videoQueueDepth = 0
        var seekMs = 0
        var smoothedFps = 0f
        var frameMs = 0f
        var gpuTimeMs = 0f
        var smoothedGpuTimeMs = 0f
        var upscalingMode = "OFF"
        var upscalingSharpness = 0f
        var mqsrEnabled = 0
        var stutterCount = 0
        var freezeCount = 0
        var thermalLevel = 0
        var scale = 1.0f
        var refreshRate = 90.0f
        var avDriftMs = 0f
        var netLastFetchMs = 0f
        var netBlocksFetched = 0L
        var netBlocksDiscarded = 0L
        var foveation = 0
        var spatialAudio = 0
        var headTracking = 0
        var speed = 1.0f
        var volume = 1.0f
        var audioTrack = 0
        var audioTrackCount = 0
        var subTrack = -1
        var subOffsetMs = 0

        if (hudText.contains('\t')) {
            // Formato TSV estruturado emitido por SerializeDebugStats
            hudText.lineSequence().forEach { line ->
                val tabIdx = line.indexOf('\t')
                if (tabIdx != -1) {
                    val key = line.substring(0, tabIdx).trim()
                    val value = line.substring(tabIdx + 1).trim()
                    when (key) {
                        "backend" -> backend = value
                        "screen_mode" -> screenMode = value
                        "stereo_layout" -> stereoLayout = value.toIntOrNull() ?: 0
                        "polar_180" -> polar180 = value.toIntOrNull() ?: 0
                        "swap_eyes" -> swapEyes = value.toIntOrNull() ?: 0
                        "has_frame" -> videoStatus = if (value == "1") "ativo" else "inativo"
                        "frame_gap_ms" -> frameGapMs = value.toFloatOrNull() ?: 0f
                        "video_fps" -> videoFps = value.toFloatOrNull() ?: 0f
                        "decoded_fps" -> decodedFps = value.toFloatOrNull() ?: 0f
                        "output_fps" -> outputFps = value.toFloatOrNull() ?: 0f
                        "dropped_fps" -> droppedFps = value.toFloatOrNull() ?: 0f
                        "jitter_ms" -> jitterMs = value.toFloatOrNull() ?: 0f
                        "net_mbs" -> netMbs = value.toFloatOrNull() ?: 0f
                        "queue_depth" -> videoQueueDepth = value.toIntOrNull() ?: 0
                        "seek_latency_ms" -> seekMs = value.toIntOrNull() ?: 0
                        "smoothed_fps" -> smoothedFps = value.toFloatOrNull() ?: 0f
                        "frame_time_ms" -> frameMs = value.toFloatOrNull() ?: 0f
                        "gpu_time_ms" -> gpuTimeMs = value.toFloatOrNull() ?: 0f
                        "smoothed_gpu_time_ms" -> smoothedGpuTimeMs = value.toFloatOrNull() ?: 0f
                        "upscaling_mode" -> upscalingMode = value
                        "upscaling_sharpness" -> upscalingSharpness = value.toFloatOrNull() ?: 0f
                        "mqsr_enabled" -> mqsrEnabled = value.toIntOrNull() ?: 0
                        "stutter_count" -> stutterCount = value.toIntOrNull() ?: 0
                        "freeze_count" -> freezeCount = value.toIntOrNull() ?: 0
                        "thermal_level" -> thermalLevel = value.toIntOrNull() ?: 0
                        "render_scale" -> scale = value.toFloatOrNull() ?: 1.0f
                        "refresh_rate" -> refreshRate = value.toFloatOrNull() ?: 90.0f
                        "av_drift_ms" -> avDriftMs = value.toFloatOrNull() ?: 0f
                        "net_last_fetch_ms" -> netLastFetchMs = value.toFloatOrNull() ?: 0f
                        "net_blocks_fetched" -> netBlocksFetched = value.toLongOrNull() ?: 0L
                        "net_blocks_discarded" -> netBlocksDiscarded = value.toLongOrNull() ?: 0L
                        "foveation" -> foveation = value.toIntOrNull() ?: 0
                        "spatial_audio" -> spatialAudio = value.toIntOrNull() ?: 0
                        "head_tracking" -> headTracking = value.toIntOrNull() ?: 0
                        "speed" -> speed = value.toFloatOrNull() ?: 1.0f
                        "volume" -> volume = value.toFloatOrNull() ?: 1.0f
                        "audio_track" -> audioTrack = value.toIntOrNull() ?: 0
                        "audio_track_count" -> audioTrackCount = value.toIntOrNull() ?: 0
                        "sub_track" -> subTrack = value.toIntOrNull() ?: -1
                        "sub_offset_ms" -> subOffsetMs = value.toIntOrNull() ?: 0
                    }
                }
            }
        } else {
            // Formato legado delimitado por '|' (preservado para compatibilidade retroativa)
            val sections = hudText.split('|').map { it.trim() }
            backend = sections.getOrNull(0) ?: "UNKNOWN"
            screenMode = sections.getOrNull(1) ?: "UNKNOWN"

            for (sec in sections) {
                val tokens = sec.split(Regex("\\s+"))
                for (token in tokens) {
                    when {
                        token.startsWith("stereoLayout=") -> stereoLayout = token.removePrefix("stereoLayout=").toIntOrNull() ?: 0
                        token.startsWith("polar180=") -> polar180 = token.removePrefix("polar180=").toIntOrNull() ?: 0
                        token.startsWith("swap=") -> swapEyes = token.removePrefix("swap=").toIntOrNull() ?: 0
                        token.startsWith("swapEyes=") -> swapEyes = token.removePrefix("swapEyes=").toIntOrNull() ?: 0
                        token.startsWith("video=") -> videoStatus = token.removePrefix("video=")
                        token.startsWith("vidGap=") -> frameGapMs = token.removePrefix("vidGap=").removeSuffix("ms").toFloatOrNull() ?: 0f
                        token.startsWith("vidFps=") -> videoFps = token.removePrefix("vidFps=").toFloatOrNull() ?: 0f
                        token.startsWith("decFps=") -> decodedFps = token.removePrefix("decFps=").toFloatOrNull() ?: 0f
                        token.startsWith("outFps=") -> outputFps = token.removePrefix("outFps=").toFloatOrNull() ?: 0f
                        token.startsWith("drop=") -> droppedFps = token.removePrefix("drop=").toFloatOrNull() ?: 0f
                        token.startsWith("jitter=") -> jitterMs = token.removePrefix("jitter=").removeSuffix("ms").toFloatOrNull() ?: 0f
                        token.startsWith("net=") -> netMbs = token.removePrefix("net=").removeSuffix("MB/s").toFloatOrNull() ?: 0f
                        token.startsWith("q=") -> videoQueueDepth = token.removePrefix("q=").toIntOrNull() ?: 0
                        token.startsWith("seekMs=") -> seekMs = token.removePrefix("seekMs=").toIntOrNull() ?: 0
                        token.startsWith("stutter=") -> stutterCount = token.removePrefix("stutter=").toIntOrNull() ?: 0
                        token.startsWith("freeze=") -> freezeCount = token.removePrefix("freeze=").toIntOrNull() ?: 0
                        token.startsWith("thermal=") -> thermalLevel = token.removePrefix("thermal=").toIntOrNull() ?: 0
                        token.startsWith("scale=") -> scale = token.removePrefix("scale=").toFloatOrNull() ?: 1.0f
                        token.endsWith("fps") && !token.contains('=') -> smoothedFps = token.removeSuffix("fps").toFloatOrNull() ?: 0f
                        token.endsWith("ms") && !token.contains('=') -> frameMs = token.removeSuffix("ms").toFloatOrNull() ?: 0f
                    }
                }
            }
        }

        return "$timestampMs,${sanitize(sessionId)},${String.format(Locale.US, "%.2f", elapsedSeconds)}," +
            "${sanitize(backend)},${sanitize(screenMode)},$stereoLayout,$polar180,$swapEyes," +
            "${sanitize(videoStatus)},${String.format(Locale.US, "%.1f", frameGapMs)}," +
            "${String.format(Locale.US, "%.1f", videoFps)},${String.format(Locale.US, "%.1f", decodedFps)}," +
            "${String.format(Locale.US, "%.1f", outputFps)},${String.format(Locale.US, "%.1f", droppedFps)}," +
            "${String.format(Locale.US, "%.1f", jitterMs)},${String.format(Locale.US, "%.2f", netMbs)}," +
            "$videoQueueDepth,$seekMs,${String.format(Locale.US, "%.1f", smoothedFps)}," +
            "${String.format(Locale.US, "%.1f", frameMs)},${String.format(Locale.US, "%.2f", gpuTimeMs)}," +
            "${String.format(Locale.US, "%.2f", smoothedGpuTimeMs)},${sanitize(upscalingMode)}," +
            "${String.format(Locale.US, "%.2f", upscalingSharpness)},$mqsrEnabled,$stutterCount,$freezeCount," +
            "$thermalLevel,${String.format(Locale.US, "%.2f", scale)},${String.format(Locale.US, "%.1f", refreshRate)}," +
            "${String.format(Locale.US, "%.1f", avDriftMs)},${String.format(Locale.US, "%.1f", netLastFetchMs)}," +
            "$netBlocksFetched,$netBlocksDiscarded,$foveation,$spatialAudio,$headTracking," +
            "${String.format(Locale.US, "%.2f", speed)},${String.format(Locale.US, "%.2f", volume)}," +
            "$audioTrack,$audioTrackCount,$subTrack,$subOffsetMs," +
            "${sanitize(sourceType)},${sanitize(sourceRedacted)}"
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
