package com.vrplayer.designsystem

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.Button

/**
 * Estilo visual de um [VoidButton]:
 *  - [PRIMARY]: acao principal ("Arquivos locais", "Rede", "Tocar") — borda
 *    acento (a "luz do projetor") pra chamar atencao mesmo sem estar focado.
 *  - [SECONDARY]: acao auxiliar (voltar, colar, testar) — borda neutra.
 *  - [ACTIVE]: estado selecionado/foco explicito — fundo acento.
 *  - [DISABLED]: placeholder inativo para uma funcionalidade que ainda nao
 *    existe (ex.: era usado por "Continuar assistindo" antes do T9 — ver
 *    `com.vrplayer.history` — implementar o historico; hoje esse botao ja
 *    usa PRIMARY) — visivel porem apagado, em vez de escondido, pra
 *    sinalizar que a funcionalidade existe/vira.
 */
enum class VoidButtonStyle { PRIMARY, SECONDARY, ACTIVE, DISABLED }

/**
 * Botao padrao do design system Void: cantos arredondados, paleta/tipografia
 * central em [VoidTheme] em vez de cores mago espalhadas pelos paineis.
 * Construido em codigo (mesmo padrao do resto do projeto — sem XML/layout).
 */
class VoidButton @JvmOverloads constructor(
    context: Context,
    style: VoidButtonStyle = VoidButtonStyle.PRIMARY
) : Button(context) {

    var style: VoidButtonStyle = style
        set(value) {
            field = value
            applyStyle()
        }

    init {
        isAllCaps = false
        typeface = VoidTheme.typefaceBody
        val paddingH = VoidTheme.dpToPx(context, 24f)
        val paddingV = VoidTheme.dpToPx(context, 16f)
        setPadding(paddingH, paddingV, paddingH, paddingV)
        minHeight = VoidTheme.dpToPx(context, 56f)
        applyStyle()
    }

    private fun applyStyle() {
        val bgColor: Int
        val borderColor: Int
        val textColor: Int
        when (style) {
            VoidButtonStyle.PRIMARY -> {
                bgColor = VoidTheme.colorSurfaceAlt
                borderColor = VoidTheme.colorAccent
                textColor = VoidTheme.colorText
            }
            VoidButtonStyle.SECONDARY -> {
                bgColor = VoidTheme.colorSurface
                borderColor = VoidTheme.colorBorder
                textColor = VoidTheme.colorText
            }
            VoidButtonStyle.ACTIVE -> {
                bgColor = VoidTheme.colorAccent
                borderColor = VoidTheme.colorAccent
                textColor = VoidTheme.colorBackground
            }
            VoidButtonStyle.DISABLED -> {
                bgColor = VoidTheme.colorSurface
                borderColor = VoidTheme.colorBorder
                textColor = VoidTheme.colorTextSecondary
            }
        }
        background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
            setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), borderColor)
        }
        setTextColor(textColor)
        isEnabled = style != VoidButtonStyle.DISABLED
        alpha = if (style == VoidButtonStyle.DISABLED) 0.55f else 1f
    }
}
