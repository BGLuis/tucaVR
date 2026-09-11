package com.tucavr.download

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entidade Room representando um arquivo baixado ou em fila de download (Fase 0.4 Seção 4).
 */
@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["state"])
    ]
)
data class Download(
    @PrimaryKey val id: String,
    val sourceUri: String,
    val sourceType: String,
    val destinationPath: String,
    val displayName: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val state: String,
    val createdAt: Long,
    val completedAt: Long? = null,
    val errorMessage: String? = null,
    val serverId: String? = null
)

object DownloadStatus {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}
