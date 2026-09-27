//! Deteccao de HDR a partir da transfer characteristic (EOTF) que o
//! container/codec reporta. Extraido pra fora de `core` (que depende de
//! `ffmpeg-next` e do NDK, nao compila em host — ver `lib.rs`) so pra
//! fixar com teste de host o mapeamento entre os codigos que o FFmpeg usa
//! e a decisao "isto e HDR (PQ/HLG) ou SDR" que o resto do pipeline
//! consome (color keys do MediaCodec, escolha de shader/pipeline Vulkan).
//!
//! `from_avcol_trc` recebe o valor NUMERICO do enum `AVColorTransferCharacteristic`
//! do FFmpeg, nao o tipo do crate `ffmpeg-next` — este crate deliberadamente
//! nao depende dele (ver Cargo.toml). Esses numeros sao estaveis (documentados
//! no proprio `pixfmt.h` do FFmpeg como casando com a tabela de transfer
//! characteristics do ISO/IEC 23091-2, nao sao detalhe de implementacao de
//! uma lib): `AVCOL_TRC_SMPTE2084 = 16` (PQ, usado por HDR10/HDR10+/Dolby
//! Vision) e `AVCOL_TRC_ARIB_STD_B67 = 18` (HLG) sao os dois casos HDR;
//! qualquer outro valor (incluindo BT709=1 e Unspecified=2) e SDR.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransferFunction {
    Sdr,
    /// SMPTE ST 2084 (Perceptual Quantizer) — HDR10/HDR10+/Dolby Vision.
    Pq,
    /// ARIB STD-B67 (Hybrid Log-Gamma).
    Hlg,
}

impl TransferFunction {
    pub fn is_hdr(self) -> bool {
        !matches!(self, TransferFunction::Sdr)
    }

    /// Codigo repassado ao shader de video pelo bridge (`get_video_is_hdr`):
    /// 0 = SDR, 1 = PQ, 2 = HLG. Qualquer valor != 0 continua significando "HDR".
    pub fn shader_code(self) -> u32 {
        match self {
            TransferFunction::Sdr => 0,
            TransferFunction::Pq => 1,
            TransferFunction::Hlg => 2,
        }
    }
}

/// `raw` = valor numerico de `AVColorTransferCharacteristic` (ver o
/// comentario do modulo). `AVCOL_TRC_SMPTE2084 = 16` -> PQ,
/// `AVCOL_TRC_ARIB_STD_B67 = 18` -> HLG, qualquer outro valor -> SDR
/// (inclui BT709, unspecified, e qualquer codigo desconhecido/reservado —
/// tratar como SDR e o fallback seguro, ja que e o que o pipeline sempre
/// assumiu implicitamente antes desta deteccao existir).
pub fn from_avcol_trc(raw: i32) -> TransferFunction {
    match raw {
        16 => TransferFunction::Pq,
        18 => TransferFunction::Hlg,
        _ => TransferFunction::Sdr,
    }
}

/// Chaves de cor do `MediaFormat` (valores de `android.media.MediaFormat`).
const COLOR_STANDARD_BT709: i32 = 1;
const COLOR_STANDARD_BT601_PAL: i32 = 2;
const COLOR_STANDARD_BT601_NTSC: i32 = 4;
const COLOR_STANDARD_BT2020: i32 = 6;
const COLOR_RANGE_FULL: i32 = 1;
const COLOR_RANGE_LIMITED: i32 = 2;

/// Maior altura tratada como SD quando o container nao declara a matriz.
const SD_MAX_HEIGHT: u32 = 576;

/// `COLOR_STANDARD_*` do `MediaFormat` para o stream.
///
/// `avcol_spc` = valor numerico de `AVColorSpace` (BT709=1, UNSPECIFIED=2,
/// BT470BG=5, SMPTE170M=6, BT2020_NCL=9, BT2020_CL=10). HDR e sempre BT.2020.
/// Sem matriz declarada, SD (<= 576 linhas) cai em BT.601 e o resto em
/// BT.709 — a convencao de ffmpeg/mpv; declarar 709 pra SD desloca matiz em
/// pele e vermelhos.
pub fn media_format_color_standard(
    transfer: TransferFunction,
    avcol_spc: i32,
    height: u32,
) -> i32 {
    if transfer.is_hdr() {
        return COLOR_STANDARD_BT2020;
    }
    match avcol_spc {
        1 => COLOR_STANDARD_BT709,
        5 => COLOR_STANDARD_BT601_PAL,
        6 => COLOR_STANDARD_BT601_NTSC,
        9 | 10 => COLOR_STANDARD_BT2020,
        _ if height <= SD_MAX_HEIGHT => COLOR_STANDARD_BT601_PAL,
        _ => COLOR_STANDARD_BT709,
    }
}

/// `COLOR_RANGE_*` do `MediaFormat`, ou `None` quando o container nao declara
/// a faixa — nesse caso o decoder decide (VUI do bitstream). Forcar "limited"
/// pra tudo lavava o preto de conteudo full range (celular, captura).
///
/// `avcol_range` = valor numerico de `AVColorRange` (MPEG=1, JPEG=2).
pub fn media_format_color_range(avcol_range: i32) -> Option<i32> {
    match avcol_range {
        1 => Some(COLOR_RANGE_LIMITED),
        2 => Some(COLOR_RANGE_FULL),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn smpte2084_is_pq_and_counts_as_hdr() {
        assert_eq!(from_avcol_trc(16), TransferFunction::Pq);
        assert!(from_avcol_trc(16).is_hdr());
    }

    #[test]
    fn arib_std_b67_is_hlg_and_counts_as_hdr() {
        assert_eq!(from_avcol_trc(18), TransferFunction::Hlg);
        assert!(from_avcol_trc(18).is_hdr());
    }

    #[test]
    fn bt709_and_unspecified_are_sdr_not_hdr() {
        assert_eq!(from_avcol_trc(1), TransferFunction::Sdr);
        assert_eq!(from_avcol_trc(2), TransferFunction::Sdr);
        assert!(!from_avcol_trc(1).is_hdr());
        assert!(!from_avcol_trc(2).is_hdr());
    }

    #[test]
    fn unknown_or_reserved_codes_default_to_sdr() {
        assert_eq!(from_avcol_trc(999), TransferFunction::Sdr);
        assert_eq!(from_avcol_trc(0), TransferFunction::Sdr);
        assert_eq!(from_avcol_trc(-1), TransferFunction::Sdr);
    }

    #[test]
    fn shader_code_distinguishes_pq_from_hlg_and_keeps_nonzero_as_hdr() {
        assert_eq!(TransferFunction::Sdr.shader_code(), 0);
        assert_eq!(TransferFunction::Pq.shader_code(), 1);
        assert_eq!(TransferFunction::Hlg.shader_code(), 2);
        for t in [TransferFunction::Sdr, TransferFunction::Pq, TransferFunction::Hlg] {
            assert_eq!(t.shader_code() != 0, t.is_hdr());
        }
    }

    #[test]
    fn hdr_is_always_bt2020_regardless_of_declared_matrix() {
        assert_eq!(media_format_color_standard(TransferFunction::Pq, 1, 2160), 6);
        assert_eq!(media_format_color_standard(TransferFunction::Hlg, 2, 1080), 6);
    }

    #[test]
    fn declared_matrix_wins_over_resolution() {
        assert_eq!(media_format_color_standard(TransferFunction::Sdr, 1, 480), 1);
        assert_eq!(media_format_color_standard(TransferFunction::Sdr, 5, 1080), 2);
        assert_eq!(media_format_color_standard(TransferFunction::Sdr, 6, 1080), 4);
        assert_eq!(media_format_color_standard(TransferFunction::Sdr, 9, 1080), 6);
    }

    #[test]
    fn undeclared_matrix_falls_back_by_resolution() {
        assert_eq!(media_format_color_standard(TransferFunction::Sdr, 2, 576), 2);
        assert_eq!(media_format_color_standard(TransferFunction::Sdr, 2, 720), 1);
        assert_eq!(media_format_color_standard(TransferFunction::Sdr, 0, 2160), 1);
    }

    #[test]
    fn color_range_is_only_forced_when_the_container_declares_it() {
        assert_eq!(media_format_color_range(1), Some(2));
        assert_eq!(media_format_color_range(2), Some(1));
        assert_eq!(media_format_color_range(0), None);
        assert_eq!(media_format_color_range(-1), None);
    }
}
