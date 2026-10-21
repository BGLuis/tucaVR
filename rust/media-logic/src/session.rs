//! Generalizes the "playback generation" contract that fixed the T2.6 zombie
//! -thread bug described in PHASE-0.1-MVP.md:
//!
//! > Bug corrigido: antes, seek()/stop() podiam deixar threads da geracao
//! > anterior "zumbis" rodando em paralelo com as novas, escrevendo na mesma
//! > textura/audio compartilhados... Cada load_at() agora cria uma
//! > PlaybackSession com suas proprias flags is_playing/is_running (nao mais
//! > compartilhadas entre geracoes) e stop() faz join() de verdade nas
//! > threads antes de retornar, em vez de um sleep(150ms) no chute.
//!
//! `rust/core/src/playback.rs::PlaybackSession` is the real, hardware-bound
//! version of this (its three fields are actual `JoinHandle<()>`s for the
//! demux/video/audio threads spawned in `load_at`, which touch
//! `HwDecoder`/`AudioOutput`/`TextureOutput`). Reimplementing that exact
//! struct here isn't possible without pulling in `core`'s Android
//! dependencies. What *is* extractable and worth locking down with a fast
//! host test is the generation contract itself:
//!
//! 1. Every new `Generation` gets its own, independent `is_playing`/
//!    `is_running` flags — never shares an `Arc` with a previous generation.
//! 2. `stop_and_join` flips `is_running` to `false` (so worker loops checking
//!    it exit), flips `is_playing` to `true` (so anyone parked in the
//!    paused-sleep branch wakes up immediately instead of waiting out a
//!    sleep), and then actually joins every handle before returning — no
//!    "sleep and hope" shutdown.
//!
//! This module is a generic, hardware-agnostic version of that contract,
//! parameterized over any `JoinHandle`-bearing worker. `core::playback`
//! could be refactored to build its `PlaybackSession` on top of this instead
//! of hand-rolling the same flag bookkeeping — that swap was intentionally
//! **not** made in this session: `core` cannot be compiled in this sandbox
//! (see crate-level docs in `lib.rs`), so touching `playback.rs`'s
//! thread-spawning code without a way to compile-check it would be an
//! unverified, higher-risk change. This module exists to document and test
//! the contract in isolation; wiring it into `playback.rs` is a
//! recommended, low-risk follow-up on a machine that can actually build
//! `core` (`cargo ndk ... build -p core`).

use std::sync::atomic::{
    AtomicBool, AtomicI32, AtomicI64, AtomicU32, AtomicU64, AtomicUsize, Ordering,
};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

/// Independent play/run flags for a single "generation" of playback
/// threads. Two `Generation`s never share the underlying `Arc<Mutex<bool>>`
/// -- that's precisely the invariant the T2.6 bug violated.
#[derive(Clone)]
pub struct Generation {
    is_playing: Arc<Mutex<bool>>,
    is_running: Arc<Mutex<bool>>,
}

impl Generation {
    pub fn new() -> Self {
        Self {
            is_playing: Arc::new(Mutex::new(true)),
            is_running: Arc::new(Mutex::new(true)),
        }
    }

    /// Handle to hand to a worker thread/closure so it can check
    /// `is_running`/`is_playing` on its own, without holding a reference to
    /// the whole `Generation`.
    pub fn is_running_handle(&self) -> Arc<Mutex<bool>> {
        self.is_running.clone()
    }

    pub fn is_playing_handle(&self) -> Arc<Mutex<bool>> {
        self.is_playing.clone()
    }

    pub fn is_running(&self) -> bool {
        *self.is_running.lock().unwrap()
    }

    pub fn is_playing(&self) -> bool {
        *self.is_playing.lock().unwrap()
    }

    pub fn set_playing(&self, playing: bool) {
        *self.is_playing.lock().unwrap() = playing;
    }

    /// Signals every worker sharing this generation's flags to stop (and
    /// wakes up any worker parked in a "paused" sleep loop), then joins the
    /// given handles for real. This is what replaced the old
    /// `sleep(150ms)`-and-hope shutdown: callers only get control back once
    /// every worker thread has actually exited.
    pub fn stop_and_join<T>(self, handles: Vec<JoinHandle<T>>) {
        *self.is_running.lock().unwrap() = false;
        *self.is_playing.lock().unwrap() = true;

        for h in handles {
            let _ = h.join();
        }
    }
}

impl Default for Generation {
    fn default() -> Self {
        Self::new()
    }
}

/// Helper for non-blocking periodic sampling in the render loop (90Hz OpenXR).
/// If the protected resource is under contention (e.g. background load/seek),
/// returns `fallback` immediately without blocking the invoking thread.
pub fn try_sample_or<T, R, F>(lock: &Mutex<T>, fallback: R, f: F) -> R
where
    F: FnOnce(&T) -> R,
{
    match lock.try_lock() {
        Ok(guard) => f(&*guard),
        Err(_) => fallback,
    }
}

/// Atomic storage for `f32` based on binary representation via `AtomicU32`.
#[derive(Debug, Default)]
pub struct AtomicF32(AtomicU32);

impl AtomicF32 {
    pub const fn new(val: f32) -> Self {
        Self(AtomicU32::new(val.to_bits()))
    }

    pub fn load(&self, ordering: Ordering) -> f32 {
        f32::from_bits(self.0.load(ordering))
    }

    pub fn store(&self, val: f32, ordering: Ordering) {
        self.0.store(val.to_bits(), ordering);
    }
}

/// Trait for atomic types storing the last successful value for non-blocking
/// periodic sampling in the render loop.
pub trait AtomicSampleStorage: Send + Sync {
    type Value: Copy;
    fn load_sample(&self) -> Self::Value;
    fn store_sample(&self, val: Self::Value);
}

impl AtomicSampleStorage for AtomicU64 {
    type Value = u64;
    fn load_sample(&self) -> Self::Value {
        self.load(Ordering::Relaxed)
    }
    fn store_sample(&self, val: Self::Value) {
        self.store(val, Ordering::Relaxed);
    }
}

impl AtomicSampleStorage for AtomicU32 {
    type Value = u32;
    fn load_sample(&self) -> Self::Value {
        self.load(Ordering::Relaxed)
    }
    fn store_sample(&self, val: Self::Value) {
        self.store(val, Ordering::Relaxed);
    }
}

impl AtomicSampleStorage for AtomicUsize {
    type Value = usize;
    fn load_sample(&self) -> Self::Value {
        self.load(Ordering::Relaxed)
    }
    fn store_sample(&self, val: Self::Value) {
        self.store(val, Ordering::Relaxed);
    }
}

impl AtomicSampleStorage for AtomicBool {
    type Value = bool;
    fn load_sample(&self) -> Self::Value {
        self.load(Ordering::Relaxed)
    }
    fn store_sample(&self, val: Self::Value) {
        self.store(val, Ordering::Relaxed);
    }
}

impl AtomicSampleStorage for AtomicI32 {
    type Value = i32;
    fn load_sample(&self) -> Self::Value {
        self.load(Ordering::Relaxed)
    }
    fn store_sample(&self, val: Self::Value) {
        self.store(val, Ordering::Relaxed);
    }
}

impl AtomicSampleStorage for AtomicI64 {
    type Value = i64;
    fn load_sample(&self) -> Self::Value {
        self.load(Ordering::Relaxed)
    }
    fn store_sample(&self, val: Self::Value) {
        self.store(val, Ordering::Relaxed);
    }
}

impl AtomicSampleStorage for AtomicF32 {
    type Value = f32;
    fn load_sample(&self) -> Self::Value {
        self.load(Ordering::Relaxed)
    }
    fn store_sample(&self, val: Self::Value) {
        self.store(val, Ordering::Relaxed);
    }
}

/// Helper for non-blocking periodic sampling in the render loop (90Hz OpenXR)
/// with fallback to the last successfully sampled value under lock contention.
///
/// When the lock is free, evaluates `f(&*guard)`, stores the result in atomic
/// storage, and returns it. Under contention, immediately returns the last
/// cached value without blocking the render loop.
pub fn try_sample_cached<T, S, F>(lock: &Mutex<T>, cache: &S, f: F) -> S::Value
where
    S: AtomicSampleStorage,
    F: FnOnce(&T) -> S::Value,
{
    match lock.try_lock() {
        Ok(guard) => {
            let val = f(&*guard);
            cache.store_sample(val);
            val
        }
        Err(_) => cache.load_sample(),
    }
}

/// Non-blocking cached sampler retaining the last successful sample.
#[derive(Debug, Default)]
pub struct CachedSampler<S> {
    cache: S,
}

impl<S: AtomicSampleStorage> CachedSampler<S> {
    pub const fn new(cache: S) -> Self {
        Self { cache }
    }

    pub fn sample<T, F>(&self, lock: &Mutex<T>, f: F) -> S::Value
    where
        F: FnOnce(&T) -> S::Value,
    {
        try_sample_cached(lock, &self.cache, f)
    }

    pub fn load(&self) -> S::Value {
        self.cache.load_sample()
    }

    pub fn store(&self, val: S::Value) {
        self.cache.store_sample(val);
    }
}

pub type CachedU64 = CachedSampler<AtomicU64>;
pub type CachedU32 = CachedSampler<AtomicU32>;
pub type CachedF32 = CachedSampler<AtomicF32>;

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::thread;
    use std::time::Duration;

    #[test]
    fn new_generations_do_not_share_flags() {
        let gen_a = Generation::new();
        let gen_b = Generation::new();

        gen_a.set_playing(false);

        // Mutating gen_a must have zero effect on gen_b -- this is the
        // exact bug T2.6 fixed (old generations' flags used to be reused).
        assert!(!gen_a.is_playing());
        assert!(gen_b.is_playing());
    }

    #[test]
    fn stop_and_join_signals_is_running_false() {
        let gen = Generation::new();
        let running_flag = gen.is_running_handle();

        gen.stop_and_join(Vec::<JoinHandle<()>>::new());

        assert!(!*running_flag.lock().unwrap());
    }

    #[test]
    fn stop_and_join_wakes_up_paused_workers_instead_of_leaving_them_asleep() {
        let gen = Generation::new();
        gen.set_playing(false); // simulate "paused" state before shutdown

        let playing_flag = gen.is_playing_handle();

        gen.stop_and_join(Vec::<JoinHandle<()>>::new());

        // stop_and_join must flip is_playing back to true so any worker
        // stuck in `if !is_playing { sleep(50ms); continue; }` notices
        // is_running == false on its very next loop iteration instead of
        // possibly sleeping through it.
        assert!(*playing_flag.lock().unwrap());
    }

    /// Simulates a real worker thread loop (structurally identical to the
    /// demux/video/audio thread bodies in `core::playback::load_at`): poll
    /// `is_running`, sleep-and-continue while paused, otherwise do work.
    /// Verifies `stop_and_join` returns only after such a thread has
    /// genuinely exited -- not after a fixed timeout/guess.
    #[test]
    fn stop_and_join_blocks_until_worker_thread_actually_exits() {
        let gen = Generation::new();
        let is_running = gen.is_running_handle();
        let is_playing = gen.is_playing_handle();
        let iterations = Arc::new(AtomicUsize::new(0));
        let iterations_worker = iterations.clone();

        let handle = thread::spawn(move || loop {
            if !*is_running.lock().unwrap() {
                break;
            }
            if !*is_playing.lock().unwrap() {
                thread::sleep(Duration::from_millis(20));
                continue;
            }
            iterations_worker.fetch_add(1, Ordering::SeqCst);
            thread::sleep(Duration::from_millis(5));
        });

        // Let the worker do a bit of real work first.
        thread::sleep(Duration::from_millis(30));

        gen.stop_and_join(vec![handle]);

        // If we get here at all, join() returned -- the thread is provably
        // dead. A regression back to a "sleep and hope" shutdown wouldn't
        // fail this assertion directly, but a worker that never observes
        // is_running == false (e.g. checking a stale/shared flag from a
        // different generation) would hang this test forever.
        assert!(iterations.load(Ordering::SeqCst) > 0);
    }

    #[test]
    fn stop_and_join_resets_is_running_flag() {
        let gen = Generation::new();
        assert!(gen.is_running());
        let handle = thread::spawn(|| {});
        gen.clone().stop_and_join(vec![handle]);
        assert!(!gen.is_running());
    }

    #[test]
    fn try_sample_or_returns_value_when_uncontended() {
        let lock = Mutex::new(42);
        let val = try_sample_or(&lock, 0, |v| *v * 2);
        assert_eq!(val, 84);
    }

    #[test]
    fn try_sample_or_returns_fallback_immediately_when_contended() {
        let lock = Arc::new(Mutex::new(42));
        let (started_tx, started_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();

        let lock_clone = lock.clone();
        let background_thread = thread::spawn(move || {
            let _guard = lock_clone.lock().unwrap();
            started_tx.send(()).unwrap();
            release_rx.recv().unwrap();
        });

        // Aguarda a thread de background adquirir o lock
        started_rx.recv().unwrap();

        // Simula o render loop (90Hz) tentando ler enquanto há contenção
        let start = std::time::Instant::now();
        let val = try_sample_or(&lock, 999, |v| *v);
        let elapsed = start.elapsed();

        // Libera a thread de background
        release_tx.send(()).unwrap();
        background_thread.join().unwrap();

        assert_eq!(
            val, 999,
            "deve retornar fallback imediatamente sob contencao"
        );
        assert!(
            elapsed < Duration::from_millis(10),
            "try_sample_or demorou demais ({elapsed:?}), bloqueou a thread!"
        );
    }

    #[test]
    fn try_sample_cached_returns_last_successful_value_during_contention_and_new_value_when_free() {
        let lock = Arc::new(Mutex::new(10u64));
        let cache = AtomicU64::new(0);

        // Initial sample when uncontended: reads current value 10 and stores it in cache
        let val1 = try_sample_cached(&lock, &cache, |v| *v);
        assert_eq!(val1, 10);
        assert_eq!(cache.load(Ordering::Relaxed), 10);

        // Spawn background thread to simulate load/seek holding lock
        let (started_tx, started_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();

        let lock_clone = lock.clone();
        let background_thread = thread::spawn(move || {
            let mut guard = lock_clone.lock().unwrap();
            *guard = 42; // value updated while lock is held
            started_tx.send(()).unwrap();
            release_rx.recv().unwrap();
        });

        started_rx.recv().unwrap();

        // While contended, try_sample_cached MUST return the last known value (10), NOT 0
        let contended_val = try_sample_cached(&lock, &cache, |v| *v);
        assert_eq!(
            contended_val, 10,
            "must return last successful value during lock contention"
        );

        // Release lock
        release_tx.send(()).unwrap();
        background_thread.join().unwrap();

        // Once free, try_sample_cached returns the new value (42) and updates the cache
        let new_val = try_sample_cached(&lock, &cache, |v| *v);
        assert_eq!(new_val, 42);
        assert_eq!(cache.load(Ordering::Relaxed), 42);
    }

    #[test]
    fn try_sample_cached_f32_works_with_atomic_f32() {
        let lock = Arc::new(Mutex::new(12.5f32));
        let cache = AtomicF32::new(0.0);

        let val1 = try_sample_cached(&lock, &cache, |v| *v);
        assert_eq!(val1, 12.5);

        let (started_tx, started_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();

        let lock_clone = lock.clone();
        let background_thread = thread::spawn(move || {
            let mut guard = lock_clone.lock().unwrap();
            *guard = 99.5;
            started_tx.send(()).unwrap();
            release_rx.recv().unwrap();
        });

        started_rx.recv().unwrap();

        // While contended, returns 12.5 instead of 0.0
        let contended_val = try_sample_cached(&lock, &cache, |v| *v);
        assert_eq!(contended_val, 12.5);

        release_tx.send(()).unwrap();
        background_thread.join().unwrap();

        let new_val = try_sample_cached(&lock, &cache, |v| *v);
        assert_eq!(new_val, 99.5);
    }

    #[test]
    fn cached_sampler_struct_helper() {
        let lock = Arc::new(Mutex::new(5u32));
        let sampler = CachedU32::new(AtomicU32::new(0));

        assert_eq!(sampler.sample(&lock, |v| *v), 5);

        let (started_tx, started_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();

        let lock_clone = lock.clone();
        let background_thread = thread::spawn(move || {
            let mut guard = lock_clone.lock().unwrap();
            *guard = 15;
            started_tx.send(()).unwrap();
            release_rx.recv().unwrap();
        });

        started_rx.recv().unwrap();

        assert_eq!(sampler.sample(&lock, |v| *v), 5);

        release_tx.send(()).unwrap();
        background_thread.join().unwrap();

        assert_eq!(sampler.sample(&lock, |v| *v), 15);
    }
}
