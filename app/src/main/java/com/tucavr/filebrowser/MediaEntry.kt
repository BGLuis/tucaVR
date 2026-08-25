package com.tucavr.filebrowser

enum class MediaType {
    VIDEO,
    AUDIO,
    IMAGE,
    DIRECTORY
}

enum class Format3DType {
    FLAT_2D,
    SBS,
    OU,
    VR_180,
    VR_360
}

data class MediaEntry(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val type: MediaType,
    val format3DHint: Format3DType = Format3DType.FLAT_2D,
    val progressFraction: Float? = null,
    val lastPlayedAt: Long? = null,
    val itemCount: Int? = null,
    val previewPath: String? = null
)

