package com.tucavr.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tucavr.R

/**
 * Caixa de diálogo modal para confirmação de exclusão de servidor salvo.
 * Exibida no 3º Quad frontal flutuante (VRModalPresentation) a 1,35 m à frente do usuário.
 */
class ConfirmDeleteModal(
    context: Context,
    title: String = context.getString(R.string.network_delete_server_title),
    message: String,
    confirmText: String = context.getString(R.string.network_delete_server_confirm),
    cancelText: String = context.getString(R.string.network_delete_server_cancel),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) : FrameLayout(context) {
    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        setOnClickListener { onDismiss() }

        val panelWidth = VoidTheme.dpToPx(context, 540f)
        val panel =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LayoutParams(panelWidth, LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER
                    }
                background =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurface)
                        cornerRadius = VoidTheme.dp(context, 16f)
                        setStroke(VoidTheme.dpToPx(context, VoidTheme.borderWidthDp), VoidTheme.colorBorder)
                    }
                val pad = VoidTheme.dpToPx(context, 28f)
                setPadding(pad, pad, pad, pad)
                isClickable = true
                setOnClickListener { /* Consumir clique dentro do card */ }
            }

        // Header: Título + Botão Fechar
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = VoidTheme.dpToPx(context, 16f)
                    }
            }

        val titleView =
            TextView(context).apply {
                text = title
                typeface = VoidTheme.typefaceTitle
                textSize = 22f
                setTextColor(VoidTheme.colorText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        header.addView(titleView)

        val closeBtn =
            VoidIconButton(
                context,
                R.drawable.icon_x,
                VoidButtonStyle.SECONDARY,
                isCircular = true,
                isTransparent = true,
            ).apply {
                val s = VoidTheme.dpToPx(context, 48f)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setOnClickListener { onDismiss() }
            }
        header.addView(closeBtn)
        panel.addView(header)

        // Mensagem de confirmação
        val messageView =
            VoidText.body(context, message, sizeSp = 17f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 24f))
            }
        panel.addView(messageView)

        // Botões de Ação
        val buttonRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }

        val btnCancel =
            VoidButton(context, VoidButtonStyle.SECONDARY).apply {
                text = cancelText
                textSize = 16f
                setOnClickListener { onDismiss() }
            }
        buttonRow.addView(
            btnCancel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = VoidTheme.dpToPx(context, 12f)
            },
        )

        val btnConfirm =
            VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                text = confirmText
                textSize = 16f
                setOnClickListener {
                    onConfirm()
                }
            }
        buttonRow.addView(
            btnConfirm,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        panel.addView(buttonRow)
        addView(panel)
    }
}
