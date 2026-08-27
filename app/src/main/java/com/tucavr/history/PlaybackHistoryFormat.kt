package com.tucavr.history

/**
 * T9.3/T9.4: formata milissegundos como `H:MM:SS` (ou `MM:SS` quando dura
 * menos de uma hora) para o prompt "Retomar de XX:XX?" e para a tela
 * "Continuar assistindo". Funcao pura — testada em
 * [PlaybackHistoryFormatTest] sem precisar de Android/`Context`.
 */
fun formatDurationMs(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** Percentual assistido (0-100), arredondado — usado como meta na lista de T9.4. */
fun watchedPercent(entry: PlaybackHistory): Int {
    if (entry.durationMs <= 0L) return 0
    val fraction = entry.positionMs.toDouble() / entry.durationMs.toDouble()
    return (fraction * 100).toInt().coerceIn(0, 100)
}
