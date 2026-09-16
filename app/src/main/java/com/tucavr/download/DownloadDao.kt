package com.tucavr.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO para operações de persistência da fila e histórico de downloads (Fase 0.4 Seção 4).
 */
@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: Download)

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): Download?

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun listAll(): List<Download>

    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED', 'DOWNLOADING') ORDER BY createdAt ASC")
    suspend fun listActive(): List<Download>

    @Query("UPDATE downloads SET downloadedBytes = :downloadedBytes, state = :state WHERE id = :id")
    suspend fun updateProgress(id: String, downloadedBytes: Long, state: String)

    @Query("UPDATE downloads SET completedAt = :completedAt, state = :state, downloadedBytes = totalBytes WHERE id = :id")
    suspend fun updateCompleted(id: String, completedAt: Long, state: String = DownloadStatus.COMPLETED)

    @Query("UPDATE downloads SET errorMessage = :errorMessage, state = :state WHERE id = :id")
    suspend fun updateFailed(id: String, errorMessage: String, state: String = DownloadStatus.FAILED)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM downloads WHERE state = 'COMPLETED'")
    suspend fun clearCompleted()
}
