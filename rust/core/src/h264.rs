pub fn extract_sps_pps(extradata: &[u8]) -> Option<Vec<u8>> {
    if extradata.len() < 7 || extradata[0] != 1 {
        return None;
    }
    let mut out = Vec::new();
    let num_sps = extradata[5] & 0x1f;
    let mut offset = 6;
    for _ in 0..num_sps {
        if offset + 2 > extradata.len() { return None; }
        let sps_len = ((extradata[offset] as usize) << 8) | (extradata[offset+1] as usize);
        offset += 2;
        if offset + sps_len > extradata.len() { return None; }
        out.extend_from_slice(&[0, 0, 0, 1]);
        out.extend_from_slice(&extradata[offset..offset+sps_len]);
        offset += sps_len;
    }
    if offset >= extradata.len() { return None; }
    let num_pps = extradata[offset];
    offset += 1;
    for _ in 0..num_pps {
        if offset + 2 > extradata.len() { return None; }
        let pps_len = ((extradata[offset] as usize) << 8) | (extradata[offset+1] as usize);
        offset += 2;
        if offset + pps_len > extradata.len() { return None; }
        out.extend_from_slice(&[0, 0, 0, 1]);
        out.extend_from_slice(&extradata[offset..offset+pps_len]);
        offset += pps_len;
    }
    Some(out)
}
