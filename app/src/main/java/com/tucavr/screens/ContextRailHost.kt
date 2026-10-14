package com.tucavr.screens

import android.view.View

/**
 * Contrato exposto pelo shell de UI VR ([com.tucavr.VRPresentation]) para permitir que
 * telas e componentes futuros alimentem ou customizem a zona de contexto (rail direito, 432 px).
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
}
