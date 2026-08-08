package com.vrplayer.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue

/**
 * Identidade visual "Void" (aprovada pelo dono do produto). O nome vem do
 * `VoidEnvironment` ja existente (ver docs/phases/PHASE-0.1-MVP.md, T3.3):
 * o ambiente 3D e propositalmente todo preto por conforto visual em VR;
 * aqui a mesma decisao vira paleta pros paineis 2D internos, em vez do
 * cinza padrao do Android que os widgets crus usavam antes.
 *
 * Objeto central de tema: TODAS as cores/fontes dos componentes `Void*`
 * devem vir daqui — nunca `Color.parseColor(...)` espalhado pelos paineis.
 */
object VoidTheme {

    // ---------- Cores ----------
    val colorBackground = Color.parseColor("#141009")
    val colorSurface = Color.parseColor("#1E1810")
    val colorSurfaceAlt = Color.parseColor("#282116")
    val colorText = Color.parseColor("#F4EEE1")
    val colorTextSecondary = Color.parseColor("#B3A791")
    val colorAccent = Color.parseColor("#E8A33D")
    val colorBorder = Color.parseColor("#3A2F1E")

    /** [colorAccent] com transparencia — usado no "glow" sob o sublinhado das tabs. */
    fun accentWithAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(colorAccent), Color.green(colorAccent), Color.blue(colorAccent))

    // ---------- Tipografia ----------
    // Android nao tem "font-stack" CSS; usamos a familia generica mais proxima
    // disponivel na plataforma e documentamos aqui o stack CSS aprovado, que e
    // a referencia de design original:
    //   titulo:  "Iowan Old Style", "Palatino Linotype", Georgia, serif
    //   corpo:   -apple-system, "Segoe UI", Roboto, sans-serif
    //   mono:    ui-monospace, "SF Mono", "Roboto Mono", monospace
    val typefaceTitle: Typeface = Typeface.create("serif", Typeface.NORMAL)
    val typefaceBody: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    val typefaceMono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)

    // ---------- Formas ----------
    const val cornerRadiusDp = 12f
    const val borderWidthDp = 1.5f

    fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    fun dpToPx(context: Context, value: Float): Int = dp(context, value).toInt()
}
