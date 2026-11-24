package com.tucavr

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

/**
 * Persists the in-app UI language override independently of the headset's system locale.
 * Stored in plain SharedPreferences like [EnvironmentStore] and [FeatureFlags].
 */
class LanguagePreferenceStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    enum class AppLanguage(val tag: String) {
        AUTO(""),
        PT_BR("pt-BR"),
        EN("en"),
        ES("es"),
        ;

        companion object {
            fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: AUTO
        }
    }

    fun get(): AppLanguage = AppLanguage.fromTag(prefs.getString(KEY_LANGUAGE_TAG, AppLanguage.AUTO.tag))

    fun set(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE_TAG, language.tag).apply()
    }

    fun reset() {
        set(AppLanguage.AUTO)
    }

    fun resolveLocale(systemLocale: Locale = Locale.getDefault()): Locale =
        when (get()) {
            AppLanguage.AUTO -> systemLocale
            AppLanguage.PT_BR -> Locale("pt", "BR")
            AppLanguage.EN -> Locale("en")
            AppLanguage.ES -> Locale("es")
        }

    fun toSubtitleLanguageTag(systemLocale: Locale = Locale.getDefault()): String = resolveLocale(systemLocale).toLanguageTag()

    fun createLocalizedContext(
        base: Context,
        systemLocale: Locale = Locale.getDefault(),
        forceOverride: Boolean = false,
    ): Context {
        val target = get()
        if (target == AppLanguage.AUTO && !forceOverride) {
            return base
        }
        val locale = resolveLocale(systemLocale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config) ?: base
    }

    companion object {
        private const val PREFS_NAME = "language_preferences"
        private const val KEY_LANGUAGE_TAG = "app_language_tag"

        fun localeFor(
            language: AppLanguage,
            systemLocale: Locale = Locale.getDefault(),
        ): Locale =
            when (language) {
                AppLanguage.AUTO -> systemLocale
                AppLanguage.PT_BR -> Locale("pt", "BR")
                AppLanguage.EN -> Locale("en")
                AppLanguage.ES -> Locale("es")
            }
    }
}
