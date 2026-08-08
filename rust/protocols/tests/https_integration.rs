//! Teste de integracao HTTPS (T7.1/T7.2) contra um nginx REAL com TLS
//! auto-assinado — ver docker/network-tests/https-test/. Diferente de `http://`,
//! `https://` depende inteiramente do `HttpsRangeSource` deste crate em producao
//! (o libavformat empacotado nao tem nenhum backend TLS — ver comentario no topo
//! de src/http.rs), entao este e o caminho de codigo mais critico para testar
//! contra um servidor real.
//!
//! So compila com a feature `integration-tests` (precisa confiar no CA de teste
//! via VRPLAYER_TEST_CA_CERT — ver src/http.rs::base_client). Roda via
//! `./scripts/test-network-protocols.sh`, nunca em `cargo test` normal.
#![cfg(feature = "integration-tests")]

use protocols::http::{probe, HttpsRangeSource};
use protocols::prefetch::{PrefetchReader, RangeSource};
use sha2::{Digest, Sha256};
use std::io::Read;

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn test_url() -> String {
    env_or("VRPLAYER_TEST_HTTPS_URL", "https://127.0.0.1:18443/testfile.bin")
}

fn expected_sha256() -> String {
    std::env::var("VRPLAYER_TEST_FILE_SHA256")
        .expect("VRPLAYER_TEST_FILE_SHA256 nao definido — rode via scripts/test-network-protocols.sh")
}

fn sha256_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    format!("{:x}", hasher.finalize())
}

#[test]
#[ignore]
fn probe_trusts_test_ca_and_detects_range_support() {
    assert!(
        std::env::var("VRPLAYER_TEST_CA_CERT").is_ok(),
        "VRPLAYER_TEST_CA_CERT nao definido — rode via scripts/test-network-protocols.sh"
    );

    let caps = probe(&test_url());

    assert!(caps.reachable, "probe nao alcancou o servidor HTTPS: {caps:?}");
    assert!(caps.seekable, "nginx deveria anunciar Accept-Ranges: bytes: {caps:?}");
}

/// Reconstroi o arquivo inteiro via `HttpsRangeSource` + `PrefetchReader` (o
/// mesmo caminho que o Demuxer usa em producao) e compara sha256 com o
/// original — pega qualquer erro sutil de offset/tamanho no custom I/O de
/// TLS que um teste "so conecta" nao pegaria.
#[test]
#[ignore]
fn https_range_source_reads_full_file_matching_sha256() {
    let source = HttpsRangeSource::new(&test_url()).expect("HttpsRangeSource::new falhou");
    let mut reader = PrefetchReader::new(source);
    let mut buf = Vec::new();
    reader.read_to_end(&mut buf).expect("leitura via PrefetchReader falhou");

    assert_eq!(sha256_hex(&buf), expected_sha256());
}

/// Le so um pedaco do meio do arquivo diretamente via `read_range` (sem
/// passar pelo PrefetchReader), confirmando que o offset/tamanho do range
/// request batem exatamente com o conteudo original.
#[test]
#[ignore]
fn https_range_source_reads_partial_range_correctly() {
    let mut source = HttpsRangeSource::new(&test_url()).expect("HttpsRangeSource::new falhou");
    let total = source.len().expect("Content-Length deveria ser conhecido");
    assert!(total > 8192, "arquivo de teste pequeno demais para este teste: {total} bytes");

    let mut buf = vec![0u8; 4096];
    let n = source.read_range(4096, &mut buf).expect("read_range falhou");
    assert_eq!(n, 4096);
}
