# Phase 0.5 — Premium

> **Objective**: Implement premium capabilities — multiple simultaneous virtual screens in 3D space, gaze-based interface control (Eye Tracking), advanced thematic environments (Cosmic Space), and dynamic lighting reactive to playing video content.  
> **Prerequisite**: Phase 0.4 complete and stable.  
> **Expected Outcome**: The VR player offers an advanced multitasking canvas with intuitive gaze selection, rich media-reactive environments, and side-by-side video playback.

---

## 📋 Table of Contents

1. [Multiple Virtual Screens](#1-multiple-virtual-screens)
2. [Eye Tracking — Gaze Selection](#2-eye-tracking--gaze-selection)
3. [Advanced Virtual Environments](#3-advanced-virtual-environments)
4. [Environment Lighting and Color Adjustment](#4-environment-lighting-and-color-adjustment)
5. [Cross-Cutting Concerns of Phase 0.5](#5-cross-cutting-concerns-of-phase-05)
6. [Definition of Done — v0.5](#6-definition-of-done--v05)

---

## 1. Multiple Virtual Screens

### Concept

Fulfills requirement **RF-2D-009** (Multiple simultaneous virtual screens). The user can spawn 2-3 floating quad displays in VR space, each streaming independent media (e.g., picture-in-picture, side-by-side comparison).

```
Multiple Screens in VR Space:

    PIP Layout (Picture-in-Picture):         Side-by-Side Layout:

    ┌─────────────────────┐                 ┌──────────┐  ┌──────────┐
    │                     │  ┌──────┐       │          │  │          │
    │     Main Screen     │  │ PIP  │       │ Screen 1 │  │ Screen 2 │
    │    (active focus)   │  │ (25%)│       │  (50%)   │  │  (50%)   │
    │                     │  └──────┘       │          │  │          │
    └─────────────────────┘                 └──────────┘  └──────────┘
    🔊 Audio 100%          🔇 Muted         🔊 100%       🔈 20%
```

### Tasks

- [ ] **T1.1** — Refactor to multi-session architecture in Rust:
  - Migrate single-instance `PlaybackController` to multi-session manager
  - Independent demuxer, decoder, sync manager, and audio output per active session
- [ ] **T1.2** — Implement multiple video quads in C++ (Vulkan):
  - Multi-texture descriptor sets and independent transform matrices per quad
- [ ] **T1.3** — Implement audio mixing across sessions in Rust:
  - Focused screen at 100% volume; unfocused secondary screens attenuated to ~15%
- [ ] **T1.4** — Implement independent spatial interaction per screen in C++:
  - Controller raycast intersection detection per active screen quad
- [ ] **T1.5** — Implement per-screen controls UI in Kotlin
- [ ] **T1.6** — Layout presets (PIP, Side-by-Side, Curved Panorama)
- [ ] **T1.7** — Expose multi-session C-ABI bridge functions (Rust → C++)

---

## 2. Eye Tracking — Gaze Selection

### Concept

Fulfills **RF-UI-006** (Gaze-based interaction). Enables navigation through OpenXR eye tracking extensions (`XR_EXT_eye_gaze_interaction`).

### Tasks

- [ ] **T2.1** — Initialize OpenXR eye tracking extension in C++ (`XR_EXT_eye_gaze_interaction`)
- [ ] **T2.2** — Query eye gaze pose per frame and perform UI raycasting
- [ ] **T2.3** — Implement Dwell Time Activation with circular progress feedback (1.0s to 2.5s dwell threshold)
- [ ] **T2.4** — Implement Foveated UI animations: visual highlight and subtle scale growth on gazed elements
- [ ] **T2.5** — Automatic input mode arbitration (Controller > Hand Tracking > Eye Gaze)
- [ ] **T2.6** — Focus switching across multiple screens via gaze
- [ ] **T2.7** — Runtime eye tracking permission request in Kotlin (`com.oculus.permission.EYE_TRACKING`)

---

## 3. Advanced Virtual Environments

### Concept

Fulfills **RF-ENV-004** (Cosmic space environment). Floating observation deck surrounded by starfields, nebulae, and ambient soundscapes.

### Tasks

- [ ] **T3.1** — Create 3D model of spatial platform (~5k triangles, baked lightmap)
- [ ] **T3.2** — Implement high-quality skybox in C++ (partial — equirectangular implemented, cubemap pending; see #37):
  > Implemented as an equirectangular 2D texture mapped onto a sphere (`native/shaders/vulkan/skybox.frag:3`, `sampler2D equirectMap`), not a native cubemap texture. Tracked in #37.
- [ ] **T3.3** — Implement lightweight starfield particle system (~200 instanced billboards)
- [ ] **T3.4** — Implement ambient audio loop manager (partial — loop and ducking functional, switch fade and config.ini volume pending; see #52):
  > Basic looping and ducking implemented in `app/src/main/java/com/tucavr/AmbientAudioManager.kt`. Linear volume ramp on stop/switch (to prevent clicks) and reading `ambient_volume` from `config.ini` are tracked in #52.
- [ ] **T3.5** — Environment registry and switching infrastructure (partial — hardcoded registry exists, dynamic config.ini registry pending; see #51):
  > Implemented statically in `EnvironmentStore.kt` and `EnvironmentSelectorModal.kt`. Refactoring to a unified `EnvironmentRegistry` reading `assets/environments/*/config.ini` is tracked in #51.

---

## 4. Environment Lighting and Color Adjustment

### Concept

Fulfills **RF-ENV-007** (Environment lighting and color temperature adjustment). Adds dynamic reactive "Screen Glow" bias lighting and Night Mode blue light reduction.

### Tasks

- [x] **T4.1** — Implement **Screen Glow** (C++/GLSL):
  > Downsample FBO extraction of dominant frame color with temporal smoothing (`vr_player_ambient.h`, `ambient.vert`, `ambient.frag`).
- [x] **T4.2** — Update environment shaders to receive screen glow attenuation (`environment.frag`)
- [x] **T4.3** — Implement environment brightness slider (0% to 100% in `FeatureFlags.kt:327`, `SettingsScreen.kt`)
- [x] **T4.4** — Implement color temperature / Night Mode:
  > Planckian locus approximation in GLSL shaders reducing blue light below ~3000K (`FeatureFlags.Flag.NIGHT_MODE`).
- [x] **T4.5** — Lighting adjustment UI controls in settings panel (`SettingsScreen.kt:561`, verified by `EnvironmentLightingTest.kt`)

---

## 5. Cross-Cutting Concerns of Phase 0.5

> [!CAUTION]
> **Worst-Case Hardware Budget**: Running multiple decoding pipelines simultaneously with HDR skyboxes and dynamic screen glow heavily taxes mobile GPU and thermals. The `AdaptiveQualityManager` acts as arbiter, degrading lighting effects before dropping video frames.

---

## 6. Definition of Done — v0.5

### Multiple Screens (Pending Phase 0.6 Minimal Scope)
- [ ] User can open 2 simultaneous virtual screens streaming independent media
- [ ] Primary screen audio at 100%, background screen attenuated to ~15%
- [ ] Controller can move and resize each screen independently

### Eye Tracking (Candidate for Phase 0.6 Descope)
- [ ] UI navigation operable via gaze dwell time
- [ ] Anti-jitter filtering prevents false activations during saccades

### Environments
- [ ] Space cubemap skybox renders smoothly without motion judder (partial: equirectangular implemented, see #37)
- [ ] Ambient audio loop plays seamlessly with automatic playback ducking (partial: switch fade ramp pending, see #52)
- [ ] Dedicated 3D platform mesh integration

### Lighting
- [x] Screen glow dynamically projects video color onto environment geometry
- [x] Temporal smoothing eliminates flashing in fast-cut scenes
- [x] Environment brightness control (0% to 100%)
- [x] Night mode reduces blue light emission
- [x] All settings persist across app reboots

---

*Phase 0.5 — Estimate: 8-12 weeks for an experienced solo developer*
