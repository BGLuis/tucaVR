package com.vrplayer.filebrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object ThumbnailGenerator {

    private const val THUMB_WIDTH = 256
    private const val THUMB_HEIGHT = 144
    private const val CACHE_DIR_NAME = "thumbnails"

    suspend fun getThumbnail(context: Context, entry: MediaEntry): Bitmap? {
        if (entry.type != MediaType.VIDEO) return null

        return withContext(Dispatchers.IO) {
            val cacheFile = cacheFileFor(context, entry)

            val cached = if (cacheFile.exists()) BitmapFactory.decodeFile(cacheFile.absolutePath) else null
            if (cached != null) return@withContext cached

            val bitmap = generateFrame(entry.path) ?: return@withContext null
            writeToCache(bitmap, cacheFile)
            bitmap
        }
    }

    // T13.3: alvo ~10% da duracao (doc da fase 0.2, secao 13), mesma
    // heuristica e mesmos limites (1s-30s) do lado Rust (core::thumbnail,
    // usado pelas miniaturas de rede) -- clipe curto nao pede seek quase
    // nulo, filme longo nao pede seek minutos adentro so pra gerar miniatura.
    private const val MIN_TARGET_US = 1_000_000L
    private const val MAX_TARGET_US = 30_000_000L

    internal fun targetTimeUs(durationMs: Long): Long {
        if (durationMs <= 0L) return MIN_TARGET_US
        return (durationMs * 1000L / 10L).coerceIn(MIN_TARGET_US, MAX_TARGET_US)
    }

    private fun generateFrame(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val targetUs = targetTimeUs(durationMs)

            val scaled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    targetUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMB_WIDTH,
                    THUMB_HEIGHT
                )
            } else {
                null
            }

            scaled ?: retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { Bitmap.createScaledBitmap(it, THUMB_WIDTH, THUMB_HEIGHT, true) }
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun writeToCache(bitmap: Bitmap, cacheFile: File) {
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        } catch (e: Exception) {
            // Failure to persist the cache is not fatal; the caller still gets the bitmap
            // it just has to be regenerated next time.
        }
    }

    private fun cacheFileFor(context: Context, entry: MediaEntry): File {
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        return File(cacheDir, "${cacheKeyFor(entry)}.jpg")
    }

    // Extracted as a pure, Context-free function so the cache-key logic (the part that
    // actually matters for correctness: same file -> same key, changed file -> different
    // key, no collisions between unrelated files) can be unit-tested on the JVM without
    // Robolectric or a real Android cacheDir. Everything that touches real I/O
    // (MediaMetadataRetriever, disk reads/writes) stays out of this function on purpose.
    internal fun cacheKeyFor(entry: MediaEntry): String {
        // size + lastModified are included so a file replaced/edited at the same path
        // doesn't reuse a stale thumbnail, while a plain rename does not collide with
        // an unrelated file's cache entry.
        return sha256("${entry.path}|${entry.sizeBytes}|${entry.lastModified}")
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
