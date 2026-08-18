package com.vrplayer.screens

import android.content.ClipboardManager
import android.content.Context
import android.widget.EditText
import com.vrplayer.VRActivity
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidTheme

/**
 * Helpers de UI compartilhados entre todas as screens.
 *
 * Centraliza padrões repetitivos que antes estavam espalhados (ou
 * duplicados) no VRPresentation original: criação de EditText estilizado e
 * do botão "Colar" que contorna a limitação de long-press em VirtualDisplay.
 */
object UiHelpers {

    /**
     * Cria um [EditText] com o visual Void e amarra o foco ao teclado nativo
     * via [host]. Único ponto de construção de campos de texto no painel —
     * ver seção "TECLADO NATIVO" em VRPresentation para o raciocínio completo.
     */
    fun buildVoidEditText(context: Context, hint: String, host: ScreenHost): EditText =
        EditText(context).apply {
            this.hint = hint
            setHintTextColor(VoidTheme.colorTextSecondary)
            setTextColor(VoidTheme.colorText)
            typeface = VoidTheme.typefaceBody
            textSize = 18f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(VoidTheme.colorSurface)
                cornerRadius = VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
                setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
            }
            // Hitbox generosa — feedback de validação real em VR.
            val padH = VoidTheme.dpToPx(context, 16f)
            val padV = VoidTheme.dpToPx(context, 20f)
            setPadding(padH, padV, padH, padV)
            minimumHeight = VoidTheme.dpToPx(context, 76f)

            // Teclado nativo: VirtualDisplay não recebe IME do sistema
            // (restrição AOSP de segurança). A solução usa um EditText proxy
            // real na Activity — ver VRActivity.showNativeKeyboardFor().
            setOnFocusChangeListener { view, hasFocus ->
                val editText = view as EditText
                if (hasFocus) {
                    host.showNativeKeyboard(editText)
                } else {
                    host.hideNativeKeyboard()
                }
            }
        }

    /**
     * Botão "Colar" explícito que lê o ClipboardManager diretamente.
     *
     * O menu de long-press nativo nunca funciona em VirtualDisplay (a janela
     * popup fica fora do alcance do toque sintético do controller). Por isso
     * todo EditText do painel precisa deste botão como alternativa.
     */
    fun buildPasteButton(context: Context, activity: VRActivity, field: EditText): VoidButton =
        VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(com.vrplayer.R.string.network_url_btn_paste)
            textSize = 16f
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).coerceToText(activity).toString()
                    field.setText(text)
                    field.setSelection(text.length)
                }
            }
        }
}
