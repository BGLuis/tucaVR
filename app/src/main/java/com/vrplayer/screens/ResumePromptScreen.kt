package com.vrplayer.screens

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vrplayer.R
import com.vrplayer.VRActivity
import com.vrplayer.designsystem.VoidButton
import com.vrplayer.designsystem.VoidButtonStyle
import com.vrplayer.designsystem.VoidPanelChrome
import com.vrplayer.designsystem.VoidText
import com.vrplayer.designsystem.VoidTheme
import com.vrplayer.history.PlaybackHistory
import com.vrplayer.history.formatDurationMs
import com.vrplayer.history.isResumable
import com.vrplayer.history.watchedPercent
import com.vrplayer.navigation.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Prompt transitório "Retomar de XX:XX?" (T9.3).
 *
 * Desenhado com [ScreenHost.showScreen] diretamente (NÃO via AppNavigator)
 * de propósito: é uma decisão pontual, não um novo nível de navegação "de
 * verdade". O back-stack do AppNavigator permanece inalterado — "Voltar"
 * aqui re-renderiza a tela de onde o usuário veio via [onBack], e depois de
 * escolher Retomar/Começar do zero o fluxo normal de navigateTo(Player)
 * empilha a partir dessa mesma tela, como se o prompt nunca tivesse existido.
 */
class ResumePromptScreen(
    private val context: Context,
    private val activity: VRActivity,
    private val host: ScreenHost,
    private val scope: CoroutineScope,
    private val onBack: () -> Unit
) {

    /**
     * Consulta o histórico e, se houver entrada retomável para [source], exibe
     * o prompt "Retomar de XX:XX?"; caso contrário chama [onDecided] diretamente
     * com `null` (começa do zero).
     *
     * [onDecided] recebe `resumeAtMs` ou `null` e é responsável por chamar
     * `activity.playX(...)` e navegar para Player — esta função nunca toca
     * mídia diretamente.
     */
    fun promptOrPlay(source: PlaybackSource, onDecided: (resumeAtMs: Long?) -> Unit) {
        scope.launch {
            val existing = activity.historyTracker.findExisting(source)
            if (existing != null && existing.isResumable()) {
                showResumePrompt(
                    entry     = existing,
                    onResume  = { onDecided(existing.positionMs) },
                    onRestart = { onDecided(null) }
                )
            } else {
                onDecided(null)
            }
        }
    }

    private fun showResumePrompt(
        entry: PlaybackHistory,
        onResume: () -> Unit,
        onRestart: () -> Unit
    ) {
        val root = VoidPanelChrome.newRoot(context)
        root.addView(
            VoidPanelChrome.buildHeader(
                context,
                title = context.getString(R.string.history_resume_prompt_title),
                onBack = { onBack() }
            )
        )
        root.addView(VoidText.body(context, entry.title, sizeSp = 20f).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
        })
        root.addView(VoidText.mono(
            context,
            context.getString(
                R.string.history_resume_prompt_watched_format,
                formatDurationMs(entry.positionMs),
                formatDurationMs(entry.durationMs),
                watchedPercent(entry)
            ),
            sizeSp = 16f
        ).apply {
            setPadding(0, 0, 0, VoidTheme.dpToPx(context, 24f))
        })

        val btnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = VoidTheme.dpToPx(context, 12f) }

        root.addView(VoidButton(context, VoidButtonStyle.PRIMARY).apply {
            text = context.getString(R.string.history_resume_prompt_btn_resume_format, formatDurationMs(entry.positionMs))
            textSize = 20f
            setOnClickListener { onResume() }
        }, btnParams)

        root.addView(VoidButton(context, VoidButtonStyle.SECONDARY).apply {
            text = context.getString(R.string.history_resume_prompt_btn_restart)
            textSize = 18f
            setOnClickListener { onRestart() }
        }, btnParams)

        host.showScreen(root)
    }
}
