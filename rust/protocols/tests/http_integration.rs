//! Teste de integracao HTTP (T7.1) contra um nginx REAL (plain HTTP, sem TLS)
//! rodando em Docker — ver docker/network-tests/http-test/. `http://` puro e
//! servido nativamente pelo libavformat em producao (nao passa pelo custom I/O
//! deste crate — ver nota em src/http.rs), mas `probe()` E usado em producao
//! independente do esquema (T7.3, UI mostra "suporta seek" antes de tocar), entao
//! vale testa-lo contra um servidor real aqui.
//!
//! `#[ignore]` — depende do ambiente Docker. Use
//! `./scripts/test-network-protocols.sh` na raiz do repo.
use protocols::http::probe;

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn test_url() -> String {
    env_or("VRPLAYER_TEST_HTTP_URL", "http://127.0.0.1:18080/testfile.bin")
}

#[test]
#[ignore]
fn probe_detects_real_nginx_range_support_and_size() {
    let caps = probe(&test_url());

    assert!(caps.reachable, "probe nao alcancou o servidor: {caps:?}");
    assert!(caps.seekable, "nginx deveria anunciar Accept-Ranges: bytes: {caps:?}");
    assert!(caps.content_length.unwrap_or(0) > 0, "content_length ausente/zero: {caps:?}");
}
