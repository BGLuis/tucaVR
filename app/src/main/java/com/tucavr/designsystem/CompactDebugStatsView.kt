package com.tucavr.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tucavr.R
import com.tucavr.codec.CodecCapabilityManager
import com.tucavr.codec.CodecSupportStatus
import com.tucavr.debug.DebugStatsParser
import com.tucavr.filebrowser.MediaMetadata
import com.tucavr.navigation.PlaybackSource
import java.util.Locale
import kotlin.math.max

/**
 * Visão compacta de telemetria ("Stats for Nerds" compact view) acoplada a ~38° de azimute
 * ao lado da tela de vídeo sem sobrepor a projeção 16:9 padrão (Issue #99).
 *
 * Exibe as 7 métricas fundamentais a ~10 Hz e uma mini curva de tempo de GPU de 60 segundos.
 */
class CompactDebugStatsView(
    context: Context,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    private val statValueViews = mutableMapOf<String, TextView>()
    private val gpuChart: VoidChart
    private val gpuTimeHistory = ArrayDeque<Float>()

    companion object {
        private const val HISTORY_MAX_SAMPLES = 600
    }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        setOnClickListener { onDismiss() }

        val cardWidth = VoidTheme.dpToPx(context, 300f)
        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER
                    }
                background =
                    GradientDrawable().apply {
                        setColor(Color.argb(220, 20, 20, 22))
                        cornerRadius = VoidTheme.dp(context, 12f)
                        setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
                    }
                setPadding(
                    VoidTheme.dpToPx(context, 16f),
                    VoidTheme.dpToPx(context, 12f),
                    VoidTheme.dpToPx(context, 16f),
                    VoidTheme.dpToPx(context, 14f),
                )
                isClickable = true
                setOnClickListener { /* Consumir clique dentro do card */ }
            }

        // Header: Título e Botão Fechar
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = VoidTheme.dpToPx(context, 10f) }
            }

        val title =
            VoidText.title(context, context.getString(R.string.stats_compact_title), sizeSp = 15f).apply {
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
                val btnSize = VoidTheme.dpToPx(context, 28f)
                minimumWidth = btnSize
                minimumHeight = btnSize
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                contentDescription = context.getString(R.string.common_close)
                setOnClickListener { onDismiss() }
            }

        header.addView(title)
        header.addView(closeBtn)
        card.addView(header)

        // 7 métricas principais
        val metrics =
            listOf(
                "fps" to R.string.stats_metric_fps,
                "dropped" to R.string.stats_metric_dropped,
                "gpu_time" to R.string.stats_metric_gpu_time,
                "cpu_time" to R.string.stats_metric_cpu_time,
                "resolution" to R.string.stats_metric_resolution,
                "decoder" to R.string.stats_metric_decoder,
                "buffer" to R.string.stats_metric_buffer,
            )

        metrics.forEach { (key, labelRes) ->
            val row =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = VoidTheme.dpToPx(context, 2f)
                            bottomMargin = VoidTheme.dpToPx(context, 2f)
                        }
                }

            val label =
                TextView(context).apply {
                    text = context.getString(labelRes)
                    textSize = 12f
                    typeface = VoidTheme.typefaceBody
                    setTextColor(VoidTheme.colorTextSecondary)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                }
            val value =
                TextView(context).apply {
                    text = "—"
                    textSize = 12f
                    typeface = VoidTheme.typefaceMono
                    setTextColor(VoidTheme.colorText)
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
                }

            statValueViews[key] = value
            row.addView(label)
            row.addView(value)
            card.addView(row)
        }

        // Mini curva de GPU (60s)
        val chartLabel =
            TextView(context).apply {
                text = context.getString(R.string.stats_chart_gpu_title)
                textSize = 11f
                typeface = VoidTheme.typefaceBody
                setTextColor(VoidTheme.colorTextSecondary)
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 4f))
            }
        card.addView(chartLabel)

        gpuChart =
            VoidChart(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        VoidTheme.dpToPx(context, 56f),
                    )
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        cornerRadius = VoidTheme.dp(context, 6f)
                    }
            }
        card.addView(gpuChart)

        addView(card)
    }

    @Suppress("UNUSED_PARAMETER")
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

        // 1. FPS
        statValueViews["fps"]?.text =
            String.format(Locale.US, "%.1f (%.0f Hz)", stats.outputFps, stats.refreshRate)

        // 2. Dropped frames
        val totalFrames = stats.decodedFps + stats.droppedFps
        val dropPct = if (totalFrames > 0f) (stats.droppedFps / totalFrames) * 100f else 0f
        statValueViews["dropped"]?.text =
            String.format(Locale.US, "%.0f fps (%.1f%%)", stats.droppedFps, dropPct)

        // 3. GPU Time
        val gpuTime = if (stats.smoothedGpuTimeMs > 0f) stats.smoothedGpuTimeMs else stats.perfAppGpuFrametimeMs
        statValueViews["gpu_time"]?.text = String.format(Locale.US, "%.1f ms", gpuTime)

        // 4. CPU Time
        val cpuTime = if (stats.perfAppCpuFrametimeMs > 0f) stats.perfAppCpuFrametimeMs else stats.frameTimeMs
        statValueViews["cpu_time"]?.text = String.format(Locale.US, "%.1f ms", cpuTime)

        // 5. Resolution
        val resText =
            if (videoTrack != null && videoTrack.width > 0) {
                "${videoTrack.width}x${videoTrack.height}"
            } else if (meta != null && meta.container.isNotEmpty()) {
                meta.container.uppercase()
            } else {
                "—"
            }
        statValueViews["resolution"]?.text = resText

        // 6. Decoder
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
        statValueViews["decoder"]?.text = decoderText

        // 7. Buffer
        val bufferSec = stats.bufferedAheadSec
        statValueViews["buffer"]?.text =
            String.format(Locale.US, "%d pkts (%.1fs)", stats.queueDepth, bufferSec)

        // Mini GPU Chart
        gpuTimeHistory.addLast(gpuTime)
        while (gpuTimeHistory.size > HISTORY_MAX_SAMPLES) {
            gpuTimeHistory.removeFirst()
        }

        val frameIntervalMs = if (stats.refreshRate > 0f) 1000f / stats.refreshRate else 11.1f
        val budgetMs = frameIntervalMs * 0.85f
        gpuChart.yMin = 0f
        gpuChart.yMax = max(budgetMs * 1.5f, gpuTimeHistory.maxOrNull() ?: budgetMs)
        gpuChart.series = listOf(ChartSeries(gpuTimeHistory.toList(), VoidChart.COLOR_NETWORK, SeriesStyle.LINE))
        gpuChart.markers = listOf(ChartMarker(budgetMs, "budget", VoidTheme.colorAccent))
    }
}
