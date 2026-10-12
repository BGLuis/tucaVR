package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Blocks literal text and colors in the UI: Android Lint only checks `HardcodedText` in XML, so
 * `Text("...")` and `view.text = "..."` in Kotlin would slip through. Earlier debt lives in the
 * baseline (`config/ui-guards/baseline.txt`); the test fails on new findings and on stale entries.
 */
class UiSourceGuardsTest {
    private data class Finding(
        val rule: String,
        val path: String,
        val snippet: String,
    ) {
        val key: String get() = "$rule|$path|$snippet"
    }

    @Test
    fun `user-facing text comes from string resources`() = assertMatchesBaseline(RULE_TEXT)

    @Test
    fun `compose colors come from BrowserColors tokens`() = assertMatchesBaseline(RULE_COLOR)

    @Test
    fun `scanner flags the literals it is supposed to flag`() {
        val source =
            """
            Text("Save")
            Text(text = "Cancel", color = x)
            Icon(contentDescription = "Back")
            label.text = "AQ: MED"
            view.setText("Hello")
            val text = "ignored declaration"
            Text(stringResource(R.string.ok))
            clock.text = "00:00"
            Text("${'$'}count")
            """.trimIndent()

        assertEquals(
            listOf("Save", "Cancel", "Back", "AQ: MED", "Hello"),
            findHardcodedText(source),
        )
    }

    @Test
    fun `scanner flags literal colors but not theme tokens`() {
        val source =
            """
            Color(0xFF123456)
            Color.White
            Color.Black.copy(alpha = 0.5f)
            Color.Transparent
            Color(VoidTheme.colorText)
            """.trimIndent()

        assertEquals(
            listOf("Color(0xFF123456)", "Color.White", "Color.Black"),
            findLiteralColors(source),
        )
    }

    private fun assertMatchesBaseline(rule: String) {
        val current = scanMainSources().filter { it.rule == rule }.map { it.key }
        val baseline = readBaseline().filter { it.startsWith("$rule|") }

        val added = minusEach(current, baseline)
        val stale = minusEach(baseline, current)

        assertTrue(
            "New '$rule' findings (use a string resource / theme token, or, only if legitimate, " +
                "add them to the baseline):\n${added.joinToString("\n")}",
            added.isEmpty(),
        )
        assertTrue(
            "'$rule' baseline entries that no longer exist (remove them from " +
                "config/ui-guards/baseline.txt):\n${stale.joinToString("\n")}",
            stale.isEmpty(),
        )
    }

    private fun scanMainSources(): List<Finding> =
        TestPaths.mainSources
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .flatMap { file ->
                val path = file.relativeTo(TestPaths.mainSources).invariantSeparatorsPath
                val source = stripComments(file.readText())
                val text = findHardcodedText(source).map { Finding(RULE_TEXT, path, it) }
                val colors =
                    if (isComposeFile(path, source)) {
                        findLiteralColors(source).map { Finding(RULE_COLOR, path, it) }
                    } else {
                        emptyList()
                    }
                text + colors
            }.toList()

    private fun readBaseline(): List<String> {
        val file = File(TestPaths.moduleDir, "config/ui-guards/baseline.txt")
        assertTrue("Baseline not found: ${file.absolutePath}", file.isFile)
        return file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    private fun minusEach(
        from: List<String>,
        remove: List<String>,
    ): List<String> {
        val pending = remove.toMutableList()
        return from.filter { !pending.remove(it) }
    }

    private fun stripComments(source: String): String =
        source
            .replace(BLOCK_COMMENT, "")
            .replace(LINE_COMMENT, "")

    private fun isComposeFile(
        path: String,
        source: String,
    ): Boolean =
        path != COLOR_TOKENS_FILE &&
            (source.contains("@Composable") || source.contains("androidx.compose.ui.graphics.Color"))

    private fun findHardcodedText(source: String): List<String> =
        TEXT_SINKS
            .flatMap { it.findAll(source).toList() }
            .distinctBy { it.groups[1]!!.range.first }
            .sortedBy { it.range.first }
            .map { it.groupValues[1] }
            .filter { hasVisibleLetters(it) }

    private fun findLiteralColors(source: String): List<String> =
        COLOR_LITERALS
            .flatMap { it.findAll(source).toList() }
            .sortedBy { it.range.first }
            .map { it.value }

    private fun hasVisibleLetters(literal: String): Boolean =
        literal
            .replace(TEMPLATE_EXPRESSION, "")
            .replace(TEMPLATE_VARIABLE, "")
            .any { it.isLetter() }

    private companion object {
        const val RULE_TEXT = "hardcoded-text"
        const val RULE_COLOR = "literal-color"
        const val COLOR_TOKENS_FILE = "com/tucavr/browser/ui/BrowserColors.kt"

        const val QUOTED = "\"((?:[^\"\\\\\\n]|\\\\.)*)\""

        val BLOCK_COMMENT = Regex("""(?m)^[ \t]*/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""(?m)^[ \t]*//.*$""")
        val TEMPLATE_EXPRESSION = Regex("""\$\{[^}]*}""")
        val TEMPLATE_VARIABLE = Regex("""\$[A-Za-z_]\w*""")

        val TEXT_SINKS =
            listOf(
                Regex("""\b(?:Text|BasicText)\(\s*(?:text\s*=\s*)?$QUOTED"""),
                Regex("""(?<!\bval )(?<!\bvar )\b(?:text|hint|contentDescription)\s*=\s*$QUOTED"""),
                Regex("""\b(?:setText|setHint|setContentDescription)\(\s*$QUOTED"""),
            )

        val COLOR_LITERALS =
            listOf(
                Regex("""\bColor\(\s*0x[0-9A-Fa-f]+[uUlL]*\s*\)"""),
                Regex(
                    """\bColor\.(?:White|Black|Gray|DarkGray|LightGray|Red|Green|Blue|""" +
                        """Yellow|Cyan|Magenta)\b""",
                ),
            )
    }
}
