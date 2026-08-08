package com.vrplayer.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * T9.1: unico banco Room do app ate agora — so a tabela `playback_history`.
 * `exportSchema = false` porque este projeto nao versiona schemas Room em
 * disco (nao ha diretorio `schemas/` configurado em `app/build.gradle.kts`);
 * se o app crescer a ponto de precisar de migracoes formais mais pra frente,
 * isso deveria ser revisitado junto com um `Migration` de verdade em vez de
 * `fallbackToDestructiveMigration()`.
 */
@Database(entities = [PlaybackHistory::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playbackHistoryDao(): PlaybackHistoryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vrplayer_history.db"
                )
                    // Sem migrations formais ainda (v0.1 do app, schema v1
                    // desde o inicio) — se uma v2 for necessaria no futuro,
                    // substituir por uma `Migration` real em vez disto.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
