pub fn convert_avcc_to_annexb(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(data.len() + 16);
    let mut offset = 0;
    while offset + 4 <= data.len() {
        let len = ((data[offset] as usize) << 24)
                | ((data[offset+1] as usize) << 16)
                | ((data[offset+2] as usize) << 8)
                | (data[offset+3] as usize);
        offset += 4;
        if offset + len > data.len() { break; }
        out.extend_from_slice(&[0, 0, 0, 1]);
        out.extend_from_slice(&data[offset..offset+len]);
        offset += len;
    }
    out
}
