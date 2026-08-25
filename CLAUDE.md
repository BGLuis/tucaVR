# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

tucaVR for Meta Quest 3 (Qualcomm XR2 Gen 2): a 2D/3D video player built as a fully immersive OpenXR app (`com.oculus.vr.mode = vr_only`, `NativeActivity`, no classic 2D Android UI). Requirements/spec lives in `docs/REQUIREMENTS.md` (Portuguese); phase breakdown with task IDs (`T1.1`, `T6.3`, etc.) is in `docs/phases/PHASE-0.*.md`. Code comments and docs are written in Portuguese (BR) — match that when adding comments to existing files.

## Three-language architecture — know which layer you're in

```
Kotlin (app/) <-JNI-> C++ (native/) <-C ABI-> Rust (rust/bridge -> core/protocols/audio/media-logic)
```

- **Kotlin** (`app/src/main/java/com/tucavr/`): Android app shell, UI (drawn as plain Android `View`s inside `android.app.Presentation` on a `VirtualDisplay`, projected as textures onto 3D quads by C++ — **not** XML layouts, **not** Compose), network credential storage, Room-based playback history, i18n resources.
- **C++** (`native/src/vr_player_app.cpp`, built via CMake, `native/CMakeLists.txt`): OpenXR session/swapchain/render loop, Meta's `SampleXrFramework` (OVRFW) from `sdk/meta-openxr-sdk/`, controller input, converts decoded `AHardwareBuffer` frames to `GL_TEXTURE_EXTERNAL_OES` via `eglCreateImageKHR` for zero-copy video rendering.
- **Rust** (`rust/`, cross-compiled to `aarch64-linux-android` via `cargo ndk`): demuxing (`ffmpeg-next`), hardware decode via `ndk::MediaCodec`, audio (Oboe), network protocol clients (SMB/HTTP/HTTPS/FTP/SFTP).

**Critical rule for the Kotlin<->Rust relationship**: Kotlin never calls Rust directly. It only talks to C++ via JNI; C++ is the only caller of the Rust `bridge` crate's flat `extern "C"` API (see the header comment in `rust/bridge/src/lib.rs`). Don't introduce a Kotlin->Rust UniFFI path — that was considered and rejected (ADR-002 in `docs/REQUIREMENTS.md`) because there is no call path where Kotlin needs to talk to Rust without going through C++'s per-frame render loop.

### Rust workspace crates (`rust/Cargo.toml` members)

- `core` — demuxer (dispatches `smb://`/`https://` to custom I/O in `protocols`, local/`http://` to native libavformat — see `demuxer.rs`), MediaCodec decoder, playback/sync state. **Depends on `ndk`/`ndk-sys`/`oboe-sys` transitively — does not compile on a normal host.**
- `audio` — Oboe (NDK) audio output. Same host-compile restriction as `core`.
- `media-logic` — **zero Android/hardware dependencies, deliberately.** Pure logic extracted out of `core` (`SyncManager`, audio-resample math, playback speed/volume clamps, playback "generation" contract) specifically so it can run under plain `cargo test` on a laptop/CI. When touching sync, resample, or playback-param logic in `core`, check whether the actual logic lives here instead (`core` re-exports/delegates to it) — see `docs/TESTING-PLAN.md` section 2 for the full reasoning.
- `protocols` — SMB2/3, HTTP(S), FTP, SFTP clients (all pure-Rust, no native TLS/SSH libs, to avoid cross-compile pain). Host-testable.
- `bridge` — the `cdylib`/`staticlib` consumed by C++; the only crate C++ links against.

Screen/stereo mode encoding (2D/SBS/OU/360/180 variants) is a numeric enum that **must stay in sync across three places**: `SCREEN_MODE` comments in `rust/bridge/src/lib.rs`, `enum class ScreenMode` in `native/src/vr_player_app.cpp`, and the positional lookup in `modeLabelResIds` in `VRControlsPresentation.kt`.

## Build commands

One-time setup after cloning (downloads `ffmpeg-android-maker` and prompts for the manually-licensed Meta OpenXR SDK):
```bash
./scripts/setup-deps.sh
```

Full unified build (Rust cross-compile -> copy `.so`s into `jniLibs` -> Gradle):
```bash
./scripts/build.sh
# or
make build      # same thing
make deploy      # build + adb install
```

Rust alone (must use `cargo ndk`, not plain `cargo build`, for anything touching `core`/`audio`/`bridge`):
```bash
cd rust && cargo ndk -t aarch64-linux-android -P 26 -o ../app/src/main/jniLibs build --release
```

Android alone:
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Testing

**Hard constraint**: `rust/core`, `rust/audio`, and `rust/bridge` do not compile on a normal host (`ndk-sys`/`oboe-sys` require the Android NDK toolchain). Only `protocols` and `media-logic` run with plain `cargo test`. This is why pure logic gets extracted into `media-logic` rather than tested in place inside `core` — see `docs/TESTING-PLAN.md` for the full rationale and inventory of what is/isn't automatable without a headset.

```bash
# Rust unit tests (host-testable crates only)
cd rust && cargo test -p protocols -p media-logic

# Rust lint (CI runs this with -D warnings)
cd rust && cargo clippy -- -D warnings

# Single Rust test
cd rust && cargo test -p media-logic sync::tests::some_test_name

# Kotlin JVM unit tests (app/src/test — pure logic only: MediaSorter, DirectoryNavigator,
# DirectoryLister, ThumbnailGenerator cache-key, PlaybackHistory mapping/format/throttle)
./gradlew testDebugUnitTest

# Single Kotlin test class
./gradlew testDebugUnitTest --tests "com.tucavr.filebrowser.MediaSorterTest"

# Kotlin lint
./gradlew ktlintCheck
```

Network protocol integration tests (real SMB/HTTP/HTTPS/FTP/SFTP servers via Docker, `#[ignore]`d by default):
```bash
./scripts/test-network-protocols.sh          # spins up docker/network-tests/, runs, tears down
./scripts/test-network-protocols.sh --keep   # leaves containers up for debugging
```
Requires docker + docker compose plugin, curl, sha256sum. No headset needed. See `docker/network-tests/README.md` for per-protocol gotchas (FTP passive-mode addressing, SFTP chroot ownership rules, TLS cert `basicConstraints`, samba `-s` field ordering) before touching `docker-compose.yml`.

Everything requiring actual OpenXR rendering, controller haptics, or hardware `MediaCodec` decode has no automated coverage and needs the physical Quest 3 headset — don't claim these are verified without stating that explicitly.

For debugging video playback/rendering on-device (forcing a `ScreenMode` via adb without a real file in that format, an in-scene debug HUD, transition logging, optional Vulkan validation layers), see `docs/DEBUGGING.md` — debug-build-only, no-op in release.

There's also `scripts/soak-test.sh` (long-run stability, results land in `soak-test-results/`, gitignored) and `scripts/test-4k-memory.sh` / `scripts/generate-4k-test-clip.sh` for memory validation against a synthetic 4K clip (`testdata/`, gitignored).

## i18n

UI strings live in `app/src/main/res/values/strings.xml` (English, default) and `values-pt-rBR/strings.xml` (Portuguese, mirrors key order exactly for side-by-side diffing). Interpolated strings use positional placeholders (`%1$s`, `%1$d`) via `getString(R.string.xxx, arg1, ...)`, never Kotlin string concatenation, so argument order can change per-locale. See `docs/i18n.md` for which files were deliberately left un-externalized (pure-logic files with no user-facing text) and the one real `<plurals>` case (SMB share count). Adding a new locale: see `docs/i18n.md` section covering T8.5.

## CI (`.github/workflows/main.yml`)

Runs, in order: `cargo clippy -D warnings`, `cargo test -p protocols -p media-logic`, `ktlintCheck` (currently non-blocking — falls back to an echo on failure), `./gradlew testDebugUnitTest`. The full native/C++ build is commented out (requires the licensed Meta OpenXR SDK, not available in CI).
