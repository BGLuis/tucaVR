#![no_main]

use libfuzzer_sys::fuzz_target;
use protocols::dlna::{
    extract_result_from_soap_response, parse_device_description, parse_didl_lite,
    parse_duration_to_seconds,
};

fuzz_target!(|data: &[u8]| {
    let text = String::from_utf8_lossy(data);
    let _ = parse_didl_lite(&text);
    let _ = parse_duration_to_seconds(&text);
    let _ = parse_device_description(&text, "http://192.168.1.100:8200/rootDesc.xml");
    if let Ok(inner) = extract_result_from_soap_response(&text) {
        let _ = parse_didl_lite(&inner);
    }
});
