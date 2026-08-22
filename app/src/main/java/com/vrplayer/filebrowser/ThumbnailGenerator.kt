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

    const val THUMB_WIDTH = 512
    const val THUMB_HEIGHT = 288
    private const val CACHE_DIR_NAME = "thumbnails_v2"

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

    // Alvo primário ~10% da duração, com mínimo de 5s para pular vinhetas/intros pretas
    // e máximo de 60s para não avançar excessivamente em filmes longos.
    private const val MIN_TARGET_US = 5_000_000L
    private const val MAX_TARGET_US = 60_000_000L

    internal fun targetTimeUs(durationMs: Long): Long {
        if (durationMs <= 0L) return MIN_TARGET_US
        return (durationMs * 1000L / 10L).coerceIn(MIN_TARGET_US, MAX_TARGET_US)
    }

    /**
     * Detector de quadro essencialmente preto ou inútil.
     * Amostra a luminância de pixels espaçados para execução rápida na CPU.
     */
    internal fun isEffectivelyBlack(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return true

        var totalLuminance = 0L
        var sampleCount = 0
        val stepX = (width / 32).coerceAtLeast(1)
        val stepY = (height / 32).coerceAtLeast(1)

        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                totalLuminance += lum
                sampleCount++
            }
        }

        val avg = if (sampleCount > 0) totalLuminance / sampleCount else 0
        return avg < 14
    }

    private fun generateFrame(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val primaryTargetUs = targetTimeUs(durationMs)

            // Lista de timestamps candidatos para evitar miniaturas pretas (10%, 15s, 30s, 60s, 5s)
            val candidateTargetsUs = listOf(
                primaryTargetUs,
                15_000_000L,
                30_000_000L,
                60_000_000L,
                5_000_000L
            ).distinct()

            var bestBitmap: Bitmap? = null

            for (targetUs in candidateTargetsUs) {
                if (durationMs > 0 && targetUs > durationMs * 1000L) continue

                val candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        THUMB_WIDTH,
                        THUMB_HEIGHT
                    ) ?: retriever.getScaledFrameAtTime(
                        targetUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        THUMB_WIDTH,
                        THUMB_HEIGHT
                    )
                } else {
                    null
                } ?: retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?.let { Bitmap.createScaledBitmap(it, THUMB_WIDTH, THUMB_HEIGHT, true) }
                    ?: retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { Bitmap.createScaledBitmap(it, THUMB_WIDTH, THUMB_HEIGHT, true) }

                if (candidate != null) {
                    if (!isEffectivelyBlack(candidate)) {
                        return candidate
                    }
                    if (bestBitmap == null) {
                        bestBitmap = candidate
                    }
                }
            }

            bestBitmap
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
