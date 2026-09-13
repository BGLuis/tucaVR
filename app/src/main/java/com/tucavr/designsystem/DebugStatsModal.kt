package com.tucavr.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
 * Modal flutuante de Estatísticas Técnicas ("Stats for Nerds") exibido no 3º Quad frontal independente.
 *
 * Fundo externo 100% transparente para flutuar de forma limpa sobre a cena VR.
 * O toque fora do card fecha o modal via [onDismiss].
 */
class DebugStatsModal(
    context: Context,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val debugStatValueViews = mutableMapOf<String, TextView>()
    private val charts = mutableMapOf<String, VoidChart>()

    // F5 (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md): histórico rolante em memória para
    // G1/G3/G4/G5 — acumulado a partir das amostras de ~10Hz que já chegam via updateStats,
    // sem alargar o wire (2.5 do relatório). G2 usa os contadores cumulativos direto do wire,
    // sem histórico próprio.
    private val bottleneckHistory = ArrayDeque<BottleneckStage>()
    private val bufferHealthHistory = ArrayDeque<Float>()
    private val netMbsHistory = ArrayDeque<Float>()
    private val avDriftHistory = ArrayDeque<Float>()
    private val gpuTimeHistory = ArrayDeque<Float>()

    companion object {
        // D-04 (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md): valor com idade acima de
        // ~2 amostras (HUD atualiza a ~10Hz, ver DebugTelemetryExporter) aparece esmaecido
        // com a idade ao lado, em vez de mentir sendo exibido como corrente — ver os 34
        // exemplos de valor congelado por 34 amostras seguidas na sessão real do relatório.
        private const val STALE_THRESHOLD_MS = 200

        // 60s a ~10Hz (relatório 2.5, G1: "faixas por estágio, 60s").
        private const val HISTORY_MAX_SAMPLES = 600
    }

    private fun <T> ArrayDeque<T>.pushCapped(value: T, maxSize: Int = HISTORY_MAX_SAMPLES) {
        addLast(value)
        while (size > maxSize) removeFirst()
    }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        setOnClickListener { onDismiss() }

        val panelWidth = VoidTheme.dpToPx(context, 760f)
        val panelHeight = VoidTheme.dpToPx(context, 580f)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(panelWidth, panelHeight).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurface)
                cornerRadius = VoidTheme.dp(context, 16f)
                setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
            }
            setPadding(
                VoidTheme.dpToPx(context, 24f),
                VoidTheme.dpToPx(context, 20f),
                VoidTheme.dpToPx(context, 24f),
                VoidTheme.dpToPx(context, 20f)
            )
            isClickable = true
            setOnClickListener { /* Consumir clique dentro do card */ }
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 12f)
            }
        }
        val modalTitle = TextView(context).apply {
            text = context.getString(R.string.debug_stats_modal_title)
            typeface = VoidTheme.typefaceBody
            textSize = 22f
            setTextColor(VoidTheme.colorText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        header.addView(modalTitle)

        val btnCloseStats = VoidIconButton(
            context,
            R.drawable.icon_x,
            VoidButtonStyle.SECONDARY,
            isCircular = true,
            isTransparent = true
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                VoidTheme.dpToPx(context, 48f),
                VoidTheme.dpToPx(context, 48f)
            )
            setOnClickListener { onDismiss() }
        }
        header.addView(btnCloseStats)
        panel.addView(header)

        // Scrollview das seções
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
            isVerticalScrollBarEnabled = false
        }
        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        buildStatSection(
            context.getString(R.string.debug_stats_section_video_render),
            listOf(
                // F5 (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md): bottleneck_stage como
                // primeira linha do painel (secao 2.3) — responde "onde travou" sem
                // reconstruir a leitura a mao.
                "bottleneck" to context.getString(R.string.debug_stats_label_bottleneck),
                "resolution" to context.getString(R.string.debug_stats_label_resolution),
                "decoder" to context.getString(R.string.debug_stats_label_video_decoder),
                "fps" to context.getString(R.string.debug_stats_label_fps),
                "dropped_frames" to context.getString(R.string.debug_stats_label_dropped_frames),
                "stutter_freeze" to context.getString(R.string.debug_stats_label_stutter_freeze),
                "jitter" to context.getString(R.string.debug_stats_label_video_jitter),
                "stereo" to context.getString(R.string.debug_stats_label_stereo_mode),
                "render_scale" to context.getString(R.string.debug_stats_label_render_scale),
                "backend" to context.getString(R.string.debug_stats_label_graphics_backend),
                // F5 G5: 7 campos ja coletados e nunca exibidos (draw_call_count/
                // triangle_count/gpu_time_ms/quality_level/quality_reason/upscaling_mode/
                // mqsr_enabled) — zero coleta nova, so exibicao.
                "gpu_time" to context.getString(R.string.debug_stats_label_gpu_time),
                "quality" to context.getString(R.string.debug_stats_label_quality),
                "upscaling" to context.getString(R.string.debug_stats_label_upscaling),
                "drawcalls" to context.getString(R.string.debug_stats_label_drawcalls)
            ),
            contentContainer
        )

        buildStatSection(
            context.getString(R.string.debug_stats_section_audio_sync),
            listOf(
                "audio_codec" to context.getString(R.string.debug_stats_label_audio_format),
                "av_drift" to context.getString(R.string.debug_stats_label_av_drift),
                "spatial_audio" to context.getString(R.string.debug_stats_label_spatial_audio),
                "audio_track" to context.getString(R.string.debug_stats_label_audio_track),
                "subtitles" to context.getString(R.string.debug_stats_label_subtitles)
            ),
            contentContainer
        )

        buildStatSection(
            context.getString(R.string.debug_stats_section_network_buffer),
            listOf(
                "source" to context.getString(R.string.debug_stats_label_source),
                "net_speed" to context.getString(R.string.debug_stats_label_network_speed),
                "buffer_queue" to context.getString(R.string.debug_stats_label_buffer_queue),
                "fetch_latency" to context.getString(R.string.debug_stats_label_fetch_latency),
                "blocks" to context.getString(R.string.debug_stats_label_blocks),
                "seek_latency" to context.getString(R.string.debug_stats_label_seek_latency)
            ),
            contentContainer
        )

        buildStatSection(
            context.getString(R.string.debug_stats_section_system),
            listOf(
                "thermal" to context.getString(R.string.debug_stats_label_thermal),
                "battery" to context.getString(R.string.debug_stats_label_battery),
                "app_version" to context.getString(R.string.debug_stats_label_app_version)
            ),
            contentContainer
        )

        buildChartsSection(contentContainer)

        scrollView.addView(contentContainer)
        panel.addView(scrollView)
        addView(panel)
    }

    private fun buildStatSection(titleText: String, rows: List<Pair<String, String>>, container: LinearLayout) {
        val sectionHeader = VoidText.title(context, titleText, sizeSp = 16f).apply {
            setPadding(0, VoidTheme.dpToPx(context, 10f), 0, VoidTheme.dpToPx(context, 6f))
        }
        container.addView(sectionHeader)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurfaceAlt)
                cornerRadius = VoidTheme.dp(context, 10f)
            }
            setPadding(
                VoidTheme.dpToPx(context, 16f),
                VoidTheme.dpToPx(context, 8f),
                VoidTheme.dpToPx(context, 16f),
                VoidTheme.dpToPx(context, 8f)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = VoidTheme.dpToPx(context, 8f) }
        }

        rows.forEach { (key, label) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = VoidTheme.dpToPx(context, 4f)
                    bottomMargin = VoidTheme.dpToPx(context, 4f)
                }
            }

            val labelView = TextView(context).apply {
                text = label
                textSize = 14f
                typeface = VoidTheme.typefaceBody
                setTextColor(VoidTheme.colorTextSecondary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            val valueView = TextView(context).apply {
                text = "—"
                textSize = 14f
                typeface = VoidTheme.typefaceMono
                setTextColor(VoidTheme.colorText)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f)
            }
            debugStatValueViews[key] = valueView

            row.addView(labelView)
            row.addView(valueView)
            card.addView(row)
        }

        container.addView(card)
    }

    /**
     * F5 (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md, seção 2.5): os 5 gráficos, cada um
     * com uma legenda curta acima. Alturas em dp (60dp) — ver aviso de densidade em
     * [VoidChart]; legibilidade real só confirmável no headset.
     */
    private fun buildChartsSection(container: LinearLayout) {
        val sectionHeader = VoidText.title(context, context.getString(R.string.debug_stats_section_charts), sizeSp = 16f).apply {
            setPadding(0, VoidTheme.dpToPx(context, 10f), 0, VoidTheme.dpToPx(context, 6f))
        }
        container.addView(sectionHeader)

        fun addChart(key: String, titleRes: Int): VoidChart {
            val caption = TextView(context).apply {
                text = context.getString(titleRes)
                textSize = 12f
                typeface = VoidTheme.typefaceBody
                setTextColor(VoidTheme.colorTextSecondary)
                setPadding(0, VoidTheme.dpToPx(context, 6f), 0, VoidTheme.dpToPx(context, 2f))
            }
            container.addView(caption)

            val chart = VoidChart(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    VoidTheme.dpToPx(context, 60f)
                )
                background = GradientDrawable().apply {
                    setColor(VoidTheme.colorSurfaceAlt)
                    cornerRadius = VoidTheme.dp(context, 6f)
                }
            }
            container.addView(chart)
            charts[key] = chart
            return chart
        }

        addChart("g1", R.string.debug_stats_chart_g1_title)
        addChart("g2", R.string.debug_stats_chart_g2_title)
        addChart("g3", R.string.debug_stats_chart_g3_title)
        addChart("g4", R.string.debug_stats_chart_g4_title)
        addChart("g5", R.string.debug_stats_chart_g5_title)
    }

    /**
     * Escreve o valor de uma linha, esmaecendo-o e anexando a idade quando o grupo
     * correspondente (D-04) está obsoleto — em vez de exibir um dado congelado como se
     * fosse uma leitura atual. `ageMs == null` = campo sem grupo de frescor (estado local,
     * não telemetria amostrada — ex.: faixa de áudio selecionada, versão do app).
     */
    private fun setValue(key: String, text: String, ageMs: Int? = null) {
        val view = debugStatValueViews[key] ?: return
        if (ageMs != null && ageMs > STALE_THRESHOLD_MS) {
            view.text = "$text (${ageMs}ms atrás)"
            view.setTextColor(VoidTheme.colorTextSecondary)
        } else {
            view.text = text
            view.setTextColor(VoidTheme.colorText)
        }
    }

    /**
     * Atualiza as métricas com o conteúdo do wire TSV recebido do native loop.
     */
    fun updateStats(
        text: String,
        meta: MediaMetadata?,
        source: PlaybackSource?,
        isCharging: Boolean,
        batteryPercent: Int,
        isDebuggable: Boolean
    ) {
        val stats = DebugStatsParser.parse(text) ?: return

        val videoTrack = meta?.videoTracks?.firstOrNull()
        val audioTrack = meta?.audioTracks?.firstOrNull()

        // 1. Vídeo & Renderização
        val bottleneckText = when (BottleneckStageAnalyzer.analyze(stats)) {
            BottleneckStage.NONE -> "—"
            BottleneckStage.NETWORK -> "NETWORK"
            BottleneckStage.PRESENTATION -> "PRESENTATION"
        }
        debugStatValueViews["bottleneck"]?.text = bottleneckText

        val resText = if (videoTrack != null && videoTrack.width > 0) {
            "${videoTrack.width}x${videoTrack.height} (${videoTrack.codec.uppercase()})"
        } else if (meta != null && meta.container.isNotEmpty()) {
            meta.container.uppercase()
        } else {
            "—"
        }
        debugStatValueViews["resolution"]?.text = resText

        val videoCodec = videoTrack?.codec
        val decoderText = if (!videoCodec.isNullOrBlank()) {
            when (val status = CodecCapabilityManager.getStatus(videoCodec)) {
                is CodecSupportStatus.Supported -> "${status.decoder.codecName} [HW]"
                is CodecSupportStatus.SoftwareOnly -> "${status.decoder.codecName} [SW]"
                is CodecSupportStatus.Unsupported -> context.getString(R.string.codec_badge_unsupported)
            }
        } else {
            "—"
        }
        debugStatValueViews["decoder"]?.text = decoderText

        setValue(
            "fps",
            String.format(Locale.US, "%.1f dec / %.1f out (%.0f Hz)", stats.decodedFps, stats.outputFps, stats.refreshRate),
            stats.videoStatsAgeMs
        )

        val totalFrames = stats.decodedFps + stats.droppedFps
        val dropPct = if (totalFrames > 0f) (stats.droppedFps / totalFrames) * 100f else 0f
        setValue(
            "dropped_frames",
            String.format(Locale.US, "%.0f fps (%.1f%%)", stats.droppedFps, dropPct),
            stats.videoStatsAgeMs
        )

        debugStatValueViews["stutter_freeze"]?.text = "${stats.stutterCount} / ${stats.freezeCount}"

        debugStatValueViews["jitter"]?.text = String.format(
            Locale.US, "%.1f ms (gap %.1f ms)", stats.jitterMs, stats.frameGapMs
        )

        val swapStr = if (stats.swapEyes != 0) " [Swap]" else ""
        debugStatValueViews["stereo"]?.text = "${stats.screenMode}$swapStr"

        val fovStr = if (stats.foveationEnabled) "Foveation: On" else "Foveation: Off"
        debugStatValueViews["render_scale"]?.text = String.format(
            Locale.US, "%.2fx | %s", stats.renderScale, fovStr
        )

        debugStatValueViews["backend"]?.text = stats.backend

        // F5 G5: campos ja coletados e nunca exibidos (relatorio 2.5) — zero coleta nova.
        setValue("gpu_time", String.format(Locale.US, "%.2f / %.2f ms", stats.gpuTimeMs, stats.smoothedGpuTimeMs), stats.renderStatsAgeMs)
        debugStatValueViews["quality"]?.text = "${stats.qualityLevel} (${stats.qualityReason})"
        val mqsrStr = if (stats.mqsrEnabled) " [MQSR]" else ""
        debugStatValueViews["upscaling"]?.text = String.format(Locale.US, "%s %.2f%s", stats.upscalingMode, stats.upscalingSharpness, mqsrStr)
        debugStatValueViews["drawcalls"]?.text = "${stats.drawCallCount} / ${stats.triangleCount}"

        // 2. Áudio & Sincronização
        val audioCodecStr = if (audioTrack != null && audioTrack.codec.isNotEmpty()) {
            "${audioTrack.codec.uppercase()} (${audioTrack.channels}ch, ${audioTrack.sampleRate / 1000}kHz)"
        } else {
            "—"
        }
        debugStatValueViews["audio_codec"]?.text = audioCodecStr

        val driftSign = if (stats.avDriftMs >= 0) "+" else ""
        setValue("av_drift", String.format(Locale.US, "%s%.1f ms", driftSign, stats.avDriftMs), stats.videoStatsAgeMs)

        val spatialName = when (stats.spatialAudioMode) {
            1 -> "Binaural (5.1/7.1)"
            2 -> "Ambisonics"
            else -> "Off (Stereo)"
        }
        val headStr = if (stats.spatialHeadTracking) " [HeadTrack]" else ""
        debugStatValueViews["spatial_audio"]?.text = "$spatialName$headStr"

        debugStatValueViews["audio_track"]?.text = "${stats.audioTrackIndex + 1} / ${maxOf(1, stats.audioTrackCount)}"

        val subText = if (stats.subtitleTrackIndex < 0) {
            context.getString(R.string.subtitles_option_off)
        } else {
            "Track ${stats.subtitleTrackIndex + 1} (${String.format(Locale.US, "%+.1fs", stats.subtitleOffsetMs / 1000f)})"
        }
        debugStatValueViews["subtitles"]?.text = subText

        // 3. Rede & Buffer
        val srcText = when (source) {
            is PlaybackSource.LocalFile -> "Local Storage"
            is PlaybackSource.Http -> "HTTP(S)"
            is PlaybackSource.Smb -> "SMB (${source.server.host})"
            is PlaybackSource.Ftp -> "FTP (${source.server.host})"
            is PlaybackSource.Sftp -> "SFTP (${source.server.host})"
            is PlaybackSource.Nfs -> "NFS (${source.server.host})"
            is PlaybackSource.Dlna -> "DLNA (${source.server.name})"
            is PlaybackSource.Webdav -> "WebDAV (${source.server.host})"
            null -> "None"
        }
        debugStatValueViews["source"]?.text = srcText

        setValue("net_speed", String.format(Locale.US, "%.2f MB/s", stats.netMBs), stats.networkStatsAgeMs)
        setValue("buffer_queue", "${stats.queueDepth} packets", stats.networkStatsAgeMs)
        setValue("fetch_latency", String.format(Locale.US, "%.1f ms", stats.netLastFetchMs), stats.networkStatsAgeMs)
        setValue("blocks", "${stats.netBlocksFetched} / ${stats.netBlocksDiscarded}", stats.networkStatsAgeMs)
        debugStatValueViews["seek_latency"]?.text = "${stats.seekLatencyMs} ms"

        // 4. Sistema & Hardware
        val thermalName = when (stats.thermalLevel) {
            0 -> context.getString(R.string.thermal_level_normal)
            1 -> "Light"
            2 -> context.getString(R.string.thermal_level_moderate)
            3 -> context.getString(R.string.thermal_level_severe)
            4 -> context.getString(R.string.thermal_level_critical)
            else -> "${stats.thermalLevel}"
        }
        debugStatValueViews["thermal"]?.text = "$thermalName (${stats.thermalLevel})"

        val chargingStr = if (isCharging) " [Charging]" else ""
        debugStatValueViews["battery"]?.text = "$batteryPercent%$chargingStr"
        debugStatValueViews["app_version"]?.text = "${BuildConfig.VERSION_NAME} (${if (isDebuggable) "Debug" else "Release"})"

        updateCharts(stats)
    }

    /**
     * F5: acumula a amostra corrente nos históricos rolantes e redesenha os 5 gráficos.
     */
    private fun updateCharts(stats: NativeDebugStats) {
        val bufferHealthSeconds = if (stats.videoFps > 0f) stats.queueDepth / stats.videoFps else 0f

        bottleneckHistory.pushCapped(BottleneckStageAnalyzer.analyze(stats))
        bufferHealthHistory.pushCapped(bufferHealthSeconds)
        netMbsHistory.pushCapped(stats.netMBs)
        avDriftHistory.pushCapped(stats.avDriftMs)
        gpuTimeHistory.pushCapped(stats.smoothedGpuTimeMs)

        charts["g1"]?.timeline = bottleneckHistory.map { TimelineSample(it) }

        charts["g2"]?.let { chart ->
            chart.yMax = (stats.histBuckets.maxOrNull()?.takeIf { it > 0 } ?: 1).toFloat()
            chart.series = listOf(
                ChartSeries(stats.histBuckets.map { it.toFloat() }, VoidChart.COLOR_NETWORK, SeriesStyle.BARS)
            )
        }

        charts["g3"]?.let { chart ->
            chart.yMin = 0f
            chart.yMax = 1f // ignorado: as duas séries usam min/maxValueOverride próprios
            val bufferMax = (bufferHealthHistory.maxOrNull()?.takeIf { it > 0f } ?: 1f)
            val netMax = (netMbsHistory.maxOrNull()?.takeIf { it > 0f } ?: 1f)
            chart.series = listOf(
                ChartSeries(bufferHealthHistory.toList(), VoidChart.COLOR_HEALTHY, SeriesStyle.AREA, 0f, bufferMax),
                ChartSeries(netMbsHistory.toList(), VoidChart.COLOR_SECONDARY_LINE, SeriesStyle.LINE, 0f, netMax)
            )
        }

        charts["g4"]?.let { chart ->
            val bound = max(40f, avDriftHistory.maxOfOrNull { kotlin.math.abs(it) } ?: 40f)
            chart.yMin = -bound
            chart.yMax = bound
            chart.showZeroLine = true
            chart.series = listOf(ChartSeries(avDriftHistory.toList(), VoidChart.COLOR_PRESENTATION, SeriesStyle.LINE))
            chart.markers = listOf(
                ChartMarker(40f, "+40ms", VoidTheme.colorTextSecondary),
                ChartMarker(-40f, "-40ms", VoidTheme.colorTextSecondary)
            )
        }

        charts["g5"]?.let { chart ->
            // Mesma formula de gpu_budget_ms do QualityController (rust/media-logic/src/quality.rs)
            // — frame_interval_ms * 0.85 — pra mostrar a mesma linha de orcamento que rege a malha.
            val frameIntervalMs = if (stats.refreshRate > 0f) 1000f / stats.refreshRate else 11.1f
            val budgetMs = frameIntervalMs * 0.85f
            chart.yMin = 0f
            chart.yMax = max(budgetMs * 1.5f, gpuTimeHistory.maxOrNull() ?: budgetMs)
            chart.series = listOf(ChartSeries(gpuTimeHistory.toList(), VoidChart.COLOR_NETWORK, SeriesStyle.LINE))
            chart.markers = listOf(ChartMarker(budgetMs, "budget", VoidTheme.colorAccent))
        }
    }
}
