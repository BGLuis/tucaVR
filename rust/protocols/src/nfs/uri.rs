//! Representação interna de um alvo NFS (host/export/path/versão) e sua
//! serialização para a string que `PlaybackController` guarda como `current_path`.
//!
//! Formato NUL-delimitado: `nfs://{host}:{port}\0{export_path}\0{file_path}\0{version}`

const SEP: char = '\u{0}';

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NfsTarget {
    pub host: String,
    pub port: u16,
    /// Caminho do export no servidor (ex: "/volume1/media" ou "/export/videos")
    pub export_path: String,
    /// Caminho relativo do arquivo dentro do export (ex: "3d/avatar.mkv")
    pub file_path: String,
    /// Versão do protocolo NFS (3 ou 4, padrão 3)
    pub version: u8,
}

impl Default for NfsTarget {
    fn default() -> Self {
        Self {
            host: String::new(),
            port: 2049,
            export_path: String::new(),
            file_path: String::new(),
            version: 3,
        }
    }
}

impl NfsTarget {
    pub fn to_internal(&self) -> String {
        format!(
            "nfs://{}:{}{sep}{}{sep}{}{sep}{}",
            self.host,
            self.port,
            self.export_path,
            self.file_path,
            self.version,
            sep = SEP
        )
    }

    pub fn from_internal(s: &str) -> Option<Self> {
        let rest = s.strip_prefix("nfs://")?;
        let mut parts = rest.split(SEP);
        let hostport = parts.next()?;
        let (host, port_str) = hostport.rsplit_once(':')?;
        let export_path = parts.next()?.to_string();
        let file_path = parts.next()?.to_string();
        let version = parts.next().and_then(|v| v.parse().ok()).unwrap_or(3);

        Some(Self {
            host: host.to_string(),
            port: port_str.parse().ok()?,
            export_path,
            file_path,
            version,
        })
    }
}

pub fn is_nfs_uri(s: &str) -> bool {
    s.starts_with("nfs://")
}

pub fn redact(s: &str) -> String {
    match NfsTarget::from_internal(s) {
        Some(t) => format!("nfs://{}:{}{}/{}", t.host, t.port, t.export_path, t.file_path),
        None => "nfs://<invalid>".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let t = NfsTarget {
            host: "192.168.1.50".into(),
            port: 2049,
            export_path: "/volume1/video".into(),
            file_path: "movies/sample.mp4".into(),
            version: 3,
        };
        let s = t.to_internal();
        assert!(is_nfs_uri(&s));
        let back = NfsTarget::from_internal(&s).unwrap();
        assert_eq!(t, back);
    }

    #[test]
    fn redact_formats_cleanly() {
        let t = NfsTarget {
            host: "nas.local".into(),
            port: 2049,
            export_path: "/srv/nfs".into(),
            file_path: "clip.mkv".into(),
            version: 3,
        };
        let s = t.to_internal();
        let r = redact(&s);
        assert_eq!(r, "nfs://nas.local:2049/srv/nfs/clip.mkv");
    }
}
