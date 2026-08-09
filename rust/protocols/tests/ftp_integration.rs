//! Testes de integracao FTP (T6.1/T6.3) contra um servidor FTP REAL rodando em
//! Docker — nao um mock. Preenche a lacuna que `docs/phases/PHASE-0.1-MVP.md`
//! documenta honestamente pro SMB e que se aplica igualmente aqui: "nunca
//! testei isto contra um servidor de verdade", incluindo o caminho de modo
//! passivo, que e o unico jeito que funciona atras de NAT (doc, secao 6).
//!
//! Nao rodam com `cargo test` normal (marcados `#[ignore]`) porque dependem do
//! ambiente em `docker/network-tests/` estar de pe. Use
//! `./scripts/test-network-protocols.sh` na raiz do repo, que sobe os containers,
//! exporta as env vars abaixo com os valores corretos e roda
//! `cargo test -p protocols -- --ignored`.
use protocols::ftp::{list_directory, FtpFileSource, FtpTarget};
use protocols::prefetch::PrefetchReader;
use sha2::{Digest, Sha256};
use std::io::Read;

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn target() -> FtpTarget {
    FtpTarget {
        host: env_or("VRPLAYER_TEST_FTP_HOST", "127.0.0.1"),
        port: env_or("VRPLAYER_TEST_FTP_PORT", "12121").parse().expect("VRPLAYER_TEST_FTP_PORT invalido"),
        path: String::new(),
        username: env_or("VRPLAYER_TEST_FTP_USER", "vruser"),
        password: env_or("VRPLAYER_TEST_FTP_PASS", "vrpass123"),
    }
}

fn test_file_name() -> String {
    env_or("VRPLAYER_TEST_FTP_FILE", "testfile.bin")
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

/// Autenticacao + listagem via modo passivo (T6.1/T6.4) — tambem cobre o
/// caminho de "status de conexao" da UI.
#[test]
#[ignore]
fn list_directory_finds_test_file() {
    let entries = list_directory(&target(), "").expect("list_directory falhou contra o servidor real");
    let file = test_file_name();
    let entry = entries.iter().find(|e| e.name == file).unwrap_or_else(|| {
        panic!("{file} nao encontrado em: {entries:?}", entries = entries.iter().map(|e| &e.name).collect::<Vec<_>>())
    });
    assert!(!entry.is_dir);
    assert!(entry.size > 0);
}

/// Leitura de arquivo via `FtpFileSource` + `PrefetchReader` (T6.3) —
/// reconstroi o arquivo inteiro via reads posicionais (que, para FTP, viram
/// REST+RETR quando o `PrefetchReader` seeka pra fora do bloco corrente) e
/// compara sha256 byte a byte com o original, pra pegar qualquer corrupcao
/// sutil de offset/tamanho ou erro na logica de skip/reopen de
/// `FtpFileSource::ensure_positioned` que um teste "so conecta" nao pegaria.
#[test]
#[ignore]
fn ftp_file_source_reads_full_file_matching_sha256() {
    let mut t = target();
    t.path = test_file_name();

    let source = FtpFileSource::open(&t).expect("FtpFileSource::open falhou");
    let mut reader = PrefetchReader::new(source);
    let mut buf = Vec::new();
    reader.read_to_end(&mut buf).expect("leitura via PrefetchReader falhou");

    assert_eq!(sha256_hex(&buf), expected_sha256());
}

/// Mesmo teste acima, mas forcando blocos pequenos (64KB em vez dos 4MB
/// padrao) pra multiplicar o numero de seeks que `PrefetchReader` emite —
/// arquivo de teste tem 256KB (ver scripts/test-network-protocols.sh), entao
/// isso gera varias transicoes de bloco reais, exercitando tanto o caminho
/// de skip-forward quanto (via o proprio `Seek` do `PrefetchReader`, se
/// usado) o caminho de reopen com REST+RETR.
#[test]
#[ignore]
fn ftp_file_source_reads_full_file_with_small_blocks() {
    let mut t = target();
    t.path = test_file_name();

    let source = FtpFileSource::open(&t).expect("FtpFileSource::open falhou");
    let mut reader = PrefetchReader::with_block_size(source, 64 * 1024);
    let mut buf = Vec::new();
    reader.read_to_end(&mut buf).expect("leitura via PrefetchReader falhou");

    assert_eq!(sha256_hex(&buf), expected_sha256());
}

/// Le o final do arquivo primeiro (via `Seek`), depois o inicio — forca o
/// caminho de "salto pra tras" em `FtpFileSource::ensure_positioned`, que
/// precisa abortar a transferencia aberta e reabrir com REST+RETR no novo
/// offset. Isto e exatamente o padrao de acesso que o doc (secao 6,
/// `[!CAUTION]`) descreve para cues de MKV no fim do arquivo.
#[test]
#[ignore]
fn ftp_file_source_backward_seek_after_forward_read() {
    use std::io::{Seek, SeekFrom};

    let mut t = target();
    t.path = test_file_name();

    let source = FtpFileSource::open(&t).expect("FtpFileSource::open falhou");
    let mut reader = PrefetchReader::with_block_size(source, 64 * 1024);

    // Le um pedaco perto do fim do arquivo (256KB) primeiro.
    reader.seek(SeekFrom::Start(200_000)).expect("seek para o fim falhou");
    let mut tail = [0u8; 1024];
    reader.read_exact(&mut tail).expect("leitura do fim falhou");

    // Agora volta pro inicio e le o arquivo inteiro — exercita o reopen.
    reader.seek(SeekFrom::Start(0)).expect("seek de volta pro inicio falhou");
    let mut buf = Vec::new();
    reader.read_to_end(&mut buf).expect("leitura completa apos salto pra tras falhou");

    assert_eq!(sha256_hex(&buf), expected_sha256());
}
