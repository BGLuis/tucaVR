package com.tucavr.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tucavr.R

/**
 * Floating popup modal for Screen geometry, scale, distance, and curvature settings.
 *
 * Placed above the controls bar in [com.tucavr.VRControlsPresentation].
 * Matches Figma specification (Player menu Tela #125:836).
 */
class ScreenSettingsModal(
    context: Context,
    private var currentScaleX: Float,
    private var currentDistanceZ: Float,
    private var isCurved: Boolean,
    private val onScaleSelected: (scaleX: Float) -> Unit,
    private val onDistanceSelected: (posZ: Float) -> Unit,
    private val onCurvedToggled: (curved: Boolean) -> Unit,
    private val onRecenter: () -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    private data class ScaleOption(val scaleX: Float, val labelResId: Int)

    private data class DistanceOption(val posZ: Float, val labelResId: Int)

    private data class CurveOption(val isCurved: Boolean, val labelResId: Int)

    private val scaleOptions =
        listOf(
            ScaleOption(2.0f, R.string.player_menu_screen_size_small),
            ScaleOption(2.8f, R.string.player_menu_screen_size_medium),
            ScaleOption(4.0f, R.string.player_menu_screen_size_large),
            ScaleOption(6.0f, R.string.player_menu_screen_size_huge),
        )

    private val distanceOptions =
        listOf(
            DistanceOption(-1.8f, R.string.player_menu_screen_distance_near),
            DistanceOption(-2.4f, R.string.player_menu_screen_distance_default),
            DistanceOption(-3.5f, R.string.player_menu_screen_distance_far),
        )

    private val curveOptions =
        listOf(
            CurveOption(false, R.string.player_menu_screen_curve_flat),
            CurveOption(true, R.string.player_menu_screen_curve_curved),
        )

    private val scaleButtons = mutableListOf<Pair<Float, VoidButton>>()
    private val distanceButtons = mutableListOf<Pair<Float, VoidButton>>()
    private val curveButtons = mutableListOf<Pair<Boolean, VoidButton>>()

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        setOnClickListener { onDismiss() }

        val panelWidth = VoidTheme.dpToPx(context, 720f)
        val panel =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LayoutParams(panelWidth, LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER
                    }
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurface)
                        cornerRadius = VoidTheme.dp(context, 16f)
                        setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
                    }
                val pad = VoidTheme.dpToPx(context, 20f)
                setPadding(pad, pad, pad, pad)
                isClickable = true
                setOnClickListener { /* Consume click inside card */ }
            }

        // Header: Icon + Title + Close Button
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 14f)
                    }
            }

        val icon =
            ImageView(context).apply {
                setImageResource(R.drawable.icon_2d)
                setColorFilter(VoidTheme.colorText)
                val iconSize = VoidTheme.dpToPx(context, 28f)
                layoutParams =
                    LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        rightMargin = VoidTheme.dpToPx(context, 10f)
                    }
            }
        header.addView(icon)

        val title =
            TextView(context).apply {
                text = context.getString(R.string.player_menu_screen_title)
                typeface = VoidTheme.typefaceTitle
                textSize = 22f
                setTextColor(VoidTheme.colorText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        header.addView(title)

        val closeBtn =
            VoidIconButton(
                context,
                R.drawable.icon_x,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                val s = VoidTheme.dpToPx(context, 40f)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setOnClickListener { onDismiss() }
            }
        header.addView(closeBtn)
        panel.addView(header)

        // Section 1: Tamanho / Size
        panel.addView(createSectionHeader(context.getString(R.string.player_menu_screen_size)))
        val scaleRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 12f)
                    }
            }
        for ((idx, opt) in scaleOptions.withIndex()) {
            val isSelected = kotlin.math.abs(currentScaleX - opt.scaleX) < 0.2f
            val btn =
                VoidButton(
                    context,
                    if (isSelected) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY,
                    isCompact = true,
                ).apply {
                    text = context.getString(opt.labelResId)
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (idx > 0) leftMargin = VoidTheme.dpToPx(context, 8f)
                        }
                    setOnClickListener {
                        currentScaleX = opt.scaleX
                        onScaleSelected(opt.scaleX)
                        updateScaleButtonStyles()
                    }
                }
            scaleButtons.add(opt.scaleX to btn)
            scaleRow.addView(btn)
        }
        panel.addView(scaleRow)

        // Section 2: Distância / Distance
        panel.addView(createSectionHeader(context.getString(R.string.player_menu_screen_distance)))
        val distRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 12f)
                    }
            }
        for ((idx, opt) in distanceOptions.withIndex()) {
            val isSelected = kotlin.math.abs(currentDistanceZ - opt.posZ) < 0.3f
            val btn =
                VoidButton(
                    context,
                    if (isSelected) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY,
                    isCompact = true,
                ).apply {
                    text = context.getString(opt.labelResId)
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (idx > 0) leftMargin = VoidTheme.dpToPx(context, 8f)
                        }
                    setOnClickListener {
                        currentDistanceZ = opt.posZ
                        onDistanceSelected(opt.posZ)
                        updateDistanceButtonStyles()
                    }
                }
            distanceButtons.add(opt.posZ to btn)
            distRow.addView(btn)
        }
        panel.addView(distRow)

        // Section 3: Curvatura / Curvature
        panel.addView(createSectionHeader(context.getString(R.string.player_menu_screen_curve)))
        val curveRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 16f)
                    }
            }
        for ((idx, opt) in curveOptions.withIndex()) {
            val isSelected = isCurved == opt.isCurved
            val btn =
                VoidButton(
                    context,
                    if (isSelected) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY,
                    isCompact = true,
                ).apply {
                    text = context.getString(opt.labelResId)
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (idx > 0) leftMargin = VoidTheme.dpToPx(context, 8f)
                        }
                    setOnClickListener {
                        isCurved = opt.isCurved
                        onCurvedToggled(opt.isCurved)
                        updateCurveButtonStyles()
                    }
                }
            curveButtons.add(opt.isCurved to btn)
            curveRow.addView(btn)
        }
        panel.addView(curveRow)

        // Recenter Button
        val btnRecenter =
            VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = context.getString(R.string.player_btn_recenter)
                setIcon(R.drawable.recenter)
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                setOnClickListener {
                    onRecenter()
                }
            }
        panel.addView(btnRecenter)

        addView(panel)
    }

    private fun createSectionHeader(title: String): TextView =
        TextView(context).apply {
            text = title
            typeface = VoidTheme.typefaceMono
            textSize = 13f
            setTextColor(VoidTheme.colorTextSecondary)
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = VoidTheme.dpToPx(context, 6f)
                }
        }

    private fun updateScaleButtonStyles() {
        for ((scaleX, btn) in scaleButtons) {
            val isSelected = kotlin.math.abs(currentScaleX - scaleX) < 0.2f
            btn.style = if (isSelected) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        }
    }

    private fun updateDistanceButtonStyles() {
        for ((posZ, btn) in distanceButtons) {
            val isSelected = kotlin.math.abs(currentDistanceZ - posZ) < 0.3f
            btn.style = if (isSelected) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        }
    }

    private fun updateCurveButtonStyles() {
        for ((curved, btn) in curveButtons) {
            val isSelected = isCurved == curved
            btn.style = if (isSelected) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        }
    }
}
