package com.tucavr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IntroSequenceControllerTest {
    @Before
    fun setUp() {
        IntroSequenceController.hasCompletedIntroOnce = false
    }

    @After
    fun tearDown() {
        IntroSequenceController.hasCompletedIntroOnce = false
    }

    @Test
    fun `cold launch triggers animation playback`() {
        var playAnimationCalled = false
        var transitionToHomeCalled = false

        val controller =
            IntroSequenceController(
                isReducedMotion = { false },
                onPlayAnimation = { playAnimationCalled = true },
                onShowStaticFinalFrame = { },
                onTransitionToHome = { transitionToHomeCalled = true },
            )

        assertEquals(IntroSequenceController.State.IDLE, controller.state)
        assertFalse(controller.isIntroActive)

        controller.start()

        assertEquals(IntroSequenceController.State.PLAYING_ANIMATION, controller.state)
        assertTrue(controller.isIntroActive)
        assertTrue(playAnimationCalled)
        assertFalse(transitionToHomeCalled)
    }

    @Test
    fun `normal animation completion transitions to Home`() {
        var transitionCount = 0

        val controller =
            IntroSequenceController(
                isReducedMotion = { false },
                onPlayAnimation = { },
                onShowStaticFinalFrame = { },
                onTransitionToHome = { transitionCount++ },
            )

        controller.start()
        controller.onAnimationComplete()

        assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        assertFalse(controller.isIntroActive)
        assertTrue(IntroSequenceController.hasCompletedIntroOnce)
        assertEquals(1, transitionCount)
    }

    @Test
    fun `cold launch with reduced motion triggers 500ms static display`() {
        var staticDurationMs = 0L
        var playAnimationCalled = false
        var transitionToHomeCalled = false

        val controller =
            IntroSequenceController(
                isReducedMotion = { true },
                onPlayAnimation = { playAnimationCalled = true },
                onShowStaticFinalFrame = { duration -> staticDurationMs = duration },
                onTransitionToHome = { transitionToHomeCalled = true },
            )

        controller.start()

        assertEquals(IntroSequenceController.State.SHOWING_REDUCED_MOTION, controller.state)
        assertTrue(controller.isIntroActive)
        assertFalse(playAnimationCalled)
        assertEquals(500L, staticDurationMs)
        assertFalse(transitionToHomeCalled)
    }

    @Test
    fun `reduced motion timer completion transitions to Home`() {
        var transitionCount = 0

        val controller =
            IntroSequenceController(
                isReducedMotion = { true },
                onPlayAnimation = { },
                onShowStaticFinalFrame = { },
                onTransitionToHome = { transitionCount++ },
            )

        controller.start()
        controller.onReducedMotionTimerComplete()

        assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        assertFalse(controller.isIntroActive)
        assertTrue(IntroSequenceController.hasCompletedIntroOnce)
        assertEquals(1, transitionCount)
    }

    @Test
    fun `skip on input immediately transitions to Home during animation`() {
        var transitionCount = 0

        val controller =
            IntroSequenceController(
                isReducedMotion = { false },
                onPlayAnimation = { },
                onShowStaticFinalFrame = { },
                onTransitionToHome = { transitionCount++ },
            )

        controller.start()
        controller.skip()

        assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        assertFalse(controller.isIntroActive)
        assertTrue(IntroSequenceController.hasCompletedIntroOnce)
        assertEquals(1, transitionCount)
    }

    @Test
    fun `skip on input immediately transitions to Home during reduced motion`() {
        var transitionCount = 0

        val controller =
            IntroSequenceController(
                isReducedMotion = { true },
                onPlayAnimation = { },
                onShowStaticFinalFrame = { },
                onTransitionToHome = { transitionCount++ },
            )

        controller.start()
        controller.skip()

        assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        assertFalse(controller.isIntroActive)
        assertTrue(IntroSequenceController.hasCompletedIntroOnce)
        assertEquals(1, transitionCount)
    }

    @Test
    fun `idempotent multiple skips do not invoke transition callback multiple times`() {
        var transitionCount = 0

        val controller =
            IntroSequenceController(
                isReducedMotion = { false },
                onPlayAnimation = { },
                onShowStaticFinalFrame = { },
                onTransitionToHome = { transitionCount++ },
            )

        controller.start()
        controller.skip()
        controller.skip()
        controller.skip()

        assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        assertEquals(1, transitionCount)
    }

    @Test
    fun `subsequent start on resume does not re-trigger intro`() {
        IntroSequenceController.hasCompletedIntroOnce = true
        var playAnimationCalled = false
        var transitionCount = 0

        val controller =
            IntroSequenceController(
                isReducedMotion = { false },
                onPlayAnimation = { playAnimationCalled = true },
                onShowStaticFinalFrame = { },
                onTransitionToHome = { transitionCount++ },
            )

        controller.start()

        assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        assertFalse(controller.isIntroActive)
        assertFalse(playAnimationCalled)
        assertEquals(1, transitionCount)
    }

    @Test
    fun `skip before start is no-op`() {
        var transitionCount = 0

        val controller =
            IntroSequenceController(
                isReducedMotion = { false },
                onPlayAnimation = { },
                onShowStaticFinalFrame = { },
                onTransitionToHome = { transitionCount++ },
            )

        controller.skip()

        assertEquals(IntroSequenceController.State.IDLE, controller.state)
        assertFalse(controller.isIntroActive)
        assertEquals(0, transitionCount)
    }

    @Test
    fun `late animation complete after skip is ignored`() {
        var transitionCount = 0

        val controller =
            IntroSequenceController(
                isReducedMotion = { false },
                onPlayAnimation = { },
                onShowStaticFinalFrame = { },
                onTransitionToHome = { transitionCount++ },
            )

        controller.start()
        controller.skip()
        controller.onAnimationComplete()

        assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        assertEquals(1, transitionCount)
    }

    @Test
    fun `late reduced motion timer complete after skip is ignored`() {
        var transitionCount = 0

        val controller =
            IntroSequenceController(
                isReducedMotion = { true },
                onPlayAnimation = { },
                onShowStaticFinalFrame = { },
                onTransitionToHome = { transitionCount++ },
            )

        controller.start()
        controller.skip()
        controller.onReducedMotionTimerComplete()

        assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        assertEquals(1, transitionCount)
    }

    @Test
    fun `force replay starts animation even if intro previously completed`() {
        IntroSequenceController.hasCompletedIntroOnce = true
        var playAnimationCalled = false

        val controller =
            IntroSequenceController(
                isReducedMotion = { false },
                onPlayAnimation = { playAnimationCalled = true },
                onShowStaticFinalFrame = { },
                onTransitionToHome = { },
            )

        controller.start(forceReplay = true)

        assertEquals(IntroSequenceController.State.PLAYING_ANIMATION, controller.state)
        assertTrue(playAnimationCalled)
    }

    @Test
    fun `start while intro is active is no-op`() {
        var playAnimationCount = 0
        val controller =
            IntroSequenceController(
                isReducedMotion = { false },
                onPlayAnimation = { playAnimationCount++ },
                onShowStaticFinalFrame = { },
                onTransitionToHome = { },
            )

        controller.start()
        assertEquals(1, playAnimationCount)
        assertEquals(IntroSequenceController.State.PLAYING_ANIMATION, controller.state)

        controller.start()
        assertEquals(1, playAnimationCount)
        assertEquals(IntroSequenceController.State.PLAYING_ANIMATION, controller.state)
    }

    private fun recordingFades(
        reducedMotion: Boolean,
        fades: MutableList<Boolean>,
    ) = IntroSequenceController(
        isReducedMotion = { reducedMotion },
        onPlayAnimation = { },
        onShowStaticFinalFrame = { },
        onTransitionToHome = { fadeOut -> fades += fadeOut },
    )

    @Test
    fun `uninterrupted animation fades out to Home`() {
        val fades = mutableListOf<Boolean>()
        val controller = recordingFades(reducedMotion = false, fades = fades)

        controller.start()
        controller.onAnimationComplete()
        controller.skip()

        assertEquals(listOf(true), fades)
    }

    @Test
    fun `skip cuts to Home without a fade`() {
        val fades = mutableListOf<Boolean>()
        val controller = recordingFades(reducedMotion = false, fades = fades)

        controller.start()
        controller.skip()
        controller.onAnimationComplete()

        assertEquals(listOf(false), fades)
    }

    @Test
    fun `reduced motion never fades to Home`() {
        val fades = mutableListOf<Boolean>()
        recordingFades(reducedMotion = true, fades = fades).apply {
            start()
            onReducedMotionTimerComplete()
        }
        IntroSequenceController.hasCompletedIntroOnce = false
        recordingFades(reducedMotion = true, fades = fades).apply {
            start()
            skip()
        }

        assertEquals(listOf(false, false), fades)
    }

    @Test
    fun `resume after the intro already played goes to Home without a fade`() {
        IntroSequenceController.hasCompletedIntroOnce = true
        val fades = mutableListOf<Boolean>()

        recordingFades(reducedMotion = false, fades = fades).start()

        assertEquals(listOf(false), fades)
    }
}
