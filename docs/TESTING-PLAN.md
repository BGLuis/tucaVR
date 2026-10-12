# Automated Testing Plan

> Context: all validation used to be manual, on the physical Quest 3 headset.
> That is slow and expensive for iterating on logic that does not really depend
> on real hardware/GPU/network. This document maps what can be automated today,
> what still requires the headset (and why), and records what has been
> implemented. Sections 1, 2 and 7 describe the first test round and are kept as
> historical context; sections 3, 5 and 6 reflect the current state.

## 1. State Before the First Round (historical)

- Rust: the only tests were in `rust/protocols/src/prefetch.rs` (an in-memory
  fake source, sequential read + seek) and `rust/protocols/src/smb/uri.rs`
  (`redact_hides_credentials`, roundtrip). Nothing in `rust/core` or
  `rust/audio`.
- Kotlin: no tests in `app/src/test` or `app/src/androidTest` — the directories
  did not exist.
- CI (`.github/workflows/main.yml`): ran `cargo clippy` (functional) and
  `ktlintCheck` (a silent fallback that never really failed — see
  PHASE-0.1-MVP.md, T1.9). No automated test ran in CI.

## 2. A Structural Constraint Discovered in the First Round

`rust/core` **does not compile on a regular host** (confirmed, not assumed): it
depends directly on `ndk`/`ndk-sys` and, transitively through `audio`, on
`oboe-sys`, whose build script compiles C++ against the Oboe headers expecting
the Android NDK toolchain.

- `cargo check -p audio` on the host fails because `ndk-sys`/`oboe-sys` do not
  compile outside an Android environment.
- `cargo check -p core` fails through the same dependency chain (`core` ->
  `audio` -> `oboe-sys`).
- Cross-compiling to `aarch64-linux-android` via `cargo ndk` (with
  `ANDROID_NDK_HOME`/`ANDROID_NDK_ROOT` set and the FFmpeg libs cross-compiled
  in `PKG_CONFIG_PATH`), the whole workspace (`core`, `audio`, `protocols`,
  `bridge`) compiles normally — but an `aarch64` binary does not run on the
  x86_64 CI runner or on the development host, so this serves to **verify it
  compiles**, not to **run tests**.

This means: any logic that has to be tested with `cargo test` on a regular
laptop/CI cannot live inside the `core` module — even if the specific module
(e.g. the former `core::sync`) uses no Android API directly, the *whole crate*
fails to compile before reaching its code.

**Solution applied**: a new `rust/media-logic` crate, with **zero
Android/hardware dependencies**, to host the pure logic that used to live in
`core`. `core` now depends on `media-logic` and delegates to it instead of
reimplementing the same logic — see `rust/media-logic/src/lib.rs` for the full
rationale. This is not an arbitrary new architecture layer: it is specifically
the mechanism needed so that "testable without a headset" is actually possible
for this code, given the shape of the existing workspace.

## 3. What CAN Be Tested Without a Headset (and Is)

### 3.1 Rust — `rust/media-logic` (runs with `cargo test` on the host)

The crate grew well beyond the first four modules. Current modules (each with
unit tests in the same file): `sync` (`SyncManager`), `audio_resample`,
`playback_params`, `session`, `frame_timing` (`decide_frame_action`),
`preroll`, `buffer_gate`, `focus_pause`, `error_ring` (`ErrorRingBuffer`),
`retry_backoff`, `quality` (adaptive quality controller), `upscaling`,
`format3d` (3D format detection and the `ScreenMode` index encoding),
`metadata_wire`, `codec`, `av1`, `color`, `yuv_convert`, `subtitle`,
`subtitle_ass`, `subtitle_pgs` and `spatial_audio/`.

The original four modules, whose rationale is still relevant:

| Module | What it covers | Extracted from |
|---|---|---|
| `sync.rs` | `SyncManager`: audio as master clock, wall-clock fallback, speed scaling, pause/resume without counting paused time, reset | `core/src/sync.rs` (now a thin re-export) |
| `audio_resample.rs` | The math of the speed control (`target_sample_rate = base/speed`, clamps) and of the T2.6 padding/linesize bug (`valid_sample_count`) | `core/src/audio_decoder.rs::set_speed`/`decode` |
| `playback_params.rs` | Speed (0.5x-2.0x) and volume (0.0-1.0) clamps | `core/src/playback.rs::set_speed`/`set_volume` |
| `session.rs` | The T2.6 playback "generation" contract: generations do not share `is_playing`/`is_running` flags; `stop_and_join` signals stop, wakes paused threads, and only returns after the real `join()` | `core/src/playback.rs::PlaybackSession` (now uses `Generation` internally) |

`SyncManager` gained a clock injection point (`trait Clock`, with `SystemClock`
in production and `FakeClock` in tests) — without it, testing pause/resume and
the wall-clock fallback would require real `sleep()`s and slow/flaky tests. It
is the only behavior change relative to the original code; the public API
(`new`, `start`, `pause`, `resume`, `update_audio_pts`, `get_master_clock`, ...)
is identical.

### 3.2 Rust — `rust/protocols` (host-testable, with unit and integration tests)

Unit tests live next to the code in every module: `prefetch`, `chunking`,
`retry`, `folder_scan`, `http`, `smb`, `ftp`, `sftp`, `nfs`, `webdav`, `dlna`,
`hls`, `dash`, `discovery` and `download`. `http.rs` is tested with a real mock
HTTP server (`httpmock`, a dev-dependency): `probe()` (HEAD with a fallback to
GET+Range, `Accept-Ranges` detection, unreachable server) and
`HttpsRangeSource` (range reads, failing early without range support, reading
past EOF) — with no real server needed.

Integration tests against real servers run in Docker (see section 6 and
`docker/network-tests/README.md`): `rust/protocols/tests/{smb,http,https,ftp,sftp,webdav,dash}_integration.rs`,
all `#[ignore]`d by default and driven by `./scripts/test-network-protocols.sh`.

**A real production bug found and fixed while writing the first of these
tests**: the HEAD branch of `probe()` used `reqwest::Response::content_length()`,
which reqwest's own documentation describes as unreliable when the response has
no body (exactly the HEAD case) — in practice this made `probe()` report
`content_length: Some(0)` for ANY server, even one that correctly answered
`Content-Length: 123456` on the HEAD, which in turn made
`HttpsRangeSource::new`/`read_range` think every offset was past the end of the
file and silently return 0 bytes. Fixed by reading the `Content-Length` header
directly (`parse_content_length_header`) instead of trusting `content_length()`.
This is relevant because T7.1 in PHASE-0.1-MVP.md explicitly documented that the
HTTPS path had never been tested against a real server — this bug would have
shown up exactly in that manual test, and the automated mock-server test caught
it first.

### 3.3 Kotlin — `app/src/test` (pure JVM, no emulator/Robolectric)

61 test files (about 335 `@Test` methods; counted by grepping, not executed for
this document) organized by package, among them: `filebrowser/` (`MediaSorterTest`,
`DirectoryNavigatorTest`, `DirectoryListerTest`, `MediaFilterEngineTest`,
thumbnail cache keys and `MemoryBudgetGateTest`), `history/` (mapping, filter,
tracker, throttle and Room migration tests), `network/` (directory, discovery
and DLNA parsing, saved-server mapping), `download/`, `playlist/`, `photos/`,
`browser/` (bookmarks, error page, scroll, architecture rules), `debug/`
(`DebugStatsParserTest`, `DebugEventLogTest`, `BottleneckStageAnalyzerTest`,
`DebugTelemetryExporterTest`), `designsystem/` (form validators, `ThemeContrastTest`)
and the repository-wide guards: `I18nParityTest` and `UiSourceGuardsTest`.

`ThumbnailGenerator.getThumbnail()` still needs a real `Context` and
`MediaMetadataRetriever` — only the cache-key logic (which is what effectively
decides cache correctness: same file = same key, different/edited file =
different key) was extracted as a pure `internal` function and tested
(`ThumbnailGenerator.cacheKeyFor` now delegates to `CacheKeys.forLocalEntry`).
The rest (decoding a frame, writing to disk) is real Android I/O, outside what
is automatable without Robolectric — and even with Robolectric,
`MediaMetadataRetriever.getFrameAtTime` would have no real video content to
decode, so the test would be of questionable value; better left to manual
verification (the thumbnail shows up correctly in the file browser).

### 3.4 C++ — native host tests (`./scripts/test-native-host.sh`)

C++ unit tests compiled natively on the host (x86_64), with no dependency on the
Quest 3 runtime. The script builds 8 binaries from `native/tests/`:

| Binary | What it covers |
|---|---|
| `test_vk_math` | Vulkan vector and matrix math operations (`native/include/vk_math.h`) |
| `test_input_fsm` | Controller button debouncing and edge detection (play/pause toggle on the rising edge), mirroring `vr_player_input_vulkan.h` |
| `test_screen_mode` | Mapping and properties of the 17 stereo projection modes (`native/include/screen_mode.h`) |
| `test_subtitle_layout` | Spatial placement, line wrapping and 3D layout of subtitles (`native/include/subtitle_layout.h`) |
| `test_hand_tracking` | Hand tracking, pinch gestures and joint mapping (`native/include/hand_tracking.h`) |
| `test_environment_config` | Virtual environment, skybox and ambient lighting configuration (`native/include/environment_config.h`) |
| `test_browser_pose` | Placement of the in-app web browser panel (`native/include/browser_pose.h`) |
| `test_panel_pick` | Ray-versus-panel picking (`native/include/panel_pick.h`) |

They need `g++` and the `libopenxr-dev` package (`sudo apt-get install libopenxr-dev`) for the headers. They can be run with AddressSanitizer enabled through `ENABLE_ASAN=1 ./scripts/test-native-host.sh` (as configured in CI).

## 4. What STILL Requires the Physical Headset (and Why)

- **Real OpenXR rendering** (`native/src/*.cpp`): swapchain, timing of
  `xrWaitFrame`/`xrBeginFrame`/`xrEndFrame`, reprojection/ATW — it only exists
  with the Quest's OpenXR runtime running.
- **Physical controller input and haptics**: raycasting over real panels,
  thumbstick/grip, vibration pulses — needs the controller hardware.
- **Real hardware decoding through MediaCodec**: `HwDecoder`
  (`rust/core/src/decoder.rs`) uses `AMediaCodec`, which only exists in the
  real Android runtime; there is no way to faithfully simulate it without the
  Quest 3's (XR2 Gen 2) hardware decoder behind it.
- **Rust→C++ shared texture**: `AHardwareBuffer`/`ImageReader`
  (`rust/core/src/texture.rs`) is a real Android system API with no host
  equivalent.
- **Oboe / real low-level audio**: `rust/audio` talks to Android's audio driver
  through Oboe; the testable logic (resampling, clamps) was already extracted
  into `media-logic` — what remains (`AudioOutput`, streams, the real callback)
  is inherently hardware.
- **Thermal behavior**: the Quest 3's throttling after prolonged use only shows
  up on real hardware under real load.
- **Real network conditions (Wi-Fi/SMB/NAS)**: the Docker integration suite
  covers real SMB, HTTP(S), FTP, SFTP and WebDAV servers and generated DASH
  fixtures, but real NTLMv2 authentication against a particular NAS/Samba,
  reconnection on genuinely unstable Wi-Fi, and 4K streaming performance over
  SMB/HTTPS still require a real server and network.
- **UI rendered through `VirtualDisplay`/`Presentation`** projected as a quad in
  VR (file browser, controls, network, web browser): the *event routing* (ray
  → synthetic `MotionEvent` → `Presentation`) and the real look of the UI in VR
  can only be confirmed on the headset. The data logic behind it (listing,
  sorting, navigation, cache) is already covered by the JVM tests of section
  3.3.

See `docs/HARDWARE-VALIDATION-CHECKLIST.md` for the headset validation campaign.

## 5. How to Run

```bash
# Rust — host-testable crates (protocols + media-logic)
cd rust && cargo test -p protocols -p media-logic

# Rust — lint scoped to the host-testable crates
cd rust && cargo clippy -p protocols -p media-logic --all-targets --all-features -- -D warnings

# C++ — native host unit tests (needs g++ and libopenxr-dev)
./scripts/test-native-host.sh

# Kotlin — pure JVM tests and static checks
./gradlew testDebugUnitTest
./gradlew ktlintCheck
./gradlew :app:lintDebug

# Run every host test suite at once
make test

# Network protocol integration tests (Docker; no headset needed)
./scripts/test-network-protocols.sh

# Rust — verify the whole workspace compiles for Android
# (does not run tests, only confirms it compiles — needs the NDK + cross-compiled
# FFmpeg; see scripts/build-rust.sh for the required environment variables)
cd rust && cargo ndk -t aarch64-linux-android -P 26 build
```

## 6. CI (`.github/workflows/main.yml`)

The continuous integration workflow runs decoupled parallel jobs for fast host
validation. On pull requests a `changes` job path-filters them, so a job is
skipped when the diff cannot affect it; pushes and manual runs run everything.

1. `security-and-workflows`: `actionlint` (workflow syntax validation), `cargo-deny` (`check licenses bans sources` for Rust dependencies), `gitleaks` (secret scan, non-blocking).
2. `rust-checks`: `cargo fmt --check`, `cargo clippy -p protocols -p media-logic --all-targets --all-features -- -D warnings`, `cargo test -p protocols -p media-logic`.
3. `cpp-checks`: `clang-format --dry-run -Werror`, `./scripts/check-shaders.sh` (GLSL validation and SPIR-V compilation), `ENABLE_ASAN=1 ./scripts/test-native-host.sh` (compiles and runs the 8 host C++ binaries with AddressSanitizer).
4. `android-checks`: `./gradlew ktlintCheck` (formatter with an XML baseline), `./gradlew :app:lintDebug` (Android static analysis), `./gradlew testDebugUnitTest` (Kotlin JVM unit tests, including `I18nParityTest`, `UiSourceGuardsTest` and `ThemeContrastTest`), run with `RUST_BUILD_MODE=skip`.
5. `markdown-checks`: `markdownlint-cli2` over every `*.md`.
6. `ci-gate`: the single check branch protection needs; it fails if any job failed and treats path-filter skips as success.

The APK build job (`build-apk`) depends on `changes` and the four check jobs
above not failing, runs only on `BGLuis/tucaVR` (or the legacy
`BGLuis/vr-multmidia` name) when the APK path filter matches, and builds the
full application (Rust via `cargo ndk`, C++ via CMake, and the Quest 3 APK via
Gradle). The Docker network integration suite is not part of CI; run it locally.

## 7. Small Refactors Done to Enable the Tests (first round, historical)

1. **New `rust/media-logic` crate** (zero Android deps): hosts `SyncManager`
   (moved from `core::sync`, which became a re-export),
   `audio_resample::{target_sample_rate, valid_sample_count}` (extracted from
   `AudioDecoder::set_speed`/`decode`), `playback_params::{clamp_speed,
   clamp_volume}` (extracted from `PlaybackController::set_speed`/`set_volume`),
   and `session::Generation` (generalizes the playback "generation" contract
   that `core::playback`'s `PlaybackSession` uses internally, replacing the
   manual `is_playing`/`is_running` bookkeeping that existed there). All of
   these swaps in `core` are mechanical delegations — same public API, same
   signature — verified with a real cross-compile (`cargo ndk build`, whole
   workspace) after each change, since `core` does not compile on the host.
2. **`SyncManager` gained clock injection** (`trait Clock` +
   `SystemClock`/`FakeClock`) to be able to test pause/resume and the
   wall-clock fallback without a real `sleep()`. It does not change the public
   API used by `core::playback`.
3. **`ThumbnailGenerator.cacheKeyFor`** extracted as a pure `internal` function
   (it used to be an inline block inside `cacheFileFor`), without touching
   `Context`/`MediaMetadataRetriever`/disk.

No new abstraction beyond these was introduced — in particular, no trait/mock
layer was created for `HwDecoder`/`AudioOutput`/`TextureOutput`/`Demuxer`
themselves; that would allow testing the `PlaybackController` end to end, but
would require a larger refactor (those types are used with concrete Android APIs
such as `MediaFormat`, `NativeWindow`, `AHardwareBuffer` spread across several
files) — outside the budget of that round and disproportionate to the marginal
value, since the logic that mattered most to test there (sync/speed math, the
generation/shutdown contract) was already extracted and covered.
