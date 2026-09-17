package com.tucavr.chroma

/**
 * Modos de máscara/transparência para reprodução com Passthrough ativo.
 */
enum class PassthroughMaskMode(val id: Int) {
    /** Sem máscara adicional (revela o passthrough apenas no hemisfério/fora do FOV). */
    OFF(0),

    /** Chroma Key por distância de cor em espaço YCbCr (verde, azul, vermelho, cinza, etc.). */
    CHROMA_KEY(1),

    /** Formato DeoVR / HereSphere 6-Segment Packed Alpha (fatiado nas quinas e cunhas do frame 2:1 SBS). */
    PACKED_ALPHA(2);

    companion object {
        fun fromId(id: Int): PassthroughMaskMode =
            entries.find { it.id == id } ?: OFF
    }
}

/**
 * Utilitário para detecção automática do formato DeoVR / HereSphere Packed Alpha
 * a partir do nome do arquivo de vídeo.
 *
 * Arquivos produzidos por ferramentas como vr-masking-tools e Deo-Alpha-Packer
 * adotam a convenção _alpha (ex: video_FISHEYE190_alpha.mp4).
 */
object PackedAlphaDetector {

    /**
     * Verifica se o nome do arquivo ou caminho indica formato Packed Alpha.
     */
    fun isPackedAlpha(filename: String?): Boolean {
        if (filename.isNullOrBlank()) return false
        // Remove extensao (.mp4, .mkv, etc.) e converte para minusculo
        val cleanName = filename.substringAfterLast('/').substringAfterLast('\\')
        val base = cleanName.substringBeforeLast('.').lowercase()

        return base.endsWith("_alpha") ||
            base.endsWith("-alpha") ||
            base.contains("_alpha_") ||
            base.contains("-alpha-") ||
            base.contains("alphapacked") ||
            base.contains("packedalpha") ||
            base.contains("alpha_packed") ||
            base.contains("packed_alpha") ||
            base.endsWith(".alpha") ||
            base.contains(".alpha.")
    }

    /**
     * Retorna o modo de máscara recomendado para o arquivo.
     * Se for um vídeo _alpha, retorna [PassthroughMaskMode.PACKED_ALPHA];
     * caso contrário, retorna [PassthroughMaskMode.OFF] (ou manter preferência existente).
     */
    fun detectMaskMode(filename: String?): PassthroughMaskMode {
        return if (isPackedAlpha(filename)) {
            PassthroughMaskMode.PACKED_ALPHA
        } else {
            PassthroughMaskMode.OFF
        }
    }
}
