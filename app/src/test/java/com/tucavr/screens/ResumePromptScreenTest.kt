package com.tucavr.screens

import com.tucavr.history.HistorySourceType
import com.tucavr.history.PlaybackHistory
import com.tucavr.navigation.PlaybackSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResumePromptScreenTest {

    private fun createHistoryEntry(
        positionMs: Long,
        durationMs: Long
    ) = PlaybackHistory(
        historyKey = "file:///sdcard/Movies/matrix.mp4",
        title = "The Matrix",
        mediaPath = "/sdcard/Movies/matrix.mp4",
        positionMs = positionMs,
        durationMs = durationMs,
        lastPlayedAt = 1_000_000L,
        thumbnailPath = null,
        sourceType = HistorySourceType.LOCAL,
        serverInfo = null
    )

    @Test
    fun `plays immediately from start without prompt when no history entry exists`() = runTest {
        // Arrange
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        var modalShown = false
        var decidedPosition: Long? = -1L

        val screen = ResumePromptScreen(
            findHistory = { null },
            showModal = { _, _, _ -> modalShown = true },
            scope = testScope
        )
        val source = PlaybackSource.LocalFile("/sdcard/Movies/matrix.mp4")

        // Act
        screen.promptOrPlay(source) { resumeAtMs ->
            decidedPosition = resumeAtMs
        }
        testScheduler.advanceUntilIdle()

        // Assert
        assertFalse("Modal não deve ser exibido quando não há histórico", modalShown)
        assertNull("Deve iniciar do começo (null) sem histórico", decidedPosition)
    }

    @Test
    fun `plays immediately from start without prompt when video was already finished`() = runTest {
        // Arrange: 99s de 100s assistidos (> 97% concluído, isResumable() == false)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        var modalShown = false
        var decidedPosition: Long? = -1L

        val finishedEntry = createHistoryEntry(positionMs = 99_000L, durationMs = 100_000L)
        val screen = ResumePromptScreen(
            findHistory = { finishedEntry },
            showModal = { _, _, _ -> modalShown = true },
            scope = testScope
        )
        val source = PlaybackSource.LocalFile("/sdcard/Movies/matrix.mp4")

        // Act
        screen.promptOrPlay(source) { resumeAtMs ->
            decidedPosition = resumeAtMs
        }
        testScheduler.advanceUntilIdle()

        // Assert
        assertFalse("Modal não deve ser exibido para vídeos já finalizados", modalShown)
        assertNull("Deve reiniciar do começo para vídeos finalizados", decidedPosition)
    }

    @Test
    fun `prompts with saved position when video has resumable progress and user chooses resume`() = runTest {
        // Arrange: 45s de 100s assistidos (retomável)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        var capturedEntry: PlaybackHistory? = null
        var onResumeCallback: (() -> Unit)? = null
        var decidedPosition: Long? = null

        val resumableEntry = createHistoryEntry(positionMs = 45_000L, durationMs = 100_000L)
        val screen = ResumePromptScreen(
            findHistory = { resumableEntry },
            showModal = { entry, onResume, _ ->
                capturedEntry = entry
                onResumeCallback = onResume
            },
            scope = testScope
        )
        val source = PlaybackSource.LocalFile("/sdcard/Movies/matrix.mp4")

        // Act
        screen.promptOrPlay(source) { resumeAtMs ->
            decidedPosition = resumeAtMs
        }
        testScheduler.advanceUntilIdle()

        // Assert: Modal foi solicitado com a entrada correta
        assertEquals(resumableEntry, capturedEntry)

        // Usuário escolhe "Retomar"
        onResumeCallback?.invoke()

        assertEquals("Deve retomar na posição salva exata de 45.000 ms", 45_000L, decidedPosition)
    }

    @Test
    fun `starts from beginning when user chooses to restart video`() = runTest {
        // Arrange
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        var onRestartCallback: (() -> Unit)? = null
        var decidedPosition: Long? = -1L

        val resumableEntry = createHistoryEntry(positionMs = 45_000L, durationMs = 100_000L)
        val screen = ResumePromptScreen(
            findHistory = { resumableEntry },
            showModal = { _, _, onRestart ->
                onRestartCallback = onRestart
            },
            scope = testScope
        )
        val source = PlaybackSource.LocalFile("/sdcard/Movies/matrix.mp4")

        // Act
        screen.promptOrPlay(source) { resumeAtMs ->
            decidedPosition = resumeAtMs
        }
        testScheduler.advanceUntilIdle()

        // Usuário escolhe "Começar do zero"
        onRestartCallback?.invoke()

        assertNull("Deve iniciar do começo (null) quando usuário escolhe reiniciar", decidedPosition)
    }

    @Test
    fun `does not trigger playback when user dismisses or cancels prompt without choosing`() = runTest {
        // Arrange
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        var callbackInvoked = false

        val resumableEntry = createHistoryEntry(positionMs = 45_000L, durationMs = 100_000L)
        val screen = ResumePromptScreen(
            findHistory = { resumableEntry },
            showModal = { _, _, _ ->
                // Modal abre, mas o usuário simplesmente fecha (backdrop / cancelar) sem invocar resume ou restart
            },
            scope = testScope
        )
        val source = PlaybackSource.LocalFile("/sdcard/Movies/matrix.mp4")

        // Act
        screen.promptOrPlay(source) {
            callbackInvoked = true
        }
        testScheduler.advanceUntilIdle()

        // Assert
        assertFalse("Não deve iniciar reprodução caso o modal seja cancelado ou fechado", callbackInvoked)
    }
}
