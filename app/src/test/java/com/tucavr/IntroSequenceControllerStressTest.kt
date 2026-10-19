package com.tucavr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical stress harness testing concurrency, race conditions, rapid skips,
 * and boundary conditions for IntroSequenceController.
 */
class IntroSequenceControllerStressTest {
    @Before
    fun setUp() {
        IntroSequenceController.hasCompletedIntroOnce = false
    }

    @After
    fun tearDown() {
        IntroSequenceController.hasCompletedIntroOnce = false
    }

    @Test
    fun `rapid successive skips on single thread never double-transition`() {
        val transitionCount = AtomicInteger(0)
        val controller =
            IntroSequenceController(
                isReducedMotion = { false },
                onPlayAnimation = {},
                onShowStaticFinalFrame = {},
                onTransitionToHome = { transitionCount.incrementAndGet() },
            )

        controller.start()
        for (i in 0 until 1000) {
            controller.skip()
        }

        assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        assertEquals(1, transitionCount.get())
    }

    @Test
    fun `concurrent skips across 50 threads trigger exactly one transition`() {
        val iterations = 50
        for (iter in 0 until iterations) {
            IntroSequenceController.hasCompletedIntroOnce = false
            val transitionCount = AtomicInteger(0)
            val controller =
                IntroSequenceController(
                    isReducedMotion = { false },
                    onPlayAnimation = {},
                    onShowStaticFinalFrame = {},
                    onTransitionToHome = { transitionCount.incrementAndGet() },
                )

            controller.start()

            val threadCount = 50
            val executor = Executors.newFixedThreadPool(threadCount)
            val latch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)

            for (t in 0 until threadCount) {
                executor.submit {
                    try {
                        latch.await()
                        controller.skip()
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            // Release all threads simultaneously
            latch.countDown()
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
            executor.shutdown()

            assertEquals("Failed on iteration $iter", 1, transitionCount.get())
            assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        }
    }

    @Test
    fun `concurrent skip and animation complete trigger exactly one transition`() {
        val iterations = 200
        for (iter in 0 until iterations) {
            IntroSequenceController.hasCompletedIntroOnce = false
            val transitionCount = AtomicInteger(0)
            val controller =
                IntroSequenceController(
                    isReducedMotion = { false },
                    onPlayAnimation = {},
                    onShowStaticFinalFrame = {},
                    onTransitionToHome = { transitionCount.incrementAndGet() },
                )

            controller.start()

            val threadCount = 20
            val executor = Executors.newFixedThreadPool(threadCount)
            val latch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)

            for (t in 0 until threadCount) {
                executor.submit {
                    try {
                        latch.await()
                        if (t % 2 == 0) {
                            controller.skip()
                        } else {
                            controller.onAnimationComplete()
                        }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            latch.countDown()
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
            executor.shutdown()

            assertEquals("Failed on iteration $iter", 1, transitionCount.get())
            assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        }
    }

    @Test
    fun `concurrent skip and reduced motion timer complete trigger exactly one transition`() {
        val iterations = 50
        for (iter in 0 until iterations) {
            IntroSequenceController.hasCompletedIntroOnce = false
            val transitionCount = AtomicInteger(0)
            val controller =
                IntroSequenceController(
                    isReducedMotion = { true },
                    onPlayAnimation = {},
                    onShowStaticFinalFrame = {},
                    onTransitionToHome = { transitionCount.incrementAndGet() },
                )

            controller.start()

            val threadCount = 20
            val executor = Executors.newFixedThreadPool(threadCount)
            val latch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)

            for (t in 0 until threadCount) {
                executor.submit {
                    try {
                        latch.await()
                        if (t % 2 == 0) {
                            controller.skip()
                        } else {
                            controller.onReducedMotionTimerComplete()
                        }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            latch.countDown()
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
            executor.shutdown()

            assertEquals("Failed on iteration $iter", 1, transitionCount.get())
            assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        }
    }

    @Test
    fun `boundary rapid skip before start does not prevent subsequent normal start`() {
        val transitionCount = AtomicInteger(0)
        var animationPlayed = false
        val controller =
            IntroSequenceController(
                isReducedMotion = { false },
                onPlayAnimation = { animationPlayed = true },
                onShowStaticFinalFrame = {},
                onTransitionToHome = { transitionCount.incrementAndGet() },
            )

        for (i in 0 until 50) {
            controller.skip()
        }

        assertEquals(IntroSequenceController.State.IDLE, controller.state)
        assertEquals(0, transitionCount.get())
        assertFalse(animationPlayed)

        controller.start()
        assertEquals(IntroSequenceController.State.PLAYING_ANIMATION, controller.state)
        assertTrue(animationPlayed)
        assertEquals(0, transitionCount.get())
    }

    @Test
    fun `boundary redundant start calls while active do not trigger multiple home transitions`() {
        val transitionCount = AtomicInteger(0)
        val playCount = AtomicInteger(0)
        val controller =
            IntroSequenceController(
                isReducedMotion = { false },
                onPlayAnimation = { playCount.incrementAndGet() },
                onShowStaticFinalFrame = {},
                onTransitionToHome = { transitionCount.incrementAndGet() },
            )

        controller.start()
        controller.start() // redundant start
        controller.skip()

        assertEquals(IntroSequenceController.State.COMPLETED, controller.state)
        assertEquals(1, transitionCount.get())
    }
}
