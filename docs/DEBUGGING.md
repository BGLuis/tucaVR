# Video Debugging Tools

> Context: debugging the SBS/OU/360/180 modes used to require playing a real
> file in that format, reading unfiltered logcat, and guessing which internal
> state (`ScreenMode`, `stereoLayout`, `polar180`, `swapEyes`) the app believed
> was active — and there was no performance data (FPS, slow frames, freezes) to
> tell whether a problem was one of rendering or of performance. This document
> records the tools added for that: transition and performance logging, a way to
> force any mode over adb without needing the real file in that format, a
> technical-stats modal showing the current state plus FPS/stutter/freeze, and
> optional Vulkan validation layer support.
> The interactive tools (the debug broadcasts in section 2) only exist in
> debuggable builds. The logging (section 1 and the FPS/stutter/freeze part of
> section 3) always runs, on both paths, regardless of build type — it is just
> `__android_log_print`, with the same cost as any log already in the project.
> The log messages quoted below are emitted verbatim by the native code, which
> still writes some of them in Portuguese; match on them as written.

## 1. Transition and Performance Logging (always on, both paths)

Every `ScreenMode` change, every "no video frame" ↔ "active frame" transition,
and every abnormally slow frame (stutter/freeze, see section 3) logs one line,
instead of total silence or per-frame spam:

```bash
# Vulkan (default path)
adb logcat -s VRPlayerAppVK:I VRPlayerAppVK:W VRPlayerAppVK:E

# GLES (-PvrplayerGraphicsApi=GLES)
adb logcat -s VRPlayerApp:I VRPlayerApp:W VRPlayerApp:E
```

Look for:

- `ScreenMode -> <name> (stereoLayout=... polar180=... swapEyes=...)` — every
  time the mode changes (3D button in the UI, `nativeSetScreenMode`, or the
  debug broadcast below).
- `Video: comecou a produzir frames (1o AHardwareBuffer recebido)` ("started
  producing frames") and `Video: sem frame disponivel, mantendo ultimo frame na
  tela ate a proxima sessao produzir um novo` ("no frame available, keeping the
  last frame on screen until the next session produces a new one") on Vulkan
  (the GLES path logs `video comecou a produzir frames` / `video parou de
  produzir frames`) — tells you whether the problem is decode/network (it never
  reaches "started") or rendering (it does, but nothing shows up on screen).
- `Video: stutter — frame levou X.Xms` (WARN) / `Video: FREEZE detectado —
  frame levou Xms` (ERROR) — render loop. `Video: sem frame novo ha Xms
  (decode/rede travado)` (WARN) — video stalled for a long time. `Video:
  judder — frame ficou Xms na tela (media recente Yms)` (WARN) — irregular
  video cadence even without stopping. The last two are about the video itself
  (decode/network), unlike the render-loop ones. See section 3 for the
  thresholds and the difference between them.
- `GetOrImportVideoFrame falhou` (Vulkan only) — failure to import the decoded
  `AHardwareBuffer` as a `VkImage` (see Stage 3 in
  `docs/VULKAN-MIGRATION-PLAN.md`).

## 2. Forcing a ScreenMode via adb (debuggable builds only)

Useful for testing every mode (SBS, OU, 360, 180, Vr180SBS, cubemaps, fisheye)
**on the same already-loaded file**, without needing a real file recorded in
that specific format:

```bash
adb shell am broadcast -a com.tucavr.debug.SET_SCREEN_MODE --ei screen_mode 6
adb shell am broadcast -a com.tucavr.debug.CYCLE_SCREEN_MODE
```

The `mode` index must match `enum class ScreenMode` in
`native/include/screen_mode.h`. The four places that encode it (the
`SCREEN_MODE` comments and `SCREEN_MODE_COUNT` in `rust/bridge/src/lib.rs`,
`screen_mode.h`, `ScreenFormatCatalog.kt` and
`Format3D::to_screen_mode_index` in `rust/media-logic/src/format3d.rs`) change
together. `CYCLE_SCREEN_MODE` advances to `(current + 1) % SCREEN_MODE_COUNT`
(`cycle_3d_mode` in `rust/bridge/src/lib.rs`).

| mode | Name | | mode | Name |
|---|---|---|---|---|
| 0 | Flat2D | | 9 | Vr180SBS |
| 1 | SBS | | 10 | Cubemap3x2 |
| 2 | SBSHalf | | 11 | Cubemap6x1 |
| 3 | OU | | 12 | EAC3x2 |
| 4 | OUHalf | | 13 | Cubemap3x2SBS |
| 5 | Sphere360 | | 14 | EAC3x2SBS |
| 6 | Sphere180 | | 15 | Fisheye190 |
| 7 | Sphere360SBS | | 16 | Fisheye190SBS |
| 8 | Sphere360OU | | | |

There is also an older mechanism to **launch** the app already in a specific
mode with a file (soak test, see `scripts/soak-test.sh`):

```bash
adb shell am start -n com.tucavr/.VRActivity \
  -e video_path /sdcard/Movies/test_8k_180.mp4 --ei screen_mode 9
```

Difference: the latter only fires ~3s after cold start (a heuristic delay, see
`AUTO_PLAY_DELAY_MS`) and only once per process; the broadcast above works at
any time, with the player already running.

The receiver is only registered if `ApplicationInfo.FLAG_DEBUGGABLE` is set
(true for Gradle's `debug` build by default) — it never exists in a release
APK, so it is not an attack surface in that case.

### Other debug broadcasts (debuggable builds)

| Action | Extras | Effect |
|---|---|---|
| `com.tucavr.debug.SET_THERMAL_STATUS` | `--ei status <int>` | Simulates a thermal status in `ThermalMonitor`. |
| `com.tucavr.debug.SET_CAMERA_OFFSET` | `--ef yaw`, `pitch`, `x`, `y`, `z` | Offsets the debug camera (`nativeSetDebugCameraOffset`). |
| `com.tucavr.debug.PLAY_SFTP` | `--es host`, `--ei port` (default 22), `--es path`, `--es user`, `--es password` | Starts SFTP playback without navigating the UI or wearing the headset (reproduces network-stall scenarios in an automated, repeatable way). |

### Switching the Virtual Environment via ADB (debuggable builds)

Lets you switch the 3D environment (and re-anchor the virtual screen) at any time:

```bash
# cinema, living_room, space, void or passthrough
adb shell am broadcast -a com.tucavr.debug.SET_ENVIRONMENT --es environment_id cinema
adb shell am broadcast -a com.tucavr.debug.SET_ENVIRONMENT --es environment_id living_room
adb shell am broadcast -a com.tucavr.debug.SET_ENVIRONMENT --es environment_id space
adb shell am broadcast -a com.tucavr.debug.SET_ENVIRONMENT --es environment_id void
```

### Viewing and Mirroring on the PC Screen

The project includes three utilities to view the app and inspect the 3D environments directly on the computer:

1. **Desktop/Web 3D Viewer (100% Offline / No Headset):**
   Opens an interactive WebGL (Three.js) viewer in the PC browser to inspect the 3D environments, walk around in first person (WASD + mouse), and test the anchored virtual screen and ambient audio:

   ```bash
   ./scripts/preview-environment.py
   ```

2. **Real-Time Mirroring over USB (with the Quest 3):**
   Uses `scrcpy` with parameters tuned for the Quest 3 (right-eye crop, 90 FPS and ~20ms low latency):

   ```bash
   ./scripts/quest-mirror.sh
   # Options: --right-eye (default), --left-eye, --full (both eyes)
   ```

3. **Instant Vulkan Frame Capture via ADB:**
   Triggers a framebuffer capture straight from the Quest 3's graphics engine, transfers it to the PC's `captures/` folder as PNG and opens it in the image viewer:

   ```bash
   ./scripts/capture-screen.sh
   # Or via a manual broadcast:
   adb shell am broadcast -a com.tucavr.debug.CAPTURE_FRAME --es capture_path /sdcard/vr-frame-capture.ppm
   ```

## 3. Technical Stats Modal ("Stats for Nerds")

The app has a complete real-time diagnostics modal ("Stats for Nerds"), reachable through the stats button in the player's controls bar when enabled in **Settings > Advanced > Technical Stats**. This section documents the unified diagnostics architecture (phases F0–F8): a single field contract, per-group freshness, stage attribution, 5 charts and an event log.

### Activation and Zero Overhead

- **Settings**: the `DEBUG_STATS_PANEL` and `DEBUG_STATS_EXPORT` toggles persist the user's preferences and immediately notify the native engine through JNI (`nativeSetDebugStatsEnabled`, driven by `DebugTelemetryExporter.shouldStreamNativeStats`). Both are Settings toggles (`FeatureFlags.Flag`), not adb broadcasts, and are available in release builds too.
- **Zero Overhead**: when both toggles are disabled, the native engine (C++/Rust) early-exits on an atomic flag (`g_debugStatsEnabled`), eliminating periodic JNI calls, string allocations and metric collection in the render loop.

### Single Field Contract (F0)

Each wire field has exactly **2** definition points — no more:

1. **`native/src/debug_stats.h`** (`struct DebugStats` + `SerializeDebugStats`) — owns the shape of the TSV wire format (`key\tvalue`), populated in `native/src/vr_player_input_vulkan.h` (Vulkan path; the GLES path in `vr_player_app.cpp` gets no new fields — see "What not to do" below).
2. **`app/.../debug/DebugStats.kt`** (`DebugStatsParser`) — the only TSV parser; `NativeDebugStats` is the structured representation used by `DebugTelemetryExporter` (CSV) and `DebugStatsModal` (UI). There is no longer a second `when(key)` reimplementing the parsing in the exporter.

To add a new field: one entry in `DebugStats` (native), one line in `SerializeDebugStats`, one field in `NativeDebugStats` + a `when` branch in `DebugStatsParser`, and (if it goes to the CSV) a position in `CSV_HEADER` — bump `SCHEMA_VERSION` whenever the CSV shape changes (D-05).

### Per-Group Freshness (D-04)

Four fields (`video_stats_age_ms`, `network_stats_age_ms`, `audio_stats_age_ms`, `render_stats_age_ms`) say how long ago the corresponding group last received genuinely new data — `render` is always 0 (computed every frame in the render loop itself). In the modal, a value older than ~2 samples (~200ms at 10Hz) appears dimmed with the age next to it, instead of looking like a current reading when it is not.

### Available Sections and Metrics

1. **Video & Rendering** (`DebugStatsModal.kt`, section `debug_stats_section_video_render`):
   - **Bottleneck Stage** (`bottleneck` — F5, first line of the panel): `NONE`/`NETWORK`/`PRESENTATION`, derived from `frame_gap_ms` + `video_q_depth` (see [`BottleneckStageAnalyzer`](../app/src/main/java/com/tucavr/debug/BottleneckStageAnalyzer.kt)) — answers "where did it stall" without reconstructing the reading by hand.
   - **Resolution / Codec**, **Video Decoder**, **Screen / Stereo Mode**, **Graphics Backend**: static session metadata.
   - **Framerate / Display** and **Dropped Frames**: `decoded_fps` / `output_fps` / `dropped_fps`, with the age of the video group.
   - **Stutter / Freeze**: cumulative counters of the **render loop** (>20ms / >250ms) — not to be confused with a video stall (D-02, see Event Log below), which measures the presentation of video frames, not the loop.
   - **Video Jitter**, **Resolution Scale / Foveation**.
   - **GPU Time**, **Adaptive Quality**, **Upscaling**, **Draw Calls / Triangles** (F5 G5): 7 fields that were already collected and traveled on the wire, but never appeared in the modal before F5.

2. **Audio & Sync**: codec/channels, **A/V Drift** (`av_drift_ms`, now an EMA — see F4 below), spatial audio, track and subtitles.

3. **Network & Buffer**: source/protocol, delivered network rate (`net_mbs`, MiB/s), last-block link throughput (`net_block_mbs`, MiB/s), buffer queue, frames pending display, per-block latency, blocks fetched/discarded, last seek latency.

4. **System & Hardware**: thermal status, battery, app version.

5. **Charts** (F5 — `debug_stats_section_charts`, the project's first `View` with a custom `onDraw`, see [`VoidChart.kt`](../app/src/main/java/com/tucavr/designsystem/VoidChart.kt)):

   | # | Chart | Answers | Source |
   |---|---|---|---|
   | G1 | Pipeline stage (60s) | Colored bands per `bottleneck_stage`, a rolling history accumulated in `DebugStatsModal` from the ~10Hz samples — no new wire field. | Green=healthy, blue=`NETWORK`, red=`PRESENTATION`. |
   | G2 | Frame time histogram | 8 cumulative buckets (`hist_bucket_0..7`: on time, +1 vsync, +2 vsyncs at the display refresh rate, then `<50`, `<100`, `<150`, `<250`, `>=250` ms) accumulated every frame in the render loop and reset per video — captures the distribution without transporting 90 samples/s. | `native/include/frame_time_histogram.h` (`FrameTimeHistogramBucket`). |
   | G3 | Buffer health (s) / Network (MiB/s) | `buffered_ahead_sec` reported by the playback controller overlaid on `net_mbs` (plus `net_block_mbs` in `scripts/plot-session.py`) — shows seconds of video buffered ahead of the master clock even during stalls; `video_q_depth / video_fps` kept as offline CSV fallback. | Wire (`buffered_ahead_sec`, `net_mbs`, `net_block_mbs` from `rust/bridge`). |
   | G4 | A/V Drift (ms) | `av_drift_ms` with a ±40ms reference band. | Wire (EMA, see F4). |
   | G5 | GPU time vs. budget (ms) | `smoothed_gpu_time_ms` against the same budget formula as the `QualityController` (`frame_interval_ms × 0.85`, `rust/media-logic/src/quality.rs`). | Wire + formula mirrored on the client. |

   Reproducible offline (no headset) from any collected CSV: `scripts/plot-session.py` generates the same 5 charts on the PC (see section 6).

### Event Log (F6)

Besides the CSV's 1Hz series (which cannot see a 20ms stutter — that is 90 frames per sample), each session writes `session-<id>-events.log` alongside the CSV, with one line per episode: `STUTTER`, `FREEZE`, `VIDEO_STALL_ENDED` (D-02 — duration approximated by the peak `frame_gap_ms` of the sample before the stall ended) and `QUALITY_TRANSITION` (previous/new level + reason), each with the `bottleneck_stage` and a snapshot of the pipeline (`frame_ms`, `gpu_ms`, `queue`, `net_mbs`, `drift_ms`, `quality`, `scale`) at the moment of detection. Pure, testable logic lives in [`DebugEventLog.kt`](../app/src/main/java/com/tucavr/debug/DebugEventLog.kt) (`detectEvents`/`formatEventLine`); file writing is done by `DebugEventLogWriter`, driven from the same `updateDebugHud` that already feeds the CSV.

### Recommended Triage Flow

1. Check the **Bottleneck Stage** first — if `NETWORK`/`PRESENTATION` shows up often, it already points at the right side of the pipeline.
2. Go to the matching chart: G1 to see when/for how long each stage dominated; G2 if the suspicion is a frame-time tail (not the average); G3/G4/G5 for the classic network/sync/GPU signals.
3. Consult the event log (`session-<id>-events.log`) for the exact pipeline snapshot at the start/end of each episode — this is what lets you reconstruct "what was happening when it stalled" without manually grepping logcat.

### What Not to Do

- Do not instrument the GLES path (`vr_player_app.cpp`) — the backend is frozen until 1.0, already fakes `renderResolutionScale`/`displayRefreshRate` and has no GPU timing.
- Do not port the panel to C++ — it is already Kotlin end to end, and the quad compositing cost is the same.
- Do not feed the `XR_META_performance_metrics` counters (section 10) back into the `QualityController` — the Khronos specification explicitly forbids it; they are diagnostics, never control input.

Implementation: `native/src/vr_player_input_vulkan.h` calls
`VRActivity.updateDebugHud(text)` through JNI at the same throttle that already
exists for `updateMediaProgress`; the text is built entirely on the native side
(no resource/i18n string — it is technical diagnostics, not production UI).
When called, `updateDebugHud` feeds the CSV exporter and the event-log writer
if a session is active; it only forwards the text to `modalPresentation.updateDebugStats`
when `VRActivity.isDebugStatsEnabled` (the Settings toggle) is on. The modal is
gated by that feature flag, not by `isDebuggable`.

## 4. Vulkan Validation Layers (optional via build flag)

`CreateVulkanInstanceAndDevice` (`vr_player_app_vulkan.cpp`) checks at runtime
(`vkEnumerateInstanceLayerProperties`) whether `VK_LAYER_KHRONOS_validation` is
available and, if it is, enables the layer + `VK_EXT_debug_utils` with a
callback that logs to logcat (`VkValidation: ...`, tag `VRPlayerAppVK`,
WARN/ERROR levels enabled by default).

**Gating by build flag:**
The validation layer is disabled by default both in the C++ code and in the APK packaging. To enable it:

1. Add the layer's `.so` at `app/src/main/jniLibs/arm64-v8a/libVkLayer_khronos_validation.so`.
2. Build passing the Gradle flag `-PenableVulkanValidation=true`:

   ```bash
   ./gradlew assembleDebug -PenableVulkanValidation=true
   ```

Without this flag, Gradle excludes the `.so` from the APK through `packaging.jniLibs.excludes` and CMake does not define `ENABLE_VK_VALIDATION_LAYERS`, ensuring that default and production builds suffer no CPU/GPU overhead or interception on the hot path of command and fence submission.

**Obtaining the `.so` file (not versioned):**
`app/src/main/jniLibs/` is in `.gitignore`. To get the layer's arm64-v8a binary again:

- Download an official release of [`KhronosGroup/Vulkan-ValidationLayers`](https://github.com/KhronosGroup/Vulkan-ValidationLayers/releases) (Android asset, **arm64-v8a** variant) and copy it to `app/src/main/jniLibs/arm64-v8a/libVkLayer_khronos_validation.so`.
- When building with `-PenableVulkanValidation=true`, the first frame with the layer active will show in logcat: `Vulkan: VK_LAYER_KHRONOS_validation encontrada, habilitando` ("found, enabling"). In regular builds without the flag, the log will record: `Vulkan: Validation layers desabilitadas por configuracao de build` ("disabled by build configuration").

## 5. Session IDs and Multi-Layer Traceability (N1 and N6)

To trace the complete lifecycle of each playback across the project's 3 languages (Kotlin $\rightarrow$ C++ $\rightarrow$ Rust), every playback start (`playFile`, `playUrl`, `playSmb`, etc.) generates a pseudo-random session identifier of 8 hexadecimal characters (e.g. `a1b2c3d4`).

The Session ID is immediately propagated through JNI to C++ and through the C-ABI to the Rust layer. All system logs then include the prefix `[s:<session_id>]`:

- **Kotlin (`VRPlayer_App`):** logs through `VRLog` (e.g. `[s:a1b2c3d4] Iniciando sessao de reproducao...`, "starting playback session").
- **C++ (`VRPlayerApp` / `VRPlayerAppVK`):** `LOGI`, `LOGW`, `LOGE` macros.
- **Rust (`VRPlayer_Rust`):** `log_info!`, `log_warn!`, `log_error!`, `log_debug!` macros.

### Error History and Crash Reporter

- **Rust Error Ring Buffer:** the Rust bridge keeps a non-destructive circular buffer with room for the last 16 errors (`ErrorRingBuffer`), preserving the timestamp and session ID even after being consumed by the UI Toast.
- **Crash Reporter:** uncaught exceptions in Kotlin trigger the `UncaughtExceptionHandler`, writing the stack trace and session metadata to `/sdcard/Android/data/com.tucavr/files/debug/crash-<sessionId>-<timestamp>.txt`.

## 6. Telemetry Time-Series Export to CSV (N2)

The app supports periodically recording performance metrics to CSV files for in-depth diagnostics without needing a real-time USB connection.

### Activation

- Go to **Settings > Advanced > Export Debug Telemetry (CSV)** or enable it via `FeatureFlags.Flag.DEBUG_STATS_EXPORT`.
- Files are written to `/sdcard/Android/data/com.tucavr/files/debug/session-<sessionId>-<timestamp>.csv`.

### CSV File Format

The 1st column is always `schema_version` (D-05) — incremented whenever the format changes incompatibly; see `DebugTelemetryExporter.SCHEMA_VERSION` for the version history and what each one added. Full header (current version):

```csv
schema_version,timestamp_ms,session_id,elapsed_s,backend,screen_mode,stereo_layout,polar_180,swap_eyes,video_status,frame_gap_ms,video_fps,decoded_fps,output_fps,dropped_fps,jitter_ms,net_mbs,video_q_depth,video_presentation_pending,buffered_ahead_sec,seek_ms,smoothed_fps,frame_ms,gpu_time_ms,smoothed_gpu_time_ms,upscaling_mode,upscaling_sharpness,mqsr_enabled,stutter_count,freeze_count,thermal_level,scale,refresh_rate,av_drift_ms,net_last_fetch_ms,net_block_mbs,net_blocks_fetched,net_blocks_discarded,foveation,spatial_audio,head_tracking,speed,volume,audio_track,audio_track_count,sub_track,sub_offset_ms,quality_level,quality_reason,draw_call_count,triangle_count,video_stall_count,video_stats_age_ms,network_stats_age_ms,audio_stats_age_ms,render_stats_age_ms,network_fetch_failures,network_sequential_streak,network_throttled,audio_queue_depth,decode_error_count,demux_corrupt_packet_count,audio_underrun_count,load_phase_demux_open_ms,load_phase_decoder_ready_ms,load_phase_audio_ready_ms,perf_metrics_valid_mask,perf_app_cpu_frametime_ms,perf_app_gpu_frametime_ms,perf_motion_to_photon_latency_ms,perf_compositor_cpu_frametime_ms,perf_compositor_gpu_frametime_ms,perf_compositor_dropped_frame_count,perf_compositor_spacewarp_mode,perf_device_cpu_util_average,perf_device_cpu_util_worst,perf_device_gpu_util,hist_bucket_0,hist_bucket_1,hist_bucket_2,hist_bucket_3,hist_bucket_4,hist_bucket_5,hist_bucket_6,hist_bucket_7,source_type,source_redacted
```

Next to each CSV, the same session writes `session-<id>-events.log` (section 3, Event Log) — see `scripts/plot-session.py` to regenerate the modal's 5 charts from the CSV, on the PC, without a headset.

> [!IMPORTANT]
> **Privacy:** passwords and tokens in network URLs (SMB, FTP, SFTP, HTTP) are automatically sanitized (`redactSource`) before being written to the CSV or logcat.

## 7. Automated Debug Bundle Collection (`collect-debug.sh` - N3)

To get a complete diagnosis of the headset with a single command:

```bash
./scripts/collect-debug.sh
# or with a specific serial and an Android bugreport:
./scripts/collect-debug.sh --serial <SERIAL> --bugreport
```

The script generates a `.tar.gz` bundle containing:

1. `manifest.txt`: Quest 3 metadata, app version, build and git commit.
2. `logcat.txt` and `logcat-filtered.txt`: full logs and logs filtered by the player's tags.
3. `telemetry/`: telemetry CSV files and crash reports pulled from the headset.
4. `meminfo.txt` and `thermalservice.txt`: memory and thermal throttling diagnostics.
5. `dropbox_crashes.txt`: Android system crash records.

## 8. Visual Frame Capture in Vulkan and GLES (N4)

Since the OpenXR compositor draws directly to the display in `vr_only` mode, standard tools such as `screencap` do not capture the video scene.

The player provides direct capture of rendered frames through `nativeRequestFrameCapture`, supported on both backends (Vulkan and GLES):

- It saves the left and right eye frames as PPM images (`.left.ppm` and `.right.ppm`) with atomic writes.
- The `scripts/capture-screen.sh` script automates triggering through the `com.tucavr.debug.CAPTURE_FRAME` broadcast, extracts the PNGs straight into the `captures/` folder on the PC and opens them automatically.
- The `scripts/test-3d-playback.sh` script uses the same mechanism to validate stereoscopic projections and for automated tests.

## 9. Diagnosing Native Failures and Lifecycle (C-01 to C-04)

> [!WARNING]
> The JVM's default crash handler (`Thread.setDefaultUncaughtExceptionHandler`, Section 5) writes `crash-*.txt` files **only** for Java exceptions (`Throwable`). Native C++/Rust failures (such as a `SIGABRT` raised by ART or a `SIGSEGV` in the Adreno graphics driver) are POSIX signals that bring the process down immediately, without going through the Kotlin handler.

To capture and diagnose native failures on the Meta Quest:

```bash
# 1. View logcat's crash buffer:
adb logcat -d -b crash

# 2. Inspect tombstone files generated by the OS:
adb shell ls -la /data/tombstones/

# 3. A monitoring session focused on lifecycle and rendering:
adb logcat -c
# perform the action (e.g. open, play, close through Horizon OS, reopen)
adb logcat -d -b main -b crash -s \
  DEBUG:* AndroidRuntime:* libc:* art:* VRPlayerAppVK:* VkValidation:*
```

### Interpreting Failure Patterns

- `Native thread exited without calling DetachCurrentThread` (art): the native thread exited without calling `DetachCurrentThread` (**C-01**).
- `SIGSEGV`/`SIGABRT` involving `libvulkan.so` or the `adreno` driver: GPU work still pending during device destruction because `vkDeviceWaitIdle` is missing (**C-02**).
- `VUID-vkDestroyDevice-device-05137` / `VUID-vkDestroyCommandPool-...`: Vulkan objects destroyed out of order or after the `VkDevice` (**C-03**).
- `WindowLeaked` with `VRPresentation`: a `Presentation` or `VirtualDisplay` was not released in the Activity's `onDestroy` (**R-01**).
- Dirty-reopen behavior (e.g. playing the previous media or starting in the wrong 3D mode): static variables retained in the process cache without a reset on re-initialization (**C-04**).

## 10. `XR_META_performance_metrics` (F3)

An OpenXR extension enabled in `CreateVulkanInstanceAndDevice` (`state.supportsPerfMetrics`), with the metrics **system** enabled separately right after session creation (`SetupPerformanceMetrics`, `vr_player_app_vulkan.cpp`) via `xrSetPerformanceMetricsStateMETA` — enabling only the extension is not enough; without that second call, `xrQueryPerformanceMetricsCounterMETA` returns `XR_ERROR_VALIDATION_FAILURE` for any counter.

10 counters sampled at ~1Hz (`PollPerformanceMetrics`, the same cadence as the FPS/network poll): `app/cpu_frametime`, `app/gpu_frametime`, `app/motion_to_photon_latency`, `compositor/cpu_frametime`, `compositor/gpu_frametime`, `compositor/dropped_frame_count`, `compositor/spacewarp_mode`, `device/cpu_utilization_average`, `device/cpu_utilization_worst`, `device/gpu_utilization` — per-core (`device/cpuN_utilization`) is deliberately out of scope (it would cost one field per XR2 Gen 2 core for the same diagnosis already covered by the average/worst aggregates).

`perf_metrics_valid_mask` on the wire/CSV is a bitmask — bit N = 1 when counter N carried a valid value in this sample. A counter that is "unsupported" or momentarily unavailable has its bit **cleared**, and never shows up as an ambiguous "0" (D-04). `compositor/spacewarp_mode` is the highest-value counter for video stalls with an apparently healthy render loop: if the compositor was reprojecting, the app's `frame_ms` does not show it.

**Specification constraint**: these counters are diagnostics — they must never feed the `QualityController`. Sampling intervals are defined by the runtime, not by the app.

## 11. `ApplicationExitInfo`, Thermal Headroom and ADPF (F8)

- **`ApplicationExitInfo`** ([`ApplicationExitInfoReporter.kt`](../app/src/main/java/com/tucavr/debug/ApplicationExitInfoReporter.kt)): read once at startup (`VRActivity.onCreate`, API 30+). It covers `REASON_ANR` and `REASON_CRASH_NATIVE` — the two classes of process death that the JVM's `UncaughtExceptionHandler` (section 5) does not see, because they are failures outside the Java runtime. It writes `exit-info-<timestamp>.txt` to `getExternalFilesDir("debug")` and, from API 31, the raw tombstone as protobuf (`.pb`, not parsed — use an external tool). It deduplicates through `SharedPreferences` (only reports events newer than the last one processed).
- **Continuous Thermal Headroom**: `ThermalMonitor.getThermalHeadroom(forecastSeconds)` (API 30+) complements the existing discrete 0–5 thermal level with a forecast from 0.0 (no throttle) to 1.0 (SEVERE threshold). It returns `null` if called faster than ~1Hz (the API itself returns `NaN` in that case) — do not poll per frame.
- **ADPF** (`SetupAdpfSession`/`ReportAdpfWorkDuration`, `vr_player_app_vulkan.cpp`, API 33+): unlike the items above, it is a **write** API — the app declares the target frame duration and reports the actual one every frame; the system adjusts CPU/GPU scheduling/frequency. The `APerformanceHint_*` symbols are resolved through `dlopen("libandroid.so")`/`dlsym` (not a direct call: the NDK's clang refuses to compile a direct call to an `__INTRODUCED_IN(33)` symbol with `minSdk=26`, even inside an `if` checking the API at runtime — the same pattern this file already uses for OpenXR extension functions via `xrGetInstanceProcAddr`). The session is created on the same thread already registered as critical to the XR runtime (`xrSetAndroidApplicationThreadKHR`) — this app has no separate render thread.
