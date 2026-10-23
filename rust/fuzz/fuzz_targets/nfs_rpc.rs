#![no_main]

use libfuzzer_sys::fuzz_target;
use protocols::nfs::client::parse_dir_page;
use protocols::nfs::rpc::XdrReader;

fuzz_target!(|data: &[u8]| {
    let _ = parse_dir_page(data, true);
    let _ = parse_dir_page(data, false);

    // Also exercise sequential XDR decoding primitives over arbitrary bytes.
    let mut reader = XdrReader::new(data);
    let _ = reader.read_u32();
    let _ = reader.read_u64();
    let _ = reader.read_fixed_bytes::<8>();
    let _ = reader.read_bytes();
    let _ = reader.read_string();
});
