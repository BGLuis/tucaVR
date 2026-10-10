package com.tucavr.browser.state

/**
 * HTML5 media control commands sent from other VR panels.
 */
sealed interface MediaCommand {
    data object Play : MediaCommand
    data object Pause : MediaCommand
    data object ToggleMute : MediaCommand
    data class Skip(val seconds: Int) : MediaCommand
}
