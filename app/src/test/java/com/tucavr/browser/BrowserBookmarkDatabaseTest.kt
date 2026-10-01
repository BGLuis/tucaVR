package com.tucavr.browser

import com.tucavr.history.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * JVM tests for the [AppDatabase] v5 -> v6 migration, the `browser_bookmarks` table,
 * CRUD/reordering operations, and default presets.
 */
class BrowserBookmarkDatabaseTest {

    private lateinit var connection: Connection

    private val v5Schema = """
        CREATE TABLE IF NOT EXISTS `playback_history` (
            `historyKey` TEXT NOT NULL PRIMARY KEY,
            `title` TEXT NOT NULL,
            `mediaPath` TEXT NOT NULL,
            `positionMs` INTEGER NOT NULL,
            `durationMs` INTEGER NOT NULL,
            `lastPlayedAt` INTEGER NOT NULL,
            `thumbnailPath` TEXT,
            `sourceType` TEXT NOT NULL,
            `serverInfo` TEXT
        );
        CREATE TABLE IF NOT EXISTS `saved_servers` (
            `id` TEXT NOT NULL PRIMARY KEY,
            `name` TEXT NOT NULL,
            `protocol` TEXT NOT NULL,
            `host` TEXT NOT NULL,
            `port` INTEGER NOT NULL,
            `path` TEXT NOT NULL,
            `username` TEXT NOT NULL,
            `domain` TEXT NOT NULL,
            `isAutoDiscovered` INTEGER NOT NULL,
            `lastConnectedAt` INTEGER,
            `iconUrl` TEXT,
            `extraJson` TEXT
        );
        CREATE TABLE IF NOT EXISTS `playlists` (
            `id` TEXT NOT NULL PRIMARY KEY,
            `name` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `itemCount` INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS `playlist_items` (
            `id` TEXT NOT NULL PRIMARY KEY,
            `playlistId` TEXT NOT NULL,
            `mediaUri` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `durationMs` INTEGER NOT NULL,
            `position` INTEGER NOT NULL,
            `sourceType` TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS `downloads` (
            `id` TEXT NOT NULL PRIMARY KEY,
            `sourceUri` TEXT NOT NULL,
            `sourceType` TEXT NOT NULL,
            `destinationPath` TEXT NOT NULL,
            `displayName` TEXT NOT NULL,
            `totalBytes` INTEGER NOT NULL,
            `downloadedBytes` INTEGER NOT NULL,
            `state` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `completedAt` INTEGER,
            `errorMessage` TEXT,
            `serverId` TEXT
        );
        CREATE TABLE IF NOT EXISTS `folder_media_status` (
            `folderKey` TEXT NOT NULL PRIMARY KEY,
            `hasPlayableMedia` INTEGER NOT NULL,
            `scanCompletedFully` INTEGER NOT NULL,
            `lastCheckedAt` INTEGER NOT NULL,
            `sourceKind` TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS `media_metadata_cache` (
            `mediaKey` TEXT NOT NULL PRIMARY KEY,
            `container` TEXT NOT NULL,
            `containerLong` TEXT NOT NULL,
            `durationMs` INTEGER NOT NULL,
            `bitRate` INTEGER NOT NULL,
            `format3dIndex` INTEGER NOT NULL,
            `detectionConfidence` INTEGER NOT NULL,
            `videoWidth` INTEGER NOT NULL,
            `videoHeight` INTEGER NOT NULL,
            `videoCodec` TEXT NOT NULL,
            `fetchedAt` INTEGER NOT NULL
        );
    """.trimIndent()

    @Before
    fun openInMemoryDatabase() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            v5Schema.split(";").forEach { sql ->
                if (sql.isNotBlank()) {
                    stmt.execute(sql)
                }
            }
        }
    }

    @After
    fun closeDatabase() {
        connection.close()
    }

    private fun Connection.tableExists(name: String): Boolean {
        createStatement().use { stmt ->
            stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'").use { rs ->
                return rs.next()
            }
        }
    }

    private fun Connection.columnNames(table: String): List<String> {
        val cols = mutableListOf<String>()
        createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                while (rs.next()) {
                    cols.add(rs.getString("name"))
                }
            }
        }
        return cols
    }

    private fun applyMigration(statements: List<String>) {
        connection.createStatement().use { stmt ->
            statements.forEach { stmt.execute(it) }
        }
    }

    @Test
    fun `migration 5 to 6 creates browser_bookmarks with expected columns`() {
        applyMigration(AppDatabase.MIGRATION_5_6_SQL)

        assertTrue("browser_bookmarks should exist after MIGRATION_5_6", connection.tableExists("browser_bookmarks"))
        assertEquals(
            listOf("id", "title", "url", "favicon_url", "display_order", "is_preset", "created_at_ms"),
            connection.columnNames("browser_bookmarks")
        )
    }

    @Test
    fun `browser_bookmarks supports insert, select, update, reorder and delete`() {
        applyMigration(AppDatabase.MIGRATION_5_6_SQL)

        // Insert
        connection.prepareStatement(
            """
            INSERT INTO browser_bookmarks (title, url, favicon_url, display_order, is_preset, created_at_ms)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, "YouTube")
            stmt.setString(2, "https://www.youtube.com")
            stmt.setString(3, "https://www.youtube.com/favicon.ico")
            stmt.setInt(4, 0)
            stmt.setInt(5, 1)
            stmt.setLong(6, 1000L)
            stmt.executeUpdate()

            stmt.setString(1, "Netflix")
            stmt.setString(2, "https://www.netflix.com")
            stmt.setString(3, null)
            stmt.setInt(4, 1)
            stmt.setInt(5, 1)
            stmt.setLong(6, 1001L)
            stmt.executeUpdate()
        }

        // Select count
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM browser_bookmarks").use { rs ->
                assertTrue(rs.next())
                assertEquals(2, rs.getInt(1))
            }
        }

        // Update display_order
        connection.prepareStatement("UPDATE browser_bookmarks SET display_order = ? WHERE title = ?").use { stmt ->
            stmt.setInt(1, 10)
            stmt.setString(2, "YouTube")
            stmt.executeUpdate()
        }

        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT display_order FROM browser_bookmarks WHERE title = 'YouTube'").use { rs ->
                assertTrue(rs.next())
                assertEquals(10, rs.getInt(1))
            }
        }

        // Delete
        connection.prepareStatement("DELETE FROM browser_bookmarks WHERE title = ?").use { stmt ->
            stmt.setString(1, "YouTube")
            stmt.executeUpdate()
        }

        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM browser_bookmarks").use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt(1))
            }
        }
    }

    @Test
    fun `default presets contain expected platforms`() {
        val presets = AppDatabase.DEFAULT_PRESETS
        assertEquals(5, presets.size)

        val titles = presets.map { it.title }
        assertTrue(titles.contains("YouTube"))
        assertTrue(titles.contains("Netflix"))
        assertTrue(titles.contains("Disney+"))
        assertTrue(titles.contains("Prime Video"))
        assertTrue(titles.contains("Twitch"))

        assertTrue(presets.all { it.isPreset })
    }
}
