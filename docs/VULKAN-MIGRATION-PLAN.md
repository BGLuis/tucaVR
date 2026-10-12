# OpenGL ES → Vulkan Migration Plan

> **Historical document:** a migration plan that has been executed.
> The Vulkan 1.1 backend (`vr_player_app_vulkan.cpp`) was fully implemented and
> became the primary/default API in Stage 6. OpenGL ES remains only as a frozen
> fallback through `-PvrplayerGraphicsApi=GLES`.
>
> The stage checklists below record the plan as it was executed. Where the
> current code has since diverged (renamed functions, changed constants), the
> text carries a **"Current:"** note. The "PENDING HARDWARE VALIDATION" success
> criteria were written before the headset sessions; the Vulkan path has since
> been run on a Quest 3 (see sections 4.1.1–4.1.3 and
> `docs/NETWORK-IO-PERFORMANCE.md` §7.1), and the remaining open validation work
> is tracked in `docs/phases/PHASE-0.6-ESTABILIZACAO.md` and
> `docs/HARDWARE-VALIDATION-CHECKLIST.md`.
>
> Original migration context: `docs/REQUIREMENTS.md` (ADR-003) recorded "Vulkan
> 1.1 as the primary API, OpenGL ES 3.2 fallback". This document planned the 6
> stages that completed the transition.

## 1. State at the Time of Writing (verified in that round, not assumed)

> This section describes the codebase **before** the migration. It is kept
> because it explains why the migration required a parallel app class instead
> of an in-place port.

All the rendering of `native/src/vr_player_app.cpp` goes through the
`SampleXrFramework` (OVRFW) vendored in `sdk/meta-openxr-sdk/`, and that
framework **is GLES-only by construction**, not by the project's choice:

- `sdk/meta-openxr-sdk/Samples/SampleXrFramework/CMakeLists.txt:78` hardcodes
  `XR_USE_GRAPHICS_API_OPENGL_ES=1`.
- `sdk/meta-openxr-sdk/Samples/SampleXrFramework/Src/XrApp.cpp:932-956` only
  knows how to assemble `XrGraphicsBindingOpenGLESAndroidKHR` (Android) or the
  Win32 GL binding (editor/preview). There is no `XrGraphicsBindingVulkanKHR`
  nor `XR_KHR_vulkan_enable` anywhere in the vendored SDK.
- Every rendering class used by the app — `GlProgram`, `GlGeometry`,
  `GlBuffer`, `GlTexture`, `Framebuffer`, `SurfaceRender`, `BeamRenderer`,
  `PanelRenderer`, `BitmapFont`, `ParticleSystem` (all in
  `sdk/meta-openxr-sdk/Samples/SampleXrFramework/Src/Render/`) — is pure GL.
  There is no Vulkan variant of any of them in Meta's package.
- The zero-copy video path (`AHardwareBuffer` → `eglCreateImageKHR` →
  `GL_TEXTURE_EXTERNAL_OES`, in `vr_player_app.cpp`, around the
  `eglCreateImageKHR` call) depends on the GLES/EGL extension
  `GL_OES_EGL_image_external_essl3`.
- `native/CMakeLists.txt` already linked `vulkan-lib`, but nothing in the code
  used it at the time — a leftover of the original ADR-003, not a functional
  path.

**Conclusion**: the deviation from ADR-003 was not negligence — the framework
the project adopted (OVRFW) simply does not offer a ready-made Vulkan path. Any
migration requires rewriting the session/rendering layer, not just swapping API
calls.

## 2. The Structural Constraint That Defines the Strategy

An OpenXR session chooses its graphics binding **exactly once**, in
`xrCreateSession`, and the resulting swapchain images are typed by the API (GL
texture ids vs. `VkImage`). **There is no "half GL, half Vulkan" inside a single
session/frame** — this is binary by OpenXR's definition, not a limitation of
this project.

Practical consequence: the minimum cutover granularity is "the whole session/app
using one graphics API", not "call by call". Since `VRPlayerApp` inherits from
`OVRFW::XrApp` (which is hardcoded GLES), the Vulkan path **cannot extend that
class** — it needs its own parallel app class (e.g. `VRPlayerAppVulkan`) that
reimplements session management and the frame loop from scratch, including the
OVRFW pieces the GLES version uses "for free" (`BeamRenderer`, `SurfaceRender`,
etc.).

## 3. Strategy: Parallel Paths, No Big Bang

Instead of migrating `vr_player_app.cpp` in place, the plan was:

1. Create the Vulkan path as **new, parallel code**
   (`native/src/vr_player_app_vulkan.cpp` or equivalent), behind a build option
   (a CMake flag, e.g. `VRPLAYER_GRAPHICS_API=GLES|VULKAN`, default `GLES`).
2. `main` and the release builds keep using the GLES path at all times until
   Stage 6 (the cutover criterion). The app is never broken/non-shippable while
   the Vulkan path is being developed.
3. Each stage below is validated on the physical Quest 3 in isolation before
   moving to the next (no automation is possible here — see
   `docs/TESTING-PLAN.md` for what requires a real headset).
4. Rust (`core`, `audio`, `protocols`, `media-logic`, `bridge`) and Kotlin are
   not affected by this migration — the Rust↔C++ boundary is already opaque to
   the graphics API (the bridge delivers an `AHardwareBuffer`, not GL textures).
   Out of scope for this plan.

## 4. Stages

### Stage 1 — Vulkan Session Skeleton

- [x] Implement `XR_KHR_vulkan_enable`: create the `VkInstance`/`VkDevice` as
      `xrGetVulkanGraphicsRequirementsKHR` requires
      (`native/src/vr_player_app_vulkan.cpp`, `CreateVulkanInstanceAndDevice`).
- [x] Create the OpenXR session with `XrGraphicsBindingVulkanKHR`
      (`android_main`, same file).
- [x] Vulkan swapchain (one per eye, `faceCount=1`/`arraySize=1`, mirroring the
      topology of the GLES path's `Framebuffer.cpp`) with a solid clear color
      and no geometry (at the time `RecordAndSubmitClear`).
- [x] Build option `VRPLAYER_GRAPHICS_API` in `native/CMakeLists.txt` (default
      `GLES`, unchanged) + `-PvrplayerGraphicsApi=VULKAN` in
      `app/build.gradle.kts` to trigger the new path through Gradle.
- **Compilation**: verified in that round — `./gradlew assembleDebug
  -PvrplayerGraphicsApi=VULKAN` builds clean, and `./gradlew assembleDebug`
  (default GLES) kept building with no behavior change.
- **Finding during implementation**: linking `samplexrframework`
  unconditionally (as the GLES path does) would break the compilation of the
  Vulkan file — that target propagates `XR_USE_GRAPHICS_API_OPENGL_ES=1` and
  `-Werror` as `PUBLIC` to whoever links it. `native/CMakeLists.txt` now links
  different libraries per variant (see the comment in the file). It was also
  necessary to `#include <jni.h>` before `openxr_platform.h` (it uses `jobject`
  without a guard).
- **Success criterion**: the app opens in VR on the Quest 3 without crashing,
  shows the clear color (solid blue, deliberately chosen to differ from the GLES
  path's black) in both eyes, and head tracking works. In that round it could
  not be confirmed visually — the connected Quest 3 blocked `am start` with the
  system dialog "controllers required" (controllers off/untracked) before the
  app got to run, and the user chose to skip hardware validation for the time
  being. To validate: turn the controllers on, `adb install -r
  app/build/outputs/apk/debug/app-debug.apk` (built with
  `-PvrplayerGraphicsApi=VULKAN`), `adb shell am start -n
  com.tucavr/.VRActivity`, and check `adb logcat -s VRPlayerAppVK:*` — the
  initialization line followed by session-state changes with no `OXR(...)`/
  `VKR(...)` aborting indicates success. (At the time that line read `"Estagio
  2 (quad estatico Vulkan) inicializado"`; **Current:** the code logs
  `"Estagios 3-5 … inicializados — loop principal"`, since the log name evolved
  with the stages.)
- **Risk**: the Vulkan version/extension requirements of the Quest's runtime
  may differ from what generic OpenXR-Vulkan documentation suggests — this is
  exactly the validation described above.

### Stage 2 — Static Quad

- [x] New, minimal GLSL shaders (`native/shaders/vulkan/quad.vert`,
      `quad.frag` — not the existing GLES shaders, which sample
      `GL_TEXTURE_EXTERNAL_OES` and only make sense from Stage 3 on) compiled to
      SPIR-V through `glslc` (from the NDK, `shader-tools/`) and embedded as a
      byte array in C++ headers generated at build time
      (`native/cmake/EmbedSpirv.cmake`, `native/CMakeLists.txt`) — no asset
      packaging/loading at runtime.
- [x] No descriptor set layout yet: color + MVP through a push constant
      (`QuadPushConstants` in `vr_player_app_vulkan.cpp`). A deliberate decision
      — descriptor sets only gain a real purpose in Stage 3 (the texture
      sampler); creating them now would be speculative infrastructure with no
      use.
- [x] Minimal Vulkan graphics pipeline (`CreateRenderPass`,
      `CreateFramebuffers`, `CreateGraphicsPipeline`, no depth attachment)
      drawing the quad in a solid color (amber, contrasting with the blue
      background) through what was then `RecordAndSubmitQuad`. **Current:** the
      solid-color fallback quad is recorded by `RecordFallbackQuad`.
- [x] Projection/view math (`native/include/vk_math.h`): an asymmetric
      projection matrix from the OpenXR FOV (a formula equivalent to the
      OpenXR-SDK reference `xr_linear.h`, specialized for Vulkan's clip space)
      and the rigid-transform inverse for each eye's pose — necessary because
      this path does not derive from `OVRFW::XrApp` and has no access to
      `OVR_Math.h`/`XrMatrix4x4f_CreateProjectionFov`.
- [x] Quad positioned 2m away, at the same height (`y=1.5`) that
      `vr_player_app.cpp` (`m_screenPosition`) uses in the GLES path, so it is
      comparable to the production app; scale 1.6m×0.9m (16:9, a placeholder —
      Stage 3 replaces it with the video's real aspect ratio).
- **Compilation**: verified in that round — `./gradlew assembleDebug
  -PvrplayerGraphicsApi=VULKAN` builds clean (glslc ran, headers generated in
  `app/.cxx/.../generated_shaders/`), and the default GLES build stays
  unchanged.
- **Success criterion**: visible quad, in the correct position/scale, correct
  stereo proportion in both eyes. Not validated in that round for the same
  reason as Stage 1 (Quest 3 controllers off blocking the launch) — the same
  test procedure documented there applies here.

### Stage 3 — Video Texture (the highest-risk step)

- [x] Import the decoded `AHardwareBuffer` as a `VkImage` through
      `VK_ANDROID_external_memory_android_hardware_buffer`
      (`GetOrImportVideoFrame` in `vr_player_app_vulkan.cpp`). A `VkImage` cache
      keyed by `AHardwareBuffer*` pointer, equivalent to the GLES path's
      `m_eglImageCache` (originally limited to 6 entries, same eviction policy).
      **Current:** `kVideoImageCacheLimit = 32` (`vr_player_app_vulkan.cpp:364`),
      evicting by `lastUsedFrame` (LRU).
- [x] `VkSamplerYcbcrConversion` for the `MediaCodec` YUV format (GLES did this
      conversion for free through `GL_OES_EGL_image_external_essl3`; in Vulkan
      it is explicit through `CreateYcbcrAndVideoPipeline`, with an immutable
      sampler in the descriptor set layout — a spec requirement for Android
      external buffers).
- [x] A video pipeline separate from the fallback quad: `video.vert`/
      `video.frag` (GLSL→SPIR-V at build time through `glslc`, embedded as C++
      headers), a vertex buffer with interleaved UVs, a descriptor set per
      video frame with the `VkSamplerYcbcrConversion` embedded in the view.
- [x] `UpdateVideoFrame` calls `get_current_video_frame()` (the Rust bridge)
      every frame; on getting a new buffer it imports it or reuses it from the
      cache; on getting `nullptr` it falls back to the solid quad of Stage 2.
- [x] A `VK_QUEUE_FAMILY_FOREIGN_EXT → graphics` pipeline barrier before the
      render pass to guarantee the visibility of the frame decoded by MediaCodec
      (no explicit semaphore — the same pragmatic approach as the GLES path).
- **Compilation**: verified in that round — `./gradlew assembleDebug
  -PvrplayerGraphicsApi=VULKAN` builds clean (the `video.vert`/`video.frag`
  shaders compiled to SPIR-V, headers generated), and the default GLES build
  stays unchanged.
- **Success criterion**: 2D video plays with no color artifacts/tearing, with
  performance comparable to the GLES path (measure with the same test clip used
  in `scripts/test-4k-memory.sh`).
- **Risk**: this is the point where the migration can stall — if the Quest 3
  driver behaves unexpectedly with `VkSamplerYcbcrConversion` for the specific
  format `MediaCodec` produces, it is worth isolating and validating before
  continuing to the following stages. In particular: `ycbcrModel` and
  `ycbcrRange` were originally set to `YCBCR_601`/`ITU_NARROW` (the most common
  for H.264/H.265 on Android); if the driver reports a different format through
  `vkGetAndroidHardwareBufferPropertiesANDROID`, it may be necessary to use the
  model/range it indicates instead of hardcoding. **Current:** the code uses
  `YCBCR_709` with `ITU_NARROW` (`vr_player_app_vulkan.cpp:2630-2631`).

### Stage 4 — UI/Controls Textures

- [x] `CreateUiPipeline`: an `AImageReader` (`kUiTexWidth×kUiTexHeight` =
      1024×768 for the file browser, `kControlsTexWidth×kControlsTexHeight` for
      the controls — 1024×384 at the time, **Current:** 1582×800) through
      `ANativeWindow_toSurface` → RGBA8888 `VkImage`. Every frame,
      `UpdateUiFrames` reads `AImageReader_acquireLatestImage` → imports into a
      `VkImage` through a staging buffer (CPU readback +
      `vkCmdCopyBufferToImage`). An RGBA8888 descriptor set with a normal linear
      sampler (no YCbCr).
      **Post-migration fix**: the file browser had been born at 1024×1024 in
      this file, but `VRActivity.dispatchVRTouch` already assumed 1024×768 (a
      contract shared with the GLES path) — the touch Y came out compressed into
      the top 75% of the panel. Unified to 1024×768 (see the post-migration
      review below).
- [x] A separate pipeline with alpha blending (`SRC_ALPHA / ONE_MINUS_SRC_ALPHA`)
      for the auto-hide fade (equivalent to `vr_player_app.cpp`'s UI alpha
      blending). The `ui.vert`/`ui.frag` shaders pass `alpha` through a push
      constant — `DrawUiQuads` now feeds it `state.uiAlpha`/`state.controlsAlpha`
      (before it was a fixed `1.0f`, with no fade at all).
- [x] Connection with Kotlin (`setupVirtualDisplay`/`setupControlsVirtualDisplay`)
      — JNI wiring done (`android_main`, after `xrCreateSession`); the
      `AImageReader` is created and the `ANativeWindow` converted into a Java
      `Surface` directly in `CreateUiPipeline`.
- **Success criterion**: the UI panel and the controls panel render and receive
  touch/interaction — **verified in the sense of "the hit-test lands on the
  right texel"** in the post-migration review (matrices/dimensions corrected).
  Visual validation on hardware was still pending when this was written.

### Stage 5 — Remaining Geometry

- [x] A stereo pipeline (`stereo.vert`/`stereo.frag`) with a push constant of
      `eyeIndex + swapEyes + stereoLayout + polar180` — ports exactly the UV
      logic of `stereoFlatFragmentShader` and `sphereFragmentShader`
      (`vr_player_app.cpp`) including CAS sharpening (4 neighbors, adaptive
      strength by local contrast).
- [x] Equirectangular sphere geometry (`CreateSphereGeometry`): 70 rings × 128
      slices = the same resolution as `OVRFW::BuildGlobe` in the GLES path.
      Linear UV 0→1, camera inside the sphere, an index draw with
      `VK_INDEX_TYPE_UINT32`. (Earlier revisions of this document called the
      function `CreateSphereMesh`; the name in the code is
      `CreateSphereGeometry`.)
- [x] Dispatch by `ScreenMode` in `RenderFrame`: Flat2D→the video pipeline
      (Stage 3), SBS/OU→the stereo pipeline + quad, Sphere*→the stereo pipeline
      + sphere. `get_3d_mode()` is read every frame from the Rust bridge.
      **Current:** the per-mode recording lives in `RecordVideoFlat`,
      `RecordStereoFrame` and `RecordPhotoFrame`.
- [x] `CreateBeamResources`: a simple laser line (2 vertices,
      `VK_PRIMITIVE_TOPOLOGY_LINE_LIST`) through `quad.vert/frag`. Functionally
      equivalent to the GLES `ovrBeamRenderer`; it does not port the
      taper/billboard geometry (out of scope for this phase). `lineWidth` went
      back to `1.0f` in the post-migration review — the original value (`2.0f`)
      requires the Vulkan feature `wideLines`, which is not enabled in
      `vkCreateDevice` (nor guaranteed on the Quest 3's Adreno); requesting it
      without checking was an invalid use of the API.
- **Success criterion**: **it did not have full functional parity** as this
  document claimed before the post-migration review — see the "Post-migration
  review" section below for what was really missing and was fixed in that round.
  Every item below was fixed by reading/analyzing the code, with no physical
  headset available in that session; the sections 4.1.1–4.1.3 fixes were later
  confirmed on hardware.

### Stage 6 — Cutover

- [x] `app/build.gradle.kts`: the default switched from `"GLES"` to `"VULKAN"`
      — `./gradlew assembleDebug` with no flags already produces the Vulkan
      path.
- [x] GLES kept as a real fallback through `-PvrplayerGraphicsApi=GLES` (two
      paths in parallel). ADR-003 decision revised: do not remove the GLES code
      until complete validation on a headset. **Current:** the GLES path is
      frozen (no new features), see `CLAUDE.md`.
- **Success criterion**: the default release build uses Vulkan; zero regression
  in the manual playback tests (`docs/TESTING-PLAN.md`). The remaining
  validation campaign is tracked in `docs/HARDWARE-VALIDATION-CHECKLIST.md`.

## 4.1 Post-Migration Review — Findings and Fixes

A review session (after Stages 1–6 above) motivated by two reported problems: a
build broken by a concurrent recenter refactor in the GLES path, and a
camera/UI with visibly wrong geometry in screenshots of the Vulkan path (a dark
`0.02,0.02,0.05` background, a blue laser — identifiable as Vulkan, not GLES,
by the colors). The investigation found that Stage 6 had already switched the
Gradle default to `VULKAN`, but the Vulkan path had neither the camera fix nor a
good part of the interaction parity that Stages 4/5 above declared done.
Findings, most serious first:

- **Billboard computed per eye, not per head**: `DrawUiQuads` was called inside
  the eye loop with `views[eye].pose.position` — the UI billboard's yaw came out
  different for each eye (~6cm of interpupillary separation), breaking the
  panel's stereo disparity. This is the most likely cause of the odd geometry in
  the screenshots. Fixed: a `headCenter` (the average of the two eyes) computed
  once per frame in `RenderFrame`, used by `UpdateInteraction` and by every call
  to `DrawUiQuads` and the video/quad recording functions.
- **Texture dimensions diverging from the Kotlin contract**: see the note in
  Stage 4 above (1024×1024 vs. the 1024×768 that `VRActivity.dispatchVRTouch`
  already assumed).
- **`rayHitsQuad` receiving a matrix with embedded scale**: `Mat4RigidInverse`
  assumes an orthonormal rotation; passing a matrix with `Mat4Scale` broke the
  inverse and, in turn, the controls panel's hit-test. Fixed: `rayHitsQuad` now
  receives the matrix *without* scale and the scale factors as separate
  parameters, dividing the local point (the same technique that already
  existed, informally, only for the UI panel).
- **Transform math duplicated and already diverging**: `UpdateInteraction` and
  `DrawUiQuads` each recomputed the UI/controls matrices on their own — one
  applied the scale embedded in the matrix, the other divided the local point
  afterwards. Extracted into `ComputeSceneTransforms` (shared) in
  `vr_player_input_vulkan.h`.
- **No recenter at all**: neither the
  `XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING` event (system recenter)
  nor the Menu long-press (manual recenter, T4.3 of Phase 0.2 in GLES) existed in
  the Vulkan path. Ported `sceneYawOffset`/`sceneTranslationOffset` (the same
  concept as GLES) plus the initial calibration on the 1st frame with a valid
  pose.
- **360 sphere fixed at the origin**: the sphere's MVP was a pure
  `proj * view`, not following the head's translation — walking physically in the
  Guardian, the user left the inside of the sphere. Fixed to mirror GLES's
  `m_sphereTransform`.
- **Beam always drawn**: the "controller detected" test (`lastRayDir.z != 0.0f`)
  was always true, since `lastRayDir` starts with `z=-1`. Replaced by a flag
  (`state.hasRay`) set by the real result of `xrLocateSpace`.
- **`samplerAnisotropy` requested without checking support**: the same category
  as `wideLines` above (see Stage 5) — `vkCreateDevice` now queries
  `vkGetPhysicalDeviceFeatures` and only enables/requests what the device
  supports.
- **No interaction parity**: auto-hide with fade, dispatch gating per visible
  panel, haptics, A/X/B/Y/Menu, thumbsticks (move/resize the screen,
  seek/volume), periodic progress and a cursor at the hit point did not exist in
  the Vulkan path — ported into `UpdateInteraction` (new OpenXR actions in
  `SetupOpenXrInputs`: `a_click`/`x_click`/`b_click`/`y_click`/`menu_click`/
  `thumbstick`/`squeeze`/`haptic`). The cursor at the hit point uses a single
  segment (instead of GLES's two) because this path's beam pipeline
  (`quad.frag`) lacks the radial fade that GLES's `BeamRenderer` has — a solid
  segment already solves it.

None of this was validated on the physical Quest 3 in the session in which it
was written — only code review and a logical compilation check.

### 4.1.1 Sign Bug in the Recenter Yaw — confirmed on hardware

A real headset test (the following session) reported three symptoms that seemed
unrelated: UI/controls panels "too far, jagged, impossible to read the text"; a
180° video over SMB "not playing"; and the automatic recenter rotation
"incorrect" (expected: the screen should end up in front of the user). All three
were the same bug: the yaw formula used at the 4 recenter points
(`RenderFrame`/`UpdateInteraction` in Vulkan, the two equivalent blocks in
GLES) —

```cpp
sceneYawOffset = atan2f(fwd.x, -fwd.z);
```

— rotates the content **180° to the wrong side**. Verified algebraically against
`OVR::Matrix4f::RotationY` (`OVR_Math.h:3437-3441`, the same convention used by
`Mat4RotationY` in `vk_math.h`): for a user facing 90° relative to the world, the
original formula places the content behind them instead of in front. That
explains the 3 symptoms: panels outside the central field of view show up only
barely at the FOV edge (severe foreshortening = "jagged, unreadable"); the front
hemisphere of a 180° video ends up rendered behind the user (it looks like it is
"not playing" when it actually is, just out of sight). Fixed by negating the X
argument of `atan2` at the 4 points: `atan2f(-fwd.x, -fwd.z)`. This was exactly
the scenario the original comment ("the angle's sign was never validated on a
physical headset") warned could happen.

See `docs/TESTING-PLAN.md` for what else requires a physical headset and had
not yet been validated in this review.

### 4.1.2 Geometry/Pipeline Bugs in the SBS/OU/180 Modes — confirmed on hardware

A real test after the §4.1.1 fix reported three more symptoms in the
stereo/sphere modes (exclusive to the Vulkan path — GLES does not have these
bugs, because its geometry comes from `OVRFW::BuildGlobe`, not reimplemented by
hand):

- **"Some video players get cut in a triangle"** (the flat SBS/OU modes,
  `ScreenMode::SBS`/`SBSHalf`/`OU`/`OUHalf`): `CreateStereoPipeline` created a
  single `VkPipeline` with `VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST`, used both for
  the sphere's index draw (correct, the indices form pairs of discrete
  triangles) and for the flat SBS/OU quad — which draws the same 4 vertices of
  `state.videoVertexBuffer` (ordered BL,BR,TL,TR, with an explicit "triangle
  strip" comment in `CreateVideoVertexBuffer`) through `vkCmdDraw(cmd, 4, ...)`
  with no indices. With `TRIANGLE_LIST`, 4 vertices only form **one** complete
  triangle (0,1,2); the 4th vertex is discarded — literally half of the quad,
  cut along the diagonal. Fixed by creating a second pipeline
  (`state.stereoFlatPipeline`, the same shaders/layout, only `TRIANGLE_STRIP`)
  and choosing which one to bind in `RenderFrame` according to `sphereMode`.
- **"The 180 is not being displayed"**: `CreateSphereGeometry` (the manual
  sphere reimplementation for Vulkan) parameterizes the longitude with
  `theta = 2π·s/slices`, which puts `uu=0` (not `uu=0.5`) at -Z (in front of the
  user). The fragment shader (`stereo.frag`, a faithful port of GLES) discards
  outside `uv.x ∈ [0.25, 0.75]` assuming the convention of GLES's
  `BuildGlobeDescriptor` — verified directly in the SDK
  (`GlGeometryDescriptor.cpp:606`: `lon = (0.25+xf)·2π`, which computes `xf=0.5`
  → -Z) — where **`uu=0.5` is the front**. Since the two conventions diverged by
  half a turn, the 180° cut discarded exactly the front hemisphere (the only
  visible one) and kept the back one. Fixed by shifting the **position** (not the
  `uu`) by half a turn: `theta = 2π·s/slices - π`. Shifting the `uu` directly
  would have been simpler but would break the duplicated-vertex technique that
  closes the sphere's seam without an interpolation jump (`s=0`/`s=slices`, the
  same position, `uu=0` vs `uu=1`) — with the shift on the position instead of
  the UV, that seam stays intact, only now aligned with the correct convention.
- **"One of the 360 models came out with the video inverted / text read as in a
  mirror"**: besides the original longitude shift (front/back), the vertex
  formula of the sphere mesh contained an unwanted negative sign in the X
  coordinate calculation: `float x = -radius * sinf(phi) * sinf(theta)`. With
  that, any point with $\theta > 0$ (the right side of the equirectangular
  texture, $uu > 0.5$) was projected to $X < 0$ (the left side in VR space),
  horizontally inverting the projection (a mirror effect, where text and scene
  details were read backwards). Fixed by removing the negative sign (`float x =
  radius * sinf(phi) * sinf(theta)`), aligning the mesh with the convention of
  GLES's `BuildGlobeDescriptor` and validated with an automated unit test in
  `native/tests/test_vk_math.cpp` (`TestSphereMeshCoordinates`).

See `docs/TESTING-PLAN.md` for what else requires a physical headset and had
not yet been validated in this review.

### 4.1.3 UI/Controls Panels Too Far/Small to Read — confirmed on hardware

Even after the yaw fix (§4.1.1), the real headset test reported that the text of
the File Browser and controls panels was still unreadable. It was no longer a
rotation bug — the position/scale values themselves (identical in both paths,
GLES and Vulkan, since they were unified in §4.1) placed the UI panel ~2.66m
away with only 0.8m of width, a visual angle of ~17° — too small to read text
comfortably in a headset. Those values had never been validated on hardware
(inherited from the original T4.5 design, see the comments in
`vr_player_app.cpp`).

Fixed by bringing both panels closer and enlarging them by 50%, preserving each
texture's proportion (4:3 for the UI, 1024×768; 2.667:1 for the controls,
1024×384 at the time):

| | Before | After |
|---|---|---|
| UI: base position | `(-2.2, 1.5, -1.5)` | `(-1.3, 1.5, -1.0)` |
| UI: distance / scale | ~2.66m / 0.8×0.6 | ~1.64m / 1.2×0.9 |
| Controls: base position | `(0, 0.4, -1.9)` | `(0, 0.4, -1.3)` |
| Controls: distance / scale | 1.9m / 0.8×0.3 | 1.3m / 1.2×0.45 |

**Current:** the controls texture is now 1582×800, so its scale is
`kControlsPanelScaleY = 1.2 × 800 / 1582 ≈ 0.607` (`vr_player_input_vulkan.h:82`);
the UI values above are unchanged.

A single source in each path — `baseUiPos`/`baseControlsPos` in
`vr_player_app.cpp` (GLES), `kBaseUiPos`/`kUiPanelScaleX/Y`/`kBaseControlsPos`/
`kControlsPanelScaleX/Y` in `vr_player_input_vulkan.h` (Vulkan, consumed by both
the hit-test and the render through `ComputeSceneTransforms`) — so adjusting
these values in the future does not risk the hit-test and the render diverging
again.

See `docs/TESTING-PLAN.md` for what else requires a physical headset and had
not yet been validated in this review.

## 5. Before Starting — Validating the Premise (historical)

This app essentially renders 1-2 quads/a sphere with video — a low geometric
load. Vulkan's gain is more relevant in scenes with many draw calls, and the real
performance bottleneck here is more likely video decode/sync than the graphics
API. Recommendation at the time: measure a concrete performance problem
attributable to GLES before investing in Stages 1-6 — otherwise the cheapest
return would be just updating ADR-003 to reflect reality (GLES through OVRFW)
instead of rewriting the rendering engine.

The project went ahead with the migration anyway; ADR-003 and REQUIREMENTS §5.3
now state that Vulkan is the default backend and GLES a frozen fallback.

## 6. Out of Scope

- Rust (`core`/`audio`/`protocols`/`media-logic`/`bridge`) — the boundary with C++
  is already opaque to the graphics API.
- Kotlin — `VirtualDisplay`/`Presentation` keep producing
  `Surface`/`AHardwareBuffer`, regardless of who consumes it on the C++ side.
- Any CI work — at the time of writing, the full native build was commented out
  in CI (it requires Meta's licensed SDK, see `CLAUDE.md`), so this migration did
  not change the existing automation surface. **Current:** CI now has a
  `cpp-checks` job (clang-format, shader validation, ASan host tests) and a
  gated `build-apk` job that runs the full native + Rust + Gradle build (it
  downloads the Meta SDK through `scripts/setup-deps.sh`).
