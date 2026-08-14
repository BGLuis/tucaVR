package com.vrplayer.filebrowser

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoMetadata(val durationMs: Long, val width: Int, val height: Int)

/**
 * Metadados de video (duracao/resolucao) pra tela de detalhe do arquivo
 * (VRPresentation.renderLocalFileDetail) — mesma fonte (MediaMetadataRetriever)
 * do ThumbnailGenerator, mas sem cache: so e chamado ao abrir o detalhe de UM
 * arquivo por vez, nao pra listagem inteira.
 */
object VideoMetadataReader {

    suspend fun read(path: String): VideoMetadata? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            VideoMetadata(durationMs, width, height)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
