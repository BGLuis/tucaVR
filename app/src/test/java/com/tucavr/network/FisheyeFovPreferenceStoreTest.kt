package com.tucavr.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FisheyeFovPreferenceStoreTest {
    @Test
    fun testValidFovsAccepted() {
        val validAngles = listOf(180, 190, 200, 220)
        for (fov in validAngles) {
            assertTrue("Expected FOV $fov to be valid", FisheyeFovPreferenceStore.isValidFov(fov))
        }
    }

    @Test
    fun testInvalidFovsRejected() {
        val invalidAngles = listOf(0, 170, 230, -1, 185, 360)
        for (fov in invalidAngles) {
            assertFalse("Expected FOV $fov to be invalid", FisheyeFovPreferenceStore.isValidFov(fov))
        }
    }

    @Test
    fun testDefaultFovIs190() {
        assertEquals(190, FisheyeFovPreferenceStore.DEFAULT_FOV)
        assertTrue(FisheyeFovPreferenceStore.isValidFov(FisheyeFovPreferenceStore.DEFAULT_FOV))
    }
}
