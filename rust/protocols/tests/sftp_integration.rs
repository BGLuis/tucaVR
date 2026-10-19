//! Testes de integracao SFTP (T6.2/T6.3) contra um servidor SFTP REAL
//! (`atmoz/sftp`) rodando em Docker — nao um mock. Preenche a mesma lacuna
//! que `smb_integration.rs`/`ftp_integration.rs` documentam: "nunca testei
//! isto contra um servidor de verdade", incluindo o caminho de autenticacao
//! por CHAVE (T6.2 "Autenticacao: password, key-based"), que os outros dois
//! protocolos nem oferecem.
//!
//! Nao rodam com `cargo test` normal (marcados `#[ignore]`) porque dependem do
//! ambiente em `docker/network-tests/` estar de pe. Use
//! `./scripts/test-network-protocols.sh` na raiz do repo, que sobe os containers,
//! exporta as env vars abaixo com os valores corretos e roda
//! `cargo test -p protocols -- --ignored`.
mod common;

use common::TcpProxy;
use protocols::prefetch::PrefetchReader;
use protocols::sftp::{
    SftpFileSource, SftpTarget, close_pooled_sessions, list_directory, scan_has_media,
};
use sha2::{Digest, Sha256};
use std::io::Read;

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

/// Obtém e fixa (TOFU) o fingerprint do container SFTP de teste.
fn server_fingerprint() -> String {
    static FP: std::sync::LazyLock<String> = std::sync::LazyLock::new(|| {
        let host = env_or("VRPLAYER_TEST_SFTP_HOST", "127.0.0.1");
        let port: u16 = env_or("VRPLAYER_TEST_SFTP_PORT", "12222")
            .parse()
            .expect("VRPLAYER_TEST_SFTP_PORT invalido");
        protocols::sftp::probe_host_key(&host, port)
            .expect("probe_host_key falhou contra container sftp-test")
    });
    FP.clone()
}

/// Alvo com autenticacao por senha (T6.2 "password").
fn password_target() -> SftpTarget {
    SftpTarget {
        host: env_or("VRPLAYER_TEST_SFTP_HOST", "127.0.0.1"),
        port: env_or("VRPLAYER_TEST_SFTP_PORT", "12222")
            .parse()
            .expect("VRPLAYER_TEST_SFTP_PORT invalido"),
        path: String::new(),
        username: env_or("VRPLAYER_TEST_SFTP_USER", "vruser"),
        password: env_or("VRPLAYER_TEST_SFTP_PASS", "vrpass123"),
        private_key: None,
        expected_fingerprint: Some(server_fingerprint()),
    }
}

/// Mesmo alvo, mas trocando senha por chave privada lida do disco (T6.2
/// "key-based") — caminho de codigo distinto de `password_target` dentro de
/// `sftp::connect_and_auth` (branch `authenticate_publickey` em vez de
/// `authenticate_password`).
fn key_target() -> SftpTarget {
    let key_path = env_or("VRPLAYER_TEST_SFTP_KEY_PATH", "");
    assert!(
        !key_path.is_empty(),
        "VRPLAYER_TEST_SFTP_KEY_PATH nao definido — rode via scripts/test-network-protocols.sh"
    );
    let pem = std::fs::read_to_string(&key_path)
        .unwrap_or_else(|e| panic!("nao consegui ler {key_path}: {e}"));
    SftpTarget {
        password: String::new(),
        private_key: Some(pem),
        ..password_target()
    }
}

/// Subdiretorio dentro do home SFTP onde a fixture fica montada (ver
/// `docker/network-tests/docker-compose.yml`, servico `sftp-test`) — o home
/// em si (`/home/vruser`) nao pode receber o mount diretamente por causa do
/// requisito de chroot do OpenSSH (dono root, ver README.md deste
/// diretorio), entao a fixture mora um nivel abaixo, diferente de SMB/FTP
/// onde o "root" listado ja E a fixture.
const FIXTURES_DIR: &str = "fixtures";

fn test_file_name() -> String {
    env_or("VRPLAYER_TEST_SFTP_FILE", "testfile.bin")
}

fn test_file_path() -> String {
    format!("{FIXTURES_DIR}/{}", test_file_name())
}

fn expected_sha256() -> String {
    std::env::var("VRPLAYER_TEST_FILE_SHA256").expect(
        "VRPLAYER_TEST_FILE_SHA256 nao definido — rode via scripts/test-network-protocols.sh",
    )
}

fn sha256_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    format!("{:x}", hasher.finalize())
}

/// Navegacao de diretorio via autenticacao por senha (T6.2) — tambem cobre o
/// caminho de "status de conexao" da UI (T6.4).
#[test]
#[ignore]
fn list_directory_finds_test_file() {
    let entries = list_directory(&password_target(), FIXTURES_DIR)
        .expect("list_directory falhou contra o servidor real");
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
}

/// Varredura recursiva de poda de pastas (T-folder-pruning) contra o servidor real —
/// `scan/empty-a` only holds `.txt` files (one in a subfolder), so the expected result is
/// "no media, scan fully completed" — exercises real connect + listing on one reused session.
#[test]
#[ignore]
fn scan_has_media_reports_no_media_for_fixture_with_only_a_non_media_file() {
    let result = scan_has_media(&password_target(), &scan_dir("empty-a"))
        .expect("scan_has_media falhou contra o servidor real");
    assert!(!result.has_media);
    assert!(result.completed_fully);
}

fn scan_dir(name: &str) -> String {
    format!("{FIXTURES_DIR}/scan/{name}")
}

/// Target going through a local TCP proxy, to count SSH authentications (one TCP
/// connection = one handshake + auth) and cut the connection mid-test.
fn proxied_target(proxy: &TcpProxy) -> SftpTarget {
    SftpTarget {
        host: "127.0.0.1".to_string(),
        port: proxy.port(),
        ..password_target()
    }
}

fn proxy_to_server() -> TcpProxy {
    let direct = password_target();
    TcpProxy::start(&direct.host, direct.port)
}

/// #146 acceptance: listing the root and scanning three subfolders reuses ONE
/// authenticated session. Each TCP connection the proxy accepts is one SSH authentication.
#[test]
#[ignore]
fn listing_root_and_scanning_three_folders_authenticates_once() {
    let proxy = proxy_to_server();
    let target = proxied_target(&proxy);

    let root = list_directory(&target, "").expect("list_directory da raiz falhou");
    assert!(root.iter().any(|e| e.name == FIXTURES_DIR));
    for folder in ["empty-a", "empty-b", "empty-c"] {
        let result = scan_has_media(&target, &scan_dir(folder)).expect("scan falhou");
        assert!(!result.has_media && result.completed_fully, "{folder}");
    }

    assert_eq!(
        proxy.connections(),
        1,
        "as 4 chamadas deveriam reusar a sessao"
    );
    close_pooled_sessions(&target.host, target.port);
}

/// `close_pooled_sessions` (server deleted by the user) drops the session: the next
/// call has to authenticate again.
#[test]
#[ignore]
fn close_pooled_sessions_forces_a_new_authentication() {
    let proxy = proxy_to_server();
    let target = proxied_target(&proxy);

    list_directory(&target, FIXTURES_DIR).expect("primeira listagem falhou");
    close_pooled_sessions(&target.host, target.port);
    list_directory(&target, FIXTURES_DIR).expect("listagem apos fechar falhou");

    assert_eq!(proxy.connections(), 2);
    close_pooled_sessions(&target.host, target.port);
}

/// Dead pooled session (Wi-Fi dropped inside the TTL): the listing reconnects by itself.
#[test]
#[ignore]
fn list_directory_recovers_after_the_pooled_connection_is_dropped() {
    let proxy = proxy_to_server();
    let target = proxied_target(&proxy);

    list_directory(&target, FIXTURES_DIR).expect("primeira listagem falhou");
    proxy.drop_connections();
    let entries =
        list_directory(&target, FIXTURES_DIR).expect("listagem apos queda deveria reconectar");

    assert!(entries.iter().any(|e| e.name == test_file_name()));
    assert_eq!(proxy.connections(), 2, "uma reconexao, nao mais");
    close_pooled_sessions(&target.host, target.port);
}

/// Regression: with a dead pooled session the scan must NOT conclude "empty folder"
/// (Kotlin would hide the folder for 24 h). It has to reconnect and find the media.
#[test]
#[ignore]
fn scan_has_media_does_not_report_empty_when_the_pooled_connection_is_dropped() {
    let proxy = proxy_to_server();
    let target = proxied_target(&proxy);

    list_directory(&target, FIXTURES_DIR).expect("primeira listagem falhou");
    proxy.drop_connections();
    let result = scan_has_media(&target, &scan_dir("with-media"))
        .expect("scan apos queda deveria reconectar");

    assert!(result.has_media, "a pasta tem clip.mp4");
    assert!(result.completed_fully);
    assert_eq!(proxy.connections(), 2);
    close_pooled_sessions(&target.host, target.port);
}

/// If the server is gone the error propagates (Kotlin treats it as "assume media",
/// short TTL) instead of becoming `exhausted_empty`.
#[test]
#[ignore]
fn scan_has_media_returns_err_when_the_server_is_unreachable() {
    let target = SftpTarget {
        host: "127.0.0.1".to_string(),
        port: 1,
        ..password_target()
    };
    assert!(scan_has_media(&target, FIXTURES_DIR).is_err());
}

/// The pool key includes the credential: once a session authenticated with the right
/// password sits in the pool, a wrong password must fail instead of reusing it.
#[test]
#[ignore]
fn wrong_password_does_not_reuse_a_pooled_session() {
    let proxy = proxy_to_server();
    let good = proxied_target(&proxy);
    let bad = SftpTarget {
        password: "wrong-password".to_string(),
        ..good.clone()
    };

    list_directory(&good, FIXTURES_DIR).expect("listagem com a senha certa falhou");
    assert!(list_directory(&bad, FIXTURES_DIR).is_err());
    close_pooled_sessions(&good.host, good.port);
}

/// Leitura de arquivo via `SftpFileSource` + `PrefetchReader` (T6.3) com
/// autenticacao por senha — reconstroi o arquivo inteiro via reads
/// posicionais (SFTP suporta random access nativo, ao contrario de FTP) e
/// compara sha256 byte a byte com o original.
#[test]
#[ignore]
fn sftp_file_source_reads_full_file_matching_sha256_password() {
    let mut target = password_target();
    target.path = test_file_path();

    let source = SftpFileSource::open(&target).expect("SftpFileSource::open falhou (senha)");
    let mut reader = PrefetchReader::new(source);
    let mut buf = Vec::new();
    reader
        .read_to_end(&mut buf)
        .expect("leitura via PrefetchReader falhou");

    assert_eq!(sha256_hex(&buf), expected_sha256());
}

/// Mesmo teste acima, mas via autenticacao por CHAVE PRIVADA (T6.2
/// "key-based") — caminho de codigo distinto que a suite de senha nao
/// exercita: `SftpTarget::private_key` preenchido, `connect_and_auth` usa
/// `russh::keys::decode_secret_key` + `authenticate_publickey` em vez de
/// `authenticate_password`.
#[test]
#[ignore]
fn sftp_file_source_reads_full_file_matching_sha256_key_auth() {
    let mut target = key_target();
    target.path = test_file_path();

    let source = SftpFileSource::open(&target).expect("SftpFileSource::open falhou (chave)");
    let mut reader = PrefetchReader::new(source);
    let mut buf = Vec::new();
    reader
        .read_to_end(&mut buf)
        .expect("leitura via PrefetchReader falhou");

    assert_eq!(sha256_hex(&buf), expected_sha256());
}

/// Listagem de diretorio via autenticacao por chave — mesmo caminho de
/// codigo do teste de leitura acima, mas exercitando `list_directory` em vez
/// de `SftpFileSource`.
#[test]
#[ignore]
fn list_directory_finds_test_file_key_auth() {
    let entries = list_directory(&key_target(), FIXTURES_DIR)
        .expect("list_directory falhou (chave) contra o servidor real");
    let file = test_file_name();
    assert!(
        entries.iter().any(|e| e.name == file),
        "entries: {entries:?}",
        entries = entries.iter().map(|e| &e.name).collect::<Vec<_>>()
    );
}

/// Forca blocos pequenos (64KB em vez dos 4MB padrao) pra multiplicar o
/// numero de chamadas de `read_range` que `PrefetchReader` emite sobre o
/// mesmo handle de arquivo SFTP — exercita `SftpFileSource::try_read_range`
/// disparando varios `SSH_FXP_READ` posicionais (`RawSftpSession::read`) em
/// sequencia sobre o mesmo handle, nao so uma leitura sequencial de ponta a
/// ponta num unico bloco de 4MB.
#[test]
#[ignore]
fn sftp_file_source_reads_full_file_with_small_blocks() {
    let mut target = password_target();
    target.path = test_file_path();

    let source = SftpFileSource::open(&target).expect("SftpFileSource::open falhou");
    let mut reader = PrefetchReader::with_block_size(source, 64 * 1024);
    let mut buf = Vec::new();
    reader
        .read_to_end(&mut buf)
        .expect("leitura via PrefetchReader falhou");

    assert_eq!(sha256_hex(&buf), expected_sha256());
}

/// Le o final do arquivo primeiro (via `Seek` do `PrefetchReader`), depois o
/// inicio — forca `SftpFileSource::read_range` a pedir um offset ALTO antes
/// de um offset BAIXO na mesma sessao. Como `read_range` usa
/// `RawSftpSession::read(handle, offset, len)` posicional (sem cursor de
/// arquivo — ver doc do modulo `sftp`), nao ha estado de "posicao atual" pra
/// realinhar: cada chamada carrega seu proprio offset explicito no pacote
/// `SSH_FXP_READ`. Isso e exatamente a vantagem de random access nativo que
/// o doc (secao 6, `[!CAUTION]`) contrasta com o REST+RETR caro do FTP — o
/// teste prova que o salto pra tras devolve os bytes corretos, nao so que o
/// codigo nao trava.
#[test]
#[ignore]
fn sftp_file_source_backward_seek_after_forward_read() {
    use std::io::{Seek, SeekFrom};

    let mut target = password_target();
    target.path = test_file_path();

    let source = SftpFileSource::open(&target).expect("SftpFileSource::open falhou");
    let mut reader = PrefetchReader::with_block_size(source, 64 * 1024);

    // Le um pedaco perto do fim do arquivo (256KB) primeiro.
    reader
        .seek(SeekFrom::Start(200_000))
        .expect("seek para o fim falhou");
    let mut tail = [0u8; 1024];
    reader.read_exact(&mut tail).expect("leitura do fim falhou");

    // Agora volta pro inicio e le o arquivo inteiro.
    reader
        .seek(SeekFrom::Start(0))
        .expect("seek de volta pro inicio falhou");
    let mut buf = Vec::new();
    reader
        .read_to_end(&mut buf)
        .expect("leitura completa apos salto pra tras falhou");

    assert_eq!(sha256_hex(&buf), expected_sha256());
}

/// T6.2 / #57: Primeira conexão sem fingerprint armazenado retorna erro solicitando
/// confirmação do usuário com o fingerprint apresentado pelo servidor.
#[test]
#[ignore]
fn tofu_first_connection_without_fingerprint_returns_needs_confirmation() {
    let mut target = password_target();
    target.expected_fingerprint = None;

    let err = list_directory(&target, "")
        .expect_err("primeira conexao sem fingerprint deve exigir confirmacao");
    assert!(
        err.contains("needs confirmation") || err.contains("ERROR:HOST_KEY_CONFIRM:"),
        "erro esperado de confirmacao de chave, obteve: {err}"
    );
}

/// T6.2 / #57: Conexão com fingerprint diferente do esperado (ou container recriado com nova chave)
/// falha com erro de identidade alterada (proteção contra MITM).
#[test]
#[ignore]
fn different_fingerprint_fails_with_identity_changed() {
    let mut target = password_target();
    target.expected_fingerprint =
        Some("SHA256:0000000000000000000000000000000000000000000".to_string());

    let err = list_directory(&target, "").expect_err("conexao com host key divergente deve falhar");
    assert!(
        err.contains("server identity changed") || err.contains("ERROR:HOST_KEY_CHANGED:"),
        "erro esperado de identidade alterada, obteve: {err}"
    );
}
