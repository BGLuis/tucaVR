package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientAudioRampTest {
    @Test
    fun testRampStepEndpoints() {
        // At start (elapsed = 0)
        val startVal = AudioRampMath.calculateRampStep(fromVolume = 0.1f, toVolume = 0.5f, elapsedMs = 0L)
        assertEquals(0.1f, startVal, 0.0001f)

        // Negative elapsed should also return fromVolume
        val negVal = AudioRampMath.calculateRampStep(fromVolume = 0.1f, toVolume = 0.5f, elapsedMs = -50L)
        assertEquals(0.1f, negVal, 0.0001f)

        // At end (elapsed = duration)
        val endVal = AudioRampMath.calculateRampStep(fromVolume = 0.1f, toVolume = 0.5f, elapsedMs = 300L)
        assertEquals(0.5f, endVal, 0.0001f)

        // Beyond end (elapsed > duration)
        val beyondVal = AudioRampMath.calculateRampStep(fromVolume = 0.1f, toVolume = 0.5f, elapsedMs = 500L)
        assertEquals(0.5f, beyondVal, 0.0001f)
    }

    @Test
    fun testRampStepLinearInterpolation() {
        val from = 0.0f
        val to = 0.8f
        val duration = 300L

        // 25% progress (75 ms)
        val val25 = AudioRampMath.calculateRampStep(from, to, 75L, duration)
        assertEquals(0.2f, val25, 0.0001f)

        // 50% progress (150 ms)
        val val50 = AudioRampMath.calculateRampStep(from, to, 150L, duration)
        assertEquals(0.4f, val50, 0.0001f)

        // 75% progress (225 ms)
        val val75 = AudioRampMath.calculateRampStep(from, to, 225L, duration)
        assertEquals(0.6f, val75, 0.0001f)
    }

    @Test
    fun testRampStepFadeOut() {
        val from = 1.0f
        val to = 0.0f
        val duration = 300L

        // 50% fade out
        val half = AudioRampMath.calculateRampStep(from, to, 150L, duration)
        assertEquals(0.5f, half, 0.0001f)

        // 100% fade out
        val complete = AudioRampMath.calculateRampStep(from, to, 300L, duration)
        assertEquals(0.0f, complete, 0.0001f)
    }

    @Test
    fun testZeroOrNegativeDuration() {
        val resZero = AudioRampMath.calculateRampStep(fromVolume = 0.2f, toVolume = 0.6f, elapsedMs = 50L, totalDurationMs = 0L)
        assertEquals(0.6f, resZero, 0.0001f)

        val resNeg = AudioRampMath.calculateRampStep(fromVolume = 0.2f, toVolume = 0.6f, elapsedMs = 50L, totalDurationMs = -100L)
        assertEquals(0.6f, resNeg, 0.0001f)
    }

    @Test
    fun testRampStepClamping() {
        // Values outside [0.0, 1.0] should be clamped
        val clampedHigh = AudioRampMath.calculateRampStep(fromVolume = 1.5f, toVolume = 2.0f, elapsedMs = 150L, totalDurationMs = 300L)
        assertEquals(1.0f, clampedHigh, 0.0001f)

        val clampedLow = AudioRampMath.calculateRampStep(fromVolume = -0.5f, toVolume = -0.1f, elapsedMs = 150L, totalDurationMs = 300L)
        assertEquals(0.0f, clampedLow, 0.0001f)
    }

    @Test
    fun testTargetVolumeCalculations() {
        // Unducked
        val unducked = AudioRampMath.calculateTargetVolume(baseVolume = 0.3f, isDucked = false)
        assertEquals(0.3f, unducked, 0.0001f)

        // Ducked with default factor (0.15)
        val ducked = AudioRampMath.calculateTargetVolume(baseVolume = 0.3f, isDucked = true)
        assertEquals(0.045f, ducked, 0.0001f)

        // Ducked with custom factor
        val customDucked = AudioRampMath.calculateTargetVolume(baseVolume = 0.4f, isDucked = true, duckFactor = 0.25f)
        assertEquals(0.1f, customDucked, 0.0001f)

        // Clamped inputs
        val clampedTarget = AudioRampMath.calculateTargetVolume(baseVolume = 1.5f, isDucked = false)
        assertEquals(1.0f, clampedTarget, 0.0001f)
    }

    @Test
    fun testStepCount() {
        val steps = AudioRampMath.calculateStepCount(totalDurationMs = 300L, intervalMs = 20L)
        assertEquals(15, steps)

        val stepsDefault = AudioRampMath.calculateStepCount()
        assertEquals(15, stepsDefault)

        val stepsEdge = AudioRampMath.calculateStepCount(totalDurationMs = 0L, intervalMs = 20L)
        assertEquals(1, stepsEdge)
    }
}
