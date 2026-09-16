package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTransformStoreTest {

    @Test
    fun testDefaultsMatchCinematicStandard() {
        assertEquals(0.0f, ScreenTransformStore.DEFAULT_POS_X, 0.001f)
        assertEquals(1.5f, ScreenTransformStore.DEFAULT_POS_Y, 0.001f)
        assertEquals(-2.4f, ScreenTransformStore.DEFAULT_POS_Z, 0.001f)
        assertEquals(2.8f, ScreenTransformStore.DEFAULT_SCALE_X, 0.001f)
        assertEquals(1.575f, ScreenTransformStore.DEFAULT_SCALE_Y, 0.001f)

        // Proporção exata 16:9
        assertEquals(16.0f / 9.0f, ScreenTransformStore.DEFAULT_SCALE_X / ScreenTransformStore.DEFAULT_SCALE_Y, 0.001f)
    }

    @Test
    fun testClampingLimitsSafety() {
        assertTrue(ScreenTransformStore.MIN_SCALE_X >= 0.5f)
        assertTrue(ScreenTransformStore.MAX_SCALE_X <= 10.0f)

        assertTrue(ScreenTransformStore.MIN_POS_Z < ScreenTransformStore.MAX_POS_Z)
        assertTrue(ScreenTransformStore.MAX_POS_Z <= -0.5f) // Nunca encostar no rosto do usuário
        assertTrue(ScreenTransformStore.MIN_POS_Y >= 0.2f) // Nunca afundar abaixo do chão
    }
}
