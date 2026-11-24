package com.tucavr.screens

import android.content.res.AssetManager
import android.util.Log
import java.io.InputStream

/**
 * Single translation credit row loaded from `assets/TRANSLATORS.md`.
 */
data class TranslatorCredit(
    val language: String,
    val translators: String,
    val reviewedBy: String? = null,
)

/**
 * Pure parser for `assets/TRANSLATORS.md` so community translators can be
 * credited and reviewed without modifying Kotlin code.
 */
object TranslatorsParser {
    private const val TAG = "TranslatorsParser"
    const val ASSET_FILE = "TRANSLATORS.md"

    private val SEPARATOR_CELL_REGEX = Regex("^:?-{2,}:?$")

    fun loadFromAssets(assets: AssetManager): List<TranslatorCredit> =
        try {
            assets.open(ASSET_FILE).use { stream -> parse(stream) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $ASSET_FILE from assets: ${e.message}")
            emptyList()
        }

    fun parse(input: InputStream): List<TranslatorCredit> {
        val text = input.bufferedReader(Charsets.UTF_8).readText()
        return parseText(text)
    }

    fun parseText(content: String): List<TranslatorCredit> {
        val lines = content.lines()
        val credits = mutableListOf<TranslatorCredit>()

        for (index in lines.indices) {
            val trimmed = lines[index].trim()
            if (!isTableRow(trimmed)) continue

            val cells = splitCells(trimmed)
            if (cells.size < 2 || isSeparatorRow(cells)) continue

            // In GFM tables the row immediately preceding the separator row is the header.
            val nextNonBlank =
                lines
                    .drop(index + 1)
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
            if (nextNonBlank != null && isTableRow(nextNonBlank) && isSeparatorRow(splitCells(nextNonBlank))) {
                continue
            }

            val language = cells[0]
            val translators = cells[1]
            if (language.isEmpty() || translators.isEmpty()) continue

            val reviewedBy = cells.getOrNull(2)?.let(::normalizeReviewer)
            credits.add(
                TranslatorCredit(
                    language = language,
                    translators = translators,
                    reviewedBy = reviewedBy,
                ),
            )
        }

        return credits
    }

    private fun isTableRow(trimmedLine: String): Boolean = trimmedLine.startsWith("|") && trimmedLine.count { it == '|' } >= 2

    private fun splitCells(trimmedLine: String): List<String> =
        trimmedLine
            .removePrefix("|")
            .removeSuffix("|")
            .split("|")
            .map { it.trim() }

    private fun isSeparatorRow(cells: List<String>): Boolean =
        cells.isNotEmpty() && cells.all { cell -> cell.isEmpty() || SEPARATOR_CELL_REGEX.matches(cell) }

    private fun normalizeReviewer(raw: String): String? {
        if (raw.isEmpty()) return null
        return when (raw.lowercase()) {
            "-", "--", "—", "n/a", "none" -> null
            else -> raw
        }
    }
}
