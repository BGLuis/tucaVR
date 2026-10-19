package com.tucavr

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings

/**
 * State controller for the VR panel intro animation sequence (Epic #102 / R4 / #106).
 * Pure Kotlin logic, fully testable on JVM without Android UI or Robolectric.
 */
class IntroSequenceController(
    private val isReducedMotion: () -> Boolean = { false },
    private val onPlayAnimation: () -> Unit,
    private val onShowStaticFinalFrame: (durationMs: Long) -> Unit,
    private val onTransitionToHome: () -> Unit,
) {
    enum class State {
        IDLE,
        PLAYING_ANIMATION,
        SHOWING_REDUCED_MOTION,
        COMPLETED,
    }

    @Volatile
    var state: State = State.IDLE
        private set

    val isIntroActive: Boolean
        get() = state == State.PLAYING_ANIMATION || state == State.SHOWING_REDUCED_MOTION

    companion object {
        const val REDUCED_MOTION_DURATION_MS = 500L
        const val NORMAL_ANIMATION_FRAMES = 96
        const val NORMAL_ANIMATION_FPS = 60

        // Process-level execution flag ensuring one-time execution across cold launch
        @Volatile
        var hasCompletedIntroOnce: Boolean = false

        /**
         * Inspects system accessibility and animation settings to detect reduced motion.
         */
        fun isReducedMotionEnabled(context: Context): Boolean {
            val resolver = context.contentResolver
            val transitionScale =
                runCatching {
                    Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f)
                }.getOrDefault(1.0f)
            val animatorScale =
                runCatching {
                    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
                }.getOrDefault(1.0f)
            val animatorsEnabled =
                runCatching {
                    ValueAnimator.areAnimatorsEnabled()
                }.getOrDefault(true)

            return transitionScale == 0.0f || animatorScale == 0.0f || !animatorsEnabled
        }
    }

    @Synchronized
    fun start(forceReplay: Boolean = false) {
        if (isIntroActive) return
        if (hasCompletedIntroOnce && !forceReplay) {
            state = State.COMPLETED
            onTransitionToHome()
            return
        }

        if (isReducedMotion()) {
            state = State.SHOWING_REDUCED_MOTION
            onShowStaticFinalFrame(REDUCED_MOTION_DURATION_MS)
        } else {
            state = State.PLAYING_ANIMATION
            onPlayAnimation()
        }
    }

    @Synchronized
    fun skip() {
        if (!isIntroActive) return
        finish()
    }

    @Synchronized
    fun onAnimationComplete() {
        if (state == State.PLAYING_ANIMATION) {
            finish()
        }
    }

    @Synchronized
    fun onReducedMotionTimerComplete() {
        if (state == State.SHOWING_REDUCED_MOTION) {
            finish()
        }
    }

    @Synchronized
    private fun finish() {
        if (!isIntroActive) return
        state = State.COMPLETED
        hasCompletedIntroOnce = true
        onTransitionToHome()
    }

    @Synchronized
    fun resetForTests() {
        state = State.IDLE
        hasCompletedIntroOnce = false
    }
}
