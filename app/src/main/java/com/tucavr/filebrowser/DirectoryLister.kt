package com.tucavr.filebrowser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "webm", "flv", "ts", "m3u8", "3gp", "wmv", "mpg", "mpeg"
)

val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "aac", "ogg", "wav", "m4a", "opus", "wma"
)

val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic"
)

fun mediaTypeForExtension(extension: String): MediaType? {
    return when (extension.lowercase()) {
        in VIDEO_EXTENSIONS -> MediaType.VIDEO
        in AUDIO_EXTENSIONS -> MediaType.AUDIO
        in IMAGE_EXTENSIONS -> MediaType.IMAGE
        else -> null
    }
}

object DirectoryLister {

    // Only the immediate children of `directory` are listed; a full recursive walk
    // would be slow on large local libraries and is not needed for browsing one level at a time.
    suspend fun listMedia(directory: File): List<MediaEntry> = withContext(Dispatchers.IO) {
        val children = directory.listFiles() ?: return@withContext emptyList()

        children.mapNotNull { file -> toMediaEntry(file) }
    }

    private fun toMediaEntry(file: File): MediaEntry? {
        val type = if (file.isDirectory) {
            MediaType.DIRECTORY
        } else {
            mediaTypeForExtension(file.extension) ?: return null
        }

        return MediaEntry(
            name = file.name,
            path = file.absolutePath,
            sizeBytes = if (file.isDirectory) 0L else file.length(),
            lastModified = file.lastModified(),
            type = type
        )
    }
}
