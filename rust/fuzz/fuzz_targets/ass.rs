#![no_main]

use libfuzzer_sys::fuzz_target;
use media_logic::subtitle_ass::{parse_ass, strip_ass_override_tags};

fuzz_target!(|data: &[u8]| {
    let text = String::from_utf8_lossy(data);
    let doc = parse_ass(&text);
    let _ = doc.to_subtitle_entries();
    let _ = strip_ass_override_tags(&text);
});
