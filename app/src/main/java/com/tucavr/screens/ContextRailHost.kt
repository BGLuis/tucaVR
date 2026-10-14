package com.tucavr.screens

import android.view.View
import com.tucavr.filebrowser.MediaEntry
import com.tucavr.navigation.PlaybackSource

/**
 * Contrato exposto pelo shell de UI VR ([com.tucavr.VRPresentation]) para permitir que
 * telas e componentes alimentem ou customizem a zona de contexto (rail direito, 432 px).
 */
interface ContextRailHost {
    /**
     * Define uma view personalizada para ser exibida na zona de contexto.
     */
    fun setContextContent(view: View)

    /**
     * Restaura a visualização padrão da zona de contexto (Now Playing / status padrão).
     */
    fun resetContextContent()

    /**
     * Exibe os detalhes e opções de reprodução de um arquivo no trilho de contexto.
     */
    fun showFileDetail(
        entry: MediaEntry,
        source: PlaybackSource,
        onPlay: (resumeAtMs: Long?) -> Unit,
    )

    /**
     * Limpa os detalhes do arquivo selecionado e restaura a aba Now Playing / status padrão.
     */
    fun clearFileDetail()
}
