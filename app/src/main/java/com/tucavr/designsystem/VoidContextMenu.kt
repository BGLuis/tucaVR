package com.tucavr.designsystem

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.tucavr.R
import com.tucavr.screens.ScreenHost

/**
 * Menu de contexto modal desenhado como View filha do painel VR (adicionada ao FrameLayout raiz).
 * Não utiliza `PopupWindow` nem `ActionMode` nativo (evita criação de múltiplas Surfaces no VirtualDisplay).
 */
class VoidContextMenu(
    context: Context,
    private val host: ScreenHost,
    private val targetField: VoidTextField
) : FrameLayout(context) {

    init {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Backdrop semi-transparente para fechar ao clicar fora
        setBackgroundColor(Color.parseColor("#40000000"))
        isClickable = true
        setOnClickListener {
            dismiss()
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = VoidTheme.dpToPx(context, 12f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(VoidTheme.colorSurfaceAlt)
                cornerRadius = VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
                setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
            }
            isClickable = true // Intercepta clique para não fechar o backdrop
        }

        val currentText = targetField.getText()
        val isPassword = targetField.kind == VoidFieldKind.PASSWORD
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val hasClip = clipboard?.primaryClip?.let { it.itemCount > 0 } ?: false

        // 1. Colar
        if (hasClip) {
            card.addView(createMenuItem(context.getString(R.string.text_action_paste), R.drawable.ic_content_paste) {
                targetField.pasteFromClipboard()
                dismiss()
            })
        }

        // 2. Copiar (desabilitado para campos de senha ou texto vazio)
        if (!isPassword && currentText.isNotEmpty()) {
            card.addView(createMenuItem(context.getString(R.string.text_action_copy), null) {
                targetField.copyToClipboard()
                dismiss()
            })
        }

        // 3. Recortar (desabilitado para campos de senha ou texto vazio)
        if (!isPassword && currentText.isNotEmpty()) {
            card.addView(createMenuItem(context.getString(R.string.text_action_cut), null) {
                targetField.cutToClipboard()
                dismiss()
            })
        }

        // 4. Selecionar tudo
        if (currentText.isNotEmpty()) {
            card.addView(createMenuItem(context.getString(R.string.text_action_select_all), null) {
                targetField.selectAll()
                dismiss()
            })
        }

        // 5. Limpar
        if (currentText.isNotEmpty()) {
            card.addView(createMenuItem(context.getString(R.string.text_action_clear), R.drawable.icon_x) {
                targetField.clear()
                dismiss()
            })
        }

        val cardParams = LayoutParams(
            VoidTheme.dpToPx(context, 260f),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        addView(card, cardParams)
    }

    private fun createMenuItem(title: String, iconResId: Int?, onClick: () -> Unit): View {
        return VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = title
            if (iconResId != null) {
                setIcon(iconResId)
            }
            textSize = 15f
            val padH = VoidTheme.dpToPx(context, 16f)
            val padV = VoidTheme.dpToPx(context, 12f)
            setPadding(padH, padV, padH, padV)
            minHeight = VoidTheme.dpToPx(context, 54f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 6f)
            }
            setOnClickListener { onClick() }
        }
    }

    fun dismiss() {
        host.hideOverlay(this)
    }
}
