package com.tucavr.designsystem

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button

/**
 * Chip de filtro selecionável (estilo pílula) do design system Void.
 */
class VoidFilterChip @JvmOverloads constructor(
    context: Context,
    text: String = "",
    var isSelectedChip: Boolean = false,
    private var iconResId: Int = 0
) : Button(context) {

    init {
        this.text = text
        isAllCaps = false
        typeface = VoidTheme.typefaceBody
        textSize = 15f
        gravity = Gravity.CENTER
        val padH = VoidTheme.dpToPx(context, 16f)
        val padV = VoidTheme.dpToPx(context, 10f)
        setPadding(padH, padV, padH, padV)
        minHeight = VoidTheme.dpToPx(context, 48f)
        minWidth = VoidTheme.dpToPx(context, 60f)
        compoundDrawablePadding = VoidTheme.dpToPx(context, 8f)
        updateStyle()
    }

    fun setSelectedState(selected: Boolean) {
        isSelectedChip = selected
        updateStyle()
    }

    fun setIcon(resId: Int) {
        iconResId = resId
        updateStyle()
    }

    private fun updateStyle() {
        val bgColor = if (isSelectedChip) VoidTheme.colorAccent else VoidTheme.colorSurface
        val borderColor = if (isSelectedChip) VoidTheme.colorAccent else VoidTheme.colorBorder
        val textColor = if (isSelectedChip) VoidTheme.colorBackground else VoidTheme.colorText

        background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = VoidTheme.dp(context, 200f) // Pílula arredondada
            setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), borderColor)
        }
        setTextColor(textColor)

        if (iconResId != 0) {
            val icon = context.getDrawable(iconResId)?.mutate()
            icon?.colorFilter = PorterDuffColorFilter(textColor, PorterDuff.Mode.SRC_IN)
            if (icon != null) {
                val size = VoidTheme.dpToPx(context, 18f)
                icon.setBounds(0, 0, size, size)
                setCompoundDrawables(icon, null, null, null)
            }
        } else {
            setCompoundDrawables(null, null, null, null)
        }
    }
}
