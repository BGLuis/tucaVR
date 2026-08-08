//! `SyncManager` moved to the `media-logic` crate so its logic can be unit
//! tested with `cargo test` on a plain host (this `core` crate cannot be
//! built outside an Android cross-compilation environment — it depends on
//! `ndk`/`ndk-sys` directly and on `audio`, which depends on `oboe-sys`).
//!
//! See `rust/media-logic/src/sync.rs` for the implementation and its tests
//! (audio-as-clock-master, wall-clock fallback, pause/resume, speed
//! scaling — T2.5 in PHASE-0.1-MVP.md).
pub use media_logic::sync::SyncManager;
