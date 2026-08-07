package com.vrplayer.filebrowser

enum class MediaType {
    VIDEO,
    AUDIO,
    IMAGE,
    DIRECTORY
}

data class MediaEntry(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val type: MediaType
)
