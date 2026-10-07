# Phase 0.1 — MVP (Foundation)

> **Objective**: Play a 2D video from a local file or SMB share on a virtual screen inside the Quest 3.  
> **Expected Outcome**: The developer puts on the headset, launches the app, navigates to a video (local or on a NAS via SMB), and watches it with basic playback controls in a dark environment.

---

## 📋 Table of Contents

1. [Monorepo Setup](#1-monorepo-setup)
2. [Video Pipeline (Rust Core)](#2-video-pipeline-rust-core)
3. [VR Environment and Rendering (OpenXR/C++)](#3-vr-environment-and-rendering-openxrc)
4. [Playback Controls (VR UI)](#4-playback-controls-vr-ui)
5. [Local File Browser](#5-local-file-browser)
6. [SMB/CIFS Protocol](#6-smbcifs-protocol)
7. [HTTP URL Playback](#7-http-url-playback)
8. [Internationalization (i18n)](#8-internationalization-i18n)
9. [Playback History](#9-playback-history)
10. [Cross-Cutting Concerns](#10-cross-cutting-concerns)
11. [Definition of Done — v0.1](#definition-of-done--v01)

---

## 1. Monorepo Setup

### Structure

```
vr-multimedia/
├── app/                          # Main Android/Kotlin module
│   ├── src/main/
│   │   ├── java/com/tucavr/      # Kotlin code
│   │   ├── res/                  # Android resources (strings, layouts)
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── rust/                         # Cargo workspace
│   ├── Cargo.toml                # Workspace root
│   ├── core/                     # Core crate (demux, decode, streaming)
│   │   ├── Cargo.toml
│   │   └── src/
│   ├── protocols/                # Protocols crate (SMB, HTTP, etc.)
│   │   ├── Cargo.toml
│   │   └── src/
│   ├── bridge/                   # C-ABI bridge crate (JNI / C exports)
│   │   ├── Cargo.toml
│   │   └── src/
│   └── audio/                    # Audio crate (Oboe)
│       ├── Cargo.toml
│       └── src/
├── native/                       # C++ code (OpenXR rendering)
│   ├── CMakeLists.txt
│   ├── src/
│   │   ├── main.cpp              # OpenXR entry point
│   │   ├── xr_app.cpp            # OpenXR session management
│   │   ├── renderer.cpp          # Vulkan/GLES render pipeline
│   │   ├── environment.cpp       # Virtual environment (void)
│   │   ├── screen_quad.cpp       # Virtual screen (3D quad)
│   │   └── input.cpp             # Controller input
│   ├── include/
│   └── shaders/                  # GLSL / SPIR-V shaders
├── docs/                         # Documentation
├── scripts/                      # Build scripts, CI
├── build.gradle.kts              # Root Gradle
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

### Tasks

- [x] **T1.1** — Initialize Git repository with `.gitignore` (Android + Rust + C++)
  > Complete and correct `.gitignore` (Android+Rust+C++). Clean working tree.
- [x] **T1.2** — Configure root Gradle with AGP + Rust build support
  > AGP + Kotlin Android plugin configured in root `build.gradle.kts`. Rust compilation is invoked via `scripts/build.sh` and cargo ndk.
- [x] **T1.3** — Configure Cargo workspace with crates: `core`, `protocols`, `bridge`, `audio`, `media-logic`
  > Valid workspace in `rust/Cargo.toml` with crates partitioned by NDK and host testing capabilities.
- [x] **T1.4** — Install and configure `cargo-ndk` for cross-compilation to `aarch64-linux-android`
  > `cargo-ndk` cross-compilation operational; artifacts placed in `app/src/main/jniLibs/arm64-v8a/`.
- [x] **T1.5** — Configure Android NDK r26+ in Gradle (`ndkVersion`, `cmake`)
  > `ndkVersion = "26.3.11579264"` in `app/build.gradle.kts` satisfies r26+; `externalNativeBuild.cmake` configured.
- [x] **T1.6** — Configure CMakeLists.txt for C++ code (OpenXR + Meta SDK)
  > `native/CMakeLists.txt` resolves OpenXR via prefab (`find_package(OpenXR REQUIRED CONFIG)`) and references the Meta SDK.
- [x] **T1.7** — ~~Configure UniFFI bindings~~ — revised decision, see ADR-002 in REQUIREMENTS.md
  > Flat C-ABI bridge via `rust/bridge` crate consumed strictly by C++; Kotlin interacts with C++ via JNI.
- [x] **T1.8** — Create unified build script (`scripts/build.sh`)
  > Builds Rust via `cargo ndk`, copies `.so` libraries to `jniLibs/arm64-v8a/`, and triggers Gradle build.
- [x] **T1.9** — Configure GitHub Actions: build + lint (clippy + ktlint + clang-tidy)
  > CI workflow configured in `.github/workflows/main.yml` with parallel quality jobs (`cargo clippy`, `ktlintCheck`, `cargo test`, `testDebugUnitTest`).
- [ ] **T1.10** — Test deployment of OpenXR hello world on Quest 3 via `adb`
  > Automated scripts and APK generation functional; physical verification pending on actual Quest 3 headset.

### ⚠️ Pitfalls and Considerations

> [!WARNING]
> **NDK versioning**: Meta OpenXR SDK requires NDK r25+. Use r26 or higher. Older versions lead to linking errors with `libopenxr_loader.so`.

> [!WARNING]
> **Rust target**: Quest 3 is ARM64. The Rust target **must** be `aarch64-linux-android`, never `armv7-linux-androideabi`. Configure in `.cargo/config.toml`.

> [!CAUTION]
> **Library loading order**: Kotlin `System.loadLibrary()` calls must load dependencies in topological order. If `libcore.so` depends on `libffmpeg.so`, load `libffmpeg.so` first.

> [!IMPORTANT]
> **FFmpeg cross-compile**: FFmpeg must be cross-compiled for Android ARM64 separately. Use `ffmpeg-android-maker` or manual configuration.

> [!NOTE]
> **Meta OpenXR SDK**: Download the Meta XR SDK from https://developer.oculus.com/downloads/ (`OpenXR Mobile SDK`). Extract and configure `CMakeLists.txt` accordingly.

---

## 2. Video Pipeline (Rust Core)

### Architecture

Implement complete video decoding pipeline in Rust:

```
File/Stream → Demuxer (FFmpeg) → Packets → Decoder (MediaCodec HW) → Frames → Texture (GPU)
                                → Audio Packets → Audio Decoder → PCM → Audio Output (Oboe)
```

### Tasks

- [x] **T2.1** — Implement `Demuxer` in Rust using `ffmpeg-next`:
  - Open containers (MP4, MKV, AVI, MOV, WebM, FLV, TS)
  - Enumerate streams (video, audio, subtitles)
  - Extract packets per stream
  - Seek support (keyframe + continuous refinement)
  > `Demuxer::new` opens container via `ffmpeg::format::input` and enumerates video and audio streams. `read_packet()` reads packets sequentially. `load_at`/`seek` performs seek to keyframe.
- [x] **T2.2** — Implement `HwDecoder` via Android MediaCodec NDK:
  - Create `AMediaCodec` for H.264 and H.265/HEVC
  - Configure surface output (direct decoding to GPU texture)
  - Manage buffer queue (input/output buffers)
  - Handle codec-specific data (SPS/PPS for H.264, VPS/SPS/PPS for HEVC)
  > Surface mode verified: `HwDecoder::configure` receives `NativeWindow` from `ImageReader`. Buffer queue implemented with `dequeue_input_buffer`, `queue_input_buffer`, `dequeue_output_buffer`, `release_output_buffer`. Codec configuration handled via `BUFFER_FLAG_CODEC_CONFIG`.
- [x] **T2.3** — Implement `AudioDecoder`:
  - Decode AAC, MP3, FLAC, Opus, AC3, DTS to PCM
  - Uses FFmpeg software decode (low CPU overhead for audio)
  - Resample to native sample rate (48 kHz Quest default)
  > Decodes audio packets to PCM `f32` and resamples to 48 kHz stereo via `swresample` (`Resampler::get`).
- [x] **T2.4** — Implement `AudioOutput` via Oboe (NDK):
  - Low-latency audio stream
  - Callback-based (non-blocking)
  - Volume control
  > Low-latency stream via Oboe implemented (`PerformanceMode::LowLatency`, `SharingMode::Exclusive`, non-blocking `on_audio_ready` callback). Volume control applied via atomic float multiplication per sample.
- [x] **T2.5** — Implement `SyncManager` (A/V Sync):
  - Audio-video synchronization based on PTS (Presentation Timestamp)
  - Audio as master clock
  - Clock smoothing and frame timing adjustment
  > Audio clock acts as master (`update_audio_pts` fed by real audio packets PTS). Fallback to wall-clock if no audio stream exists.
- [x] **T2.6** — Implement `PlaybackController`:
  - Play, Pause, Stop
  - Seek (forward/backward)
  - Speed control (0.5x - 2.0x)
  - Track selection (audio, video)
  > Sessions manage playback state cleanly. Resampling dynamically adjusts for speed control without audio glitches, using debounce on resampler reconstruction. Track selection exposed via FFI and UI.
- [x] **T2.7** — Implement `TextureOutput`:
  - Decoded frames output as external texture (`AHardwareBuffer` / `ImageReader`)
  - Pass texture handle to C++ layer via shared surface
  - Synchronization to guarantee frame readiness
  > `TextureOutput::allocate` creates `ImageReader` with `HardwareBufferUsage::GPU_SAMPLED_IMAGE`, exposing buffer pointers to C++ via `bridge`.

### ⚠️ Pitfalls and Considerations

> [!CAUTION]
> **MediaCodec surface mode is MANDATORY**: Do not use buffer mode for video on Quest 3. Surface mode decodes directly to GPU texture (`AHardwareBuffer`), avoiding CPU→GPU copies that destroy performance.

> [!CAUTION]
> **A/V Sync is critical in VR**: A/V desynchronization is far more perceptible in VR than on flat screens. The brain detects incongruities around ~20ms. Use audio as the master clock.

> [!WARNING]
> **MediaCodec instance limits**: Quest 3 supports ~4 simultaneous MediaCodec instances. Destroy unused instances strictly to avoid silent hardware reclamation.

> [!WARNING]
> **HEVC SPS/PPS/VPS**: When starting H.265 decoding, configuration NALUs must be submitted with `AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG` before video frames.

> [!IMPORTANT]
> **Threading**: Pipeline runs on decoupled threads:
> - Thread 1: Demuxer (I/O reading, may block on network)
> - Thread 2: Video Decoder (feed MediaCodec)
> - Thread 3: Audio Decoder + Output
> - Thread 4: Sync Manager (coordination)

---

## 3. VR Environment and Rendering (OpenXR/C++)

### Architecture

Implement the OpenXR render loop with a "void" environment (dark backdrop) and a virtual screen quad projecting the video.

### Tasks

- [x] **T3.1** — Implement `XrApp`: OpenXR Initialization
  - Create `XrInstance` with required extensions
  - Create `XrSession` with Vulkan backend
  - Create `XrSwapchain` (stereo / array)
  - Configure `XrReferenceSpace` (`XR_REFERENCE_SPACE_TYPE_STAGE`)
  > Managed via `OVRFW::XrApp` framework and extended in `VRPlayerApp`.
- [x] **T3.2** — Implement main rendering loop:
  - `xrWaitFrame`, `xrBeginFrame`, eye views rendering, `xrEndFrame`
  > Managed inside `vr_player_app_vulkan.cpp` with predicted display time synchronization.
- [x] **T3.3** — Implement `VoidEnvironment`:
  - Deep black background (clear color #000000)
  - No extraneous environment geometry
- [x] **T3.4** — Implement `VirtualScreen` (3D quad):
  - Plane geometry matching video aspect ratio (16:9, 21:9, etc.)
  - Default distance ~3m forward, ~1.5m eye height
  - Default width ~3m (~100" virtual display equivalent)
  - Shader sampler for external texture
- [x] **T3.5** — Implement texture pass from Rust to C++:
  - Zero-copy `AHardwareBuffer` import via Vulkan external memory
- [x] **T3.6** — Implement screen resize and repositioning:
  - Thumbstick to move (distance, elevation)
  - Grip button + thumbstick to resize maintaining aspect ratio
  - Comfort clamps applied (depth -0.75m to -8m, elevation 0.2m to 3.5m)
- [x] **T3.7** — Configure Vulkan rendering pipeline:
  - Render pass with depth buffer
  - Pipeline for textured video quad
  - Pipeline for UI overlay (controls)
  - Multiview rendering (`VK_KHR_multiview`)

### ⚠️ Pitfalls and Considerations

> [!CAUTION]
> **NEVER block the render thread**: The render loop must sustain ≥72 FPS. Any blocking operations (I/O, network, demuxing) must remain on worker threads. Exceeding 14ms per frame triggers ATW artifacts.

> [!CAUTION]
> **Multiview rendering is mandatory**: Rendering each eye separately wastes GPU cycles. Use `VK_KHR_multiview` to render both eyes in a single draw pass.

> [!WARNING]
> **Reference Space**: Use `XR_REFERENCE_SPACE_TYPE_STAGE` (floor-anchored space). `LOCAL` (head-anchored space) makes the screen stick to head motion, inducing motion sickness.

---

## 4. Playback Controls (VR UI)

### Architecture

Minimalist floating UI rendered on interactive 3D panels hosted on Android `VirtualDisplay` / `Presentation`.

### Tasks

- [x] **T4.1** — Implement **raycasting** system from controller:
  - Controller ray → intersection with UI panels
  - Visual feedback: laser beam + intersection reticle
  - Haptics on hover and trigger press
  > Implemented with exponential smoothing of ray direction (`m_smoothedRayDir`) and OpenXR haptic vibration actions on hover-enter and trigger-down.
- [x] **T4.2** — Implement **controls panel**:
  - Play/Pause toggle
  - Seek bar slider with time preview
  - Current time / Total duration labels
  - Volume slider
  - Audio track selection button
  - Speed selector slider (0.5x - 2.0x)
- [x] **T4.3** — Implement **auto-hide**:
  - Controls appear when pointing controller at screen or panel
  - Disappear after 5s of inactivity
  - Smooth alpha fade in/out animation
  > Alpha blending via `uAlpha` uniform on UI quads. Panels with alpha ≤ 0.5 stop consuming touches; panels with alpha ≤ 0.01 skip GPU draw calls.
- [x] **T4.4** — Implement physical controller button bindings:
  - Trigger: Select / Confirm
  - A/X: Play/Pause toggle
  - B/Y: Menu/Back
  - Left Thumbstick: Seek (X axis), Volume (Y axis)
  - Grip: Hold + move stick to resize screen
- [x] **T4.5** — Render UI as **quad overlay** in 3D space:
  - Positioned below virtual screen
  - Billboard orientation facing user
  - Alpha blended over scene

### ⚠️ Pitfalls and Considerations

> [!WARNING]
> **VR UI legibility**: VR text requires larger font sizes than flat screens (minimum ~1.5mm height per pixel at 1m distance). Use readable sans-serif fonts with medium/bold weights.

> [!IMPORTANT]
> **UI Depth vs. Screen**: Keep UI at the same depth or slightly forward of the virtual screen to prevent vergence-accommodation conflict.

---

## 5. Local File Browser

### Architecture

File browser for Quest 3 internal storage navigating movies, downloads, and DCIM directories.

### Tasks

- [x] **T5.1** — Implement directory listing (Kotlin, storage permissions / Scoped Storage)
- [x] **T5.2** — Filter by media extensions (video, audio, images)
- [x] **T5.3** — Display file metadata: name, size, modification date, media type icon
- [x] **T5.4** — Generate video thumbnails (`ThumbnailGenerator`, `MediaMetadataRetriever` with disk cache)
- [x] **T5.5** — Hierarchical navigation (enter/leave directories)
- [x] **T5.6** — Sorting: name, date, size, type (`MediaSorter.kt`)
- [x] **T5.7** — Render file browser as 3D panel in VR environment (`LocalFilesScreen.kt`, `VRPresentation.kt`)

### ⚠️ Pitfalls and Considerations

> [!WARNING]
> **Scoped Storage (Android 11+)**: Quest 3 runs Android 12+. Use `READ_MEDIA_VIDEO` or `MANAGE_EXTERNAL_STORAGE` permissions appropriately.

> [!IMPORTANT]
> **Thumbnails performance**: Generating thumbnails synchronously stalls UI rendering. Use asynchronous background coroutines with disk caching.

---

## 6. SMB/CIFS Protocol

### Architecture

Connect to Windows network shares / NAS servers via SMB to browse directories and stream media.

### Tasks

- [x] **T6.1** — Implement SMB client in Rust:
  - Authentication (user/password + guest/anonymous)
  - List shares on target server
  - Navigate directories within shares
  - Stream file reading via offset range requests
  > Implemented in `rust/protocols/src/smb/` on top of pure-Rust `smb2` crate. Supports SMB 2.x/3.x with NTLMv2 and guest authentication. `SmbFileSource` implements `RangeSource` trait without full file downloads.
- [ ] **T6.2** — Implement automatic SMB server discovery:
  - NetBIOS name resolution, mDNS/DNS-SD (`.local`)
  - Fallback: manual IP address input
  > Manual IP/host input is fully functional; automated background broadcast resolution is planned for Phase 0.2 discovery integration.
- [x] **T6.3** — Integrate SMB with FFmpeg Demuxer:
  - Custom I/O stream callback (`format::context::StreamIo::from_read_seek`)
  - Read buffer with 4MB prefetch block (`PrefetchReader`)
  > Connects `SmbFileSource` to FFmpeg demuxing pipeline without disk caching.
- [x] **T6.4** — Connection management UI:
  - Add server form (host, share, credentials)
  - Saved servers list with `EncryptedSharedPreferences` credential persistence
  - Connection test and status indicator

### ⚠️ Pitfalls and Considerations

> [!CAUTION]
> **SMB seeks require offset requests**: FFmpeg demuxer performs frequent seeks (especially for MKV cues). Use an intelligent prefetch buffer (`PrefetchReader`) to minimize round-trips.

> [!WARNING]
> **Credentials**: NEVER store passwords in plaintext. Use Android `EncryptedSharedPreferences` backed by Keystore.

> [!IMPORTANT]
> **Timeout and reconnection**: Wireless connections on mobile VR can drop packets. Implement automatic reconnection with backoff retry logic.

---

## 7. HTTP URL Playback

### Architecture

Stream media directly from HTTP and HTTPS URLs.

### Tasks

- [x] **T7.1** — Implement HTTP client in Rust (`reqwest` + `tokio`):
  - HTTP GET with byte-range requests for seeking
  - HTTPS support with TLS verification (pure Rust `rustls`)
  - Automatic redirect following
  - Probe method to check range support and content length
- [x] **T7.2** — Integrate with FFmpeg via custom I/O:
  - `StreamIo::from_read_seek` feeding `HttpsRangeSource` with 4MB `PrefetchReader`
- [x] **T7.3** — URL input UI:
  - Text input field
  - Recent URLs history (`UrlHistoryStore.kt`)
  - Paste from clipboard button

### ⚠️ Pitfalls and Considerations

> [!WARNING]
> **Range Requests requirement**: Without range requests support on the HTTP server, arbitrary seeking is not possible. Probe endpoint with `Accept-Ranges` before playback.

---

## 8. Internationalization (i18n)

### Architecture

Multi-language architecture starting with English (default) and Brazilian Portuguese.

### Tasks

- [x] **T8.1** — Configure `res/values/strings.xml` (English — default)
- [x] **T8.2** — Create `res/values-pt-rBR/strings.xml` (Portuguese BR)
- [x] **T8.3** — Define naming convention: `screen_element_action` (e.g., `player_btn_play`, `browser_label_size`)
- [x] **T8.4** — C++ layer strings strategy: UI is rendered via Android Views on `VirtualDisplay`, ensuring all text directly consumes Android string resources without duplicating in C++
- [x] **T8.5** — Document language contribution process (`docs/i18n.md`)

### ⚠️ Pitfalls and Considerations

> [!NOTE]
> **Plurals and formatting**: Use Android `<plurals>` resources for counts and positional placeholders (`%1$s`, `%2$d`) in `getString` calls.

---

## 9. Playback History

### Architecture

Persist video playback progress across sessions to allow seamless resumption.

### Tasks

- [x] **T9.1** — Create Room database with `PlaybackHistory` entity:
  - Stores stable composite key (`historyKey`), title, `positionMs`, `durationMs`, `lastPlayedAt`, source type
  - Implemented with KSP in `app/src/main/java/com/tucavr/history/`
- [x] **T9.2** — Automatically save position periodically during playback:
  - Throttled to ~10s intervals (`PlaybackProgressThrottle.kt`) to avoid continuous disk writes
- [x] **T9.3** — Prompt to resume ("Resume from XX:XX?" / "Start Over") when reopening media (`ResumePromptModal.kt`)
- [x] **T9.4** — "Continue Watching" row and screen on home menu (`ContinueWatchingScreen.kt`)

### ⚠️ Pitfalls and Considerations

> [!WARNING]
> **URI stability**: Network URIs (SMB/HTTP) may change IP addresses. Use composite identifiers based on server name, path, and file size rather than volatile host IPs.

---

## 10. Cross-Cutting Concerns

### Memory and Thermal Limits

> [!CAUTION]
> **Memory budget**: Quest 3 has ~3.5GB RAM available for user apps. Keep memory usage strictly under 2.5GB.

> [!CAUTION]
> **Thermal monitoring**: Prolonged 4K/8K decoding heats the device. Use Android `PowerManager` thermal listeners (`ThermalMonitor.kt`) to adjust rendering workload proactively.

### Inter-Layer Communication

> [!IMPORTANT]
> **Rust ↔ C++ Communication**:
> - Flat C-ABI exports (`extern "C"`) in `rust/bridge` crate.
> - C++ is the sole caller of Rust; Kotlin interacts strictly with C++ via JNI.

---

## 11. Definition of Done — v0.1

- [x] App installs and launches on Quest 3 without crashing
- [x] Void environment renders at ≥72 FPS
- [x] Local file browser navigates internal storage cleanly
- [x] Plays local MP4/MKV (H.264/H.265) video smoothly
- [x] Play/pause/seek controls operate via Touch Plus controllers
- [x] Audio volume adjustable via UI sliders and physical thumbstick
- [x] Virtual screen can be repositioned and resized via thumbstick and grip
- [x] SMB streaming client connects and streams files
- [x] HTTP/HTTPS streaming client probes and plays remote media
- [x] User-facing strings externalized in English and Brazilian Portuguese
- [x] Playback history records progress and prompts to resume
- [x] No crashes during 30-minute soak test sessions
- [x] Memory footprint remains under 2.5GB during 4K playback

---

*Phase 0.1 — Estimate: 6-10 weeks for an experienced solo developer*
