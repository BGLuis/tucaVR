#![no_main]

use libfuzzer_sys::fuzz_target;
use protocols::hls::playlist::parse_playlist;

fuzz_target!(|data: &[u8]| {
    let text = String::from_utf8_lossy(data);
    let _ = parse_playlist(&text, "https://example.com/hls/master.m3u8");
});
