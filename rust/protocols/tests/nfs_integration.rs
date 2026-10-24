//! Testes de integracao NFS (T5.1/T5.2) contra um servidor NFSv3 REAL
//! (NFS-Ganesha em `docker/network-tests/nfs-test`), nao um mock. O MOUNT fica
//! numa porta diferente da 2049 do NFS, como num `nfs-kernel-server`, entao
//! estes testes so passam se o cliente consultar o rpcbind e decodificar as
//! listagens do `READDIRPLUS` corretamente.
//!
//! Nao rodam com `cargo test` normal (marcados `#[ignore]`): use
//! `./scripts/test-network-protocols.sh`, que sobe os containers e exporta as
//! env vars abaixo.
mod common;

use common::sha256_hex;
use protocols::nfs::{NfsFileSource, NfsTarget, list_directory, list_exports, scan_has_media};
use protocols::prefetch::PrefetchReader;
use std::io::Read;

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn target() -> NfsTarget {
    NfsTarget {
        host: env_or("VRPLAYER_TEST_NFS_HOST", "127.0.0.1"),
        port: env_or("VRPLAYER_TEST_NFS_PORT", "2049")
            .parse()
            .expect("VRPLAYER_TEST_NFS_PORT invalido"),
        export_path: env_or("VRPLAYER_TEST_NFS_EXPORT", "/export"),
        file_path: env_or("VRPLAYER_TEST_NFS_FILE", "testfile.bin"),
        version: 3,
    }
}

fn expected_sha256() -> String {
    std::env::var("VRPLAYER_TEST_FILE_SHA256").expect(
        "VRPLAYER_TEST_FILE_SHA256 nao definido — rode via scripts/test-network-protocols.sh",
    )
}

#[test]
#[ignore]
fn list_exports_finds_the_export() {
    let t = target();
    let exports = list_exports(&t.host, t.port).expect("list_exports falhou");
    assert!(
        exports.iter().any(|e| e == &t.export_path),
        "exports devolvidos: {exports:?}"
    );
}

/// Nome, tipo e tamanho tem que sair certos para as tres entradas da raiz da
/// fixture: se o parser desalinhar (U-02), o tamanho de uma vira lixo e os nomes
/// seguintes saem corrompidos.
#[test]
#[ignore]
fn list_directory_decodes_names_types_and_sizes() {
    let t = target();
    let entries = list_directory(&t, "").expect("list_directory falhou");

    let file = entries
        .iter()
        .find(|e| e.name == t.file_path)
        .unwrap_or_else(|| panic!("{} ausente: {entries:?}", t.file_path));
    assert!(!file.is_dir);
    assert_eq!(
        file.size, 262_144,
        "tamanho de {} (fixture de 256 KiB)",
        t.file_path
    );
    assert!(file.mtime > 0, "mtime de {} nao decodificado", t.file_path);

    for dir in ["dash", "webdav"] {
        let e = entries
            .iter()
            .find(|e| e.name == dir)
            .unwrap_or_else(|| panic!("{dir} ausente: {entries:?}"));
        assert!(e.is_dir, "{dir} deveria ser diretorio: {entries:?}");
    }
    assert!(entries.iter().all(|e| e.name != "." && e.name != ".."));
}

#[test]
#[ignore]
fn list_subdirectory_resolves_the_path() {
    let t = target();
    let entries = list_directory(&t, "webdav").expect("list_directory de subpasta falhou");
    assert!(
        entries
            .iter()
            .any(|e| e.name == "testfile.bin" && !e.is_dir && e.size == 131_072),
        "{entries:?}"
    );
}

#[test]
#[ignore]
fn nfs_file_source_reads_full_file_matching_sha256() {
    let source = NfsFileSource::open(&target()).expect("NfsFileSource::open falhou");
    let mut reader = PrefetchReader::new(source);
    let mut data = Vec::new();
    reader.read_to_end(&mut data).expect("leitura falhou");
    assert_eq!(data.len(), 262_144);
    assert_eq!(sha256_hex(&data), expected_sha256());
}

/// Arquivo maior que um RPC READ (o cliente le de 1 MiB em 1 MiB): a remontagem tem que fechar bit a bit.
#[test]
#[ignore]
fn nfs_file_source_reads_a_file_spanning_many_reads_matching_sha256() {
    let multi = NfsTarget {
        file_path: env_or("VRPLAYER_TEST_NFS_MULTIBLOCK_FILE", "nfs-multiblock.bin"),
        ..target()
    };
    let expected = std::env::var("VRPLAYER_TEST_NFS_MULTIBLOCK_SHA256")
        .expect("VRPLAYER_TEST_NFS_MULTIBLOCK_SHA256 nao definido — rode via scripts/test-network-protocols.sh");
    let mut reader =
        PrefetchReader::new(NfsFileSource::open(&multi).expect("NfsFileSource::open falhou"));
    let mut data = Vec::new();
    reader.read_to_end(&mut data).expect("leitura falhou");
    assert_eq!(
        data.len(),
        3 * 1024 * 1024 + 12_345,
        "tamanho nao multiplo de 1 MiB: exercita o ultimo READ parcial"
    );
    assert_eq!(sha256_hex(&data), expected);
}

#[test]
#[ignore]
fn scan_has_media_reports_media_in_the_dash_fixture() {
    let result = scan_has_media(&target(), "dash").expect("scan_has_media falhou");
    assert!(result.has_media, "a pasta dash tem video.mp4");
}
