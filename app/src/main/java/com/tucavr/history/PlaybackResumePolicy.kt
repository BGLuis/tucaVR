package com.tucavr.history

/**
 * Política de retomada de reprodução para vídeos locais e de rede (#88, #100).
 *
 * - [ASK]: Exibe diálogo/prompt frontal confirmando se deseja retomar ou começar do início.
 * - [RESUME]: Retoma automaticamente do ponto salvo se for retomável, sem perguntar.
 * - [START]: Começa sempre do início (ignora posição salva do histórico).
 */
enum class PlaybackResumePolicy {
    ASK,
    RESUME,
    START,
}
