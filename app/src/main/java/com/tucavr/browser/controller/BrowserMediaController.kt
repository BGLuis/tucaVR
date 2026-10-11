package com.tucavr.browser.controller

import com.tucavr.browser.state.MediaCommand
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Controller for cross-panel communication.
 * Allows external panels (e.g., ControlsPresentation) to send HTML5 media commands.
 */
class BrowserMediaController {
    private val _commands = MutableSharedFlow<MediaCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<MediaCommand> = _commands.asSharedFlow()

    fun emitCommand(command: MediaCommand) {
        _commands.tryEmit(command)
    }
}
