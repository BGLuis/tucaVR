package com.tucavr.designsystem

import com.tucavr.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM unit tests for [LanguageRegionHelper] verifying ISO mappings,
 * region extraction, title parsing, and fallback hierarchy (Issue #98).
 */
class LanguageRegionHelperTest {
    @Test
    fun `por maps to Portuguese and normalizes to pt`() {
        assertEquals("Português", LanguageRegionHelper.isoToLanguageName("por"))
        assertEquals("Português", LanguageRegionHelper.isoToLanguageName("pt"))
        assertEquals("pt", LanguageRegionHelper.normalizeLanguageCode("por"))
        assertEquals("pt", LanguageRegionHelper.normalizeLanguageCode("pt"))
    }

    @Test
    fun `pt-PT extracts region Portugal`() {
        val region = LanguageRegionHelper.extractRegionFromTag("pt-PT")
        assertEquals("PT", region?.first)
        assertEquals("Portugal", region?.second)
        assertEquals(R.drawable.flag_pt, LanguageRegionHelper.resolveFlagDrawable(region?.first))
    }

    @Test
    fun `pt applies default region Brazil`() {
        val defaultRegion = LanguageRegionHelper.defaultRegionForLanguage("pt")
        assertEquals("BR", defaultRegion?.first)
        assertEquals("Brasil", defaultRegion?.second)
        assertEquals(R.drawable.flag_br, LanguageRegionHelper.resolveFlagDrawable(defaultRegion?.first))
    }

    @Test
    fun `title parsing identifies dialect regions correctly`() {
        val br = LanguageRegionHelper.extractRegionFromTitle("Filme Nacional (Português Brasil)")
        assertEquals("BR", br?.first)

        val pt = LanguageRegionHelper.extractRegionFromTitle("Filme Europeu (Português de Portugal)")
        assertEquals("PT", pt?.first)

        val latino = LanguageRegionHelper.extractRegionFromTitle("Película en Español Latino")
        assertEquals("MX", latino?.first)

        val castellano = LanguageRegionHelper.extractRegionFromTitle("Versión en Castellano")
        assertEquals("ES", castellano?.first)
    }

    @Test
    fun `flags map to corresponding drawables and fallback to globe`() {
        assertEquals(R.drawable.flag_br, LanguageRegionHelper.resolveFlagDrawable("BR"))
        assertEquals(R.drawable.flag_pt, LanguageRegionHelper.resolveFlagDrawable("PT"))
        assertEquals(R.drawable.flag_us, LanguageRegionHelper.resolveFlagDrawable("US"))
        assertEquals(R.drawable.flag_gb, LanguageRegionHelper.resolveFlagDrawable("GB"))
        assertEquals(R.drawable.flag_es, LanguageRegionHelper.resolveFlagDrawable("ES"))
        assertEquals(R.drawable.ic_flag_mx, LanguageRegionHelper.resolveFlagDrawable("MX"))
        assertEquals(R.drawable.ic_flag_fr, LanguageRegionHelper.resolveFlagDrawable("FR"))
        assertEquals(R.drawable.ic_flag_de, LanguageRegionHelper.resolveFlagDrawable("DE"))
        assertEquals(R.drawable.flag_jp, LanguageRegionHelper.resolveFlagDrawable("JP"))
        assertEquals(R.drawable.flag_und, LanguageRegionHelper.resolveFlagDrawable("UNKNOWN"))
        assertEquals(R.drawable.flag_und, LanguageRegionHelper.resolveFlagDrawable(null))
    }

    @Test
    fun `empty language tag returns null region and normalizes to empty`() {
        assertEquals("", LanguageRegionHelper.normalizeLanguageCode(""))
        assertNull(LanguageRegionHelper.extractRegionFromTag(""))
        assertNull(LanguageRegionHelper.isoToLanguageName(""))
    }
}
