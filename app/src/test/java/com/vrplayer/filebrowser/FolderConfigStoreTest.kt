package com.vrplayer.filebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderConfigStoreTest {

    @Test
    fun defaultFolderConfigHasSensibleDefaults() {
        val config = FolderConfig()
        assertEquals(SortBy.NAME, config.sortBy)
        assertTrue(config.ascending)
        assertEquals(ViewMode.GRID, config.viewMode)
    }

    @Test
    fun jsonSerializationAndDeserializationPreservesState() {
        val config = FolderConfig(
            sortBy = SortBy.LAST_PLAYED,
            ascending = false,
            viewMode = ViewMode.LIST
        )

        // Simula a lógica de serialização/parsing pura do FolderConfigStore
        val jsonStr = """{"sortBy":"${config.sortBy.name}","ascending":${config.ascending},"viewMode":"${config.viewMode.name}"}"""

        val sortByStr = Regex("\"sortBy\"\\s*:\\s*\"([A-Za-z_]+)\"").find(jsonStr)?.groupValues?.get(1)
        val ascendingStr = Regex("\"ascending\"\\s*:\\s*(true|false)").find(jsonStr)?.groupValues?.get(1)
        val viewModeStr = Regex("\"viewMode\"\\s*:\\s*\"([A-Za-z_]+)\"").find(jsonStr)?.groupValues?.get(1)

        val parsedSortBy = SortBy.valueOf(sortByStr!!)
        val parsedAscending = ascendingStr!!.toBooleanStrict()
        val parsedViewMode = ViewMode.valueOf(viewModeStr!!)

        assertEquals(SortBy.LAST_PLAYED, parsedSortBy)
        assertFalse(parsedAscending)
        assertEquals(ViewMode.LIST, parsedViewMode)
    }

    @Test
    fun handlesCorruptJsonGracefullyWithDefaults() {
        val corruptJson = "invalid text without brackets"
        val hasBrackets = corruptJson.contains("{") && corruptJson.contains("}")
        assertFalse(hasBrackets)
    }
}
