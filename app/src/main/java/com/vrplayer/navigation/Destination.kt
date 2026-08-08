package com.vrplayer.navigation

import com.vrplayer.network.SmbServer

/**
 * Fase 1 do redesign "Void" (ver docs/phases): consolida o que hoje sao 3
 * paineis 3D desconectados (VRPresentation / NetworkPresentation /
 * VRControlsPresentation, cada um seu proprio quad OES em
 * `native/src/vr_player_app.cpp`) num unico fluxo de navegacao logico:
 *
 *   Home -> (LocalFiles | NetworkHome -> NetworkFiles) -> Player
 *
 * Este arquivo e [AppNavigator] sao Kotlin puro (sem `Context`/`View`/nenhum
 * import `android.*`) de proposito: precisam ser exercitaveis com JUnit puro
 * na JVM, sem Robolectric nem um device/emulador. A UNICA dependencia
 * "de fora" e [SmbServer], que e um data class puro (sem framework Android
 * em si — so a classe que o persiste, `SmbCredentialStore`, usa Android).
 *
 * Fase 2 (fora de escopo aqui, exige teste em headset fisico) vai consolidar
 * os 3 quads nativos em 1 e remapear os botoes fisicos B/Y para chamar
 * `AppNavigator.back()`/`backToHome()` diretamente. Por enquanto, quem
 * conduz esta maquina de estados e a UI Void dentro de `VRPresentation`
 * (botao "Voltar" apontado/clicado, nao o botao fisico).
 */
sealed class Destination {

    /** Tela inicial: "Arquivos locais" | "Rede" | (futuro) "Continuar assistindo". */
    object Home : Destination()

    /** Listagem de arquivos locais na pasta [path] (path absoluto). */
    data class LocalFiles(val path: String) : Destination()

    /**
     * Landing da secao "Rede" (abas URL / SMB). Nesta fase a UI real de
     * rede continua vivendo em `NetworkPresentation` (seu proprio quad,
     * aberto pelo botao Menu) — ver TODO em `VRPresentation.renderNetworkHome()`
     * para a justificativa de nao duplicar aquela logica aqui ainda.
     */
    object NetworkHome : Destination()

    /** Listagem de um diretorio dentro de um compartilhamento SMB ja conectado. */
    data class NetworkFiles(val server: SmbServer, val path: String) : Destination()

    /** Reproduzindo [source]. */
    data class Player(val source: PlaybackSource) : Destination()
}

/** De onde vem a midia que esta tocando — usado por [Destination.Player]. */
sealed class PlaybackSource {
    data class LocalFile(val path: String) : PlaybackSource()
    data class Http(val url: String) : PlaybackSource()
    data class Smb(val server: SmbServer, val path: String) : PlaybackSource()
}
