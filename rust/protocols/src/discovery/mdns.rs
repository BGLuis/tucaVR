//! Descoberta mDNS / DNS-SD (T10.2) via crate `mdns-sd`.

use super::DiscoveredServer;
use mdns_sd::{ServiceDaemon, ServiceEvent};
use std::time::{Duration, Instant};

const MDNS_SERVICE_TYPES: &[(&str, &str, u16)] = &[
    ("_smb._tcp.local.", "SMB", 445),
    ("_nfs._tcp.local.", "NFS", 2049),
    ("_ftp._tcp.local.", "FTP", 21),
    ("_sftp-ssh._tcp.local.", "SFTP", 22),
    ("_webdav._tcp.local.", "WEBDAV", 80),
];

pub fn scan_mdns(timeout: Duration) -> Vec<DiscoveredServer> {
    let daemon = match ServiceDaemon::new() {
        Ok(d) => d,
        Err(e) => {
            log::warn!("Falha ao iniciar ServiceDaemon do mDNS: {e}");
            return Vec::new();
        }
    };

    let mut receivers = Vec::new();
    for &(service_type, protocol, default_port) in MDNS_SERVICE_TYPES {
        if let Ok(receiver) = daemon.browse(service_type) {
            receivers.push((receiver, protocol, default_port));
        }
    }

    let mut results = Vec::new();
    let deadline = Instant::now() + timeout;

    while Instant::now() < deadline {
        let mut any_received = false;
        for (receiver, protocol, default_port) in &receivers {
            while let Ok(event) = receiver.try_recv() {
                any_received = true;
                if let ServiceEvent::ServiceResolved(info) = event {
                    let host = info
                        .get_addresses()
                        .iter()
                        .next()
                        .map(|ip| ip.to_string())
                        .unwrap_or_else(|| info.get_hostname().trim_end_matches('.').to_string());

                    let port = if info.get_port() > 0 {
                        info.get_port()
                    } else {
                        *default_port
                    };

                    let name = info
                        .get_fullname()
                        .split('.')
                        .next()
                        .unwrap_or("Server")
                        .to_string();

                    results.push(DiscoveredServer {
                        protocol: protocol.to_string(),
                        name,
                        host,
                        port,
                        path: String::new(),
                    });
                }
            }
        }

        if !any_received {
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    let _ = daemon.shutdown();
    results
}
