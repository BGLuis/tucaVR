package com.tucavr.navigation

/**
 * Back-stack de navegacao do app, desacoplado de Android de proposito (sem
 * `Context`/`View`/`Activity` — so estado puro em cima de [Destination]).
 * Isso permite testar toda a logica de "para onde o botao Voltar leva" com
 * JUnit comum, sem Robolectric nem device.
 *
 * Semantica:
 *  - [navigateTo] empilha o destino atual e torna [destination] o atual
 *    (navegacao "para frente", ex: Home -> LocalFiles).
 *  - [back] desempilha um nivel; retorna `false` (e nao faz nada) se ja
 *    estamos no destino inicial ([home]) e nao ha pra onde voltar.
 *  - [backToHome] limpa a pilha inteira e volta direto pro destino inicial —
 *    usado por atalhos tipo "fechar o painel inteiro", nao por um unico
 *    toque de "voltar".
 *
 * `current` comeca em [home] e nunca fica fora da pilha: representa "onde
 * estou agora", enquanto a pilha privada guarda so o historico por tras dele.
 */
class AppNavigator(private val home: Destination = Destination.Home) {

    private val backStack = ArrayDeque<Destination>()

    var current: Destination = home
        private set

    /** Se `false`, [current] ja e [home] e não há nível anterior. */
    val canGoBack: Boolean
        get() = backStack.isNotEmpty()

    fun navigateTo(destination: Destination) {
        backStack.addLast(current)
        current = destination
    }

    /** Volta um nivel. Retorna `true` se moveu, `false` se ja estava em [home]. */
    fun back(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        current = previous
        return true
    }

    fun backToHome() {
        backStack.clear()
        current = home
    }
}
