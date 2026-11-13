package com.tucavr.designsystem

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.ImageButton

// The ACTIVE icon is colorBackground, so without its accent fill it vanishes into the player bar.
internal fun iconButtonDrawsContainer(
    style: VoidButtonStyle,
    isTransparent: Boolean,
): Boolean = !isTransparent || style == VoidButtonStyle.ACTIVE

class VoidIconButton
    @JvmOverloads
    constructor(
        context: Context,
        iconResId: Int,
        style: VoidButtonStyle = VoidButtonStyle.PRIMARY,
        private val isCircular: Boolean = true,
        private val isTransparent: Boolean = false,
    ) : ImageButton(context) {
        var style: VoidButtonStyle = style
            set(value) {
                field = value
                applyStyle()
            }

        init {
            scaleType = ScaleType.FIT_CENTER
            setImageResource(iconResId)

            // Padding padrao para garantir um bom hit target
            val padding = VoidTheme.dpToPx(context, 12f)
            setPadding(padding, padding, padding, padding)

            applyStyle()
        }

        private fun applyStyle() {
            val bgColor: Int
            val borderColor: Int
            val iconColor: Int
            when (style) {
                VoidButtonStyle.PRIMARY -> {
                    bgColor = VoidTheme.colorSurfaceAlt
                    borderColor = VoidTheme.colorAccent
                    iconColor = VoidTheme.colorText
                }
                VoidButtonStyle.SECONDARY -> {
                    bgColor = VoidTheme.colorSurface
                    borderColor = VoidTheme.colorBorder
                    iconColor = VoidTheme.colorText
                }
                VoidButtonStyle.ACTIVE -> {
                    bgColor = VoidTheme.colorAccent
                    borderColor = VoidTheme.colorAccent
                    iconColor = VoidTheme.colorBackground
                }
                VoidButtonStyle.DISABLED -> {
                    bgColor = VoidTheme.colorSurface
                    borderColor = VoidTheme.colorBorder
                    iconColor = VoidTheme.colorTextSecondary
                }
            }

            val drawsContainer = iconButtonDrawsContainer(style, isTransparent)
            val baseBg =
                GradientDrawable().apply {
                    setColor(if (drawsContainer) bgColor else android.graphics.Color.TRANSPARENT)
                    cornerRadius = if (isCircular) VoidTheme.dp(context, 200f) else VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
                    setStroke(
                        if (drawsContainer) VoidTheme.dpToPx(context, VoidTheme.borderWidthDp) else 0,
                        if (drawsContainer) borderColor else android.graphics.Color.TRANSPARENT,
                    )
                }

            val mask =
                GradientDrawable().apply {
                    setColor(android.graphics.Color.WHITE)
                    cornerRadius = if (isCircular) VoidTheme.dp(context, 200f) else VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
                }

            background = RippleDrawable(ColorStateList.valueOf(android.graphics.Color.parseColor("#33FFFFFF")), baseBg, mask)

            setColorFilter(iconColor)
            isEnabled = style != VoidButtonStyle.DISABLED
            alpha = if (style == VoidButtonStyle.DISABLED) 0.55f else 1f
        }
    }
