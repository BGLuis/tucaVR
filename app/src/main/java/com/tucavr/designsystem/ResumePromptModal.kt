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
import com.tucavr.history.PlaybackHistory
import com.tucavr.history.formatDurationMs
import com.tucavr.history.watchedPercent

/**
 * Modal flutuante para confirmação de retomada de vídeo ("Retomar de XX:XX?").
 *
 * Exibido no 3º Quad frontal flutuante (VRModalPresentation) a 1,35 m à frente do usuário.
 * Mantém o navegador de arquivos intacto ao fundo enquanto solicita a decisão do usuário.
 */
class ResumePromptModal(
    context: Context,
    private val entry: PlaybackHistory,
    private val onResume: () -> Unit,
    private val onRestart: () -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        setOnClickListener { onDismiss() }

        val panelWidth = VoidTheme.dpToPx(context, 600f)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(panelWidth, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
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
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = VoidTheme.dpToPx(context, 16f)
            }
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.history_resume_prompt_title)
            typeface = VoidTheme.typefaceTitle
            textSize = 22f
            setTextColor(VoidTheme.colorText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        val closeBtn = VoidIconButton(
            context,
            R.drawable.icon_x,
            VoidButtonStyle.SECONDARY,
            isCircular = true,
            isTransparent = true
        ).apply {
            val s = VoidTheme.dpToPx(context, 48f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { onDismiss() }
        }
        header.addView(closeBtn)
        panel.addView(header)

        // Nome do vídeo
        val videoTitle = VoidText.body(context, entry.title, sizeSp = 18f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
        }
        panel.addView(videoTitle)

        // Estatística de progresso assistido
        val watchedInfo = VoidText.mono(
            context,
            context.getString(
                R.string.history_resume_prompt_watched_format,
                formatDurationMs(entry.positionMs),
                formatDurationMs(entry.durationMs),
                watchedPercent(entry)
            ),
            sizeSp = 15f
        ).apply {
            setTextColor(VoidTheme.colorTextSecondary)
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 24f))
        }
        panel.addView(watchedInfo)

        val btnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = VoidTheme.dpToPx(context, 10f)
        }

        // Botão 1: Retomar
        val btnResume = VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(
                R.string.history_resume_prompt_btn_resume_format,
                formatDurationMs(entry.positionMs)
            )
            textSize = 18f
            setOnClickListener {
                onDismiss()
                onResume()
            }
        }
        panel.addView(btnResume, btnParams)

        // Botão 2: Começar do Início
        val btnRestart = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.history_resume_prompt_btn_restart)
            textSize = 18f
            setOnClickListener {
                onDismiss()
                onRestart()
            }
        }
        panel.addView(btnRestart, btnParams)

        // Botão 3: Cancelar
        val btnCancel = VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.history_resume_prompt_btn_cancel)
            textSize = 16f
            setOnClickListener { onDismiss() }
        }
        panel.addView(btnCancel, btnParams)

        addView(panel)
    }
}
