package com.tucavr.designsystem

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.tucavr.R

/**
 * Floating popup modal for Volume control with slider and presets (0% to 200%).
 *
 * Placed above the controls bar in [com.tucavr.VRControlsPresentation].
 * Matches Figma specification (Player menu Volume #125:1034).
 */
class VolumeMenuModal(
    context: Context,
    private var currentVolume: Float,
    private val onVolumeChanged: (volume: Float) -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    private val volumePresets = listOf(0, 25, 50, 75, 100, 150, 200)
    private val presetButtons = mutableListOf<Pair<Int, VoidButton>>()
    private val volumeLabel: TextView
    private val volumeSeekBar: SeekBar
    private val muteButton: VoidIconButton
    private var lastNonZeroVolume: Float = if (currentVolume > 0f) currentVolume else 1.0f

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
                        bottomMargin = VoidTheme.dpToPx(context, 16f)
                    }
            }

        val icon =
            ImageView(context).apply {
                setImageResource(R.drawable.icon_volume_2)
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
                text = context.getString(R.string.player_menu_volume_title)
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

        // Slider Row: Mute Toggle + SeekBar + Percent Text
        val sliderRow =
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

        muteButton =
            VoidIconButton(
                context,
                R.drawable.icon_volume_2,
                if (currentVolume <= 0.01f) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                val s = VoidTheme.dpToPx(context, 44f)
                layoutParams =
                    LinearLayout.LayoutParams(s, s).apply {
                        rightMargin = VoidTheme.dpToPx(context, 12f)
                    }
                setOnClickListener {
                    if (currentVolume > 0.01f) {
                        lastNonZeroVolume = currentVolume
                        applyVolume(0.0f)
                    } else {
                        applyVolume(lastNonZeroVolume)
                    }
                }
            }
        sliderRow.addView(muteButton)

        val initialPercent = (currentVolume * 100f).toInt().coerceIn(0, 200)

        volumeSeekBar =
            SeekBar(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                max = 200
                progress = initialPercent
                progressTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
                thumbTintList = ColorStateList.valueOf(VoidTheme.colorAccent)
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            if (fromUser) {
                                applyVolume(progress / 100f)
                            }
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                    },
                )
            }
        sliderRow.addView(volumeSeekBar)

        volumeLabel =
            TextView(context).apply {
                text = context.getString(R.string.player_controls_volume_format, initialPercent)
                typeface = VoidTheme.typefaceMono
                textSize = 20f
                setTextColor(VoidTheme.colorText)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        VoidTheme.dpToPx(context, 76f),
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        leftMargin = VoidTheme.dpToPx(context, 12f)
                    }
            }
        sliderRow.addView(volumeLabel)
        panel.addView(sliderRow)

        // Presets Row
        val presetsRow =
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

        for ((idx, pct) in volumePresets.withIndex()) {
            val isSelected = kotlin.math.abs(initialPercent - pct) < 3
            val btn =
                VoidButton(
                    context,
                    if (isSelected) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY,
                    isCompact = true,
                ).apply {
                    text = context.getString(R.string.player_controls_volume_format, pct)
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (idx > 0) leftMargin = VoidTheme.dpToPx(context, 6f)
                        }
                    setOnClickListener {
                        applyVolume(pct / 100f)
                    }
                }
            presetButtons.add(pct to btn)
            presetsRow.addView(btn)
        }
        panel.addView(presetsRow)

        // Footer Hint
        val hintText =
            TextView(context).apply {
                text = context.getString(R.string.player_menu_volume_hint)
                typeface = VoidTheme.typefaceBody
                textSize = 13f
                setTextColor(VoidTheme.colorTextSecondary)
            }
        panel.addView(hintText)

        addView(panel)
    }

    private fun applyVolume(volume: Float) {
        currentVolume = volume
        val percent = (volume * 100f).toInt().coerceIn(0, 200)
        volumeSeekBar.progress = percent
        volumeLabel.text = context.getString(R.string.player_controls_volume_format, percent)
        muteButton.style = if (volume <= 0.01f) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY

        for ((pct, btn) in presetButtons) {
            val isSelected = kotlin.math.abs(percent - pct) < 3
            btn.style = if (isSelected) VoidButtonStyle.ACTIVE else VoidButtonStyle.SECONDARY
        }
        onVolumeChanged(volume)
    }
}
