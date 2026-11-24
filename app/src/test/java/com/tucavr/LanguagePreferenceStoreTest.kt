package com.tucavr

import com.tucavr.LanguagePreferenceStore.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LanguagePreferenceStoreTest {
    @Test
    fun `defaults to AUTO and delegates to system locale`() {
        val store = LanguagePreferenceStore(FakeSharedPreferences())

        assertEquals(AppLanguage.AUTO, store.get())
        val systemLocale = Locale("fr", "FR")
        assertEquals(systemLocale, store.resolveLocale(systemLocale))
        assertEquals("fr-FR", store.toSubtitleLanguageTag(systemLocale))
    }

    @Test
    fun `persists and resolves supported override locales`() {
        val prefs = FakeSharedPreferences()
        val store = LanguagePreferenceStore(prefs)

        store.set(AppLanguage.PT_BR)
        assertEquals(AppLanguage.PT_BR, LanguagePreferenceStore(prefs).get())
        assertEquals(Locale("pt", "BR"), store.resolveLocale(Locale.US))
        assertEquals("pt-BR", store.toSubtitleLanguageTag(Locale.US))

        store.set(AppLanguage.EN)
        assertEquals(Locale("en"), store.resolveLocale(Locale("pt", "BR")))
        assertEquals("en", store.toSubtitleLanguageTag(Locale("pt", "BR")))

        store.set(AppLanguage.ES)
        assertEquals(Locale("es"), store.resolveLocale(Locale.US))
        assertEquals("es", store.toSubtitleLanguageTag(Locale.US))
    }

    @Test
    fun `unknown stored tag falls back to AUTO and reset restores AUTO`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("app_language_tag", "zz-ZZ").apply()
        val store = LanguagePreferenceStore(prefs)
        assertEquals(AppLanguage.AUTO, store.get())

        store.set(AppLanguage.ES)
        assertEquals(AppLanguage.ES, store.get())
        store.reset()
        assertEquals(AppLanguage.AUTO, store.get())
    }
}
