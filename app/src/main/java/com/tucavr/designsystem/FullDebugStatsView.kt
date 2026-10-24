package com.tucavr.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tucavr.BuildConfig
import com.tucavr.R
import com.tucavr.codec.CodecCapabilityManager
import com.tucavr.codec.CodecSupportStatus
import com.tucavr.debug.BottleneckStage
import com.tucavr.debug.BottleneckStageAnalyzer
import com.tucavr.debug.DebugStatsParser
import com.tucavr.debug.NativeDebugStats
import com.tucavr.filebrowser.MediaMetadata
import com.tucavr.navigation.PlaybackSource
import java.util.Locale
import kotlin.math.max

/**
 * Layout de telemetria completa em tela cheia ("Stats for Nerds" full-panel view)
 * projetado para a superfície cilíndrica de 1920x960 sem barras de rolagem (Issue #99).
 *
 * - Metade superior: 4 colunas de dados diagnósticos organizados lado a lado.
 * - Metade inferior: 5 gráficos em tempo real (VoidChart) exibidos em paralelo.
 */
class FullDebugStatsView(
    context: Context,
    private val onClose: () -> Unit,
) : FrameLayout(context) {
    private val statViews = mutableMapOf<String, TextView>()
    private val charts = mutableMapOf<String, VoidChart>()

    private val bottleneckHistory = ArrayDeque<BottleneckStage>()
    private val bufferHealthHistory = ArrayDeque<Float>()
    private val netMbsHistory = ArrayDeque<Float>()
    private val avDriftHistory = ArrayDeque<Float>()
    private val gpuTimeHistory = ArrayDeque<Float>()

    companion object {
        private const val HISTORY_MAX_SAMPLES = 600
        private const val STALE_THRESHOLD_MS = 200
    }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.argb(235, 14, 14, 16))
        isClickable = true

        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                setPadding(
                    VoidTheme.dpToPx(context, 24f),
                    VoidTheme.dpToPx(context, 16f),
                    VoidTheme.dpToPx(context, 24f),
                    VoidTheme.dpToPx(context, 16f),
                )
            }

        // Header (Barra de título e botão fechar)
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = VoidTheme.dpToPx(context, 8f) }
            }

        val titleView =
            VoidText.title(context, context.getString(R.string.stats_full_title), sizeSp = 18f).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

        val closeBtn =
            VoidIconButton(
                context,
                R.drawable.ic_close,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                val btnSize = VoidTheme.dpToPx(context, 32f)
                minimumWidth = btnSize
                minimumHeight = btnSize
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                contentDescription = context.getString(R.string.common_close)
                setOnClickListener { onClose() }
            }

        header.addView(titleView)
        header.addView(closeBtn)
        root.addView(header)

        // Metade Superior: 4 colunas de dados
        val upperColumns =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1.0f,
                    ).apply { bottomMargin = VoidTheme.dpToPx(context, 8f) }
            }

        val col1 =
            buildSectionCard(
                context.getString(R.string.debug_stats_section_video_render),
                listOf(
                    "bottleneck" to R.string.debug_stats_label_bottleneck,
                    "resolution" to R.string.stats_metric_resolution,
                    "decoder" to R.string.stats_metric_decoder,
                    "fps" to R.string.stats_metric_fps,
                    "dropped_frames" to R.string.stats_metric_dropped,
                    "jitter" to R.string.debug_stats_label_video_jitter,
                    "stereo" to R.string.debug_stats_label_stereo_mode,
                    "render_scale" to R.string.debug_stats_label_render_scale,
                    "backend" to R.string.debug_stats_label_graphics_backend,
                ),
            )

        val col2 =
            buildSectionCard(
                context.getString(R.string.debug_stats_section_network_buffer),
                listOf(
                    "source" to R.string.debug_stats_label_source,
                    "net_speed" to R.string.debug_stats_label_network_speed,
                    "net_link_speed" to R.string.debug_stats_label_network_link_speed,
                    "buffer_queue" to R.string.debug_stats_label_buffer_queue,
                    "presentation_pending" to R.string.debug_stats_label_presentation_pending,
                    "fetch_latency" to R.string.debug_stats_label_fetch_latency,
                    "blocks" to R.string.debug_stats_label_blocks,
                    "seek_latency" to R.string.debug_stats_label_seek_latency,
                    "net_failures" to R.string.debug_stats_label_net_failures,
                ),
            )

        val col3 =
            buildSectionCard(
                context.getString(R.string.debug_stats_section_audio_sync),
                listOf(
                    "av_drift" to R.string.debug_stats_label_av_drift,
                    "audio_track" to R.string.debug_stats_label_audio_track,
                    "audio_volume" to R.string.player_btn_volume,
                    "playback_speed" to R.string.player_btn_speed,
                    "spatial_audio" to R.string.debug_stats_label_spatial_audio,
                    "head_tracking" to R.string.debug_stats_label_head_tracking,
                    "audio_underruns" to R.string.debug_stats_label_audio_underruns,
                    "audio_queue" to R.string.debug_stats_label_audio_queue,
                ),
            )

        val col4 =
            buildSectionCard(
                context.getString(R.string.debug_stats_section_system),
                listOf(
                    "thermal" to R.string.debug_stats_label_thermal,
                    "battery" to R.string.debug_stats_label_battery,
                    "cpu_time" to R.string.stats_metric_cpu_time,
                    "gpu_time" to R.string.stats_metric_gpu_time,
                    "cpu_util" to R.string.debug_stats_label_cpu_util,
                    "gpu_util" to R.string.debug_stats_label_gpu_util,
                    "stutter_freeze" to R.string.debug_stats_label_stutter_freeze,
                    "app_version" to R.string.debug_stats_label_app_version,
                ),
            )

        upperColumns.addView(col1)
        upperColumns.addView(col2)
        upperColumns.addView(col3)
        upperColumns.addView(col4)
        root.addView(upperColumns)

        // Metade Inferior: 5 gráficos diagnósticos
        val lowerCharts =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        0.95f,
                    )
            }

        lowerCharts.addView(buildChartColumn("g1", R.string.stats_chart_bottleneck_title))
        lowerCharts.addView(buildChartColumn("g2", R.string.stats_chart_network_title))
        lowerCharts.addView(buildChartColumn("g3", R.string.stats_chart_buffer_title))
        lowerCharts.addView(buildChartColumn("g4", R.string.debug_stats_chart_g4_title))
        lowerCharts.addView(buildChartColumn("g5", R.string.stats_chart_gpu_title))
        root.addView(lowerCharts)

        addView(root)
    }

    private fun buildSectionCard(
        title: String,
        rows: List<Pair<String, Int>>,
    ): LinearLayout {
        val col =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginEnd = VoidTheme.dpToPx(context, 8f)
                    }
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurface)
                        cornerRadius = VoidTheme.dp(context, 8f)
                        setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
                    }
                setPadding(
                    VoidTheme.dpToPx(context, 12f),
                    VoidTheme.dpToPx(context, 8f),
                    VoidTheme.dpToPx(context, 12f),
                    VoidTheme.dpToPx(context, 8f),
                )
            }

        val header =
            VoidText.title(context, title, sizeSp = 13f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
            }
        col.addView(header)

        rows.forEach { (key, labelRes) ->
            val row =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = VoidTheme.dpToPx(context, 1.5f)
                            bottomMargin = VoidTheme.dpToPx(context, 1.5f)
                        }
                }

            val label =
                TextView(context).apply {
                    text = context.getString(labelRes)
                    textSize = 11f
                    typeface = VoidTheme.typefaceBody
                    setTextColor(VoidTheme.colorTextSecondary)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
                }

            val value =
                TextView(context).apply {
                    text = "—"
                    textSize = 11f
                    typeface = VoidTheme.typefaceMono
                    setTextColor(VoidTheme.colorText)
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
                }

            statViews[key] = value
            row.addView(label)
            row.addView(value)
            col.addView(row)
        }

        return col
    }

    private fun buildChartColumn(
        key: String,
        titleRes: Int,
    ): LinearLayout {
        val col =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginEnd = VoidTheme.dpToPx(context, 6f)
                    }
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurface)
                        cornerRadius = VoidTheme.dp(context, 8f)
                        setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
                    }
                setPadding(
                    VoidTheme.dpToPx(context, 8f),
                    VoidTheme.dpToPx(context, 6f),
                    VoidTheme.dpToPx(context, 8f),
                    VoidTheme.dpToPx(context, 6f),
                )
            }

        val caption =
            TextView(context).apply {
                text = context.getString(titleRes)
                textSize = 11f
                typeface = VoidTheme.typefaceBody
                setTextColor(VoidTheme.colorTextSecondary)
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
            }
        col.addView(caption)

        val chart =
            VoidChart(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    )
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        cornerRadius = VoidTheme.dp(context, 6f)
                    }
            }
        charts[key] = chart
        col.addView(chart)

        return col
    }

    private fun setValue(
        key: String,
        text: String,
        ageMs: Int? = null,
    ) {
        val view = statViews[key] ?: return
        if (ageMs != null && ageMs > STALE_THRESHOLD_MS) {
            val formatted = String.format(Locale.US, "%s (%dms)", text, ageMs)
            view.text = formatted
            view.setTextColor(VoidTheme.colorTextSecondary)
        } else {
            view.text = text
            view.setTextColor(VoidTheme.colorText)
        }
    }

    fun updateStats(
        text: String,
        meta: MediaMetadata?,
        source: PlaybackSource?,
        isCharging: Boolean,
        batteryPercent: Int,
        isDebuggable: Boolean,
    ) {
        val stats = DebugStatsParser.parse(text) ?: return
        val videoTrack = meta?.videoTracks?.firstOrNull()
        val audioTrack = meta?.audioTracks?.firstOrNull()

        // 1. Vídeo
        val bottleneckText =
            when (BottleneckStageAnalyzer.analyze(stats)) {
                BottleneckStage.NONE -> "NONE"
                BottleneckStage.NETWORK -> "NETWORK"
                BottleneckStage.PRESENTATION -> "PRESENTATION"
            }
        statViews["bottleneck"]?.text = bottleneckText

        val resText =
            if (videoTrack != null && videoTrack.width > 0) {
                "${videoTrack.width}x${videoTrack.height} (${videoTrack.codec.uppercase()})"
            } else if (meta != null && meta.container.isNotEmpty()) {
                meta.container.uppercase()
            } else {
                "—"
            }
        statViews["resolution"]?.text = resText

        val videoCodec = videoTrack?.codec
        val decoderText =
            if (!videoCodec.isNullOrBlank()) {
                when (val status = CodecCapabilityManager.getStatus(videoCodec)) {
                    is CodecSupportStatus.Supported -> "${status.decoder.codecName} [HW]"
                    is CodecSupportStatus.SoftwareOnly -> "${status.decoder.codecName} [SW]"
                    is CodecSupportStatus.Unsupported -> context.getString(R.string.codec_badge_unsupported)
                }
            } else if (stats.backend.isNotBlank() && stats.backend != "UNKNOWN") {
                stats.backend
            } else {
                "—"
            }
        statViews["decoder"]?.text = decoderText

        setValue(
            "fps",
            String.format(Locale.US, "%.1f dec / %.1f out (%.0f Hz)", stats.decodedFps, stats.outputFps, stats.refreshRate),
            stats.videoStatsAgeMs,
        )

        val totalFrames = stats.decodedFps + stats.droppedFps
        val dropPct = if (totalFrames > 0f) (stats.droppedFps / totalFrames) * 100f else 0f
        setValue(
            "dropped_frames",
            String.format(Locale.US, "%.0f fps (%.1f%%)", stats.droppedFps, dropPct),
            stats.videoStatsAgeMs,
        )

        statViews["jitter"]?.text =
            String.format(Locale.US, "%.1f ms (gap %.1f ms)", stats.jitterMs, stats.frameGapMs)

        val swapStr = if (stats.swapEyes != 0) " [Swap]" else ""
        statViews["stereo"]?.text = "${stats.screenMode}$swapStr"

        val fovStr = if (stats.foveationEnabled) "Fov: On" else "Fov: Off"
        statViews["render_scale"]?.text =
            String.format(Locale.US, "%.2fx | %s", stats.renderScale, fovStr)

        statViews["backend"]?.text = stats.backend

        // 2. Rede
        val srcText =
            when (source) {
                is PlaybackSource.LocalFile -> "Local"
                is PlaybackSource.Http -> "HTTP"
                is PlaybackSource.Smb -> "SMB (${source.server.host})"
                is PlaybackSource.Ftp -> "FTP (${source.server.host})"
                is PlaybackSource.Sftp -> "SFTP (${source.server.host})"
                is PlaybackSource.Nfs -> "NFS (${source.server.host})"
                is PlaybackSource.Dlna -> "DLNA (${source.server.name})"
                is PlaybackSource.Webdav -> "WebDAV (${source.server.host})"
                null -> "None"
            }
        statViews["source"]?.text = srcText

        setValue("net_speed", String.format(Locale.US, "%.2f MiB/s", stats.netMBs), stats.networkStatsAgeMs)
        setValue("net_link_speed", String.format(Locale.US, "%.2f MiB/s", stats.netBlockMBs), stats.networkStatsAgeMs)
        setValue("buffer_queue", "${stats.queueDepth} pkts", stats.networkStatsAgeMs)
        setValue("presentation_pending", "${stats.presentationPending} frames", stats.networkStatsAgeMs)
        setValue("fetch_latency", String.format(Locale.US, "%.1f ms", stats.netLastFetchMs), stats.networkStatsAgeMs)
        setValue("blocks", "${stats.netBlocksFetched} / ${stats.netBlocksDiscarded}", stats.networkStatsAgeMs)
        statViews["seek_latency"]?.text = String.format(Locale.US, "%d ms", stats.seekLatencyMs)
        statViews["net_failures"]?.text = "${stats.networkFetchFailures}"

        // 3. Áudio
        setValue("av_drift", String.format(Locale.US, "%+.1f ms", stats.avDriftMs), stats.audioStatsAgeMs)
        val audioFmt =
            if (audioTrack != null) {
                "${audioTrack.codec.uppercase()} (${audioTrack.channels}ch)"
            } else {
                "${stats.audioTrackIndex + 1}/${stats.audioTrackCount}"
            }
        statViews["audio_track"]?.text = audioFmt
        statViews["audio_volume"]?.text = String.format(Locale.US, "%.0f%%", stats.audioVolume * 100f)
        statViews["playback_speed"]?.text = String.format(Locale.US, "%.2fx", stats.playbackSpeed)

        val spatialModeText =
            when (stats.spatialAudioMode) {
                0 -> "Off"
                1 -> "Binaural"
                2 -> "Downmix"
                else -> "${stats.spatialAudioMode}"
            }
        statViews["spatial_audio"]?.text = spatialModeText
        statViews["head_tracking"]?.text = if (stats.spatialHeadTracking) "Enabled" else "Disabled"
        statViews["audio_underruns"]?.text = "${stats.audioUnderrunCount}"
        statViews["audio_queue"]?.text = "${stats.audioQueueDepth}"

        // 4. Hardware
        val thermalName =
            when (stats.thermalLevel) {
                0 -> context.getString(R.string.thermal_level_normal)
                1 -> context.getString(R.string.thermal_level_light)
                2 -> context.getString(R.string.thermal_level_moderate)
                3 -> context.getString(R.string.thermal_level_severe)
                4 -> context.getString(R.string.thermal_level_critical)
                else -> "${stats.thermalLevel}"
            }
        statViews["thermal"]?.text = String.format(Locale.US, "%s (%d)", thermalName, stats.thermalLevel)

        val chargingStr = if (isCharging) " [Charging]" else ""
        statViews["battery"]?.text = String.format(Locale.US, "%d%%%s", batteryPercent, chargingStr)

        val cpuTime = if (stats.perfAppCpuFrametimeMs > 0f) stats.perfAppCpuFrametimeMs else stats.frameTimeMs
        val gpuTime = if (stats.smoothedGpuTimeMs > 0f) stats.smoothedGpuTimeMs else stats.perfAppGpuFrametimeMs
        statViews["cpu_time"]?.text = String.format(Locale.US, "%.1f ms", cpuTime)
        statViews["gpu_time"]?.text = String.format(Locale.US, "%.1f ms", gpuTime)

        statViews["cpu_util"]?.text =
            String.format(Locale.US, "%.0f%% (avg) / %.0f%%", stats.perfDeviceCpuUtilAverage * 100f, stats.perfDeviceCpuUtilWorst * 100f)
        statViews["gpu_util"]?.text = String.format(Locale.US, "%.0f%%", stats.perfDeviceGpuUtil * 100f)
        statViews["stutter_freeze"]?.text = "${stats.stutterCount} / ${stats.freezeCount}"
        val buildMode = if (isDebuggable) "Debug" else "Release"
        statViews["app_version"]?.text = String.format(Locale.US, "%s (%s)", BuildConfig.VERSION_NAME, buildMode)

        // Atualizar gráficos
        updateCharts(stats)
    }

    private fun updateCharts(stats: NativeDebugStats) {
        val bufferHealthSeconds = stats.bufferedAheadSec

        bottleneckHistory.addLast(BottleneckStageAnalyzer.analyze(stats))
        while (bottleneckHistory.size > HISTORY_MAX_SAMPLES) bottleneckHistory.removeFirst()

        bufferHealthHistory.addLast(bufferHealthSeconds)
        while (bufferHealthHistory.size > HISTORY_MAX_SAMPLES) bufferHealthHistory.removeFirst()

        netMbsHistory.addLast(stats.netMBs)
        while (netMbsHistory.size > HISTORY_MAX_SAMPLES) netMbsHistory.removeFirst()

        avDriftHistory.addLast(stats.avDriftMs)
        while (avDriftHistory.size > HISTORY_MAX_SAMPLES) avDriftHistory.removeFirst()

        val gpuTime = if (stats.smoothedGpuTimeMs > 0f) stats.smoothedGpuTimeMs else stats.perfAppGpuFrametimeMs
        gpuTimeHistory.addLast(gpuTime)
        while (gpuTimeHistory.size > HISTORY_MAX_SAMPLES) gpuTimeHistory.removeFirst()

        charts["g1"]?.timeline = bottleneckHistory.map { TimelineSample(it) }

        charts["g2"]?.let { chart ->
            chart.yMax = (stats.histBuckets.maxOrNull()?.takeIf { it > 0 } ?: 1).toFloat()
            chart.series =
                listOf(
                    ChartSeries(stats.histBuckets.map { it.toFloat() }, VoidChart.COLOR_NETWORK, SeriesStyle.BARS),
                )
        }

        charts["g3"]?.let { chart ->
            chart.yMin = 0f
            chart.yMax = 1f
            val bufferMax = (bufferHealthHistory.maxOrNull()?.takeIf { it > 0f } ?: 1f)
            val netMax = (netMbsHistory.maxOrNull()?.takeIf { it > 0f } ?: 1f)
            chart.series =
                listOf(
                    ChartSeries(bufferHealthHistory.toList(), VoidChart.COLOR_HEALTHY, SeriesStyle.AREA, 0f, bufferMax),
                    ChartSeries(netMbsHistory.toList(), VoidChart.COLOR_SECONDARY_LINE, SeriesStyle.LINE, 0f, netMax),
                )
        }

        charts["g4"]?.let { chart ->
            val bound = max(40f, avDriftHistory.maxOfOrNull { kotlin.math.abs(it) } ?: 40f)
            chart.yMin = -bound
            chart.yMax = bound
            chart.showZeroLine = true
            chart.series = listOf(ChartSeries(avDriftHistory.toList(), VoidChart.COLOR_PRESENTATION, SeriesStyle.LINE))
            chart.markers =
                listOf(
                    ChartMarker(40f, "+40ms", VoidTheme.colorTextSecondary),
                    ChartMarker(-40f, "-40ms", VoidTheme.colorTextSecondary),
                )
        }

        charts["g5"]?.let { chart ->
            val frameIntervalMs = if (stats.refreshRate > 0f) 1000f / stats.refreshRate else 11.1f
            val budgetMs = frameIntervalMs * 0.85f
            chart.yMin = 0f
            chart.yMax = max(budgetMs * 1.5f, gpuTimeHistory.maxOrNull() ?: budgetMs)
            chart.series = listOf(ChartSeries(gpuTimeHistory.toList(), VoidChart.COLOR_NETWORK, SeriesStyle.LINE))
            chart.markers = listOf(ChartMarker(budgetMs, "budget", VoidTheme.colorAccent))
        }
    }
}
