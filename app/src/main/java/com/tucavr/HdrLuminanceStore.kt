package com.tucavr

import android.content.Context
import android.content.SharedPreferences

/**
 * The viewer's HDR brightness: a multiplier on the tone mapping exposure, where 1.0 is neutral.
 * The range mirrors `kHdrLuminance*` in native/include/color_grading.h.
 */
class HdrLuminanceStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun get(): Float = prefs.getFloat(KEY_LUMINANCE, DEFAULT).coerceIn(MIN, MAX)

    fun set(luminance: Float) {
        prefs.edit().putFloat(KEY_LUMINANCE, luminance.coerceIn(MIN, MAX)).apply()
    }

    companion object {
        const val MIN = 0.5f
        const val DEFAULT = 1.0f
        const val MAX = 2.0f

        private const val PREFS_NAME = "hdr_luminance"
        private const val KEY_LUMINANCE = "luminance"
    }
}
