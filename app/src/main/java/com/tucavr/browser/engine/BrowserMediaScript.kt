package com.tucavr.browser.engine

import com.tucavr.browser.state.MediaCommand

object BrowserMediaScript {
    // O void() descarta o valor da expressão: um javascript: URL que devolve valor substitui a página por ele.
    fun uriFor(command: MediaCommand): String {
        val action =
            when (command) {
                MediaCommand.Play -> "v.play();"
                MediaCommand.Pause -> "v.pause();"
                MediaCommand.ToggleMute -> "v.muted=!v.muted;"
                is MediaCommand.Skip -> "v.currentTime+=${command.seconds};"
            }
        return "javascript:void(function(){var v=document.querySelector('video');if(!v)return;$action}())"
    }
}
