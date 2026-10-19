package com.tucavr.navigation

import com.tucavr.network.FtpServer
import com.tucavr.network.SavedServer
import com.tucavr.network.SftpServer
import com.tucavr.network.SmbServer

/**
 * Fase 1+2 do redesign "Void" (ver docs/phases): consolida o fluxo de navegacao
 * num unico canvas cilindrico de 1920x960 (`VRPresentation`), organizado em 3 zonas
 * simultaneas (Zona 1: Sidebar de navegacao [288px], Zona 2: Conteudo principal [1200px],
 * Zona 3: Context Rail [432px]):
 *
 *   Home -> (LocalFiles | NetworkHome -> NetworkFiles | ContinueWatching | Settings) -> Direct Playback
 *
 * O painel de Controles (`VRControlsPresentation`) continua sendo seu proprio
 * quad — ele e contextual (aparece ao apontar pra tela de video durante a
 * reproducao) e nao fazia parte da duplicacao Local/Rede que motivou esta
 * consolidacao. Modal e estatisticas compactas vivem no quad frontal flutuante (`VRModalPresentation`).
 *
 * Este arquivo e [AppNavigator] sao Kotlin puro (sem `Context`/`View`/nenhum
 * import `android.*`) de proposito: precisam ser exercitaveis com JUnit puro
 * na JVM, sem Robolectric nem um device/emulador. As UNICAS dependencias
 * "de fora" sao [SmbServer]/[FtpServer]/[SftpServer], data classes puras
 * (sem framework Android em si — so as classes que os persistem,
 * `SmbCredentialStore`/`FtpCredentialStore`/`SftpCredentialStore`, usam
 * Android).
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

    /** T6.4: listagem de um diretorio num servidor FTP ja conectado. */
    data class NetworkFtpFiles(val server: FtpServer, val path: String) : Destination()

    /** T6.4: listagem de um diretorio num servidor SFTP ja conectado. */
    data class NetworkSftpFiles(val server: SftpServer, val path: String) : Destination()

    /** T5.4: listagem de um diretorio num servidor NFS ja conectado. */
    data class NetworkNfsFiles(val server: SavedServer, val path: String) : Destination()

    /** T7.5: listagem de um container num servidor DLNA ja conectado. */
    data class NetworkDlnaFiles(val server: SavedServer, val objectId: String, val folderName: String) : Destination()

    /** T3.1/T3.4: listagem de um diretório num servidor WebDAV já conectado. */
    data class NetworkWebdavFiles(val server: SavedServer, val path: String) : Destination()

    /** @deprecated Now Playing screen foi removida (Epic #81 / Issue #93). Reprodução direta via callbacks. */
    @Deprecated("Now Playing screen foi removida na Issue #93. Use reprodução direta via callbacks.")
    data class Player(val source: PlaybackSource) : Destination()

    /** T9.2: listagem de playlists criadas pelo usuário. */
    object Playlists : Destination()

    /** T9.2: detalhes da playlist [playlistId] com lista de itens e reordenação. */
    data class PlaylistDetail(val playlistId: String) : Destination()

    /** Fase 0.4 T5: toggles de feature flags (Foveated Rendering, e futuros). */
    object Settings : Destination()

    /** Fase 0.3 Seção 8: visualizador de fotos 360 e 3D estéreo (T8.5). */
    data class PhotoViewer(
        val initialEntry: com.tucavr.filebrowser.MediaEntry,
        val photoEntries: List<com.tucavr.filebrowser.MediaEntry>,
        val initialIndex: Int = 0,
    ) : Destination()

    /** Fase 0.4 Seção 4: tela do gerenciador de downloads offline. */
    object Downloads : Destination()
}

/**
 * De onde vem a midia que esta tocando.
 *
 * [LocalFile.sizeBytes]/[Smb.sizeBytes] (T9, ver `com.tucavr.history`):
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

    /** T6.4: mesma logica de [Smb] acima, ver `VRActivity.playFtp`. */
    data class Ftp(val server: FtpServer, val path: String, val sizeBytes: Long = 0L) : PlaybackSource()

    /** T6.4: mesma logica de [Smb] acima, ver `VRActivity.playSftp`. */
    data class Sftp(val server: SftpServer, val path: String, val sizeBytes: Long = 0L) : PlaybackSource()

    /** T5.4: playback NFS a partir de um [SavedServer]. */
    data class Nfs(val server: SavedServer, val path: String, val sizeBytes: Long = 0L) : PlaybackSource()

    /** T7.4: playback DLNA a partir de um [SavedServer]. */
    data class Dlna(val server: SavedServer, val title: String, val url: String, val sizeBytes: Long = 0L) : PlaybackSource()

    /** T3.2/T3.4: playback WebDAV a partir de um [SavedServer]. */
    data class Webdav(val server: SavedServer, val path: String, val sizeBytes: Long = 0L) : PlaybackSource()
}
