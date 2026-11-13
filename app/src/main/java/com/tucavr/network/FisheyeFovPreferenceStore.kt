package com.tucavr.network

import android.content.Context

/**
 * Sub-issue #214: Per-file fisheye lens field of view preference store.
 * Allows user-selected fisheye lens angles (180°, 190°, 200°, 220°) to persist per media key.
 */
class FisheyeFovPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(historyKey: String): Int {
        val v = prefs.getInt(historyKey, DEFAULT_FOV)
        return if (isValidFov(v)) v else DEFAULT_FOV
    }

    fun set(
        historyKey: String,
        fov: Int,
    ) {
        if (isValidFov(fov)) {
            prefs.edit().putInt(historyKey, fov).apply()
        }
    }

    fun clear(historyKey: String) {
        prefs.edit().remove(historyKey).apply()
    }

    companion object {
        const val DEFAULT_FOV = 190
        val VALID_FOVS = setOf(180, 190, 200, 220)
        private const val PREFS_NAME = "fisheye_fov_preferences"

        fun isValidFov(fov: Int): Boolean = fov in VALID_FOVS
    }
}
