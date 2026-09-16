package com.tucavr

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

/**
 * Gerenciador de áudio de ambiência para ambientes virtuais (T3.4 da Fase 0.5).
 * Toca faixas em loop suavemente e realiza ducking automático durante a reprodução de vídeo.
 */
class AmbientAudioManager(private val context: Context) {
    private var currentPlayer: MediaPlayer? = null
    private var currentEnvId: String = ""
    private var baseVolume: Float = 0.3f
    private var isDucked: Boolean = false

    fun playAmbient(environmentId: String, volume: Float = 0.3f) {
        if (currentEnvId == environmentId && currentPlayer != null) {
            baseVolume = volume
            updateVolume()
            return
        }

        stopAmbient()
        currentEnvId = environmentId
        baseVolume = volume

        val assetPath = "environments/$environmentId/ambient.ogg"
        try {
            val assetFd = context.assets.openFd(assetPath)
            currentPlayer = MediaPlayer().apply {
                setDataSource(assetFd.fileDescriptor, assetFd.startOffset, assetFd.length)
                isLooping = true
                val vol = if (isDucked) baseVolume * 0.15f else baseVolume
                setVolume(vol, vol)
                prepare()
                start()
            }
            assetFd.close()
            Log.i(TAG, "Ambiência iniciada para: $environmentId (volume: $baseVolume)")
        } catch (e: Exception) {
            // Nem todo ambiente tem áudio de ambiência (ex.: Void, Sala sem som)
            Log.d(TAG, "Nenhum arquivo de áudio de ambiência encontrado para: $environmentId")
            currentPlayer = null
        }
    }

    fun setDucked(ducked: Boolean) {
        if (isDucked == ducked) return
        isDucked = ducked
        updateVolume()
    }

    private fun updateVolume() {
        val player = currentPlayer ?: return
        val vol = if (isDucked) baseVolume * 0.15f else baseVolume
        player.setVolume(vol, vol)
    }

    fun stopAmbient() {
        try {
            currentPlayer?.stop()
            currentPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao liberar MediaPlayer de ambiência: ${e.message}")
        }
        currentPlayer = null
        currentEnvId = ""
    }

    companion object {
        private const val TAG = "AmbientAudioManager"
    }
}
