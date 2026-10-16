package com.tucavr

/**
 * Pure mathematical calculations for ambient audio volume ramps and ducking (Phase 0.5 §3 T3.4).
 * Completely decoupled from Android runtime APIs to enable deterministic JVM testing.
 */
object AudioRampMath {
    const val DEFAULT_RAMP_DURATION_MS = 300L
    const val RAMP_INTERVAL_MS = 20L
    const val DUCK_FACTOR = 0.15f

    /**
     * Calculates the intermediate volume step for a linear ramp.
     *
     * @param fromVolume Starting volume [0.0f .. 1.0f]
     * @param toVolume Target volume [0.0f .. 1.0f]
     * @param elapsedMs Elapsed duration in milliseconds
     * @param totalDurationMs Total ramp duration in milliseconds
     * @return Interpolated volume clamped between 0.0f and 1.0f
     */
    fun calculateRampStep(
        fromVolume: Float,
        toVolume: Float,
        elapsedMs: Long,
        totalDurationMs: Long = DEFAULT_RAMP_DURATION_MS,
    ): Float {
        val clampedTo = toVolume.coerceIn(0.0f, 1.0f)
        val clampedFrom = fromVolume.coerceIn(0.0f, 1.0f)

        if (totalDurationMs <= 0L || elapsedMs >= totalDurationMs) {
            return clampedTo
        }
        if (elapsedMs <= 0L) {
            return clampedFrom
        }

        val progress = (elapsedMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0.0f, 1.0f)
        val interpolated = clampedFrom + (clampedTo - clampedFrom) * progress
        return interpolated.coerceIn(0.0f, 1.0f)
    }

    /**
     * Calculates the effective target volume considering ducking state.
     */
    fun calculateTargetVolume(
        baseVolume: Float,
        isDucked: Boolean,
        duckFactor: Float = DUCK_FACTOR,
    ): Float {
        val clampedBase = baseVolume.coerceIn(0.0f, 1.0f)
        val clampedFactor = duckFactor.coerceIn(0.0f, 1.0f)
        return if (isDucked) {
            (clampedBase * clampedFactor).coerceIn(0.0f, 1.0f)
        } else {
            clampedBase
        }
    }

    /**
     * Calculates the total number of update steps for a given ramp duration.
     */
    fun calculateStepCount(
        totalDurationMs: Long = DEFAULT_RAMP_DURATION_MS,
        intervalMs: Long = RAMP_INTERVAL_MS,
    ): Int {
        if (totalDurationMs <= 0L || intervalMs <= 0L) return 1
        return (totalDurationMs / intervalMs).toInt().coerceAtLeast(1)
    }
}
