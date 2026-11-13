//! Testes de integracao SMB (T6.1/T6.3) contra um servidor Samba REAL rodando em
//! Docker — nao um mock. Preenche a lacuna que `docs/phases/PHASE-0.1-MVP.md`
//! documenta honestamente: "nunca testei isto contra um servidor SMB de verdade".
//!
//! Nao rodam com `cargo test` normal (marcados `#[ignore]`) porque dependem do
//! ambiente em `docker/network-tests/` estar de pe. Use
//! `./scripts/test-network-protocols.sh` na raiz do repo, que sobe os containers,
//! exporta as env vars abaixo com os valores corretos e roda
//! `cargo test -p protocols -- --ignored`.
mod common;

use common::{TcpProxy, sha256_hex};
use protocols::prefetch::PrefetchReader;
use protocols::smb::{
    SmbFileSource, SmbTarget, close_pooled_sessions, list_directory, list_shares, scan_has_media,
};
use std::io::Read;

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn auth_target() -> SmbTarget {
    SmbTarget {
        host: env_or("VRPLAYER_TEST_SMB_HOST", "127.0.0.1"),
        port: env_or("VRPLAYER_TEST_SMB_PORT", "14450")
            .parse()
            .expect("VRPLAYER_TEST_SMB_PORT invalido"),
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
    std::env::var("VRPLAYER_TEST_FILE_SHA256").expect(
        "VRPLAYER_TEST_FILE_SHA256 nao definido — rode via scripts/test-network-protocols.sh",
    )
}

/// Autenticacao NTLMv2 real (T6.1) + listagem de shares — tambem cobre o
/// caminho de "status de conexao" da UI (T6.4).
#[test]
#[ignore]
fn list_shares_returns_configured_shares() {
    let shares = list_shares(&auth_target()).expect("list_shares falhou contra o servidor real");
    assert!(
        shares.iter().any(|s| s == "authshare"),
        "shares retornados: {shares:?}"
    );
    assert!(
        shares.iter().any(|s| s == "guestshare"),
        "shares retornados: {shares:?}"
    );
}

/// Navegacao de diretorio dentro de um share autenticado (T6.1).
#[test]
#[ignore]
fn list_directory_finds_test_file() {
    let entries = list_directory(&auth_target(), "").expect("list_directory falhou");
    let file = test_file_name();
    let entry = entries.iter().find(|e| e.name == file).unwrap_or_else(|| {
        panic!(
            "{file} nao encontrado em: {entries:?}",
            entries = entries.iter().map(|e| &e.name).collect::<Vec<_>>()
        )
    });
    assert!(!entry.is_dir);
    assert!(entry.size > 0);
    assert!(entry.mtime > 0, "mtime ausente em {}", entry.name);
    assert!(entries.iter().all(|e| e.name != "." && e.name != ".."));
}

/// Varredura recursiva de poda de pastas (T-folder-pruning) contra o servidor real —
/// `scan/empty-a` only holds `.txt` files (non-media) on the authenticated share, so the
/// expected result is "no media, scan fully completed" — exercises real connect + listing
/// on one reused session, not just the pure extension logic.
#[test]
#[ignore]
fn scan_has_media_reports_no_media_for_fixture_with_only_a_non_media_file() {
    let result = scan_has_media(&auth_target(), "scan/empty-a")
        .expect("scan_has_media falhou contra o servidor real");
    assert!(!result.has_media);
    assert!(result.completed_fully);
}

/// Target going through a local TCP proxy, to count authentications (one TCP
/// connection = one SMB handshake + NTLM) and cut the connection mid-test.
fn proxied_target(proxy: &TcpProxy) -> SmbTarget {
    SmbTarget {
        host: "127.0.0.1".to_string(),
        port: proxy.port(),
        ..auth_target()
    }
}

fn proxy_to_server() -> TcpProxy {
    let direct = auth_target();
    TcpProxy::start(&direct.host, direct.port)
}

/// #146 acceptance: listing the root and scanning three subfolders reuses ONE authenticated session.
#[test]
#[ignore]
fn listing_root_and_scanning_three_folders_authenticates_once() {
    let proxy = proxy_to_server();
    let target = proxied_target(&proxy);

    list_directory(&target, "").expect("list_directory da raiz falhou");
    for folder in ["empty-a", "empty-b", "empty-c"] {
        let result = scan_has_media(&target, &format!("scan/{folder}")).expect("scan falhou");
        assert!(!result.has_media && result.completed_fully, "{folder}");
    }

    assert_eq!(
        proxy.connections(),
        1,
        "as 4 chamadas deveriam reusar a sessao"
    );
    close_pooled_sessions(&target.host, target.port);
}

#[test]
#[ignore]
fn close_pooled_sessions_forces_a_new_authentication() {
    let proxy = proxy_to_server();
    let target = proxied_target(&proxy);

    list_directory(&target, "").expect("primeira listagem falhou");
    close_pooled_sessions(&target.host, target.port);
    list_directory(&target, "").expect("listagem apos fechar falhou");

    assert_eq!(proxy.connections(), 2);
    close_pooled_sessions(&target.host, target.port);
}

/// Regression: with a dead pooled session the scan must NOT conclude "empty folder"
/// (Kotlin would hide the folder for 24 h). It has to reconnect and find the media.
#[test]
#[ignore]
fn scan_has_media_does_not_report_empty_when_the_pooled_connection_is_dropped() {
    let proxy = proxy_to_server();
    let target = proxied_target(&proxy);

    list_directory(&target, "").expect("primeira listagem falhou");
    proxy.drop_connections();
    let result =
        scan_has_media(&target, "scan/with-media").expect("scan apos queda deveria reconectar");

    assert!(result.has_media, "a pasta tem clip.mp4");
    assert!(result.completed_fully);
    assert_eq!(proxy.connections(), 2);
    close_pooled_sessions(&target.host, target.port);
}

#[test]
#[ignore]
fn list_directory_recovers_after_the_pooled_connection_is_dropped() {
    let proxy = proxy_to_server();
    let target = proxied_target(&proxy);

    list_directory(&target, "").expect("primeira listagem falhou");
    proxy.drop_connections();
    let entries = list_directory(&target, "").expect("listagem apos queda deveria reconectar");

    assert!(entries.iter().any(|e| e.name == test_file_name()));
    assert_eq!(proxy.connections(), 2, "uma reconexao, nao mais");
    close_pooled_sessions(&target.host, target.port);
}

/// The pool key includes the password: a wrong password must not reuse the right one's session.
#[test]
#[ignore]
fn wrong_password_does_not_reuse_a_pooled_session() {
    let proxy = proxy_to_server();
    let good = proxied_target(&proxy);
    let bad = SmbTarget {
        password: "wrong-password".to_string(),
        ..good.clone()
    };

    list_directory(&good, "").expect("listagem com a senha certa falhou");
    assert!(list_directory(&bad, "").is_err());
    close_pooled_sessions(&good.host, good.port);
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
    reader
        .read_to_end(&mut buf)
        .expect("leitura via PrefetchReader falhou");

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
    reader
        .read_to_end(&mut buf)
        .expect("leitura via PrefetchReader falhou");

    assert_eq!(sha256_hex(&buf), expected_sha256());
}
