package com.tucavr.filebrowser

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Resultado persistido de "esta pasta tem alguma mídia reproduzível (recursivamente)?" —
 * usado por [MediaFilterEngine] pra podar pastas vazias sem precisar reconectar/relistar toda
 * vez. `folderKey` vem de [CacheKeys.forFolder] (rede) ou é o `path` puro (local, ver
 * [FolderPreviewGenerator]).
 *
 * [scanCompletedFully]=false significa que a varredura bateu no deadline de segurança (ver
 * `rust/protocols`, função de scan recursivo) e ASSUMIU que a pasta tem conteúdo em vez de
 * concluir a varredura — esse resultado é revalidado com TTL mais curto que um resultado
 * definitivo.
 */
@Entity(tableName = "folder_media_status")
data class FolderMediaStatus(
    @PrimaryKey val folderKey: String,
    val hasPlayableMedia: Boolean,
    val scanCompletedFully: Boolean,
    val lastCheckedAt: Long,
    val sourceKind: String
)

@Dao
interface FolderMediaStatusDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FolderMediaStatus)

    @Query("SELECT * FROM folder_media_status WHERE folderKey = :key LIMIT 1")
    suspend fun find(key: String): FolderMediaStatus?

    @Query("DELETE FROM folder_media_status WHERE sourceKind = :sourceKind")
    suspend fun clearForSource(sourceKind: String)
}
