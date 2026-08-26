package com.tucavr

import android.content.Context

/**
 * Persistência do modo de Upscaling de vídeo (Vulkan-only, MQSR & SGSR1).
 * Modos:
 * 0 = OFF (Desativado)
 * 1 = QUALITY (Modo Qualidade - SGSR1 12 taps)
 * 2 = PERFORMANCE (Modo Desempenho - Render Scale 0.8x + MQSR)
 * 3 = AUTO (Modo Automático - Heurística por formato/resolução)
 */
class UpscalingModeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class Mode(val id: Int) {
        OFF(0),
        QUALITY(1),
        PERFORMANCE(2),
        AUTO(3);

        companion object {
            fun fromId(id: Int): Mode = entries.firstOrNull { it.id == id } ?: OFF
        }
    }

    fun get(): Mode {
        val id = prefs.getInt(KEY_MODE, Mode.OFF.id)
        return Mode.fromId(id)
    }

    fun set(mode: Mode) {
        prefs.edit().putInt(KEY_MODE, mode.id).apply()
    }

    companion object {
        private const val PREFS_NAME = "upscaling_preferences"
        private const val KEY_MODE = "upscaling_mode"
    }
}
