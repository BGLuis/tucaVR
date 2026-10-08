# Phase 0.6 — Stabilization

> **Objective**: Bridge the gap between what the codebase actually does and what the tracking
> documentation claims it does, validate on physical hardware (Meta Quest 3) everything built
> since Phase 0.3 under feature flags labeled "never validated on real headset", fix the MSAA
> regression introduced by the Vulkan migration, and resolve the two items from Phase 0.5 that
> still have zero lines of code (Multiple Screens, Eye Tracking) — either via minimal implementation
> or formal descope.
> **Prerequisite**: Phase 0.4 complete and stable. Phase 0.5 **partially** implemented —
> Advanced Virtual Environments (§3) and Lighting Adjustment (§4, via Ambient Mode) already exist
> in the codebase; Multiple Screens (§1) and Eye Tracking (§2) do not. This phase takes that gap as
> its starting point rather than reopening what is already done.
> **Expected Outcome**: Every item currently marked as "implemented, never validated on headset"
> gains a recorded validation session — leading to an explicit `defaultEnabled` decision in
> `FeatureFlags.kt`. Canonical documentation reflects the actual state of the repository.
> Phase 1.0 can begin on a tested, rather than assumed, foundation.

---

## 📋 Table of Contents

1. [Documentation Reconciliation](#1-documentation-reconciliation)
2. [Hardware Validation Checklist and Campaign](#2-hardware-validation-checklist-and-campaign)
3. [MSAA Fix in Vulkan](#3-msaa-fix-in-vulkan)
4. [Multiple Virtual Screens — Minimal Scope](#4-multiple-virtual-screens--minimal-scope)
5. [Eye Tracking — Scope Decision](#5-eye-tracking--scope-decision)
6. [Cross-Cutting Concerns of Phase 0.6](#6-cross-cutting-concerns-of-phase-06)
7. [Definition of Done — v0.6](#7-definition-of-done--v06)

---

## 1. Documentation Reconciliation

### Concept

Planning for this phase takes the canonical audit of documentation and code as its baseline to align status tracking and specifications with implementation reality. Planning on top of stale documentation regarding code state is the #1 risk of this phase — and the very reason it was created.

### Tasks

- [x] **T1.1** — Remove references to non-existent legacy reports and maintain technical documentation consolidated directly in `docs/phases/` and `docs/ARCHITECTURE.md`.

- [x] **T1.2** — Fix cross-references, broken links, and legacy repository names throughout documentation.

- [x] **T1.3** — Review implementation status of each technical task in corresponding phase files (`docs/phases/`), marking what is already implemented and identifying what still requires hardware validation.

- [ ] **T1.4** — Add process guideline: every new feature or fix must directly update the corresponding phase checklist and canonical architecture, keeping code and docs continuously synchronized.

### ⚠️ Pitfalls and Considerations

> [!CAUTION]
> **This section must complete before the others truly begin.** Sections 2–5 of this phase were designed from the actual code state (verified via grep, not assumptions) — any new feature started in parallel with T1.1–T1.4 by someone reading outdated docs risks rediscovering work already done.

---

## 2. Hardware Validation Checklist and Campaign

### Concept

`FeatureFlags.kt` maintains three flags with `defaultEnabled = false` and the identical comment — "never validated on real headset so far": `FOVEATED_RENDERING`, `PASSTHROUGH`, `AMBIENT_MODE`. Hand tracking has no flag but is in the same boat: no feature built since Phase 0.3 (environments, passthrough with chroma key/packed alpha, hand tracking, HDR, fisheye/cubemap/EAC, MQSR/upscaling, ambient halo) has a confirmed session on physical Quest 3 — following project convention ("nothing in this set of implementations has been run on Quest 3"). This section formalizes that gap as planned work with acceptance criteria per item, rather than leaving it indefinitely implicit.

```
Validation Campaign Flow:

  Feature/Flag                  Script (T2.1)               Result
      │                             │                         │
      ├── Enable flag on device ───►│                         │
      │                             ├── Session ≥ 30 min ───► │
      │                             │   (or objective         │
      │                             │    functional criterion │
      │                             │    if not comfort)      │
      │                             │                         │
      │  ◄── Passed cleanly ────────┤                         │
      │      → defaultEnabled=true  │                         │
      │                             │                         │
      │  ◄── Reproducible issue ────┤                         │
      │      → file bug,            │                         │
      │        keep flag off        │                         │
```

### Tasks

- [ ] **T2.1** — Write `docs/HARDWARE-VALIDATION-CHECKLIST.md`, one item per feature/axis, with objective acceptance criteria (verifiable and recordable, not "looks good"):
  ```markdown
  ## Foveated Rendering (XR_FB_foveation)
  - [ ] Enables without crash across all 5 levels (Off/Low/Med/High/Auto)
  - [ ] No noticeable visual degradation in foveated region on 4K video
  - [ ] FPS does not drop below 72 during level transitions

  ## Passthrough + Chroma Key + Packed Alpha
  - [ ] Screen remains stable in physical space for ≥ 5 min without noticeable drift
  - [ ] Chroma key removes green/blue background without visible halo on subject edges
  - [ ] Packed Alpha (HereSphere/DeoVR standard) cuts out correctly on at least 1 real clip

  ## Ambient Mode (lighting halo)
  - [ ] Halo follows dominant frame color with imperceptible latency
  - [ ] No measurable FPS drop with halo enabled (compare draw_call_count before/after)

  ## Hand Tracking
  - [ ] Pinch select works on all reachable UI buttons
  - [ ] Controller → hands → controller transition does not hang or duplicate cursor
  - [ ] Comfortable for sessions ≥ 10 min (subjective criterion, record impressions)
  ```

- [ ] **T2.2** — Execute checklist T2.1 on physical Quest 3, one block per session. For each item: record pass/fail; if failed, log backlog item with reproduction steps (use `scripts/capture-screen.sh`, `docs/DEBUGGING.md` §8 for visual evidence).

- [ ] **T2.3** — Upon successful validation of each feature, update `FeatureFlags.kt`: set `defaultEnabled = true` and remove the comment "never validated" (or replace with reference to validation session, e.g. "validated on physical Quest 3, see `docs/HARDWARE-VALIDATION-CHECKLIST.md`").

- [ ] **T2.4** — Close specific comfort open item from `PHASE-0.3-POLISH-AUDIO.md`: session ≥ 30 min testing Ambisonics soundfield rotation + head tracking (the only comfort feature testable before this phase).

### ⚠️ Pitfalls and Considerations

> [!IMPORTANT]
> **This section is the core of the phase, not just one item among others.** This is where the largest unmitigated risk lies: merged code never run on target hardware. Prioritize over Sections 3–5 if time is constrained.

> [!WARNING]
> **Physical session on Quest 3 is the scarcest resource in this phase.** T2.2 cannot be parallelized internally (requires headset), but T1 (documentation) and T3 (MSAA, pure code work) can proceed in parallel without competing for the same resource.

> [!NOTE]
> An issue in T2.2 does not necessarily block the rest of the campaign — features are largely independent (foveated rendering does not depend on passthrough). Continue the checklist and accumulate findings; do not stop the entire phase for a single bug.

---

## 3. MSAA Fix in Vulkan

### Concept

The Vulkan migration lost 4× MSAA that the GLES/OVRFW path previously had. UI/environment pipelines in `vr_player_app_vulkan.cpp` use `VK_SAMPLE_COUNT_1_BIT` across multiple occurrences, with no `VK_SAMPLE_COUNT_2/4/8_BIT` present. This affects edge sharpness across **all** non-video content (2D panels, environment geometry) — visual noise that complicates Section 2 (evaluators cannot easily distinguish "poor environment look due to lack of MSAA" from an "actual environment mesh bug").

### Tasks

- [ ] **T3.1** — Measure the cost of re-enabling `VK_SAMPLE_COUNT_4_BIT` in UI/environment pipelines (`vr_player_app_vulkan.cpp`) via debug HUD (`draw_call_count`, `smoothed_gpu_time_ms`, already instrumented) — before/after in the same scene.

- [ ] **T3.2** — If GPU cost is acceptable on XR2 Gen 2 (does not exceed budget keeping ≥ 72 FPS): re-enable 4× MSAA on these pipelines. **Do not touch** 2D/stereo video pipelines — video is a textured quad via `samplerExternalOES`, already filtered, and intentionally avoids MSAA.

- [ ] **T3.3** — If cost is prohibitive: explicitly document the decision (instead of leaving it as a silent regression) in `docs/VULKAN-MIGRATION-PLAN.md`, using measured numbers from T3.1 as justification.

### ⚠️ Pitfalls and Considerations

> [!WARNING]
> **Re-enabling MSAA without measuring first is a critical pitfall.** XR2 Gen 2 has a tight GPU budget (~57% GPU spec estimate). T3.1 is mandatory before T3.2.

---

## 4. Multiple Virtual Screens — Minimal Scope

### Concept

Partially addresses **RF-2D-009** (priority 🟢 Low). `PHASE-0.5-PREMIUM.md` §1 specifies `MAX_SCREENS = 3` with a full configurator; currently zero lines of code exist (`m_screens`/`MAX_SCREENS` existed only as pseudocode in spec). Implementing the full spec would compete for the scarce hardware session resource. This section proposes a reduced scope that fulfills the requirement without full polish — explicitly leaving remainder for 1.0 or later.

### Tasks

- [ ] **T4.1** — Before duplicating positioning mechanics for a second screen, fix the existing defect: current flat screen (`PHASE-0.1-MVP.md` T3.6) moves without thumbstick confirmation lock and does not persist size/position across sessions. Fixing here avoids duplicating the defect.

- [ ] **T4.2** — Implement `MAX_SCREENS = 2` (not 3): second screen reuses thumbstick move/resize mechanics fixed in T4.1, without glTF positioning ghost configurator from full 0.5 spec.
  ```cpp
  // Minimal scope: 2 screens, no auto-layout or positioning ghost.
  struct VirtualScreen {
      glm::vec3 position;
      glm::vec3 scale;
      PlaybackSource source;   // each screen has independent source
  };
  static constexpr int MAX_SCREENS = 2;
  std::vector<VirtualScreen> m_screens;
  ```

- [ ] **T4.3** — Minimal UI to add/remove second screen (reuse existing modal pattern from `ScreenFormatModal.kt`/`EnvironmentSelectorModal.kt`, without a dedicated new tab).

- [ ] **T4.4** — Explicitly document in `PHASE-0.5-PREMIUM.md` §1 that full scope (auto layout, ghost configurator, `MAX_SCREENS = 3`) is deferred — not left implicit.

### ⚠️ Pitfalls and Considerations

> [!IMPORTANT]
> **T4.1 before T4.2, always.** Phase 0.5 T1.4 previously planned to reuse positioning mechanics as-is, which would duplicate defects. This is the opportunity to fix before duplicating.

---

## 5. Eye Tracking — Scope Decision

### Concept

Addresses **RF-UI-006** (priority 🟢 Low). Zero lines of code exist (`XR_EXT_eye_gaze_interaction`/`EyeGaze` exist only as pseudocode in `PHASE-0.5-PREMIUM.md` §2). Unlike Section 4, the recommendation here is **explicit descope**, given calibration overhead + gaze-foveated UI relative to a 🟢 Low priority feature, while higher priority features are still awaiting physical validation (Section 2).

### Tasks

- [ ] **T5.1** — Product decision: descope Eye Tracking from Phase 0.5/1.0, or accept cost and plan as dedicated post-1.0 phase. If descoped: update `REQUIREMENTS.md` (move RF-UI-006 to excluded items, along with DRM/Social/Cloud storage) and `PHASE-1.0-RELEASE.md` (prerequisite "Phase 0.5 complete" no longer strictly requires Eye Tracking).

### ⚠️ Pitfalls and Considerations

> [!IMPORTANT]
> **This decision must be formally recorded.** Without recording in T5.1, future readers (human or AI) will assume Eye Tracking remains a blocker for 1.0.

---

## 6. Cross-Cutting Concerns of Phase 0.6

> [!CAUTION]
> **Do not begin items from `PHASE-1.0-RELEASE.md` in parallel with this phase.** Onboarding, unified settings panel, store assets, and API docs presuppose a validated baseline. A VR bug found in Section 2 could invalidate completed onboarding screens or store screenshots.

> [!WARNING]
> **CI does not cover this phase automatically.** Native/C++ build is not run on CI (requires proprietary Meta SDK) — rendering, OpenXR, or MSAA regressions are not detectable without manual execution on headset. Section 2 and Section 3 (T3.1) are inherently manual.

> [!NOTE]
> This phase does not strictly increment SemVer yet (strict SemVer starts at 1.0, see `PHASE-1.0-RELEASE.md` §7). `version.properties` may remain on `0.4.x`/`0.5.x` during this phase — phase numbering is organizational.

---

## 7. Definition of Done — v0.6

### Documentation
- [x] Canonical technical documentation (`docs/phases/`, `docs/ARCHITECTURE.md`, `docs/REQUIREMENTS.md`) reconciled with `develop` branch code
- [x] No broken references to non-existent reports or files
- [x] Implementation status of each technical task reviewed against current code (T1.3)

### Hardware Validation
- [ ] `docs/HARDWARE-VALIDATION-CHECKLIST.md` exists and every item has a recorded outcome (passed / issue with repro)
- [ ] `FOVEATED_RENDERING`, `PASSTHROUGH`, `AMBIENT_MODE`: each has `defaultEnabled` decided based on real session
- [ ] Hand tracking validated over session ≥ 10 min without cursor lockup
- [ ] Ambisonics + head tracking validated over session ≥ 30 min

### Rendering
- [ ] MSAA measured (T3.1) and decision recorded (re-enabled or justified as infeasible)
- [ ] No FPS drop below 72 attributable to MSAA change, if re-enabled

### Multiple Screens
- [ ] Single screen positioning/persistence defect fixed (T4.1) before second screen exists
- [ ] 2 simultaneous screens with independent sources, without noticeable framerate degradation

### Eye Tracking
- [ ] Scope decision recorded in `REQUIREMENTS.md` and `PHASE-1.0-RELEASE.md` (implement post-1.0 or formally descope)

### General
- [ ] No regressions in test suites from phases 0.1–0.5
- [ ] `cargo test -p protocols -p media-logic` and `./gradlew testDebugUnitTest` green

---

*Phase 0.6 — Estimate: 2.5–4 weeks for an experienced solo developer, of which 6–10 dev-days strictly depend on physical Quest 3 sessions.*
*This phase adds no new end-user product features — the product remains identical in feature surface, but with a validated foundation and dependable documentation.*
*Dependency: none technically (can begin immediately); blocks the formal start of Phase 1.0.*
