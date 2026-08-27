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
import com.tucavr.debug.DebugStatsParser
import com.tucavr.filebrowser.MediaMetadata
import com.tucavr.navigation.PlaybackSource
import java.util.Locale

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
                "resolution" to context.getString(R.string.debug_stats_label_resolution),
                "fps" to context.getString(R.string.debug_stats_label_fps),
                "dropped_frames" to context.getString(R.string.debug_stats_label_dropped_frames),
                "stutter_freeze" to context.getString(R.string.debug_stats_label_stutter_freeze),
                "jitter" to context.getString(R.string.debug_stats_label_video_jitter),
                "stereo" to context.getString(R.string.debug_stats_label_stereo_mode),
                "render_scale" to context.getString(R.string.debug_stats_label_render_scale),
                "backend" to context.getString(R.string.debug_stats_label_graphics_backend)
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
        val resText = if (videoTrack != null && videoTrack.width > 0) {
            "${videoTrack.width}x${videoTrack.height} (${videoTrack.codec.uppercase()})"
        } else if (meta != null && meta.container.isNotEmpty()) {
            meta.container.uppercase()
        } else {
            "—"
        }
        debugStatValueViews["resolution"]?.text = resText

        debugStatValueViews["fps"]?.text = String.format(
            Locale.US, "%.1f dec / %.1f out (%.0f Hz)",
            stats.decodedFps, stats.outputFps, stats.refreshRate
        )

        val totalFrames = stats.decodedFps + stats.droppedFps
        val dropPct = if (totalFrames > 0f) (stats.droppedFps / totalFrames) * 100f else 0f
        debugStatValueViews["dropped_frames"]?.text = String.format(
            Locale.US, "%.0f fps (%.1f%%)", stats.droppedFps, dropPct
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

        // 2. Áudio & Sincronização
        val audioCodecStr = if (audioTrack != null && audioTrack.codec.isNotEmpty()) {
            "${audioTrack.codec.uppercase()} (${audioTrack.channels}ch, ${audioTrack.sampleRate / 1000}kHz)"
        } else {
            "—"
        }
        debugStatValueViews["audio_codec"]?.text = audioCodecStr

        val driftSign = if (stats.avDriftMs >= 0) "+" else ""
        debugStatValueViews["av_drift"]?.text = String.format(Locale.US, "%s%.1f ms", driftSign, stats.avDriftMs)

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
            null -> "None"
        }
        debugStatValueViews["source"]?.text = srcText

        debugStatValueViews["net_speed"]?.text = String.format(Locale.US, "%.2f MB/s", stats.netMBs)
        debugStatValueViews["buffer_queue"]?.text = "${stats.queueDepth} packets"
        debugStatValueViews["fetch_latency"]?.text = String.format(Locale.US, "%.1f ms", stats.netLastFetchMs)
        debugStatValueViews["blocks"]?.text = "${stats.netBlocksFetched} / ${stats.netBlocksDiscarded}"
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
    }
}
