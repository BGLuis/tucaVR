//! Crate de protocolos de rede (T6/T7): SMB2/3 e HTTP(S), usados pelo
//! `Demuxer` (`core::demuxer`) para tocar midia remota. Ate esta sessao isto
//! era um esqueleto (`pub fn add(left, right) -> u64`); substituido pela
//! implementacao real.

pub mod chunking;
pub mod dash;
pub mod discovery;
pub mod dlna;
pub mod download;
pub mod folder_scan;
pub mod ftp;
pub mod hls;
pub mod http;
pub mod nfs;
pub mod prefetch;
mod retry;
mod session_pool;
pub mod sftp;
pub mod smb;
pub mod webdav;
mod xml_text;

use std::sync::LazyLock;
use tokio::runtime::Runtime;

/// Shared multi-thread Tokio runtime for connection pools (SMB, SFTP) and short-lived UI queries.
pub static POOL_RUNTIME: LazyLock<Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("tucavr-pool-rt")
        .enable_all()
        .build()
        .expect("Failed to initialize tucaVR pool runtime")
});
