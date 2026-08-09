//! Representacao interna de um alvo FTP (host/path/credenciais) e sua
//! (de)serializacao para a string que `PlaybackController` guarda como
//! `current_path` (ver `core::playback`) — mesmo formato e mesma motivacao
//! de `crate::smb::uri`, so com os campos que FTP realmente precisa (sem
//! `share`/`domain`, que sao conceitos SMB).
//!
//! **Isto NAO e uma URI de verdade** (nao segue RFC 3986) — e um formato
//! delimitado por NUL (`'\0'`), escolhido de proposito em vez de
//! `ftp://user:pass@host/path` percent-encoded:
//!
//! 1. So existe na memoria do processo Rust: e produzida a partir de campos
//!    JA separados vindos do JNI, e so e consumida por `Demuxer::new`. Nunca
//!    cruza a fronteira JNI de volta para o Kotlin, nunca e persistida em
//!    disco.
//! 2. Evita todo o cuidado de percent-encoding de usuario/senha (que podem
//!    conter `@`, `:`, `/`) — NUL nunca aparece em host/path/usuario/senha
//!    reais, entao split ingenuo e seguro.
//! 3. **Nunca deve ser logada inteira** — use `redact()` para logs. Isto
//!    importa ainda mais para FTP que para SMB: a senha aqui tambem trafega
//!    em texto claro na rede (doc, secao 6, aviso "FTP senha em texto
//!    claro"), entao evitar que ela vaze em logs locais e a unica linha de
//!    defesa que resta.
//!
//! O prefixo `"ftp://"` e mantido só para o `Demuxer::new` reconhecer o
//! esquema com um `starts_with` legivel, igual aos outros esquemas.

const SEP: char = '\u{0}';

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FtpTarget {
    pub host: String,
    pub port: u16,
    /// Caminho do arquivo/diretorio no servidor, sem barra inicial.
    pub path: String,
    /// Vazio = login anonimo (T6.1 — `login anonymous`).
    pub username: String,
    pub password: String,
}

impl FtpTarget {
    pub fn to_internal(&self) -> String {
        format!(
            "ftp://{}:{}{sep}{}{sep}{}{sep}{}",
            self.host, self.port, self.path, self.username, self.password,
            sep = SEP
        )
    }

    pub fn from_internal(s: &str) -> Option<Self> {
        let rest = s.strip_prefix("ftp://")?;
        let mut parts = rest.split(SEP);
        let hostport = parts.next()?;
        let (host, port_str) = hostport.rsplit_once(':')?;
        Some(Self {
            host: host.to_string(),
            port: port_str.parse().ok()?,
            path: parts.next()?.to_string(),
            username: parts.next()?.to_string(),
            password: parts.next()?.to_string(),
        })
    }
}

/// `true` se `s` e uma URI interna FTP (formato acima) — usado pelo
/// `Demuxer::new` para roteamento de esquema.
pub fn is_ftp_uri(s: &str) -> bool {
    s.starts_with("ftp://")
}

/// Versao segura para log: host/porta/caminho, NUNCA usuario/senha.
pub fn redact(s: &str) -> String {
    match FtpTarget::from_internal(s) {
        Some(t) => format!("ftp://{}:{}/{}", t.host, t.port, t.path),
        None => "ftp://<invalid>".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let t = FtpTarget {
            host: "192.168.1.20".into(),
            port: 21,
            path: "filmes/filme.mkv".into(),
            username: "user".into(),
            password: "p@ss:word".into(),
        };
        let s = t.to_internal();
        assert!(is_ftp_uri(&s));
        let back = FtpTarget::from_internal(&s).unwrap();
        assert_eq!(t, back);
    }

    #[test]
    fn redact_hides_credentials() {
        let t = FtpTarget {
            host: "ftp.local".into(),
            port: 21,
            path: "a/b.mp4".into(),
            username: "secretuser".into(),
            password: "secretpass".into(),
        };
        let redacted = redact(&t.to_internal());
        assert!(!redacted.contains("secretuser"));
        assert!(!redacted.contains("secretpass"));
        assert_eq!(redacted, "ftp://ftp.local:21/a/b.mp4");
    }

    #[test]
    fn anonymous_roundtrip() {
        let t = FtpTarget {
            host: "ftp".into(),
            port: 21,
            path: "".into(),
            username: "".into(),
            password: "".into(),
        };
        let back = FtpTarget::from_internal(&t.to_internal()).unwrap();
        assert_eq!(t, back);
    }
}
