// Parser do box `hvcC` (ISO/IEC 14496-15), formato de extradata que o FFmpeg
// expoe em AVCodecParameters.extradata para streams H.265/HEVC extraidos de
// MP4/MKV. Estrutura bem diferente do `avcC` do H.264 (ver h264.rs):
// um cabecalho fixo de 23 bytes seguido de N arrays de NAL units (VPS/SPS/PPS
// entre outros), cada um com seu proprio contador de unidades.
pub fn extract_vps_sps_pps(extradata: &[u8]) -> Option<Vec<u8>> {
    const FIXED_HEADER_LEN: usize = 23;
    if extradata.len() < FIXED_HEADER_LEN || extradata[0] != 1 {
        return None;
    }
    let num_arrays = extradata[22];
    let mut offset = FIXED_HEADER_LEN;
    let mut out = Vec::new();

    for _ in 0..num_arrays {
        // 1 byte: array_completeness(1) + reserved(1) + NAL_unit_type(6)
        if offset + 3 > extradata.len() { return None; }
        offset += 1;
        let num_nalus = ((extradata[offset] as usize) << 8) | (extradata[offset + 1] as usize);
        offset += 2;

        for _ in 0..num_nalus {
            if offset + 2 > extradata.len() { return None; }
            let nalu_len = ((extradata[offset] as usize) << 8) | (extradata[offset + 1] as usize);
            offset += 2;
            if offset + nalu_len > extradata.len() { return None; }
            out.extend_from_slice(&[0, 0, 0, 1]);
            out.extend_from_slice(&extradata[offset..offset + nalu_len]);
            offset += nalu_len;
        }
    }

    if out.is_empty() { None } else { Some(out) }
}
