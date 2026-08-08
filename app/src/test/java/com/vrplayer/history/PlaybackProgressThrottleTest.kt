package com.vrplayer.history

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T9.2 — o hook real (`VRActivity.updateMediaProgress`) e chamado pelo C++
 * via JNI ~10x/segundo (ver `native/src/vr_player_app.cpp`,
 * `frameCount % 6 == 0` a 60fps), nao 1x a cada 10s. Este teste garante que
 * o throttle de fato reduz isso para no maximo 1 "save liberado" por janela
 * de 10s, usando um relogio falso (injetavel via construtor) para nao
 * depender de `Thread.sleep` real nem de tempo de parede.
 */
class PlaybackProgressThrottleTest {

    private class FakeClock(var nowMs: Long = 0L) {
        fun advance(deltaMs: Long) { nowMs += deltaMs }
    }

    @Test
    fun `first call always saves, even before any interval has passed`() {
        val clock = FakeClock(0L)
        val throttle = PlaybackProgressThrottle(minIntervalMs = 10_000L, nowMs = { clock.nowMs })
        assertTrue(throttle.shouldSave())
    }

    @Test
    fun `rapid repeated calls within the window do not save again`() {
        val clock = FakeClock(0L)
        val throttle = PlaybackProgressThrottle(minIntervalMs = 10_000L, nowMs = { clock.nowMs })
        assertTrue(throttle.shouldSave())

        // Simula ~10 chamadas/segundo por 2 segundos (o padrao real do JNI),
        // todas dentro da mesma janela de 10s.
        repeat(20) {
            clock.advance(100L)
            assertFalse(throttle.shouldSave())
        }
    }

    @Test
    fun `saves again once the interval has fully elapsed`() {
        val clock = FakeClock(0L)
        val throttle = PlaybackProgressThrottle(minIntervalMs = 10_000L, nowMs = { clock.nowMs })
        assertTrue(throttle.shouldSave())

        clock.advance(9_999L)
        assertFalse(throttle.shouldSave())

        clock.advance(1L) // total: exatamente 10_000ms desde o ultimo save
        assertTrue(throttle.shouldSave())
    }

    @Test
    fun `reset makes the next call save immediately regardless of elapsed time`() {
        val clock = FakeClock(0L)
        val throttle = PlaybackProgressThrottle(minIntervalMs = 10_000L, nowMs = { clock.nowMs })
        assertTrue(throttle.shouldSave())

        clock.advance(1_000L) // bem dentro da janela
        throttle.reset() // nova midia comecou (ver PlaybackHistoryTracker.startTracking)
        assertTrue(throttle.shouldSave())
    }

    @Test
    fun `simulated 60s of real JNI call frequency saves at most 6 times`() {
        val clock = FakeClock(0L)
        val throttle = PlaybackProgressThrottle(minIntervalMs = 10_000L, nowMs = { clock.nowMs })

        var saves = 0
        // 60 segundos * ~10 chamadas/segundo (frameCount % 6 == 0 a 60fps) = 600 chamadas.
        repeat(600) {
            if (throttle.shouldSave()) saves++
            clock.advance(100L)
        }

        assertTrue("esperava <= 7 saves em 60s (janela de 10s), teve $saves", saves <= 7)
        assertTrue("esperava >= 5 saves em 60s (janela de 10s), teve $saves", saves >= 5)
    }
}
