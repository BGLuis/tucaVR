package com.tucavr

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistência do identificador do ambiente virtual ativo.
 */
class EnvironmentStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getActiveEnvironment(): String {
        return prefs.getString(KEY_ACTIVE_ENV, DEFAULT_ENV) ?: DEFAULT_ENV
    }

    fun setActiveEnvironment(environmentId: String) {
        prefs.edit().putString(KEY_ACTIVE_ENV, environmentId).apply()
    }

    companion object {
        private const val PREFS_NAME = "vrplayer_environment_prefs"
        private const val KEY_ACTIVE_ENV = "active_environment_id"

        const val ENV_VOID = "void"
        const val ENV_CINEMA = "cinema"
        const val ENV_LIVING_ROOM = "living_room"
        const val ENV_SPACE = "space"
        const val ENV_PASSTHROUGH = "passthrough"

        const val DEFAULT_ENV = ENV_VOID
    }
}
