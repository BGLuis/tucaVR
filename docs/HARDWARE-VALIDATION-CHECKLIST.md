# Hardware Validation Checklist (Meta Quest 3)

This checklist tracks physical hardware validation on the **Meta Quest 3** (Qualcomm Snapdragon XR2 Gen 2) for features implemented under feature flags or marked as *"never validated on real headset"* across Phases 0.3–0.5.

It serves as the execution standard for **Phase 0.6 §2 (T2.1)** and tracks the sub-issues **#41 through #47** under parent Epic **#34**.

---

## 🛠️ Global Execution & Diagnostic Tooling

Before and during any validation session on physical hardware, use the following tools:

1. **Frame Capture (Visual Validation):**
   ```bash
   ./scripts/capture-screen.sh
   # Or via manual broadcast:
   adb shell am broadcast -a com.tucavr.debug.CAPTURE_FRAME --es capture_path /sdcard/vr-frame-capture.ppm
   ```
   Extracts both eye framebuffers (`.left.ppm` and `.right.ppm`) directly from the OpenXR swapchain into `captures/` on the host PC (see `docs/DEBUGGING.md` §8).

2. **Performance HUD & Telemetry ("Stats for Nerds"):**
   - Enable via **Settings > Advanced > Technical Statistics** or ADB toggle `DEBUG_STATS_PANEL`.
   - Real-time readouts exported by `native/src/debug_stats.h`:
     - `smoothed_gpu_time_ms` (target: $\le 11.5\text{ ms}$ at 72 Hz, $\le 9.5\text{ ms}$ at 90 Hz)
     - `draw_call_count`
     - `video_fps`, `output_fps`, `decoded_fps`
     - `stutter_count`, `freeze_count`
   - CSV telemetry recording: enable via `FeatureFlags.Flag.DEBUG_STATS_EXPORT` (see `docs/DEBUGGING.md` §6).

3. **Automated Diagnostic Bundle:**
   ```bash
   ./scripts/collect-debug.sh --bugreport
   ```
   Captures device manifest, logcat, telemetry CSVs, and crash reports (`docs/DEBUGGING.md` §7).

---

## Block 1: Foveated Rendering (`XR_FB_foveation`) — Sub-issue #41

Validates fixed foveated rendering via the Vulkan pipeline (`vr_player_app_vulkan.cpp::ApplyFoveation`) to decide whether `FeatureFlags.FOVEATED_RENDERING` should default to `true`.

### Session Metadata
- **Date:** 
- **Build Commit:** 
- **Quest Firmware:** 
- **Tester / Device Serial:** 

### Debug Switch & Setup
- **Feature Flag:** Set `FOVEATED_RENDERING` in Settings or `FeatureFlags.kt`.
- **Test Asset:** 4K video clip ($3840 \times 2160$ at 60 fps or 72 fps).
- **Logcat Filter:**
  ```bash
  adb logcat -s VRPlayerAppVK:I VRPlayerAppVK:W VRPlayerAppVK:E
  ```

### Objective Pass Criteria
1. **Level Switching Without Crash:**
   - App transitions through all 5 foveation levels (`Off`, `Low`, `Medium`, `High`, `Auto`) without crashing or producing Vulkan validation layer warnings/errors.
2. **Framerate Stability:**
   - Framerate (`output_fps`) remains $\ge 72\text{ FPS}$ continuously during level changes.
3. **Foveal Region Quality:**
   - Zero visible pixelation, tearing, or rendering artifacts in the central foveal cone ($< 15^\circ$ eccentricity) on 4K playback.
4. **GPU Timing:**
   - `smoothed_gpu_time_ms` at `High` foveation is strictly less than or equal to `smoothed_gpu_time_ms` with foveation `Off`.

### Results & Metrics
- `Off`  GPU Time: _____ ms | FPS: _____
- `Low`  GPU Time: _____ ms | FPS: _____
- `Med`  GPU Time: _____ ms | GPU Time: _____ ms
- `High` GPU Time: _____ ms | FPS: _____
- `Auto` GPU Time: _____ ms | FPS: _____
- **Status:** [ ] PASSED | [ ] FAILED (Issue: #_____)
- **Decided Flag Default (`FOVEATED_RENDERING`):** [ ] `true` | [ ] `false`

---

## Block 2: Passthrough, Chroma Key & Packed Alpha — Sub-issue #42

Validates `XR_FB_passthrough`, background keying, and packed transparency to decide defaults for `FeatureFlags.PASSTHROUGH` and `FeatureFlags.CHROMA_KEY`.

### Session Metadata
- **Date:** 
- **Build Commit:** 
- **Quest Firmware:** 
- **Tester / Device Serial:** 

### Debug Switch & Setup
- **Passthrough Toggle:** Settings UI or ADB environment switch:
  ```bash
  adb shell am broadcast -a com.tucavr.debug.SET_ENVIRONMENT --es environment_id passthrough
  ```
- **Test Clips:**
  - Standard 2D/3D clip for spatial anchoring.
  - Video with solid green (#00FF00) and blue (#0000FF) backdrop for Chroma Key.
  - HereSphere / DeoVR packed alpha clip (side-by-side RGB + Alpha mask).

### Objective Pass Criteria
1. **Spatial Anchoring Stability:**
   - The virtual screen quad stays anchored in physical space for $\ge 5\text{ minutes}$ with angular drift $< 1.0^\circ$ and positional drift $< 2.0\text{ cm}$ relative to room geometry.
2. **Chroma Key Boundary Precision:**
   - Chroma keying eliminates green/blue backgrounds with zero visible colored fringe or halo around high-contrast edges (verified by `scripts/capture-screen.sh`).
3. **Packed Alpha Cutout:**
   - Packed alpha clip separates transparency cleanly; transparent pixels blend into real-world passthrough feed without rendering black or white borders.

### Results & Metrics
- **Anchoring Drift after 5 min:** _____ cm / _____ deg
- **Chroma Key Artifacts:** [ ] None detected | [ ] Fringing observed
- **Packed Alpha Cutout:** [ ] Clean transparency | [ ] Blend defect
- **Status:** [ ] PASSED | [ ] FAILED (Issue: #_____)
- **Decided Flag Defaults:**
  - `PASSTHROUGH`: [ ] `true` | [ ] `false`
  - `CHROMA_KEY`:  [ ] `true` | [ ] `false`

---

## Block 3: Ambisonics & Head Tracking (Comfort Session) — Sub-issue #43

Validates spatial soundfield stability and binaural rendering over extended sessions, closing Task **T2.4** and the comfort requirement from `PHASE-0.3-POLISH-AUDIO.md`.

### Session Metadata
- **Date:** 
- **Build Commit:** 
- **Quest Firmware:** 
- **Tester / Device Serial:** 

### Debug Switch & Setup
- **Audio Mode:** `SPATIAL_AUDIO` and `SPATIAL_HEAD_TRACKING` enabled (`defaultEnabled = true`).
- **Test Asset:** First-Order Ambisonics (FOA / AmbiX format, 4 channels ACN/SN3D) with localized directional audio sources.
- **Duration:** $\ge 30\text{ minutes}$ continuous session.

### Objective Pass Criteria
1. **Soundfield Spatial Fixation:**
   - Directional audio cues remain anchored to absolute world coordinates when turning head $\pm 90^\circ$ yaw, $\pm 45^\circ$ pitch, and $\pm 30^\circ$ roll.
2. **Rotation Smoothness & Artifacts:**
   - Zero zipper noise, clicks, phase distortion, or buffer underruns during rapid angular head movements ($> 90^\circ/\text{s}$).
3. **Zero Long-Term Drift:**
   - After $\ge 30\text{ minutes}$ of continuous playback, sound sources maintain angular alignment within $\pm 5^\circ$ of their initial orientation.

### Results & Metrics
- **Session Duration:** _____ minutes (must be $\ge 30$)
- **Rapid Turn Audio Artifacts:** [ ] 0 detected | [ ] Audible clicks/zippers
- **Drift after 30 min:** _____ degrees
- **Status:** [ ] PASSED | [ ] FAILED (Issue: #_____)

---

## Block 4: Ambient Mode (Reactive Lighting Halo) — Sub-issue #44

Validates the bias lighting halo in the Void environment (`vr_player_app_vulkan.cpp::shouldDrawAmbientHalo`) to evaluate GPU overhead and determine the default for `FeatureFlags.AMBIENT_MODE`.

### Session Metadata
- **Date:** 
- **Build Commit:** 
- **Quest Firmware:** 
- **Tester / Device Serial:** 

### Debug Switch & Setup
- **Environment:** Void:
  ```bash
  adb shell am broadcast -a com.tucavr.debug.SET_ENVIRONMENT --es environment_id void
  ```
- **Feature Flag:** `AMBIENT_MODE` enabled.
- **Screen Glow Settings:** Test with `screen_glow_intensity = Strong (2)` and `Off (0)`.
- **Test Asset:** Clip with alternating high-contrast scene changes (e.g., solid red $\to$ solid blue $\to$ bright white).

### Objective Pass Criteria
1. **Dynamic Color Latency:**
   - Halo color updates within $\le 2$ presentation frames ($< 30\text{ ms}$) following scene color changes without noticeable flicker.
2. **Draw Call & GPU Overhead:**
   - Draw call count increase is $\le 2$ draw calls per eye with halo enabled.
   - `smoothed_gpu_time_ms` delta between halo ON and halo OFF is $< 0.8\text{ ms}$.
   - Framerate remains $\ge 72\text{ FPS}$ with zero introduced frame freezes.

### Results & Metrics
- **Halo OFF:** `smoothed_gpu_time_ms`: _____ ms | `draw_call_count`: _____ | FPS: _____
- **Halo ON:**  `smoothed_gpu_time_ms`: _____ ms | `draw_call_count`: _____ | FPS: _____
- **Delta:** GPU: +_____ ms | Draw calls: +_____
- **Status:** [ ] PASSED | [ ] FAILED (Issue: #_____)
- **Decided Flag Default (`AMBIENT_MODE`):** [ ] `true` | [ ] `false`
- **Fallback Glow Mode (`getScreenGlowIntensityMode`):** [ ] Strong (2) | [ ] Off (0)

---

## Block 5: Hand Tracking (`XR_EXT_hand_tracking`) — Sub-issue #45

Validates optical hand tracking, pinch gestures, and controller-hand transitions across UI panels without input deadlocks.

### Session Metadata
- **Date:** 
- **Build Commit:** 
- **Quest Firmware:** 
- **Tester / Device Serial:** 

### Debug Switch & Setup
- **Input Mode:** Natural hands active (place Touch Plus controllers on flat surface).
- **Session Duration:** $\ge 10\text{ minutes}$ continuous UI interaction.

### Objective Pass Criteria
1. **Button Pinch Reliability:**
   - Pinch gesture (index finger + thumb touch) triggers `ACTION_DOWN` / `ACTION_UP` reliably on 100% of reachable UI buttons (Play/Pause, Seekbar, Settings, Audio/Subtitle menus).
2. **Controller $\leftrightarrow$ Hands Seamless Switching:**
   - Performing 3 consecutive switches (Pick up controller $\to$ Put down controller $\to$ Hands active $\to$ Pick up controller) results in:
     - Zero pointer freeze / unresponsive cursor.
     - Zero duplicate cursors or residual ghost rays in the 3D scene.
3. **Session Stability & Comfort:**
   - No tracking dropouts or gesture hysteresis locking during a $\ge 10\text{ minute}$ session. Record ergonomic notes.

### Results & Metrics
- **Pinch Select Accuracy:** _____ / _____ buttons triggered correctly
- **Controller $\leftrightarrow$ Hands Transitions (3 cycles):** [ ] Clean | [ ] Cursor duplicated/frozen
- **Session Duration:** _____ minutes (must be $\ge 10$)
- **Ergonomics / Comfort Score (1–5):** _____ / 5
- **Status:** [ ] PASSED | [ ] FAILED (Issue: #_____)

---

## Block 6: 3D Projections, HDR, MQSR & Space Environment — Sub-issue #46

Validates stereoscopic spherical projections, HDR tonemapping, Meta Quest Super Resolution (MQSR), and the 3D Space environment.

### Session Metadata
- **Date:** 
- **Build Commit:** 
- **Quest Firmware:** 
- **Tester / Device Serial:** 

### Debug Switch & Setup
- **Projection ADB Overrides (`docs/DEBUGGING.md` §2):**
  ```bash
  # Fisheye 180 (mode 6 or 9), Cubemap, EAC
  adb shell am broadcast -a com.tucavr.debug.SET_SCREEN_MODE --ei mode 6
  adb shell am broadcast -a com.tucavr.debug.SET_ENVIRONMENT --es environment_id space
  ```
- **Test Assets:**
  - 1 HDR10 (BT.2020 PQ) 4K clip.
  - 1 Fisheye 180 stereoscopic clip.
  - 1 Equirectangular / Cubemap / EAC clip.

### Objective Pass Criteria
1. **HDR10 Playback:**
   - Plays without colorspace clipping, crushing, or pipeline crashes; luminance levels map appropriately without blown highlights.
2. **Projection Geometry:**
   - Fisheye 180, Cubemap, and EAC render straight lines without distortion or seam tears at texture boundaries.
3. **MQSR Upscaling:**
   - Toggling MQSR produces measurable edge contrast enhancement on text and video without dropping framerate below 72 FPS.
4. **Space Environment Performance:**
   - `space/model.glb` + skybox maintains $\ge 72\text{ FPS}$ sustained over $n \ge 3$ runs both idle and during 4K video playback (`smoothed_gpu_time_ms} \le 11.5\text{ ms}$).

### Results & Metrics
- **HDR10 Render Status:** [ ] Clean color grading | [ ] Washed out / crashed
- **Projection Seams / Geometry:** [ ] Verified clean | [ ] Distortion detected
- **Space Environment (Idle):**
  - Run 1: _____ FPS (_____ ms) | Run 2: _____ FPS (_____ ms) | Run 3: _____ FPS (_____ ms)
- **Space Environment (4K Playback):**
  - Run 1: _____ FPS (_____ ms) | Run 2: _____ FPS (_____ ms) | Run 3: _____ FPS (_____ ms)
- **Status:** [ ] PASSED | [ ] FAILED (Issue: #_____)

---

## Block 7: Vulkan MSAA Measurement & Decision — Sub-issue #47

Executes the measurement protocol for 4× MSAA on geometry pipelines (`vr_player_app_vulkan.cpp`) to satisfy Phase 0.6 Tasks **T3.1–T3.3**.

### Session Metadata
- **Date:** 
- **Build Commit:** 
- **Quest Firmware:** 
- **Tester / Device Serial:** 

### Debug Switch & Setup
- **Validation Layers (Check VUID-06853):**
  ```bash
  ./gradlew assembleDebug -PenableVulkanValidation=true
  adb logcat -s VkValidation:* VRPlayerAppVK:*
  ```
- **Scenes:** Cinema Environment and Space Environment.

### Objective Pass Criteria & Measurement Protocol
1. **Baseline Measurement (T3.1):**
   - Record `smoothed_gpu_time_ms`, `draw_call_count`, and `output_fps` across $n \ge 3$ runs for both Cinema and Space environments in current 1× sample configuration.
   - Capture edge quality using `scripts/capture-screen.sh`.
2. **Prototype 4× Evaluation:**
   - Test prototype with 4× MSAA transient attachments and `pResolveAttachments`.
   - Check logcat: zero `VUID-VkGraphicsPipelineCreateInfo-multisampledRenderToSingleSampled-06853` errors.
3. **Decision Threshold (T3.2 / T3.3):**
   - If 4× MSAA maintains $\ge 72\text{ FPS}$ with `smoothed_gpu_time_ms} \le 11.5\text{ ms}$: merge 4× MSAA.
   - If cost exceeds budget: record exact measured timings in `docs/VULKAN-MIGRATION-PLAN.md` with rationale.

### Results & Metrics
- **Cinema Baseline (1×):** Median GPU: _____ ms | Draw calls: _____ | FPS: _____
- **Cinema Test (4×):**     Median GPU: _____ ms | Draw calls: _____ | FPS: _____
- **Space Baseline (1×):**  Median GPU: _____ ms | Draw calls: _____ | FPS: _____
- **Space Test (4×):**      Median GPU: _____ ms | Draw calls: _____ | FPS: _____
- **Vulkan Validation (VUID-06853):** [ ] Clean (0 errors) | [ ] Layer violation
- **Status:** [ ] PASSED | [ ] FAILED (Issue: #_____)
- **Formal Decision:** [ ] Enable 4× MSAA (T3.2) | [ ] Descope & Document in VULKAN-MIGRATION-PLAN.md (T3.3)
