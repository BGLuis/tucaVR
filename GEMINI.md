# GEMINI.md

This file provides architectural context, build workflows, testing guidelines, and coding conventions for **Gemini CLI / Antigravity** and other AI assistants working in the **vr-multmidia** repository.

---

## 1. Project Overview

The **tucaVR** is an immersive 2D/3D video player built specifically for the **Meta Quest 3** (Qualcomm XR2 Gen 2 platform).

- **Application Model:** 100% immersive OpenXR application (`NativeActivity`), declared with `<meta-data android:name="com.oculus.vr.mode" android:value="vr_only" />` in `AndroidManifest.xml`. There are **no** standard 2D Android activities or flat Compose windows.
- **User Interface:** UI is rendered via `android.app.Presentation` on a `VirtualDisplay`. The resulting buffers are rendered to textures and projected onto interactive 3D quad panels in VR space by the C++ engine.
- **Specifications & Requirements:** Technical specifications and Architecture Decision Records (ADRs) live in `docs/REQUIREMENTS.md`. Phase tracking and task breakdowns (`T1.1`, `T6.3`, etc.) reside in `docs/phases/PHASE-0.*.md`.
- **Code Language Conventions:** Source code comments and internal documentation are written in **Portuguese (Brazil)**; commit messages are written in **English** (Conventional Commits). Preserve these conventions when modifying or adding code.

---

## 2. Three-Language Architecture

The system is strictly partitioned into three communicating layers:

```
┌────────────────────────────────────────────────────────┐
│                   Kotlin (app/)                        │
│  UI (VirtualDisplay/Presentation), Room, Network Auth  │
└───────────────────────────▲────────────────────────────┘
                            │ JNI
┌───────────────────────────▼────────────────────────────┐
│                    C++ (native/)                       │
│ OpenXR Session, Vulkan/GLES Render Loop, Touch Inputs  │
└───────────────────────────▲────────────────────────────┘
                            │ C ABI (extern "C")
┌───────────────────────────▼────────────────────────────┐
│                    Rust (rust/)                        │
│ Demuxer (ffmpeg-next), MediaCodec, Oboe Audio, Network │
└────────────────────────────────────────────────────────┘
```

### Layer Responsibilities:

1. **Kotlin (`app/src/main/java/com/tucavr/`):**
   - Android application shell and lifecycle management (`VRActivity.kt`).
   - UI views using pure Android `View`s inside `VRPresentation` / `VRControlsPresentation` hosted on a `VirtualDisplay` (no XML layouts, no Jetpack Compose).
   - Custom UI design system in `com.tucavr.designsystem` (`VoidButton`, `VoidTheme`, `VoidPanelChrome`, etc.).
   - Secure credential storage using `androidx.security:security-crypto` (`EncryptedSharedPreferences` — **never** store passwords in plain text).
   - Playback history persistence via Android Room DB using KSP (`app/src/main/java/com/tucavr/history/`).
   - Localization & i18n resources (`app/src/main/res/values/strings.xml` and `values-pt-rBR/strings.xml`).

2. **C++ (`native/src/`, built via CMake `native/CMakeLists.txt`):**
   - OpenXR session, swapchains, tracking, reference spaces (`XR_REFERENCE_SPACE_TYPE_STAGE`), and frame render loop.
   - Built on Meta's `SampleXrFramework` (OVRFW) from `sdk/meta-openxr-sdk/`.
   - **Graphics Backends:**
     - **Vulkan (`vr_player_app_vulkan.cpp`):** Primary/default graphics backend (see `docs/VULKAN-MIGRATION-PLAN.md`).
     - **OpenGL ES (`vr_player_app.cpp`):** Maintained fallback backend switchable via Gradle build property.
   - Input handling for Meta Quest Touch Plus controllers, raycast intersection with floating 3D panels, and haptic feedback.
   - Zero-copy video frame import from decoded `AHardwareBuffer` pointers into GPU textures (`samplerExternalOES` on GLES or `VkSamplerYcbcrConversion` / external memory on Vulkan).

3. **Rust (`rust/`, cross-compiled to `aarch64-linux-android` via `cargo ndk`):**
   - Demuxing with `ffmpeg-next` (local files and custom network streams).
   - Hardware-accelerated video decoding via `ndk::MediaCodec`.
   - Low-latency audio rendering with `Oboe` (NDK).
   - Pure-Rust network streaming clients (SMB 2/3, HTTP/HTTPS, FTP, SFTP).

### ⚠️ Critical FFI Boundary Rule (Kotlin $\leftrightarrow$ Rust)
> [!IMPORTANT]
> **Kotlin NEVER calls Rust directly.** Kotlin interacts strictly with C++ via JNI. C++ is the sole consumer of the flat `extern "C"` C-ABI exported by the `rust/bridge` crate. Do NOT introduce UniFFI or direct Kotlin $\leftrightarrow$ Rust bindings (ADR-002 in `docs/REQUIREMENTS.md`), because all video frames and audio states must synchronize with the C++ per-frame render loop.

---

## 3. Rust Workspace Partitioning (`rust/Cargo.toml`)

The Rust codebase is organized into modular crates to isolate Android NDK dependencies and enable fast local host testing:

| Crate | Responsibility | Host Compilation Status |
| :--- | :--- | :--- |
| `core` | Demuxer (`ffmpeg-next`), `MediaCodec` decoder, playback state machine (`PlaybackController`). | **NDK Only:** Transitive dependency on `ndk`, `ndk-sys`, `oboe-sys`. Does not compile on host x86_64 without NDK toolchain. |
| `audio` | Audio output stream via Oboe (NDK). | **NDK Only:** Requires Android NDK audio headers and runtime. |
| `media-logic` | Pure logic extracted from `core`: A/V synchronization (`SyncManager`), audio resampling math, volume/speed clamps, seek generation tracking. | **Host Testable:** Zero Android/hardware dependencies. Executes directly under `cargo test` on development host and CI. |
| `protocols` | SMB2/3, HTTP(S), FTP, and SFTP streaming clients (pure-Rust implementations, zero native C TLS/SSH library dependencies). | **Host Testable:** Executes under `cargo test` and automated Docker integration tests. |
| `bridge` | C-ABI `extern "C"` (`cdylib` / `staticlib`) consumed by C++ (`vr_player_app.cpp` / `vr_player_app_vulkan.cpp`). | **NDK Only:** Links into the native shared library for Android. |

### Synchronization of the `ScreenMode` Enum
Stereoscopic/projection mode representation (2D, Side-by-Side, Over-Under, 180°, 360°, Cubemap, EAC, etc.) is a numeric enum that **must remain strictly synchronized across 4 locations**:
1. `SCREEN_MODE` comments and `SCREEN_MODE_COUNT` in `rust/bridge/src/lib.rs`.
2. `enum class ScreenMode` in `native/include/screen_mode.h`.
3. The catalog in `ScreenFormatCatalog.kt`.
4. `Format3D::to_screen_mode_index` in `rust/media-logic/src/format3d.rs`.

The host test `format3d::tests::screen_mode_encoding_matches_cpp_kotlin_and_bridge` (runs in CI) fails when the indices or the mode count diverge; it does not check the meaning of each index.

---

## 4. Build & Development Commands

### One-Time Setup After Cloning
Downloads `ffmpeg-android-maker` and prepares the Meta OpenXR SDK dependencies:
```bash
./scripts/setup-deps.sh
```

### Full Unified Build & Deploy
Cross-compiles Rust (`cargo ndk`), places `.so` artifacts in `app/src/main/jniLibs/arm64-v8a`, and executes Gradle assemble:
```bash
./scripts/build.sh
# or
make build
make deploy      # Builds and runs adb install on connected Quest 3
```

### Rust Workspace Only
Must use `cargo ndk` (target `aarch64-linux-android`) for anything touching `core`, `audio`, or `bridge`:
```bash
cd rust && cargo ndk -t aarch64-linux-android -P 26 -o ../app/src/main/jniLibs build --release
```

### Android / Gradle Only
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Switching the Graphics Backend
Vulkan is the default backend. To build with the legacy OpenGL ES backend:
```bash
./gradlew assembleDebug -PvrplayerGraphicsApi=GLES
```

---

## 5. Testing Strategy

### 1. Fast Host Unit Tests (No Headset Required)
Because `rust/core`, `rust/audio`, and `rust/bridge` require the NDK, only `protocols` and `media-logic` run with standard `cargo test`. Pure Kotlin logic runs on the JVM via Gradle:

```bash
# Rust unit tests (host-testable crates only)
cd rust && cargo test -p protocols -p media-logic

# Rust linter (enforced in CI with -D warnings)
cd rust && cargo clippy -- -D warnings

# Run a specific Rust test
cd rust && cargo test -p media-logic sync::tests::test_name

# Kotlin JVM unit tests (MediaSorter, DirectoryNavigator, DirectoryLister, History, etc.)
./gradlew testDebugUnitTest

# Run a specific Kotlin test class
./gradlew testDebugUnitTest --tests "com.tucavr.filebrowser.MediaSorterTest"

# Kotlin linter and Android static analysis
./gradlew ktlintCheck
./gradlew :app:lintDebug

# C++ code formatting and shader validation
clang-format --dry-run -Werror native/tests/*.cpp native/include/vk_math.h native/include/screen_mode.h native/include/subtitle_layout.h native/include/hand_tracking.h native/include/environment_config.h
./scripts/check-shaders.sh
```

### 2. Network Protocol Integration Tests (Docker)
Automated integration tests against real SMB, HTTP, HTTPS, FTP, and SFTP containers:
```bash
./scripts/test-network-protocols.sh          # Spins up docker/network-tests/, runs tests, tears down
./scripts/test-network-protocols.sh --keep   # Leaves containers running for debugging
```
> [!NOTE]
> Review `docker/network-tests/README.md` for protocol caveats (FTP passive mode addressing, SFTP chroot ownership, TLS cert `basicConstraints`, Samba `-s` argument ordering) before modifying `docker-compose.yml`.

### 3. Real Device Validation (Meta Quest 3)
Features requiring true OpenXR swapchains, 6DoF controller tracking, haptics, and `MediaCodec` hardware decoding must be tested on the physical device:
- `scripts/soak-test.sh`: Long-duration stability tests (results in `soak-test-results/`).
- `scripts/test-4k-memory.sh` / `scripts/generate-4k-test-clip.sh`: Memory leak and 4K playback validation.
- `scripts/test-3d-playback.sh`: Stereoscopic 3D projection validation.
- See `docs/DEBUGGING.md` for runtime debugging tools (in-scene debug HUD, forcing 3D modes via `adb`, and transition logging).

---

## 6. Coding Conventions & Best Practices

- **Language:** Source code comments follow **Portuguese (Brazil)**; commit messages are in **English** (Conventional Commits).
- **Internationalization (i18n):** User-facing strings must be declared in `app/src/main/res/values/strings.xml` (English default) and `values-pt-rBR/strings.xml` (Portuguese). Always use positional placeholders (`%1$s`, `%1$d`) via `getString(R.string.xxx, arg1)` — never Kotlin string concatenation.
- **Thread Management:** Calls originating from the C++ OpenXR render thread dispatched into Kotlin/Android via JNI must be executed on the UI thread using `runOnUiThread`.
- **Decoder Control (Rust):** Playback state changes (`is_playing`) must suspend the thread (`sleep` / condition variables) rather than killing and re-spawning ffmpeg demux loops.
- **Credential Security:** Network credentials must use `SmbCredentialStore`, `FtpCredentialStore`, and `SftpCredentialStore` backed by `EncryptedSharedPreferences`. Never write plaintext passwords to disk or logs.

---

## 7. Continuous Integration (CI)

The GitHub Actions workflow (`.github/workflows/main.yml`) runs decoupled parallel quality jobs on PRs and pushes:
1. `security-and-workflows`: `actionlint` (workflow syntax), `cargo-deny` (Rust licenses/advisories), `gitleaks` (secret scan).
2. `rust-checks`: `cargo fmt --check`, `cargo clippy -p protocols -p media-logic --all-targets --all-features -- -D warnings`, `cargo test -p protocols -p media-logic`.
3. `cpp-checks`: `clang-format --dry-run -Werror`, `./scripts/check-shaders.sh`, `ENABLE_ASAN=1 ./scripts/test-native-host.sh`.
4. `android-checks`: `./gradlew ktlintCheck`, `./gradlew :app:lintDebug`, `./gradlew testDebugUnitTest`.

A gated job, `build-apk`, depends on all above checks passing and runs the full native C++ + Rust + Gradle build to upload the Quest 3 APK. It runs on `main`/`develop` or via manual `workflow_dispatch`.

---

## 8. Documentation Index (`docs/`)

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): Canonical technical system architecture, tri-layer topology, Vulkan/GLES pipelines, and sequence diagrams.
- [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md): Core business requirements, architecture decisions (ADR-001 through ADR-005).
- [`docs/TESTING-PLAN.md`](docs/TESTING-PLAN.md): Detailed testing isolation rationale and hardware test inventory.
- [`docs/VULKAN-MIGRATION-PLAN.md`](docs/VULKAN-MIGRATION-PLAN.md): Architecture, migration stages, and Vulkan rendering pipeline.
- [`docs/DEBUGGING.md`](docs/DEBUGGING.md): On-device debugging guide, HUD overlay, and ADB trigger commands.
- [`docs/i18n.md`](docs/i18n.md): Localization conventions, plurals, and string resource guidelines.
- [`docs/NETWORK-IO-PERFORMANCE.md`](docs/NETWORK-IO-PERFORMANCE.md): Network buffer analysis and prefetching behavior.
- [`docs/phases/`](docs/phases/): Task tracking by project phase (`PHASE-0.1-MVP.md` through `PHASE-1.0-RELEASE.md`).
- [`docs/reports/`](docs/reports/): Feature investigation reports (3D format auto-detection, spatial audio, DLNA/UPnP, HLS, etc.).
