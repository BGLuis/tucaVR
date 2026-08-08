//! Testes de integracao SMB (T6.1/T6.3) contra um servidor Samba REAL rodando em
//! Docker — nao um mock. Preenche a lacuna que `docs/phases/PHASE-0.1-MVP.md`
//! documenta honestamente: "nunca testei isto contra um servidor SMB de verdade".
//!
//! Nao rodam com `cargo test` normal (marcados `#[ignore]`) porque dependem do
//! ambiente em `docker/network-tests/` estar de pe. Use
//! `./scripts/test-network-protocols.sh` na raiz do repo, que sobe os containers,
//! exporta as env vars abaixo com os valores corretos e roda
//! `cargo test -p protocols -- --ignored`.
use protocols::prefetch::PrefetchReader;
use protocols::smb::{list_directory, list_shares, SmbFileSource, SmbTarget};
use sha2::{Digest, Sha256};
use std::io::Read;

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn auth_target() -> SmbTarget {
    SmbTarget {
        host: env_or("VRPLAYER_TEST_SMB_HOST", "127.0.0.1"),
        port: env_or("VRPLAYER_TEST_SMB_PORT", "14450").parse().expect("VRPLAYER_TEST_SMB_PORT invalido"),
        share: env_or("VRPLAYER_TEST_SMB_SHARE_AUTH", "authshare"),
        path: String::new(),
        username: env_or("VRPLAYER_TEST_SMB_USER", "vruser"),
        password: env_or("VRPLAYER_TEST_SMB_PASS", "vrpass123"),
        domain: String::new(),
    }
}

fn guest_target() -> SmbTarget {
    SmbTarget {
        share: env_or("VRPLAYER_TEST_SMB_SHARE_GUEST", "guestshare"),
        username: String::new(),
        password: String::new(),
        ..auth_target()
    }
}

fn test_file_name() -> String {
    env_or("VRPLAYER_TEST_SMB_FILE", "testfile.bin")
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

/// Autenticacao NTLMv2 real (T6.1) + listagem de shares — tambem cobre o
/// caminho de "status de conexao" da UI (T6.4).
#[test]
#[ignore]
fn list_shares_returns_configured_shares() {
    let shares = list_shares(&auth_target()).expect("list_shares falhou contra o servidor real");
    assert!(shares.iter().any(|s| s == "authshare"), "shares retornados: {shares:?}");
    assert!(shares.iter().any(|s| s == "guestshare"), "shares retornados: {shares:?}");
}

/// Navegacao de diretorio dentro de um share autenticado (T6.1).
#[test]
#[ignore]
fn list_directory_finds_test_file() {
    let entries = list_directory(&auth_target(), "").expect("list_directory falhou");
    let file = test_file_name();
    let entry = entries.iter().find(|e| e.name == file).unwrap_or_else(|| panic!("{file} nao encontrado em: {entries:?}", entries = entries.iter().map(|e| &e.name).collect::<Vec<_>>()));
    assert!(!entry.is_dir);
    assert!(entry.size > 0);
}

/// Leitura de arquivo via `SmbFileSource` + `PrefetchReader` (T6.3) num share
/// autenticado — reconstroi o arquivo inteiro via reads posicionais e compara
/// sha256 byte a byte com o original, para pegar qualquer corrupcao sutil de
/// offset/tamanho que um teste "so conecta" nao pegaria.
#[test]
#[ignore]
fn smb_file_source_reads_full_file_matching_sha256_authenticated() {
    let mut target = auth_target();
    target.path = test_file_name();

    let source = SmbFileSource::open(&target).expect("SmbFileSource::open falhou (auth)");
    let mut reader = PrefetchReader::new(source);
    let mut buf = Vec::new();
    reader.read_to_end(&mut buf).expect("leitura via PrefetchReader falhou");

    assert_eq!(sha256_hex(&buf), expected_sha256());
}

/// Mesmo teste acima, mas pelo share guest/anonimo (username vazio) — cobre o
/// outro caminho de autenticacao que T6.1 promete ("guest/anonimo").
#[test]
#[ignore]
fn smb_file_source_reads_full_file_matching_sha256_guest() {
    let mut target = guest_target();
    target.path = test_file_name();

    let source = SmbFileSource::open(&target).expect("SmbFileSource::open falhou (guest)");
    let mut reader = PrefetchReader::new(source);
    let mut buf = Vec::new();
    reader.read_to_end(&mut buf).expect("leitura via PrefetchReader falhou");

    assert_eq!(sha256_hex(&buf), expected_sha256());
}
