//! Descoberta automatica de servidores na rede local (T10.1-T10.4) combinando mDNS e SSDP.

pub mod mdns;
pub mod ssdp;

use std::time::Duration;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredServer {
    pub protocol: String,
    pub name: String,
    pub host: String,
    pub port: u16,
    pub path: String,
}

/// Executa varredura concorrente na rede local para localizar servidores SMB, NFS, FTP, SFTP e DLNA
pub fn scan_local_network(timeout_ms: u64) -> Vec<DiscoveredServer> {
    let timeout = Duration::from_millis(timeout_ms.max(500));

    let t_ssdp = std::thread::spawn(move || ssdp::scan_ssdp(timeout));
    let t_mdns = std::thread::spawn(move || mdns::scan_mdns(timeout));

    let mut all = Vec::new();
    if let Ok(ssdp_res) = t_ssdp.join() {
        all.extend(ssdp_res);
    }
    if let Ok(mdns_res) = t_mdns.join() {
        all.extend(mdns_res);
    }

    // Deduplica por (protocolo, host, porta)
    let mut seen = std::collections::HashSet::new();
    let mut unique = Vec::new();
    for s in all {
        let key = (s.protocol.clone(), s.host.clone(), s.port);
        if seen.insert(key) {
            unique.push(s);
        }
    }

    unique.sort_by_key(|a| a.name.to_lowercase());
    unique
}
