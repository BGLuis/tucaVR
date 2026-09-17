package com.tucavr.chroma

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Resultado da detecção automática de cor e parâmetros de Chroma Key.
 *
 * @property colorRgb Cor detectada no formato 0xRRGGBB.
 * @property suggestedSimilarity Tolerância recomendada para o corte (entre 0.15 e 0.50).
 * @property confidence Nível de confiança da detecção (0.0 a 1.0) baseado na dominância estatística da borda.
 */
data class ChromaDetectionResult(
    val colorRgb: Int,
    val suggestedSimilarity: Float,
    val confidence: Float
)

/**
 * Algoritmo de auto-detecção estatística da cor de fundo para Chroma Key.
 *
 * Em produções de vídeo (2D, VR180 e 3D SBS/OU), os objetos/atores principais situam-se
 * no centro da cena. As bordas perimétricas e cantos contêm de forma quase pura o ciclorama
 * ou tela de fundo (seja verde, azul, vermelho, cinza de estúdio ou preto).
 *
 * O algoritmo:
 * 1. Amostra pontos perimétricos com recuo (inset) de segurança (evitando letterboxing ou bordas de compressão).
 * 2. Trata modos 3D (SBS e Over-Under) amostrando as bordas externas de cada olho e evitando as costuras centrais.
 * 3. Converte as amostras para o espaço YCbCr e agrupa em clusters de similaridade.
 * 4. Extrai a cor mediana/média do cluster dominante e calcula a similaridade sugerida com base na variância.
 */
object ChromaColorDetector {

    private data class SamplePoint(val x: Int, val y: Int)

    private data class YcbcrColor(val y: Float, val cb: Float, val cr: Float, val r: Int, val g: Int, val b: Int)

    /**
     * Detecta a cor de fundo dominante a partir de um [Bitmap] do Android.
     */
    fun detectFromBitmap(bitmap: Bitmap, screenModeIndex: Int = 0): ChromaDetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return detectFromPixels(pixels, width, height, screenModeIndex)
    }

    /**
     * Detecta a cor de fundo a partir de um array plano de pixels ARGB (independente de Android SDK para testes JVM rápidos).
     */
    fun detectFromPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        screenModeIndex: Int = 0
    ): ChromaDetectionResult {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return ChromaDetectionResult(colorRgb = 0x00FF00, suggestedSimilarity = 0.35f, confidence = 0f)
        }

        // 1. Gera os pontos de amostragem no perímetro com base no formato de tela
        val sampleCoords = generatePerimeterSampleCoords(width, height, screenModeIndex)
        if (sampleCoords.isEmpty()) {
            return ChromaDetectionResult(colorRgb = 0x00FF00, suggestedSimilarity = 0.35f, confidence = 0f)
        }

        // 2. Extrai cores dos pontos
        val samples = mutableListOf<YcbcrColor>()
        for (pt in sampleCoords) {
            if (pt.x in 0 until width && pt.y in 0 until height) {
                val pixel = pixels[pt.y * width + pt.x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                samples.add(rgbToYcbcr(r, g, b))
            }
        }

        if (samples.isEmpty()) {
            return ChromaDetectionResult(colorRgb = 0x00FF00, suggestedSimilarity = 0.35f, confidence = 0f)
        }

        // 3. Filtra letterbox preto espúrio (se apenas uma parte da borda for preta pura, ex: barras de cinema)
        val validSamples = filterLetterboxIfNonDominant(samples)

        // 4. Clusterização simples por proximidade no espaço YCbCr adaptativo
        val clusters = clusterSamples(validSamples)
        val dominantCluster = clusters.maxByOrNull { it.size } ?: validSamples

        // 5. Calcula o centróide / média do cluster dominante
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (c in dominantCluster) {
            sumR += c.r
            sumG += c.g
            sumB += c.b
        }
        val count = dominantCluster.size
        val avgR = (sumR / count).toInt().coerceIn(0, 255)
        val avgG = (sumG / count).toInt().coerceIn(0, 255)
        val avgB = (sumB / count).toInt().coerceIn(0, 255)

        val detectedColorRgb = (avgR shl 16) or (avgG shl 8) or avgB

        // 6. Calcula a variância cromática para sugerir a tolerância de corte ideal
        val keyYcbcr = rgbToYcbcr(avgR, avgG, avgB)
        var sumSqDist = 0.0
        for (c in dominantCluster) {
            val d = colorDistance(c, keyYcbcr)
            sumSqDist += d * d
        }
        val variance = if (count > 1) sumSqDist / (count - 1) else 0.0
        val stdDev = sqrt(variance).toFloat()

        // Tolerância sugerida cobre a variabilidade das sombras do estúdio (~3 desvios padrão), limitada entre 0.20 e 0.45
        val suggestedSim = (0.22f + stdDev * 2.2f).coerceIn(0.20f, 0.45f)
        val confidence = (dominantCluster.size.toFloat() / validSamples.size.toFloat()).coerceIn(0f, 1f)

        return ChromaDetectionResult(
            colorRgb = detectedColorRgb,
            suggestedSimilarity = (suggestedSim * 100f).roundToInt() / 100f,
            confidence = (confidence * 100f).roundToInt() / 100f
        )
    }

    private fun generatePerimeterSampleCoords(width: Int, height: Int, screenModeIndex: Int): List<SamplePoint> {
        val result = mutableListOf<SamplePoint>()

        // Verifica se é Side-by-Side (SBS)
        val isSbs = screenModeIndex in listOf(1, 2, 7, 9, 13, 14, 16)
        // Verifica se é Over-Under (OU)
        val isOu = screenModeIndex in listOf(3, 4, 8)

        when {
            isSbs -> {
                val halfW = width / 2
                // Amostra perímetro do olho esquerdo [0, halfW]
                sampleRect(result, 0, 0, halfW, height)
                // Amostra perímetro do olho direito [halfW, width]
                sampleRect(result, halfW, 0, width - halfW, height)
            }
            isOu -> {
                val halfH = height / 2
                // Amostra perímetro do olho superior [0, halfH]
                sampleRect(result, 0, 0, width, halfH)
                // Amostra perímetro do olho inferior [halfH, height]
                sampleRect(result, 0, halfH, width, height - halfH)
            }
            else -> {
                // 2D Monoscópico / Flat / Esférico padrão
                sampleRect(result, 0, 0, width, height)
            }
        }

        return result
    }

    private fun sampleRect(outList: MutableList<SamplePoint>, offsetX: Int, offsetY: Int, rectW: Int, rectH: Int) {
        val insetX = max(2, (rectW * 0.04f).toInt())
        val insetY = max(2, (rectH * 0.04f).toInt())

        val x0 = offsetX + insetX
        val x1 = offsetX + rectW - 1 - insetX
        val y0 = offsetY + insetY
        val y1 = offsetY + rectH - 1 - insetY

        if (x1 <= x0 || y1 <= y0) return

        // 4 cantos
        outList.add(SamplePoint(x0, y0))
        outList.add(SamplePoint(x1, y0))
        outList.add(SamplePoint(x0, y1))
        outList.add(SamplePoint(x1, y1))

        // Borda superior e inferior (amostras uniformes)
        val numSamplesH = 12
        for (i in 1..numSamplesH) {
            val x = x0 + (x1 - x0) * i / (numSamplesH + 1)
            outList.add(SamplePoint(x, y0))
            outList.add(SamplePoint(x, y1))
        }

        // Bordas laterais (amostras uniformes)
        val numSamplesV = 8
        for (i in 1..numSamplesV) {
            val y = y0 + (y1 - y0) * i / (numSamplesV + 1)
            outList.add(SamplePoint(x0, y))
            outList.add(SamplePoint(x1, y))
        }
    }

    private fun rgbToYcbcr(r: Int, g: Int, b: Int): YcbcrColor {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val y = 0.299f * rf + 0.587f * gf + 0.114f * bf
        val cb = -0.168736f * rf - 0.331264f * gf + 0.5f * bf
        val cr = 0.5f * rf - 0.418688f * gf - 0.081312f * bf
        return YcbcrColor(y, cb, cr, r, g, b)
    }

    private fun colorDistance(a: YcbcrColor, b: YcbcrColor): Float {
        // Distância adaptativa equivalente ao fragment shader
        val keyChroma = sqrt(b.cb * b.cb + b.cr * b.cr)
        // Se a saturação for baixa (cinza/neutro), peso do Y é 1.0. Se saturado, 0.15.
        val factor = ((keyChroma - 0.04f) / (0.16f - 0.04f)).coerceIn(0f, 1f)
        val lumaWeight = 1.0f - factor * (1.0f - 0.15f)

        val dy = (a.y - b.y) * lumaWeight
        val dcb = a.cb - b.cb
        val dcr = a.cr - b.cr
        return sqrt(dy * dy + dcb * dcb + dcr * dcr)
    }

    private fun filterLetterboxIfNonDominant(samples: List<YcbcrColor>): List<YcbcrColor> {
        val nonLetterbox = samples.filter { it.y >= 0.04f || (it.r > 15 || it.g > 15 || it.b > 15) }
        // Se a esmagadora maioria for preta (> 85%), o fundo de fato é preto (luma key em espaço negro)
        if (nonLetterbox.size < samples.size * 0.15f) {
            return samples
        }
        return nonLetterbox
    }

    private fun clusterSamples(samples: List<YcbcrColor>): List<List<YcbcrColor>> {
        val clusters = mutableListOf<MutableList<YcbcrColor>>()
        val threshold = 0.18f // limiar de agrupamento

        for (sample in samples) {
            var matchedCluster: MutableList<YcbcrColor>? = null
            var minDist = Float.MAX_VALUE

            for (cluster in clusters) {
                val center = cluster[0]
                val dist = colorDistance(sample, center)
                if (dist < threshold && dist < minDist) {
                    minDist = dist
                    matchedCluster = cluster
                }
            }

            if (matchedCluster != null) {
                matchedCluster.add(sample)
            } else {
                clusters.add(mutableListOf(sample))
            }
        }

        return clusters
    }
}
