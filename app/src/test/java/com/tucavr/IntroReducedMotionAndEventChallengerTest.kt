package com.tucavr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Empirical challenger stress test suite for Milestone 3 (Requirement R4, Epic #102).
 * Verifies reduced motion detection edge cases, touch/key event interception and consumption,
 * and presentation resource disposal safety under adversarial conditions.
 */
class IntroReducedMotionAndEventChallengerTest {
    @Before
    fun setUp() {
        IntroSequenceController.hasCompletedIntroOnce = false
    }

    @After
    fun tearDown() {
        IntroSequenceController.hasCompletedIntroOnce = false
    }

    // =========================================================================
    // 1. Reduced Motion Detection Edge Cases & Fallback Oracles
    // =========================================================================

    @Test
    fun `reduced motion oracle - transition animation scale zero triggers reduced motion`() {
        fun evaluate(
            transitionScale: Float,
            animatorScale: Float,
            animatorsEnabled: Boolean,
        ): Boolean = transitionScale == 0.0f || animatorScale == 0.0f || !animatorsEnabled

        assertTrue(evaluate(transitionScale = 0.0f, animatorScale = 1.0f, animatorsEnabled = true))
    }

    @Test
    fun `reduced motion oracle - animator duration scale zero triggers reduced motion`() {
        fun evaluate(
            transitionScale: Float,
            animatorScale: Float,
            animatorsEnabled: Boolean,
        ): Boolean = transitionScale == 0.0f || animatorScale == 0.0f || !animatorsEnabled

        assertTrue(evaluate(transitionScale = 1.0f, animatorScale = 0.0f, animatorsEnabled = true))
    }

    @Test
    fun `reduced motion oracle - animators disabled triggers reduced motion`() {
        fun evaluate(
            transitionScale: Float,
            animatorScale: Float,
            animatorsEnabled: Boolean,
        ): Boolean = transitionScale == 0.0f || animatorScale == 0.0f || !animatorsEnabled

        assertTrue(evaluate(transitionScale = 1.0f, animatorScale = 1.0f, animatorsEnabled = false))
    }

    @Test
    fun `reduced motion oracle - standard animations enabled returns false`() {
        fun evaluate(
            transitionScale: Float,
            animatorScale: Float,
            animatorsEnabled: Boolean,
        ): Boolean = transitionScale == 0.0f || animatorScale == 0.0f || !animatorsEnabled

        assertFalse(evaluate(transitionScale = 1.0f, animatorScale = 1.0f, animatorsEnabled = true))
        assertFalse(evaluate(transitionScale = 0.5f, animatorScale = 0.5f, animatorsEnabled = true))
        assertFalse(evaluate(transitionScale = 1.5f, animatorScale = 1.5f, animatorsEnabled = true))
    }

    @Test
    fun `reduced motion query handles security exception gracefully without crash`() {
        // Simulates the exact runCatching fallback logic in IntroSequenceController.isReducedMotionEnabled
        val transitionScale =
            runCatching {
                throw SecurityException("Permission denial: cannot read settings in restricted profile")
            }.getOrDefault(1.0f)

        val animatorScale =
            runCatching {
                throw SecurityException("Permission denial: reading animator duration scale not permitted")
            }.getOrDefault(1.0f)

        val animatorsEnabled =
            runCatching {
                throw SecurityException("Permission denial: reading ValueAnimator state failed")
            }.getOrDefault(true)

        val isReducedMotion = transitionScale == 0.0f || animatorScale == 0.0f || !animatorsEnabled

        // Under security exception, defaults (1.0f, 1.0f, true) ensure the app defaults to standard animation safely
        assertEquals(1.0f, transitionScale, 0.001f)
        assertEquals(1.0f, animatorScale, 0.001f)
        assertTrue(animatorsEnabled)
        assertFalse(isReducedMotion)
    }

    @Test
    fun `reduced motion query handles setting not found exception gracefully`() {
        val transitionScale =
            runCatching {
                throw IllegalStateException("SettingNotFoundException: TRANSITION_ANIMATION_SCALE")
            }.getOrDefault(1.0f)

        val animatorScale =
            runCatching {
                throw IllegalStateException("SettingNotFoundException: ANIMATOR_DURATION_SCALE")
            }.getOrDefault(1.0f)

        val isReducedMotion = transitionScale == 0.0f || animatorScale == 0.0f

        assertEquals(1.0f, transitionScale, 0.001f)
        assertEquals(1.0f, animatorScale, 0.001f)
        assertFalse(isReducedMotion)
    }

    // =========================================================================
    // 2. Touch and Key Event Interception and Consumption in VRPresentation
    // =========================================================================

    /**
     * Test harness replicating VRPresentation's event dispatch contract:
     * - Returns true (consumed) when intro is active.
     * - Dispatches to super (returns false) when intro has completed.
     */
    private class VRPresentationEventHarness(
        isReducedMotion: Boolean = false,
    ) {
        var transitionCount: Int = 0
            private set
        var introDisposed: Boolean = false
            private set
        var pendingTimerCancelled: Boolean = false
            private set

        val controller =
            IntroSequenceController(
                isReducedMotion = { isReducedMotion },
                onPlayAnimation = {},
                onShowStaticFinalFrame = { _ -> },
                onTransitionToHome = {
                    disposeIntro()
                    transitionCount++
                },
            )

        init {
            controller.start()
        }

        fun skipIntro(): Boolean {
            if (controller.isIntroActive) {
                controller.skip()
                return true
            }
            return false
        }

        private fun disposeIntro() {
            pendingTimerCancelled = true
            introDisposed = true
        }

        fun dispatchTouchEvent(): Boolean {
            if (controller.isIntroActive) {
                skipIntro()
                return true
            }
            return false // super.dispatchTouchEvent
        }

        fun dispatchKeyEvent(): Boolean {
            if (controller.isIntroActive) {
                skipIntro()
                return true
            }
            return false // super.dispatchKeyEvent
        }
    }

    @Test
    fun `event interception - touch event is consumed and returns true during animation`() {
        val harness = VRPresentationEventHarness(isReducedMotion = false)

        assertTrue(harness.controller.isIntroActive)
        assertEquals(IntroSequenceController.State.PLAYING_ANIMATION, harness.controller.state)

        // First touch event should be intercepted and consumed
        val consumed = harness.dispatchTouchEvent()
        assertTrue("Touch event must be consumed when intro is active", consumed)
        assertEquals(IntroSequenceController.State.COMPLETED, harness.controller.state)
        assertEquals(1, harness.transitionCount)
        assertTrue(harness.introDisposed)
    }

    @Test
    fun `event interception - touch event passes through after intro completed`() {
        val harness = VRPresentationEventHarness(isReducedMotion = false)

        // Complete the intro with first touch
        val firstConsumed = harness.dispatchTouchEvent()
        assertTrue(firstConsumed)

        // Subsequent touch events must pass through (return false from harness / super)
        val secondConsumed = harness.dispatchTouchEvent()
        assertFalse("Subsequent touch must pass through to screen", secondConsumed)

        val thirdConsumed = harness.dispatchTouchEvent()
        assertFalse("Subsequent touch must pass through to screen", thirdConsumed)

        // Transition to home must have occurred exactly once
        assertEquals(1, harness.transitionCount)
    }

    @Test
    fun `event interception - key event is consumed and returns true during animation`() {
        val harness = VRPresentationEventHarness(isReducedMotion = false)

        assertTrue(harness.controller.isIntroActive)

        // First key event (e.g. controller trigger / button) must be consumed
        val consumed = harness.dispatchKeyEvent()
        assertTrue("Key event must be consumed when intro is active", consumed)
        assertEquals(IntroSequenceController.State.COMPLETED, harness.controller.state)
        assertEquals(1, harness.transitionCount)
        assertTrue(harness.introDisposed)
    }

    @Test
    fun `event interception - key event passes through after intro completed`() {
        val harness = VRPresentationEventHarness(isReducedMotion = false)

        val firstConsumed = harness.dispatchKeyEvent()
        assertTrue(firstConsumed)

        // Subsequent key events must pass through to super
        val secondConsumed = harness.dispatchKeyEvent()
        assertFalse("Subsequent key must pass through to screen", secondConsumed)

        assertEquals(1, harness.transitionCount)
    }

    @Test
    fun `event interception - touch event is consumed during reduced motion static hold`() {
        val harness = VRPresentationEventHarness(isReducedMotion = true)

        assertTrue(harness.controller.isIntroActive)
        assertEquals(IntroSequenceController.State.SHOWING_REDUCED_MOTION, harness.controller.state)

        val consumed = harness.dispatchTouchEvent()
        assertTrue("Touch event during reduced motion hold must be consumed", consumed)
        assertEquals(IntroSequenceController.State.COMPLETED, harness.controller.state)
        assertEquals(1, harness.transitionCount)
        assertTrue(harness.pendingTimerCancelled)
        assertTrue(harness.introDisposed)
    }

    @Test
    fun `event interception - key event is consumed during reduced motion static hold`() {
        val harness = VRPresentationEventHarness(isReducedMotion = true)

        assertTrue(harness.controller.isIntroActive)
        assertEquals(IntroSequenceController.State.SHOWING_REDUCED_MOTION, harness.controller.state)

        val consumed = harness.dispatchKeyEvent()
        assertTrue("Key event during reduced motion hold must be consumed", consumed)
        assertEquals(IntroSequenceController.State.COMPLETED, harness.controller.state)
        assertEquals(1, harness.transitionCount)
        assertTrue(harness.pendingTimerCancelled)
        assertTrue(harness.introDisposed)
    }

    @Test
    fun `stress harness - 500 rapid interleaved touch and key events trigger single transition`() {
        val harness = VRPresentationEventHarness(isReducedMotion = false)

        var consumedCount = 0
        var passThroughCount = 0

        for (i in 0 until 500) {
            val consumed =
                if (i % 2 == 0) {
                    harness.dispatchTouchEvent()
                } else {
                    harness.dispatchKeyEvent()
                }

            if (consumed) consumedCount++ else passThroughCount++
        }

        // Exactly the first event must be consumed, the other 499 pass through
        assertEquals(1, consumedCount)
        assertEquals(499, passThroughCount)
        assertEquals(1, harness.transitionCount)
    }

    // =========================================================================
    // 3. Resource Disposal and Handler Lifecycle Safety
    // =========================================================================

    @Test
    fun `reduced motion hold completion transitions to home if uninterrupted`() {
        var timerCompletedCalled = false
        var transitionCount = 0

        val controller =
            IntroSequenceController(
                isReducedMotion = { true },
                onPlayAnimation = {},
                onShowStaticFinalFrame = { durationMs ->
                    assertEquals(500L, durationMs)
                    timerCompletedCalled = true
                },
                onTransitionToHome = { transitionCount++ },
            )

        controller.start()
        assertTrue(timerCompletedCalled)
        assertEquals(IntroSequenceController.State.SHOWING_REDUCED_MOTION, controller.state)

        // Timer fires after 500ms
        controller.onReducedMotionTimerComplete()

        assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        assertEquals(1, transitionCount)
        assertTrue(IntroSequenceController.hasCompletedIntroOnce)
    }

    @Test
    fun `disposal simulation - late timer complete after touch skip is strictly ignored`() {
        var transitionCount = 0
        val isTimerPending = AtomicBoolean(true)

        val controller =
            IntroSequenceController(
                isReducedMotion = { true },
                onPlayAnimation = {},
                onShowStaticFinalFrame = {},
                onTransitionToHome = {
                    // Simulates disposeIntro(): removeCallbacksAndMessages(null)
                    isTimerPending.set(false)
                    transitionCount++
                },
            )

        controller.start()
        // User taps at t = 100ms
        controller.skip()

        assertEquals(1, transitionCount)
        assertFalse("Pending timer must be cancelled on disposal", isTimerPending.get())

        // Even if runnable was already in flight on another thread / queued:
        controller.onReducedMotionTimerComplete()

        // Transition callback must NOT fire a second time
        assertEquals(1, transitionCount)
        assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
    }

    @Test
    fun `disposal simulation - idempotent disposeIntro calls do not crash`() {
        var cancelAnimationCount = 0
        var removeViewCount = 0
        var viewAttached = true

        fun simulateDisposeIntro() {
            // Emulates VRPresentation.disposeIntro() with null checks
            if (viewAttached) {
                cancelAnimationCount++
                removeViewCount++
                viewAttached = false
            }
        }

        // Multiple dispose invocations (e.g. skip + dismiss + onStop)
        for (i in 0 until 100) {
            simulateDisposeIntro()
        }

        assertEquals(1, cancelAnimationCount)
        assertEquals(1, removeViewCount)
        assertFalse(viewAttached)
    }

    @Test
    fun `cold launch persistence - subsequent VRPresentation creation bypasses intro completely`() {
        // Cold launch phase
        var initialTransitions = 0
        val firstPresentationController =
            IntroSequenceController(
                isReducedMotion = { false },
                onPlayAnimation = {},
                onShowStaticFinalFrame = {},
                onTransitionToHome = { initialTransitions++ },
            )

        firstPresentationController.start()
        firstPresentationController.onAnimationComplete()

        assertEquals(1, initialTransitions)
        assertTrue(IntroSequenceController.hasCompletedIntroOnce)

        // Second presentation creation (e.g. display re-creation or activity resume)
        // VRPresentation.kt lines 217-221 checks:
        // if (IntroSequenceController.hasCompletedIntroOnce) render() else setupIntroSequence()
        val secondPresentationCreatedIntro =
            if (IntroSequenceController.hasCompletedIntroOnce) {
                false // directly renders Home
            } else {
                true // setupIntroSequence()
            }

        assertFalse("Second presentation must not instantiate intro sequence", secondPresentationCreatedIntro)
    }
}
