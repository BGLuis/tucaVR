//! Descoberta SSDP / DLNA (T10.1 / T7.1) via UDP Multicast M-SEARCH.

use super::DiscoveredServer;
use std::net::{SocketAddr, UdpSocket};
use std::time::{Duration, Instant};

const SSDP_MULTICAST_ADDR: &str = "239.255.255.250:1900";
const SSDP_SEARCH_MSG: &str = "M-SEARCH * HTTP/1.1\r\n\
HOST: 239.255.255.250:1900\r\n\
MAN: \"ssdp:discover\"\r\n\
MX: 2\r\n\
ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\
\r\n";

pub fn scan_ssdp(timeout: Duration) -> Vec<DiscoveredServer> {
    let socket = match UdpSocket::bind("0.0.0.0:0") {
        Ok(s) => s,
        Err(e) => {
            log::warn!("Falha ao criar UDP socket para SSDP: {e}");
            return Vec::new();
        }
    };

    let _ = socket.set_read_timeout(Some(Duration::from_millis(200)));
    let _ = socket.set_broadcast(true);

    let target_addr: SocketAddr = match SSDP_MULTICAST_ADDR.parse() {
        Ok(a) => a,
        Err(_) => return Vec::new(),
    };

    // Dispara a mensagem de busca M-SEARCH
    if let Err(e) = socket.send_to(SSDP_SEARCH_MSG.as_bytes(), target_addr) {
        log::warn!("Falha ao enviar M-SEARCH SSDP: {e}");
        return Vec::new();
    }

    let mut results = Vec::new();
    let mut buf = [0u8; 2048];
    let deadline = Instant::now() + timeout;

    while Instant::now() < deadline {
        match socket.recv_from(&mut buf) {
            Ok((len, src_addr)) => {
                let text = String::from_utf8_lossy(&buf[..len]);
                if text.contains("HTTP/1.1 200 OK") || text.contains("NOTIFY") {
                    let mut location = String::new();
                    let mut server_name = String::new();

                    for line in text.lines() {
                        let lower = line.to_ascii_lowercase();
                        if lower.starts_with("location:") {
                            location = line["location:".len()..].trim().to_string();
                        } else if lower.starts_with("server:") {
                            server_name = line["server:".len()..].trim().to_string();
                        }
                    }

                    let (host, port) = if !location.is_empty() {
                        parse_host_port_from_url(&location).unwrap_or((src_addr.ip().to_string(), src_addr.port()))
                    } else {
                        (src_addr.ip().to_string(), src_addr.port())
                    };

                    let name = if !server_name.is_empty() {
                        server_name
                    } else {
                        format!("DLNA Media Server ({host})")
                    };

                    results.push(DiscoveredServer {
                        protocol: "DLNA".to_string(),
                        name,
                        host,
                        port,
                        path: location,
                    });
                }
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut => {
                // Intervalo de leitura normal
            }
            Err(e) => {
                log::debug!("Erro ao receber resposta SSDP: {e}");
            }
        }
    }

    results
}

fn parse_host_port_from_url(url: &str) -> Option<(String, u16)> {
    let without_proto = url.strip_prefix("http://").or_else(|| url.strip_prefix("https://"))?;
    let hostport = without_proto.split('/').next()?;
    if let Some((h, p_str)) = hostport.split_once(':') {
        let p = p_str.parse().ok()?;
        Some((h.to_string(), p))
    } else {
        Some((hostport.to_string(), 80))
    }
}
