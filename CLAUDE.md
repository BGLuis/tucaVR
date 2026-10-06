# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

tucaVR for Meta Quest 3 (Qualcomm XR2 Gen 2): a 2D/3D video player built as a fully immersive OpenXR app (`com.oculus.vr.mode = vr_only`, `NativeActivity`, no classic 2D Android UI). Architecture documentation lives in `docs/ARCHITECTURE.md`; requirements/spec lives in `docs/REQUIREMENTS.md` (Portuguese); phase breakdown with task IDs (`T1.1`, `T6.3`, etc.) is in `docs/phases/PHASE-0.*.md`. Code comments and docs are written in Portuguese (BR) — match that when adding comments to existing files.

## Three-language architecture — know which layer you're in

```
Kotlin (app/) <-JNI-> C++ (native/) <-C ABI-> Rust (rust/bridge -> core/protocols/audio/media-logic)
```

- **Kotlin** (`app/src/main/java/com/tucavr/`): Android app shell, UI (drawn as plain Android `View`s inside `android.app.Presentation` on a `VirtualDisplay`, projected as textures onto 3D quads by C++ — **not** XML layouts, **not** Compose), network credential storage, Room-based playback history, i18n resources.
- **C++** (`native/src/`, built via CMake, `native/CMakeLists.txt`): OpenXR session/swapchain/render loop, controller input, zero-copy import of decoded `AHardwareBuffer` frames. Two graphics back-ends, chosen at build time:
  - **Vulkan** (`vr_player_app_vulkan.cpp` + `vr_player_jni_vulkan.cpp`) is what every default build compiles: `app/build.gradle.kts` passes `-DVRPLAYER_GRAPHICS_API=VULKAN` unless `-PvrplayerGraphicsApi=GLES` is given, overriding the `GLES` default in `native/CMakeLists.txt`. Frames are imported via `VkSamplerYcbcrConversion`; this path does not link OVRFW.
  - **GLES** (`vr_player_app.cpp`, Meta's `SampleXrFramework`/OVRFW from `sdk/meta-openxr-sdk/`, frames via `eglCreateImageKHR` -> `GL_TEXTURE_EXTERNAL_OES`) stays compilable as a fallback until headset validation of the Vulkan cut (`docs/VULKAN-MIGRATION-PLAN.md`, Stage 6), but is frozen: rendering work goes into the Vulkan path only, with no mirroring into GLES.
- **Rust** (`rust/`, cross-compiled to `aarch64-linux-android` via `cargo ndk`): demuxing (`ffmpeg-next`), hardware decode via `ndk::MediaCodec`, audio (Oboe), network protocol clients (SMB/HTTP/HTTPS/FTP/SFTP).

**Critical rule for the Kotlin<->Rust relationship**: Kotlin never calls Rust directly. It only talks to C++ via JNI; C++ is the only caller of the Rust `bridge` crate's flat `extern "C"` API (see the header comment in `rust/bridge/src/lib.rs`). Don't introduce a Kotlin->Rust UniFFI path — that was considered and rejected (ADR-002 in `docs/REQUIREMENTS.md`) because there is no call path where Kotlin needs to talk to Rust without going through C++'s per-frame render loop.

### Rust workspace crates (`rust/Cargo.toml` members)

- `core` — demuxer (dispatches `smb://`/`https://` to custom I/O in `protocols`, local/`http://` to native libavformat — see `demuxer.rs`), MediaCodec decoder, playback/sync state. **Depends on `ndk`/`ndk-sys`/`oboe-sys` transitively — does not compile on a normal host.**
- `audio` — Oboe (NDK) audio output. Same host-compile restriction as `core`.
- `media-logic` — **zero Android/hardware dependencies, deliberately.** Pure logic extracted out of `core` (`SyncManager`, audio-resample math, playback speed/volume clamps, playback "generation" contract) specifically so it can run under plain `cargo test` on a laptop/CI. When touching sync, resample, or playback-param logic in `core`, check whether the actual logic lives here instead (`core` re-exports/delegates to it) — see `docs/TESTING-PLAN.md` section 2 for the full reasoning.
- `protocols` — pure-Rust streaming clients and format parsers (SMB 2/3, HTTP(S), FTP, SFTP, NFS, WebDAV, DLNA/UPnP, HLS, DASH, chunking, discovery, download, folder_scan, prefetch; zero native C TLS/SSH dependencies). Host-testable.
- `bridge` — the `cdylib`/`staticlib` consumed by C++; the only crate C++ links against.

Screen/stereo mode encoding (2D/SBS/OU/360/180/Cubemap/EAC/Fisheye variants) is a numeric enum that **must stay in sync across four places**: `SCREEN_MODE` comments and `SCREEN_MODE_COUNT` in `rust/bridge/src/lib.rs`, `enum class ScreenMode` in `native/include/screen_mode.h`, the catalog in `ScreenFormatCatalog.kt`, and `Format3D::to_screen_mode_index` in `rust/media-logic/src/format3d.rs`. The host test `format3d::tests::screen_mode_encoding_matches_cpp_kotlin_and_bridge` fails when the indices or the count diverge; it can't check what each index means, so keep the names aligned by hand.

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

Containerized build (no SDK/NDK/Rust/FFmpeg on the host; runs `scripts/build.sh` inside `docker/build/`, still needs the Meta SDK under `sdk/`; does not work from a `git worktree`):
```bash
./scripts/docker-build.sh   # or: make docker-build
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

# Rust lint (same scope as CI; workspace-wide clippy fails on the host for the NDK crates)
cd rust && cargo clippy -p protocols -p media-logic --all-targets --all-features -- -D warnings

# Single Rust test
cd rust && cargo test -p media-logic sync::tests::some_test_name

# C++ host unit tests (native math, screen mode, subtitle layout, hand tracking, environment config)
# Requires cmake and libopenxr-dev (or run via ./scripts/test-native-host.sh)
./scripts/test-native-host.sh

# Kotlin JVM unit tests (app/src/test — pure logic only: MediaSorter, DirectoryNavigator,
# DirectoryLister, ThumbnailGenerator cache-key, PlaybackHistory mapping/format/throttle, I18nParityTest)
./gradlew testDebugUnitTest

# Single Kotlin test class
./gradlew testDebugUnitTest --tests "com.tucavr.filebrowser.MediaSorterTest"

# Kotlin lint and Android static analysis
./gradlew ktlintCheck
./gradlew :app:lintDebug

# Run all host unit tests at once (Rust + C++ + Android lint & unit tests)
make test
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

UI strings live in `app/src/main/res/values/strings.xml` (English, default), `values-pt-rBR/strings.xml` (Portuguese), and `values-es/strings.xml` (Spanish), which mirror key order exactly for side-by-side diffing. Parity across locales is enforced by `I18nParityTest` in `testDebugUnitTest`. Interpolated strings use positional placeholders (`%1$s`, `%1$d`) via `getString(R.string.xxx, arg1, ...)`, never Kotlin string concatenation, so argument order can change per-locale. See `docs/i18n.md` for which files were deliberately left un-externalized (pure-logic files with no user-facing text) and the one real `<plurals>` case (SMB share count). Adding a new locale: see `docs/i18n.md` section covering T8.5.

## CI (`.github/workflows/main.yml`)

The CI workflow runs 4 parallel check jobs:
1. `security-and-workflows`: `actionlint` (workflow lint), `cargo-deny` (Rust licenses and advisories), `gitleaks` (secret scan).
2. `rust-checks`: `cargo fmt --check`, `cargo clippy -p protocols -p media-logic --all-targets --all-features -- -D warnings`, `cargo test -p protocols -p media-logic`.
3. `cpp-checks`: `clang-format --dry-run -Werror`, `./scripts/check-shaders.sh`, `ENABLE_ASAN=1 ./scripts/test-native-host.sh`.
4. `android-checks`: `./gradlew ktlintCheck`, `./gradlew :app:lintDebug`, `./gradlew testDebugUnitTest`.

`build-apk` (runs on `BGLuis/vr-multmidia` or `BGLuis/tucaVR` when SDK PAT is present) depends on all 4 check jobs passing, executes the real native/C++ + Rust + Gradle build, and uploads `VR-Player-APK-<ref>` as an artifact. Both native (CMake/NDK) and Rust (`cargo ndk`) compiles are wrapped with `hendrikmuhs/ccache-action` and `mozilla-actions/sccache-action` respectively to avoid full rebuilds from scratch on every run — see the `ccachePath` detection in `app/build.gradle.kts` (only activates when `ccache` is on `PATH`, so local dev builds are unaffected).

`.github/workflows/release.yml` reuses the CI build instead of compiling the APK itself: it triggers via `workflow_run` after `tucaVR CI` succeeds, looks up that CI run's `VR-Player-APK-main` artifact for the exact commit (`locate-artifact` job, via `gh api .../actions/runs?head_sha=...`) and publishes that. If no matching CI run is found (e.g. releasing an old tag whose artifact expired), it transparently falls back to the full build steps — same job, steps gated by `steps.reuse.outcome != 'success'`.
