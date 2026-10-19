package com.tucavr

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistência das transformações da tela virtual (posição e escala no espaço 3D).
 * Salvo em SharedPreferences comuns (valores de UI/geometria, não sensíveis).
 * As transformações são indexadas por ambiente virtual para preservar os ajustes do usuário.
 */
class ScreenTransformStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    data class ScreenTransform(
        val posX: Float,
        val posY: Float,
        val posZ: Float,
        val scaleX: Float,
        val scaleY: Float,
        val isCustomized: Boolean,
    )

    fun get(environmentId: String = EnvironmentStore.DEFAULT_ENV): ScreenTransform {
        val defaultTransform = getDefaultTransform(environmentId)
        val envKeyIsCustomized = key(environmentId, KEY_IS_CUSTOMIZED_SUFFIX)

        val isCustomized =
            if (prefs.contains(envKeyIsCustomized)) {
                prefs.getBoolean(envKeyIsCustomized, false)
            } else if (environmentId == EnvironmentStore.DEFAULT_ENV && prefs.contains(KEY_LEGACY_IS_CUSTOMIZED)) {
                prefs.getBoolean(KEY_LEGACY_IS_CUSTOMIZED, false)
            } else {
                false
            }

        if (!isCustomized) {
            return defaultTransform
        }

        val posX =
            if (prefs.contains(key(environmentId, KEY_POS_X_SUFFIX))) {
                prefs.getFloat(key(environmentId, KEY_POS_X_SUFFIX), defaultTransform.posX)
            } else if (environmentId == EnvironmentStore.DEFAULT_ENV) {
                prefs.getFloat(KEY_LEGACY_POS_X, defaultTransform.posX)
            } else {
                defaultTransform.posX
            }

        val posY =
            if (prefs.contains(key(environmentId, KEY_POS_Y_SUFFIX))) {
                prefs.getFloat(key(environmentId, KEY_POS_Y_SUFFIX), defaultTransform.posY)
            } else if (environmentId == EnvironmentStore.DEFAULT_ENV) {
                prefs.getFloat(KEY_LEGACY_POS_Y, defaultTransform.posY)
            } else {
                defaultTransform.posY
            }

        val posZ =
            if (prefs.contains(key(environmentId, KEY_POS_Z_SUFFIX))) {
                prefs.getFloat(key(environmentId, KEY_POS_Z_SUFFIX), defaultTransform.posZ)
            } else if (environmentId == EnvironmentStore.DEFAULT_ENV) {
                prefs.getFloat(KEY_LEGACY_POS_Z, defaultTransform.posZ)
            } else {
                defaultTransform.posZ
            }

        val scaleX =
            if (prefs.contains(key(environmentId, KEY_SCALE_X_SUFFIX))) {
                prefs.getFloat(key(environmentId, KEY_SCALE_X_SUFFIX), defaultTransform.scaleX)
            } else if (environmentId == EnvironmentStore.DEFAULT_ENV) {
                prefs.getFloat(KEY_LEGACY_SCALE_X, defaultTransform.scaleX)
            } else {
                defaultTransform.scaleX
            }

        val scaleY =
            if (prefs.contains(key(environmentId, KEY_SCALE_Y_SUFFIX))) {
                prefs.getFloat(key(environmentId, KEY_SCALE_Y_SUFFIX), defaultTransform.scaleY)
            } else if (environmentId == EnvironmentStore.DEFAULT_ENV) {
                prefs.getFloat(KEY_LEGACY_SCALE_Y, defaultTransform.scaleY)
            } else {
                defaultTransform.scaleY
            }

        val clampedScaleX = scaleX.coerceIn(MIN_SCALE_X, MAX_SCALE_X)
        val resolvedScaleY = if (scaleY > 0f) scaleY else clampedScaleX * (9.0f / 16.0f)
        return ScreenTransform(
            posX = posX.coerceIn(MIN_POS_X, MAX_POS_X),
            posY = posY.coerceIn(MIN_POS_Y, MAX_POS_Y),
            posZ = posZ.coerceIn(MIN_POS_Z, MAX_POS_Z),
            scaleX = clampedScaleX,
            scaleY = resolvedScaleY,
            isCustomized = true,
        )
    }

    fun save(
        environmentId: String,
        posX: Float,
        posY: Float,
        posZ: Float,
        scaleX: Float,
        scaleY: Float,
    ) {
        val clampedScaleX = scaleX.coerceIn(MIN_SCALE_X, MAX_SCALE_X)
        val clampedScaleY = if (scaleY > 0f) scaleY else clampedScaleX * (9.0f / 16.0f)
        prefs.edit()
            .putFloat(key(environmentId, KEY_POS_X_SUFFIX), posX.coerceIn(MIN_POS_X, MAX_POS_X))
            .putFloat(key(environmentId, KEY_POS_Y_SUFFIX), posY.coerceIn(MIN_POS_Y, MAX_POS_Y))
            .putFloat(key(environmentId, KEY_POS_Z_SUFFIX), posZ.coerceIn(MIN_POS_Z, MAX_POS_Z))
            .putFloat(key(environmentId, KEY_SCALE_X_SUFFIX), clampedScaleX)
            .putFloat(key(environmentId, KEY_SCALE_Y_SUFFIX), clampedScaleY)
            .putBoolean(key(environmentId, KEY_IS_CUSTOMIZED_SUFFIX), true)
            .apply()
    }

    fun save(
        posX: Float,
        posY: Float,
        posZ: Float,
        scaleX: Float,
        scaleY: Float,
    ) {
        save(EnvironmentStore.DEFAULT_ENV, posX, posY, posZ, scaleX, scaleY)
    }

    fun reset(environmentId: String = EnvironmentStore.DEFAULT_ENV) {
        val defaultTransform = getDefaultTransform(environmentId)
        val editor =
            prefs.edit()
                .putFloat(key(environmentId, KEY_POS_X_SUFFIX), defaultTransform.posX)
                .putFloat(key(environmentId, KEY_POS_Y_SUFFIX), defaultTransform.posY)
                .putFloat(key(environmentId, KEY_POS_Z_SUFFIX), defaultTransform.posZ)
                .putFloat(key(environmentId, KEY_SCALE_X_SUFFIX), defaultTransform.scaleX)
                .putFloat(key(environmentId, KEY_SCALE_Y_SUFFIX), defaultTransform.scaleY)
                .putBoolean(key(environmentId, KEY_IS_CUSTOMIZED_SUFFIX), false)

        if (environmentId == EnvironmentStore.DEFAULT_ENV) {
            editor
                .putFloat(KEY_LEGACY_POS_X, defaultTransform.posX)
                .putFloat(KEY_LEGACY_POS_Y, defaultTransform.posY)
                .putFloat(KEY_LEGACY_POS_Z, defaultTransform.posZ)
                .putFloat(KEY_LEGACY_SCALE_X, defaultTransform.scaleX)
                .putFloat(KEY_LEGACY_SCALE_Y, defaultTransform.scaleY)
                .putBoolean(KEY_LEGACY_IS_CUSTOMIZED, false)
        }
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "vrplayer_screen_transform_prefs"
        private const val KEY_LEGACY_POS_X = "screen_pos_x"
        private const val KEY_LEGACY_POS_Y = "screen_pos_y"
        private const val KEY_LEGACY_POS_Z = "screen_pos_z"
        private const val KEY_LEGACY_SCALE_X = "screen_scale_x"
        private const val KEY_LEGACY_SCALE_Y = "screen_scale_y"
        private const val KEY_LEGACY_IS_CUSTOMIZED = "screen_is_customized"

        private const val KEY_POS_X_SUFFIX = "pos_x"
        private const val KEY_POS_Y_SUFFIX = "pos_y"
        private const val KEY_POS_Z_SUFFIX = "pos_z"
        private const val KEY_SCALE_X_SUFFIX = "scale_x"
        private const val KEY_SCALE_Y_SUFFIX = "scale_y"
        private const val KEY_IS_CUSTOMIZED_SUFFIX = "is_customized"

        fun key(
            environmentId: String,
            suffix: String,
        ): String = "screen_${environmentId}_$suffix"

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

        fun getDefaultTransform(environmentId: String): ScreenTransform {
            val env = EnvironmentRegistry.get(environmentId)
            return if (env != null) {
                ScreenTransform(
                    posX = env.screenPosX,
                    posY = env.screenPosY,
                    posZ = env.screenPosZ,
                    scaleX = env.screenScaleX,
                    scaleY = env.screenScaleY,
                    isCustomized = false,
                )
            } else {
                ScreenTransform(
                    posX = DEFAULT_POS_X,
                    posY = DEFAULT_POS_Y,
                    posZ = DEFAULT_POS_Z,
                    scaleX = DEFAULT_SCALE_X,
                    scaleY = DEFAULT_SCALE_Y,
                    isCustomized = false,
                )
            }
        }
    }
}
