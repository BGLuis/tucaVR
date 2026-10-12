# Next Steps: Seek/Scrub UX and Visual Feedback

> A continuation plan for a session that implemented play/pause/seek feedback,
> a loading indicator, a frozen frame on seek, network connection reuse between
> seeks (SFTP/SMB/HTTPS) and a first version of the seekbar drag preview. This
> document covers what was left pending, with the reasoning already gathered, so
> it can be resumed in another session — it is not theoretical work: items 1-3
> came from behavior **observed on real hardware** (Quest 3, a NAS at
> `192.168.0.246`, a collection mostly of 8K VR180), not from assumption.
>
> The text below describes the code as of that session. Where the code has since
> moved on, a **"Current:"** note says so (FTP/SMB retry, the SFTP read timeout
> value, `ConnectionCache` variants, line references).

## Context — What Is Already Resolved

- Play/pause/±10s feedback (a central flash on the controls panel).
- Loading indicator (`get_playback_is_loading`) and the real play/pause signal
  (`get_playback_is_playing`) through ~10Hz polling, GLES/Vulkan parity.
- `seek()` no longer undoes the paused state (a real bug: every seek un-paused
  on its own, because `Generation::new()` always starts with `is_playing=true`).
- A frozen frame during the post-seek load (it no longer flickers to empty),
  GLES/Vulkan parity — in Vulkan, safety depends on `activeVideoFrame` never
  being an LRU eviction candidate while it is on screen (documented in the code,
  `vr_player_app_vulkan.cpp::UpdateVideoFrame`).
- `ConnectionCache` (SFTP, SMB, HTTPS) — reuses the network connection between
  seeks in the same file instead of reopening from scratch. FTP was left out
  (reason: see section 4). **Current:** the cache is
  `Empty | Sftp | Smb | Https | Webdav` (`rust/core/src/demuxer.rs:49-56`).
- A read timeout for SFTP (`SFTP_READ_TIMEOUT`, 20s at the time) — without it, a
  read the server never answers (the channel stays alive, keepalive does not
  catch it) froze the whole `PlaybackController`, because `seek()`/`stop()` have to
  `join()` the stuck thread before proceeding. **A real finding**: reproduced on
  hardware as "the video freezes on the frozen frame indefinitely" — the log
  showed `"sem frame novo há 506ms"` ("no new frame for 506ms") followed by total
  silence, with no error reported (the freeze happened BEFORE any error existed
  to report). **Current:** `SFTP_READ_TIMEOUT` is 3500ms
  (`rust/protocols/src/sftp/mod.rs:149`); SMB's is 20s (`smb/mod.rs:58`).
- Infrastructure discovery: this project's default build uses **Vulkan**, not
  GLES (`build.gradle.kts` silently overrides the default in `CMakeLists.txt`).
  At the time `CLAUDE.md` was outdated on that point; it now states that Vulkan is
  the default and GLES a frozen fallback. All the C++ work of that session was
  ported to both paths.
- A fix for the black thumbnail (a retry at 5s/15s if the frame at 1s comes out
  black — common in VR180, which tends to have a long fade-in).

## 1. Drag (Scrub) Preview for Videos Above 4K

**Current state**: `core::thumbnail::generate_strip()` refuses to generate a
strip above `MAX_STRIP_SOURCE_PIXELS` (4K = 3840×2160) — decided in session after
a real crash (probably OOM) when dragging the tracker on an 8K60 SFTP file, with
the strip generation running concurrently with the real playback. Since the test
collection is almost entirely 8K, **that leaves the preview unavailable for the
real use case today**.

**Why "downscale" does not solve it**: the expensive cost is the *decode* (the
reconstruction of the frame from HEVC/AV1), not the output size — the output is
already tiny (80×45px). Software decoders have no way of decoding HEVC/AV1 "at a
lower resolution" from the bitstream; the reduction only happens afterwards,
through `sws_scale`, which is exactly what the code already does. There is no
possible memory gain just by adjusting scale parameters.

**The real path**: **hardware** decode (MediaCodec) for the strip generation,
instead of pure software. The reason the cost is so much lower: a hardware
decoder is a dedicated ASIC with its own memory pipeline, and does not compete
for general RAM/CPU the way software `avcodec` does — especially relevant
running concurrently with the real playback (which already uses hardware).

Sketch of the work (not started at the time of the original plan):

1. Reuse `HwDecoder` (`rust/core/src/decoder.rs`, already used by the real
   playback) instead of `thumbnail.rs`'s software decode.
2. It needs an output `Surface`/`AHardwareBuffer` — today `core::thumbnail`
   touches none of that (that is why it exists: "hardware decode requires
   Surface/AHardwareBuffer/GL, needless weight for a static frame" — the
   original comment, valid for 1 frame, no longer valid for a strip of hundreds).
3. Read the pixels back from the `Surface`/`AHardwareBuffer` into RGBA
   (glReadPixels or equivalent) after each internal seek — more complex than the
   current `sws_scale`, it needs an active GL context (or Vulkan, given the
   finding in the "Context" section above).
4. A simpler alternative to evaluate first: generate the strip **in the
   background, at low priority, one frame at a time with pauses between decodes**
   (instead of sequentially with no pause) — it would reduce the chance of a
   memory peak coinciding with the moment of highest demand of the real
   playback, without needing to migrate to hardware. Simpler, but it does not
   eliminate the risk, only reduces the probability — worth measuring on
   hardware before deciding whether it is enough.

**Pending decision**: which of the two paths (a "real" hardware decode vs. the
spacing mitigation) is worth the effort first. I recommend measuring option 4
(cheaper to implement) before investing in option 1-3.

**Update 1**: option 4 (pacing) was implemented first — `generate_strip()` took
a 100ms pause between frames (`rust/core/src/thumbnail.rs`). A mitigation only;
it did not unblock the 4K gate by itself.

**Update 2 — hardware decode implemented**: above `MAX_STRIP_SOURCE_PIXELS`,
`generate_strip()` now calls `generate_strip_hw()` instead of giving up. A
finding that simplified the original sketch considerably: **it did not need any
Surface/AHardwareBuffer/GL/Vulkan**.
`ndk::media::media_codec::OutputBuffer::buffer()` lets you read the raw YUV bytes
directly when `HwDecoder::configure()` is called with `window: None` — decode to
a CPU buffer instead of decode to a Surface. The YUV→RGBA conversion
(nearest-neighbor, since the output is tiny) became `media_logic::yuv_convert` —
pure, tested through `cargo test -p media-logic` with no hardware at all (6
tests: white/black, planar vs. semi-planar, stride padding, out-of-bounds). Only
Android's two "flat" color formats are supported
(`COLOR_FormatYUV420Planar`=19, `COLOR_FormatYUV420SemiPlanar`=21, see
`YuvLayout::from_android_color_format`) — `COLOR_FormatYUV420Flexible` would
require the `Image`/`MediaImage` API, which the raw `AMediaCodec` API used here
does not expose; if the decoder returns another format, that position of the
strip stays black (the same silent contract as the rest of the module) instead
of garbage.

Architecture decision: the `HwDecoder` is recreated from scratch at each strip
position (not `flush()`) — it is the only pattern already validated on hardware
in this project for "seek during MediaCodec decode" (`PlaybackController::seek`
also recreates the whole session instead of reusing the decoder, `playback.rs`).
More expensive than `flush()` would have been, but without the risk of a silent
driver-specific bug that was never tested here.

**A real risk not ruled out**: Qualcomm hardware decoders do not always reliably
support decoding without a Surface — on some devices the accelerated path
requires a real Surface, and without one `configure()` fails or falls back to a
non-accelerated path. It can only be confirmed by testing on a real Quest 3. If it
fails, plan B is the original sketch (Surface + AHardwareBuffer + headless GL/Vulkan
readback, steps 1-3 above) — more code, but it uses the mechanism already known
to work (the real playback uses a Surface successfully).

The `MAX_STRIP_SOURCE_PIXELS` (4K) gate stays active for the software path — it
only switches to hardware above that limit; the existing ≤4K path was not
touched.

## 2. What Shows Up During the Drag (Preview UX)

Not yet decided/validated on hardware:

- **Position on screen**: today the preview (when it exists) is a fixed rectangle
  above the seekbar, inside the controls panel (`VRControlsPresentation`). It is
  subject to the same problem as section 3 below — if the user is looking at the
  video, not at the panel, they do not see the preview.
- **Strip frequency**: today 1 frame every 15s (`SCRUB_INTERVAL_SECONDS` in
  `NetworkThumbnailGenerator.kt`) — not validated whether it is fine-grained
  enough for long videos (2h+ movies) or whether it should scale with the total
  duration.
- **Local (`MediaMetadataRetriever`)**: implemented as a "live" decode during the
  drag (throttled), never tested on hardware in that session — all the real test
  time was on SFTP content.
- **Fallback when the strip has not finished generating** (the first drag on a
  new video): today it shows nothing. It is worth considering a placeholder (a
  small spinner in place of the preview) instead of silence.

## 3. Visual Feedback (play/pause/±10s) Over the Video, Not Just on the Panel

**An explicit user request — implemented.** State (`FEEDBACK_EVENT`, a sequence +
a type in a single `AtomicU64`) in `rust/bridge/src/lib.rs`, exposed through
`get_playback_feedback_event()`, analogous to the existing `LOADING_COUNT`. Fired
from the same call point shared by all play/pause paths (the panel, the shortcut
outside the panel, the controller button) and by seek (±10s, seekbar drag) —
`toggle_play_pause()` and `seek_video_playback()`. Rendered as simple vector
geometry (triangle/bars/chevron, with no icon atlas/new texture) overlaid on the
video quad, with a fade following the same pattern as `uiTargetAlpha`/
`controlsTargetAlpha` (`kUiFadeDuration = 0.35f`, a 0.6s hold). GLES+Vulkan parity
confirmed through `assembleDebug` in both variants
(`native/src/vr_player_feedback_overlay.h`, `vr_player_app.cpp`,
`vr_player_app_vulkan.cpp`, `vr_player_input_vulkan.h`).

**Scope decision made**: since the only shared trigger point for seek is the
absolute position (`seek_video_playback`), the seekbar drag also fires the
chevron, not only the ±10s buttons — separating that would require a new
Kotlin→JNI entry point, outside the scope of that round. Kept that way
deliberately; adjust if it bothers in real use.

**Not validated on hardware**: the fade timing, the legibility/contrast of white
over the video, the anchoring in 360/180 modes (where there is no flat video
quad — the anchor uses the "flat screen" position at ~2m), and whether firing on
every seekbar drag is pleasant or noisy in practice.

## 4. Connection Reuse for FTP

Left out of the `ConnectionCache` (item 4 of the original session) because
`FtpFileSource` had no resilience to a stalled connection (no retry/reconnect on a
read error, unlike SFTP/SMB/HTTPS).

**Step 1 implemented**: `FtpFileSource::read_range` now reconnects and retries
once on a read error, the same pattern as `SftpFileSource::read_range`
(`rust/protocols/src/ftp/mod.rs`, a new `reconnect()` method reusing
`connect_and_login()`). With no per-request timeout yet (step 2 below) and no
dedicated integration test (there is no fault-injection fixture available in
`docker/network-tests/` for FTP or SFTP today). **Current:** `read_range` now
retries a failed chunk up to `FTP_CHUNK_RETRY_ATTEMPTS = 2` times with jittered
backoff (`ftp/mod.rs:331-360`).

Remaining, still pending:

2. A per-request timeout as well (the same reasoning as section 5 — the FTP
   control connection tends to be even more prone to hanging on a `RETR` with no
   response). **Current:** only `CONNECT_TIMEOUT` (8s) exists for FTP; there is
   still no per-read timeout.
3. Only after that does it make sense to add `ConnectionCache::Ftp(...)`.
   **Current:** still not added.

## 5. Read Timeout for SMB

**Updated after audit**: HTTPS is already covered — `base_client()`
(`rust/protocols/src/http.rs:64-71`) configures `.timeout(Duration::from_secs(10))`
on the `reqwest::blocking::Client`, which covers precisely the "the server accepts
but never responds" case. No additional work is needed here.

**Implemented**: `SMB_READ_TIMEOUT` (20s, the same value as SFTP's at the time) in
`rust/protocols/src/smb/mod.rs`, wrapping the existing `block_on(join_all(...))` in
`SmbFileSource::read_range` with `tokio::time::timeout` — the same as SFTP's
mechanism (which also uses `tokio::time::timeout` over `block_on`, not a
thread+channel as originally speculated). The **connection** timeout
(`client_config()`, 8s) was left untouched, as it is a different setting and
already correct. **No retry/reconnect on the timeout** (unlike SFTP) — it only
unblocks the `join()`, it does not try again. **Current:** SMB now retries a failed
chunk (`SMB_CHUNK_RETRY_ATTEMPTS = 2`, `smb/mod.rs:277-310`), but there is still no
reconnect on a timeout (`smb/mod.rs:253-270` returns the error).

## 6. Pending Hardware Validation

Nothing from the previous session (the loading spinner, `updateMediaState`, the
thumbnail strip for ≤4K videos, SMB/HTTPS connection reuse) had actually been
observed working on hardware yet — only the SFTP timeout and the frozen frame had
been exercised through real reproduction of the bug. A dedicated test pass
covering each item of the "Context" section above is worthwhile before
considering the previous session closed.

**Items of this round also pending hardware validation** (all only
compiled/build-verified, never run on the Quest 3): the 100ms pacing in the >4K
scrub (item 1), the feedback overlay over the video quad in both GLES/Vulkan
modes and in 360/180 modes (item 3), FTP reconnect on a really dropped connection
(item 4), the SMB read timeout against a real hang (item 5), the pause icon fixed
on screen instead of disappearing on its own (item 3), the removal of the
redundant flash on the controls panel, the keyframe-skip catch-up when the video
decode falls behind the requested speed (playback.rs, found through a real bug of
2x freezing on local 8K60 — the skip itself was never seen running).

**Item 1 (the scrub hardware decode) deserves special attention in validation**:
it is the item of greatest technical uncertainty of this round — it depends on the
Quest 3's Qualcomm decoder supporting a `MediaCodec` configured without a Surface
(`window: None`), which is not guaranteed on every Android hardware/driver
combination and can only be confirmed by really testing it. If
`generate_strip_hw()` fails silently (the module's silent contract: no error
popup, just no strip), it may go unnoticed — it is worth testing explicitly by
dragging the tracker on a local >4K file and confirming that the strip appears,
not just that the app does not freeze.
