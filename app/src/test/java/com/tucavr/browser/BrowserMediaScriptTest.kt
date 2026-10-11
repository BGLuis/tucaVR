package com.tucavr.browser

import com.tucavr.browser.engine.BrowserMediaScript
import com.tucavr.browser.state.MediaCommand
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMediaScriptTest {
    private val allCommands =
        listOf(MediaCommand.Play, MediaCommand.Pause, MediaCommand.ToggleMute, MediaCommand.Skip(15))

    @Test
    fun `every command discards its result so the page is never replaced by it`() {
        allCommands.forEach { command ->
            val uri = BrowserMediaScript.uriFor(command)

            assertTrue("$command: precisa começar com javascript:void(", uri.startsWith("javascript:void(function(){"))
            assertTrue("$command: precisa fechar a função e o void", uri.endsWith("}())"))
        }
    }

    @Test
    fun `a page without a video is left alone`() {
        allCommands.forEach { command ->
            assertTrue(BrowserMediaScript.uriFor(command).contains("if(!v)return;"))
        }
    }

    @Test
    fun `each command maps to its html5 media action`() {
        assertTrue(BrowserMediaScript.uriFor(MediaCommand.Play).contains("v.play();"))
        assertTrue(BrowserMediaScript.uriFor(MediaCommand.Pause).contains("v.pause();"))
        assertTrue(BrowserMediaScript.uriFor(MediaCommand.ToggleMute).contains("v.muted=!v.muted;"))
    }

    @Test
    fun `skip keeps the sign so rewinding goes backwards`() {
        assertTrue(BrowserMediaScript.uriFor(MediaCommand.Skip(15)).contains("v.currentTime+=15;"))
        assertTrue(BrowserMediaScript.uriFor(MediaCommand.Skip(-10)).contains("v.currentTime+=-10;"))
    }
}
