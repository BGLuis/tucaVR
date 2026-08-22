package com.vrplayer.history

import com.vrplayer.navigation.PlaybackSource
import org.json.JSONObject

/**
 * T9.1/T9.3: converte [PlaybackSource] (o modelo de navegacao/UI ja
 * existente, ver `com.vrplayer.navigation.Destination`) para o que o
 * historico de reproducao precisa persistir. Mantido como funcoes puras
 * (sem `Context`, sem Room, sem I/O) para poder ser testado com JUnit puro
 * na JVM — a UNICA excecao e [PlaybackSource.serverInfoJson], que usa
 * `org.json.JSONObject`: essa classe existe de verdade no `android.jar` de
 * testes unitarios (stub) e lanca `RuntimeException` em runtime de teste
 * sem Robolectric, entao essa funcao especifica NAO tem teste JVM puro
 * (mesma limitacao que ja existe hoje em `SmbCredentialStore`, que usa a
 * mesma API) — [PlaybackHistoryKeyTest] cobre so [historyKey]/[historySourceType].
 */

/**
 * Chave estavel do historico (`PlaybackHistory.historyKey`).
 *
 * Aviso do doc (secao 9, "URI estabilidade"): "URIs de SMB podem mudar se o
 * IP do servidor mudar. Use um identificador composto: server_name + share +
 * path + filename + file_size como chave, nao apenas o URI." Implementado
 * assim:
 *
 *  - LOCAL: `local|<path>|<sizeBytes>` — path absoluto local nao sofre do
 *    problema de IP mutavel do SMB, mas ainda inclui o tamanho para nao
 *    colidir com um arquivo diferente que por acaso reuse o mesmo path
 *    apos ser substituido (mesmo raciocinio ja usado por
 *    `ThumbnailGenerator.cacheKeyFor`, T5.4).
 *  - HTTP: `http|<url>` — a URL EH o endereco escolhido pelo usuario; se ela
 *    mudar, e deliberadamente uma entrada de historico diferente (nao ha
 *    "IP do servidor" separado da URL neste caso, diferente do SMB).
 *  - SMB: `smb|<server.name>|<share>|<path>|<sizeBytes>` — meio bloco no
 *    aviso do doc, com uma decisao explicita: NAO inclui `server.host`/porta
 *    (que sao exatamente o que pode mudar), so `server.name` (o rotulo que
 *    o usuario escolheu ao salvar o servidor, ver T6.4/`SmbCredentialStore`).
 *    `path` ja inclui o nome do arquivo como seu ultimo segmento (e como
 *    `VRPresentation.loadNetworkDirectory` monta `childPath`), entao nao ha
 *    um campo `filename` separado — seria redundante.
 */
fun PlaybackSource.historyKey(): String = when (this) {
    is PlaybackSource.LocalFile -> "local|$path|$sizeBytes"
    is PlaybackSource.Http -> "http|$url"
    is PlaybackSource.Smb -> "smb|${server.name}|${server.share}|$path|$sizeBytes"
    // Mesmo raciocinio do SMB acima (aviso do doc, secao 9): so `server.name`
    // (rotulo escolhido pelo usuario), nunca host/porta (que podem mudar).
    is PlaybackSource.Ftp -> "ftp|${server.name}|$path|$sizeBytes"
    is PlaybackSource.Sftp -> "sftp|${server.name}|$path|$sizeBytes"
    is PlaybackSource.Nfs -> "nfs|${server.name}|${server.path}|$path|$sizeBytes"
}

/** Ver [PlaybackHistory.mediaPath]. */
fun PlaybackSource.mediaPath(): String = when (this) {
    is PlaybackSource.LocalFile -> path
    is PlaybackSource.Http -> url
    is PlaybackSource.Smb -> path
    is PlaybackSource.Ftp -> path
    is PlaybackSource.Sftp -> path
    is PlaybackSource.Nfs -> path
}

fun PlaybackSource.historySourceType(): HistorySourceType = when (this) {
    is PlaybackSource.LocalFile -> HistorySourceType.LOCAL
    is PlaybackSource.Http -> HistorySourceType.HTTP
    is PlaybackSource.Smb -> HistorySourceType.SMB
    is PlaybackSource.Ftp -> HistorySourceType.FTP
    is PlaybackSource.Sftp -> HistorySourceType.SFTP
    is PlaybackSource.Nfs -> HistorySourceType.NFS
}

/** Titulo padrao (usado quando quem chama nao tem um titulo melhor a mao). */
fun PlaybackSource.defaultHistoryTitle(): String = when (this) {
    is PlaybackSource.LocalFile -> path.substringAfterLast('/')
    is PlaybackSource.Http -> url
    is PlaybackSource.Smb -> path.substringAfterLast('/')
    is PlaybackSource.Ftp -> path.substringAfterLast('/')
    is PlaybackSource.Sftp -> path.substringAfterLast('/')
    is PlaybackSource.Nfs -> path.substringAfterLast('/')
}

/**
 * JSON com dados do servidor SMB/FTP/SFTP/NFS para [PlaybackHistory.serverInfo]
 * — `null` para fontes que nao vem de um servidor salvo (local/HTTP).
 */
fun PlaybackSource.serverInfoJson(): String? = when (this) {
    is PlaybackSource.Smb -> JSONObject().apply {
        put("serverId", server.id)
        put("name", server.name)
        put("host", server.host)
        put("port", server.port)
        put("share", server.share)
        put("domain", server.domain)
    }.toString()
    is PlaybackSource.Ftp -> JSONObject().apply {
        put("serverId", server.id)
        put("name", server.name)
        put("host", server.host)
        put("port", server.port)
    }.toString()
    is PlaybackSource.Sftp -> JSONObject().apply {
        put("serverId", server.id)
        put("name", server.name)
        put("host", server.host)
        put("port", server.port)
    }.toString()
    is PlaybackSource.Nfs -> JSONObject().apply {
        put("serverId", server.id)
        put("name", server.name)
        put("host", server.host)
        put("port", server.port)
        put("exportPath", server.path)
    }.toString()
    else -> null
}
