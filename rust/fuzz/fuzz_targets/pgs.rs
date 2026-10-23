#![no_main]

use libfuzzer_sys::fuzz_target;
use media_logic::subtitle_pgs::{PgsPacket, parse_pgs_packets, parse_pgs_sup};

fuzz_target!(|data: &[u8]| {
    let _ = parse_pgs_sup(data);
    let _ = parse_pgs_packets(&[PgsPacket {
        pts_ms: 1000,
        data: data.to_vec(),
    }]);
});
