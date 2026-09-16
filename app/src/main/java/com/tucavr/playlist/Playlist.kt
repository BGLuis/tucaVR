package com.tucavr.playlist

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * T9.1: Entidade Room representando uma playlist criada pelo usuário.
 */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val itemCount: Int
)
