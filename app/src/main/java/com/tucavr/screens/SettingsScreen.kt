package com.tucavr.screens

import android.content.Context
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import com.tucavr.FeatureFlags
import com.tucavr.R
import com.tucavr.UpscalingModeStore
import com.tucavr.VRActivity
import com.tucavr.designsystem.VoidFilterChip
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme

/**
 * Tela de Configurações — Seções "Vídeo" (Upscaling MQSR/SGSR1 e Foveated Rendering),
 * "Áudio" (Áudio Espacial e Head Tracking) e "Legendas".
 *
 * Ao mudar um toggle ou modo: persiste via SharedPreferences E empurra pro
 * nativo na hora (`activity.nativeSetXxx(...)`) para aplicação imediata em runtime.
 */
class SettingsScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val onBack: () -> Unit
) {

    fun render() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(VoidPanelChrome.buildHeader(context, title = context.getString(R.string.settings_title), onBack = { onBack() }))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroller = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroller)

        content.addView(
            VoidText.title(context, context.getString(R.string.settings_section_video), sizeSp = 18f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
            }
        )

        content.addView(buildUpscalingRow())

        content.addView(
            buildFlagRow(
                labelRes = R.string.settings_foveated_rendering_label,
                descriptionRes = R.string.settings_foveated_rendering_description,
                flag = FeatureFlags.Flag.FOVEATED_RENDERING,
                onChanged = { enabled -> activity.nativeSetFoveationEnabled(enabled) }
            )
        )

        // Seção Áudio
        content.addView(
            VoidText.title(context, context.getString(R.string.settings_section_audio), sizeSp = 18f).apply {
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 8f))
            }
        )

        content.addView(
            buildFlagRow(
                labelRes = R.string.settings_spatial_audio_label,
                descriptionRes = R.string.settings_spatial_audio_description,
                flag = FeatureFlags.Flag.SPATIAL_AUDIO,
                onChanged = { enabled -> activity.nativeSetSpatialAudioMode(if (enabled) 1 else 0) }
            )
        )

        content.addView(
            buildFlagRow(
                labelRes = R.string.settings_spatial_head_tracking_label,
                descriptionRes = R.string.settings_spatial_head_tracking_description,
                flag = FeatureFlags.Flag.SPATIAL_HEAD_TRACKING,
                onChanged = { enabled -> activity.nativeSetSpatialAudioHeadTracking(enabled) }
            )
        )

        // Seção Legendas (T9.6)
        content.addView(
            VoidText.title(context, context.getString(R.string.settings_section_subtitles), sizeSp = 18f).apply {
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 8f))
            }
        )

        content.addView(
            buildFlagRow(
                labelRes = R.string.settings_subtitles_auto_load_label,
                descriptionRes = R.string.settings_subtitles_auto_load_description,
                flag = FeatureFlags.Flag.AUTO_LOAD_SUBTITLES,
                onChanged = { /* flag persisted automatically */ }
            )
        )

        // Seção Avançado (Diagnóstico e Telemetria)
        content.addView(
            VoidText.title(context, context.getString(R.string.settings_section_advanced), sizeSp = 18f).apply {
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 8f))
            }
        )

        content.addView(
            buildFlagRow(
                labelRes = R.string.settings_debug_stats_label,
                descriptionRes = R.string.settings_debug_stats_description,
                flag = FeatureFlags.Flag.DEBUG_STATS_PANEL,
                onChanged = { enabled -> activity.setDebugStatsEnabled(enabled) }
            )
        )

        content.addView(
            buildFlagRow(
                labelRes = R.string.settings_telemetry_export_label,
                descriptionRes = R.string.settings_telemetry_export_description,
                flag = FeatureFlags.Flag.DEBUG_STATS_EXPORT,
                onChanged = { /* flag persisted automatically */ }
            )
        )

        host.showScreen(root)
    }

    private fun buildFlagRow(
        labelRes: Int,
        descriptionRes: Int,
        flag: FeatureFlags.Flag,
        onChanged: (Boolean) -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

        addView(CheckBox(context).apply {
            text = context.getString(labelRes)
            textSize = 18f
            setTextColor(VoidTheme.colorText)
            isChecked = FeatureFlags.isEnabled(context, flag)
            setOnCheckedChangeListener { _, checked ->
                FeatureFlags.setEnabled(context, flag, checked)
                onChanged(checked)
            }
        })

        addView(
            VoidText.body(context, context.getString(descriptionRes), sizeSp = 14f, secondary = true).apply {
                setPadding(VoidTheme.dpToPx(context, 4f), VoidTheme.dpToPx(context, 4f), 0, 0)
            }
        )
    }

    private fun buildUpscalingRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

        addView(
            VoidText.title(context, context.getString(R.string.settings_upscaling_label), sizeSp = 16f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
            }
        )

        addView(
            VoidText.body(context, context.getString(R.string.settings_upscaling_description), sizeSp = 14f, secondary = true).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
            }
        )

        val chipContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val modes = listOf(
            UpscalingModeStore.Mode.OFF to R.string.settings_upscaling_mode_off,
            UpscalingModeStore.Mode.QUALITY to R.string.settings_upscaling_mode_quality,
            UpscalingModeStore.Mode.PERFORMANCE to R.string.settings_upscaling_mode_performance,
            UpscalingModeStore.Mode.AUTO to R.string.settings_upscaling_mode_auto
        )

        val chips = mutableListOf<VoidFilterChip>()
        val currentMode = activity.upscalingStore.get()

        modes.forEach { (mode, labelRes) ->
            val chip = VoidFilterChip(
                context = context,
                text = context.getString(labelRes),
                isSelectedChip = (mode == currentMode)
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    val margin = VoidTheme.dpToPx(context, 4f)
                    setMargins(margin, 0, margin, 0)
                }
                setOnClickListener {
                    chips.forEach { it.setSelectedState(false) }
                    setSelectedState(true)
                    activity.upscalingStore.set(mode)
                    activity.nativeSetUpscalingMode(mode.id)
                }
            }
            chips.add(chip)
            chipContainer.addView(chip)
        }

        addView(chipContainer)
    }
}
