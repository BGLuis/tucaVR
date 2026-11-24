package com.tucavr.screens

import com.tucavr.BuildConfig
import com.tucavr.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Properties

class TranslatorsParserTest {
    @Test
    fun `parses bundled TRANSLATORS_md asset`() {
        val assetFile = File(TestPaths.moduleDir, "src/main/assets/${TranslatorsParser.ASSET_FILE}")
        assertTrue("Expected ${assetFile.path} to exist", assetFile.isFile)

        val credits = assetFile.inputStream().use { TranslatorsParser.parse(it) }

        assertEquals(
            listOf(
                TranslatorCredit(
                    language = "Português (Brasil)",
                    translators = "Luis Henrique G Barbosa",
                    reviewedBy = "Luis Henrique G Barbosa",
                ),
                TranslatorCredit(
                    language = "Español",
                    translators = "Luis Henrique G Barbosa",
                    reviewedBy = null,
                ),
            ),
            credits,
        )
    }

    @Test
    fun `parses 3-column markdown table and normalizes unreviewed markers`() {
        val markdown =
            """
            # Translators

            Intro paragraph that should be ignored.

            | Language | Translators | Reviewed by |
            | :--- | --- | ---: |
            | Français | Alice, Bob | Claire |
            | Deutsch | Dana | — |
            | 日本語 | Kenji | - |
            | Italiano | Marco |   |
            | Polski | Zofia | N/A |
            """.trimIndent()

        val credits = TranslatorsParser.parse(ByteArrayInputStream(markdown.toByteArray(Charsets.UTF_8)))

        assertEquals(5, credits.size)
        assertEquals(TranslatorCredit("Français", "Alice, Bob", "Claire"), credits[0])
        assertNull(credits[1].reviewedBy)
        assertNull(credits[2].reviewedBy)
        assertNull(credits[3].reviewedBy)
        assertNull(credits[4].reviewedBy)
    }

    @Test
    fun `parses 2-column table and skips malformed or empty rows`() {
        val markdown =
            """
            | Language | Translators |
            | --- | --- |
            | 한국어 | Min-jun |
            | | Missing language |
            | Missing translator | |
            | OnlyOneCell |
            not a table line
            """.trimIndent()

        val credits = TranslatorsParser.parseText(markdown)

        assertEquals(listOf(TranslatorCredit("한국어", "Min-jun", null)), credits)
    }

    @Test
    fun `empty stream returns empty list`() {
        val credits = TranslatorsParser.parse(ByteArrayInputStream(ByteArray(0)))
        assertTrue(credits.isEmpty())
    }

    @Test
    fun `BuildConfig version matches version_properties`() {
        val propsFile = File(TestPaths.moduleDir.parentFile, "version.properties")
        assertTrue("Expected ${propsFile.path} to exist", propsFile.isFile)
        val props = Properties().apply { propsFile.inputStream().use { load(it) } }

        assertEquals(props.getProperty("versionName"), BuildConfig.VERSION_NAME)
        assertEquals(props.getProperty("versionCode").toInt(), BuildConfig.VERSION_CODE)
    }
}
