package com.tucavr.screens

import android.content.Context
import com.tucavr.VRActivity
import com.tucavr.history.PlaybackHistory
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
class ResumePromptScreen internal constructor(
    private val findHistory: suspend (PlaybackSource) -> PlaybackHistory?,
    private val showModal: (entry: PlaybackHistory, onResume: () -> Unit, onRestart: () -> Unit) -> Unit,
    private val scope: CoroutineScope
) {
    @Suppress("UNUSED_PARAMETER")
    constructor(
        context: Context,
        activity: VRActivity,
        host: ScreenHost,
        scope: CoroutineScope,
        onBack: () -> Unit
    ) : this(
        findHistory = { source -> activity.historyTracker.findExisting(source) },
        showModal = { entry, onResume, onRestart -> activity.openResumePromptModal(entry, onResume, onRestart) },
        scope = scope
    )

    /**
     * Consulta o histórico e, se houver entrada retomável para [source], exibe
     * o modal frontal "Retomar de XX:XX?"; caso contrário chama [onDecided] diretamente
     * com `null` (começa do zero).
     */
    fun promptOrPlay(source: PlaybackSource, onDecided: (resumeAtMs: Long?) -> Unit) {
        scope.launch {
            val existing = findHistory(source)
            if (existing != null && existing.isResumable()) {
                showModal(
                    existing,
                    { onDecided(existing.positionMs) },
                    { onDecided(null) }
                )
            } else {
                onDecided(null)
            }
        }
    }
}
