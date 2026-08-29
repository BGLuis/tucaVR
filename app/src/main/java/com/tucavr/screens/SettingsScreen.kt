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
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidFilterChip
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme

/**
 * Tela de Configurações — Seções "Vídeo" (Upscaling MQSR/SGSR1 e Foveated Rendering),
 * "Áudio" (Áudio Espacial 3-estados, Head Tracking e Screen-locked) e "Legendas".
 *
 * Ao mudar um toggle ou modo: persiste via SharedPreferences E empurra pro
 * nativo na hora (`activity.nativeSetXxx(...)`) para aplicação imediata em runtime.
 *
 * T4.5: O modo de áudio espacial é exposto como seletor de 3 chips (Off / Binaural / Downmix)
 * em vez do toggle booleano anterior, desbloqueia o modo SimpleDownmix (modo 2).
 *
 * T4.4: O toggle de screen-locked é exibido condicionalmente apenas quando head tracking está ativo.
 */
class SettingsScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val onBack: () -> Unit
) {
    // Referências guardadas para atualizar visibilidade condicional entre callbacks
    private var screenLockedRow: android.view.View? = null
    private var headTrackingRow: android.view.View? = null

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

        content.addView(
            buildFlagRow(
                labelRes = R.string.settings_pause_on_exit_label,
                descriptionRes = R.string.settings_pause_on_exit_description,
                flag = FeatureFlags.Flag.PAUSE_ON_EXIT,
                onChanged = { enabled -> activity.nativeSetPauseOnExit(enabled) }
            )
        )

        // Seção Áudio
        content.addView(
            VoidText.title(context, context.getString(R.string.settings_section_audio), sizeSp = 18f).apply {
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 8f))
            }
        )

        content.addView(buildSpatialAudioModeRow())

        content.addView(
            buildFlagRow(
                labelRes = R.string.settings_spatial_head_tracking_label,
                descriptionRes = R.string.settings_spatial_head_tracking_description,
                flag = FeatureFlags.Flag.SPATIAL_HEAD_TRACKING,
                onChanged = { enabled ->
                    activity.nativeSetSpatialAudioHeadTracking(enabled)
                    // Atualiza visibilidade do row de screen-locked
                    screenLockedRow?.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
                }
            ).also { row -> headTrackingRow = row }
        )

        // Screen-locked: visível apenas quando head tracking estiver ativo
        val headTrackingEnabled = FeatureFlags.isEnabled(context, FeatureFlags.Flag.SPATIAL_HEAD_TRACKING)
        buildFlagRow(
            labelRes = R.string.settings_spatial_screen_locked_label,
            descriptionRes = R.string.settings_spatial_screen_locked_description,
            flag = FeatureFlags.Flag.SPATIAL_SCREEN_LOCKED,
            onChanged = { locked -> activity.nativeSetAudioScreenLocked(locked) }
        ).also { row ->
            screenLockedRow = row
            row.visibility = if (headTrackingEnabled) android.view.View.VISIBLE else android.view.View.GONE
            content.addView(row)
        }

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
                actionButtonTextRes = R.string.settings_debug_stats_open_button,
                onActionClicked = { activity.openDebugStatsModal() },
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
        actionButtonTextRes: Int? = null,
        onActionClicked: (() -> Unit)? = null,
        onChanged: (Boolean) -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

        val isFlagEnabled = FeatureFlags.isEnabled(context, flag)
        var actionBtn: VoidButton? = null

        addView(CheckBox(context).apply {
            text = context.getString(labelRes)
            textSize = 18f
            setTextColor(VoidTheme.colorText)
            isChecked = isFlagEnabled
            setOnCheckedChangeListener { _, checked ->
                FeatureFlags.setEnabled(context, flag, checked)
                actionBtn?.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
                onChanged(checked)
            }
        })

        addView(
            VoidText.body(context, context.getString(descriptionRes), sizeSp = 14f, secondary = true).apply {
                setPadding(VoidTheme.dpToPx(context, 4f), VoidTheme.dpToPx(context, 4f), 0, 0)
            }
        )

        if (actionButtonTextRes != null && onActionClicked != null) {
            actionBtn = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = context.getString(actionButtonTextRes)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    VoidTheme.dpToPx(context, 60f)
                ).apply {
                    topMargin = VoidTheme.dpToPx(context, 8f)
                    leftMargin = VoidTheme.dpToPx(context, 4f)
                }
                visibility = if (isFlagEnabled) android.view.View.VISIBLE else android.view.View.GONE
                setOnClickListener { onActionClicked() }
            }
            addView(actionBtn)
        }
    }

    /**
     * Constrói o seletor de modo de áudio espacial com 3 chips: Off / Binaural / Downmix.
     *
     * - **Off** (modo 0 = DirectStereo): pass-through estéreo sem processamento.
     * - **Binaural** (modo 1 = VirtualizedBinaural): HRTF 3D completo com posicionamento espacial.
     * - **Downmix** (modo 2 = SimpleDownmix): downmix matricial ITU-R de baixo custo — degrau
     *   térmico manual (ver T4.5 do relatório PHASE-0.3-04-AUDIO-MULTICANAL.md).
     */
    private fun buildSpatialAudioModeRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

        addView(
            VoidText.title(context, context.getString(R.string.settings_spatial_audio_label), sizeSp = 16f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
            }
        )

        addView(
            VoidText.body(context, context.getString(R.string.settings_spatial_audio_description), sizeSp = 14f, secondary = true).apply {
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
            0 to R.string.settings_spatial_audio_mode_off,
            1 to R.string.settings_spatial_audio_mode_binaural,
            2 to R.string.settings_spatial_audio_mode_downmix,
        )

        val chips = mutableListOf<VoidFilterChip>()
        val currentMode = FeatureFlags.getSpatialAudioMode(context)

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
                    FeatureFlags.setSpatialAudioMode(context, mode)
                    activity.nativeSetSpatialAudioMode(mode)
                }
            }
            chips.add(chip)
            chipContainer.addView(chip)
        }

        addView(chipContainer)
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
