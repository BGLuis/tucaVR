package com.vrplayer.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vrplayer.network.SavedServer
import com.vrplayer.network.SavedServerDao

/**
 * Banco Room principal do aplicativo:
 * - Tabela `playback_history`: historico de reproducao (schema v1).
 * - Tabela `saved_servers`: servidores de rede salvos (schema v2, T11.1).
 */
@Database(entities = [PlaybackHistory::class, SavedServer::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun savedServerDao(): SavedServerDao

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

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vrplayer_history.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
