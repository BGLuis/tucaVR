package com.tucavr.chroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes unitários para o algoritmo estatístico [ChromaColorDetector].
 * Valida a auto-detecção em fundos Cinza, Vermelho, Verde, Preto e formatos 3D SBS.
 */
class ChromaColorDetectorTest {

    private fun createSyntheticFrame(
        width: Int,
        height: Int,
        bgColor: Int,
        centerColor: Int = 0x334455,
        centerMarginRatio: Float = 0.25f
    ): IntArray {
        val pixels = IntArray(width * height)
        val minX = (width * centerMarginRatio).toInt()
        val maxX = (width * (1f - centerMarginRatio)).toInt()
        val minY = (height * centerMarginRatio).toInt()
        val maxY = (height * (1f - centerMarginRatio)).toInt()

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x in minX..maxX && y in minY..maxY) {
                    pixels[y * width + x] = centerColor
                } else {
                    pixels[y * width + x] = bgColor
                }
            }
        }
        return pixels
    }

    @Test
    fun testDetectsGrayBackgroundCorrectly() {
        val width = 160
        val height = 90
        val grayColor = 0x808080 // Cinza médio
        val pixels = createSyntheticFrame(width, height, bgColor = grayColor, centerColor = 0xFF5533)

        val result = ChromaColorDetector.detectFromPixels(pixels, width, height, screenModeIndex = 0)

        val r = (result.colorRgb shr 16) and 0xFF
        val g = (result.colorRgb shr 8) and 0xFF
        val b = result.colorRgb and 0xFF

        // Tolera margem minúscula de arredondamento
        assertTrue("R deve ser próximo de 128 (atual: $r)", r in 120..136)
        assertTrue("G deve ser próximo de 128 (atual: $g)", g in 120..136)
        assertTrue("B deve ser próximo de 128 (atual: $b)", b in 120..136)
        assertTrue("Confiança deve ser alta (> 0.8)", result.confidence > 0.8f)
    }

    @Test
    fun testDetectsRedBackgroundCorrectly() {
        val width = 160
        val height = 90
        val redColor = 0xE50914 // Vermelho estúdio
        val pixels = createSyntheticFrame(width, height, bgColor = redColor, centerColor = 0x112233)

        val result = ChromaColorDetector.detectFromPixels(pixels, width, height, screenModeIndex = 0)

        val r = (result.colorRgb shr 16) and 0xFF
        val g = (result.colorRgb shr 8) and 0xFF
        val b = result.colorRgb and 0xFF

        assertTrue("R deve dominar fortemente (atual: $r)", r > 200)
        assertTrue("G deve ser baixo (atual: $g)", g < 30)
        assertTrue("B deve ser baixo (atual: $b)", b < 40)
        assertTrue("Confiança deve ser alta", result.confidence > 0.8f)
    }

    @Test
    fun testDetectsGreenBackgroundCorrectly() {
        val width = 160
        val height = 90
        val greenColor = 0x00FF00 // Verde clássico
        val pixels = createSyntheticFrame(width, height, bgColor = greenColor, centerColor = 0x802020)

        val result = ChromaColorDetector.detectFromPixels(pixels, width, height, screenModeIndex = 0)

        val r = (result.colorRgb shr 16) and 0xFF
        val g = (result.colorRgb shr 8) and 0xFF
        val b = result.colorRgb and 0xFF

        assertEquals(0, r)
        assertEquals(255, g)
        assertEquals(0, b)
        assertTrue(result.confidence > 0.8f)
    }

    @Test
    fun testDetectsSideBySide3dBackdrop() {
        val width = 320
        val height = 180
        val pixels = IntArray(width * height)
        val redColor = 0xFF0000

        // Preenche com vermelho
        pixels.fill(redColor)

        // Coloca um objeto no centro do olho esquerdo e outro no olho direito
        val halfW = width / 2
        for (y in 40..140) {
            for (x in 40..120) {
                pixels[y * width + x] = 0x00FF00 // ator esquerdo
            }
            for (x in (halfW + 40)..(halfW + 120)) {
                pixels[y * width + x] = 0x0000FF // ator direito
            }
        }

        // Modo 1 = SBS
        val result = ChromaColorDetector.detectFromPixels(pixels, width, height, screenModeIndex = 1)

        val r = (result.colorRgb shr 16) and 0xFF
        val g = (result.colorRgb shr 8) and 0xFF
        val b = result.colorRgb and 0xFF

        assertEquals(255, r)
        assertEquals(0, g)
        assertEquals(0, b)
        assertTrue("Confiança alta no SBS", result.confidence > 0.8f)
    }

    @Test
    fun testRejectsSpuriousLetterboxLines() {
        val width = 160
        val height = 90
        val grayColor = 0x787878
        val pixels = createSyntheticFrame(width, height, bgColor = grayColor, centerColor = 0x223344)

        // Simula linha de letterbox preto de 1 pixel na borda extrema (y = 0 e y = height - 1)
        for (x in 0 until width) {
            pixels[x] = 0x000000
            pixels[(height - 1) * width + x] = 0x000000
        }

        val result = ChromaColorDetector.detectFromPixels(pixels, width, height, screenModeIndex = 0)

        val r = (result.colorRgb shr 16) and 0xFF
        val g = (result.colorRgb shr 8) and 0xFF
        val b = result.colorRgb and 0xFF

        // Como o algoritmo possui inset de 4%, ele não é enganado pela linha preta na extremidade
        assertTrue("R deve ser próximo de 120 (atual: $r)", r in 110..130)
        assertTrue("G deve ser próximo de 120 (atual: $g)", g in 110..130)
        assertTrue("B deve ser próximo de 120 (atual: $b)", b in 110..130)
    }

    @Test
    fun testEmptyOrInvalidInputReturnsFallback() {
        val result = ChromaColorDetector.detectFromPixels(IntArray(0), 0, 0, 0)
        assertEquals(0x00FF00, result.colorRgb)
        assertEquals(0.35f, result.suggestedSimilarity, 0.01f)
    }
}
