package com.tucavr.playlist

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * T9.1: Entidade Room representando um item dentro de uma playlist.
 * Possui chave estrangeira para [Playlist] com deleção em cascata.
 */
@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId"])
    ]
)
data class PlaylistItem(
    @PrimaryKey val id: String,
    val playlistId: String,
    val mediaUri: String,
    val title: String,
    val durationMs: Long,
    val position: Int,
    val sourceType: String
)
