//! SFTP host key verification and trust-on-first-use (TOFU) pinning.
//!
//! T6.2 / #57: Implements host key verification for SFTP sessions.
//! On first connection, if no fingerprint is stored for the server, the connection
//! returns a "needs confirmation" error containing the server's public key fingerprint.
//! Once pinned, subsequent connections compare the server's key against the pinned
//! fingerprint and fail with an identity-changed error if they differ (protecting
//! against MITM attacks).

use russh::keys::PublicKeyOrCertificate;
use russh::keys::ssh_key::HashAlg;

/// Error returned when host key verification fails.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HostKeyError {
    /// No host key was stored for this server yet; the connection requires
    /// user confirmation of the presented fingerprint (TOFU).
    NeedsConfirmation { fingerprint: String },
    /// The server presented a host key different from the previously pinned one.
    /// Indicates a possible man-in-the-middle attack or server re-installation.
    IdentityChanged { expected: String, actual: String },
}

impl std::fmt::Display for HostKeyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NeedsConfirmation { fingerprint } => {
                write!(f, "needs confirmation: {fingerprint}")
            }
            Self::IdentityChanged { expected, actual } => {
                write!(
                    f,
                    "server identity changed: expected fingerprint {expected}, server presented {actual}"
                )
            }
        }
    }
}

impl std::error::Error for HostKeyError {}

/// Normalizes fingerprint string by trimming and canonicalizing the SHA256: prefix.
pub fn normalize_fingerprint(fp: &str) -> String {
    let trimmed = fp.trim();
    if let Some(rest) = trimmed.strip_prefix("SHA256:") {
        format!("SHA256:{rest}")
    } else if let Some(rest) = trimmed.strip_prefix("sha256:") {
        format!("SHA256:{rest}")
    } else {
        trimmed.to_string()
    }
}

/// Computes the SHA-256 OpenSSH-format fingerprint of an SSH public key or certificate.
pub fn compute_fingerprint(key: &PublicKeyOrCertificate) -> String {
    match key {
        PublicKeyOrCertificate::PublicKey { key, .. } => {
            key.fingerprint(HashAlg::Sha256).to_string()
        }
        PublicKeyOrCertificate::Certificate(cert) => {
            cert.public_key().fingerprint(HashAlg::Sha256).to_string()
        }
    }
}

/// Compares the expected host key fingerprint (if any) against the actual fingerprint.
///
/// - If `expected_fingerprint` matches `actual_fingerprint`, returns `Ok(())`.
/// - If `expected_fingerprint` differs from `actual_fingerprint`, returns `Err(HostKeyError::IdentityChanged)`.
/// - If `expected_fingerprint` is `None` or empty, returns `Err(HostKeyError::NeedsConfirmation)`.
pub fn verify_host_key_fingerprint(
    expected_fingerprint: Option<&str>,
    actual_fingerprint: &str,
) -> Result<(), HostKeyError> {
    let actual_norm = normalize_fingerprint(actual_fingerprint);
    let expected = expected_fingerprint
        .map(normalize_fingerprint)
        .filter(|s| !s.is_empty());

    match expected {
        Some(expected_norm) => {
            if expected_norm == actual_norm {
                Ok(())
            } else {
                Err(HostKeyError::IdentityChanged {
                    expected: expected_norm,
                    actual: actual_norm,
                })
            }
        }
        None => Err(HostKeyError::NeedsConfirmation {
            fingerprint: actual_norm,
        }),
    }
}

/// Computes the fingerprint of `server_public_key` and verifies it against `expected_fingerprint`.
pub fn verify_server_public_key(
    expected_fingerprint: Option<&str>,
    server_public_key: &PublicKeyOrCertificate,
) -> Result<String, HostKeyError> {
    let actual_fingerprint = compute_fingerprint(server_public_key);
    verify_host_key_fingerprint(expected_fingerprint, &actual_fingerprint)?;
    Ok(actual_fingerprint)
}

#[cfg(test)]
mod tests {
    use super::*;
    use russh::keys::ssh_key::PublicKey;

    const TEST_PUB_KEY: &str = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBX7WU1zK1z4l/sMET0oAin9Kopkmzmv3yfG3EWuHp14 vrplayer-sftp-test-only";

    fn test_key_and_fingerprint() -> (PublicKeyOrCertificate, String) {
        let pk = PublicKey::from_openssh(TEST_PUB_KEY).expect("valid openssh public key");
        let public_key: PublicKeyOrCertificate = pk.into();
        let expected_fp = compute_fingerprint(&public_key);
        (public_key, expected_fp)
    }

    #[test]
    fn known_fingerprint_matches() {
        let (key, fingerprint) = test_key_and_fingerprint();

        // Direct fingerprint verification
        assert!(verify_host_key_fingerprint(Some(&fingerprint), &fingerprint).is_ok());

        // Key verification
        let res = verify_server_public_key(Some(&fingerprint), &key);
        assert_eq!(res, Ok(fingerprint));
    }

    #[test]
    fn different_fingerprint_returns_identity_changed_error() {
        let (key, actual_fp) = test_key_and_fingerprint();
        let different_fp = "SHA256:0000000000000000000000000000000000000000000";

        // Direct fingerprint verification
        let err = verify_host_key_fingerprint(Some(different_fp), &actual_fp)
            .expect_err("should have failed with identity changed");
        assert_eq!(
            err,
            HostKeyError::IdentityChanged {
                expected: different_fp.to_string(),
                actual: actual_fp.clone(),
            }
        );
        assert!(err.to_string().contains("server identity changed"));

        // Key verification
        let key_err = verify_server_public_key(Some(different_fp), &key)
            .expect_err("should have failed with identity changed");
        assert_eq!(
            key_err,
            HostKeyError::IdentityChanged {
                expected: different_fp.to_string(),
                actual: actual_fp,
            }
        );
    }

    #[test]
    fn no_stored_fingerprint_returns_needs_confirmation() {
        let (key, actual_fp) = test_key_and_fingerprint();

        // Direct verification with None
        let err_none = verify_host_key_fingerprint(None, &actual_fp)
            .expect_err("should have failed with needs confirmation");
        assert_eq!(
            err_none,
            HostKeyError::NeedsConfirmation {
                fingerprint: actual_fp.clone(),
            }
        );
        assert!(err_none.to_string().contains("needs confirmation"));

        // Direct verification with empty string
        let err_empty = verify_host_key_fingerprint(Some(""), &actual_fp)
            .expect_err("should have failed with needs confirmation");
        assert_eq!(
            err_empty,
            HostKeyError::NeedsConfirmation {
                fingerprint: actual_fp.clone(),
            }
        );

        // Key verification
        let key_err = verify_server_public_key(None, &key)
            .expect_err("should have failed with needs confirmation");
        assert_eq!(
            key_err,
            HostKeyError::NeedsConfirmation {
                fingerprint: actual_fp,
            }
        );
    }

    #[test]
    fn fingerprint_normalization_handles_case_and_whitespace() {
        let fp1 = "  SHA256:abcDEF123  ";
        let fp2 = "sha256:abcDEF123";
        assert!(verify_host_key_fingerprint(Some(fp1), fp2).is_ok());
    }
}
