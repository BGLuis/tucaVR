package com.tucavr

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Ambient audio manager for 3D virtual environments (Phase 0.5 §3 T3.4).
 * Plays looping ambient tracks with smooth 300 ms volume transitions and ducking without clicks.
 */
class AmbientAudioManager(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())

    private var currentPlayer: MediaPlayer? = null
    private var currentEnvId: String = ""
    private var baseVolume: Float = 0.3f
    private var currentVolume: Float = 0.0f
    private var isDucked: Boolean = false

    private var activeRampRunnable: Runnable? = null
    private val pendingFadeOutPlayers = mutableListOf<MediaPlayer>()

    fun playAmbient(
        environmentId: String,
        volume: Float = 0.3f,
    ) {
        if (volume <= 0.0f) {
            stopAmbient()
            return
        }

        if (currentEnvId == environmentId && currentPlayer != null) {
            baseVolume = volume
            rampVolumeTo(AudioRampMath.calculateTargetVolume(baseVolume, isDucked))
            return
        }

        // Switching environment: fade out existing player
        fadeOutAndReleaseCurrentPlayer()

        currentEnvId = environmentId
        baseVolume = volume
        currentVolume = 0.0f

        val audioFile = EnvironmentRegistry.get(environmentId)?.ambientAudio ?: "$environmentId/ambient.ogg"
        val assetPath = if (audioFile.startsWith("environments/")) audioFile else "environments/$audioFile"
        try {
            val assetFd = context.assets.openFd(assetPath)
            val player =
                MediaPlayer().apply {
                    setDataSource(assetFd.fileDescriptor, assetFd.startOffset, assetFd.length)
                    isLooping = true
                    setVolume(0.0f, 0.0f)
                    prepare()
                    start()
                }
            assetFd.close()
            currentPlayer = player
            Log.i(TAG, "Ambiência iniciada para: $environmentId (volume base: $baseVolume)")

            val targetVolume = AudioRampMath.calculateTargetVolume(baseVolume, isDucked)
            rampVolumeTo(targetVolume)
        } catch (e: Exception) {
            Log.d(TAG, "Nenhum arquivo de áudio de ambiência encontrado para: $environmentId")
            currentPlayer = null
            currentEnvId = ""
            currentVolume = 0.0f
        }
    }

    fun setDucked(ducked: Boolean) {
        if (isDucked == ducked) return
        isDucked = ducked
        if (currentPlayer != null) {
            val targetVolume = AudioRampMath.calculateTargetVolume(baseVolume, isDucked)
            rampVolumeTo(targetVolume)
        }
    }

    fun stopAmbient(immediate: Boolean = false) {
        if (immediate) {
            cancelActiveRamp()
            val playersToRelease = pendingFadeOutPlayers.toList()
            pendingFadeOutPlayers.clear()
            for (p in playersToRelease) {
                releasePlayerImmediately(p)
            }
            releasePlayerImmediately(currentPlayer)
            currentPlayer = null
            currentEnvId = ""
            currentVolume = 0.0f
        } else {
            fadeOutAndReleaseCurrentPlayer()
        }
    }

    private fun rampVolumeTo(targetVolume: Float) {
        val player = currentPlayer ?: return
        cancelActiveRamp()

        val startVolume = currentVolume
        val startTime = SystemClock.uptimeMillis()
        val duration = AudioRampMath.DEFAULT_RAMP_DURATION_MS

        val runnable =
            object : Runnable {
                override fun run() {
                    if (currentPlayer !== player) return
                    val elapsed = SystemClock.uptimeMillis() - startTime
                    val vol = AudioRampMath.calculateRampStep(startVolume, targetVolume, elapsed, duration)
                    currentVolume = vol
                    try {
                        player.setVolume(vol, vol)
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao ajustar volume: ${e.message}")
                    }

                    if (elapsed < duration) {
                        handler.postDelayed(this, AudioRampMath.RAMP_INTERVAL_MS)
                    } else {
                        currentVolume = targetVolume
                        activeRampRunnable = null
                    }
                }
            }

        activeRampRunnable = runnable
        handler.post(runnable)
    }

    private fun fadeOutAndReleaseCurrentPlayer() {
        val player = currentPlayer ?: return
        cancelActiveRamp()
        currentPlayer = null
        currentEnvId = ""

        val startVol = currentVolume
        currentVolume = 0.0f
        pendingFadeOutPlayers.add(player)

        val startTime = SystemClock.uptimeMillis()
        val duration = AudioRampMath.DEFAULT_RAMP_DURATION_MS

        handler.post(
            object : Runnable {
                override fun run() {
                    if (!pendingFadeOutPlayers.contains(player)) return
                    val elapsed = SystemClock.uptimeMillis() - startTime
                    val vol = AudioRampMath.calculateRampStep(startVol, 0.0f, elapsed, duration)
                    try {
                        player.setVolume(vol, vol)
                    } catch (e: Exception) {
                        // Player may have been stopped already
                    }

                    if (elapsed < duration) {
                        if (pendingFadeOutPlayers.contains(player)) {
                            handler.postDelayed(this, AudioRampMath.RAMP_INTERVAL_MS)
                        }
                    } else {
                        pendingFadeOutPlayers.remove(player)
                        releasePlayerImmediately(player)
                    }
                }
            },
        )
    }

    private fun cancelActiveRamp() {
        activeRampRunnable?.let {
            handler.removeCallbacks(it)
            activeRampRunnable = null
        }
    }

    private fun releasePlayerImmediately(player: MediaPlayer?) {
        if (player == null) return
        try {
            if (player.isPlaying) {
                player.stop()
            }
            player.release()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao liberar MediaPlayer: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AmbientAudioManager"
    }
}
