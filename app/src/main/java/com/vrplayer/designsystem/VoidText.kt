package com.vrplayer.designsystem

import android.content.Context
import android.widget.TextView

/**
 * Fabricas de `TextView` com a tipografia/paleta Void ja aplicada, pra nao
 * repetir `typeface = ...; setTextColor(...)` em cada painel.
 */
object VoidText {

    /** Titulo de tela — serifada, cor de texto primaria. */
    fun title(context: Context, text: String, sizeSp: Float = 30f): TextView =
        TextView(context).apply {
            this.text = text
            typeface = VoidTheme.typefaceTitle
            setTextColor(VoidTheme.colorText)
            textSize = sizeSp
        }

    /** Texto de corpo — sans-serif do sistema, cor primaria ou secundaria. */
    fun body(context: Context, text: String, sizeSp: Float = 20f, secondary: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            typeface = VoidTheme.typefaceBody
            setTextColor(if (secondary) VoidTheme.colorTextSecondary else VoidTheme.colorText)
            textSize = sizeSp
        }

    /** Rotulos/dados tecnicos (caminhos, enderecos, contadores) — monoespacada. */
    fun mono(context: Context, text: String, sizeSp: Float = 16f, secondary: Boolean = true): TextView =
        TextView(context).apply {
            this.text = text
            typeface = VoidTheme.typefaceMono
            setTextColor(if (secondary) VoidTheme.colorTextSecondary else VoidTheme.colorText)
            textSize = sizeSp
        }
}
