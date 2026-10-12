package com.tucavr.designsystem

import com.tucavr.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * WCAG 2.x contrast of the color pairs the UI actually uses. The hex values are read from the
 * sources because `Color.parseColor` returns 0 on the JVM (`isReturnDefaultValues`). Pairs that are
 * not listed are not checked.
 */
class ThemeContrastTest {
    private data class ContrastPair(
        val foreground: String,
        val background: String,
        val minRatio: Double,
    )

    @Test
    fun `contrast formula matches WCAG reference values`() {
        assertEquals(21.0, contrastRatio(0xFFFFFF, 0x000000), 0.001)
        assertEquals(1.0, contrastRatio(0x336699, 0x336699), 0.001)
        assertEquals(4.54, contrastRatio(0x767676, 0xFFFFFF), 0.01)
    }

    @Test
    fun `token extraction found the whole palette`() {
        val expected = setOf("background", "surface", "surfaceAlt", "text", "textSecondary", "accent", "border")
        assertTrue("Missing tokens: ${expected - palette.keys}", palette.keys.containsAll(expected))
        assertTrue(palette.keys.containsAll(setOf("danger", "errorBanner")))
    }

    @Test
    fun `text pairs meet AA 4_5`() = assertPairs(TEXT_PAIRS)

    @Test
    fun `icon and graphic pairs meet 3_0`() = assertPairs(GRAPHIC_PAIRS)

    private fun assertPairs(pairs: List<ContrastPair>) {
        val failures =
            pairs.mapNotNull { pair ->
                val ratio = contrastRatio(color(pair.foreground), color(pair.background))
                "%s on %s = %.2f (minimum %.1f)".format(pair.foreground, pair.background, ratio, pair.minRatio)
                    .takeIf { ratio < pair.minRatio }
            }
        assertTrue("Insufficient contrast:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun color(token: String): Int = palette[token] ?: error("Unknown token: $token")

    private fun contrastRatio(
        first: Int,
        second: Int,
    ): Double {
        val (lighter, darker) = listOf(luminance(first), luminance(second)).sortedDescending()
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(rgb: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((rgb shr shift) and 0xFF) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    companion object {
        // Text on accent uses `background` (like VoidButton ACTIVE); white on accent is 2.86:1.
        private val TEXT_PAIRS =
            listOf("background", "surface", "surfaceAlt").flatMap { surface ->
                listOf("text", "textSecondary").map { ContrastPair(it, surface, 4.5) }
            } +
                listOf(
                    ContrastPair("accent", "background", 4.5),
                    ContrastPair("accent", "surface", 4.5),
                    ContrastPair("background", "accent", 4.5),
                    ContrastPair("text", "errorBanner", 4.5),
                )

        private val GRAPHIC_PAIRS =
            listOf(
                ContrastPair("danger", "surface", 3.0),
                ContrastPair("danger", "surfaceAlt", 3.0),
            )

        private lateinit var palette: Map<String, Int>

        @BeforeClass
        @JvmStatic
        fun loadPalette() {
            val themeTokens =
                parseHex(
                    file = "com/tucavr/designsystem/VoidTheme.kt",
                    pattern = Regex("""val color(\w+)\s*=\s*Color\.parseColor\("#([0-9A-Fa-f]{6})"\)"""),
                ).mapKeys { it.key.replaceFirstChar(Char::lowercaseChar) }
            val browserLiterals =
                parseHex(
                    file = "com/tucavr/browser/ui/BrowserColors.kt",
                    pattern = Regex("""val (\w+)\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)"""),
                )
            palette = themeTokens + browserLiterals
        }

        private fun parseHex(
            file: String,
            pattern: Regex,
        ): Map<String, Int> =
            pattern
                .findAll(File(TestPaths.mainSources, file).readText())
                .associate { it.groupValues[1] to it.groupValues[2].toInt(16) }
    }
}
