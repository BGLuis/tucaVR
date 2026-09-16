package com.tucavr

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistência das transformações da tela virtual (posição e escala no espaço 3D).
 * Salvo em SharedPreferences comuns (valores de UI/geometria, não sensíveis).
 */
class ScreenTransformStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class ScreenTransform(
        val posX: Float,
        val posY: Float,
        val posZ: Float,
        val scaleX: Float,
        val scaleY: Float,
        val isCustomized: Boolean,
    )

    fun get(): ScreenTransform {
        val posX = prefs.getFloat(KEY_POS_X, DEFAULT_POS_X)
        val posY = prefs.getFloat(KEY_POS_Y, DEFAULT_POS_Y)
        val posZ = prefs.getFloat(KEY_POS_Z, DEFAULT_POS_Z)
        val scaleX = prefs.getFloat(KEY_SCALE_X, DEFAULT_SCALE_X)
        val scaleY = prefs.getFloat(KEY_SCALE_Y, DEFAULT_SCALE_Y)
        val isCustomized = prefs.getBoolean(KEY_IS_CUSTOMIZED, false)

        val clampedScaleX = scaleX.coerceIn(MIN_SCALE_X, MAX_SCALE_X)
        val resolvedScaleY = if (scaleY > 0f) scaleY else clampedScaleX * (9.0f / 16.0f)
        return ScreenTransform(
            posX = posX.coerceIn(MIN_POS_X, MAX_POS_X),
            posY = posY.coerceIn(MIN_POS_Y, MAX_POS_Y),
            posZ = posZ.coerceIn(MIN_POS_Z, MAX_POS_Z),
            scaleX = clampedScaleX,
            scaleY = resolvedScaleY,
            isCustomized = isCustomized,
        )
    }

    fun save(posX: Float, posY: Float, posZ: Float, scaleX: Float, scaleY: Float) {
        val clampedScaleX = scaleX.coerceIn(MIN_SCALE_X, MAX_SCALE_X)
        val clampedScaleY = if (scaleY > 0f) scaleY else clampedScaleX * (9.0f / 16.0f)
        prefs.edit()
            .putFloat(KEY_POS_X, posX.coerceIn(MIN_POS_X, MAX_POS_X))
            .putFloat(KEY_POS_Y, posY.coerceIn(MIN_POS_Y, MAX_POS_Y))
            .putFloat(KEY_POS_Z, posZ.coerceIn(MIN_POS_Z, MAX_POS_Z))
            .putFloat(KEY_SCALE_X, clampedScaleX)
            .putFloat(KEY_SCALE_Y, clampedScaleY)
            .putBoolean(KEY_IS_CUSTOMIZED, true)
            .apply()
    }

    fun reset() {
        prefs.edit()
            .putFloat(KEY_POS_X, DEFAULT_POS_X)
            .putFloat(KEY_POS_Y, DEFAULT_POS_Y)
            .putFloat(KEY_POS_Z, DEFAULT_POS_Z)
            .putFloat(KEY_SCALE_X, DEFAULT_SCALE_X)
            .putFloat(KEY_SCALE_Y, DEFAULT_SCALE_Y)
            .putBoolean(KEY_IS_CUSTOMIZED, false)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "vrplayer_screen_transform_prefs"
        private const val KEY_POS_X = "screen_pos_x"
        private const val KEY_POS_Y = "screen_pos_y"
        private const val KEY_POS_Z = "screen_pos_z"
        private const val KEY_SCALE_X = "screen_scale_x"
        private const val KEY_SCALE_Y = "screen_scale_y"
        private const val KEY_IS_CUSTOMIZED = "screen_is_customized"

        // Padrão cinematográfico confortável (60.5° FOV horizontal no Quest 3)
        const val DEFAULT_POS_X = 0.0f
        const val DEFAULT_POS_Y = 1.5f
        const val DEFAULT_POS_Z = -2.4f
        const val DEFAULT_SCALE_X = 2.8f
        const val DEFAULT_SCALE_Y = 1.575f // 2.8 * (9/16)

        // Limites ergonômicos de conforto
        const val MIN_POS_X = -4.0f
        const val MAX_POS_X = 4.0f
        const val MIN_POS_Y = 0.3f
        const val MAX_POS_Y = 3.5f
        const val MIN_POS_Z = -10.0f
        const val MAX_POS_Z = -0.8f
        const val MIN_SCALE_X = 0.8f
        const val MAX_SCALE_X = 8.0f
    }
}
