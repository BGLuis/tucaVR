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
