package com.vrplayer.network

import android.content.Context

/**
 * T3.4: Armazenamento de preferência de modo 3D por mídia (chave de histórico estável).
 * Permite que correções manuais de formato 3D feitas pelo usuário no player VR sobrevivam
 * entre sessões de reprodução sem depender de migração Room complexa.
 */
class Format3DPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(historyKey: String): Int? {
        val v = prefs.getInt(historyKey, -1)
        return if (v in 0..9) v else null
    }

    fun set(historyKey: String, mode: Int) {
        if (mode in 0..9) {
            prefs.edit().putInt(historyKey, mode).apply()
        }
    }

    fun clear(historyKey: String) {
        prefs.edit().remove(historyKey).apply()
    }

    companion object {
        private const val PREFS_NAME = "format3d_preferences"
    }
}
