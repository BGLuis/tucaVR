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
}
