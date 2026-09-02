package com.tucavr.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
 */
@Database(
    entities = [PlaybackHistory::class, SavedServer::class, Playlist::class, PlaylistItem::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun savedServerDao(): SavedServerDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlists` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `itemCount` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
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
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_items_playlistId` ON `playlist_items` (`playlistId`)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vrplayer_history.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
