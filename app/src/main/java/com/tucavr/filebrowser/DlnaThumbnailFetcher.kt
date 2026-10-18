package com.tucavr.filebrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

object DlnaThumbnailFetcher {
    private const val CACHE_DIR_NAME = "dlna_thumbnails"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_CONCURRENT_FETCHES = 4

    private val gate = Semaphore(MAX_CONCURRENT_FETCHES)

    suspend fun getThumbnail(
        context: Context,
        url: String,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            if (!DlnaThumbnailPolicy.isFetchableUrl(url)) return@withContext null
            val cacheFile = File(File(context.cacheDir, CACHE_DIR_NAME), "${CacheKeys.sha256(url)}.jpg")
            if (cacheFile.exists()) {
                BitmapFactory.decodeFile(cacheFile.absolutePath)?.let { return@withContext it }
            }

            val bytes = gate.withPermit { download(url.trim()) } ?: return@withContext null
            ensureActive()
            val bitmap = decodeBounded(bytes) ?: return@withContext null
            writeToCache(bitmap, cacheFile)
            bitmap
        }

    private suspend fun download(url: String): ByteArray? {
        val connection = URL(url).openConnection() as? HttpURLConnection ?: return null
        // A redirect could hop to a scheme or host the policy never validated.
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        val cancelHook = coroutineContext[Job]?.invokeOnCompletion { connection.disconnect() }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            if (!DlnaThumbnailPolicy.acceptsHeaders(connection.contentType, connection.contentLengthLong)) return null
            connection.inputStream.use { DlnaThumbnailPolicy.readCapped(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } finally {
            cancelHook?.dispose()
            connection.disconnect()
        }
    }

    private fun decodeBounded(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!DlnaThumbnailPolicy.isDecodableSize(bounds.outWidth, bounds.outHeight)) return null
        val options =
            BitmapFactory.Options().apply {
                inSampleSize =
                    DlnaThumbnailPolicy.sampleSizeFor(
                        bounds.outWidth,
                        bounds.outHeight,
                        NetworkThumbnailGenerator.THUMB_WIDTH,
                        NetworkThumbnailGenerator.THUMB_HEIGHT,
                    )
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun writeToCache(
        bitmap: Bitmap,
        cacheFile: File,
    ) {
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (e: Exception) {
            // Cache is best-effort: the caller still gets the bitmap.
        }
    }
}
