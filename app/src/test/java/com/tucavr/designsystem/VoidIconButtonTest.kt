package com.tucavr.designsystem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoidIconButtonTest {
    @Test
    fun `active transparent button keeps the fill its dark icon needs to stay visible`() {
        assertTrue(iconButtonDrawsContainer(VoidButtonStyle.ACTIVE, isTransparent = true))
    }

    @Test
    fun `inactive transparent buttons stay borderless on the player bar`() {
        listOf(VoidButtonStyle.PRIMARY, VoidButtonStyle.SECONDARY, VoidButtonStyle.DISABLED).forEach { style ->
            assertFalse(style.name, iconButtonDrawsContainer(style, isTransparent = true))
        }
    }

    @Test
    fun `opaque buttons always draw their container`() {
        VoidButtonStyle.values().forEach { style ->
            assertTrue(style.name, iconButtonDrawsContainer(style, isTransparent = false))
        }
    }
}
