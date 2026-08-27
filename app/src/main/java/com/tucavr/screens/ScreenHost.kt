package com.tucavr.screens

import android.view.View
import com.tucavr.designsystem.KeyboardBinding

/**
 * Contrato que [VRPresentation] implementa e injeta nas screens.
 *
 * Separa o que cada tela precisa saber sobre o host (mostrar uma view,
 * gerenciar teclado e exibir overlays) do restante da lógica de navegação.
 */
interface ScreenHost {
    /** Substitui a view atual pelo conteúdo da tela. */
    fun showScreen(view: View)

    /** Abre o teclado nativo do sistema para o [binding] informado. */
    fun showNativeKeyboard(binding: KeyboardBinding)

    /** Fecha o teclado nativo do sistema. */
    fun hideNativeKeyboard()

    /** Sincroniza o texto do proxy de teclado na Activity caso o [binding] esteja ativo. */
    fun syncKeyboard(binding: KeyboardBinding)

    /** Adiciona uma view de sobreposição (ex.: menu de contexto) ao contêiner raiz do painel VR. */
    fun showOverlay(view: View)

    /** Remove uma view de sobreposição do contêiner raiz do painel VR. */
    fun hideOverlay(view: View)
}
