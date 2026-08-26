package com.tucavr.filebrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class FolderSummary(
    val totalItems: Int,
    val videoCount: Int,
    val audioCount: Int,
    val imageCount: Int,
    val previewEntries: List<MediaEntry>,
    val available3DFormats: Set<Format3DType>
) {
    val firstVideo: MediaEntry? get() = previewEntries.firstOrNull()
}

/**
 * Gera estatísticas detalhadas e mosaicos visuais (colagens de até 4 miniaturas)
 * para pastas da biblioteca.
 */
object FolderPreviewGenerator {

    // Caches limitados para evitar vazamento de memória nativa com bitmaps de mosaicos
    private val summaryCache = android.util.LruCache<String, FolderSummary>(200)
    private val mosaicCache = object : android.util.LruCache<String, Bitmap>(32 * 1024 * 1024) { // 32 MiB
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun getSummary(dirPath: String): FolderSummary? = withContext(Dispatchers.IO) {
        summaryCache.get(dirPath)?.let { return@withContext it }

        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) return@withContext null

        val children = dir.listFiles() ?: return@withContext null

        var videoCount = 0
        var audioCount = 0
        var imageCount = 0
        val previewVideos = mutableListOf<MediaEntry>()
        val formats3D = mutableSetOf<Format3DType>()

        for (file in children) {
            if (file.name.startsWith(".")) continue
            val ext = file.extension
            val type = mediaTypeForExtension(ext)
            when (type) {
                MediaType.VIDEO -> {
                    videoCount++
                    val f3d = MediaFilterEngine.detectFormat3DFromFilename(file.name)
                    formats3D.add(f3d)
                    if (previewVideos.size < 4) {
                        previewVideos.add(
                            MediaEntry(
                                name = file.name,
                                path = file.absolutePath,
                                sizeBytes = file.length(),
                                lastModified = file.lastModified(),
                                type = MediaType.VIDEO,
                                format3DHint = f3d
                            )
                        )
                    }
                }
                MediaType.AUDIO -> audioCount++
                MediaType.IMAGE -> imageCount++
                MediaType.DIRECTORY, null -> {}
            }
        }

        val summary = FolderSummary(
            totalItems = children.size,
            videoCount = videoCount,
            audioCount = audioCount,
            imageCount = imageCount,
            previewEntries = previewVideos,
            available3DFormats = formats3D
        )

        summaryCache.put(dirPath, summary)
        summary
    }

    /**
     * Retorna o mosaico (até 4 thumbnails em grade 2x2) para a pasta especificada.
     */
    suspend fun getFolderMosaic(
        context: Context,
        dirPath: String,
        thumbnailLoader: (suspend (MediaEntry) -> Bitmap?)? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        mosaicCache.get(dirPath)?.let { return@withContext it }

        val summary = getSummary(dirPath) ?: return@withContext null
        if (summary.previewEntries.isEmpty()) return@withContext null

        val loadedBitmaps = mutableListOf<Bitmap>()
        for (entry in summary.previewEntries) {
            val bmp = if (thumbnailLoader != null) {
                thumbnailLoader(entry)
            } else {
                ThumbnailGenerator.getThumbnail(context, entry)
            }
            if (bmp != null) {
                loadedBitmaps.add(bmp)
            }
        }

        if (loadedBitmaps.isEmpty()) return@withContext null

        val mosaic = createFolderMosaic(loadedBitmaps)
        if (mosaic != null) {
            mosaicCache.put(dirPath, mosaic)
        }
        mosaic
    }

    /**
     * Compõe uma colagem de até 4 bitmaps em resolução 512x288.
     */
    fun createFolderMosaic(bitmaps: List<Bitmap>, width: Int = 512, height: Int = 288): Bitmap? {
        if (bitmaps.isEmpty()) return null

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dividerPaint = Paint().apply {
            color = Color.parseColor("#121214")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        when (bitmaps.size) {
            1 -> {
                // 1 único vídeo: ocupa toda a área 512x288
                val src = Rect(0, 0, bitmaps[0].width, bitmaps[0].height)
                val dst = Rect(0, 0, width, height)
                canvas.drawBitmap(bitmaps[0], src, dst, paint)
            }
            2 -> {
                // 2 vídeos: divisão vertical (duas colunas 256x288)
                val midX = width / 2
                val src0 = cropCenterRect(bitmaps[0].width, bitmaps[0].height, midX, height)
                val dst0 = Rect(0, 0, midX, height)
                canvas.drawBitmap(bitmaps[0], src0, dst0, paint)

                val src1 = cropCenterRect(bitmaps[1].width, bitmaps[1].height, width - midX, height)
                val dst1 = Rect(midX, 0, width, height)
                canvas.drawBitmap(bitmaps[1], src1, dst1, paint)

                canvas.drawLine(midX.toFloat(), 0f, midX.toFloat(), height.toFloat(), dividerPaint)
            }
            3 -> {
                // 3 vídeos: 2 quadrantes em cima (256x144 cada) e 1 faixa embaixo (512x144)
                val midX = width / 2
                val midY = height / 2

                // Top-Left
                val src0 = cropCenterRect(bitmaps[0].width, bitmaps[0].height, midX, midY)
                val dst0 = Rect(0, 0, midX, midY)
                canvas.drawBitmap(bitmaps[0], src0, dst0, paint)

                // Top-Right
                val src1 = cropCenterRect(bitmaps[1].width, bitmaps[1].height, width - midX, midY)
                val dst1 = Rect(midX, 0, width, midY)
                canvas.drawBitmap(bitmaps[1], src1, dst1, paint)

                // Bottom
                val src2 = cropCenterRect(bitmaps[2].width, bitmaps[2].height, width, height - midY)
                val dst2 = Rect(0, midY, width, height)
                canvas.drawBitmap(bitmaps[2], src2, dst2, paint)

                canvas.drawLine(midX.toFloat(), 0f, midX.toFloat(), midY.toFloat(), dividerPaint)
                canvas.drawLine(0f, midY.toFloat(), width.toFloat(), midY.toFloat(), dividerPaint)
            }
            else -> {
                // 4 ou mais vídeos: matriz 2x2 clássica
                val midX = width / 2
                val midY = height / 2

                // Quadrante 1 (Top-Left)
                val src0 = cropCenterRect(bitmaps[0].width, bitmaps[0].height, midX, midY)
                val dst0 = Rect(0, 0, midX, midY)
                canvas.drawBitmap(bitmaps[0], src0, dst0, paint)

                // Quadrante 2 (Top-Right)
                val src1 = cropCenterRect(bitmaps[1].width, bitmaps[1].height, width - midX, midY)
                val dst1 = Rect(midX, 0, width, midY)
                canvas.drawBitmap(bitmaps[1], src1, dst1, paint)

                // Quadrante 3 (Bottom-Left)
                val src2 = cropCenterRect(bitmaps[2].width, bitmaps[2].height, midX, height - midY)
                val dst2 = Rect(0, midY, midX, height)
                canvas.drawBitmap(bitmaps[2], src2, dst2, paint)

                // Quadrante 4 (Bottom-Right)
                val src3 = cropCenterRect(bitmaps[3].width, bitmaps[3].height, width - midX, height - midY)
                val dst3 = Rect(midX, midY, width, height)
                canvas.drawBitmap(bitmaps[3], src3, dst3, paint)

                canvas.drawLine(midX.toFloat(), 0f, midX.toFloat(), height.toFloat(), dividerPaint)
                canvas.drawLine(0f, midY.toFloat(), width.toFloat(), midY.toFloat(), dividerPaint)
            }
        }

        return result
    }

    private fun cropCenterRect(srcWidth: Int, srcHeight: Int, targetWidth: Int, targetHeight: Int): Rect {
        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
        val srcAspect = srcWidth.toFloat() / srcHeight.toFloat()

        return if (srcAspect > targetAspect) {
            val cropWidth = (srcHeight * targetAspect).toInt()
            val left = (srcWidth - cropWidth) / 2
            Rect(left, 0, left + cropWidth, srcHeight)
        } else {
            val cropHeight = (srcWidth / targetAspect).toInt()
            val top = (srcHeight - cropHeight) / 2
            Rect(0, top, srcWidth, top + cropHeight)
        }
    }

    fun clearCache() {
        summaryCache.evictAll()
        mosaicCache.evictAll()
    }
}
