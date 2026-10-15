package com.tucavr.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Seletor de opções horizontais segmentadas em linha para conjuntos de 3 a 5 opções (Issue #100).
 *
 * Utilizado nas Configurações para Fov/Tamanho do Painel, Distância, Política de Retomada,
 * Upscaling e Áudio Espacial.
 */
data class SegmentOption<T>(
    val value: T,
    val label: String,
)

class VoidSegmentedSelector<T>(
    context: Context,
    private val options: List<SegmentOption<T>>,
    initialSelectedValue: T,
    private val onOptionSelected: (T) -> Unit,
) : LinearLayout(context) {
    private var selectedValue: T = initialSelectedValue
    private val segmentViews = mutableListOf<TextView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams =
            LayoutParams(
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
            VoidTheme.dpToPx(context, 4f),
            VoidTheme.dpToPx(context, 4f),
            VoidTheme.dpToPx(context, 4f),
            VoidTheme.dpToPx(context, 4f),
        )

        options.forEachIndexed { index, option ->
            val textView =
                TextView(context).apply {
                    text = option.label
                    textSize = 13f
                    typeface = VoidTheme.typefaceBody
                    gravity = Gravity.CENTER
                    setPadding(
                        VoidTheme.dpToPx(context, 8f),
                        VoidTheme.dpToPx(context, 8f),
                        VoidTheme.dpToPx(context, 8f),
                        VoidTheme.dpToPx(context, 8f),
                    )
                    layoutParams =
                        LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (index > 0) marginStart = VoidTheme.dpToPx(context, 4f)
                        }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (selectedValue != option.value) {
                            selectedValue = option.value
                            updateStyles()
                            onOptionSelected(option.value)
                        }
                    }
                }
            segmentViews.add(textView)
            addView(textView)
        }

        updateStyles()
    }

    fun setSelectedValue(value: T) {
        if (selectedValue != value) {
            selectedValue = value
            updateStyles()
        }
    }

    private fun updateStyles() {
        options.forEachIndexed { index, option ->
            val view = segmentViews[index]
            val isSelected = (option.value == selectedValue)

            if (isSelected) {
                view.background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorAccent)
                        cornerRadius = VoidTheme.dp(context, 6f)
                    }
                view.setTextColor(Color.WHITE)
            } else {
                view.background = null
                view.setTextColor(VoidTheme.colorTextSecondary)
            }
        }
    }
}
