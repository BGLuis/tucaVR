//! Parser do box `av1C` (AV1 Codec ISO Media File Format Binding), extradata
//! que o FFmpeg expõe em `AVCodecParameters.extradata` para streams AV1
//! extraídos de MP4/MKV/WebM. Cabeçalho fixo de 4 bytes seguido de
//! `configOBUs` (Sequence Header OBU + Metadata OBUs opcionais) — já em
//! framing OBU nativo (cada OBU carrega seu próprio tamanho), sem NAL/Annex-B
//! como em h264.rs/hevc.rs.

pub fn extract_config_obus(extradata: &[u8]) -> Option<Vec<u8>> {
    const FIXED_HEADER_LEN: usize = 4;
    if extradata.len() <= FIXED_HEADER_LEN || (extradata[0] >> 7) != 1 {
        return None;
    }
    let obus = &extradata[FIXED_HEADER_LEN..];
    if obus.is_empty() {
        None
    } else {
        Some(obus.to_vec())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_config_obus_empty_input() {
        assert_eq!(extract_config_obus(&[]), None);
    }

    #[test]
    fn test_extract_config_obus_short_header() {
        assert_eq!(extract_config_obus(&[0x81, 0x00, 0x00]), None);
    }

    #[test]
    fn test_extract_config_obus_marker_bit_zero() {
        // Marcador (bit 7) deve ser 1. Se for 0 (0x01), deve retornar None.
        let data = [0x01, 0x00, 0x00, 0x00, 0x0A, 0x0B];
        assert_eq!(extract_config_obus(&data), None);
    }

    #[test]
    fn test_extract_config_obus_header_only_no_payload() {
        // Apenas 4 bytes de cabeçalho, sem OBUs subsequentes.
        let data = [0x81, 0x00, 0x0C, 0x00];
        assert_eq!(extract_config_obus(&data), None);
    }

    #[test]
    fn test_extract_config_obus_valid_sequence_header() {
        // Cabeçalho av1C sintético:
        // Byte 0: marker=1, version=1 (0x81)
        // Byte 1: seq_profile=0, seq_level_idx_0=8 (0x08)
        // Byte 2: tier=0, 8-bit, 4:2:0 (0x00)
        // Byte 3: delay present=0 (0x00)
        // Payload: Sequence Header OBU sintético [0x0A, 0x0E, 0x00, 0x00, 0x10]
        let mut extradata = vec![0x81, 0x08, 0x00, 0x00];
        let synthetic_obu = vec![0x0A, 0x0E, 0x00, 0x00, 0x10];
        extradata.extend_from_slice(&synthetic_obu);

        let result = extract_config_obus(&extradata);
        assert_eq!(result, Some(synthetic_obu));
    }

    #[test]
    fn test_extract_config_obus_multiple_obus() {
        // Cabeçalho + Sequence Header OBU + Metadata OBU
        let mut extradata = vec![0x81, 0x00, 0x00, 0x00];
        let payload = vec![0x0A, 0x01, 0xAA, 0x05, 0x02, 0xBB, 0xCC];
        extradata.extend_from_slice(&payload);

        let result = extract_config_obus(&extradata);
        assert_eq!(result, Some(payload));
    }
}
