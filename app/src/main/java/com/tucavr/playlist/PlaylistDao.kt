package com.tucavr.playlist

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/**
 * T9.1: DAO para operações nas tabelas de playlists e itens de playlists.
 */
@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun getAllPlaylists(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: String): Playlist?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist)

    @Update
    suspend fun updatePlaylist(playlist: Playlist)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("UPDATE playlists SET itemCount = :count WHERE id = :id")
    suspend fun updateItemCount(id: String, count: Int)

    // ---- Itens de Playlist ----

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getItemsForPlaylist(playlistId: String): List<PlaylistItem>

    @Query("SELECT * FROM playlist_items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: String): PlaylistItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: PlaylistItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<PlaylistItem>)

    @Update
    suspend fun updateItem(item: PlaylistItem)

    @Update
    suspend fun updateItems(items: List<PlaylistItem>)

    @Query("DELETE FROM playlist_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteItemsForPlaylist(playlistId: String)

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun getItemCount(playlistId: String): Int

    @Query("SELECT MAX(position) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: String): Int?

    /**
     * Adiciona um item na última posição da playlist e atualiza a contagem de itens.
     */
    @Transaction
    suspend fun addItemToPlaylist(item: PlaylistItem) {
        insertItem(item)
        val count = getItemCount(item.playlistId)
        updateItemCount(item.playlistId, count)
    }

    /**
     * Remove um item da playlist, renumera as posições dos itens restantes e atualiza a contagem.
     */
    @Transaction
    suspend fun removeItemAndReorder(item: PlaylistItem) {
        deleteItem(item.id)
        val remaining = getItemsForPlaylist(item.playlistId)
        val reordered = remaining.mapIndexed { index, pi ->
            if (pi.position != index) pi.copy(position = index) else pi
        }
        updateItems(reordered)
        updateItemCount(item.playlistId, reordered.size)
    }

    /**
     * Troca a ordem de dois itens adjacentes na playlist.
     */
    @Transaction
    suspend fun swapItemPositions(itemA: PlaylistItem, itemB: PlaylistItem) {
        val updatedA = itemA.copy(position = itemB.position)
        val updatedB = itemB.copy(position = itemA.position)
        updateItems(listOf(updatedA, updatedB))
    }
}
