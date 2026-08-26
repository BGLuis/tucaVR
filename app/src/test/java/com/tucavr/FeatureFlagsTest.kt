package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsTest {

    @Test
    fun `pause on exit flag defaults to true`() {
        val flag = FeatureFlags.Flag.PAUSE_ON_EXIT
        assertEquals("pause_on_exit", flag.key)
        assertTrue("PAUSE_ON_EXIT deve vir habilitado por padrão", flag.defaultEnabled)
    }
}
