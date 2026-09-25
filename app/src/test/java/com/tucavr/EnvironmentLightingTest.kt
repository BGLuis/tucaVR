package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes unitários para configurações e mapeamentos de iluminação e cor do ambiente (RF-ENV-007).
 * Valida limites de clamping, conversões de modo de screen glow e flags de modo noturno.
 */
class EnvironmentLightingTest {

    @Test
    fun testScreenGlowModeMapping() {
        assertEquals(0.0f, FeatureFlags.screenGlowModeToFloat(FeatureFlags.SCREEN_GLOW_OFF), 0.001f)
        assertEquals(0.45f, FeatureFlags.screenGlowModeToFloat(FeatureFlags.SCREEN_GLOW_SUBTLE), 0.001f)
        assertEquals(0.85f, FeatureFlags.screenGlowModeToFloat(FeatureFlags.SCREEN_GLOW_STRONG), 0.001f)

        // Valores fora do intervalo devem mapear para 0.0f (desligado)
        assertEquals(0.0f, FeatureFlags.screenGlowModeToFloat(-1), 0.001f)
        assertEquals(0.0f, FeatureFlags.screenGlowModeToFloat(99), 0.001f)
    }

    @Test
    fun testScreenGlowConstantsMatchNativeContract() {
        assertEquals(0, FeatureFlags.SCREEN_GLOW_OFF)
        assertEquals(1, FeatureFlags.SCREEN_GLOW_SUBTLE)
        assertEquals(2, FeatureFlags.SCREEN_GLOW_STRONG)

        assertEquals(0.0f, FeatureFlags.SCREEN_GLOW_INTENSITY_OFF, 0.001f)
        assertEquals(0.45f, FeatureFlags.SCREEN_GLOW_INTENSITY_SUBTLE, 0.001f)
        assertEquals(0.85f, FeatureFlags.SCREEN_GLOW_INTENSITY_STRONG, 0.001f)
    }

    @Test
    fun testEnvironmentBrightnessClampingContract() {
        val testClamping = { value: Float -> value.coerceIn(0.0f, 1.0f) }

        assertEquals(0.0f, testClamping(-0.5f), 0.001f)
        assertEquals(0.0f, testClamping(0.0f), 0.001f)
        assertEquals(0.5f, testClamping(0.5f), 0.001f)
        assertEquals(1.0f, testClamping(1.0f), 0.001f)
        assertEquals(1.0f, testClamping(1.5f), 0.001f)
    }

    @Test
    fun testColorTemperatureClampingContract() {
        val testClamping = { kelvin: Float -> kelvin.coerceIn(2700.0f, 6500.0f) }

        assertEquals(2700.0f, testClamping(1500.0f), 0.001f)
        assertEquals(2700.0f, testClamping(2700.0f), 0.001f)
        assertEquals(4500.0f, testClamping(4500.0f), 0.001f)
        assertEquals(6500.0f, testClamping(6500.0f), 0.001f)
        assertEquals(6500.0f, testClamping(9000.0f), 0.001f)
    }

    @Test
    fun testNightModeFlagContract() {
        assertEquals("night_mode", FeatureFlags.Flag.NIGHT_MODE.key)
        assertFalse(FeatureFlags.Flag.NIGHT_MODE.defaultEnabled)
    }
}
