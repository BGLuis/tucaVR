//! Mapeamento e especificações de codecs de vídeo para o Android MediaCodec.

pub const MIME_H264: &str = "video/avc";
pub const MIME_HEVC: &str = "video/hevc";
pub const MIME_VP9: &str = "video/x-vnd.on2.vp9";
pub const MIME_AV1: &str = "video/av01";

/// Propriedades de decodificação de um codec suportado pelo player.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CodecInfo {
    pub mime: &'static str,
    /// Se true, o codec utiliza empacotamento NAL (avcC/hvcC) e requer
    /// conversão para Annex-B antes de submeter ao MediaCodec.
    /// Se false (VP9 e AV1), os pacotes já vêm no formato bruto aceito pelo MediaCodec.
    pub is_nal_based: bool,
}

/// Mapeia um identificador de codec de vídeo (nome ou MIME) para as propriedades
/// correspondentes do MediaCodec.
pub fn mime_for_codec_name(name: &str) -> Result<CodecInfo, String> {
    let clean = name.trim().to_ascii_lowercase();
    match clean.as_str() {
        "h264" | "avc" | "video/avc" => Ok(CodecInfo {
            mime: MIME_H264,
            is_nal_based: true,
        }),
        "hevc" | "h265" | "video/hevc" => Ok(CodecInfo {
            mime: MIME_HEVC,
            is_nal_based: true,
        }),
        "vp9" | "vp09" | "video/x-vnd.on2.vp9" => Ok(CodecInfo {
            mime: MIME_VP9,
            is_nal_based: false,
        }),
        "av1" | "av01" | "video/av01" => Ok(CodecInfo {
            mime: MIME_AV1,
            is_nal_based: false,
        }),
        other => Err(format!(
            "Unsupported video codec: {} (H.264/H.265/VP9/AV1 sao suportados)",
            other
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_h264_mapping() {
        for alias in &["h264", "H264", "avc", "AVC", "video/avc"] {
            let info = mime_for_codec_name(alias).expect("H264 should be supported");
            assert_eq!(info.mime, MIME_H264);
            assert!(info.is_nal_based);
        }
    }

    #[test]
    fn test_hevc_mapping() {
        for alias in &["hevc", "HEVC", "h265", "H265", "video/hevc"] {
            let info = mime_for_codec_name(alias).expect("HEVC should be supported");
            assert_eq!(info.mime, MIME_HEVC);
            assert!(info.is_nal_based);
        }
    }

    #[test]
    fn test_vp9_mapping() {
        for alias in &["vp9", "VP9", "vp09", "video/x-vnd.on2.vp9"] {
            let info = mime_for_codec_name(alias).expect("VP9 should be supported");
            assert_eq!(info.mime, MIME_VP9);
            assert!(!info.is_nal_based, "VP9 does not use NAL framing");
        }
    }

    #[test]
    fn test_av1_mapping() {
        for alias in &["av1", "AV1", "av01", "video/av01"] {
            let info = mime_for_codec_name(alias).expect("AV1 should be supported");
            assert_eq!(info.mime, MIME_AV1);
            assert!(!info.is_nal_based, "AV1 does not use NAL framing");
        }
    }

    #[test]
    fn test_unsupported_codecs() {
        for unsupported in &["mpeg4", "mpeg2video", "vp8", "prores", "theora", "vc1"] {
            let result = mime_for_codec_name(unsupported);
            assert!(result.is_err());
            assert!(result.unwrap_err().contains("Unsupported video codec"));
        }
    }
}
