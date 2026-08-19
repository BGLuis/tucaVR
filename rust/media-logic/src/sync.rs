//! `SyncManager` — A/V sync clock (T2.5 in PHASE-0.1-MVP.md).
//!
//! Audio is the clock master: `update_audio_pts` is fed by the real PTS of
//! decoded audio packets. While no audio PTS has arrived yet (start of
//! playback, or files with no audio track at all) the master clock falls
//! back to a wall-clock measurement (`start_time`), scaled by the current
//! playback speed. `pause()`/`resume()` shift `start_time` forward by the
//! pause duration so that `elapsed()` reads the same right before pausing
//! and right after resuming — i.e. the wall-clock fallback doesn't jump
//! forward by however long the video was paused.
//!
//! This module was moved out of `rust/core/src/sync.rs` verbatim (same
//! public API), because `core` cannot be compiled on a normal host (see the
//! crate-level docs in `lib.rs`) and this logic has zero Android
//! dependencies of its own — it only ever used `std::sync`/`std::time`.
//! `core::sync` now just re-exports `SyncManager` from here.
//!
//! The only behavioral addition versus the original is the `Clock`
//! injection point, added so `get_master_clock`'s wall-clock fallback path
//! (and pause/resume math) can be tested deterministically without
//! sleeping in real time.

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Source of "now" for the wall-clock fallback. Production code always uses
/// [`SystemClock`]; tests use [`FakeClock`] to advance time deterministically
/// without real sleeps.
pub trait Clock: Send + Sync {
    fn now(&self) -> Instant;
}

#[derive(Clone, Copy, Default)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> Instant {
        Instant::now()
    }
}

/// A clock that only advances when told to via [`FakeClock::advance`].
/// `Instant` has no public constructor other than `now()`, so this stores an
/// arbitrary real `Instant` as its epoch and adds a controllable offset on
/// top of it — the absolute value is never observed by callers, only
/// differences between two `now()` calls matter to `SyncManager`.
pub struct FakeClock {
    epoch: Instant,
    offset_nanos: std::sync::atomic::AtomicU64,
}

impl FakeClock {
    pub fn new() -> Self {
        Self {
            epoch: Instant::now(),
            offset_nanos: std::sync::atomic::AtomicU64::new(0),
        }
    }

    pub fn advance(&self, d: Duration) {
        self.offset_nanos
            .fetch_add(d.as_nanos() as u64, Ordering::SeqCst);
    }
}

impl Default for FakeClock {
    fn default() -> Self {
        Self::new()
    }
}

impl Clock for FakeClock {
    fn now(&self) -> Instant {
        self.epoch + Duration::from_nanos(self.offset_nanos.load(Ordering::SeqCst))
    }
}

pub struct SyncManager {
    audio_pts: Arc<Mutex<f64>>,
    start_time: Arc<Mutex<Option<Instant>>>,
    pause_start: Arc<Mutex<Option<Instant>>>,
    // Shared with PlaybackController: when there's an audio track, speed
    // control works by resampling audio (see AudioDecoder::set_speed), and
    // the master clock (audio_pts) reflects that automatically. For files
    // WITHOUT audio, the master clock falls back to wall-clock below, which
    // needs to know the speed to track correctly.
    speed_bits: Arc<AtomicU32>,
    clock: Arc<dyn Clock>,
}

impl SyncManager {
    pub fn new(speed_bits: Arc<AtomicU32>, initial_pts: f64) -> Self {
        Self::with_clock(speed_bits, initial_pts, Arc::new(SystemClock))
    }

    pub fn with_clock(speed_bits: Arc<AtomicU32>, initial_pts: f64, clock: Arc<dyn Clock>) -> Self {
        Self {
            audio_pts: Arc::new(Mutex::new(initial_pts)),
            start_time: Arc::new(Mutex::new(None)),
            pause_start: Arc::new(Mutex::new(None)),
            speed_bits,
            clock,
        }
    }

    pub fn start(&mut self) {
        if let Ok(mut st) = self.start_time.lock() {
            *st = Some(self.clock.now());
        }
        if let Ok(mut locked_pts) = self.audio_pts.lock() {
            *locked_pts = 0.0;
        }
    }

    pub fn reset(&self) {
        if let Ok(mut st) = self.start_time.lock() {
            *st = Some(self.clock.now());
        }
        if let Ok(mut locked_pts) = self.audio_pts.lock() {
            *locked_pts = 0.0;
        }
    }

    pub fn update_audio_pts(&self, pts: f64) {
        if let Ok(mut locked_pts) = self.audio_pts.lock() {
            *locked_pts = pts;
        }
    }

    pub fn update_master_clock(&self, time_sec: f64) {
        if let Ok(mut locked_pts) = self.audio_pts.lock() {
            *locked_pts = time_sec;
        }
    }

    pub fn pause(&self) {
        if let Ok(mut pt) = self.pause_start.lock() {
            *pt = Some(self.clock.now());
        }
    }

    pub fn resume(&self) {
        if let Ok(mut pt_lock) = self.pause_start.lock() {
            if let Some(pt) = *pt_lock {
                let pause_duration = self.clock.now().saturating_duration_since(pt);
                if let Ok(mut st_lock) = self.start_time.lock() {
                    if let Some(st) = st_lock.as_mut() {
                        // Advance start_time by pause_duration so elapsed() stays the same
                        *st += pause_duration;
                    }
                }
            }
            *pt_lock = None;
        }
    }

    pub fn get_master_clock(&self) -> f64 {
        if let Ok(pts) = self.audio_pts.lock() {
            let pts_val = *pts;
            if pts_val == 0.0 {
                if let Ok(st_lock) = self.start_time.lock() {
                    if let Some(st) = *st_lock {
                        let speed = f32::from_bits(self.speed_bits.load(Ordering::Relaxed)) as f64;
                        let now = if let Ok(pt_lock) = self.pause_start.lock() {
                            if let Some(pt) = *pt_lock { pt } else { self.clock.now() }
                        } else { self.clock.now() };
                        return now.saturating_duration_since(st).as_secs_f64() * speed;
                    }
                }
            }
            pts_val
        } else {
            0.0
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn speed_bits(speed: f32) -> Arc<AtomicU32> {
        Arc::new(AtomicU32::new(speed.to_bits()))
    }

    #[test]
    fn wall_clock_fallback_tracks_elapsed_time_at_normal_speed() {
        let clock = Arc::new(FakeClock::new());
        let mut sync = SyncManager::with_clock(speed_bits(1.0), clock.clone());
        sync.start();

        clock.advance(Duration::from_millis(500));

        assert!((sync.get_master_clock() - 0.5).abs() < 1e-9);
    }

    #[test]
    fn wall_clock_fallback_scales_by_playback_speed() {
        let clock = Arc::new(FakeClock::new());
        let mut sync = SyncManager::with_clock(speed_bits(2.0), clock.clone());
        sync.start();

        clock.advance(Duration::from_secs(1));

        // At 2x speed, 1 real second of elapsed wall-clock time should read
        // as 2 seconds of "video" time.
        assert!((sync.get_master_clock() - 2.0).abs() < 1e-9);
    }

    #[test]
    fn audio_pts_overrides_wall_clock_fallback_once_present() {
        let clock = Arc::new(FakeClock::new());
        let mut sync = SyncManager::with_clock(speed_bits(1.0), clock.clone());
        sync.start();
        clock.advance(Duration::from_secs(10));

        // Real audio has started reporting timestamps; from here on the
        // master clock must reflect audio PTS, not wall-clock elapsed time,
        // even though they disagree wildly.
        sync.update_audio_pts(3.25);

        assert_eq!(sync.get_master_clock(), 3.25);
    }

    #[test]
    fn pause_then_resume_does_not_count_the_paused_interval() {
        let clock = Arc::new(FakeClock::new());
        let mut sync = SyncManager::with_clock(speed_bits(1.0), clock.clone());
        sync.start();

        clock.advance(Duration::from_secs(2));
        sync.pause();
        clock.advance(Duration::from_secs(5)); // time passes while paused
        sync.resume();
        clock.advance(Duration::from_secs(1));

        // 2s before pause + 1s after resume = 3s of "counted" time; the 5s
        // spent paused must not show up in the master clock.
        assert!((sync.get_master_clock() - 3.0).abs() < 1e-6);
    }

    #[test]
    fn resume_without_a_matching_pause_is_a_harmless_no_op() {
        let clock = Arc::new(FakeClock::new());
        let mut sync = SyncManager::with_clock(speed_bits(1.0), clock.clone());
        sync.start();
        clock.advance(Duration::from_secs(1));

        sync.resume(); // no prior pause() call

        assert!((sync.get_master_clock() - 1.0).abs() < 1e-9);
    }

    #[test]
    fn reset_zeroes_audio_pts_and_restarts_wall_clock() {
        let clock = Arc::new(FakeClock::new());
        let mut sync = SyncManager::with_clock(speed_bits(1.0), clock.clone());
        sync.start();
        sync.update_audio_pts(42.0);
        clock.advance(Duration::from_secs(3));

        sync.reset();

        // audio_pts back to 0.0 means the wall-clock fallback takes over
        // again, measured from the moment of reset() (i.e. ~0s elapsed).
        assert!(sync.get_master_clock() < 1e-6);
    }

    #[test]
    fn multiple_pause_resume_cycles_accumulate_correctly() {
        let clock = Arc::new(FakeClock::new());
        let mut sync = SyncManager::with_clock(speed_bits(1.0), clock.clone());
        sync.start();

        clock.advance(Duration::from_secs(1));
        sync.pause();
        clock.advance(Duration::from_secs(10));
        sync.resume();

        clock.advance(Duration::from_secs(1));
        sync.pause();
        clock.advance(Duration::from_secs(10));
        sync.resume();

        clock.advance(Duration::from_secs(1));

        // 1 + 1 + 1 = 3 counted seconds across two pause/resume cycles.
        assert!((sync.get_master_clock() - 3.0).abs() < 1e-6);
    }
}
