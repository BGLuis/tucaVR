package com.vrplayer.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * T9.1: espelha o `SourceType` do exemplo em `docs/phases/PHASE-0.1-MVP.md`
 * (secao 9), mas deliberadamente NAO e um enum "de dominio" espalhado pelo
 * app — [com.vrplayer.navigation.PlaybackSource] (ja existente, ver
 * `Destination.kt`) e que continua sendo o unico jeito de representar "de
 * onde vem a midia" em toda a navegacao/UI. Este enum e so o formato de
 * armazenamento da coluna `sourceType` no Room, derivado de um
 * [com.vrplayer.navigation.PlaybackSource] sempre atraves de UMA unica
 * funcao (`PlaybackSource.historySourceType()`, em `PlaybackHistoryMapping.kt`)
 * — nenhum outro lugar do codigo constroi um [HistorySourceType] "na mao"
 * casando manualmente com um branch de `PlaybackSource`, entao os dois nunca
 * podem divergir silenciosamente.
 */
enum class HistorySourceType { LOCAL, SMB, HTTP, FTP, SFTP, NFS, DLNA }

/**
 * T9.1: registro de progresso de reproducao (Room). Segue o exemplo do doc
 * de perto, com duas mudancas deliberadas:
 *
 * 1. `mediaUri: String` (chave primaria do doc) virou `historyKey: String`
 *    — o doc pede explicitamente (secao 9, aviso "URI estabilidade") um
 *    identificador COMPOSTO em vez de uma URI/path crua, porque URIs de SMB
 *    podem mudar se o IP do servidor mudar. `historyKey` e sempre construido
 *    por `PlaybackSource.historyKey()` (`PlaybackHistoryMapping.kt`), nunca
 *    montado manualmente em outro lugar.
 *
 * 2. `lastPlayedAt: Instant` (doc) virou `lastPlayedAt: Long` (epoch millis).
 *    DECISAO: Long em vez de `java.time.Instant`. O `minSdk = 26` deste
 *    projeto (`app/build.gradle.kts`) already suporta `java.time` nativamente
 *    sem desugaring, entao `Instant` seria tecnicamente viavel — mas exigiria
 *    escrever e manter um `TypeConverter` so para isso (Room nao persiste
 *    `Instant` nativamente), com zero ganho pratico aqui: o unico uso de
 *    `lastPlayedAt` e ordenar por "mais recente primeiro" (`ORDER BY
 *    lastPlayedAt DESC`) e formatar um timestamp legivel na tela de T9.4 —
 *    ambos igualmente triviais com `Long`. `Long` tambem deixa a logica de
 *    throttle/testes (`PlaybackProgressThrottle`) pura e testavel na JVM sem
 *    precisar lidar com fuso horario/`Clock` de `java.time`.
 */
@Entity(tableName = "playback_history")
data class PlaybackHistory(
    @PrimaryKey val historyKey: String,
    val title: String,
    /**
     * O "endereco tocavel" da midia — path absoluto local, URL http(s), ou
     * path relativo dentro do share SMB. Deliberadamente SEPARADO de
     * [historyKey]: `historyKey` e uma chave composta opaca (ver aviso do
     * doc sobre estabilidade de URI) usada so para achar/atualizar o
     * registro certo; `mediaPath` e o que a tela "Continuar assistindo"
     * (T9.4) de fato usa para retocar a midia (junto com [sourceType]/
     * [serverInfo] para o caso SMB, que tambem precisa resolver o
     * [com.vrplayer.network.SmbServer] salvo por `serverId`).
     */
    val mediaPath: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,
    val thumbnailPath: String?,
    val sourceType: HistorySourceType,
    /**
     * JSON com dados do servidor SMB/FTP para poder re-navegar/reconectar
     * (host/port/share/name/serverId) — SOMENTE para [HistorySourceType.SMB]
     * ou [HistorySourceType.FTP]. NUNCA contem username/password: essas
     * credenciais continuam vivendo exclusivamente em `SmbCredentialStore`/
     * `FtpCredentialStore` (`EncryptedSharedPreferences`, ver T6.4) e sao
     * recuperadas de novo pelo `serverId` na hora de retomar, nunca
     * duplicadas aqui em texto plano.
     */
    val serverInfo: String?
)
