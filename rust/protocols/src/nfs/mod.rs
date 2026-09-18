//! Cliente NFS v3 puro-Rust (T5.1 / T5.2) para navegacao e streaming de rede.

pub mod client;
pub mod rpc;
pub mod source;
pub mod uri;

pub use client::{NfsClient, NfsDirEntry};
pub use source::NfsFileSource;
pub use uri::{is_nfs_uri, redact, NfsTarget};

use std::time::Duration;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);

/// Lista os arquivos e pastas dentro de um diretorio NFS
pub fn list_directory(target: &NfsTarget, dir_path: &str) -> Result<Vec<NfsDirEntry>, String> {
    let mut client = NfsClient::connect(&target.host, target.port, CONNECT_TIMEOUT)?;
    let root_handle = client.mount(&target.export_path)?;
    let (target_handle, _, is_dir) = client.resolve_path(&root_handle, dir_path)?;

    if !is_dir {
        return Err(format!("'{dir_path}' nao e um diretorio no export NFS"));
    }

    client.readdir(&target_handle)
}

/// Lista os exports disponiveis no servidor NFS
pub fn list_exports(host: &str, port: u16) -> Result<Vec<String>, String> {
    let mut client = NfsClient::connect(host, port, CONNECT_TIMEOUT)?;
    client.list_exports()
}

/// Varredura recursiva "esta pasta tem alguma midia reproduzivel?" -- ver
/// `crate::folder_scan`. Conecta e monta o export UMA vez (nao uma por
/// nivel), pilha explicita de paths resolvidos a partir da raiz, sem limite
/// de profundidade fixo, para no primeiro arquivo de midia ou no deadline
/// de seguranca.
pub fn scan_has_media(target: &NfsTarget, dir_path: &str) -> Result<crate::folder_scan::ScanResult, String> {
    let mut client = NfsClient::connect(&target.host, target.port, CONNECT_TIMEOUT)?;
    let root_handle = client.mount(&target.export_path)?;
    let deadline = crate::folder_scan::deadline_from_now();

    let mut stack = vec![dir_path.to_string()];
    let mut result = crate::folder_scan::ScanResult::exhausted_empty();

    while let Some(current) = stack.pop() {
        if std::time::Instant::now() > deadline {
            result = crate::folder_scan::ScanResult::timed_out_assume_has_media();
            break;
        }
        let (handle, _, is_dir) = match client.resolve_path(&root_handle, &current) {
            Ok(v) => v,
            Err(_) => continue,
        };
        if !is_dir {
            continue;
        }
        let entries = match client.readdir(&handle) {
            Ok(e) => e,
            Err(_) => continue,
        };
        let mut found = false;
        for e in &entries {
            if e.is_dir {
                let child = if current.is_empty() { e.name.clone() } else { format!("{}/{}", current, e.name) };
                stack.push(child);
            } else if crate::folder_scan::is_media_filename(&e.name) {
                found = true;
                break;
            }
        }
        if found {
            result = crate::folder_scan::ScanResult::found();
            break;
        }
    }

    Ok(result)
}
