package com.vrplayer.history

import android.content.Context
import com.vrplayer.navigation.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T9.1-T9.3: ponto unico de escrita/leitura do historico de reproducao,
 * dono de [AppDatabase] + [PlaybackProgressThrottle]. Instanciado uma vez
 * por [com.vrplayer.VRActivity] (mesmo ciclo de vida do processo) e usado
 * pelos 3 entry points de playback (`playFile`/`playUrl`/`playSmb`, ver
 * VRActivity.kt) e pelo hook `updateMediaProgress` (chamado pelo C++ via
 * JNI, ver `PlaybackProgressThrottle` para a frequencia real).
 */
class PlaybackHistoryTracker(context: Context) {

    private val dao = AppDatabase.getInstance(context).playbackHistoryDao()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val throttle = PlaybackProgressThrottle()

    // Estado da midia tocando agora — atualizado a cada `startTracking` e a
    // cada save de progresso (para o proximo save "herdar" positionMs/
    // durationMs mais recentes sem precisar reconsultar o Room).
    @Volatile
    private var current: PlaybackHistory? = null

    /**
     * Chamado pelos 3 entry points de playback (T9.2/T9.3: "e aqui que voce
     * tem o path/titulo/fonte disponiveis para popular o registro"), ANTES
     * de `nativePlayVideo`/`nativePlaySmb`. Reseta o throttle (nova midia =
     * nova janela de 10s) e prepara o registro que sera de fato gravado na
     * primeira chamada de [onProgress].
     *
     * Nao grava no Room aqui de imediato — so quando o primeiro `onProgress`
     * chegar com `totalSec > 0` (temos duracao real). Gravar um registro com
     * `durationMs = 0` imediatamente poluiria a tela de T9.4 com uma entrada
     * sem progresso visivel enquanto o video ainda esta carregando.
     */
    fun startTracking(source: PlaybackSource, title: String) {
        throttle.reset()
        current = PlaybackHistory(
            historyKey = source.historyKey(),
            title = title,
            mediaPath = source.mediaPath(),
            positionMs = 0L,
            durationMs = 0L,
            lastPlayedAt = System.currentTimeMillis(),
            thumbnailPath = null,
            sourceType = source.historySourceType(),
            serverInfo = source.serverInfoJson()
        )
    }

    /**
     * T9.2. Chamado a partir de `VRActivity.updateMediaProgress` (que por
     * sua vez e chamado pelo C++ via JNI, ~10x/segundo — ver
     * [PlaybackProgressThrottle]). So grava de fato quando o throttle libera
     * (~a cada 10s) e quando ha uma midia sendo rastreada
     * ([startTracking] ja foi chamado).
     */
    fun onProgress(currentSec: Float, totalSec: Float) {
        val base = current ?: return
        if (totalSec <= 0f) return
        if (!throttle.shouldSave()) return

        val updated = base.copy(
            positionMs = (currentSec * 1000).toLong().coerceAtLeast(0L),
            durationMs = (totalSec * 1000).toLong().coerceAtLeast(0L),
            lastPlayedAt = System.currentTimeMillis()
        )
        current = updated
        scope.launch { dao.upsert(updated) }
    }

    /**
     * T9.3: consulta se ja existe historico para [source] (mesma chave
     * composta de [PlaybackSource.historyKey]). Chamar SEMPRE de uma
     * coroutine fora da main thread (Room bloqueia query em main thread por
     * padrao) — a propria funcao ja e `suspend` e faz `withContext(IO)`
     * internamente, entao o chamador so precisa estar dentro de QUALQUER
     * coroutine.
     */
    suspend fun findExisting(source: PlaybackSource): PlaybackHistory? =
        withContext(Dispatchers.IO) { dao.findByKey(source.historyKey()) }

    /** T9.4: lista completa, mais recentes primeiro. */
    suspend fun listRecent(): List<PlaybackHistory> =
        withContext(Dispatchers.IO) { dao.listRecentFirst() }

    suspend fun delete(historyKey: String) {
        withContext(Dispatchers.IO) { dao.deleteByKey(historyKey) }
    }
}

/**
 * T9.3: uma entrada de historico so vale a pena oferecer "retomar" se ainda
 * sobra progresso relevante — perto do fim (>= 97%) e tratado como "ja
 * terminou de assistir", entao comecar do zero de novo e o comportamento
 * certo sem perguntar nada. Constante extraida (em vez de inline) para
 * poder ser testada isoladamente (`PlaybackHistoryMappingTest`).
 */
fun PlaybackHistory.isResumable(): Boolean {
    if (durationMs <= 0L) return false
    if (positionMs < MIN_RESUMABLE_POSITION_MS) return false
    val fraction = positionMs.toDouble() / durationMs.toDouble()
    return fraction < RESUMABLE_MAX_FRACTION
}

private const val MIN_RESUMABLE_POSITION_MS = 5_000L
private const val RESUMABLE_MAX_FRACTION = 0.97
