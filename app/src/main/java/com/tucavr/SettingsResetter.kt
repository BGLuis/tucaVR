package com.tucavr

import android.content.Context
import android.content.SharedPreferences
import com.tucavr.network.NetworkPreferenceStore

/**
 * Restores all user-configurable settings to their factory defaults while preserving
 * user data (saved network servers/credentials, playback history, playlists, and downloads).
 */
object SettingsResetter {
    private val BUILTIN_ENVIRONMENT_IDS =
        listOf(
            EnvironmentStore.ENV_VOID,
            EnvironmentStore.ENV_CINEMA,
            EnvironmentStore.ENV_LIVING_ROOM,
            EnvironmentStore.ENV_SPACE,
            EnvironmentStore.ENV_PASSTHROUGH,
        )

    fun resetAll(
        featureFlagsPrefs: SharedPreferences,
        hdrLuminanceStore: HdrLuminanceStore,
        upscalingModeStore: UpscalingModeStore,
        lutStore: LutStore,
        screenTransformStore: ScreenTransformStore,
        languagePreferenceStore: LanguagePreferenceStore,
        networkPreferenceStore: NetworkPreferenceStore,
        environmentStore: EnvironmentStore? = null,
        environmentIds: Collection<String> = BUILTIN_ENVIRONMENT_IDS,
    ) {
        FeatureFlags.resetAll(featureFlagsPrefs)
        hdrLuminanceStore.set(HdrLuminanceStore.DEFAULT)
        upscalingModeStore.set(UpscalingModeStore.Mode.OFF)
        lutStore.clear()
        for (envId in (BUILTIN_ENVIRONMENT_IDS + environmentIds).distinct()) {
            screenTransformStore.reset(envId)
        }
        languagePreferenceStore.reset()
        networkPreferenceStore.reset()
        environmentStore?.reset()
    }

    fun resetAll(context: Context) {
        val envIds = EnvironmentRegistry.getAll().map { it.id }
        resetAll(
            featureFlagsPrefs = FeatureFlags.sharedPreferences(context),
            hdrLuminanceStore = HdrLuminanceStore(context),
            upscalingModeStore = UpscalingModeStore(context),
            lutStore = LutStore(context),
            screenTransformStore = ScreenTransformStore(context),
            languagePreferenceStore = LanguagePreferenceStore(context),
            networkPreferenceStore = NetworkPreferenceStore(context),
            environmentStore = EnvironmentStore(context),
            environmentIds = envIds,
        )
    }
}
