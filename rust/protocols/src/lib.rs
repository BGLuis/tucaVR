//! Crate de protocolos de rede (T6/T7): SMB2/3 e HTTP(S), usados pelo
//! `Demuxer` (`core::demuxer`) para tocar midia remota. Ate esta sessao isto
//! era um esqueleto (`pub fn add(left, right) -> u64`); substituido pela
//! implementacao real.

pub mod chunking;
pub mod discovery;
pub mod ftp;
pub mod http;
pub mod nfs;
pub mod prefetch;
pub mod sftp;
pub mod smb;
