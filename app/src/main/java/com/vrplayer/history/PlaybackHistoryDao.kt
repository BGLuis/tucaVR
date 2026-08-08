package com.vrplayer.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * T9.1. Todas as funcoes sao `suspend` — Room ja bloqueia por padrao
 * qualquer query feita na main thread (`allowMainThreadQueries()` nao e
 * habilitado em [AppDatabase]), entao todo call site precisa estar numa
 * coroutine em `Dispatchers.IO` (ver `PlaybackHistoryTracker`, mesmo padrao
 * ja usado por `DirectoryLister`/`ThumbnailGenerator`, T5).
 */
@Dao
interface PlaybackHistoryDao {

    /**
     * Insere ou substitui (por [PlaybackHistory.historyKey], a chave
     * primaria) — cobre tanto "primeiro save" (T9.2, aos 10s de reproducao)
     * quanto "atualizar progresso de uma entrada ja existente".
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PlaybackHistory)

    @Query("SELECT * FROM playback_history WHERE historyKey = :key LIMIT 1")
    suspend fun findByKey(key: String): PlaybackHistory?

    /** T9.4: "Continuar assistindo" — mais recentes primeiro. */
    @Query("SELECT * FROM playback_history ORDER BY lastPlayedAt DESC")
    suspend fun listRecentFirst(): List<PlaybackHistory>

    @Query("DELETE FROM playback_history WHERE historyKey = :key")
    suspend fun deleteByKey(key: String)
}
