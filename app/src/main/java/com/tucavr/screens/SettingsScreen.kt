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
import com.tucavr.BuildConfig
import com.tucavr.FeatureFlags
import com.tucavr.HdrLuminanceStore
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
        ABOUT(R.string.settings_section_about, R.string.settings_about_description),
    }

    private var selectedCategory = Category.VIDEO
    private lateinit var categoryViews: Map<Category, LinearLayout>
    private lateinit var optionsContainer: FrameLayout

    // Live preview rail views
    private var standardContextRail: LinearLayout? = null
    private var privacyContextRail: LinearLayout? = null
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
        val isAbout = (cat == Category.ABOUT)
        standardContextRail?.visibility = if (isAbout) View.GONE else View.VISIBLE
        privacyContextRail?.visibility = if (isAbout) View.VISIBLE else View.GONE
        previewDescriptionView?.text = context.getString(cat.descRes)
    }

    private fun setupLivePreviewRail() {
        val railContainer =
            FrameLayout(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }

        val rail =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    FrameLayout.LayoutParams(
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
        standardContextRail = rail

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

        val privacyRail = buildPrivacyContextRail()
        privacyContextRail = privacyRail

        railContainer.addView(rail)
        railContainer.addView(privacyRail)
        host.setContextContent(railContainer)

        // Registrar listener de telemetria
        activity.statsTelemetryListeners.remove(telemetryListener)
        activity.statsTelemetryListeners.add(telemetryListener)
    }

    private fun buildPrivacyContextRail(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            setPadding(
                VoidTheme.dpToPx(context, 16f),
                VoidTheme.dpToPx(context, 20f),
                VoidTheme.dpToPx(context, 16f),
                VoidTheme.dpToPx(context, 20f),
            )
            visibility = View.GONE

            addView(
                VoidText.mono(
                    context,
                    context.getString(R.string.settings_about_privacy_eyebrow),
                    sizeSp = 13f,
                    secondary = false,
                ).apply {
                    setTextColor(VoidTheme.colorAccent)
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 12f))
                },
            )

            val buildMode =
                context.getString(
                    if (activity.isDebuggable) R.string.settings_about_build_debug else R.string.settings_about_build_release,
                )
            val versionBadge =
                context.getString(
                    R.string.settings_about_privacy_version_format,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                    buildMode,
                )

            val card =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = VoidTheme.dpToPx(context, 14f) }
                    background =
                        GradientDrawable().apply {
                            setColor(VoidTheme.colorSurfaceAlt)
                            cornerRadius = VoidTheme.dp(context, 12f)
                        }
                    setPadding(
                        VoidTheme.dpToPx(context, 16f),
                        VoidTheme.dpToPx(context, 16f),
                        VoidTheme.dpToPx(context, 16f),
                        VoidTheme.dpToPx(context, 16f),
                    )

                    addView(VoidText.title(context, context.getString(R.string.app_name), sizeSp = 28f))
                    addView(
                        VoidText.mono(context, versionBadge, sizeSp = 12f, secondary = false).apply {
                            setTextColor(VoidTheme.colorAccent)
                            setPadding(0, VoidTheme.dpToPx(context, 4f), 0, VoidTheme.dpToPx(context, 6f))
                        },
                    )
                    addView(VoidText.body(context, context.getString(R.string.settings_about_privacy_headline), sizeSp = 15f))
                }
            addView(card)

            addView(
                VoidText.body(
                    context,
                    context.getString(R.string.settings_about_privacy_details),
                    sizeSp = 14f,
                    secondary = true,
                ),
            )
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
                content.addView(
                    buildFlagRow(
                        labelRes = R.string.settings_direct_video_layer_label,
                        descriptionRes = R.string.settings_direct_video_layer_description,
                        flag = FeatureFlags.Flag.DIRECT_VIDEO_LAYER,
                        onChanged = { enabled -> activity.nativeSetDirectVideoLayerEnabled(enabled) },
                    ),
                )
                content.addView(buildLutRow())
            }
            Category.LIGHTING -> {
                content.addView(buildEnvironmentBrightnessRow())
                content.addView(buildScreenGlowRow())
                content.addView(buildColorTemperatureRow())
                content.addView(buildHdrLuminanceRow())
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
                        labelRes = R.string.settings_match_content_frame_rate_label,
                        descriptionRes = R.string.settings_match_content_frame_rate_description,
                        flag = FeatureFlags.Flag.MATCH_CONTENT_FRAME_RATE,
                        onChanged = { enabled -> activity.nativeSetMatchContentFrameRate(enabled) },
                    ),
                )
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
                        onChanged = { activity.refreshNativeDebugStatsStream() },
                    ),
                )
            }
            Category.ABOUT -> {
                buildAboutCategory(content)
            }
        }

        return content
    }

    private fun buildAboutCategory(content: LinearLayout) {
        val versionCard = buildAboutCard()
        val versionHeader =
            context.getString(
                R.string.settings_about_version_format,
                context.getString(R.string.app_name),
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            )
        versionCard.addView(
            VoidText.body(context, versionHeader, sizeSp = 18f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
            },
        )
        versionCard.addView(
            VoidText.body(context, context.getString(R.string.settings_about_app_tagline), sizeSp = 13f, secondary = true),
        )
        content.addView(versionCard)

        val licensesCard = buildAboutCard()
        licensesCard.addView(
            VoidText.body(context, context.getString(R.string.settings_about_licenses_title), sizeSp = 18f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
            },
        )
        licensesCard.addView(
            VoidText.body(context, context.getString(R.string.settings_about_licenses_subtitle), sizeSp = 13f, secondary = true).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 10f))
            },
        )
        val licenseEntries =
            listOf(
                R.string.settings_about_component_ffmpeg to R.string.settings_about_license_ffmpeg,
                R.string.settings_about_component_geckoview to R.string.settings_about_license_geckoview,
                R.string.settings_about_component_meta_openxr to R.string.settings_about_license_meta_openxr,
                R.string.settings_about_component_rust_crates to R.string.settings_about_license_rust,
            )
        licenseEntries.forEachIndexed { index, (componentRes, licenseRes) ->
            licensesCard.addView(
                buildAboutRow(
                    label = context.getString(componentRes),
                    value = context.getString(licenseRes),
                    addBottomMargin = index < licenseEntries.lastIndex,
                ),
            )
        }
        content.addView(licensesCard)

        val translatorsCard = buildAboutCard()
        translatorsCard.addView(
            VoidText.body(context, context.getString(R.string.settings_about_translators_title), sizeSp = 18f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
            },
        )
        translatorsCard.addView(
            VoidText.body(context, context.getString(R.string.settings_about_translators_subtitle), sizeSp = 13f, secondary = true).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 10f))
            },
        )
        val credits = TranslatorsParser.loadFromAssets(context.assets)
        if (credits.isEmpty()) {
            translatorsCard.addView(
                VoidText.body(context, context.getString(R.string.settings_about_translators_empty), sizeSp = 14f, secondary = true),
            )
        } else {
            credits.forEachIndexed { index, credit ->
                val reviewer = credit.reviewedBy ?: context.getString(R.string.settings_about_review_pending)
                translatorsCard.addView(
                    buildAboutRow(
                        label = credit.language,
                        value = credit.translators,
                        subline = context.getString(R.string.settings_about_reviewed_by_format, reviewer),
                        addBottomMargin = index < credits.lastIndex,
                    ),
                )
            }
        }
        content.addView(translatorsCard)
    }

    private fun buildAboutCard(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 12f) }
            background =
                GradientDrawable().apply {
                    setColor(VoidTheme.colorSurface)
                    cornerRadius = VoidTheme.dp(context, 12f)
                }
            setPadding(
                VoidTheme.dpToPx(context, 20f),
                VoidTheme.dpToPx(context, 16f),
                VoidTheme.dpToPx(context, 20f),
                VoidTheme.dpToPx(context, 16f),
            )
        }

    private fun buildAboutRow(
        label: String,
        value: String,
        subline: String? = null,
        addBottomMargin: Boolean = true,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (addBottomMargin) bottomMargin = VoidTheme.dpToPx(context, 8f)
                }
            background =
                GradientDrawable().apply {
                    setColor(VoidTheme.colorSurfaceAlt)
                    cornerRadius = VoidTheme.dp(context, 10f)
                }
            setPadding(
                VoidTheme.dpToPx(context, 16f),
                VoidTheme.dpToPx(context, 10f),
                VoidTheme.dpToPx(context, 16f),
                VoidTheme.dpToPx(context, 10f),
            )

            val topRow =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    addView(
                        VoidText.body(context, label, sizeSp = 16f).apply {
                            layoutParams =
                                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                    marginEnd = VoidTheme.dpToPx(context, 12f)
                                }
                        },
                    )
                    addView(VoidText.mono(context, value, sizeSp = 13f, secondary = true))
                }
            addView(topRow)

            if (subline != null) {
                addView(
                    VoidText.mono(context, subline, sizeSp = 12f, secondary = true).apply {
                        setPadding(0, VoidTheme.dpToPx(context, 4f), 0, 0)
                    },
                )
            }
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
                    VoidButton(context, VoidButtonStyle.SECONDARY, isCompact = true).apply {
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
                    },
                )
            addView(selector)
        }

    private fun buildLutRow(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

            addView(
                VoidText.title(context, context.getString(R.string.settings_lut_label), sizeSp = 16f).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
                },
            )

            addView(
                VoidText.body(context, context.getString(R.string.settings_lut_description), sizeSp = 13f, secondary = true).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
                },
            )

            val activeTitle = activity.lutStore.getActiveTitle() ?: context.getString(R.string.settings_lut_none)
            val currentLutText =
                VoidText.body(context, activeTitle, sizeSp = 14f, secondary = !activity.lutStore.hasActiveLut()).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
                }
            addView(currentLutText)

            val buttonRow =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )

                    val loadBtn =
                        VoidButton(context, VoidButtonStyle.SECONDARY, isCompact = true).apply {
                            text = context.getString(R.string.settings_lut_load_button)
                            textSize = 13f
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    VoidTheme.dpToPx(context, 48f),
                                ).apply {
                                    rightMargin = VoidTheme.dpToPx(context, 8f)
                                }
                            setOnClickListener {
                                activity.openLutFilePicker()
                            }
                        }
                    addView(loadBtn)

                    val clearBtn =
                        VoidButton(context, VoidButtonStyle.SECONDARY, isCompact = true).apply {
                            text = context.getString(R.string.settings_lut_clear_button)
                            textSize = 13f
                            layoutParams =
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    VoidTheme.dpToPx(context, 48f),
                                )
                            visibility = if (activity.lutStore.hasActiveLut()) View.VISIBLE else View.GONE
                            setOnClickListener {
                                activity.clearLut()
                            }
                        }
                    addView(clearBtn)

                    activity.onLutChanged = {
                        val title = activity.lutStore.getActiveTitle() ?: context.getString(R.string.settings_lut_none)
                        currentLutText.text = title
                        clearBtn.visibility = if (activity.lutStore.hasActiveLut()) View.VISIBLE else View.GONE
                    }
                }
            addView(buttonRow)
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

    private fun buildHdrLuminanceRow(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

            val store = HdrLuminanceStore(context)
            val luminanceStep = 0.05f
            val maxProgress = ((HdrLuminanceStore.MAX - HdrLuminanceStore.MIN) / luminanceStep).roundToInt()
            val current = store.get()
            val percentText = { luminance: Float ->
                context.getString(R.string.settings_hdr_luminance_percent, (luminance * 100f).roundToInt())
            }

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
                VoidText.title(context, context.getString(R.string.settings_hdr_luminance_label), sizeSp = 16f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            val valueView = VoidText.body(context, percentText(current), sizeSp = 14f, secondary = true)
            headerRow.addView(titleView)
            headerRow.addView(valueView)
            addView(headerRow)

            addView(
                VoidText.body(
                    context,
                    context.getString(R.string.settings_hdr_luminance_description),
                    sizeSp = 13f,
                    secondary = true,
                ).apply {
                    setPadding(0, VoidTheme.dpToPx(context, 2f), 0, VoidTheme.dpToPx(context, 4f))
                },
            )

            val seekBar =
                SeekBar(context).apply {
                    max = maxProgress
                    progress = ((current - HdrLuminanceStore.MIN) / luminanceStep).roundToInt().coerceIn(0, maxProgress)
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
                                val luminance = HdrLuminanceStore.MIN + prog * luminanceStep
                                valueView.text = percentText(luminance)
                                if (fromUser) {
                                    store.set(luminance)
                                    activity.nativeSetHdrLuminance(luminance)
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
