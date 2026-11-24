//! Crate de protocolos de rede (T6/T7): SMB2/3 e HTTP(S), usados pelo
//! `Demuxer` (`core::demuxer`) para tocar midia remota. Ate esta sessao isto
//! era um esqueleto (`pub fn add(left, right) -> u64`); substituido pela
//! implementacao real.

pub mod chunking;
pub mod dash;
pub mod discovery;
pub mod dlna;
pub mod download;
pub mod folder_scan;
pub mod ftp;
pub mod hls;
pub mod http;
pub mod nfs;
pub mod prefetch;
mod retry;
mod session_pool;
pub mod sftp;
pub mod smb;
pub mod webdav;
mod xml_text;

use std::sync::LazyLock;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;
use tokio::runtime::Runtime;

pub const DEFAULT_CONNECT_TIMEOUT_SECS: u64 = 10;
pub const MIN_CONNECT_TIMEOUT_SECS: u64 = 5;
pub const MAX_CONNECT_TIMEOUT_SECS: u64 = 30;

static CONNECT_TIMEOUT_SECS: AtomicU64 = AtomicU64::new(DEFAULT_CONNECT_TIMEOUT_SECS);

/// Sets the global network connection/request timeout in seconds.
/// Passing `0` resets to [`DEFAULT_CONNECT_TIMEOUT_SECS`]; non-zero values are
/// clamped to `[MIN_CONNECT_TIMEOUT_SECS, MAX_CONNECT_TIMEOUT_SECS]`.
pub fn set_connect_timeout_secs(secs: u64) {
    let normalized = if secs == 0 {
        DEFAULT_CONNECT_TIMEOUT_SECS
    } else {
        secs.clamp(MIN_CONNECT_TIMEOUT_SECS, MAX_CONNECT_TIMEOUT_SECS)
    };
    CONNECT_TIMEOUT_SECS.store(normalized, Ordering::Relaxed);
}

/// Returns the configured global network connection/request timeout in seconds.
pub fn get_connect_timeout_secs() -> u64 {
    CONNECT_TIMEOUT_SECS.load(Ordering::Relaxed)
}

/// Returns the configured global network connection/request timeout as a [`Duration`].
pub fn connect_timeout() -> Duration {
    Duration::from_secs(get_connect_timeout_secs())
}

/// Shared multi-thread Tokio runtime for connection pools (SMB, SFTP) and short-lived UI queries.
pub static POOL_RUNTIME: LazyLock<Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("tucavr-pool-rt")
        .enable_all()
        .build()
        .expect("Failed to initialize tucaVR pool runtime")
});

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Mutex, MutexGuard};

    static TIMEOUT_TEST_LOCK: Mutex<()> = Mutex::new(());

    struct TimeoutGuard<'a> {
        _lock: MutexGuard<'a, ()>,
    }

    impl<'a> TimeoutGuard<'a> {
        fn new() -> Self {
            let lock = TIMEOUT_TEST_LOCK
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            CONNECT_TIMEOUT_SECS.store(DEFAULT_CONNECT_TIMEOUT_SECS, Ordering::Relaxed);
            Self { _lock: lock }
        }
    }

    impl Drop for TimeoutGuard<'_> {
        fn drop(&mut self) {
            CONNECT_TIMEOUT_SECS.store(DEFAULT_CONNECT_TIMEOUT_SECS, Ordering::Relaxed);
        }
    }

    #[test]
    fn default_connect_timeout_is_10s() {
        let _guard = TimeoutGuard::new();
        assert_eq!(get_connect_timeout_secs(), 10);
        assert_eq!(connect_timeout(), Duration::from_secs(10));
    }

    #[test]
    fn valid_connect_timeout_values_are_stored() {
        let _guard = TimeoutGuard::new();
        for secs in [5, 10, 20, 30] {
            set_connect_timeout_secs(secs);
            assert_eq!(get_connect_timeout_secs(), secs);
            assert_eq!(connect_timeout(), Duration::from_secs(secs));
        }
    }

    #[test]
    fn out_of_range_connect_timeout_values_are_clamped() {
        let _guard = TimeoutGuard::new();
        for secs in [1, 4] {
            set_connect_timeout_secs(secs);
            assert_eq!(get_connect_timeout_secs(), MIN_CONNECT_TIMEOUT_SECS);
            assert_eq!(
                connect_timeout(),
                Duration::from_secs(MIN_CONNECT_TIMEOUT_SECS)
            );
        }
        for secs in [31, 120] {
            set_connect_timeout_secs(secs);
            assert_eq!(get_connect_timeout_secs(), MAX_CONNECT_TIMEOUT_SECS);
            assert_eq!(
                connect_timeout(),
                Duration::from_secs(MAX_CONNECT_TIMEOUT_SECS)
            );
        }
    }

    #[test]
    fn zero_connect_timeout_restores_default() {
        let _guard = TimeoutGuard::new();
        set_connect_timeout_secs(20);
        assert_eq!(get_connect_timeout_secs(), 20);

        set_connect_timeout_secs(0);
        assert_eq!(get_connect_timeout_secs(), DEFAULT_CONNECT_TIMEOUT_SECS);
        assert_eq!(
            connect_timeout(),
            Duration::from_secs(DEFAULT_CONNECT_TIMEOUT_SECS)
        );
    }
}
