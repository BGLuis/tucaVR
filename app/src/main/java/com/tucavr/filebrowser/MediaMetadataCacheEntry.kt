package com.tucavr.filebrowser

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Cache persistido dos campos "resumo" de [MediaMetadata] (dimensões/duração/codec/container),
 * pra exibição rápida na listagem (badge) sem recalcular via Rust toda vez. `mediaKey` vem de
 * [CacheKeys.forSource]/[CacheKeys.forLocalEntry].
 *
 * Deliberadamente NÃO guarda `tags`/`tracks` completos — telas de detalhe/player continuam
 * chamando [MediaMetadataReader.read] fresco quando precisam do dado completo; este cache serve
 * só pra exibição rápida na listagem, populado em write-through por [MediaMetadataReader.read]
 * e opcionalmente por [ThumbnailGenerator]/[NetworkThumbnailGenerator] (que já descobrem
 * width/height ao decodificar um frame).
 */
@Entity(tableName = "media_metadata_cache")
data class MediaMetadataCacheEntry(
    @PrimaryKey val mediaKey: String,
    val container: String,
    val containerLong: String,
    val durationMs: Long,
    val bitRate: Long,
    val format3dIndex: Int,
    val detectionConfidence: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val videoCodec: String,
    val fetchedAt: Long
)

@Dao
interface MediaMetadataCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MediaMetadataCacheEntry)

    @Query("SELECT * FROM media_metadata_cache WHERE mediaKey = :key LIMIT 1")
    suspend fun find(key: String): MediaMetadataCacheEntry?
}
