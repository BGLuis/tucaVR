//! Representacao interna de um alvo SFTP (host/porta/caminho/credenciais) e
//! sua (de)serializacao para a mesma string interna que `smb::uri::SmbTarget`
//! usa — ver o comentario la para o raciocinio completo (nao e RFC 3986,
//! delimitado por NUL, so vive na memoria do processo Rust, nunca persistida,
//! nunca cruza a fronteira JNI de volta pro Kotlin, nunca deve ser logada
//! inteira).
//!
//! Uma diferenca em relacao a SMB: autenticacao por chave (T6.2) guarda o
//! **conteudo PEM da chave privada**, nao um caminho de arquivo — mesmo
//! espirito de "nunca persistir segredo bruto de um jeito diferente do
//! armazenamento criptografado" que se aplica a senha. O campo de chave e o
//! ULTIMO da string interna justamente porque PEM pode conter quebras de
//! linha (`\n`) — nao escapamos isso, so garantimos que ele nunca aparece
//! antes de outro separador NUL (NUL nunca ocorre em texto PEM real).

const SEP: char = '\u{0}';

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SftpTarget {
    pub host: String,
    pub port: u16,
    /// Caminho do arquivo/diretorio remoto, sem barra inicial obrigatoria
    /// (pode ser relativo ao $HOME do usuario SSH ou absoluto).
    pub path: String,
    pub username: String,
    /// Vazio quando a autenticacao usada e por chave (`private_key`).
    pub password: String,
    /// Conteudo PEM da chave privada (nao um caminho de arquivo). `None`
    /// quando a autenticacao usada e por senha.
    pub private_key: Option<String>,
}

impl SftpTarget {
    pub fn to_internal(&self) -> String {
        let key_field = self.private_key.as_deref().unwrap_or("");
        format!(
            "sftp://{}:{}{sep}{}{sep}{}{sep}{}{sep}{}",
            self.host, self.port, self.path, self.username, self.password, key_field,
            sep = SEP
        )
    }

    pub fn from_internal(s: &str) -> Option<Self> {
        let rest = s.strip_prefix("sftp://")?;
        let mut parts = rest.split(SEP);
        let hostport = parts.next()?;
        let (host, port_str) = hostport.rsplit_once(':')?;
        let path = parts.next()?.to_string();
        let username = parts.next()?.to_string();
        let password = parts.next()?.to_string();
        // Ultimo campo: string vazia -> None (nenhuma chave PEM real e
        // vazia, entao nao ha ambiguidade pratica com "chave vazia de
        // verdade"). `unwrap_or("")` cobre strings antigas/roundtrips sem
        // esse campo.
        let private_key = parts.next().unwrap_or("");
        Some(Self {
            host: host.to_string(),
            port: port_str.parse().ok()?,
            path,
            username,
            password,
            private_key: if private_key.is_empty() { None } else { Some(private_key.to_string()) },
        })
    }

    /// Valida que ha um metodo de autenticacao utilizavel — SFTP nao tem uma
    /// convencao de "anonimo" como FTP, entao senha vazia + sem chave e
    /// rejeitado explicitamente em vez de tentar autenticar silenciosamente
    /// (o que so falharia mais tarde, de um jeito mais confuso, contra o
    /// servidor).
    pub fn validate(&self) -> Result<(), String> {
        let has_key = self.private_key.as_deref().is_some_and(|k| !k.is_empty());
        if self.password.is_empty() && !has_key {
            return Err("SftpTarget sem senha e sem chave privada — SFTP nao tem autenticacao anonima".to_string());
        }
        Ok(())
    }
}

/// `true` se `s` e uma URI interna SFTP (formato acima) — usado pelo
/// `Demuxer::new` para roteamento de esquema.
pub fn is_sftp_uri(s: &str) -> bool {
    s.starts_with("sftp://")
}

/// Versao segura para log: host/porta/caminho, NUNCA usuario/senha/chave
/// privada.
pub fn redact(s: &str) -> String {
    match SftpTarget::from_internal(s) {
        Some(t) => format!("sftp://{}:{}/{}", t.host, t.port, t.path),
        None => "sftp://<invalid>".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_password_auth() {
        let t = SftpTarget {
            host: "192.168.1.20".into(),
            port: 22,
            path: "videos/filme.mkv".into(),
            username: "user".into(),
            password: "p@ss:word".into(),
            private_key: None,
        };
        let s = t.to_internal();
        assert!(is_sftp_uri(&s));
        let back = SftpTarget::from_internal(&s).unwrap();
        assert_eq!(t, back);
    }

    #[test]
    fn roundtrip_key_auth() {
        let pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk\nmore\nlines\n-----END OPENSSH PRIVATE KEY-----\n";
        let t = SftpTarget {
            host: "nas.local".into(),
            port: 2222,
            path: "/mnt/videos".into(),
            username: "vruser".into(),
            password: String::new(),
            private_key: Some(pem.to_string()),
        };
        let s = t.to_internal();
        assert!(is_sftp_uri(&s));
        let back = SftpTarget::from_internal(&s).unwrap();
        assert_eq!(t, back);
        assert_eq!(back.private_key.as_deref(), Some(pem));
    }

    #[test]
    fn redact_hides_credentials() {
        let t = SftpTarget {
            host: "nas.local".into(),
            port: 22,
            path: "a/b.mp4".into(),
            username: "secretuser".into(),
            password: "secretpass".into(),
            private_key: None,
        };
        let redacted = redact(&t.to_internal());
        assert!(!redacted.contains("secretuser"));
        assert!(!redacted.contains("secretpass"));
        assert_eq!(redacted, "sftp://nas.local:22/a/b.mp4");
    }

    #[test]
    fn redact_hides_private_key() {
        let t = SftpTarget {
            host: "nas.local".into(),
            port: 22,
            path: "a/b.mp4".into(),
            username: "secretuser".into(),
            password: String::new(),
            private_key: Some("-----BEGIN OPENSSH PRIVATE KEY-----\nsupersecretmaterial\n-----END OPENSSH PRIVATE KEY-----\n".into()),
        };
        let redacted = redact(&t.to_internal());
        assert!(!redacted.contains("supersecretmaterial"));
        assert!(!redacted.contains("secretuser"));
    }

    #[test]
    fn validate_rejects_no_auth() {
        let t = SftpTarget {
            host: "nas".into(),
            port: 22,
            path: "".into(),
            username: "user".into(),
            password: String::new(),
            private_key: None,
        };
        assert!(t.validate().is_err());
    }

    #[test]
    fn validate_accepts_password_or_key() {
        let mut t = SftpTarget {
            host: "nas".into(),
            port: 22,
            path: "".into(),
            username: "user".into(),
            password: "pw".into(),
            private_key: None,
        };
        assert!(t.validate().is_ok());
        t.password = String::new();
        t.private_key = Some("pem".into());
        assert!(t.validate().is_ok());
    }
}
