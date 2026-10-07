# Phase 0.3 — Polish & Audio

> **Objective**: Elevate the player experience with immersive 3D virtual environments, full spatial audio (Ambisonics + 5.1/7.1 virtualization), hand tracking, additional codecs (VP9/AV1), advanced subtitles (ASS/PGS), 3D/360° photos, playlists, and Spanish language localization.  
> **Prerequisite**: Phase 0.2 complete and stable.  
> **Expected Outcome**: The user enjoys a rich immersive experience — watching media inside a virtual cinema or with mixed-reality passthrough, navigating via natural hand gestures, listening to binaural spatial audio, and displaying any subtitle format.

---

## 📋 Table of Contents

1. [Virtual Environments: Cinema and Living Room](#1-virtual-environments-cinema-and-living-room)
2. [Passthrough / Mixed Reality](#2-passthrough--mixed-reality)
3. [Spatial Audio — Ambisonics](#3-spatial-audio--ambisonics)
4. [Multichannel Audio — 5.1 and 7.1 Virtualization](#4-multichannel-audio--51-and-71-virtualization)
5. [Hand Tracking](#5-hand-tracking)
6. [VP9 and AV1 Codecs](#6-vp9-and-av1-codecs)
7. [Advanced Subtitles — ASS/SSA and PGS](#7-advanced-subtitles--assssa-and-pgs)
8. [360° and 3D Photos](#8-360-and-3d-photos)
9. [Playlists](#9-playlists)
10. [Internationalization — Spanish](#10-internationalization--spanish)
11. [Cross-Cutting Concerns of Phase 0.3](#11-cross-cutting-concerns-of-phase-03)
12. [Definition of Done — v0.3](#12-definition-of-done--v03)

---

## 1. Virtual Environments: Cinema and Living Room

### Concept

Replace the empty dark void with immersive 3D environments where the video screen anchors naturally into a physical space:

```
Virtual Cinema:                         Virtual Living Room:
┌────────────────────────────────┐     ┌──────────────────────────────┐
│          ┌──────────┐          │     │     🪟         🖼️            │
│          │  LARGE   │          │     │   ┌──────────┐              │
│          │  SCREEN  │          │     │   │ VIRTUAL  │   🛋️         │
│          │          │          │     │   │   TV     │              │
│          └──────────┘          │     │   └──────────┘              │
│  🪑  🪑  🪑  🪑  🪑  🪑  🪑  │     │   📦         🪴             │
│     🎬  Cinema Floor  🎬      │     │      Living Room Floor      │
└────────────────────────────────┘     └──────────────────────────────┘
```

### Tasks

- [x] **T1.1** — Define 3D environment loading pipeline:
  > Implemented glTF 2.0 asset structure and metadata configuration.
- [x] **T1.2** — Implement glTF loader in C++:
  > Implemented via `cgltf` (`native/include/cgltf.h`) and Vulkan vertex/index buffer bindings.
- [x] **T1.3** — Create Cinema environment:
  > Baked lightmaps, IMAX-scale screen positioning (~8m depth, ~5m width), ambient frame lighting (`EnvironmentStore.ENV_CINEMA`).
- [x] **T1.4** — Create Living Room environment:
  > Domestic warm ambient setup with wall-mounted TV proportions (`EnvironmentStore.ENV_LIVING_ROOM`).
- [x] **T1.5** — Implement environment selector in UI (`EnvironmentSelectorModal.kt`, `EnvironmentStore.kt`)
- [x] **T1.6** — Anchor virtual screen according to active environment (`VRActivity.kt:1639`)
- [x] **T1.7** — Vulkan environment rendering pipeline (`environment.vert`, `environment.frag`)

### ⚠️ Pitfalls and Considerations

> [!CAUTION]
> **GPU draw call budget**: Keep environment meshes under 50k triangles and 5-10 draw calls per eye. Baked lighting is mandatory; do not use real-time dynamic lights.

---

## 2. Passthrough / Mixed Reality

### Concept

Blend virtual video playback over real-world surroundings using Quest 3 color passthrough cameras.

### Tasks

- [x] **T2.1** — Enable Meta Passthrough API via OpenXR (`XR_FB_passthrough`)
- [x] **T2.2** — Integrate passthrough layer into Vulkan frame submission
- [x] **T2.3** — Real-time toggle between virtual environments and MR passthrough (`VRControlsPresentation.kt`)
- [x] **T2.4** — Adjust passthrough opacity and edge highlighting (`PassthroughSettingsModal.kt`, `FeatureFlags.kt`)
- [x] **T2.5** — Spatial positioning of the virtual screen in physical room space

### ⚠️ Pitfalls and Considerations

> [!WARNING]
> **Passthrough latency**: Passthrough feed runs on Quest OS level. Keep app render latency low (<14ms) to avoid visual discrepancies between room and floating video quad.

---

## 3. Spatial Audio — Ambisonics

### Concept

Decode spherical audio soundfields (Ambisonics B-format) and binauralize them dynamically as the user turns their head.

### Tasks

- [x] **T3.1** — Detect Ambisonics audio streams in demuxer (FOA channel tags, ACN/SN3D, FuMa)
- [x] **T3.2** — Implement Ambisonics → binaural decoder in Rust (`rust/media-logic/src/spatial_audio/ambisonics.rs`)
- [x] **T3.3** — Integrate HRTF datasets and convolution (`rust/media-logic/src/spatial_audio/hrtf.rs`)
- [x] **T3.4** — Synchronize soundfield rotation with real-time head tracking (`rust/bridge/src/lib.rs:2319`)
- [x] **T3.5** — Clean fallback to stereo when Ambisonics streams are absent

---

## 4. Multichannel Audio — 5.1 and 7.1 Virtualization

### Concept

Virtualize surround sound mixes (5.1 / 7.1) for Quest 3 spatial headphones.

### Tasks

- [x] **T4.1** — Detect surround channel layouts in demuxer (Rust)
- [x] **T4.2** — Implement 5.1/7.1 → binaural virtualization (`rust/media-logic/src/spatial_audio/surround.rs`)
- [x] **T4.3** — Dedicated LFE channel processing via Butterworth 2nd-order lowpass biquad filter (`rust/media-logic/src/spatial_audio/biquad.rs`)
- [x] **T4.4** — Head tracking orientation coupling with surround virtualizer
- [x] **T4.5** — Audio output mode toggle in settings modal (`AudioTrackModal.kt`)

---

## 5. Hand Tracking

### Concept

Allow hands-free navigation using OpenXR natural hand tracking.

### Tasks

- [x] **T5.1** — Enable hand tracking via OpenXR (`XR_EXT_hand_tracking`, `native/include/hand_tracking.h`)
- [x] **T5.2** — Obtain joint transforms per frame
- [x] **T5.3** — Pinch gesture recognition and thumb-index distance thresholds (`test_hand_tracking.cpp`)
- [x] **T5.4** — Index finger raycasting targeting UI panels
- [x] **T5.5** — Action mapping (pinch = select/click)
- [x] **T5.6** — Seamless fallback between Touch Plus controllers and hands
- [x] **T5.7** — Visual hand joints and laser reticle overlay (`vr_player_feedback_overlay.h`)

---

## 6. VP9 and AV1 Codecs

### Tasks

- [x] **T6.1** — Verify hardware decode capabilities on Quest 3 Snapdragon XR2 Gen 2 (`CodecCapabilityManager.kt`)
- [x] **T6.2** — Implement VP9 hardware decoder path via MediaCodec
- [x] **T6.3** — Implement AV1 hardware decoder path via MediaCodec (`rust/core/src/av1.rs`, `rust/media-logic/src/av1.rs`)
- [x] **T6.4** — Preventive handling of unsupported resolutions/profiles
- [x] **T6.5** — Update file detail metadata view to display active codec

---

## 7. Advanced Subtitles — ASS/SSA and PGS

### Tasks

- [x] **T7.1** — Implement ASS/SSA parser in Rust (`rust/media-logic/src/subtitle_ass.rs`)
- [x] **T7.2** — Render styled ASS subtitle events in C++ (`subtitle_layout.h`)
- [x] **T7.3** — Implement PGS bitmap subtitle parser in Rust (`rust/media-logic/src/subtitle_pgs.rs`)
- [x] **T7.4** — Render PGS bitmaps as textures over video plane
- [x] **T7.5** — Embedded subtitle track selector (`SubtitleSelectionModal.kt`)
- [x] **T7.6** — Subtitle fallback priority (embedded vs external files)

---

## 8. 360° and 3D Photos

### Tasks

- [x] **T8.1** — EXIF/XMP metadata detection for 360° and stereoscopic photos (`PhotoFormatDetector.kt`)
- [x] **T8.2** — High-resolution image decoding via `PhotoDecoder.kt` and `stb_image.h`
- [x] **T8.3** — Render 360° photos on spherical mesh
- [x] **T8.4** — Render 3D stereoscopic photos on virtual quad
- [x] **T8.5** — Dedicated photo viewer with navigation controls (`PhotoViewerScreen.kt`)
- [x] **T8.6** — Image thumbnail generation in library browser

---

## 9. Playlists

### Tasks

- [x] **T9.1** — Room database playlist entities (`Playlist.kt`, `PlaylistItem.kt`, `PlaylistDao.kt`)
- [x] **T9.2** — Playlist management UI (`PlaylistsScreen.kt`, `PlaylistDetailScreen.kt`)
- [x] **T9.3** — Sequential playback queue manager (`PlaylistQueueManager.kt`)
- [x] **T9.4** — In-player playlist drawer modal (`PlaylistModal.kt`)
- [x] **T9.5** — Automated "Continue Watching" smart playlist

---

## 10. Internationalization — Spanish

### Tasks

- [x] **T10.1** — Create `res/values-es/strings.xml`
- [x] **T10.2** — Translate all UI strings to Spanish
- [x] **T10.3** — Verify plurals and string formatting (`I18nParityTest`)
- [ ] **T10.4** — Physical verification on Quest 3 set to Spanish locale
- [x] **T10.5** — Document translation contribution guidelines

---

## 11. Cross-Cutting Concerns of Phase 0.3

> [!CAUTION]
> **Audio Thread Latency**: Spatial audio convolution and ITU surround filters must not block the real-time audio callback thread. HRTF convolution is partitioned into bounded sub-buffers.

---

## 12. Definition of Done — v0.3

- [x] Cinema and Living Room virtual environments load and render
- [x] Mixed Reality passthrough blends video into physical room
- [x] Ambisonics FOA spatial audio decodes with head-coupled orientation
- [x] 5.1 and 7.1 multichannel virtualization with subwoofer filtering
- [x] Hand tracking pinch selection controls UI without physical controllers
- [x] VP9 and AV1 hardware decode support
- [x] ASS and PGS advanced subtitle rendering
- [x] 360° and 3D photo gallery viewer
- [x] Playlist creation, editing, and queue progression
- [x] Complete Spanish (es) localization resources verified by CI parity tests

---

*Phase 0.3 — Estimate: 8-12 weeks for an experienced solo developer*
