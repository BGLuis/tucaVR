package com.vrplayer.screens

import android.content.Context
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import com.vrplayer.FeatureFlags
import com.vrplayer.R
import com.vrplayer.VRActivity
import com.vrplayer.designsystem.VoidPanelChrome
import com.vrplayer.designsystem.VoidText
import com.vrplayer.designsystem.VoidTheme

/**
 * Tela de Configurações — hoje só a seção "Vídeo" com o toggle de Foveated
 * Rendering (fase 0.4 T5). Ponto de extensão para os próximos toggles
 * planejados (áudio multicanal, áudio espacial): cada um vira uma chamada a
 * mais de [buildFlagRow] dentro de [render].
 *
 * Ao mudar um toggle: persiste via [FeatureFlags.setEnabled] E empurra pro
 * nativo na hora (`activity.nativeSetXxx(...)`) — é isso que faz o toggle
 * ser de verdade "em runtime", não só "no próximo boot".
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

        root.addView(
            VoidText.title(context, context.getString(R.string.settings_section_video), sizeSp = 18f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
            }
        )

        root.addView(
            buildFlagRow(
                labelRes = R.string.settings_foveated_rendering_label,
                descriptionRes = R.string.settings_foveated_rendering_description,
                flag = FeatureFlags.Flag.FOVEATED_RENDERING,
                onChanged = { enabled -> activity.nativeSetFoveationEnabled(enabled) }
            )
        )

        // Seção Áudio
        root.addView(
            VoidText.title(context, context.getString(R.string.settings_section_audio), sizeSp = 18f).apply {
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 8f))
            }
        )

        root.addView(
            buildFlagRow(
                labelRes = R.string.settings_spatial_audio_label,
                descriptionRes = R.string.settings_spatial_audio_description,
                flag = FeatureFlags.Flag.SPATIAL_AUDIO,
                onChanged = { enabled -> activity.nativeSetSpatialAudioMode(if (enabled) 1 else 0) }
            )
        )

        root.addView(
            buildFlagRow(
                labelRes = R.string.settings_spatial_head_tracking_label,
                descriptionRes = R.string.settings_spatial_head_tracking_description,
                flag = FeatureFlags.Flag.SPATIAL_HEAD_TRACKING,
                onChanged = { enabled -> activity.nativeSetSpatialAudioHeadTracking(enabled) }
            )
        )

        // Seção Legendas (T9.6)
        root.addView(
            VoidText.title(context, context.getString(R.string.settings_section_subtitles), sizeSp = 18f).apply {
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, VoidTheme.dpToPx(context, 8f))
            }
        )

        root.addView(
            buildFlagRow(
                labelRes = R.string.settings_subtitles_auto_load_label,
                descriptionRes = R.string.settings_subtitles_auto_load_description,
                flag = FeatureFlags.Flag.AUTO_LOAD_SUBTITLES,
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
}
