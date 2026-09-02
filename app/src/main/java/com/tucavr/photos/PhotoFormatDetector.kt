package com.tucavr.photos

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale

/**
 * Tipo de projeção espacial da foto (T8.1).
 */
enum class PhotoProjection {
    FLAT,
    EQUIRECTANGULAR_360,
    VR_180
}

/**
 * Modo estereoscópico 3D da foto (T8.1, T8.4).
 */
enum class PhotoStereoMode {
    MONO,
    SIDE_BY_SIDE,
    OVER_UNDER
}

/**
 * Metadados consolidados de formato de imagem para VR.
 */
data class PhotoFormat(
    val projection: PhotoProjection,
    val stereoMode: PhotoStereoMode
) {
    /**
     * Mapeia para o enum ScreenMode do renderizador C++/Vulkan:
     * Flat2D = 0, SBS = 1, SBSHalf = 2, OU = 3, OUHalf = 4,
     * Sphere360 = 5, Sphere180 = 6, Sphere360SBS = 7, Sphere360OU = 8, Vr180SBS = 9
     */
    fun toScreenMode(): Int {
        return when (projection) {
            PhotoProjection.FLAT -> when (stereoMode) {
                PhotoStereoMode.MONO -> 0 // Flat2D
                PhotoStereoMode.SIDE_BY_SIDE -> 1 // SBS
                PhotoStereoMode.OVER_UNDER -> 3 // OU
            }
            PhotoProjection.EQUIRECTANGULAR_360 -> when (stereoMode) {
                PhotoStereoMode.MONO -> 5 // Sphere360
                PhotoStereoMode.SIDE_BY_SIDE -> 7 // Sphere360SBS
                PhotoStereoMode.OVER_UNDER -> 8 // Sphere360OU
            }
            PhotoProjection.VR_180 -> when (stereoMode) {
                PhotoStereoMode.MONO -> 6 // Sphere180
                PhotoStereoMode.SIDE_BY_SIDE -> 9 // Vr180SBS
                PhotoStereoMode.OVER_UNDER -> 6 // Fallback Sphere180
            }
        }
    }
}

/**
 * Detector de projeção espacial e estereoscopia 3D para fotos (T8.1, T8.4).
 * Suporta metadados XMP GPano (Google Photosphere / equirectangular),
 * heurística de aspecto 2:1 para fotos 360 panorâmicas e convenções de sufixo de arquivo (_sbs, _ou, _lr, _3d).
 */
object PhotoFormatDetector {

    private const val MIN_360_WIDTH_HEURISTIC = 3840

    /**
     * Detecta o modo estéreo a partir do nome do arquivo e dimensões.
     */
    fun detectStereoMode(filename: String, width: Int = 0, height: Int = 0): PhotoStereoMode {
        val lower = filename.lowercase(Locale.ROOT)

        // Over-Under (Top-Bottom)
        if (lower.contains("_ou") || lower.contains("-ou") || lower.contains(".ou.") ||
            lower.contains("_tb") || lower.contains("-tb") || lower.contains(".tb.") ||
            lower.contains("topbottom") || lower.contains("top-bottom") ||
            lower.contains("overunder") || lower.contains("over-under") ||
            lower.contains("3d_ou") || lower.contains("3d-ou") || lower.contains("3d.ou") ||
            lower.contains("_hou") || lower.contains(".hou")
        ) {
            return PhotoStereoMode.OVER_UNDER
        }

        // Side-by-Side (SBS) / Left-Right (LR)
        if (lower.contains("_sbs") || lower.contains("-sbs") || lower.contains(".sbs.") ||
            lower.contains("_lr") || lower.contains("-lr") || lower.contains(".lr.") ||
            lower.contains("leftright") || lower.contains("left-right") ||
            lower.contains("sidebyside") || lower.contains("side-by-side") ||
            lower.contains("3d_sbs") || lower.contains("3d-sbs") || lower.contains("3d.sbs") ||
            lower.contains("_hsbs") || lower.contains(".hsbs")
        ) {
            return PhotoStereoMode.SIDE_BY_SIDE
        }

        // Sufixo genérico _3d (ex: foto_3d.jpg ou foto-3d.png)
        if (lower.contains("_3d") || lower.contains("-3d") || lower.contains(".3d.")) {
            // Em fotos estéreo sem outra marcação, o formato padrão de mercado (câmeras 3D) é Side-by-Side
            return PhotoStereoMode.SIDE_BY_SIDE
        }

        return PhotoStereoMode.MONO
    }

    /**
     * Avalia os metadados XMP em busca de tags do namespace GPano.
     */
    fun parseXmpProjection(xmpString: String?): PhotoProjection? {
        if (xmpString.isNullOrBlank()) return null
        val lower = xmpString.lowercase(Locale.ROOT)

        if (lower.contains("projectiontype=\"equirectangular\"") ||
            lower.contains("projectiontype>equirectangular<") ||
            lower.contains("usepanoramaviewer=\"true\"") ||
            lower.contains("usepanoramaviewer>true<")
        ) {
            return PhotoProjection.EQUIRECTANGULAR_360
        }

        if (lower.contains("projectiontype=\"cylindrical\"")) {
            return PhotoProjection.EQUIRECTANGULAR_360
        }

        return null
    }

    /**
     * Detecta a projeção da foto considerando XMP, nome de arquivo e dimensões/aspect ratio.
     */
    fun detectProjection(
        width: Int,
        height: Int,
        xmpMetadata: String? = null,
        filename: String? = null
    ): PhotoProjection {
        // 1. Prioridade máxima: metadados XMP explícitos GPano
        val xmpProjection = parseXmpProjection(xmpMetadata)
        if (xmpProjection != null) {
            return xmpProjection
        }

        val lowerName = filename?.lowercase(Locale.ROOT) ?: ""

        // 2. Indicador explícito no nome do arquivo
        if (lowerName.contains("vr180") || lowerName.contains("180vr") ||
            lowerName.contains("_180") || lowerName.contains("-180") || lowerName.contains(".180.")
        ) {
            return PhotoProjection.VR_180
        }

        if (lowerName.contains("vr360") || lowerName.contains("360vr") ||
            lowerName.contains("equirectangular") || lowerName.contains("photosphere") ||
            lowerName.contains("_360") || lowerName.contains("-360") || lowerName.contains(".360.")
        ) {
            return PhotoProjection.EQUIRECTANGULAR_360
        }

        // 3. Heurística de aspect ratio e resolução
        if (width > 0 && height > 0) {
            val aspect = width.toDouble() / height.toDouble()
            // Fotos equirretangulares têm proporção 2:1 exata (360° horizontal x 180° vertical)
            val isTwoToOne = Math.abs(aspect - 2.0) <= 0.05
            if (isTwoToOne && width >= MIN_360_WIDTH_HEURISTIC) {
                return PhotoProjection.EQUIRECTANGULAR_360
            }
        }

        return PhotoProjection.FLAT
    }

    /**
     * Detecta o formato completo da imagem (projeção + estereoscopia).
     */
    fun detectFormat(
        width: Int,
        height: Int,
        xmpMetadata: String? = null,
        filename: String = ""
    ): PhotoFormat {
        val proj = detectProjection(width, height, xmpMetadata, filename)
        val stereo = detectStereoMode(filename, width, height)
        return PhotoFormat(projection = proj, stereoMode = stereo)
    }

    /**
     * Extrai metadados e detecta o formato diretamente do arquivo local.
     */
    fun detectFromPath(filePath: String, width: Int = 0, height: Int = 0): PhotoFormat {
        val file = File(filePath)
        val filename = file.name

        var detectedWidth = width
        var detectedHeight = height
        var xmp: String? = null

        if (file.exists() && file.canRead()) {
            try {
                val exif = ExifInterface(filePath)
                xmp = exif.getAttribute(ExifInterface.TAG_XMP)

                if (detectedWidth <= 0 || detectedHeight <= 0) {
                    val exifW = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val exifH = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    if (exifW > 0 && exifH > 0) {
                        detectedWidth = exifW
                        detectedHeight = exifH
                    }
                }
            } catch (_: Throwable) {
                // Fallback gracioso caso ExifInterface falhe
            }
        }

        return detectFormat(detectedWidth, detectedHeight, xmp, filename)
    }
}
