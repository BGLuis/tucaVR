package com.vrplayer.screens

import android.view.View
import android.widget.EditText

/**
 * Contrato que [VRPresentation] implementa e injeta nas screens.
 *
 * Separa o que cada tela precisa saber sobre o host (mostrar uma view,
 * gerenciar teclado) do restante da lógica de navegação — as screens não
 * conhecem [VRPresentation] diretamente, só este contrato mínimo.
 */
interface ScreenHost {
    /** Substitui a view atual pelo conteúdo da tela. */
    fun showScreen(view: View)
    /** Abre o teclado nativo do sistema para o campo informado. */
    fun showNativeKeyboard(field: EditText)
    /** Fecha o teclado nativo do sistema. */
    fun hideNativeKeyboard()
}

