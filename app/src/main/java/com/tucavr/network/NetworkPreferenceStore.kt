package com.tucavr.network

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the default connection timeout (in seconds) used by Rust network protocol clients.
 */
class NetworkPreferenceStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun getConnectTimeoutSeconds(): Int = sanitizeTimeoutSeconds(prefs.getInt(KEY_CONNECT_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS))

    fun setConnectTimeoutSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_CONNECT_TIMEOUT_SECONDS, sanitizeTimeoutSeconds(seconds)).apply()
    }

    fun reset() {
        setConnectTimeoutSeconds(DEFAULT_TIMEOUT_SECONDS)
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 10
        val VALID_TIMEOUT_SECONDS = listOf(5, 10, 20, 30)

        private const val PREFS_NAME = "network_preferences"
        private const val KEY_CONNECT_TIMEOUT_SECONDS = "connect_timeout_seconds"

        fun sanitizeTimeoutSeconds(seconds: Int): Int = if (seconds in VALID_TIMEOUT_SECONDS) seconds else DEFAULT_TIMEOUT_SECONDS
    }
}
