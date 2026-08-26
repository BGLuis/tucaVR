package com.tucavr.screens

import android.content.Context
import com.tucavr.VRActivity
import com.tucavr.history.isResumable
import com.tucavr.navigation.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Orquestrador do prompt de retomada "Retomar de XX:XX?".
 *
 * Desacoplado da navegação de tela do painel esquerdo: exibe o diálogo
 * no 3º Quad frontal flutuante (VRModalPresentation) via [VRActivity.openResumePromptModal].
 * Mantém a lista do navegador de arquivos visível e intacta no fundo.
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
     * o modal frontal "Retomar de XX:XX?"; caso contrário chama [onDecided] diretamente
     * com `null` (começa do zero).
     */
    fun promptOrPlay(source: PlaybackSource, onDecided: (resumeAtMs: Long?) -> Unit) {
        scope.launch {
            val existing = activity.historyTracker.findExisting(source)
            if (existing != null && existing.isResumable()) {
                activity.openResumePromptModal(
                    entry = existing,
                    onResume = { onDecided(existing.positionMs) },
                    onRestart = { onDecided(null) }
                )
            } else {
                onDecided(null)
            }
        }
    }
}
