//! Representacao interna de um alvo SMB (host/share/path/credenciais) e sua
//! (de)serializacao para a string que `PlaybackController` guarda como
//! `current_path` (ver `core::playback`).
//!
//! **Isto NAO e uma URI de verdade** (nao segue RFC 3986) — e um formato
//! delimitado por NUL (`'\0'`), escolhido de proposito em vez de
//! `smb://user:pass@host/share/path` percent-encoded:
//!
//! 1. So existe na memoria do processo Rust: e produzida por
//!    `start_smb_playback` (bridge) a partir de campos JA separados vindos
//!    do JNI, e so e consumida por `Demuxer::new`. Nunca cruza a fronteira
//!    JNI de volta para o Kotlin, nunca e persistida em disco.
//! 2. Evita todo o cuidado de percent-encoding de usuario/senha (que podem
//!    conter `@`, `:`, `/`) — NUL nunca aparece em host/share/path/usuario/
//!    senha reais, entao split ingenuo e seguro.
//! 3. **Nunca deve ser logada inteira** — use `redact()` para logs.
//!
//! O prefixo `"smb://"` e mantido só para o `Demuxer::new` reconhecer o
//! esquema com um `starts_with` legivel, igual aos outros esquemas.

const SEP: char = '\u{0}';

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SmbTarget {
    pub host: String,
    pub port: u16,
    pub share: String,
    /// Caminho do arquivo/diretorio dentro do share, sem barra inicial.
    pub path: String,
    /// Vazio = guest/anonimo (T6.1).
    pub username: String,
    pub password: String,
    /// Vazio = dominio local.
    pub domain: String,
}

impl SmbTarget {
    pub fn to_internal(&self) -> String {
        format!(
            "smb://{}:{}{sep}{}{sep}{}{sep}{}{sep}{}{sep}{}",
            self.host, self.port, self.share, self.path, self.username, self.password, self.domain,
            sep = SEP
        )
    }

    pub fn from_internal(s: &str) -> Option<Self> {
        let rest = s.strip_prefix("smb://")?;
        let mut parts = rest.split(SEP);
        let hostport = parts.next()?;
        let (host, port_str) = hostport.rsplit_once(':')?;
        Some(Self {
            host: host.to_string(),
            port: port_str.parse().ok()?,
            share: parts.next()?.to_string(),
            path: parts.next()?.to_string(),
            username: parts.next()?.to_string(),
            password: parts.next()?.to_string(),
            domain: parts.next().unwrap_or("").to_string(),
        })
    }
}

/// `true` se `s` e uma URI interna SMB (formato acima) — usado pelo
/// `Demuxer::new` para roteamento de esquema.
pub fn is_smb_uri(s: &str) -> bool {
    s.starts_with("smb://")
}

/// Versao segura para log: host/porta/share/caminho, NUNCA usuario/senha/
/// dominio.
pub fn redact(s: &str) -> String {
    match SmbTarget::from_internal(s) {
        Some(t) => format!("smb://{}:{}/{}/{}", t.host, t.port, t.share, t.path),
        None => "smb://<invalid>".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let t = SmbTarget {
            host: "192.168.1.10".into(),
            port: 445,
            share: "Videos".into(),
            path: "filme.mkv".into(),
            username: "user".into(),
            password: "p@ss:word".into(),
            domain: "WORKGROUP".into(),
        };
        let s = t.to_internal();
        assert!(is_smb_uri(&s));
        let back = SmbTarget::from_internal(&s).unwrap();
        assert_eq!(t, back);
    }

    #[test]
    fn redact_hides_credentials() {
        let t = SmbTarget {
            host: "nas.local".into(),
            port: 445,
            share: "S".into(),
            path: "a/b.mp4".into(),
            username: "secretuser".into(),
            password: "secretpass".into(),
            domain: "".into(),
        };
        let redacted = redact(&t.to_internal());
        assert!(!redacted.contains("secretuser"));
        assert!(!redacted.contains("secretpass"));
        assert_eq!(redacted, "smb://nas.local:445/S/a/b.mp4");
    }

    #[test]
    fn guest_roundtrip() {
        let t = SmbTarget {
            host: "nas".into(),
            port: 445,
            share: "Public".into(),
            path: "".into(),
            username: "".into(),
            password: "".into(),
            domain: "".into(),
        };
        let back = SmbTarget::from_internal(&t.to_internal()).unwrap();
        assert_eq!(t, back);
    }
}
