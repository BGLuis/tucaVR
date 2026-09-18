package com.tucavr.history

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Testes de migração do Room para `AppDatabase` (Fase 0.4, item R-05 de
 * `docs/reports/PHASE-0.4-08-VERIFICACAO-PROFUNDA.md` — pedido também pelo relatório anterior,
 * `PHASE-0.4-07-TRANSVERSAIS-E-DOD.md:113-115`).
 *
 * O projeto evita Robolectric de propósito (ver comentário em `app/build.gradle.kts` sobre os
 * testes de `filebrowser`), e não há source set `androidTest`/emulador disponível para rodar
 * `androidx.room:room-testing` de verdade. Em vez disso, este teste executa o SQL bruto real das
 * migrations (`AppDatabase.MIGRATION_1_2_SQL`/`MIGRATION_2_3_SQL`/`MIGRATION_3_4_SQL` — as mesmas
 * listas que os objetos `Migration` de produção iteram) contra um banco SQLite real via
 * `org.xerial:sqlite-jdbc` (driver puro-JVM, sem dependência Android), partindo de um schema v1
 * (`playback_history`) construído manualmente a partir de [PlaybackHistory] — Room não exporta
 * schema JSON aqui (`exportSchema = false`), então não há arquivo de schema v1 para reidratar.
 *
 * Cobre o critério de DoD "migração v3 → v4 preserva todos os dados" (e, por extensão, a cadeia
 * inteira 1→4): insere uma linha em `playback_history` antes de qualquer migração e confirma que
 * ela sobrevive intacta depois das três.
 */
class AppDatabaseMigrationTest {

    private lateinit var connection: Connection

    // Espelha a tabela `playback_history` real (schema v1) — ver PlaybackHistory.kt.
    private val v1Schema = """
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
        )
    """.trimIndent()

    @Before
    fun openInMemoryDatabase() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { it.execute(v1Schema) }
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

    private fun Connection.indexExists(name: String): Boolean {
        createStatement().use { stmt ->
            stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='$name'").use { rs ->
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

    private fun insertSampleHistoryRow() {
        connection.prepareStatement(
            "INSERT INTO `playback_history` " +
                "(historyKey, title, mediaPath, positionMs, durationMs, lastPlayedAt, thumbnailPath, sourceType, serverInfo) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { stmt ->
            stmt.setString(1, "local::/sdcard/Movies/clip.mp4")
            stmt.setString(2, "Clip de teste")
            stmt.setString(3, "/sdcard/Movies/clip.mp4")
            stmt.setLong(4, 12_345L)
            stmt.setLong(5, 600_000L)
            stmt.setLong(6, 1_700_000_000_000L)
            stmt.setString(7, null)
            stmt.setString(8, "LOCAL")
            stmt.setString(9, null)
            stmt.executeUpdate()
        }
    }

    private fun applyMigration(statements: List<String>) {
        connection.createStatement().use { stmt ->
            statements.forEach { stmt.execute(it) }
        }
    }

    @Test
    fun `migration 1 to 2 creates saved_servers with the expected columns`() {
        insertSampleHistoryRow()

        applyMigration(AppDatabase.MIGRATION_1_2_SQL)

        assertTrue("saved_servers deveria existir após MIGRATION_1_2", connection.tableExists("saved_servers"))
        assertEquals(
            listOf("id", "name", "protocol", "host", "port", "path", "username", "domain", "isAutoDiscovered", "lastConnectedAt", "iconUrl", "extraJson"),
            connection.columnNames("saved_servers")
        )
    }

    @Test
    fun `migration 2 to 3 creates playlists and playlist_items with FK and index`() {
        applyMigration(AppDatabase.MIGRATION_1_2_SQL)
        applyMigration(AppDatabase.MIGRATION_2_3_SQL)

        assertTrue(connection.tableExists("playlists"))
        assertTrue(connection.tableExists("playlist_items"))
        assertTrue(
            "index_playlist_items_playlistId deveria existir após MIGRATION_2_3",
            connection.indexExists("index_playlist_items_playlistId")
        )
        assertEquals(
            listOf("id", "name", "createdAt", "itemCount"),
            connection.columnNames("playlists")
        )
        assertEquals(
            listOf("id", "playlistId", "mediaUri", "title", "durationMs", "position", "sourceType"),
            connection.columnNames("playlist_items")
        )
    }

    @Test
    fun `migration 3 to 4 creates downloads with the expected columns and index`() {
        applyMigration(AppDatabase.MIGRATION_1_2_SQL)
        applyMigration(AppDatabase.MIGRATION_2_3_SQL)
        applyMigration(AppDatabase.MIGRATION_3_4_SQL)

        assertTrue("downloads deveria existir após MIGRATION_3_4", connection.tableExists("downloads"))
        assertTrue(
            "index_downloads_state deveria existir após MIGRATION_3_4",
            connection.indexExists("index_downloads_state")
        )
        assertEquals(
            listOf(
                "id", "sourceUri", "sourceType", "destinationPath", "displayName", "totalBytes",
                "downloadedBytes", "state", "createdAt", "completedAt", "errorMessage", "serverId"
            ),
            connection.columnNames("downloads")
        )
    }

    @Test
    fun `migration 4 to 5 creates folder_media_status and media_metadata_cache with the expected columns`() {
        applyMigration(AppDatabase.MIGRATION_1_2_SQL)
        applyMigration(AppDatabase.MIGRATION_2_3_SQL)
        applyMigration(AppDatabase.MIGRATION_3_4_SQL)
        applyMigration(AppDatabase.MIGRATION_4_5_SQL)

        assertTrue("folder_media_status deveria existir após MIGRATION_4_5", connection.tableExists("folder_media_status"))
        assertTrue("media_metadata_cache deveria existir após MIGRATION_4_5", connection.tableExists("media_metadata_cache"))
        assertEquals(
            listOf("folderKey", "hasPlayableMedia", "scanCompletedFully", "lastCheckedAt", "sourceKind"),
            connection.columnNames("folder_media_status")
        )
        assertEquals(
            listOf(
                "mediaKey", "container", "containerLong", "durationMs", "bitRate", "format3dIndex",
                "detectionConfidence", "videoWidth", "videoHeight", "videoCodec", "fetchedAt"
            ),
            connection.columnNames("media_metadata_cache")
        )
    }

    @Test
    fun `full migration chain 1 to 5 preserves playback_history data untouched`() {
        insertSampleHistoryRow()

        applyMigration(AppDatabase.MIGRATION_1_2_SQL)
        applyMigration(AppDatabase.MIGRATION_2_3_SQL)
        applyMigration(AppDatabase.MIGRATION_3_4_SQL)
        applyMigration(AppDatabase.MIGRATION_4_5_SQL)

        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT title, mediaPath, positionMs, durationMs, sourceType FROM playback_history").use { rs ->
                assertTrue("a linha inserida antes da migração deveria sobreviver", rs.next())
                assertEquals("Clip de teste", rs.getString("title"))
                assertEquals("/sdcard/Movies/clip.mp4", rs.getString("mediaPath"))
                assertEquals(12_345L, rs.getLong("positionMs"))
                assertEquals(600_000L, rs.getLong("durationMs"))
                assertEquals("LOCAL", rs.getString("sourceType"))
                assertFalse("deveria haver só uma linha", rs.next())
            }
        }

        // As seis tabelas de todas as versões coexistem depois da cadeia completa.
        assertTrue(connection.tableExists("playback_history"))
        assertTrue(connection.tableExists("saved_servers"))
        assertTrue(connection.tableExists("playlists"))
        assertTrue(connection.tableExists("playlist_items"))
        assertTrue(connection.tableExists("downloads"))
        assertTrue(connection.tableExists("folder_media_status"))
        assertTrue(connection.tableExists("media_metadata_cache"))
    }

    @Test
    fun `migrations are idempotent via CREATE TABLE IF NOT EXISTS`() {
        // Todas as migrations usam CREATE TABLE/INDEX IF NOT EXISTS — reaplicar não deve falhar.
        applyMigration(AppDatabase.MIGRATION_1_2_SQL)
        applyMigration(AppDatabase.MIGRATION_1_2_SQL)
        applyMigration(AppDatabase.MIGRATION_2_3_SQL)
        applyMigration(AppDatabase.MIGRATION_2_3_SQL)
        applyMigration(AppDatabase.MIGRATION_3_4_SQL)
        applyMigration(AppDatabase.MIGRATION_3_4_SQL)
        applyMigration(AppDatabase.MIGRATION_4_5_SQL)
        applyMigration(AppDatabase.MIGRATION_4_5_SQL)

        assertTrue(connection.tableExists("saved_servers"))
        assertTrue(connection.tableExists("playlists"))
        assertTrue(connection.tableExists("downloads"))
        assertTrue(connection.tableExists("folder_media_status"))
        assertTrue(connection.tableExists("media_metadata_cache"))
    }
}
