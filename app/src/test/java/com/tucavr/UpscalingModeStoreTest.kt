package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Test

class UpscalingModeStoreTest {

    @Test
    fun testModeIdsMatchContract() {
        assertEquals(0, UpscalingModeStore.Mode.OFF.id)
        assertEquals(1, UpscalingModeStore.Mode.QUALITY.id)
        assertEquals(2, UpscalingModeStore.Mode.PERFORMANCE.id)
        assertEquals(3, UpscalingModeStore.Mode.AUTO.id)
    }

    @Test
    fun testFromIdMapsCorrectly() {
        assertEquals(UpscalingModeStore.Mode.OFF, UpscalingModeStore.Mode.fromId(0))
        assertEquals(UpscalingModeStore.Mode.QUALITY, UpscalingModeStore.Mode.fromId(1))
        assertEquals(UpscalingModeStore.Mode.PERFORMANCE, UpscalingModeStore.Mode.fromId(2))
        assertEquals(UpscalingModeStore.Mode.AUTO, UpscalingModeStore.Mode.fromId(3))
        assertEquals(UpscalingModeStore.Mode.OFF, UpscalingModeStore.Mode.fromId(-1))
        assertEquals(UpscalingModeStore.Mode.OFF, UpscalingModeStore.Mode.fromId(99))
    }
}
