package com.tucavr.codec

import android.media.MediaCodecList
import android.os.Build
import com.tucavr.R

/**
 * Informações sobre um decodificador registrado no sistema operacional Android.
 */
data class CodecDecoderInfo(
    val mimeType: String,
    val codecName: String,
    val isHardwareAccelerated: Boolean,
    val isSoftwareOnly: Boolean,
    val isVendor: Boolean
)

/**
 * Status de suporte para um codec específico no dispositivo atual.
 */
sealed class CodecSupportStatus {
    data class Supported(val decoder: CodecDecoderInfo) : CodecSupportStatus()
    data class SoftwareOnly(val decoder: CodecDecoderInfo) : CodecSupportStatus()
    object Unsupported : CodecSupportStatus()

    val isHardwareAccelerated: Boolean
        get() = this is Supported && decoder.isHardwareAccelerated

    val isSupported: Boolean
        get() = this !is Unsupported
}

/**
 * Resultado da validação de capacidade de reprodução de um codec.
 */
data class PlaybackValidationResult(
    val isPlayable: Boolean,
    val isHardwareAccelerated: Boolean,
    val errorMessageResId: Int? = null,
    val decoderName: String? = null
)

/**
 * Provedor abstrato de decodificadores para permitir testes unitários na JVM sem mock estático.
 */
interface CodecInfoProvider {
    fun getDecoders(): List<CodecDecoderInfo>
}

/**
 * Implementação padrão que consulta a API MediaCodecList do Android.
 */
class SystemCodecInfoProvider : CodecInfoProvider {
    override fun getDecoders(): List<CodecDecoderInfo> {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val result = mutableListOf<CodecDecoderInfo>()
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.startsWith("OMX.google.", ignoreCase = true) &&
                        !info.name.startsWith("c2.android.", ignoreCase = true)
                }
                val isSw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isSoftwareOnly
                } else {
                    !isHw
                }
                val isVendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isVendor
                } else {
                    isHw
                }
                for (type in info.supportedTypes) {
                    result.add(
                        CodecDecoderInfo(
                            mimeType = type.lowercase(),
                            codecName = info.name,
                            isHardwareAccelerated = isHw,
                            isSoftwareOnly = isSw,
                            isVendor = isVendor
                        )
                    )
                }
            }
            result
        } catch (e: Throwable) {
            // Em ambiente de teste unitário puro na JVM ou erro de serviço de mídia
            emptyList()
        }
    }
}

/**
 * Gerenciador de capacidades de codecs e aceleração por hardware (T6.1, T6.5).
 * Inspeciona os decodificadores do dispositivo para AV1, VP9, AVC (H.264) e HEVC (H.265).
 */
object CodecCapabilityManager {
    const val MIME_AV1 = "video/av01"
    const val MIME_VP9 = "video/x-vnd.on2.vp9"
    const val MIME_AVC = "video/avc"
    const val MIME_HEVC = "video/hevc"

    @Volatile
    private var cachedDecoders: List<CodecDecoderInfo>? = null

    var provider: CodecInfoProvider = SystemCodecInfoProvider()

    /**
     * Limpa o cache de decodificadores.
     */
    fun refresh() {
        cachedDecoders = null
    }

    /**
     * Retorna a lista de todos os decodificadores disponíveis.
     */
    fun getAvailableDecoders(): List<CodecDecoderInfo> {
        return cachedDecoders ?: synchronized(this) {
            cachedDecoders ?: provider.getDecoders().also { cachedDecoders = it }
        }
    }

    /**
     * Normaliza nomes de codecs do FFmpeg ou aliases para os MIME types do Android MediaCodec.
     */
    fun normalizeMimeType(codecOrMime: String): String? {
        val clean = codecOrMime.trim().lowercase()
        return when {
            clean == MIME_AV1 || clean == "av1" || clean == "av01" -> MIME_AV1
            clean == MIME_VP9 || clean == "vp9" || clean == "vp09" -> MIME_VP9
            clean == MIME_AVC || clean == "h264" || clean == "h.264" || clean == "avc" -> MIME_AVC
            clean == MIME_HEVC || clean == "hevc" || clean == "h265" || clean == "h.265" -> MIME_HEVC
            clean.startsWith("video/") -> clean
            else -> null
        }
    }

    /**
     * Retorna o melhor decodificador registrado para o codec especificado, preferindo
     * decodificadores com aceleração por hardware.
     */
    fun findDecoder(codecOrMime: String): CodecDecoderInfo? {
        val targetMime = normalizeMimeType(codecOrMime) ?: return null
        val matches = getAvailableDecoders().filter { it.mimeType.equals(targetMime, ignoreCase = true) }
        return matches.firstOrNull { it.isHardwareAccelerated } ?: matches.firstOrNull()
    }

    /**
     * Avalia o status de suporte do codec no runtime.
     */
    fun getStatus(codecOrMime: String): CodecSupportStatus {
        val decoder = findDecoder(codecOrMime) ?: return CodecSupportStatus.Unsupported
        return if (decoder.isHardwareAccelerated) {
            CodecSupportStatus.Supported(decoder)
        } else {
            CodecSupportStatus.SoftwareOnly(decoder)
        }
    }

    /**
     * Retorna true se houver suporte a decodificação acelerada por hardware.
     */
    fun isHardwareSupported(codecOrMime: String): Boolean {
        return getStatus(codecOrMime).isHardwareAccelerated
    }

    /**
     * Retorna true se houver algum decodificador (HW ou SW) registrado.
     */
    fun isSupported(codecOrMime: String): Boolean {
        return getStatus(codecOrMime).isSupported
    }

    /**
     * Retorna o nome do decodificador selecionado para o codec (ex: "c2.qti.av1.decoder").
     */
    fun getDecoderName(codecOrMime: String): String? {
        return findDecoder(codecOrMime)?.codecName
    }

    /**
     * T6.1: Valida se o vídeo pode ser reproduzido no Quest 3.
     * Para codecs AV1 e VP9: se o hardware não possuir decodificador por hardware,
     * retorna isPlayable = false e a mensagem de erro específica amigável
     * (R.string.codec_hw_unsupported_error), prevenindo falhas silenciosas ou erros genéricos.
     */
    fun validatePlaybackSupport(codecOrMime: String): PlaybackValidationResult {
        val targetMime = normalizeMimeType(codecOrMime)
        val status = getStatus(codecOrMime)
        val isAv1OrVp9 = targetMime == MIME_AV1 || targetMime == MIME_VP9

        return when (status) {
            is CodecSupportStatus.Supported -> {
                PlaybackValidationResult(
                    isPlayable = true,
                    isHardwareAccelerated = true,
                    decoderName = status.decoder.codecName
                )
            }
            is CodecSupportStatus.SoftwareOnly -> {
                if (isAv1OrVp9) {
                    // O pipeline zero-copy VR exige hardware acceleration; software decode
                    // causaria travamentos severos ou tela preta.
                    PlaybackValidationResult(
                        isPlayable = false,
                        isHardwareAccelerated = false,
                        errorMessageResId = R.string.codec_hw_unsupported_error,
                        decoderName = status.decoder.codecName
                    )
                } else {
                    PlaybackValidationResult(
                        isPlayable = true,
                        isHardwareAccelerated = false,
                        decoderName = status.decoder.codecName
                    )
                }
            }
            is CodecSupportStatus.Unsupported -> {
                val errorRes = if (isAv1OrVp9) {
                    R.string.codec_hw_unsupported_error
                } else {
                    R.string.codec_generic_unsupported_error
                }
                PlaybackValidationResult(
                    isPlayable = false,
                    isHardwareAccelerated = false,
                    errorMessageResId = errorRes,
                    decoderName = null
                )
            }
        }
    }
}
