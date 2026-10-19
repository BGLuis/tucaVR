package com.tucavr.browser.engine

object BrowserScroll {
    // AXIS_VSCROLL positivo rola a página para cima; o app usa delta positivo para rolar para baixo.
    fun wheelAxisValue(
        scrollDeltaPixels: Float,
        pixelsPerWheelUnit: Float,
    ): Float = if (pixelsPerWheelUnit > 0f) -scrollDeltaPixels / pixelsPerWheelUnit else 0f
}
