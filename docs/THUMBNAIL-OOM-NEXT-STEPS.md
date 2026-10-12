# Network Thumbnails: OOM in 8K Folders + 3D-Format-Aware Generation

> **Historical document — status of the items:**
>
> | Work item | Status | Where |
> |---|---|---|
> | Work 1 — adaptive cost-based concurrency gate (`MemoryBudgetGate`) | Done, with a fixed 900 MiB budget, `K = 10`, a 2000 ms starvation deadline | `app/src/main/java/com/tucavr/filebrowser/MemoryBudgetGate.kt:17`, `MemoryBudgetGateTest.kt` |
> | Work 1 — probe step with an on-disk `.meta` cache (semaphore of 4) | Done | `NetworkThumbnailGenerator.kt:56` |
> | Work 2 — real cancellation (per-token, Rust side) | Done | `rust/core/src/thumbnail.rs:25-56,136-169`, `nativeCancelThumbnailGeneration`, `NetworkThumbnailGenerator.kt:67-72` |
> | Work 3 — `thread_count = 1` in the thumbnail decoder | Done | `rust/core/src/thumbnail.rs:161-164` |
> | Work 4 — 3D-format-aware thumbnails | Done | `Format3D::thumbnail_crop_region` / `thumbnail_dimensions` in `rust/media-logic/src/format3d.rs:217`, used at `rust/core/src/thumbnail.rs:274-284` |
> | §8 — unbounded Bitmap caches | Done: byte-sized `LruCache`s | `FolderPreviewGenerator.kt:39-40`, `NetworkThumbnailGenerator.kt:223-224` |
> | Budget derived from `ActivityManager.MemoryInfo.availMem` and shrinking on `onTrimMemory` | **Not implemented** (the budget is a fixed 900 MiB) | — |
> | RSS / in-flight-thumbnail counters in the debug HUD (§10) | **Not implemented** | `native/src/debug_stats.h` |
>
> The line references in the body below are those of the time of the incident
> and have drifted; use the table above for current locations. Other figures that
> have moved on carry a **"Current:"** note.

---

## 1. What Happened

The app was killed by Horizon OS's `lmkd` (low memory killer) while the user was
browsing an SMB folder containing mostly 8K videos. **It was not a crash**: there
is no tombstone, `SIGSEGV`, `SIGABRT` nor `OutOfMemoryError`.

Evidence (the device's logcat, 2026-08-25):

```text
23:18:41.455  killinfo: [15504,10062,0,201,4641532,3,242472,256272,...]
23:18:42.452  oom_reaper: reaped process 15504 (com.tucavr)
23:18:42.951  ActivityManager: Process com.tucavr (pid 15504) has died: fg TOP
```

Decoding lmkd's `killinfo`: pid `15504`, `oom_score_adj=0` (foreground), **RSS =
4,641,532 KB ≈ 4.43 GB**, `kill_reason=3` (`LOW_MEM_AND_SWAP`).

Calibration: the other ~20 victims of the same window had an RSS between 88 MB
and 160 MB. tucaVR held **58% of the device's total 7.6 GB** (`MemTotal: 7943148
kB`), plus ~3.5 GB pushed to swap.

In the preceding 20 s, lmkd walked down the entire `oom_adj` ladder — killing
background apps, services and even visible apps — before sacrificing the
foreground app. Free swap fell from 4.47 GB to 0.91 GB in that interval.

**The app's own logs were lost**: logcat's `main` buffer is 256 KiB and the kill
storm rotated everything; the buffer starts 3 s *after* the death. Before any
playback, run `adb logcat -G 16M`.

---

## 2. Root Cause

1. The user enters an SMB folder — `screens/NetworkSmbScreen.kt` (at the time,
   line 461).
2. `FileAdapter.onBindViewHolder` fires **one thumbnail coroutine per visible
   item** — `screens/adapters/FileAdapter.kt`.
3. Each one calls `nativeSmbGenerateThumbnail`, a **blocking JNI call** →
   `core::thumbnail::generate` (`rust/core/src/thumbnail.rs`).
4. `generate` does a **software decode at native resolution**. This is deliberate
   and documented in `thumbnail.rs`: hardware decode would require
   Surface/AHardwareBuffer/GL, considered needless weight for a static frame.
5. The user scrolls the list. `FileAdapter` calls `holder.thumbnailJob?.cancel()`
   — **and that cancels nothing**. Coroutine cancellation is cooperative and does
   not interrupt a JNI call already in progress.
6. **Nothing limits concurrency.** `Dispatchers.IO` (64 threads by default) fills
   up with orphan 8K decoders that nobody is waiting for any more.

### The Code Already Knew This

`rust/core/src/thumbnail.rs` (the comment at the top of the file):

> *"Kotlin's cooperative cancellation (Job.cancel()) does not interrupt a JNI
> call already in progress, so real cancellation has to be a flag on the Rust
> side checked at each position."* (the original comment is in Portuguese)

The `STRIP_CANCELLED` flag was created — **but only for `generate_strip`**.
`generate`, which is the listing path, has no equivalent. The diagnosis was
already written; the fix covered half of the cases.

### Why It Only Showed Up With the Network

The local path uses `MediaMetadataRetriever.getScaledFrameAtTime`
(`filebrowser/ThumbnailGenerator.kt`), which decodes **in the mediaserver
process**, outside the app's address space, and returns the already-scaled
bitmap. tucaVR's RSS never inflated.

The network path decodes **inside the process**, at full resolution. The same
screen, the same scroll, a completely different memory model.

### The Arithmetic

An 8K VR180 frame (7680×3840, YUV420p 8 bits) = 7680×3840×1.5 ≈ **42 MB**.
`thumbnail.rs` never configured `thread_count`, so ffmpeg used the default
(frame-threading, one per core — 8 on the XR2 Gen 2), and each decoder keeps its
own pool of reference frames (DPB). A single instance easily exceeds 500 MB. Half
a dozen in parallel reach the observed 4.43 GB.

The comments in `thumbnail.rs` confirm that the test corpus is 8K VR180, and the
user confirmed the folder was mostly 8K.

---

## 3. Scope: Which Protocols

Verified — the three protocols with thumbnails fall into the **same**
`core::thumbnail::generate`:

| Protocol | Screen | Bridge | Affected |
|---|---|---|---|
| SMB | `NetworkSmbScreen.kt` | `smb_generate_thumbnail` in `bridge/src/lib.rs` | **yes** |
| FTP | `NetworkFtpScreen.kt` | `ftp_generate_thumbnail` | **yes** |
| SFTP | `NetworkSftpScreen.kt` | `sftp_generate_thumbnail` | **yes** |
| NFS | `NetworkNfsScreen.kt` | — | does not generate thumbnails |
| DLNA | `NetworkDlnaScreen.kt` | — | does not generate thumbnails |
| Local | `ThumbnailGenerator.kt` | — | no (mediaserver) |

Fixing `core::thumbnail::generate` + `NetworkThumbnailGenerator` covers all three
at once. Do **not** duplicate the fix per protocol.

### Already Ruled Out (do not waste time)

- `rust/protocols/src/prefetch.rs` — `PrefetchReader` is disciplined: at most one
  prefetch in flight (`pending_start`), a single-block cache (4 MB at the time;
  **Current:** remote blocks are 12 MB, `REMOTE_PREFETCH_BLOCK_SIZE`), and it
  drains orphan results. It is not the leak.
- `native/src/vr_player_app_vulkan.cpp` — `videoImageCache` limited (to 16 at the
  time; **Current:** `kVideoImageCacheLimit = 32`) with a real LRU eviction and
  complete destruction. Correct.
- `rust/core/src/playback.rs` — bounded packet queues (90/100 at the time;
  **Current:** `bounded(300)`).
- `rust/core/src/texture.rs` — `ImageReader` with `maxImages=4`.
- `demuxer.rs` — `probesize`/`analyzeduration` already at 1 MB / 1 s.

---

## 4. Work 1 — Adaptive Cost-Based Concurrency Gate

### The Question

A fixed semaphore of 1 solves the OOM but is excessive for 1080p, where dozens
could run in parallel. We want parallelism proportional to each video's real
cost.

### Feasibility Verdict: **accurate and implementable**, with one caveat

**The caveat matters:** the network listing returns only name/type/size —
SMB/FTP/SFTP do not deliver the resolution (see the comment in
`NetworkThumbnailGenerator.kt` and `MediaEntry.kt`). Therefore:

> **Using `sizeBytes` as a cost proxy is NOT accurate and must be rejected.** A
> file's size = duration × bitrate, which does not determine resolution. A 3
> min 8K clip can be smaller than a 2 h 1080p movie. The heuristic would be
> wrong precisely in the cases that cause the OOM.

**What makes the heuristic accurate:** a two-phase design. The real resolution
can be read **before decoding**, cheaply.

`core::metadata::extract` (`rust/core/src/metadata.rs`) already does exactly
that: it opens the demuxer (probe ≤ 1 MB, `demuxer.rs`), **decodes no frame**,
and returns `TrackInfo.width/height`
(`media-logic/src/metadata_wire.rs`). It is already exposed per protocol:
`smb_read_metadata`, `ftp_read_metadata`, `sftp_read_metadata`
(`bridge/src/lib.rs`).

### Design

```text
for each visible item:
  1. does the on-disk cache exist?  -> return it, zero cost (the short-circuit already exists)
  2. probe  (light gate: Semaphore(4))       ~1 MB of network, no decode
  3. cost = f(width, height, bits)
  4. acquire(cost) on the budget gate        <- waits here
  5. decode + scale
  6. release(cost)
```

### Cost Model

With `thread_count = 1` (Work 3), memory becomes predictable:

```text
frame_bytes = width * height * 1.5        (8 bits 4:2:0)
frame_bytes = width * height * 3.0        (10 bits)
cost        = frame_bytes * K
```

`K` covers the DPB + working frames. **`K` needs empirical calibration on the
device** — do not invent the value. Starting point: `K = 10`.

Estimates with `K = 10`:

| Resolution | frame | cost | parallel @ 900 MB |
|---|---|---|---|
| 8K VR180 (7680×3840) | 42 MB | ~420 MB | 2 |
| 8K (7680×4320) | 50 MB | ~500 MB | 1–2 |
| 4K (3840×2160) | 12.4 MB | ~124 MB | 7 |
| 1080p (1920×1080) | 3.1 MB | ~31 MB | 29 |

It is exactly the requested behavior: 8K serializes, 1080p parallelizes.

### Implementing the Gate

`kotlinx.coroutines.Semaphore` is only a counter and does **not** support
atomically acquiring N permits — using `acquire()` N times causes a deadlock (a
large request never collects all the permits while small ones slip ahead of it).

Implement a custom `MemoryBudgetGate` (~60 lines): a mutex + a wait queue.
Requirements:

- If `cost > total budget`, clamp it to the whole budget and let it run alone —
  never deadlock.
- Suggested initial budget: **900 MiB**, derived from
  `ActivityManager.MemoryInfo.availMem` instead of hardcoded. Do not use
  `getMemoryClass()`/`getLargeMemoryClass()`: those measure Java heap, and the
  consumption here is **native**. (Not implemented: the budget is a fixed 900 MiB.)
- React to `ComponentCallbacks2.onTrimMemory` by shrinking the budget. (Not
  implemented.)

#### Mixed Loads and Queue Policy

Because it is a budget (and not slots per resolution class), heterogeneous mixes
work with no special case — each job debits its own cost. With K=10 and 900 MiB:

| Mix | Cost | Fits? |
|---|---|---|
| 1×8K + 4×4K | 896.6 MiB | yes, by 3 MiB |
| 2×8K | 843.8 MiB | yes |
| 2×8K + 1×4K | 962.5 MiB | no |
| 1×8K + 3×4K + 4×1080p | 896.7 MiB | yes |

Margins of this order are tight: **the validity of the mix depends entirely on
the calibration of `K`.** With K=12 the first mix already overflows (1076 MiB).
Calibrate before fixing the budget.

**Do not use strict FIFO.** It avoids starving the 8K, but causes *head-of-line
blocking*: an 8K at the head waiting for 420 MiB blocks every 4K/1080p behind it
even with 300 MiB free. In a mixed folder that wastes budget and makes the
listing visibly slower.

Use **deadline-bounded barging**:

- Smaller requests may overtake the head of the queue as long as the head job has
  waited less than `STARVATION_DEADLINE_MS` (suggestion: 2000 ms).
- Once the deadline expires, the gate stops admitting new requests and reserves
  budget until the head job fits.
- This bounds the 8K's wait to ~the deadline + the duration of the longest job in
  progress, while keeping the small ones' throughput.

Mandatory JVM tests for this policy (`./gradlew testDebugUnitTest`):

- a 1×8K + 4×4K mix admitted simultaneously within the budget;
- small ones jump the queue while the head's deadline has not expired;
- a large job **always** gets in after the deadline (anti-starvation), even under
  a continuous flow of small ones;
- clamping a request larger than the budget;
- release on exception and on cancellation;
- absence of deadlock.

Make it **pure logic testable on the JVM** (the project's pattern, see
`docs/TESTING-PLAN.md`) and cover it with `./gradlew testDebugUnitTest`: FIFO,
clamping of the giant request, release on exception, absence of deadlock.

### Insertion Point

Inside `NetworkThumbnailGenerator.getThumbnail` — a single place covers the 3
network screens, plus `FileDetailScreen.kt` and `PlayerScreen.kt`.

Check `coroutineContext.isActive` **before** acquiring a permit and right after:
an item that is already off screen must not even enter the queue.

### Recommended Optimization

Cache the probe result on disk, with the same sha256 key as `cacheKeyFor`
(`NetworkThumbnailGenerator.kt`), reusing `metadata_wire::encode`. Re-entering a
folder then costs zero probes.

---

## 5. Work 2 — Real Cancellation (Rust Side)

`STRIP_CANCELLED` is a **global** `AtomicBool` — it works for `generate_strip`
because there is only one strip generation at a time. For `generate` there are
several concurrent ones, so a global bool would cancel all of them.

Design: a token per generation.

- Kotlin generates a `token: Long` per call and passes it in the
  `nativeSmb/Ftp/SftpGenerateThumbnail` signatures.
- Rust keeps a `Mutex<HashSet<u64>>` of cancelled tokens; a new bridge fn
  `cancel_thumbnail_generation(token)`.
- `decode_and_scale` checks the token in the packet loop (already bounded by
  `MAX_PACKETS_TRIED = 500`) and `generate` checks between the fallback seek
  attempts.
- Kotlin fires the cancel through `Job.invokeOnCompletion` / `onViewRecycled`.
- Remove the token from the set on completion, otherwise the `HashSet` leaks.

With Work 1's gate the pile-up already drops a lot; this work prevents a fast
scroll from continuing to decode 8K that nobody will see.

---

## 6. Work 3 — `thread_count = 1` in the Thumbnail Decoder

One line, and it cuts the per-instance consumption by several times.
Frame-threading brings nothing for a static frame, and it is what multiplies the
frame pool.

In `thumbnail.rs`:

```rust
let mut codec_context = ffmpeg::codec::context::Context::from_parameters(stream.parameters()).ok()?;
codec_context.set_threading(ffmpeg::threading::Config {
    kind: ffmpeg::threading::Type::None,
    count: 1,
});
let mut decoder = codec_context.decoder().video().ok()?;
```

Verified: `set_threading` exists in `ffmpeg-next 9.0` (`codec/context.rs`). Note
that `codec_context` has to become `mut`.

**Do this work first** — it is the lowest-risk, and it changes the `K` constant of
Work 1's cost model. Calibrate `K` only after it.

---

## 7. Work 4 — 3D-Format-Aware Thumbnails

### The Problem

`decode_and_scale` scales the whole decoded frame to 512×288 **ignoring the
stereo layout**. The result at the time:

- SBS → a thumbnail with two eyes squeezed side by side.
- Over/Under → two stacked images.
- VR180 SBS → two fisheye circles.

### The Detection Already Exists and Is Free Here

`format3d_detect::detect(&demuxer, path, width, height)`
(`rust/core/src/format3d_detect.rs`) returns `(Format3D, DetectionConfidence)`
from the MKV `stereo_mode` tags, the `Stereo3d`/`DataSpherical` side data, the
file name and the dimensions.

`generate` **already has the `Demuxer` and the `path` in hand** — detecting there
costs no additional I/O. See `metadata.rs` for the exact use.

A borrow caution: compute the `Format3D` **before** the mutable borrow of the
demuxer in `decode_and_scale` (`detect` takes `&demuxer`, `decode_and_scale`
takes `&mut demuxer`).

### Crop per Variant

Variants in `rust/media-logic/src/format3d.rs`.

| `Format3D` | Crop | Aspect correction |
|---|---|---|
| `Flat2D`, `Spherical360Mono`, `Vr180Mono` | none | — |
| `SbsFull`, `Spherical360SbsFull`, `Vr180Sbs` | left half | none |
| `SbsHalf`, `Spherical360SbsHalf` | left half | stretch 2× horizontally |
| `OverUnderFull`, `Spherical360OverUnderFull` | top half | none |
| `OverUnderHalf`, `Spherical360OverUnderHalf` | top half | stretch 2× vertically |

The Full/Half distinction exists in the enum precisely for this: in `Half` each
eye is squeezed and has to be re-expanded, otherwise the thumbnail comes out with
the wrong aspect ratio.

### How to Crop

The `ScalingContext` takes a whole `Frame`, so there are two options:

- **(a) Copy the cropped region into a new `Frame` before scaling.** Simple and
  safe. Cost: an extra buffer of up to ~21 MB per thumbnail — irrelevant next to
  the decoder's ~420 MB. **Recommended for v1.**
- **(b) Adjust `AVFrame.data[i]`/`width`/`height` through `unsafe`.** Zero copy.
  The project already does `unsafe` over `AVCodecParameters` (`metadata.rs`), so
  there is precedent. Leave as a later optimization, if measurement justifies it.

Care in (a): respect each plane's `stride`/linesize and the chroma subsampling
(in 4:2:0 the horizontal offset of the U/V plane is half the Y offset; an odd
offset breaks alignment).

### Out of Scope in This v1

Reprojecting equirectangular to a rectilinear view. For 360/180, taking one eye
and scaling is acceptable for now. Note it as future work.

### Interaction With `is_effectively_black`

`generate` tries up to 3 seek points checking whether the frame came out black
(`thumbnail.rs`). The crop must happen **before** that check, otherwise the metric
considers the area of the wrong eye or the black bars of a layout that is not even
the real one.

---

## 8. Secondary Findings (not the cause of this death)

Two `Bitmap` caches with no limit at all. Neither is fed during navigation, so
they did not cause this kill — but they are real leaks in a long session:

- `filebrowser/FolderPreviewGenerator.kt` —
  `private val mosaicCache = mutableMapOf<String, Bitmap>()`, with no eviction.
  512×288 ARGB_8888 mosaics ≈ 590 KB each.
- `filebrowser/NetworkThumbnailGenerator.kt` — `scrubMemoryCache`, with a comment
  explicitly assuming that "never invalidated" is acceptable. A `ScrubStrip` of a
  2 h movie ≈ 6.9 MB.

Suggested fix: replace them with an `LruCache` sized **by bytes** (`sizeOf` =
`bitmap.byteCount`), not by count. **Current:** done.

---

## 9. Suggested Execution Order

Branch: `feat/fix-smb-thumbnail-oom` off `develop`.

1. **Work 3** (`thread_count = 1`) — one line, lowest risk, changes the cost
   model's constant.
2. **Measure `K`** on the device with 8K/4K/1080p (see §10).
3. **Work 1** (the budget gate) — it is what alone would have prevented the kill.
4. **Work 2** (token cancellation).
5. **Work 4** (3D crop) — independent of the previous ones, it can go in parallel
   or in a separate branch.
6. **§8** (LRU caches) — opportunistic.

If only one thing can be delivered: **Work 1 + 3**.

---

## 10. Validation

**There is no automated coverage for the real path** — network decode and render
require the physical Quest 3 (`docs/TESTING-PLAN.md`). Do not declare as verified
what did not run on the headset.

Automatable:

```bash
cd rust && cargo test -p protocols -p media-logic
cd rust && cargo clippy -p protocols -p media-logic --all-targets --all-features -- -D warnings
./gradlew testDebugUnitTest      # MemoryBudgetGate goes in here
```

- The 3D crop logic (the `Format3D` → rectangle + aspect factor map): extract it
  to `media-logic` and test it there, following the rationale documented in
  `docs/TESTING-PLAN.md` §2. `format3d.rs` already lives in `media-logic`.
- `MemoryBudgetGate`: a pure JVM test.

On the device:

```bash
adb logcat -G 16M                      # BEFORE reproducing
adb shell dumpsys meminfo com.tucavr   # every ~2 s during the scroll
```

`dumpsys meminfo` separates `Native Heap` from `Gfx dev`/`EGL mtrack` — it is what
distinguishes "decoders piling up" from "graphics frames leaking".

Acceptance criterion: scroll the 8K folder end to end with a stable RSS below
~1.5 GB, and no `killinfo` line mentioning `com.tucavr`.

### Telemetry Gap

`native/src/debug_stats.h` has **no** memory counter at all — neither RSS nor live
allocations. The very failure mode that happened has no instrumentation. Consider
adding RSS + the number of thumbnail generations in flight to the debug HUD
(`docs/DEBUGGING.md`), which would also give a direct reading of Work 1's gate.
(Not implemented.)

---

## 11. Pitfalls

- Do **not** use the listing's `sizeBytes` as a cost proxy (§4).
- Do **not** duplicate the fix per protocol: SMB/FTP/SFTP share
  `core::thumbnail::generate` (§3).
- Do **not** use a counting `Semaphore` for the budget — deadlock (§4).
- Do **not** use strict FIFO in the gate — head-of-line blocking in mixed
  folders; use deadline-bounded barging (§4).
- Do **not** reuse `STRIP_CANCELLED` for `generate`: it is global and would cancel
  every concurrent generation (§5).
- Do **not** size the budget by `getMemoryClass()` — it measures Java heap, and the
  consumption here is native (§4).
- Android Bitmaps have their pixels on the **native heap** since Android 8: a
  Bitmap leak shows up as RSS and **never** triggers `OutOfMemoryError`. The
  absence of an OOM is not proof of the absence of a leak.
- If `ScreenMode` is touched, the numeric enum has to stay in sync in **all four**
  places (see `CLAUDE.md`) — but the §7 crop should not need to touch it.
