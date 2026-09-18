//! Teste de integracao WebDAV (Fase 0.4 Secao 3, achado R-04 de
//! docs/reports/PHASE-0.4-08-VERIFICACAO-PROFUNDA.md) contra um servidor WebDAV REAL
//! (Apache + mod_dav via `bytemark/webdav`) rodando em Docker — nao um mock. Os testes
//! existentes em src/webdav/mod.rs usam `httpmock` (in-process); este arquivo preenche a
//! lacuna de nunca ter sido testado contra um servidor real (PROPFIND, auth Basic, GET
//! com Range), mesmo padrao dos demais protocolos em `rust/protocols/tests/`.
//!
//! `#[ignore]` — depende do ambiente Docker. Use `./scripts/test-network-protocols.sh`
//! na raiz do repo, que sobe os containers, exporta as env vars abaixo e roda
//! `cargo test -p protocols -- --ignored`.
use protocols::prefetch::RangeSource;
use protocols::webdav::{list_directory, scan_has_media, WebdavFileSource, WebdavTarget};
use sha2::{Digest, Sha256};

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn target(file_path: &str) -> WebdavTarget {
    WebdavTarget {
        host: env_or("VRPLAYER_TEST_WEBDAV_HOST", "127.0.0.1"),
        port: env_or("VRPLAYER_TEST_WEBDAV_PORT", "18099").parse().expect("VRPLAYER_TEST_WEBDAV_PORT invalido"),
        base_path: String::new(),
        file_path: file_path.to_string(),
        username: env_or("VRPLAYER_TEST_WEBDAV_USER", "vruser"),
        password: env_or("VRPLAYER_TEST_WEBDAV_PASS", "vrpass123"),
        use_https: false,
        accept_invalid_certs: false,
    }
}

fn test_file_name() -> String {
    env_or("VRPLAYER_TEST_WEBDAV_FILE", "testfile.bin")
}

fn expected_sha256() -> String {
    std::env::var("VRPLAYER_TEST_WEBDAV_FILE_SHA256")
        .expect("VRPLAYER_TEST_WEBDAV_FILE_SHA256 nao definido — rode via scripts/test-network-protocols.sh")
}

fn sha256_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    format!("{:x}", hasher.finalize())
}

/// PROPFIND (Depth: 1) contra o Apache/mod_dav real — cobre o parser de multistatus XML
/// (`parse_multistatus_xml`, testado em src/webdav/mod.rs só com XML fixo) contra a resposta
/// de verdade de um servidor WebDAV, incluindo autenticação Basic.
#[test]
#[ignore]
fn list_directory_finds_test_file_on_real_server() {
    let entries = list_directory(&target(""), "").expect("PROPFIND falhou contra o servidor real");
    let file = test_file_name();
    let entry = entries.iter().find(|e| e.name == file).unwrap_or_else(|| {
        panic!("{file} nao encontrado em: {:?}", entries.iter().map(|e| &e.name).collect::<Vec<_>>())
    });
    assert!(!entry.is_dir);
    assert!(entry.size > 0);
}

/// Varredura recursiva de poda de pastas (T-folder-pruning) contra o servidor real —
/// a raiz so tem o arquivo de teste (nao-midia), entao o resultado esperado e
/// "nenhuma midia encontrada, varredura concluida por completo" — cobre o caminho de
/// PROPFIND real reusando o mesmo cliente HTTP (pool keep-alive) entre chamadas.
#[test]
#[ignore]
fn scan_has_media_reports_no_media_for_fixture_with_only_a_non_media_file() {
    let result = scan_has_media(&target(""), "").expect("scan_has_media falhou contra o servidor real");
    assert!(!result.has_media);
    assert!(result.completed_fully);
}

/// Leitura completa via `WebdavFileSource` (GET com Range em blocos concorrentes,
/// `read_range`/`RangeSource`) — mesmo caminho usado pelo demuxer em produção
/// (`rust/core/src/demuxer.rs::webdav_source`). Confirma sha256 idêntico ao arquivo original.
#[test]
#[ignore]
fn webdav_file_source_reads_full_file_matching_sha256() {
    let file = test_file_name();
    let mut source = WebdavFileSource::open(&target(&file)).expect("open WebDAV falhou contra o servidor real");

    let total = source.len().expect("tamanho do arquivo deveria ser conhecido");
    assert!(total > 0);

    let mut buf = vec![0u8; total as usize];
    let n = source.read_range(0, &mut buf).expect("read_range falhou");
    assert_eq!(n, total as usize, "deveria ler o arquivo inteiro numa chamada");

    assert_eq!(sha256_hex(&buf), expected_sha256());
}

/// Leitura em blocos pequenos forçando múltiplos `read_range` (simula os seeks reais do
/// demuxer durante cue points) — mesmo padrão de `ftp_integration.rs`/`sftp_integration.rs`.
#[test]
#[ignore]
fn webdav_file_source_reads_full_file_with_small_blocks() {
    let file = test_file_name();
    let mut source = WebdavFileSource::open(&target(&file)).expect("open WebDAV falhou contra o servidor real");

    let total = source.len().expect("tamanho do arquivo deveria ser conhecido") as usize;
    let mut collected = Vec::with_capacity(total);
    let mut offset = 0u64;
    let block = 8192usize;

    while (offset as usize) < total {
        let mut chunk = vec![0u8; block];
        let n = source.read_range(offset, &mut chunk).expect("read_range falhou");
        if n == 0 {
            break;
        }
        collected.extend_from_slice(&chunk[..n]);
        offset += n as u64;
    }

    assert_eq!(collected.len(), total);
    assert_eq!(sha256_hex(&collected), expected_sha256());
}
