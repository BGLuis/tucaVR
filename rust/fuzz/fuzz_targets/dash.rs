#![no_main]

use libfuzzer_sys::fuzz_target;
use protocols::dash::manifest::{parse_iso8601_duration, parse_mpd, resolve_template_url};

fuzz_target!(|data: &[u8]| {
    let text = String::from_utf8_lossy(data);
    if let Ok(manifest) = parse_mpd(&text, "https://stream.example.com/live/manifest.mpd") {
        let _ = manifest.video_representations();
    }
    let _ = parse_iso8601_duration(&text);
    let _ = resolve_template_url(&text, "video_1080p", 42, 192000);
});
