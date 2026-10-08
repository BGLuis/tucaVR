# Phase 0.4 — Advanced

> **Objective**: Implement advanced media capabilities — 8K content playback with adaptive quality management, DASH streaming, WebDAV protocol, offline downloads, foveated rendering, and advanced projections (Cubemap, EAC).  
> **Prerequisite**: Phase 0.3 complete and stable.  
> **Expected Outcome**: The player supports ultra-high-resolution VR content (8K), adaptive multi-source streaming, and state-of-the-art rendering techniques to maintain performance in demanding scenarios.

---

## 📋 Table of Contents

1. [8K Support with Adaptive Quality](#1-8k-support-with-adaptive-quality)
2. [DASH Streaming](#2-dash-streaming)
3. [WebDAV Protocol](#3-webdav-protocol)
4. [Offline Downloads](#4-offline-downloads)
5. [Foveated Rendering](#5-foveated-rendering)
6. [Advanced Projections — Cubemap and EAC](#6-advanced-projections--cubemap-and-eac)
7. [Cross-Cutting Concerns of Phase 0.4](#7-cross-cutting-concerns-of-phase-04)
8. [Definition of Done — v0.4](#8-definition-of-done--v04)

---

## 1. 8K Support with Adaptive Quality

### Concept

High-fidelity 360° VR content requires 8K resolution (7680×3840 or 7680×7680 stereo) to achieve acceptable pixel density (~20 PPD on Quest 3 displays). Snapdragon XR2 Gen 2 decodes 8K HEVC, but GPU and thermal budgets are strictly constrained.

### Tasks

- [x] **T1.1** — Implement **8K HEVC decoding** via MediaCodec:
  - Dynamically configured `AMEDIAFORMAT_KEY_MAX_INPUT_SIZE` (4MB for 8K)
  - Realtime decode priority (`"priority" = 0` in `rust/core/src/playback.rs`)
- [x] **T1.2** — Implement **Adaptive Quality Manager**:
  - Implemented in `rust/media-logic/src/quality.rs` (`QualityController`) across 5 levels (`Ultra`, `High`, `Medium`, `Low`, `Emergency`)
  - Asymmetric hysteresis: rapid downgrade (≤2 samples) and conservative upgrade (30s stable window)
  - Exposed via C-ABI and consumed in Vulkan render loop (`vr_player_app_vulkan.cpp`)
- [x] **T1.3** — ~~GPU texture downscaling~~ *(Pruned per ADR due to double memory bandwidth overhead)*
- [x] **T1.4** — Implement **performance telemetry metrics** (C++):
  - Vulkan timestamp query tracking (`smoothedGpuTimeMs`), frame pacing lag, and dropped frame counts
- [x] **T1.5** — **Debug HUD**:
  - Real-time technical stats overlay and CSV telemetry exporter (`DebugStatsModal.kt`, `debug_stats.h`)
- [x] **T1.6** — **User adaptive feedback**:
  - Quality badges in `VRControlsPresentation.kt` and non-intrusive thermal/overload notifications

### ⚠️ Pitfalls and Considerations

> [!CAUTION]
> **8K HEVC memory bandwidth**: An uncompressed 8K NV12 frame is ~44MB. At 30fps, video transfers consume ~1.3GB/s of system bus bandwidth. Monitor with OVR Metrics Tool.

> [!CAUTION]
> **Frame rate caps**: Quest 3 hardware decodes 8K HEVC up to 30fps. Content at 8K@60fps must undergo frame doubling or 4K downscale fallback.

---

## 2. DASH Streaming

### Concept

DASH (Dynamic Adaptive Streaming over HTTP, ISO/IEC 23009-1) provides standardized adaptive streaming, complementing HLS.

### Tasks

- [x] **T2.1** — Implement **MPD (XML) parser** in Rust (`rust/protocols/src/dash/manifest.rs`):
  - Parses MPD structures, Periods, AdaptationSets, Representations, SegmentTemplate, and SegmentBase
- [x] **T2.2** — Implement **segment downloader** (`rust/protocols/src/dash/stream.rs`):
  - Asynchronous downloads of initialization segments and sequential media segments with prefetching
  - Byte-range requests for SegmentBase streams
- [x] **T2.3** — Implement **adaptive bitrate selection** (ABR):
  - Conservative bandwidth estimation (P20 percentile) with safe variant selection
- [x] **T2.4** — Integrate DASH streams with **FFmpeg demuxer**:
  - Demuxer detects `.mpd` and `dash://` schemes (`rust/core/src/demuxer.rs:197`)
- [x] **T2.5** — Seek support in DASH:
  - Timestamp to segment calculation and byte-offset seeking
- [x] **T2.6** — UI integration and format probing (`rust/bridge/src/lib.rs:2087`, `FieldValidators.kt`)

---

## 3. WebDAV Protocol

### Architecture

Connect to WebDAV-enabled NAS servers and cloud endpoints via HTTP extensions.

### Tasks

- [x] **T3.1** — Implement **WebDAV client** in Rust (`rust/protocols/src/webdav/`):
  - XML `PROPFIND` directory browsing and multistatus parsing
  - Range requests reading via `read_range()`
- [x] **T3.2** — Integrate WebDAV into playback demuxing via `PrefetchReader` and `RangeSource`
- [x] **T3.3** — mDNS discovery integration (`_webdav._tcp.local`)
- [x] **T3.4** — WebDAV connection UI (`NetworkWebdavScreen.kt`, self-signed certificate toggles)

---

## 4. Offline Downloads

### Architecture

Download remote media files to local Quest 3 storage for offline playback.

### Tasks

- [x] **T4.1** — Implement **Download Manager** in Rust (`rust/protocols/src/download/`):
  - Queue coordination, concurrency limiting, and chunk streaming
- [x] **T4.2** — Implement partial resume for interrupted downloads using `.part` temporary files
- [x] **T4.3** — Download task queue with priority management
- [x] **T4.4** — Room database download persistence (`Download.kt`, `DownloadDao.kt`, `DownloadRepository.kt`)
- [x] **T4.5** — Downloads manager UI with progress bars and transfer statistics (`DownloadsScreen.kt`)
- [x] **T4.6** — Storage space manager and low-space thresholds (`DiskSpaceManager.kt`)
- [x] **T4.7** — Completed downloads auto-indexing in local file library

---

## 5. Foveated Rendering

### Concept

Fixed Foveated Rendering (FFR) reduces fragment resolution towards visual periphery, conserving critical GPU cycles.

### Tasks

- [x] **T5.1** — Enable Fixed Foveated Rendering via Meta OpenXR SDK:
  - Configured with `XR_FB_foveation` on Vulkan swapchains (`vr_player_app_vulkan.cpp`)
- [x] **T5.2** — Configure dynamic foveation levels (`NONE`, `LOW`, `MEDIUM`, `HIGH`)
- [x] **T5.3** — Integrate foveation levels with Adaptive Quality Manager
- [x] **T5.4** — ~~Foveated video decode~~ *(Pruned per ADR due to missing NDK motion-constrained tile support)*
- [x] **T5.5** — Settings menu granular foveation selector (`SettingsScreen.kt`, `FeatureFlags.kt`)

---

## 6. Advanced Projections — Cubemap and EAC

### Concept

Equi-Angular Cubemap (EAC) and Cubemaps distribute pixels with greater angular uniformity than equirectangular projections, eliminating polar distortion.

### Tasks

- [x] **T6.1** — Implement Cubemap projection on sphere geometry (3x2, 6x1, cross layouts)
- [x] **T6.2** — Vulkan Cubemap sampling shaders (`stereo_cubemap.frag`, `stereo_cubemap.vert`)
- [x] **T6.3** — Equi-Angular Cubemap (EAC) transformation math with safe tangent clamping
- [x] **T6.4** — Container projection metadata detection (`rust/media-logic/src/format3d.rs`)
- [x] **T6.5** — Stereoscopic Cubemap (SBS) support
- [x] **T6.6** — Manual projection selector in UI (`ScreenFormatModal.kt`)

---

## 7. Cross-Cutting Concerns of Phase 0.4

> [!CAUTION]
> **Multi-Layer Debugging Complexity**: System spans Kotlin, Rust, C++ (Vulkan), and GLSL. Maintain structured logging and debug overlays.

> [!IMPORTANT]
> **Storage Management**: 8K files rapidly deplete Quest 3 storage. Enforce disk space pre-checks before launching downloads.

---

## 8. Definition of Done — v0.4

- [x] 8K HEVC video decoding pipeline operational
- [x] Adaptive Quality Manager adjusts rendering under stress
- [x] Debug telemetry HUD visualizes frame times and GPU bottlenecks
- [x] DASH streaming with MPD parsing and segment downloading
- [x] WebDAV browsing and range streaming
- [x] Offline download manager with resume support and Room persistence
- [x] Fixed Foveated Rendering enabled on Vulkan swapchains
- [x] Cubemap and EAC advanced projection shaders
- [x] Host unit tests and protocol integration test suites passing

---

*Phase 0.4 — Estimate: 8-12 weeks for an experienced solo developer*
