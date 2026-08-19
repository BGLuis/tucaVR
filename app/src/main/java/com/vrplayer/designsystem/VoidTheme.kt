package com.vrplayer.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue

/**
 * Identidade visual "Void" (aprovada pelo dono do produto).
 */
object VoidTheme {

    // ---------- Cores ----------
    val colorBackground = Color.parseColor("#121212")
    val colorSurface = Color.parseColor("#1A1A1A")
    val colorSurfaceAlt = Color.parseColor("#2C2C2C")
    val colorText = Color.parseColor("#FFFFFF")
    val colorTextSecondary = Color.parseColor("#B3B3B3")
    val colorAccent = Color.parseColor("#FF6B00")
    val colorBorder = Color.parseColor("#3A2F1E")

    fun accentWithAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(colorAccent), Color.green(colorAccent), Color.blue(colorAccent))

    // ---------- Tipografia ----------
    lateinit var typefaceTitle: Typeface
    lateinit var typefaceBody: Typeface
    lateinit var typefaceMono: Typeface

    fun init(context: Context) {
        typefaceTitle = Typeface.create("serif", Typeface.NORMAL)
        // Usando as fontes Rubik e Fira Code
        typefaceBody = Typeface.createFromAsset(context.assets, "fonts/Rubik-Regular.ttf")
        typefaceMono = Typeface.createFromAsset(context.assets, "fonts/FiraCode-Regular.ttf")
    }

    // ---------- Formas ----------
    const val cornerRadiusDp = 12f
    const val borderWidthDp = 1.5f

    fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    fun dpToPx(context: Context, value: Float): Int = dp(context, value).toInt()
}
