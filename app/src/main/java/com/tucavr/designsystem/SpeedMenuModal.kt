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
 * Floating popup modal for playback speed presets (0.5×, 0.75×, 1.0×, 1.25×, 1.5×, 2.0×).
 *
 * Placed above the controls bar in [com.tucavr.VRControlsPresentation].
 * Matches Figma specification (Player menu Velocidade #125:1014).
 */
class SpeedMenuModal(
    context: Context,
    private var currentSpeed: Float,
    private val onSpeedSelected: (speed: Float) -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    private val speedPresets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val speedButtons = mutableListOf<Pair<Float, VoidButton>>()

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        setOnClickListener { onDismiss() }

        val panelWidth = VoidTheme.dpToPx(context, 680f)
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
                        bottomMargin = VoidTheme.dpToPx(context, 16f)
                    }
            }

        val icon =
            ImageView(context).apply {
                setImageResource(R.drawable.icon_gauge)
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
                text = context.getString(R.string.player_menu_speed_title)
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

        // Options Row (Chips)
        val optionsRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }

        for ((idx, speed) in speedPresets.withIndex()) {
            val isSelected = kotlin.math.abs(currentSpeed - speed) < 0.05f
            val btn =
                VoidButton(
                    context,
                    if (isSelected) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY,
                    isCompact = true,
                ).apply {
                    text = formatSpeedLabel(speed)
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (idx > 0) leftMargin = VoidTheme.dpToPx(context, 8f)
                        }
                    setOnClickListener {
                        currentSpeed = speed
                        onSpeedSelected(speed)
                        updateSpeedButtonStyles()
                    }
                }
            speedButtons.add(speed to btn)
            optionsRow.addView(btn)
        }
        panel.addView(optionsRow)

        addView(panel)
    }

    private fun formatSpeedLabel(speed: Float): String {
        return if (speed == speed.toInt().toFloat()) {
            "${speed.toInt()}×"
        } else {
            val formatted = String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
            "$formatted×"
        }
    }

    private fun updateSpeedButtonStyles() {
        for ((speed, btn) in speedButtons) {
            val isSelected = kotlin.math.abs(currentSpeed - speed) < 0.05f
            btn.style = if (isSelected) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        }
    }
}
