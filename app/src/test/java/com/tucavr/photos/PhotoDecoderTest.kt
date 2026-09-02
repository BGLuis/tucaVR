package com.tucavr.photos

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDecoderTest {

    @Test
    fun testCalculateInSampleSizeWithinMaxBounds() {
        // Imagens que já cabem no limite seguro 8192x4096 devem ter sampleSize = 1
        assertEquals(1, PhotoDecoder.calculateInSampleSize(1920, 1080, 8192, 4096))
        assertEquals(1, PhotoDecoder.calculateInSampleSize(3840, 2160, 8192, 4096))
        assertEquals(1, PhotoDecoder.calculateInSampleSize(8192, 4096, 8192, 4096))
        assertEquals(1, PhotoDecoder.calculateInSampleSize(4096, 2048, 8192, 4096))
    }

    @Test
    fun testCalculateInSampleSizeExceedingResolution() {
        // Foto 12K (12000x6000): deve calcular sampleSize = 2 (vira 6000x3000 <= 8192x4096)
        val sample12k = PhotoDecoder.calculateInSampleSize(12000, 6000, 8192, 4096)
        assertEquals(2, sample12k)
        assertTrue(12000 / sample12k <= 8192)
        assertTrue(6000 / sample12k <= 4096)

        // Foto 16K (16384x8192): sampleSize = 2 vira 8192x4096 (exato no limite)
        val sample16k = PhotoDecoder.calculateInSampleSize(16384, 8192, 8192, 4096)
        assertEquals(2, sample16k)
        assertTrue(16384 / sample16k <= 8192)
        assertTrue(8192 / sample16k <= 4096)

        // Foto 24K (24000x12000): sampleSize = 4 (vira 6000x3000 <= 8192x4096)
        val sample24k = PhotoDecoder.calculateInSampleSize(24000, 12000, 8192, 4096)
        assertEquals(4, sample24k)
        assertTrue(24000 / sample24k <= 8192)
        assertTrue(12000 / sample24k <= 4096)
    }

    @Test
    fun testCalculateInSampleSizeSingleDimensionExceeded() {
        // Foto panorâmica horizontal extrema (10000x2000): largura excede 8192, altura ok
        val sampleH = PhotoDecoder.calculateInSampleSize(10000, 2000, 8192, 4096)
        assertEquals(2, sampleH)
        assertTrue(10000 / sampleH <= 8192)

        // Foto vertical extrema (2000x6000): altura excede 4096, largura ok
        val sampleV = PhotoDecoder.calculateInSampleSize(2000, 6000, 8192, 4096)
        assertEquals(2, sampleV)
        assertTrue(6000 / sampleV <= 4096)
    }

    @Test
    fun testCalculateInSampleSizePowerOfTwo() {
        val samples = listOf(
            PhotoDecoder.calculateInSampleSize(1000, 1000, 500, 500),
            PhotoDecoder.calculateInSampleSize(5000, 5000, 500, 500),
            PhotoDecoder.calculateInSampleSize(20000, 20000, 8192, 4096)
        )
        for (sample in samples) {
            // Potência de 2: (s & (s - 1)) == 0 e s > 0
            assertTrue(sample > 0 && (sample and (sample - 1)) == 0)
        }
    }

    @Test
    fun testGetRotationDegrees() {
        assertEquals(0f, PhotoDecoder.getRotationDegrees(ExifInterface.ORIENTATION_NORMAL), 0.001f)
        assertEquals(0f, PhotoDecoder.getRotationDegrees(ExifInterface.ORIENTATION_UNDEFINED), 0.001f)
        assertEquals(90f, PhotoDecoder.getRotationDegrees(ExifInterface.ORIENTATION_ROTATE_90), 0.001f)
        assertEquals(180f, PhotoDecoder.getRotationDegrees(ExifInterface.ORIENTATION_ROTATE_180), 0.001f)
        assertEquals(270f, PhotoDecoder.getRotationDegrees(ExifInterface.ORIENTATION_ROTATE_270), 0.001f)
    }
}
