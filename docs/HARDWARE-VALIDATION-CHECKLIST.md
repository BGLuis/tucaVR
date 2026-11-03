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
   - Enable via **Settings > Advanced > Technical Statistics** (the `FeatureFlags.Flag.DEBUG_STATS_PANEL` toggle; there is no ADB broadcast for it).
   - Real-time readouts exported by `native/src/debug_stats.h`:
     - `smoothed_gpu_time_ms` (target: the `QualityController` budget, `frame_interval × 0.85`: $\le 11.8\text{ ms}$ at 72 Hz, $\le 9.4\text{ ms}$ at 90 Hz)
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

## Headless session notes (2026-11-01)

Results below marked *headless* come from adb automation with the headset on a table: `debug.oculus.guardian_pause=1`, `vrpowermanager.prox_close`, preferences written with `run-as`, captures through `CAPTURE_FRAME`. They cover what telemetry and swapchain captures can prove. They do not replace a wearer: head motion, comfort, perceived quality and the composited passthrough are out of reach.

- A/B numbers use the Void environment with `debug.oculus.gpuLevel=4` and `cpuLevel=4`. With free clocks or a 3D environment, the same configuration ranged from 1.5 to 10 ms between launches, because of clock changes, `QualityController` scale and refresh changes, and the table pose.
- `output_fps` is the video cadence (30 fps on these clips), so the FPS criteria below were checked against `smoothed_fps` and the compositor's `VrApi FPS=` line.
- The Quest sleeps after about 10 minutes without a wearer, even with `prox_close` sent every 45 s, which caps headless sessions.
- Vulkan validation layers were not enabled in this session.

---

## Block 1: Foveated Rendering (`XR_FB_foveation`) — Sub-issue #41

Validates fixed foveated rendering via the Vulkan pipeline (`vr_player_app_vulkan.cpp::ApplyFoveation`) to decide whether `FeatureFlags.Flag.FOVEATED_RENDERING` should default to `true`.

### Session Metadata
- **Date:** 2026-11-01 (headless, nobody wearing the headset)
- **Build Commit:** `af128b6` (debug APK)
- **Quest Firmware:** `52641960031600520` (Horizon OS API 160 build)
- **Tester / Device Serial:** Claude Code over adb / `2G97C5ZH9201LH`

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
*Headless, Void, Sphere360 with the 4K clip, pinned clocks, n = 3 launches per level:*

- `Off`  GPU Time: 1.24 ms | FPS: 90
- `Low`  GPU Time: 1.24 ms | FPS: 90
- `Med`  GPU Time: 1.24 ms | FPS: 90
- `High` GPU Time: 1.24 ms | FPS: 90
- `Auto` GPU Time: 1.24 ms | FPS: 90 (the runtime reports level 1)
- **Finding:** the runtime reports each level (`VrApi Fov=`), but nothing changes: compositor `App` stays at 1.56–1.62 ms and `GPU%` at 0.23–0.24 on every level, and a High capture matches an Off capture (SSIM 0.9997). `CreateEyeSwapchains` chains no `XrSwapchainCreateInfoFoveationFB`, and there is no fragment density map in the render pass, so foveation has no effect on the Vulkan path.
- **Not run:** changing the level during playback (prefs are read at launch), Vulkan validation, foveal quality by eye.
- **Status:** [ ] PASSED | [x] FAILED (Issue: #232)
- **Decided Flag Default (`FOVEATED_RENDERING`):** [ ] `true` | [x] `false` until that is fixed

---

## Block 2: Passthrough, Chroma Key & Packed Alpha — Sub-issue #42

Validates `XR_FB_passthrough`, background keying, and packed transparency to decide defaults for `FeatureFlags.Flag.PASSTHROUGH` and `FeatureFlags.Flag.CHROMA_KEY`.

### Session Metadata
- **Date:** 2026-11-01 (headless, nobody wearing the headset)
- **Build Commit:** `af128b6` (debug APK)
- **Quest Firmware:** `52641960031600520` (Horizon OS API 160 build)
- **Tester / Device Serial:** Claude Code over adb / `2G97C5ZH9201LH`

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
- **Anchoring Drift after 5 min:** not run (needs a wearer) cm / deg
- **Chroma Key Artifacts:** [x] None detected | [ ] Fringing observed. *Headless, synthetic green and blue clips, one capture each: 0 key-tinted pixels among about 42,000 lit pixels. The composite over the real passthrough feed was not seen.*
- **Packed Alpha Cutout:** [ ] Clean transparency | [ ] Blend defect. *Not validated: no real DeoVR/HereSphere clip is available. A synthetic clip only shows the mode runs without a crash.*
- **Passthrough:** *headless, session starts and holds 90 FPS (`smoothed_fps` minimum 89.7), no crash.*
- **Status:** [ ] PASSED | [ ] FAILED (Issue: #_____)
- **Decided Flag Defaults:**
  - `PASSTHROUGH`: [ ] `true` | [ ] `false`
  - `CHROMA_KEY`:  [ ] `true` | [ ] `false`

---

## Block 3: Ambisonics & Head Tracking (Comfort Session) — Sub-issue #43

Validates spatial soundfield stability and binaural rendering over extended sessions, closing Task **T2.4** and the comfort requirement from `PHASE-0.3-POLISH-AUDIO.md`.

### Session Metadata
- **Date:** 2026-11-01 (headless, nobody wearing the headset)
- **Build Commit:** `af128b6` (debug APK)
- **Quest Firmware:** `52641960031600520` (Horizon OS API 160 build)
- **Tester / Device Serial:** Claude Code over adb / `2G97C5ZH9201LH`

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
- **Session Duration:** not met. *Headless, synthetic FOA MKV, n = 2 runs: telemetry covers 595 s each, then the headset sleeps. 0 underruns after startup, 0 freezes, A/V drift at most 1.3 ms, 90 FPS. The stream's detection as Ambisonics was not confirmed by any log or telemetry field.*
- **Rapid Turn Audio Artifacts:** [ ] 0 detected | [ ] Audible clicks/zippers (needs a wearer)
- **Drift after 30 min:** not run (needs a wearer)
- **Status:** [ ] PASSED | [ ] FAILED (Issue: #_____)

---

## Block 4: Ambient Mode (Reactive Lighting Halo) — Sub-issue #44

Validates the bias lighting halo in the Void environment (the halo gating in `vr_player_input_vulkan.h`, which draws it only when `get_ambient_mode_enabled()` is set) to evaluate GPU overhead and determine the default for `FeatureFlags.Flag.AMBIENT_MODE`.

### Session Metadata
- **Date:** 2026-11-01 (headless, nobody wearing the headset)
- **Build Commit:** `af128b6` (debug APK)
- **Quest Firmware:** `52641960031600520` (Horizon OS API 160 build)
- **Tester / Device Serial:** Claude Code over adb / `2G97C5ZH9201LH`

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
   - The halo color converges to the new scene color within the implemented smoothing time (`kAmbientColorSmoothSeconds` = 0.6 s, sampled every `kAmbientDecimationFrames` = 3rd frame, `vr_player_ambient.h`) following scene color changes, without noticeable flicker.
2. **Draw Call & GPU Overhead:**
   - Draw call count increase is $\le 2$ draw calls per eye with halo enabled.
   - `smoothed_gpu_time_ms` delta between halo ON and halo OFF is $< 0.8\text{ ms}$.
   - Framerate remains $\ge 72\text{ FPS}$ with zero introduced frame freezes.

### Results & Metrics
*Headless, Void, `ambient_steps.mp4` (1080p red/blue/white, 2 s each), pinned clocks, n = 3 per state:*

- **Halo OFF:** `smoothed_gpu_time_ms`: 0.11 ms | `draw_call_count`: 2 | FPS: 90
- **Halo ON:**  `smoothed_gpu_time_ms`: 0.40 ms (0.40, 0.40, 0.24) | `draw_call_count`: 4 | FPS: 90
- **Delta:** GPU: +0.29 ms | Draw calls: +2 in total, +1 per eye
- **Color tracking:** 12 captures show the halo following each scene color. The capture interval (about 1.75 s) is too coarse to measure the 0.6 s convergence. Flicker needs a wearer.
- **Scope note:** the code also draws the halo in `cinema` and `living_room` (`vr_player_input_vulkan.h:785-790`), not only Void. Those were not measured.
- **Status:** cost criteria PASSED. Colour latency and flicker were not verified (Issue: #233).
- **Decided Flag Default (`AMBIENT_MODE`):** [ ] `true` | [x] `false` until #233 has a result
- **Fallback Glow Mode (`getScreenGlowIntensityMode`):** [ ] Strong (2) | [x] Off (0), unchanged while the flag is off

---

## Block 5: Hand Tracking (`XR_EXT_hand_tracking`) — Sub-issue #45

Validates optical hand tracking, pinch gestures, and controller-hand transitions across UI panels without input deadlocks.

### Session Metadata
- **Date:** 2026-11-01 (headless, nobody wearing the headset)
- **Build Commit:** `af128b6` (debug APK)
- **Quest Firmware:** `52641960031600520` (Horizon OS API 160 build)
- **Tester / Device Serial:** Claude Code over adb / `2G97C5ZH9201LH`

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
*Not validated: every item needs real hands. Headless evidence only: `XR_EXT_hand_tracking` is detected and both hand trackers are created, with no crash when `HAND_TRACKING` is on.*

- **Pinch Select Accuracy:** _____ / _____ buttons triggered correctly
- **Controller $\leftrightarrow$ Hands Transitions (3 cycles):** [ ] Clean | [ ] Cursor duplicated/frozen
- **Session Duration:** _____ minutes (must be $\ge 10$)
- **Ergonomics / Comfort Score (1–5):** _____ / 5
- **Status:** [ ] PASSED | [ ] FAILED (Issue: #_____)

---

## Block 6: 3D Projections, HDR, MQSR & Space Environment — Sub-issue #46

Validates stereoscopic spherical projections, HDR tonemapping, Meta Quest Super Resolution (MQSR), and the 3D Space environment.

### Session Metadata
- **Date:** 2026-11-01 (headless, nobody wearing the headset)
- **Build Commit:** `af128b6` (debug APK)
- **Quest Firmware:** `52641960031600520` (Horizon OS API 160 build)
- **Tester / Device Serial:** Claude Code over adb / `2G97C5ZH9201LH`

### Debug Switch & Setup
- **Projection ADB Overrides (`docs/DEBUGGING.md` §2):**
  ```bash
  # Sphere180 (mode 6), Vr180SBS (9), Cubemap/EAC (10-14), Fisheye190 (15-16)
  adb shell am broadcast -a com.tucavr.debug.SET_SCREEN_MODE --ei screen_mode 6
  adb shell am broadcast -a com.tucavr.debug.SET_ENVIRONMENT --es environment_id space
  ```
- **Test Assets:**
  - 1 HDR10 (BT.2020 PQ) 4K clip.
  - 1 stereoscopic 180° (VR180 SBS) clip and, if available, 1 Fisheye190 clip.
  - 1 Equirectangular / Cubemap / EAC clip.

### Objective Pass Criteria
1. **HDR10 Playback:**
   - Plays without colorspace clipping, crushing, or pipeline crashes; luminance levels map appropriately without blown highlights.
2. **Projection Geometry:**
   - VR180, Fisheye190, Cubemap, and EAC render straight lines without distortion or seam tears at texture boundaries.
3. **MQSR Upscaling:**
   - Toggling MQSR produces measurable edge contrast enhancement on text and video without dropping framerate below 72 FPS.
4. **Space Environment Performance:**
   - `space/model.glb` + skybox maintains $\ge 72\text{ FPS}$ sustained over $n \ge 3$ runs both idle and during 4K video playback ($\texttt{smoothed\_gpu\_time\_ms} \le 11.8\text{ ms}$ at 72 Hz).

### Results & Metrics
*Headless. Clips and captures are synthetic, generated with ffmpeg; forced modes use `SET_SCREEN_MODE`:*

- **HDR10 Render Status:** [ ] Clean color grading | [x] Washed out / crashed. *No crash, and the tonemap path runs. A PQ step ramp (10-bit codes 189–939) captures as 93, 149, 199, 233, 248, 253, 254, so the top steps are nearly merged. See the gamma finding below.*
- **SDR gamma finding:** a BT.709 gray ramp captures at the sRGB encoding of its video values (Y 47 captures as 101, Y 233 as 253). The video shaders write gamma-coded values to an `R8G8B8A8_SRGB` swapchain with no linearization, so midtones are lifted. This needs an eye check against another player.
- **Projection Seams / Geometry:** [ ] Verified clean | [x] Distortion detected. *Against an equirect reference, Cubemap3x2 (10), Cubemap6x1 (11) and EAC3x2 (12) match on the side faces but show the up and down faces rotated 180°, with a visible seam (SSIM 0.84–0.90 there, 0.94–0.97 on the sides). Sphere180 (6) and Fisheye190 (15) match in front (SSIM 0.995). In the SBS modes 7, 9, 13, 14 and 16 each eye samples its own half. Clips came from ffmpeg `v360`; confirm with a real YouTube EAC file.*
- **MQSR:** *modes 0–3 hold 90 FPS. Telemetry reports the mode (`QUAL`, `PERF`, `AUTO`, `mqsr_enabled=1`), and `PERF` renders at scale 0.8. No edge gain was shown: MQSR runs in the compositor, outside the capture, and SGSR1 did not change the Sobel measure on this content (0.957 vs 0.954). Needs an eye check on real footage.*
- **Space Environment (Idle):** *no session means no CSV, so ms is the compositor `App` value.*
  - Run 1: 90 FPS (0.81 ms) | Run 2: 90 FPS (0.80 ms) | Run 3: 90 FPS (0.81 ms). *Lowest 1 s sample: 80, 90, 89.*
- **Space Environment (4K Playback):** *free clocks, table pose, about 45 s each.*
  - Run 1: 90 FPS (0.94 ms) | Run 2: 90 FPS (0.94 ms) | Run 3: 90 FPS (1.20 ms). *Lowest 1 s sample: 80, 90, 90.*
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
   - If 4× MSAA maintains $\ge 72\text{ FPS}$ with $\texttt{smoothed\_gpu\_time\_ms} \le 11.8\text{ ms}$ (72 Hz budget): merge 4× MSAA.
   - If cost exceeds budget: record exact measured timings in `docs/VULKAN-MIGRATION-PLAN.md` with rationale.

### Results & Metrics
- **Cinema Baseline (1×):** Median GPU: _____ ms | Draw calls: _____ | FPS: _____
- **Cinema Test (4×):**     Median GPU: _____ ms | Draw calls: _____ | FPS: _____
- **Space Baseline (1×):**  Median GPU: _____ ms | Draw calls: _____ | FPS: _____
- **Space Test (4×):**      Median GPU: _____ ms | Draw calls: _____ | FPS: _____
- **Vulkan Validation (VUID-06853):** [ ] Clean (0 errors) | [ ] Layer violation
- **Status:** [ ] PASSED | [ ] FAILED (Issue: #_____)
- **Formal Decision:** [ ] Enable 4× MSAA (T3.2) | [ ] Descope & Document in VULKAN-MIGRATION-PLAN.md (T3.3)
