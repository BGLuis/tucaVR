package com.vrplayer.navigation

import com.vrplayer.network.SmbServer

/**
 * Fase 1+2 do redesign "Void" (ver docs/phases): consolida o que originalmente
 * eram 3 paineis 3D desconectados (VRPresentation / NetworkPresentation /
 * VRControlsPresentation, cada um seu proprio quad OES em
 * `native/src/vr_player_app.cpp`) num unico fluxo de navegacao logico, todo
 * hospedado no mesmo quad (`VRPresentation`):
 *
 *   Home -> (LocalFiles | NetworkHome -> NetworkFiles | ContinueWatching) -> Player
 *
 * O painel de Controles (`VRControlsPresentation`) continua sendo seu proprio
 * quad — ele e contextual (aparece ao apontar pra tela de video durante a
 * reproducao) e nao fazia parte da duplicacao Local/Rede que motivou esta
 * consolidacao.
 *
 * Este arquivo e [AppNavigator] sao Kotlin puro (sem `Context`/`View`/nenhum
 * import `android.*`) de proposito: precisam ser exercitaveis com JUnit puro
 * na JVM, sem Robolectric nem um device/emulador. A UNICA dependencia
 * "de fora" e [SmbServer], que e um data class puro (sem framework Android
 * em si — so a classe que o persiste, `SmbCredentialStore`, usa Android).
 *
 * O botao fisico Menu (esquerdo), que antes abria/fechava o quad de rede
 * dedicado, agora tambem alterna a visibilidade deste quad unico (mesmo
 * efeito que B/Y) — ver decisao documentada em `vr_player_app.cpp`
 * (`Update()`, tratamento de `kButtonMenu`). Quem efetivamente conduz esta
 * maquina de estados (Home/Rede/Arquivos/Player) e a UI Void dentro de
 * `VRPresentation` (botao "Voltar" apontado/clicado), nao os botoes fisicos.
 */
sealed class Destination {

    /** Tela inicial: "Arquivos locais" | "Rede" | "Continuar assistindo". */
    object Home : Destination()

    /** T9.4: lista do historico de reproducao (`PlaybackHistory`, mais recentes primeiro). */
    object ContinueWatching : Destination()

    /** Listagem de arquivos locais na pasta [path] (path absoluto). */
    data class LocalFiles(val path: String) : Destination()

    /**
     * Landing da secao "Rede" (abas URL / SMB), renderizada dentro do mesmo
     * quad do Home (ver `VRPresentation.renderNetworkHome()`). Reaproveita a
     * logica de estado que antes vivia em `NetworkPresentation` (removida):
     * `SmbCredentialStore`, `UrlHistoryStore`, probe HTTP, etc.
     */
    object NetworkHome : Destination()

    /** Listagem de um diretorio dentro de um compartilhamento SMB ja conectado. */
    data class NetworkFiles(val server: SmbServer, val path: String) : Destination()

    /** Reproduzindo [source]. */
    data class Player(val source: PlaybackSource) : Destination()
}

/**
 * De onde vem a midia que esta tocando — usado por [Destination.Player].
 *
 * [LocalFile.sizeBytes]/[Smb.sizeBytes] (T9, ver `com.vrplayer.history`):
 * tamanho do arquivo em bytes, `0L` quando desconhecido no ponto de
 * chamada (default, compativel com todo o codigo pre-existente que
 * construia estas classes sem esse argumento). Usado para compor a chave
 * estavel do historico de reproducao — ver aviso do doc, secao 9
 * ("URIs de SMB podem mudar se o IP do servidor mudar"): a chave NAO usa
 * host/porta (que podem mudar), so `server.name` (rotulo escolhido pelo
 * usuario) + `share` + `path` (que ja inclui o nome do arquivo, ultimo
 * segmento) + `sizeBytes`.
 */
sealed class PlaybackSource {
    data class LocalFile(val path: String, val sizeBytes: Long = 0L) : PlaybackSource()
    data class Http(val url: String) : PlaybackSource()
    data class Smb(val server: SmbServer, val path: String, val sizeBytes: Long = 0L) : PlaybackSource()
}
