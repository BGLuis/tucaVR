//! Representação interna de um alvo WebDAV (host/porta/caminho-base/arquivo/credenciais/flags TLS)
//! e sua serialização para a string interna que `PlaybackController` guarda como `current_path`.
//!
//! Formato NUL-delimitado:
//! `webdav://{host}:{port}\0{base_path}\0{file_path}\0{username}\0{password}\0{use_https}\0{accept_invalid_certs}`

const SEP: char = '\u{0}';

/// Conjunto de caracteres ASCII a serem codificados nos segmentos de caminho de URL.
/// Preserva caracteres unreserved (RFC 3986: alfanuméricos, '-', '.', '_', '~')
/// e codifica espaços, caracteres de controle, barras, porcentagens, etc.
pub const PATH_SEGMENT_ENCODE_SET: &percent_encoding::AsciiSet = &percent_encoding::CONTROLS
    .add(b' ')
    .add(b'"')
    .add(b'#')
    .add(b'%')
    .add(b'<')
    .add(b'>')
    .add(b'?')
    .add(b'`')
    .add(b'{')
    .add(b'}')
    .add(b'/');

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WebdavTarget {
    pub host: String,
    pub port: u16,
    /// Caminho base no servidor (ex: "/webdav", "/remote.php/dav/files/user", ou "" para raiz)
    pub base_path: String,
    /// Caminho relativo do arquivo ou diretório dentro do base_path (ex: "videos/filme.mkv")
    pub file_path: String,
    /// Usuário para autenticação HTTP Basic (vazio para anônimo)
    pub username: String,
    /// Senha para autenticação HTTP Basic
    pub password: String,
    /// Se true, utiliza esquema https://; caso contrário, http://
    pub use_https: bool,
    /// Se true, aceita certificados TLS auto-assinados ou inválidos (danger_accept_invalid_certs)
    pub accept_invalid_certs: bool,
}

impl Default for WebdavTarget {
    fn default() -> Self {
        Self {
            host: String::new(),
            port: 5005,
            base_path: String::new(),
            file_path: String::new(),
            username: String::new(),
            password: String::new(),
            use_https: false,
            accept_invalid_certs: false,
        }
    }
}

impl WebdavTarget {
    pub fn to_internal(&self) -> String {
        format!(
            "webdav://{}:{}{sep}{}{sep}{}{sep}{}{sep}{}{sep}{}{sep}{}",
            self.host,
            self.port,
            self.base_path,
            self.file_path,
            self.username,
            self.password,
            if self.use_https { 1 } else { 0 },
            if self.accept_invalid_certs { 1 } else { 0 },
            sep = SEP
        )
    }

    pub fn from_internal(s: &str) -> Option<Self> {
        let rest = s.strip_prefix("webdav://")?;
        let mut parts = rest.split(SEP);
        let hostport = parts.next()?;
        let (host, port_str) = hostport.rsplit_once(':')?;
        let base_path = parts.next()?.to_string();
        let file_path = parts.next()?.to_string();
        let username = parts.next()?.to_string();
        let password = parts.next()?.to_string();
        let use_https = parts.next().map(|v| v == "1" || v == "true").unwrap_or(false);
        let accept_invalid_certs = parts.next().map(|v| v == "1" || v == "true").unwrap_or(false);

        Some(Self {
            host: host.to_string(),
            port: port_str.parse().ok()?,
            base_path,
            file_path,
            username,
            password,
            use_https,
            accept_invalid_certs,
        })
    }

    /// Constrói a URL HTTP(S) completa para o caminho especificado relativo ao base_path.
    /// Codifica percentualmente cada segmento de `rel_path` e preserva barras.
    pub fn url_for_path(&self, rel_path: &str) -> String {
        let scheme = if self.use_https { "https" } else { "http" };
        let base_clean = self.base_path.trim_matches('/');

        let mut url = if base_clean.is_empty() {
            format!("{scheme}://{}:{}", self.host, self.port)
        } else {
            format!("{scheme}://{}:{}/{}", self.host, self.port, base_clean)
        };

        let trimmed_rel = rel_path.trim();
        if !trimmed_rel.is_empty() {
            let has_trailing_slash = trimmed_rel.ends_with('/');
            for segment in trimmed_rel.split('/') {
                let s = segment.trim();
                if !s.is_empty() {
                    url.push('/');
                    let encoded = percent_encoding::utf8_percent_encode(s, PATH_SEGMENT_ENCODE_SET);
                    url.push_str(&encoded.to_string());
                }
            }
            if has_trailing_slash && !url.ends_with('/') {
                url.push('/');
            }
        }

        url
    }

    /// Constrói a URL HTTP(S) do arquivo atual desta instância (`self.file_path`).
    pub fn file_url(&self) -> String {
        self.url_for_path(&self.file_path)
    }
}

pub fn is_webdav_uri(s: &str) -> bool {
    s.starts_with("webdav://")
}

pub fn redact(s: &str) -> String {
    match WebdavTarget::from_internal(s) {
        Some(t) => {
            let scheme = if t.use_https { "https" } else { "http" };
            let base = if t.base_path.is_empty() {
                String::new()
            } else if t.base_path.starts_with('/') {
                t.base_path.clone()
            } else {
                format!("/{}", t.base_path)
            };
            let file = if t.file_path.is_empty() {
                String::new()
            } else if t.file_path.starts_with('/') {
                t.file_path.clone()
            } else {
                format!("/{}", t.file_path)
            };
            format!("webdav+{scheme}://{}:{}{base}{file}", t.host, t.port)
        }
        None => "webdav://<invalid>".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_with_all_fields() {
        let t = WebdavTarget {
            host: "nas.local".into(),
            port: 5006,
            base_path: "/remote.php/dav/files/user".into(),
            file_path: "Movies/Avatar (2009).mkv".into(),
            username: "admin".into(),
            password: "secret:password@123".into(),
            use_https: true,
            accept_invalid_certs: true,
        };
        let s = t.to_internal();
        assert!(is_webdav_uri(&s));
        let back = WebdavTarget::from_internal(&s).unwrap();
        assert_eq!(t, back);
    }

    #[test]
    fn roundtrip_anonymous_http() {
        let t = WebdavTarget {
            host: "192.168.1.100".into(),
            port: 8080,
            base_path: "".into(),
            file_path: "video.mp4".into(),
            username: "".into(),
            password: "".into(),
            use_https: false,
            accept_invalid_certs: false,
        };
        let s = t.to_internal();
        let back = WebdavTarget::from_internal(&s).unwrap();
        assert_eq!(t, back);
    }

    #[test]
    fn redact_hides_credentials() {
        let t = WebdavTarget {
            host: "nas.local".into(),
            port: 5006,
            base_path: "/webdav".into(),
            file_path: "secret_movie.mp4".into(),
            username: "admin".into(),
            password: "super_secret_password".into(),
            use_https: true,
            accept_invalid_certs: true,
        };
        let s = t.to_internal();
        let redacted = redact(&s);
        assert!(!redacted.contains("admin"));
        assert!(!redacted.contains("super_secret_password"));
        assert!(redacted.contains("nas.local:5006"));
        assert!(redacted.contains("/webdav/secret_movie.mp4"));
    }

    #[test]
    fn url_for_path_encodes_spaces_and_special_chars() {
        let t = WebdavTarget {
            host: "10.0.0.2".into(),
            port: 8080,
            base_path: "/dav".into(),
            file_path: "".into(),
            username: "".into(),
            password: "".into(),
            use_https: false,
            accept_invalid_certs: false,
        };

        let url = t.url_for_path("Vídeos 3D/Filme #1 (Final).mkv");
        assert_eq!(
            url,
            "http://10.0.0.2:8080/dav/V%C3%ADdeos%203D/Filme%20%231%20(Final).mkv"
        );
    }

    #[test]
    fn url_for_path_handles_trailing_slash() {
        let t = WebdavTarget {
            host: "nas".into(),
            port: 5005,
            base_path: "".into(),
            file_path: "".into(),
            username: "".into(),
            password: "".into(),
            use_https: false,
            accept_invalid_certs: false,
        };

        let url = t.url_for_path("pasta/");
        assert_eq!(url, "http://nas:5005/pasta/");
    }
}
