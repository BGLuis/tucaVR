//! Idle-session pool shared by SMB and SFTP. Sessions are checked out for the
//! duration of one call, so concurrent callers never wait on each other: a
//! caller reuses an idle session or the module opens a new one.
//!
//! The TTL limits reuse, not lifetime: expired sessions are closed on the next
//! pool access, so an idle session can outlive it until something touches the pool.

use std::collections::HashMap;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::sync::{Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

/// Idle sessions kept per server: 3 parallel folder scans + 1 directory listing.
pub(crate) const MAX_IDLE_PER_KEY: usize = 4;

pub(crate) const SESSION_TTL: Duration = Duration::from_secs(30);

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub(crate) struct PoolKey {
    host: String,
    port: u16,
    /// Hash of everything else that distinguishes a session, credentials included,
    /// so a wrong password never reuses a session authenticated with the right one.
    identity: u64,
}

impl PoolKey {
    pub(crate) fn new(host: &str, port: u16, identity_parts: &[&str]) -> Self {
        let mut hasher = DefaultHasher::new();
        for part in identity_parts {
            part.hash(&mut hasher);
        }
        Self {
            host: host.to_string(),
            port,
            identity: hasher.finish(),
        }
    }
}

struct Idle<S> {
    session: S,
    since: Instant,
}

struct State<S> {
    idle: HashMap<PoolKey, Vec<Idle<S>>>,
    /// Bumped by `drain_server`; sessions opened under an older value are not pooled again.
    epochs: HashMap<(String, u16), u64>,
}

pub(crate) struct SessionPool<S> {
    state: Mutex<State<S>>,
    ttl: Duration,
    max_idle: usize,
}

impl<S> SessionPool<S> {
    pub(crate) fn new(ttl: Duration, max_idle: usize) -> Self {
        Self {
            state: Mutex::new(State {
                idle: HashMap::new(),
                epochs: HashMap::new(),
            }),
            ttl,
            max_idle,
        }
    }

    fn lock(&self) -> MutexGuard<'_, State<S>> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Generation of a server's pooled sessions. Read it before opening a session and hand it
    /// back to `checkin`, so a server removed in the meantime does not get its session pooled.
    pub(crate) fn epoch(&self, host: &str, port: u16) -> u64 {
        self.lock()
            .epochs
            .get(&(host.to_string(), port))
            .copied()
            .unwrap_or(0)
    }

    /// Returns an idle session for `key` (if any) plus every expired session found
    /// in the pool; the caller must close the expired ones.
    pub(crate) fn checkout(&self, key: &PoolKey) -> (Option<S>, Vec<S>) {
        self.checkout_at(key, Instant::now())
    }

    fn checkout_at(&self, key: &PoolKey, now: Instant) -> (Option<S>, Vec<S>) {
        let mut state = self.lock();
        let expired = self.sweep(&mut state.idle, now);
        let session = state
            .idle
            .get_mut(key)
            .and_then(|list| list.pop())
            .map(|idle| idle.session);
        (session, expired)
    }

    /// Hands a healthy session back. Returns sessions the caller must close: the
    /// expired ones and `session` itself when the key already holds `max_idle` or
    /// when its server was drained after `epoch` was read.
    pub(crate) fn checkin(&self, key: PoolKey, session: S, epoch: u64) -> Vec<S> {
        self.checkin_at(key, session, epoch, Instant::now())
    }

    fn checkin_at(&self, key: PoolKey, session: S, epoch: u64, now: Instant) -> Vec<S> {
        let mut state = self.lock();
        let mut to_close = self.sweep(&mut state.idle, now);
        let current = state
            .epochs
            .get(&(key.host.clone(), key.port))
            .copied()
            .unwrap_or(0);
        let list = state.idle.entry(key).or_default();
        if epoch != current || list.len() >= self.max_idle {
            to_close.push(session);
        } else {
            list.push(Idle {
                session,
                since: now,
            });
        }
        to_close
    }

    /// Removes every idle session of a server, whatever its credentials.
    pub(crate) fn drain_server(&self, host: &str, port: u16) -> Vec<S> {
        let mut state = self.lock();
        *state.epochs.entry((host.to_string(), port)).or_insert(0) += 1;
        let keys: Vec<PoolKey> = state
            .idle
            .keys()
            .filter(|k| k.host == host && k.port == port)
            .cloned()
            .collect();
        keys.into_iter()
            .filter_map(|k| state.idle.remove(&k))
            .flatten()
            .map(|idle| idle.session)
            .collect()
    }

    fn sweep(&self, map: &mut HashMap<PoolKey, Vec<Idle<S>>>, now: Instant) -> Vec<S> {
        let mut expired = Vec::new();
        map.retain(|_, list| {
            let (stale, fresh): (Vec<_>, Vec<_>) = std::mem::take(list)
                .into_iter()
                .partition(|idle| now.saturating_duration_since(idle.since) >= self.ttl);
            expired.extend(stale.into_iter().map(|idle| idle.session));
            *list = fresh;
            !list.is_empty()
        });
        expired
    }

    #[cfg(test)]
    fn idle_count(&self, key: &PoolKey) -> usize {
        self.lock().idle.get(key).map_or(0, Vec::len)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TTL: Duration = Duration::from_secs(30);

    fn key(password: &str) -> PoolKey {
        PoolKey::new("nas", 445, &["share", "user", password])
    }

    fn pool() -> SessionPool<u32> {
        SessionPool::new(TTL, 2)
    }

    #[test]
    fn key_changes_with_credentials_but_not_with_identical_input() {
        assert_eq!(key("right"), key("right"));
        assert_ne!(key("right"), key("wrong"));
        assert_ne!(
            PoolKey::new("nas", 445, &["ab", "c"]),
            PoolKey::new("nas", 445, &["a", "bc"]),
            "field boundaries must matter"
        );
    }

    #[test]
    fn wrong_password_does_not_reuse_a_session_authenticated_with_the_right_one() {
        let pool = pool();
        pool.checkin(key("right"), 1, 0);
        let (session, _) = pool.checkout(&key("wrong"));
        assert_eq!(session, None);
        let (session, _) = pool.checkout(&key("right"));
        assert_eq!(session, Some(1));
    }

    #[test]
    fn expired_sessions_are_not_reused_and_are_returned_for_closing() {
        let pool = pool();
        let t0 = Instant::now();
        pool.checkin_at(key("p"), 7, 0, t0);

        let (session, expired) = pool.checkout_at(&key("p"), t0 + TTL);

        assert_eq!(session, None);
        assert_eq!(expired, vec![7]);
        assert_eq!(pool.idle_count(&key("p")), 0);
    }

    #[test]
    fn sessions_inside_the_ttl_are_reused() {
        let pool = pool();
        let t0 = Instant::now();
        pool.checkin_at(key("p"), 7, 0, t0);

        let (session, expired) = pool.checkout_at(&key("p"), t0 + TTL - Duration::from_secs(1));

        assert_eq!(session, Some(7));
        assert!(expired.is_empty());
    }

    #[test]
    fn expired_sessions_of_other_servers_are_swept_on_any_access() {
        let pool = pool();
        let t0 = Instant::now();
        pool.checkin_at(PoolKey::new("other", 22, &["u"]), 9, 0, t0);

        let (_, expired) = pool.checkout_at(&key("p"), t0 + TTL);

        assert_eq!(expired, vec![9]);
    }

    #[test]
    fn idle_list_is_capped_and_the_overflow_is_returned_for_closing() {
        let pool = pool();
        assert!(pool.checkin(key("p"), 1, 0).is_empty());
        assert!(pool.checkin(key("p"), 2, 0).is_empty());
        assert_eq!(pool.checkin(key("p"), 3, 0), vec![3]);
        assert_eq!(pool.idle_count(&key("p")), 2);
    }

    #[test]
    fn concurrent_callers_each_get_their_own_session() {
        let pool = pool();
        pool.checkin(key("p"), 1, 0);
        pool.checkin(key("p"), 2, 0);

        let (a, _) = pool.checkout(&key("p"));
        let (b, _) = pool.checkout(&key("p"));
        let (c, _) = pool.checkout(&key("p"));

        assert!(a.is_some() && b.is_some() && a != b);
        assert_eq!(
            c, None,
            "a third caller opens a new session instead of waiting"
        );
    }

    #[test]
    fn drain_server_removes_every_credential_variant_of_that_server_only() {
        let pool = pool();
        pool.checkin(key("a"), 1, 0);
        pool.checkin(key("b"), 2, 0);
        pool.checkin(PoolKey::new("nas", 22, &["u"]), 3, 0);
        pool.checkin(PoolKey::new("other", 445, &["u"]), 4, 0);

        let mut drained = pool.drain_server("nas", 445);
        drained.sort();

        assert_eq!(drained, vec![1, 2]);
        assert_eq!(pool.checkout(&PoolKey::new("nas", 22, &["u"])).0, Some(3));
        assert_eq!(
            pool.checkout(&PoolKey::new("other", 445, &["u"])).0,
            Some(4)
        );
    }

    #[test]
    fn a_session_checked_out_before_its_server_was_drained_is_not_pooled_again() {
        let pool = pool();
        let epoch = pool.epoch("nas", 445);

        pool.drain_server("nas", 445);

        assert_eq!(pool.checkin(key("p"), 1, epoch), vec![1]);
        assert_eq!(pool.idle_count(&key("p")), 0);
        let fresh = pool.epoch("nas", 445);
        assert!(pool.checkin(key("p"), 2, fresh).is_empty());
    }

    #[test]
    fn draining_one_server_does_not_invalidate_another() {
        let pool = pool();
        let epoch = pool.epoch("other", 445);

        pool.drain_server("nas", 445);

        assert!(
            pool.checkin(PoolKey::new("other", 445, &["u"]), 1, epoch)
                .is_empty()
        );
    }

    #[test]
    fn multithreaded_concurrent_checkouts_and_checkins() {
        use std::sync::Arc;
        use std::sync::atomic::{AtomicU32, Ordering};

        let pool = Arc::new(SessionPool::new(TTL, 4));
        let session_seq = Arc::new(AtomicU32::new(1));
        let closed_count = Arc::new(AtomicU32::new(0));

        let thread_count = 8;
        let iters_per_thread = 50;

        let handles: Vec<_> = (0..thread_count)
            .map(|_| {
                let pool = Arc::clone(&pool);
                let seq = Arc::clone(&session_seq);
                let closed = Arc::clone(&closed_count);
                std::thread::spawn(move || {
                    let mut local_closed = 0u32;
                    for _ in 0..iters_per_thread {
                        let (checked_out, expired) = pool.checkout(&key("concurrent"));
                        local_closed += expired.len() as u32;

                        let session =
                            checked_out.unwrap_or_else(|| seq.fetch_add(1, Ordering::Relaxed));
                        std::thread::yield_now();

                        let overflow = pool.checkin(key("concurrent"), session, 0);
                        local_closed += overflow.len() as u32;
                    }
                    closed.fetch_add(local_closed, Ordering::Relaxed);
                })
            })
            .collect();

        for h in handles {
            h.join().expect("thread panicked");
        }

        let remaining = pool.idle_count(&key("concurrent"));
        assert!(
            remaining <= 4,
            "idle count ({remaining}) must not exceed max_idle (4)"
        );

        let created_count = session_seq.load(Ordering::Relaxed) - 1;
        let closed = closed_count.load(Ordering::Relaxed);
        assert_eq!(
            created_count,
            closed + remaining as u32,
            "every created session must either remain in pool or have been closed"
        );
    }

    #[test]
    fn checkin_beyond_capacity_returns_overflow_immediately() {
        let pool = SessionPool::new(TTL, 2);
        assert_eq!(pool.checkin(key("p"), 10, 0), Vec::<u32>::new());
        assert_eq!(pool.checkin(key("p"), 20, 0), Vec::<u32>::new());
        assert_eq!(pool.idle_count(&key("p")), 2);

        let overflow1 = pool.checkin(key("p"), 30, 0);
        assert_eq!(overflow1, vec![30]);
        assert_eq!(pool.idle_count(&key("p")), 2);

        let overflow2 = pool.checkin(key("p"), 40, 0);
        assert_eq!(overflow2, vec![40]);
        assert_eq!(pool.idle_count(&key("p")), 2);

        assert_eq!(pool.checkout(&key("p")).0, Some(20));
        assert_eq!(pool.checkout(&key("p")).0, Some(10));
        assert_eq!(pool.checkout(&key("p")).0, None);
    }

    #[test]
    fn drain_nonexistent_server_returns_empty() {
        let pool = pool();
        // Drain on completely empty pool
        assert!(pool.drain_server("any_host", 445).is_empty());

        pool.checkin(PoolKey::new("nas", 445, &["share"]), 1, 0);

        assert!(pool.drain_server("other_host", 445).is_empty());
        assert!(pool.drain_server("nas", 9999).is_empty());
        assert_eq!(pool.idle_count(&PoolKey::new("nas", 445, &["share"])), 1);

        let drained = pool.drain_server("nas", 445);
        assert_eq!(drained, vec![1]);
        assert_eq!(pool.idle_count(&PoolKey::new("nas", 445, &["share"])), 0);
    }

    #[test]
    fn checkin_with_zero_max_idle_always_overflows() {
        let pool = SessionPool::new(TTL, 0);
        assert_eq!(pool.checkin(key("zero"), 100, 0), vec![100]);
        assert_eq!(pool.checkin(key("zero"), 200, 0), vec![200]);
        assert_eq!(pool.idle_count(&key("zero")), 0);
        assert_eq!(pool.checkout(&key("zero")).0, None);
    }

    #[test]
    fn multithreaded_concurrent_checkouts_and_checkins_multi_key() {
        use std::sync::Arc;
        use std::sync::atomic::{AtomicU32, Ordering};

        let pool = Arc::new(SessionPool::new(TTL, 4));
        let seq1 = Arc::new(AtomicU32::new(100));
        let seq2 = Arc::new(AtomicU32::new(200));
        let closed1 = Arc::new(AtomicU32::new(0));
        let closed2 = Arc::new(AtomicU32::new(0));

        let handles: Vec<_> = (0..8)
            .map(|i| {
                let pool = Arc::clone(&pool);
                let is_server1 = i % 2 == 0;
                let seq = if is_server1 {
                    Arc::clone(&seq1)
                } else {
                    Arc::clone(&seq2)
                };
                let closed = if is_server1 {
                    Arc::clone(&closed1)
                } else {
                    Arc::clone(&closed2)
                };
                let key_name = if is_server1 { "server1" } else { "server2" };

                std::thread::spawn(move || {
                    let mut local_closed = 0u32;
                    for _ in 0..40 {
                        let pool_key = key(key_name);
                        let (checked_out, expired) = pool.checkout(&pool_key);
                        local_closed += expired.len() as u32;

                        let session =
                            checked_out.unwrap_or_else(|| seq.fetch_add(1, Ordering::Relaxed));
                        if is_server1 {
                            assert!((100..200).contains(&session));
                        } else {
                            assert!(session >= 200);
                        }
                        std::thread::yield_now();

                        let overflow = pool.checkin(pool_key, session, 0);
                        local_closed += overflow.len() as u32;
                    }
                    closed.fetch_add(local_closed, Ordering::Relaxed);
                })
            })
            .collect();

        for h in handles {
            h.join().expect("thread panicked");
        }

        let rem1 = pool.idle_count(&key("server1"));
        let rem2 = pool.idle_count(&key("server2"));
        assert!(rem1 <= 4);
        assert!(rem2 <= 4);

        let created1 = seq1.load(Ordering::Relaxed) - 100;
        let created2 = seq2.load(Ordering::Relaxed) - 200;
        assert_eq!(created1, closed1.load(Ordering::Relaxed) + rem1 as u32);
        assert_eq!(created2, closed2.load(Ordering::Relaxed) + rem2 as u32);
    }
}
