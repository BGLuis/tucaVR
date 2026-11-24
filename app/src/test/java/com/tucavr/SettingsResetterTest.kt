package com.tucavr

import com.tucavr.network.NetworkPreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SettingsResetterTest {
    @Test
    fun `resetAll restores all settings stores while preserving external data prefs`() {
        val featurePrefs = FakeSharedPreferences()
        val hdrPrefs = FakeSharedPreferences()
        val upscalingPrefs = FakeSharedPreferences()
        val lutPrefs = FakeSharedPreferences()
        val transformPrefs = FakeSharedPreferences()
        val languagePrefs = FakeSharedPreferences()
        val networkPrefs = FakeSharedPreferences()
        val externalUserDataPrefs = FakeSharedPreferences()

        // Mutate every setting away from defaults
        featurePrefs.edit()
            .putBoolean(FeatureFlags.Flag.PAUSE_ON_EXIT.key, false)
            .putBoolean(FeatureFlags.Flag.NIGHT_MODE.key, true)
            .putInt(FeatureFlags.KEY_DOMINANT_HAND_MODE, FeatureFlags.DominantHandMode.LEFT.id)
            .putFloat(FeatureFlags.KEY_UI_PANEL_FOV, 100f)
            .apply()
        val hdrStore = HdrLuminanceStore(hdrPrefs).apply { set(1.75f) }
        val upscalingStore = UpscalingModeStore(upscalingPrefs).apply { set(UpscalingModeStore.Mode.QUALITY) }
        val tempDir = Files.createTempDirectory("lut-reset-test").toFile()
        val lutStore = LutStore(lutPrefs, tempDir).apply { save("cinema.cube", byteArrayOf(1, 2, 3)) }
        val screenTransformStore =
            ScreenTransformStore(transformPrefs).apply {
                save(EnvironmentStore.ENV_VOID, 0.5f, 1.8f, -3.0f, 4.0f, 2.25f)
                save(EnvironmentStore.ENV_CINEMA, 0.2f, 2.7f, -7.0f, 6.2f, 3.4875f)
            }
        val languageStore = LanguagePreferenceStore(languagePrefs).apply { set(LanguagePreferenceStore.AppLanguage.ES) }
        val networkStore = NetworkPreferenceStore(networkPrefs).apply { setConnectTimeoutSeconds(30) }
        externalUserDataPrefs.edit().putString("server_1", "fingerprint_xyz").apply()

        SettingsResetter.resetAll(
            featureFlagsPrefs = featurePrefs,
            hdrLuminanceStore = hdrStore,
            upscalingModeStore = upscalingStore,
            lutStore = lutStore,
            screenTransformStore = screenTransformStore,
            languagePreferenceStore = languageStore,
            networkPreferenceStore = networkStore,
        )

        assertTrue(featurePrefs.all.isEmpty())
        assertEquals(FeatureFlags.DominantHandMode.AUTO, FeatureFlags.getDominantHandMode(featurePrefs))
        assertEquals(HdrLuminanceStore.DEFAULT, hdrStore.get(), 0.001f)
        assertEquals(UpscalingModeStore.Mode.OFF, upscalingStore.get())
        assertFalse(lutStore.hasActiveLut())
        assertNull(lutStore.getActiveLutName())
        assertFalse(screenTransformStore.get(EnvironmentStore.ENV_VOID).isCustomized)
        assertFalse(screenTransformStore.get(EnvironmentStore.ENV_CINEMA).isCustomized)
        assertEquals(LanguagePreferenceStore.AppLanguage.AUTO, languageStore.get())
        assertEquals(NetworkPreferenceStore.DEFAULT_TIMEOUT_SECONDS, networkStore.getConnectTimeoutSeconds())

        // Unrelated user data stores remain untouched
        assertEquals("fingerprint_xyz", externalUserDataPrefs.getString("server_1", null))
        tempDir.deleteRecursively()
    }
}
