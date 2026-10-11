package com.tucavr.browser

import com.tucavr.browser.engine.BrowserScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserScrollTest {
    @Test
    fun `pushing the stick down scrolls the page down which is a negative wheel value`() {
        val wheel = BrowserScroll.wheelAxisValue(scrollDeltaPixels = 13f, pixelsPerWheelUnit = 64f)

        assertTrue("A rolagem para baixo precisa virar AXIS_VSCROLL negativo", wheel < 0f)
    }

    @Test
    fun `pulling the stick up scrolls the page up which is a positive wheel value`() {
        val wheel = BrowserScroll.wheelAxisValue(scrollDeltaPixels = -13f, pixelsPerWheelUnit = 64f)

        assertTrue(wheel > 0f)
    }

    @Test
    fun `the wheel value is scaled by the gecko pixels per unit so the speed matches the stick`() {
        val wheel = BrowserScroll.wheelAxisValue(scrollDeltaPixels = 64f, pixelsPerWheelUnit = 64f)

        assertEquals(-1f, wheel, 0.0001f)
    }

    @Test
    fun `an invalid scroll factor never produces a scroll`() {
        assertEquals(0f, BrowserScroll.wheelAxisValue(50f, 0f), 0f)
        assertEquals(0f, BrowserScroll.wheelAxisValue(50f, -1f), 0f)
    }
}
