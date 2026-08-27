package com.tucavr.designsystem

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.widget.Button

/**
 * Estilo visual de um [VoidButton]:
 *  - [PRIMARY]: acao principal ("Arquivos locais", "Rede", "Tocar") — borda
 *    acento (a "luz do projetor") pra chamar atencao mesmo sem estar focado.
 *  - [SECONDARY]: acao auxiliar (voltar, colar, testar) — borda neutra.
 *  - [ACTIVE]: estado selecionado/foco explicito — fundo acento.
 *  - [DISABLED]: placeholder inativo para uma funcionalidade que ainda nao
 *    existe (ex.: era usado por "Continuar assistindo" antes do T9 — ver
 *    `com.tucavr.history` — implementar o historico; hoje esse botao ja
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

    private var currentIconResId: Int = 0

    init {
        isAllCaps = false
        typeface = VoidTheme.typefaceBody
        // Alvo de toque generoso de proposito (feedback de usuario em
        // validacao real: hitbox pequena demais pra apontar com precisao via
        // raycast — o tremor natural da mao a distancia torna mirar em algo
        // pequeno bem mais dificil em VR do que em touch mobile/desktop, ver
        // tambem o cursor/reticle em vr_player_app.cpp). O hit-test do
        // toque sintetico (dispatchVRTouch -> MotionEvent -> Android touch
        // dispatch) usa os bounds REAIS da View, entao aumentar
        // padding/minHeight aqui aumenta o alvo clicavel junto com o visual,
        // sem precisar de nenhum mecanismo separado de "hitbox".
        val paddingH = VoidTheme.dpToPx(context, 28f)
        val paddingV = VoidTheme.dpToPx(context, 22f)
        setPadding(paddingH, paddingV, paddingH, paddingV)
        minHeight = VoidTheme.dpToPx(context, 76f)
        compoundDrawablePadding = VoidTheme.dpToPx(context, 12f)
        applyStyle()
    }

    fun setIcon(resId: Int) {
        this.currentIconResId = resId
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
        
        if (currentIconResId != 0) {
            val icon = context.getDrawable(currentIconResId)?.mutate()
            icon?.colorFilter = PorterDuffColorFilter(textColor, PorterDuff.Mode.SRC_IN)
            if (icon != null) {
                val size = VoidTheme.dpToPx(context, 24f)
                icon.setBounds(0, 0, size, size)
                setCompoundDrawables(icon, null, null, null)
            }
        } else {
            setCompoundDrawables(null, null, null, null)
        }

        isEnabled = style != VoidButtonStyle.DISABLED
        alpha = if (style == VoidButtonStyle.DISABLED) 0.55f else 1f
    }
}
