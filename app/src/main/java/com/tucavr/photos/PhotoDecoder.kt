package com.tucavr.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Representação de uma foto decodificada pronta para consumo em VR e renderização (T8.2).
 */
data class DecodedPhoto(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val originalWidth: Int,
    val originalHeight: Int,
    val format: PhotoFormat
) {
    /**
     * Converte os pixels do Bitmap (ARGB_8888) em um array de bytes RGBA cru
     * pronto para upload direto via JNI em buffers Vulkan / GLES.
     */
    fun toRgbaByteArray(): ByteArray {
        val byteBuffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(byteBuffer)
        return byteBuffer.array()
    }
}

/**
 * Decodificador de imagens para o visualizador VR de fotos 360° e 3D (T8.2).
 * Aplica subsampling automático para nunca exceder o limite seguro da GPU (8192×4096),
 * além de normalizar a rotação com base nas tags EXIF.
 */
object PhotoDecoder {

    /**
     * Limite seguro de textura para o Quest 3 (Snapdragon XR2 Gen 2).
     * Embora o limite de hardware seja 16384, 8192x4096 é o teto prático para evitar esgotamento de VRAM.
     */
    const val MAX_TEXTURE_WIDTH = 8192
    const val MAX_TEXTURE_HEIGHT = 4096

    /**
     * Calcula o valor de inSampleSize (potência de 2) necessário para que as dimensões
     * da imagem de entrada fiquem dentro de [maxWidth] x [maxHeight].
     * Função pura, testável diretamente na JVM.
     */
    fun calculateInSampleSize(
        width: Int,
        height: Int,
        maxWidth: Int = MAX_TEXTURE_WIDTH,
        maxHeight: Int = MAX_TEXTURE_HEIGHT
    ): Int {
        if (width <= 0 || height <= 0) return 1
        if (width <= maxWidth && height <= maxHeight) return 1

        var inSampleSize = 1
        val halfWidth = width / 2
        val halfHeight = height / 2

        while ((halfWidth / inSampleSize) >= maxWidth || (halfHeight / inSampleSize) >= maxHeight ||
            (width / inSampleSize) > maxWidth || (height / inSampleSize) > maxHeight
        ) {
            inSampleSize *= 2
        }

        return inSampleSize
    }

    /**
     * Converte a orientação EXIF em graus de rotação (0°, 90°, 180°, 270°).
     * Função pura, testável diretamente na JVM.
     */
    fun getRotationDegrees(orientation: Int): Float {
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }

    /**
     * Lê a orientação EXIF de um arquivo local.
     */
    fun readExifOrientation(filePath: String): Int {
        return try {
            val exif = ExifInterface(filePath)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * Lê a orientação EXIF de um stream de bytes.
     */
    fun readExifOrientation(inputStream: InputStream): Int {
        return try {
            val exif = ExifInterface(inputStream)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * Decodifica uma imagem a partir de um arquivo local, aplicando limite seguro de resolução
     * e rotação EXIF.
     */
    fun decodePhoto(filePath: String): DecodedPhoto? {
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) return null

        // 1. Obter dimensões originais sem carregar os pixels em memória
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, boundsOptions)
        val origW = boundsOptions.outWidth
        val origH = boundsOptions.outHeight
        if (origW <= 0 || origH <= 0) return null

        // 2. Metadados e formato
        val orientation = readExifOrientation(filePath)
        val degrees = getRotationDegrees(orientation)
        val format = PhotoFormatDetector.detectFromPath(filePath, origW, origH)

        // Se a foto tiver rotação de 90° ou 270°, as dimensões se invertem no display
        val effectiveW = if (degrees == 90f || degrees == 270f) origH else origW
        val effectiveH = if (degrees == 90f || degrees == 270f) origW else origH

        // 3. Calcular inSampleSize seguro
        val sampleSize = calculateInSampleSize(effectiveW, effectiveH, MAX_TEXTURE_WIDTH, MAX_TEXTURE_HEIGHT)

        // 4. Decodificação completa com subsample
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = BitmapFactory.decodeFile(filePath, decodeOptions) ?: return null

        // 5. Aplicar rotação EXIF se necessário
        val finalBitmap = if (degrees != 0f) {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            if (rotated != rawBitmap) {
                rawBitmap.recycle()
            }
            rotated
        } else {
            rawBitmap
        }

        return DecodedPhoto(
            bitmap = finalBitmap,
            width = finalBitmap.width,
            height = finalBitmap.height,
            originalWidth = origW,
            originalHeight = origH,
            format = format
        )
    }

    /**
     * Decodifica uma imagem a partir de um array de bytes (ex: baixado de rede SMB/FTP/SFTP/HTTP).
     */
    fun decodePhoto(bytes: ByteArray, filename: String = ""): DecodedPhoto? {
        if (bytes.isEmpty()) return null

        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        val origW = boundsOptions.outWidth
        val origH = boundsOptions.outHeight
        if (origW <= 0 || origH <= 0) return null

        val orientation = readExifOrientation(ByteArrayInputStream(bytes))
        val degrees = getRotationDegrees(orientation)
        val format = PhotoFormatDetector.detectFormat(origW, origH, null, filename)

        val effectiveW = if (degrees == 90f || degrees == 270f) origH else origW
        val effectiveH = if (degrees == 90f || degrees == 270f) origW else origH

        val sampleSize = calculateInSampleSize(effectiveW, effectiveH, MAX_TEXTURE_WIDTH, MAX_TEXTURE_HEIGHT)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null

        val finalBitmap = if (degrees != 0f) {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            if (rotated != rawBitmap) {
                rawBitmap.recycle()
            }
            rotated
        } else {
            rawBitmap
        }

        return DecodedPhoto(
            bitmap = finalBitmap,
            width = finalBitmap.width,
            height = finalBitmap.height,
            originalWidth = origW,
            originalHeight = origH,
            format = format
        )
    }

    /**
     * Gera miniatura dimensionada para a biblioteca de arquivos (T8.6).
     */
    fun decodeThumbnail(filePath: String, targetWidth: Int = 512, targetHeight: Int = 288): Bitmap? {
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) return null

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, boundsOptions)
        val origW = boundsOptions.outWidth
        val origH = boundsOptions.outHeight
        if (origW <= 0 || origH <= 0) return null

        val orientation = readExifOrientation(filePath)
        val degrees = getRotationDegrees(orientation)
        val effectiveW = if (degrees == 90f || degrees == 270f) origH else origW
        val effectiveH = if (degrees == 90f || degrees == 270f) origW else origH

        val sampleSize = calculateInSampleSize(effectiveW, effectiveH, targetWidth * 2, targetHeight * 2)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampled = BitmapFactory.decodeFile(filePath, decodeOptions) ?: return null

        val rotated = if (degrees != 0f) {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rot = Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
            if (rot != sampled) sampled.recycle()
            rot
        } else sampled

        // Escalar preservando aspecto ou ajustando para targetWidth/targetHeight
        return if (rotated.width != targetWidth || rotated.height != targetHeight) {
            val scaled = Bitmap.createScaledBitmap(rotated, targetWidth, targetHeight, true)
            if (scaled != rotated) rotated.recycle()
            scaled
        } else {
            rotated
        }
    }
}
