package com.tucavr.designsystem

/**
 * Contrato de comunicação entre componentes de entrada de texto no painel VR
 * e o proxy de teclado nativo gerenciado por `VRActivity`.
 *
 * Como o Android não despacha eventos de IME do sistema diretamente para janelas
 * secundárias de `Presentation` hospedadas em `VirtualDisplay`, a `VRActivity`
 * mantém um proxy de teclado na sua janela principal. Este contrato abstrai a
 * sincronização de texto, seleção, tipo de entrada e ações IME de ida e volta.
 */
interface KeyboardBinding {
    /** Tipo de entrada Android (ex.: `InputType.TYPE_CLASS_TEXT`, `TYPE_CLASS_NUMBER`, etc.). */
    val inputType: Int

    /** Opções de IME (ex.: `EditorInfo.IME_ACTION_NEXT`, `IME_ACTION_DONE`). */
    val imeOptions: Int

    /** Retorna o texto atual do campo. */
    fun currentText(): CharSequence

    /**
     * Chamado pelo proxy de teclado nativo quando o usuário digita ou altera o cursor no teclado do sistema.
     * Atualiza o campo com [text] e posiciona o cursor em [selection].
     */
    fun onKeyboardText(text: CharSequence, selection: Int)

    /**
     * Chamado quando o usuário aciona uma ação do editor (Next, Done, etc.) no teclado nativo.
     */
    fun onImeAction(actionId: Int)
}
