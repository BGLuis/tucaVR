//! Teste de integracao HLS fMP4 (EXT-X-MAP) contra stream gerado por ffmpeg
//! e servido por nginx real em Docker — ver #177.
//!
//! `#[ignore]` — depende do ambiente Docker + ffmpeg gerando as fixtures. Use
//! `./scripts/test-network-protocols.sh` na raiz do repo.
use protocols::hls::HlsStreamSource;
use std::io::Read;

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn base_url() -> String {
    env_or("VRPLAYER_TEST_HTTP_URL_BASE", "http://127.0.0.1:18080")
}

#[test]
#[ignore]
fn hls_fmp4_reads_init_and_all_segments_from_real_server() {
    let m3u8_url = format!("{}/hls_fmp4/playlist.m3u8", base_url());
    let mut source =
        HlsStreamSource::open(&m3u8_url).expect("open deveria funcionar contra o HLS fMP4 real");

    assert!(source.total_duration() > 0.0);

    let mut total = 0usize;
    let mut buf = vec![0u8; 16 * 1024];
    loop {
        let n = source
            .read(&mut buf)
            .expect("read não deveria falhar contra o servidor real");
        if n == 0 {
            break;
        }
        total += n;
    }

    assert!(
        total > 20_000,
        "esperava init segment + segmentos de mídia, só recebeu {total} bytes"
    );
}
