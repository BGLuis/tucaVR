//! Stable error codes for failures that cross the C ABI to Kotlin (#59).
//!
//! The protocol clients return errors as `String`s (the `Display` text of
//! `smb2`, `russh`, `suppaftp`, `reqwest`, `std::io`, plus our own messages),
//! so the code is derived from that text. The bridge sends
//! `ERROR:<CODE>:<detail>`; Kotlin maps `<CODE>` to a translated message and
//! keeps `<detail>` for the log only.

/// What kind of failure a network error is, as far as the user can act on it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorCode {
    Auth,
    Timeout,
    NotFound,
    Tls,
    Unreachable,
    /// SFTP trust-on-first-use: the detail carries the fingerprint to confirm (#57, #223).
    HostKeyConfirm,
    /// SFTP pinned host key mismatch (#57, #223).
    HostKeyChanged,
    Unknown,
}

impl ErrorCode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Auth => "AUTH",
            Self::Timeout => "TIMEOUT",
            Self::NotFound => "NOT_FOUND",
            Self::Tls => "TLS",
            Self::Unreachable => "UNREACHABLE",
            Self::HostKeyConfirm => "HOST_KEY_CONFIRM",
            Self::HostKeyChanged => "HOST_KEY_CHANGED",
            Self::Unknown => "UNKNOWN",
        }
    }
}

const WIRE_PREFIX: &str = "ERROR:";

const TIMEOUT_MARKERS: &[&str] = &["timed out", "timeout", "deadline has elapsed"];

const TLS_MARKERS: &[&str] = &["certificate", "tls", "ssl"];

const AUTH_MARKERS: &[&str] = &[
    "authentication failed",
    "autenticacao",
    "status_logon_failure",
    "status_access_denied",
    "status_wrong_password",
    "status_account_",
    "status_password_",
    "access denied",
    "permission denied",
    "login incorrect",
    "not logged in",
    "[530]",
    "http 401",
    "http 403",
    "credenciais",
    "chave privada invalida",
];

const NOT_FOUND_MARKERS: &[&str] = &[
    "_not_found",
    "status_bad_network_name",
    "no such file",
    "not found",
    "http 404",
    "status 404",
    "[550]",
];

const UNREACHABLE_MARKERS: &[&str] = &[
    "connection refused",
    "no route to host",
    "network is unreachable",
    "host is unreachable",
    "failed to lookup address",
    "name or service not known",
    "nao foi possivel resolver",
    "falha ao resolver",
    "nenhum endereco encontrado",
    "could not connect",
    "connection reset",
    "disconnected from server",
    "error sending request",
    "falha ao conectar",
];

/// NFS3 / MOUNT3 status values (RFC 1813) that map to a user-facing code.
const NFS_PERM: u32 = 1;
const NFS_NOENT: u32 = 2;
const NFS_ACCES: u32 = 13;

/// Classifies an error message into an [`ErrorCode`].
///
/// Order matters: a timeout or TLS failure is often wrapped in a generic
/// "could not connect" message, so the specific causes are checked first.
pub fn classify(message: &str) -> ErrorCode {
    let message = message.trim();
    let message = message.strip_prefix(WIRE_PREFIX).unwrap_or(message);
    if message.starts_with("HOST_KEY_CONFIRM:") {
        return ErrorCode::HostKeyConfirm;
    }
    if message.starts_with("HOST_KEY_CHANGED:") {
        return ErrorCode::HostKeyChanged;
    }

    let lower = message.to_lowercase();
    let has = |markers: &[&str]| markers.iter().any(|m| lower.contains(m));

    if has(TIMEOUT_MARKERS) {
        return ErrorCode::Timeout;
    }
    if has(TLS_MARKERS) {
        return ErrorCode::Tls;
    }
    if has(AUTH_MARKERS) {
        return ErrorCode::Auth;
    }
    if has(NOT_FOUND_MARKERS) {
        return ErrorCode::NotFound;
    }
    match nfs_status(&lower) {
        Some(NFS_PERM | NFS_ACCES) => return ErrorCode::Auth,
        Some(NFS_NOENT) => return ErrorCode::NotFound,
        _ => {}
    }
    if has(UNREACHABLE_MARKERS) {
        return ErrorCode::Unreachable;
    }
    ErrorCode::Unknown
}

/// Reads the numeric status of an `nfs/client.rs` failure
/// (`"NFS LOOKUP falhou ... com status 2"`, `"MOUNT falhou ... com status 13"`).
fn nfs_status(lower: &str) -> Option<u32> {
    if !(lower.contains("nfs") || lower.contains("mount")) {
        return None;
    }
    let (_, rest) = lower.rsplit_once("status ")?;
    let digits: String = rest.chars().take_while(char::is_ascii_digit).collect();
    digits.parse().ok()
}

/// Formats an error for the C ABI as `ERROR:<CODE>:<detail>` on a single line.
///
/// The SFTP host-key messages already start with their code
/// (`HOST_KEY_CONFIRM:<fingerprint> ...`), and Kotlin's `SftpHostKeyStatus`
/// reads the fingerprint right after it, so they are passed through as they are.
pub fn to_wire(message: &str) -> String {
    let trimmed = message.trim();
    let detail = trimmed
        .strip_prefix(WIRE_PREFIX)
        .unwrap_or(trimmed)
        .replace(['\n', '\t'], " ");
    match classify(&detail) {
        ErrorCode::HostKeyConfirm | ErrorCode::HostKeyChanged => format!("{WIRE_PREFIX}{detail}"),
        code => format!("{WIRE_PREFIX}{}:{detail}", code.as_str()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auth_failures_from_each_protocol_are_auth() {
        for message in [
            "Authentication failed: STATUS_LOGON_FAILURE",
            "Protocol error: STATUS_ACCESS_DENIED during TreeConnect",
            "autenticacao SFTP falhou (usuario/senha ou chave privada invalidos)",
            "Invalid response: [530] Login incorrect.",
            "Servidor WebDAV respondeu com status HTTP 401 Unauthorized",
            "MOUNT falhou para /export com status 13",
        ] {
            assert_eq!(classify(message), ErrorCode::Auth, "{message}");
        }
    }

    #[test]
    fn timeouts_are_timeout_even_when_wrapped_in_a_connect_failure() {
        for message in [
            "SFTP: timeout de conexao/autenticacao apos 10s",
            "Operation timed out",
            "Falha ao conectar em 10.0.0.2:2049: connection timed out (os error 110)",
            "deadline has elapsed",
        ] {
            assert_eq!(classify(message), ErrorCode::Timeout, "{message}");
        }
    }

    #[test]
    fn missing_paths_and_shares_are_not_found() {
        for message in [
            "Protocol error: STATUS_OBJECT_NAME_NOT_FOUND during Create",
            "Protocol error: STATUS_BAD_NETWORK_NAME during TreeConnect",
            "Invalid response: [550] Failed to change directory.",
            "No such file",
            "Servidor WebDAV respondeu com status HTTP 404 Not Found",
            "NFS LOOKUP falhou para 'movies' com status 2",
        ] {
            assert_eq!(classify(message), ErrorCode::NotFound, "{message}");
        }
    }

    #[test]
    fn tls_and_unreachable_are_told_apart() {
        assert_eq!(
            classify("invalid peer certificate: UnknownIssuer"),
            ErrorCode::Tls
        );
        assert_eq!(
            classify("Connection refused (os error 111)"),
            ErrorCode::Unreachable
        );
        assert_eq!(
            classify("nao foi possivel resolver nas.local:445"),
            ErrorCode::Unreachable
        );
    }

    #[test]
    fn an_nfs_status_needs_an_nfs_message() {
        assert_eq!(
            classify("RPC call rejeitado pelo servidor"),
            ErrorCode::Unknown
        );
        assert_eq!(
            classify("NFS READ falhou com status 5 no offset 0"),
            ErrorCode::Unknown
        );
    }

    #[test]
    fn wire_carries_code_and_single_line_detail() {
        assert_eq!(
            to_wire("Authentication failed:\nSTATUS_LOGON_FAILURE"),
            "ERROR:AUTH:Authentication failed: STATUS_LOGON_FAILURE"
        );
        assert_eq!(to_wire("host invalido"), "ERROR:UNKNOWN:host invalido");
    }

    #[test]
    fn wire_does_not_double_an_existing_prefix() {
        assert_eq!(
            to_wire("ERROR:Operation timed out"),
            "ERROR:TIMEOUT:Operation timed out"
        );
    }

    #[test]
    fn host_key_messages_keep_the_format_sftp_host_key_status_parses() {
        let fp = "SHA256:abc";
        let confirm = format!("HOST_KEY_CONFIRM:{fp} type=ssh-ed25519 needs confirmation: {fp}");
        assert_eq!(classify(&confirm), ErrorCode::HostKeyConfirm);
        assert_eq!(to_wire(&confirm), format!("ERROR:{confirm}"));

        let changed = "HOST_KEY_CHANGED:expected SHA256:a, server presented SHA256:b";
        assert_eq!(classify(changed), ErrorCode::HostKeyChanged);
        assert_eq!(to_wire(changed), format!("ERROR:{changed}"));
    }
}
