package com.tucavr

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Persists the active 3D .cube Color LUT state across application sessions.
 * Caches the active .cube file content in [filesDir]/active_lut.cube so that playback
 * can reliably restore custom color grades even if SAF URI permissions expire across reboots.
 */
class LutStore(
    private val prefs: SharedPreferences,
    private val filesDir: File,
) {
    constructor(context: Context) : this(
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        filesDir = context.filesDir,
    )

    private val cachedLutFile: File
        get() = File(filesDir, CACHED_LUT_FILENAME)

    fun hasActiveLut(): Boolean = prefs.getBoolean(KEY_LUT_ACTIVE, false) && cachedLutFile.isFile

    fun getActiveLutName(): String? = if (hasActiveLut()) prefs.getString(KEY_LUT_NAME, null) else null

    fun getActiveTitle(): String? = getActiveLutName()

    fun getActiveLutUri(): Uri? {
        val uriStr = prefs.getString(KEY_LUT_URI, null) ?: return null
        return try {
            Uri.parse(uriStr)
        } catch (_: Exception) {
            null
        }
    }

    fun getActiveLutBytes(): ByteArray? {
        if (!hasActiveLut()) return null
        return try {
            cachedLutFile.readBytes()
        } catch (_: IOException) {
            null
        }
    }

    fun save(
        name: String,
        bytes: ByteArray,
    ) = saveLut(name, null, bytes)

    fun saveLut(
        name: String,
        uri: Uri?,
        bytes: ByteArray,
    ) {
        try {
            cachedLutFile.parentFile?.mkdirs()
            cachedLutFile.writeBytes(bytes)
            prefs.edit()
                .putBoolean(KEY_LUT_ACTIVE, true)
                .putString(KEY_LUT_NAME, name)
                .putString(KEY_LUT_URI, uri?.toString())
                .apply()
        } catch (_: IOException) {
            clear()
        }
    }

    fun clear() {
        try {
            if (cachedLutFile.exists()) {
                cachedLutFile.delete()
            }
        } catch (_: IOException) {
            // ignore
        }
        prefs.edit()
            .remove(KEY_LUT_ACTIVE)
            .remove(KEY_LUT_NAME)
            .remove(KEY_LUT_URI)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "lut_store"
        private const val KEY_LUT_ACTIVE = "lut_active"
        private const val KEY_LUT_NAME = "lut_name"
        private const val KEY_LUT_URI = "lut_uri"
        private const val CACHED_LUT_FILENAME = "active_lut.cube"
    }
}
