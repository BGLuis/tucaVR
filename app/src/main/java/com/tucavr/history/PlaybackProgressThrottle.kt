package com.tucavr.history

/**
 * T9.2: "Salvar posicao automaticamente a cada 10 segundos durante
 * reproducao". A fonte do progresso (`VRActivity.updateMediaProgress`, ver
 * companion object) e chamada pelo C++ via JNI repetidamente durante toda a
 * reproducao — confirmado no source (`native/src/vr_player_app.cpp`,
 * `Update()`): `frameCount % 6 == 0` a ~60fps, ou seja, ~10 vezes POR
 * SEGUNDO, nao uma vez a cada 10s. Sem throttle aqui, cada uma dessas
 * chamadas viraria uma escrita no Room (`INSERT OR REPLACE`) — ~10
 * escritas/segundo de I/O em disco pelo tempo inteiro de reproducao, o que
 * e claramente desperdicio (e o proprio objetivo de T9.2 e literalmente "a
 * cada 10 segundos", nao "a cada frame").
 *
 * Classe pura e sem estado de Android/Room de proposito — testavel com
 * JUnit puro na JVM (`PlaybackProgressThrottleTest`). O relogio e
 * injetavel (`nowMs`) para os testes nao dependerem de `Thread.sleep` real
 * nem de `System.currentTimeMillis()` de verdade (mesmo padrao do `trait
 * Clock`/`FakeClock` usado no lado Rust para testar o `SyncManager`, ver
 * `docs/TESTING-PLAN.md`).
 */
class PlaybackProgressThrottle(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private var lastSaveAtMs: Long? = null

    /**
     * `true` se ja se passaram >= [minIntervalMs] desde o ultimo save (ou se
     * ainda nao houve nenhum save nesta sessao de playback — a PRIMEIRA
     * chamada apos [reset] sempre libera um save imediato, para o historico
     * ja existir assim que o usuario comeca a assistir, em vez de exigir
     * esperar os primeiros 10s inteiros). Ao retornar `true`, ja marca
     * internamente "salvo agora" — quem chama nao precisa (e nao deve)
     * chamar de novo sem de fato ter salvo.
     */
    fun shouldSave(): Boolean {
        val now = nowMs()
        val last = lastSaveAtMs
        if (last != null && now - last < minIntervalMs) {
            return false
        }
        lastSaveAtMs = now
        return true
    }

    /** Chamado ao iniciar uma nova midia — o throttle de uma midia anterior
     * nao deve vazar para a proxima (ver `PlaybackHistoryTracker.startTracking`). */
    fun reset() {
        lastSaveAtMs = null
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 10_000L
    }
}
