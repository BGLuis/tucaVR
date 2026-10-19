package com.tucavr.screens

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.tucavr.FeatureFlags
import com.tucavr.R
import com.tucavr.UpscalingModeStore
import com.tucavr.VRActivity
import com.tucavr.debug.DebugStatsParser
import com.tucavr.designsystem.SegmentOption
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidPanelChrome
import com.tucavr.designsystem.VoidSegmentedSelector
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme
import com.tucavr.history.PlaybackResumePolicy
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tela de Configurações redesenhada em 3 colunas com Live Preview Rail e geometria dinâmica (Issue #100).
 *
 * - **Coluna 1 (Categorias, ~260 px):** Vídeo, Iluminação/Ambiente, Áudio, Legendas, Reprodução, Interface/Controles, Avançado.
 * - **Coluna 2 (Opções da Categoria, ~940 px):** Opções interativas com switches e [VoidSegmentedSelector].
 * - **Coluna 3 (Live Preview & Telemetria, ~432 px via [ScreenHost.setContextContent]):** Explicação da categoria/opção e telemetria nativa ao vivo.
 */
class SettingsScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val onBack: () -> Unit,
) {
    enum class Category(val titleRes: Int, val descRes: Int) {
        VIDEO(R.string.settings_section_video, R.string.settings_upscaling_description),
        LIGHTING(R.string.settings_section_lighting, R.string.settings_environment_brightness_description),
        AUDIO(R.string.settings_section_audio, R.string.settings_spatial_audio_description),
        SUBTITLES(R.string.settings_section_subtitles, R.string.settings_subtitles_auto_load_description),
        PLAYBACK(R.string.settings_section_playback, R.string.settings_resume_policy_desc),
        CONTROLS(R.string.settings_section_controls, R.string.settings_panel_fov_desc),
        ADVANCED(R.string.settings_section_advanced, R.string.settings_debug_stats_description),
    }

    private var selectedCategory = Category.VIDEO
    private lateinit var categoryViews: Map<Category, LinearLayout>
    private lateinit var optionsContainer: FrameLayout

    // Live preview rail views
    private var previewDescriptionView: TextView? = null
    private var telemetryGpuView: TextView? = null
    private var telemetryThermalView: TextView? = null
    private var telemetryRefreshView: TextView? = null

    // Referências guardadas para áudio espacial
    private var screenLockedRow: View? = null
    private var headTrackingRow: View? = null

    private val telemetryListener: (String) -> Unit = { rawStats ->
        val parsed = DebugStatsParser.parse(rawStats)
        if (parsed != null) {
            val gpuTime = if (parsed.smoothedGpuTimeMs > 0f) parsed.smoothedGpuTimeMs else parsed.perfAppGpuFrametimeMs
            telemetryGpuView?.text =
                context.getString(
                    R.string.settings_telemetry_gpu,
                    String.format(Locale.US, "%.1f ms", gpuTime),
                )

            val thermalName =
                when (parsed.thermalLevel) {
                    0 -> context.getString(R.string.thermal_level_normal)
                    1 -> context.getString(R.string.thermal_level_light)
                    2 -> context.getString(R.string.thermal_level_moderate)
                    3 -> context.getString(R.string.thermal_level_severe)
                    4 -> context.getString(R.string.thermal_level_critical)
                    else -> "${parsed.thermalLevel}"
                }
            telemetryThermalView?.text = context.getString(R.string.settings_telemetry_temp, thermalName)

            telemetryRefreshView?.text =
                context.getString(
                    R.string.settings_telemetry_refresh,
                    String.format(Locale.US, "%.0f Hz", parsed.refreshRate),
                )
        }
    }

    fun render() {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = context.getString(R.string.settings_title),
                onBack = { handleExit() },
            ),
        )

        val columnsLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    )
            }

        // Coluna 1: Categorias (~260 px)
        val colCategories = buildCategoriesColumn()
        columnsLayout.addView(colCategories)

        // Divisor vertical
        val divider =
            View(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        VoidTheme.dpToPx(context, 1f),
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ).apply {
                        marginEnd = VoidTheme.dpToPx(context, 16f)
                    }
                setBackgroundColor(VoidTheme.colorBorder)
            }
        columnsLayout.addView(divider)

        // Coluna 2: Opções da categoria selecionada (preenche restante do canvas central)
        optionsContainer =
            FrameLayout(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    )
            }
        columnsLayout.addView(optionsContainer)

        root.addView(columnsLayout)

        // Configurar Coluna 3: Live Preview Rail no host contextual
        setupLivePreviewRail()

        // Carregar opções da categoria inicial
        selectCategory(selectedCategory)

        host.showScreen(root)
    }

    fun cleanup() {
        activity.statsTelemetryListeners.remove(telemetryListener)
        host.resetContextContent()
    }

    private fun handleExit() {
        cleanup()
        onBack()
    }

    private fun buildCategoriesColumn(): LinearLayout {
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        VoidTheme.dpToPx(context, 260f),
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ).apply {
                        marginEnd = VoidTheme.dpToPx(context, 16f)
                    }
            }

        val map = mutableMapOf<Category, LinearLayout>()

        Category.values().forEach { cat ->
            val row =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            VoidTheme.dpToPx(context, 48f),
                        ).apply {
                            bottomMargin = VoidTheme.dpToPx(context, 6f)
                        }
                    setPadding(
                        VoidTheme.dpToPx(context, 16f),
                        0,
                        VoidTheme.dpToPx(context, 16f),
                        0,
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { selectCategory(cat) }
                }

            val title =
                TextView(context).apply {
                    text = context.getString(cat.titleRes)
                    textSize = 15f
                    typeface = VoidTheme.typefaceBody
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                }
            row.addView(title)
            map[cat] = row
            container.addView(row)
        }

        categoryViews = map
        return container
    }

    private fun selectCategory(cat: Category) {
        selectedCategory = cat

        // Atualiza destaque visual das abas
        categoryViews.forEach { (c, row) ->
            val isSelected = (c == cat)
            val titleView = row.getChildAt(0) as? TextView

            if (isSelected) {
                row.background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        cornerRadius = VoidTheme.dp(context, 8f)
                        setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorAccent)
                    }
                titleView?.setTextColor(VoidTheme.colorAccent)
            } else {
                row.background = null
                titleView?.setTextColor(VoidTheme.colorTextSecondary)
            }
        }

        // Atualiza a coluna 2 (Opções)
        optionsContainer.removeAllViews()
        val optionsView = buildOptionsForCategory(cat)
        val scroller =
            ScrollView(context).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                isFillViewport = true
                addView(optionsView)
            }
        optionsContainer.addView(scroller)

        // Atualiza coluna 3 (Preview & Contexto)
        previewDescriptionView?.text = context.getString(cat.descRes)
    }

    private fun setupLivePreviewRail() {
        val rail =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                setPadding(
                    VoidTheme.dpToPx(context, 16f),
                    VoidTheme.dpToPx(context, 20f),
                    VoidTheme.dpToPx(context, 16f),
                    VoidTheme.dpToPx(context, 20f),
                )
            }

        // Título Live Preview
        val previewTitle =
            VoidText.title(context, context.getString(R.string.settings_live_preview_title), sizeSp = 16f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
            }
        rail.addView(previewTitle)

        // Explicação contextual
        val descView =
            VoidText.body(
                context,
                context.getString(selectedCategory.descRes),
                sizeSp = 13f,
                secondary = true,
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = VoidTheme.dpToPx(context, 24f) }
            }
        previewDescriptionView = descView
        rail.addView(descView)

        // Seção Telemetria Nativa ao Vivo
        val telemetryTitle =
            VoidText.title(context, context.getString(R.string.settings_telemetry_title), sizeSp = 15f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 10f))
            }
        rail.addView(telemetryTitle)

        val telemetryCard =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        cornerRadius = VoidTheme.dp(context, 8f)
                        setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
                    }
                setPadding(
                    VoidTheme.dpToPx(context, 14f),
                    VoidTheme.dpToPx(context, 12f),
                    VoidTheme.dpToPx(context, 14f),
                    VoidTheme.dpToPx(context, 12f),
                )
            }

        fun addTelemetryRow(initialText: String): TextView {
            val tv =
                TextView(context).apply {
                    text = initialText
                    textSize = 12f
                    typeface = VoidTheme.typefaceMono
                    setTextColor(VoidTheme.colorText)
                    setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 4f))
                }
            telemetryCard.addView(tv)
            return tv
        }

        telemetryGpuView = addTelemetryRow(context.getString(R.string.settings_telemetry_gpu, "—"))
        telemetryThermalView = addTelemetryRow(context.getString(R.string.settings_telemetry_temp, "—"))
        telemetryRefreshView = addTelemetryRow(context.getString(R.string.settings_telemetry_refresh, "—"))

        rail.addView(telemetryCard)

        host.setContextContent(rail)

        // Registrar listener de telemetria
        activity.statsTelemetryListeners.remove(telemetryListener)
        activity.statsTelemetryListeners.add(telemetryListener)
    }

    private fun buildOptionsForCategory(cat: Category): LinearLayout {
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                setPadding(0, 0, VoidTheme.dpToPx(context, 16f), 0)
            }

        when (cat) {
            Category.VIDEO -> {
                content.addView(buildUpscalingRow())
                content.addView(buildFoveationRow())
            }
            Category.LIGHTING -> {
                content.addView(buildEnvironmentBrightnessRow())
                content.addView(buildScreenGlowRow())
                content.addView(buildColorTemperatureRow())
                content.addView(
                    buildFlagRow(
                        labelRes = R.string.settings_night_mode_label,
                        descriptionRes = R.string.settings_night_mode_description,
                        flag = FeatureFlags.Flag.NIGHT_MODE,
                        onChanged = { enabled -> activity.nativeSetNightMode(enabled) },
                    ),
                )
                if (activity.nativeIsPassthroughSupported()) {
                    content.addView(
                        buildFlagRow(
                            labelRes = R.string.passthrough_toggle_label,
                            descriptionRes = R.string.passthrough_toggle_description,
                            flag = FeatureFlags.Flag.PASSTHROUGH,
                            actionButtonTextRes = R.string.settings_passthrough_open_button,
                            onActionClicked = { activity.openPassthroughSettingsModal() },
                            onChanged = { enabled -> activity.setPassthroughEnabled(enabled, updatePrefs = false) },
                        ),
                    )
                }
            }
            Category.AUDIO -> {
                content.addView(buildSpatialAudioModeRow())
                content.addView(
                    buildFlagRow(
                        labelRes = R.string.settings_spatial_head_tracking_label,
                        descriptionRes = R.string.settings_spatial_head_tracking_description,
                        flag = FeatureFlags.Flag.SPATIAL_HEAD_TRACKING,
                        onChanged = { enabled ->
                            activity.nativeSetSpatialAudioHeadTracking(enabled)
                            screenLockedRow?.visibility = if (enabled) View.VISIBLE else View.GONE
                        },
                    ).also { row -> headTrackingRow = row },
                )
                val headTrackingEnabled = FeatureFlags.isEnabled(context, FeatureFlags.Flag.SPATIAL_HEAD_TRACKING)
                buildFlagRow(
                    labelRes = R.string.settings_spatial_screen_locked_label,
                    descriptionRes = R.string.settings_spatial_screen_locked_description,
                    flag = FeatureFlags.Flag.SPATIAL_SCREEN_LOCKED,
                    onChanged = { locked -> activity.nativeSetAudioScreenLocked(locked) },
                ).also { row ->
                    screenLockedRow = row
                    row.visibility = if (headTrackingEnabled) View.VISIBLE else View.GONE
                    content.addView(row)
                }
            }
            Category.SUBTITLES -> {
                content.addView(
                    buildFlagRow(
                        labelRes = R.string.settings_subtitles_auto_load_label,
                        descriptionRes = R.string.settings_subtitles_auto_load_description,
                        flag = FeatureFlags.Flag.AUTO_LOAD_SUBTITLES,
                        onChanged = { /* flag persisted automatically */ },
                    ),
                )
            }
            Category.PLAYBACK -> {
                content.addView(
                    buildFlagRow(
                        labelRes = R.string.settings_pause_on_exit_label,
                        descriptionRes = R.string.settings_pause_on_exit_description,
                        flag = FeatureFlags.Flag.PAUSE_ON_EXIT,
                        onChanged = { enabled -> activity.nativeSetPauseOnExit(enabled) },
                    ),
                )
                content.addView(buildResumePolicyRow())
            }
            Category.CONTROLS -> {
                content.addView(buildPanelFovRow())
                content.addView(buildPanelDistanceRow())
                content.addView(
                    buildFlagRow(
                        labelRes = R.string.settings_by_back_label,
                        descriptionRes = R.string.settings_by_back_desc,
                        flag = FeatureFlags.Flag.BY_BUTTON_AS_BACK,
                        onChanged = { enabled -> activity.nativeSetByButtonAsBack(enabled) },
                    ),
                )
                content.addView(
                    buildFlagRow(
                        labelRes = R.string.settings_hover_haptics_label,
                        descriptionRes = R.string.settings_hover_haptics_desc,
                        flag = FeatureFlags.Flag.HOVER_HAPTICS,
                        onChanged = { enabled -> activity.nativeSetHoverHapticsEnabled(enabled) },
                    ),
                )
                content.addView(
                    buildFlagRow(
                        labelRes = R.string.settings_hand_tracking_label,
                        descriptionRes = R.string.settings_hand_tracking_description,
                        flag = FeatureFlags.Flag.HAND_TRACKING,
                        onChanged = { enabled -> activity.nativeSetHandTrackingEnabled(enabled) },
                    ),
                )
            }
            Category.ADVANCED -> {
                content.addView(
                    buildFlagRow(
                        labelRes = R.string.settings_debug_stats_label,
                        descriptionRes = R.string.settings_debug_stats_description,
                        flag = FeatureFlags.Flag.DEBUG_STATS_PANEL,
                        actionButtonTextRes = R.string.settings_debug_stats_open_button,
                        onActionClicked = { activity.cycleDebugStatsMode() },
                        onChanged = { enabled -> activity.setDebugStatsEnabled(enabled) },
                    ),
                )
                content.addView(
                    buildFlagRow(
                        labelRes = R.string.settings_telemetry_export_label,
                        descriptionRes = R.string.settings_telemetry_export_description,
                        flag = FeatureFlags.Flag.DEBUG_STATS_EXPORT,
                        onChanged = { /* flag persisted automatically */ },
                    ),
                )
            }
        }

        return content
    }

    private fun buildResumePolicyRow(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

            addView(
                VoidText.title(context, context.getString(R.string.settings_resume_policy_label), sizeSp = 16f).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
                },
            )
            addView(
                VoidText.body(context, context.getString(R.string.settings_resume_policy_desc), sizeSp = 14f, secondary = true).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
                },
            )

            val current = FeatureFlags.getPlaybackResumePolicy(context)
            val selector =
                VoidSegmentedSelector(
                    context = context,
                    options =
                        listOf(
                            SegmentOption(PlaybackResumePolicy.ASK, context.getString(R.string.settings_resume_ask)),
                            SegmentOption(PlaybackResumePolicy.RESUME, context.getString(R.string.settings_resume_always)),
                            SegmentOption(PlaybackResumePolicy.START, context.getString(R.string.settings_resume_never)),
                        ),
                    initialSelectedValue = current,
                    onOptionSelected = { policy ->
                        FeatureFlags.setPlaybackResumePolicy(context, policy)
                    },
                )
            addView(selector)
        }

    private fun buildPanelFovRow(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

            addView(
                VoidText.title(context, context.getString(R.string.settings_panel_fov_label), sizeSp = 16f).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
                },
            )
            addView(
                VoidText.body(context, context.getString(R.string.settings_panel_fov_desc), sizeSp = 14f, secondary = true).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
                },
            )

            val currentFov = FeatureFlags.getUiPanelFov(context)
            val selector =
                VoidSegmentedSelector(
                    context = context,
                    options =
                        listOf(
                            SegmentOption(50.0f, context.getString(R.string.settings_panel_fov_50)),
                            SegmentOption(80.0f, context.getString(R.string.settings_panel_fov_80)),
                            SegmentOption(100.0f, context.getString(R.string.settings_panel_fov_100)),
                        ),
                    initialSelectedValue = currentFov,
                    onOptionSelected = { fovDeg ->
                        FeatureFlags.setUiPanelFov(context, fovDeg)
                        val dist = FeatureFlags.getUiPanelDistance(context)
                        activity.nativeSetUiPanelGeometry(dist, fovDeg)
                    },
                )
            addView(selector)
        }

    private fun buildPanelDistanceRow(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

            addView(
                VoidText.title(context, context.getString(R.string.settings_panel_distance_label), sizeSp = 16f).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
                },
            )
            addView(
                VoidText.body(context, context.getString(R.string.settings_panel_distance_desc), sizeSp = 14f, secondary = true).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
                },
            )

            val currentDist = FeatureFlags.getUiPanelDistance(context)
            val selector =
                VoidSegmentedSelector(
                    context = context,
                    options =
                        listOf(
                            SegmentOption(1.3f, context.getString(R.string.settings_panel_dist_13)),
                            SegmentOption(1.6f, context.getString(R.string.settings_panel_dist_16)),
                            SegmentOption(2.0f, context.getString(R.string.settings_panel_dist_20)),
                        ),
                    initialSelectedValue = currentDist,
                    onOptionSelected = { distance ->
                        FeatureFlags.setUiPanelDistance(context, distance)
                        val fov = FeatureFlags.getUiPanelFov(context)
                        activity.nativeSetUiPanelGeometry(distance, fov)
                    },
                )
            addView(selector)
        }

    private fun buildFlagRow(
        labelRes: Int,
        descriptionRes: Int,
        flag: FeatureFlags.Flag,
        actionButtonTextRes: Int? = null,
        onActionClicked: (() -> Unit)? = null,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

            val isFlagEnabled = FeatureFlags.isEnabled(context, flag)
            var actionBtn: VoidButton? = null

            addView(
                CheckBox(context).apply {
                    text = context.getString(labelRes)
                    textSize = 17f
                    setTextColor(VoidTheme.colorText)
                    isChecked = isFlagEnabled
                    setOnCheckedChangeListener { _, checked ->
                        FeatureFlags.setEnabled(context, flag, checked)
                        actionBtn?.visibility = if (checked) View.VISIBLE else View.GONE
                        onChanged(checked)
                    }
                },
            )

            addView(
                VoidText.body(context, context.getString(descriptionRes), sizeSp = 13f, secondary = true).apply {
                    setPadding(VoidTheme.dpToPx(context, 4f), VoidTheme.dpToPx(context, 2f), 0, 0)
                },
            )

            if (actionButtonTextRes != null && onActionClicked != null) {
                actionBtn =
                    VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                        text = context.getString(actionButtonTextRes)
                        textSize = 13f
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                VoidTheme.dpToPx(context, 48f),
                            ).apply {
                                topMargin = VoidTheme.dpToPx(context, 8f)
                                leftMargin = VoidTheme.dpToPx(context, 4f)
                            }
                        visibility = if (isFlagEnabled) View.VISIBLE else View.GONE
                        setOnClickListener { onActionClicked() }
                    }
                addView(actionBtn)
            }
        }

    private fun buildSpatialAudioModeRow(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

            addView(
                VoidText.title(context, context.getString(R.string.settings_spatial_audio_label), sizeSp = 16f).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
                },
            )

            addView(
                VoidText.body(
                    context,
                    context.getString(R.string.settings_spatial_audio_description),
                    sizeSp = 13f,
                    secondary = true,
                ).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
                },
            )

            val currentMode = FeatureFlags.getSpatialAudioMode(context)
            val selector =
                VoidSegmentedSelector(
                    context = context,
                    options =
                        listOf(
                            SegmentOption(0, context.getString(R.string.settings_spatial_audio_mode_off)),
                            SegmentOption(1, context.getString(R.string.settings_spatial_audio_mode_binaural)),
                            SegmentOption(2, context.getString(R.string.settings_spatial_audio_mode_downmix)),
                        ),
                    initialSelectedValue = currentMode,
                    onOptionSelected = { mode ->
                        FeatureFlags.setSpatialAudioMode(context, mode)
                        activity.nativeSetSpatialAudioMode(mode)
                    },
                )
            addView(selector)
        }

    private fun buildUpscalingRow(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

            addView(
                VoidText.title(context, context.getString(R.string.settings_upscaling_label), sizeSp = 16f).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
                },
            )

            addView(
                VoidText.body(context, context.getString(R.string.settings_upscaling_description), sizeSp = 13f, secondary = true).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
                },
            )

            val currentMode = activity.upscalingStore.get()
            val selector =
                VoidSegmentedSelector(
                    context = context,
                    options =
                        listOf(
                            SegmentOption(UpscalingModeStore.Mode.OFF, context.getString(R.string.settings_upscaling_mode_off)),
                            SegmentOption(UpscalingModeStore.Mode.QUALITY, context.getString(R.string.settings_upscaling_mode_quality)),
                            SegmentOption(
                                UpscalingModeStore.Mode.PERFORMANCE,
                                context.getString(R.string.settings_upscaling_mode_performance),
                            ),
                            SegmentOption(UpscalingModeStore.Mode.AUTO, context.getString(R.string.settings_upscaling_mode_auto)),
                        ),
                    initialSelectedValue = currentMode,
                    onOptionSelected = { mode ->
                        activity.upscalingStore.set(mode)
                        activity.nativeSetUpscalingMode(mode.id)
                    },
                )
            addView(selector)
        }

    private fun buildFoveationRow(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

            addView(
                VoidText.title(context, context.getString(R.string.settings_foveated_rendering_label), sizeSp = 16f).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
                },
            )

            addView(
                VoidText.body(
                    context,
                    context.getString(R.string.settings_foveated_rendering_description),
                    sizeSp = 13f,
                    secondary = true,
                ).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
                },
            )

            val currentMode = FeatureFlags.getFoveatedRenderingMode(context)
            val selector =
                VoidSegmentedSelector(
                    context = context,
                    options =
                        listOf(
                            SegmentOption(0, context.getString(R.string.settings_foveated_mode_off)),
                            SegmentOption(1, context.getString(R.string.settings_foveated_mode_low)),
                            SegmentOption(2, context.getString(R.string.settings_foveated_mode_medium)),
                            SegmentOption(3, context.getString(R.string.settings_foveated_mode_high)),
                            SegmentOption(4, context.getString(R.string.settings_foveated_mode_auto)),
                        ),
                    initialSelectedValue = currentMode,
                    onOptionSelected = { mode ->
                        FeatureFlags.setFoveatedRenderingMode(context, mode)
                        activity.nativeSetFoveationMode(mode)
                        activity.nativeSetFoveationEnabled(mode != 0)
                    },
                )
            addView(selector)
        }

    private fun buildEnvironmentBrightnessRow(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

            val currentBrightness = FeatureFlags.getEnvironmentBrightness(context)
            val initialPercent = (currentBrightness * 100f).roundToInt().coerceIn(0, 100)

            val headerRow =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                }

            val titleView =
                VoidText.title(context, context.getString(R.string.settings_environment_brightness_label), sizeSp = 16f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            val valueView =
                VoidText.body(
                    context,
                    context.getString(R.string.settings_environment_brightness_percent, initialPercent),
                    sizeSp = 14f,
                    secondary = true,
                )
            headerRow.addView(titleView)
            headerRow.addView(valueView)
            addView(headerRow)

            addView(
                VoidText.body(
                    context,
                    context.getString(R.string.settings_environment_brightness_description),
                    sizeSp = 13f,
                    secondary = true,
                ).apply {
                    setPadding(0, VoidTheme.dpToPx(context, 2f), 0, VoidTheme.dpToPx(context, 4f))
                },
            )

            val seekBar =
                SeekBar(context).apply {
                    max = 100
                    progress = initialPercent
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            VoidTheme.dpToPx(context, 44f),
                        )
                    progressTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
                    thumbTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(
                                sb: SeekBar?,
                                prog: Int,
                                fromUser: Boolean,
                            ) {
                                val brightness = prog / 100f
                                valueView.text = context.getString(R.string.settings_environment_brightness_percent, prog)
                                if (fromUser) {
                                    FeatureFlags.setEnvironmentBrightness(context, brightness)
                                    activity.nativeSetEnvironmentBrightness(brightness)
                                }
                            }

                            override fun onStartTrackingTouch(sb: SeekBar?) {}

                            override fun onStopTrackingTouch(sb: SeekBar?) {}
                        },
                    )
                }
            addView(seekBar)
        }

    private fun buildScreenGlowRow(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

            addView(
                VoidText.title(context, context.getString(R.string.settings_screen_glow_label), sizeSp = 16f).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
                },
            )

            addView(
                VoidText.body(context, context.getString(R.string.settings_screen_glow_description), sizeSp = 13f, secondary = true).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
                },
            )

            val currentMode = FeatureFlags.getScreenGlowIntensityMode(context)
            val selector =
                VoidSegmentedSelector(
                    context = context,
                    options =
                        listOf(
                            SegmentOption(FeatureFlags.SCREEN_GLOW_OFF, context.getString(R.string.settings_screen_glow_off)),
                            SegmentOption(FeatureFlags.SCREEN_GLOW_SUBTLE, context.getString(R.string.settings_screen_glow_subtle)),
                            SegmentOption(FeatureFlags.SCREEN_GLOW_STRONG, context.getString(R.string.settings_screen_glow_strong)),
                        ),
                    initialSelectedValue = currentMode,
                    onOptionSelected = { mode ->
                        FeatureFlags.setScreenGlowIntensityMode(context, mode)
                        val intensity = FeatureFlags.screenGlowModeToFloat(mode)
                        activity.nativeSetScreenGlowIntensity(intensity)
                        activity.nativeSetAmbientMode(mode != FeatureFlags.SCREEN_GLOW_OFF)
                    },
                )
            addView(selector)
        }

    private fun buildColorTemperatureRow(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

            val currentKelvin = FeatureFlags.getColorTemperature(context)
            val initialProgress = ((currentKelvin - 2700f) / 100f).roundToInt().coerceIn(0, 38)

            val headerRow =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                }

            val titleView =
                VoidText.title(context, context.getString(R.string.settings_color_temperature_label), sizeSp = 16f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            val valueView =
                VoidText.body(
                    context,
                    context.getString(R.string.settings_color_temperature_kelvin, currentKelvin.toInt()),
                    sizeSp = 14f,
                    secondary = true,
                )
            headerRow.addView(titleView)
            headerRow.addView(valueView)
            addView(headerRow)

            addView(
                VoidText.body(
                    context,
                    context.getString(R.string.settings_color_temperature_description),
                    sizeSp = 13f,
                    secondary = true,
                ).apply {
                    setPadding(0, VoidTheme.dpToPx(context, 2f), 0, VoidTheme.dpToPx(context, 4f))
                },
            )

            val seekBar =
                SeekBar(context).apply {
                    max = 38
                    progress = initialProgress
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            VoidTheme.dpToPx(context, 44f),
                        )
                    progressTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
                    thumbTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(
                                sb: SeekBar?,
                                prog: Int,
                                fromUser: Boolean,
                            ) {
                                val kelvin = 2700f + prog * 100f
                                valueView.text = context.getString(R.string.settings_color_temperature_kelvin, kelvin.toInt())
                                if (fromUser) {
                                    FeatureFlags.setColorTemperature(context, kelvin)
                                    activity.nativeSetColorTemperature(kelvin)
                                }
                            }

                            override fun onStartTrackingTouch(sb: SeekBar?) {}

                            override fun onStopTrackingTouch(sb: SeekBar?) {}
                        },
                    )
                }
            addView(seekBar)
        }
}
