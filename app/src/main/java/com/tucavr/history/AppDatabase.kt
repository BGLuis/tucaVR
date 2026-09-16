package com.tucavr.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tucavr.download.Download
import com.tucavr.download.DownloadDao
import com.tucavr.network.SavedServer
import com.tucavr.network.SavedServerDao
import com.tucavr.playlist.Playlist
import com.tucavr.playlist.PlaylistDao
import com.tucavr.playlist.PlaylistItem

/**
 * Banco Room principal do aplicativo:
 * - Tabela `playback_history`: historico de reproducao (schema v1).
 * - Tabela `saved_servers`: servidores de rede salvos (schema v2, T11.1).
 * - Tabelas `playlists` e `playlist_items`: listas de reproducao (schema v3, T9.1).
 * - Tabela `downloads`: fila e historico de downloads offline (schema v4, Fase 0.4 Seção 4).
 */
@Database(
    entities = [PlaybackHistory::class, SavedServer::class, Playlist::class, PlaylistItem::class, Download::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun savedServerDao(): SavedServerDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // R-05 (PHASE-0.4-08-VERIFICACAO-PROFUNDA.md): o SQL bruto de cada migração vive numa
        // lista `internal` separada do objeto `Migration`, para poder ser exercido por
        // `AppDatabaseMigrationTest` (JVM, `org.xerial:sqlite-jdbc`) sem depender de
        // `SupportSQLiteDatabase`/Robolectric — o projeto evita Robolectric de propósito (ver
        // comentário em `app/build.gradle.kts` sobre os testes de `filebrowser`). O objeto
        // `Migration` real usado pelo Room em produção só itera essa lista chamando `execSQL`.
        internal val MIGRATION_1_2_SQL = listOf(
            """
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
            )
            """.trimIndent()
        )

        internal val MIGRATION_2_3_SQL = listOf(
            """
            CREATE TABLE IF NOT EXISTS `playlists` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `name` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `itemCount` INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `playlist_items` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `playlistId` TEXT NOT NULL,
                `mediaUri` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `position` INTEGER NOT NULL,
                `sourceType` TEXT NOT NULL,
                FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_playlist_items_playlistId` ON `playlist_items` (`playlistId`)"
        )

        internal val MIGRATION_3_4_SQL = listOf(
            """
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
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_downloads_state` ON `downloads` (`state`)"
        )

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach { db.execSQL(it) }
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach { db.execSQL(it) }
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_SQL.forEach { db.execSQL(it) }
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vrplayer_history.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
