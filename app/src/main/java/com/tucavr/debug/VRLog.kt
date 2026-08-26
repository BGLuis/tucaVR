package com.tucavr.debug

import android.util.Log

/**
 * Utilitário de log da camada Kotlin com tag padronizada `VRPlayer_App`
 * e injeção automática de `[s:<sessionId>]` (N1 do plano de telemetria).
 */
object VRLog {
    private const val TAG = "VRPlayer_App"

    @Volatile
    var activeSessionId: String? = null

    private fun formatMessage(message: String): String {
        val sid = activeSessionId
        val prefix = if (!sid.isNullOrBlank()) "[s:$sid] " else "[s:--------] "
        return "$prefix$message"
    }

    fun d(message: String) {
        Log.d(TAG, formatMessage(message))
    }

    fun i(message: String) {
        Log.i(TAG, formatMessage(message))
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(TAG, formatMessage(message), throwable)
        } else {
            Log.w(TAG, formatMessage(message))
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, formatMessage(message), throwable)
        } else {
            Log.e(TAG, formatMessage(message))
        }
    }
}
