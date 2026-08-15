// Parser do box `av1C` (AV1 Codec ISO Media File Format Binding), extradata
// que o FFmpeg expoe em AVCodecParameters.extradata para streams AV1
// extraidos de MP4/MKV/WebM. Cabecalho fixo de 4 bytes seguido de
// `configOBUs` (Sequence Header OBU + Metadata OBUs opcionais) — ja em
// framing OBU nativo (cada OBU carrega seu proprio tamanho), sem NAL/Annex-B
// como em h264.rs/hevc.rs.
pub fn extract_config_obus(extradata: &[u8]) -> Option<Vec<u8>> {
    const FIXED_HEADER_LEN: usize = 4;
    if extradata.len() <= FIXED_HEADER_LEN || (extradata[0] >> 7) != 1 {
        return None;
    }
    let obus = &extradata[FIXED_HEADER_LEN..];
    if obus.is_empty() { None } else { Some(obus.to_vec()) }
}
