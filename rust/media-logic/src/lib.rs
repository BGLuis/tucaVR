//! Pure, hardware-free logic extracted out of `core` so it can run under a
//! plain `cargo test` on a developer machine.
//!
//! `core` cannot be built on a normal host: it depends (transitively, via
//! the `audio` crate) on `oboe-sys`, whose build script shells out to a C++
//! compiler expecting the Android NDK's `oboe` sources/toolchain, and it
//! also links against `ndk`/`ndk-sys` directly. Both fail outside an
//! Android cross-compilation environment (verified in this session: `cargo
//! check -p core` and `cargo check -p audio` both fail on host with oboe/
//! ndk toolchain errors). That means anything living inside `core::*`,
//! *even modules with no Android-specific code of their own* (like the old
//! `core::sync`), is untestable on host as long as it's compiled as part of
//! that crate.
//!
//! This crate is the escape hatch: modules here have zero Android/hardware
//! dependencies by construction, so `cargo test -p media-logic` runs
//! instantly on any machine. `core` depends on this crate and re-exports/
//! delegates to it instead of reimplementing the same logic.
pub mod audio_resample;
pub mod error_ring;
pub mod focus_pause;
pub mod format3d;
pub mod metadata_wire;
pub mod playback_params;
pub mod preroll;
pub mod session;
pub mod spatial_audio;
pub mod subtitle;
pub mod sync;
pub mod telemetry;
pub mod upscaling;
pub mod yuv_convert;
