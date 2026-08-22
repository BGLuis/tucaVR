package com.vrplayer.network

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * T11.1/T11.2: DAO para operações CRUD sobre a tabela `saved_servers`.
 */
@Dao
interface SavedServerDao {

    @Query("SELECT * FROM saved_servers ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<SavedServer>

    @Query("SELECT * FROM saved_servers WHERE protocol = :protocol ORDER BY name COLLATE NOCASE ASC")
    suspend fun getByProtocol(protocol: ServerProtocol): List<SavedServer>

    @Query("SELECT * FROM saved_servers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SavedServer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: SavedServer)

    @Update
    suspend fun update(server: SavedServer)

    @Query("DELETE FROM saved_servers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE saved_servers SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long)
}
