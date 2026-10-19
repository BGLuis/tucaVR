# Network I/O Performance for Remote Playback

> A record of the work of one session: the investigation of degraded 8K60
> playback over SFTP, the redesign of the prefetch buffer and the
> parallelization of reads for the 4 protocols (SMB, SFTP, HTTP/S, FTP), the
> regression found on real hardware and the fix applied. Like the rest of the
> repo, this document records what was **validated** (automated tests + Docker)
> vs. what still depends on the **physical Quest 3** — see
> `docs/TESTING-PLAN.md`.
>
> This is a session record, so the numbers and file lists describe the code as it
> was then. Where the code has since moved on, the text carries a **"Current:"**
> note (test counts, the Vulkan image cache limit, per-chunk retry coverage).

## 1. Motivation

An 8K60 HEVC video streamed over SFTP degraded progressively during playback on
the Quest 3. Evidence that ruled out the initial hypothesis ("thermal
ceiling/hardware decode"):

- The same file, in **another player**, plays fine over SFTP on the same server
  — rules out a server problem.
- A **local** 4K60 video (no network) plays normally in our player — rules out a
  general decode/render problem.
- MKV is not yet supported by the application, so the comparative test was
  restricted to raw HEVC.

Conclusion: the bottleneck was **this project's SFTP client implementation**,
not the hardware, the decode or the server.

## 2. Diagnosis

Root cause in `rust/protocols/src/sftp/mod.rs`
(`SftpFileSource::try_read_range`, before this session): the `SSH_FXP_READ` loop
was **entirely sequential** — each read of up to `SFTP_MAX_READ_CHUNK` (~252KB)
waited for the response before firing the next, even though
`RawSftpSession::read` takes `&self` (not `&mut self`) *precisely* to allow
concurrent positional calls multiplexed by request-id over the same SSH session
— confirmed by reading the `russh-sftp` crate's source
(`~/.cargo/registry/.../russh-sftp-2.4.0/src/client/rawsession.rs`), not just the
docs. `smb2::client::stream::FileReader::read_at` has the same property
(multiplexed by `MessageId`), explicitly documented in the crate's own
doc-comment.

A second, orthogonal finding: `PrefetchReader`
(`rust/protocols/src/prefetch.rs`) was a purely **pull** cache — it only fetched
the next 4-12MB block when the current one ended, with no background read-ahead
at all. This affects all 4 protocols equally, not just SFTP.

A third, also orthogonal finding (found while reviewing the 4 protocols
together, unrelated to the original SFTP case): `FtpFileSource::read_range`
(`rust/protocols/src/ftp/mod.rs`) made a single `Read::read()` call with no loop
— a TCP socket can "short-read" (return less than requested without it being
EOF), which silently undermined the block size `PrefetchReader` assumed.

## 3. Scope Decision

Explicit instruction: do not do just the minimal SFTP fix — use the opportunity
to bring **all** network protocols up to the same performance standard (at least
in the same-LAN scenario, server and Quest on the same network), with
host-testable tests written **before** the implementation. Without chasing
hypothetical throughput goals unrelated to the real problem (e.g. "16K@100fps"
was mentioned only as an illustrative ceiling, never implemented as a test
goal).

## 4. What Was Implemented

### 4.1 `PrefetchReader` — real background read-ahead (`prefetch.rs`)

Redesigned to own a **dedicated background thread** that:

1. Is kicked off at construction (`kick_prefetch(0)`), fetching block 0 before
   the first `read()`.
2. For each block consumed by the main thread, fires in the background the fetch
   of the **next** sequential block (`ensure_cache` → `kick_prefetch`), overlapping
   the network RTT of the next block with the consumption/decode time of the
   current block — instead of adding them, as in the previous "pull" design.
3. A seek outside both the current block **and** the block being prefetched
   invalidates the in-flight prefetch (the stale result is discarded when it
   arrives — there is no real cooperative cancellation of a network read already
   in progress).
4. `Drop` sends `Shutdown` to the thread and does a synchronous `join()`,
   deterministically closing the network connection (inside the worker).

Communication with the worker is through `std::sync::mpsc`
(`Command::Fetch`/`Shutdown` → `io::Result<Block>`), at most 1 request in flight
at a time (simple double-buffering: current block + next block).

This benefits **all** protocols equally (it is purely client-side, agnostic of
SMB/SFTP/HTTP/FTP) — it is the "buffer" improvement that helps the player in
general, in addition to each protocol's specific parallelization.

### 4.2 `chunking::split_range` — pure range splitting into sub-chunks (new, `chunking.rs`)

A deterministic function, with no I/O, used by the 3 protocols below to decide
into how many pieces (and of what size) to split a `read_range` before firing
the real reads:

```rust
pub fn split_range(offset: u64, total_len: u32, chunk_size: u32) -> Vec<(u64, u32)>
```

### 4.3 Concurrent Reads per Protocol

| Protocol | Mechanism | Chunk size | Concurrency |
|---|---|---|---|
| SMB (`smb/mod.rs`) | `FileReader::read_at(&self, ...)` through `futures_util::future::join_all` inside the existing `block_on` | 1MB (`SMB_CONCURRENT_CHUNK_SIZE`) | up to 4 in flight (`SMB_MAX_CONCURRENT_CHUNKS`) |
| SFTP (`sftp/mod.rs`) | `RawSftpSession::read(&self, ...)` through `join_all` | ~252KB (`SFTP_MAX_READ_CHUNK`, already existed) | up to 4 in flight (`SFTP_MAX_CONCURRENT_CHUNKS`) |
| HTTP/S (`http.rs`) | `std::thread::scope` + a cloned `reqwest::blocking::Client` (shared connection pool) | 2MB (`HTTP_CONCURRENT_CHUNK_SIZE`) | up to 4 threads in flight (`HTTP_MAX_CONCURRENT_CHUNKS`) |
| FTP (`ftp/mod.rs`) | **no parallelism** — a deliberate decision (see 4.4) | — | — |

In all three, the chunks of a `read_range` are processed in **batches** of up
to N (today N=4 in all three — see section 6, that is the post-regression
fix), with concurrent `join_all`/`thread::scope` **inside** each batch and the
batches themselves sequential. A short chunk/EOF in the middle of a batch stops
the reassembly there (the chunks already fetched after it are discarded) — the
same short-read semantics that `PrefetchReader` already expected.

### 4.4 FTP — Short-Read Fix, No Parallelism (`ftp/mod.rs`)

`FtpFileSource::read_range` now loops over `Read::read()` until it fills the
requested buffer or hits EOF, instead of a single call (which could return much
less than the requested 4-12MB block, without signaling an error).

A deliberate decision **not** to parallelize FTP: the protocol does not
multiplex requests over a single control connection like SMB2/SFTP — real
parallelism would require multiple simultaneous data connections (far more
invasive), for a protocol that is already the project's secondary/legacy one (no
TLS at the time; see `docs/phases/PHASE-0.2-3D-NETWORK.md` section 6 for the FTP
scope). Contiguous sequential reading is already nearly optimal for FTP (a
continuous stream, with no per-chunk request overhead once the `RETR` is open).
**Current:** FTP now also reconnects and retries a failed chunk
(`FTP_CHUNK_RETRY_ATTEMPTS = 2`, `ftp/mod.rs`).

### 4.5 New Dependency

`futures-util = { version = "0.3", default-features = false, features = ["alloc"] }`
in `rust/protocols/Cargo.toml` — only for `future::join_all` (it needs no
executor/runtime of its own: it runs inside the `block_on` that SMB/SFTP already
used). It was already in `Cargo.lock` transitively (through `russh`/`reqwest`),
so it does not add a new dependency tree.

## 5. Tests Written (before the implementation, as requested)

### 5.1 Host-Testable, No Network/Docker (`cargo test -p protocols`)

`prefetch.rs` (7 new/adapted tests at the time):

- `background_prefetch_overlaps_network_delay_with_consumption` — **proves by
  measurement** (with an artificial delay + `Instant`) that the total time to
  read N blocks with a slow consumer stays well below
  `N * (network_delay + consumption_time)`, comparing against a "no overlap"
  baseline computed in the test itself.
- `seek_during_pending_prefetch_returns_correct_data` — seeking far away while a
  sequential prefetch is in flight does not corrupt the following read.
- `mixed_sequential_and_random_access_matches_reference` — a mixed seek pattern
  compared byte by byte against a reference buffer.
- `drop_joins_background_thread_without_hanging` — `Drop` ends the thread
  synchronously, with a timeout in the test itself so as not to hang the suite if
  there is a deadlock regression.
- `source_error_propagates_to_read_caller` — a source error is not swallowed.
- `sequential_reads_hit_one_block_per_prefetch` / `seek_and_read` — adapted
  versions of the original "pull" cache tests.

`chunking.rs` (6 tests at the time): edge cases of `split_range` (empty, smaller
than a chunk, an exact multiple, a remainder, a 1-byte range, exact contiguous
coverage of a large, "ugly" range — an offset/size that are not multiples of the
chunk).

Total at the time: **33 tests** in `cargo test -p protocols` (besides the
already-existing per-protocol URI/redaction ones), **0 failures**, running in
<1s. **Current:** `prefetch.rs` has 14 tests, `chunking.rs` 7, and
`rust/protocols/src` as a whole about 83; `rust/media-logic` about 224.
(Counted by grepping `#[test]`, not by running them for this document.)

### 5.2 Integration Against Real Servers via Docker (`./scripts/test-network-protocols.sh`)

The existing `#[ignore]` tests in `rust/protocols/tests/*_integration.rs` (a
sha256 comparison of the whole file reassembled through `PrefetchReader` + each
`*FileSource`) already serve as a correctness regression for the new concurrent
implementations — they did not need to be rewritten, only to keep passing with
the new code underneath. **18/18 passed** at the time against SMB (real Samba),
SFTP (real OpenSSH, password and key), HTTP and HTTPS (real nginx) and FTP (real
vsftpd) — confirming that the parallelization and the short-read fix corrupted
nothing. **Current:** the suite has grown to 33 `#[ignore]`d integration tests
across `smb`, `sftp`, `http`, `https`, `ftp`, `webdav` and `dash`.

A collateral finding (infrastructure, unrelated to the protocol logic): the
`ftp-test` container would not start — `docker-compose.yml` mounted `./fixtures`
as `:ro` directly over the home directory that the image's entrypoint
(`delfer/alpine-ftp-server`) needs to write to (`adduser`/`chown`) at boot. Fixed
by removing the `:ro` (an ephemeral test container, with no real risk).

## 6. Regression Found on Real Hardware + Fix

The first version of this optimization (the chunks of a `read_range` fired **all
at once**, with no limit) was only validated through Docker/localhost before
deploying. On the physical Quest 3, testing the same 8K60 video over SFTP, the
numbers got **worse** than the baseline before this session:

| Metric | Before this session | After the 1st version (no limit) |
|---|---|---|
| `vidFps` | ~30 (20 in 180° mode) | fixed at 10 |
| `decFps` | — | average 10 (varying 7–14) |
| `jitter` | ~200ms | ~300ms |

A 12MB block (`REMOTE_PREFETCH_BLOCK_SIZE` in `core/src/demuxer.rs`) divided
into ~252KB chunks for SFTP yields **~48 chunks** — the first version fired all
48 concurrently through `join_all`. Two plausible causes (not confirmed by logs —
no access to `adb logcat` at that moment):

1. **CPU contention with the decode thread**: every `SSH_FXP_READ` costs real SSH
   encryption (CPU, unlike video decode, which runs on dedicated hardware).
   Before, that CPU cost happened serialized with the decode (the decode thread
   slept while the network read ran). With the background read-ahead, that work
   started running **concurrently** with the active decode — a burst of 48
   requests at once may have competed for CPU with the decode thread far more
   than the design intended.
2. **Exceeding a server-side/SSH-channel limit**: a burst of that size can
   overflow the SSH channel's flow window or some server limit. Since
   `SftpFileSource::read_range` reconnects from scratch (a full SSH handshake +
   auth + reopening the file) and retries the entire read as soon as **any**
   chunk fails, a single failure in that scenario becomes a much more expensive
   event than the RTT the concurrency was supposed to save — indistinguishable,
   from the HUD's point of view, from a large stall.

### Fix Applied

The same idea in the three protocols that parallelize (SMB, SFTP, HTTP/S): the
chunks of a `read_range` came to be processed in **batches of up to 4**
(`SFTP_MAX_CONCURRENT_CHUNKS` / `SMB_MAX_CONCURRENT_CHUNKS` /
`HTTP_MAX_CONCURRENT_CHUNKS`, all = 4 today), concurrent within the batch, the
batches themselves sequential — still a pipeline (4x better than pure
sequential), without the burst of dozens of simultaneous requests.

Also added `log::warn!("SftpFileSource: reconectando apos falha de leitura")`
("reconnecting after a read failure") in `SftpFileSource::reconnect` — if cause
(2) above is the real one, it will show up repeatedly in the log during a stall.

**State**: the fix was reapplied and re-verified through `cargo test -p
protocols` (33 tests) and `./scripts/test-network-protocols.sh` (18 tests, real
servers) — both went back to passing 100%. **Not yet tested on the physical Quest
3** — it was the next step before considering this work done. If the problem
persisted, the reconnect log above would help decide between lowering the
concurrency limit even further (cause 1) or revising SFTP's error/reconnect
handling (cause 2).

## 7. Following Session: Root Cause Confirmed on Hardware + Fixes

A new session, with a physical Quest 3 connected (`adb devices`) and access to
`adb logcat` — what was missing in the previous session to confirm or refute
hypotheses (1)/(2) of §6.

### 7.1 The Missing A/B

The same 8192×4320 file, the same Vulkan build, two scenarios:

| Scenario | `decFps` | `vidFps` |
|---|---|---|
| Local (Quest storage, no network) | 60 | 50–55 |
| SFTP | 17 | 10 |

This refutes the "burst of 48 requests" hypothesis of §6 as the main cause: the
APK that produced the 10fps numbers (`app-debug.apk`, the 22:55 build) already
contained the batch-of-4 fix (`SFTP_MAX_CONCURRENT_CHUNKS`, source files edited
at 22:52–22:53) — the mitigation was already active and the regression
persisted. The logcat of the 23:11 session also shows **0 `stutter` events
(>20ms) and 0 `FREEZE` (>250ms)** in the render loop, against 26 `judder` (a
video frame stuck on screen) — the Vulkan/render path is cleared as the main
bottleneck; the problem is the network client's sustained throughput (~2.2 MB/s
effective against the ~7.85MB/s that the 8K60 stream requires).

### 7.2 Two Instrumentation Problems Found

1. **`decFps` did not measure the decoder.**
   `HwDecoder::release_output_frames_with_sync` (`decoder.rs`) only counted a
   frame as "decoded" (`TextureOutput::frames_decoded`) when the sync callback
   decided to RENDER — frames that MediaCodec produced but that were dropped for
   lateness did not enter the count. `decFps ≈ vidFps` was therefore an artifact
   of the metric, not proof that the decode was healthy.
2. **The SFTP reconnect `log::warn!` (§6) never reached logcat** — no `log`
   backend (`android_logger` or similar) was initialized anywhere in the
   workspace; every log that did show up went through raw `__android_log_print`.

### 7.3 Fixes Applied

**New instrumentation** (`decoder.rs`, `texture.rs`, `playback.rs`,
`prefetch.rs`, `demuxer.rs`, `bridge/lib.rs`, the HUD in
`vr_player_input_vulkan.h`/`vr_player_app.cpp`):

- `HwDecoder::metrics()` — REAL counters of MediaCodec output
  (`frames_output`) and of drops for lateness (`frames_dropped`), incremented
  BEFORE the sync callback, regardless of the result.
- `PrefetchStats` (`prefetch.rs`) — bytes read, the duration of the last block
  fetch, blocks fetched/discarded, exposed through `PrefetchReader::stats()`.
- The demux→decode queue depth exposed through a clone of the `Sender`
  (`.len()`).
- The HUD gained `outFps`, `drop`, `net=X MB/s`, `q=N` next to the existing
  `decFps`/`vidFps`, in both paths (Vulkan and GLES).
- Fixed a real counting bug: `get_video_frames_decoded_count()` (and the new
  analogous functions) returned `0` when `CONTROLLER.try_lock()` failed from
  contention — C++ computes a `uint64_t` delta against the last sample, and `0`
  produced a wraparound and a spurious `decFps` spike in the next sample. They
  now return the last known value (delta = 0).
- `Demuxer::read_packet` now distinguishes EOF from a real I/O error (before the
  two cases were indistinguishable — `Input::packets()` returns `None` for both
  — and a network failure became a silent restart to the beginning of the
  file). The error is now logged; the recovery (a seek to the beginning) stays
  the same for now.

**I/O thread priority** (`prefetch.rs`, `raise_io_thread_priority`) — the
initial suspect for the ~2.2MB/s: no thread in the workspace set an explicit
priority/affinity. Best-effort through `libc::setpriority(PRIO_PROCESS, 0, -10)`,
logged (`nice before -> after`). **Refuted on hardware (§7.4 below)**: kept in
the code as a low-risk best-effort (it is not the cause, but neither does it
hurt), yet the doc should no longer be read as "this fixed the throughput" — it
did not.

**A seek no longer triggers chained speculative prefetch**
(`PrefetchReader::ensure_cache`) — a non-sequential miss (a real seek) no longer
fires the fetch of the next block immediately; it only fires on the first
following access that confirms the read continues inside the block the seek just
installed. This prevents a sequence of chained seeks (the container probe, cues
at the end of the file) from paying for prefetches that the next seek was only
going to discard — without true cancellation of in-flight I/O (which would
require a second connection), this remains the limit of `ensure_cache`,
documented in the module header.

**SFTP: an error in one chunk no longer takes down the whole batch**
(`SftpFileSource::try_read_range`) — before, any chunk that failed inside a
batch of 4 concurrent ones propagated the error to `read_range`, which
reconnected from scratch (a full SSH handshake) and redid the entire 12MB,
discarding the other successful chunks. It now repeats only the chunk(s) that
failed (up to `SFTP_CHUNK_RETRY_ATTEMPTS = 2` times) before escalating to a full
reconnect.

**Vulkan image cache fixed** (`vr_player_app_vulkan.cpp`) — confirmed in logcat:
`evicao de entrada antiga` ("evicting an old entry") + `importado
AHardwareBuffer` ("imported AHardwareBuffer") on EVERY frame, ~10 distinct
pointers circulating against `kVideoImageCacheLimit = 6`, and the eviction used
`unordered_map::begin()` (an arbitrary hash bucket, not the truly oldest, even
though the comment said "LRU"). The limit was raised to 16 and a real LRU
implemented through a usage stamp (`VideoFrame::lastUsedFrame`). **Current:**
`kVideoImageCacheLimit = 32`.

**State**: `cargo test -p protocols -p media-logic` (69+33 tests), `cargo clippy
-p protocols -p media-logic -- -D warnings` and
`./scripts/test-network-protocols.sh` (18 tests, real servers) — all passing.
Full cross-compile verified through `cargo ndk ... build --release` (the whole
workspace) and through `./gradlew assembleDebug` in both graphics variants
(`-PvrplayerGraphicsApi=VULKAN` and `=GLES`).

### 7.4 Hardware Test: Thread Priority Refuted, Real Root Cause Found

With the new HUD installed on the Quest 3, the decisive test that was missing
(§1) was finally possible: the SAME file, over SMB, played smoothly (`net=11.2`
to `12MB/s`, the same as local) — **with no thread-priority change involved in
that path**, since SMB uses exactly the same `PrefetchReader` infrastructure.
That refutes the XR2 Gen 2 core-scheduling hypothesis as the cause of the low
throughput: if it were that, SMB would have the same problem.

The log with the new `blockFetchMs`/`blocksFetched` (§7.3) pointed to the real
cause immediately: during SFTP, `blockFetchMs` stayed at 5-10ms (it should be
~500ms for a 12MB block, which SMB indeed showed) and `blocksFetched` climbed
~80/s — simple arithmetic (`netMBs / blocksFetched`) gave **~30KB per "block"**,
neither the intended 12MB nor the ~252KB of `SFTP_MAX_READ_CHUNK`.

Root cause: `SftpFileSource::try_read_range` (`sftp/mod.rs`) requested each chunk
at up to 252KB, but when the server returned LESS than requested — allowed by the
SFTP protocol without signaling an explicit EOF, the code's own comment already
cited that possibility — the code treated that as the end of data and stopped
reassembling the block there (`break 'batches`). The NAS's SFTP server used in
the test apparently limits each `SSH_FXP_READ` response to around 30KB, well
below what the client requested. Every "block" exchange paid the full overhead of
an SSH round trip/mpsc channel/thread wake-up for a tiny fraction of the data —
that, not CPU or priority, was the real bottleneck since before this session
(inherited from before `3d682c0`, only never measured with enough granularity to
show up).

**Fix**: a new function `SftpFileSource::read_chunk_filling` — instead of a
single `raw.read(handle, chunk_offset, chunk_len)` call, it loops, continuing to
call `raw.read()` for the REMAINDER of the current chunk until it fills it
completely, and only really stops on an explicit EOF (`StatusCode::Eof`) or a
real error. Applied to both `raw.read()` calls inside `try_read_range` (the
initial batch and the chunk retry).

**Validated on real hardware**: with the fix, `net=12MB/s` over SFTP — the same
as SMB and local — and the video's frame rate matched local playback. **The
30fps→10fps regression is resolved.**

Tests: `cargo test -p protocols -p media-logic` (69+33) and
`./scripts/test-network-protocols.sh` (18 tests, real servers, including a
sha256 check of the whole file over SFTP — no corruption introduced by the new
loop) — all passing.

## 8. Files Touched

```text
rust/protocols/Cargo.toml                  (+ futures-util, + libc)
rust/protocols/src/lib.rs                  (+ pub mod chunking)
rust/protocols/src/chunking.rs             (new — split_range + tests)
rust/protocols/src/prefetch.rs             (redesign: background thread + tests;
                                             following session: PrefetchStats, thread
                                             priority, no speculative prefetch after a seek)
rust/protocols/src/smb/mod.rs              (concurrent read_range in batches)
rust/protocols/src/sftp/mod.rs             (concurrent read_range in batches + reconnect log;
                                             following session: per-chunk retry before
                                             reconnecting; following session, real root
                                             cause: read_chunk_filling — no longer treats
                                             a short read as EOF)
rust/protocols/src/http.rs                 (concurrent read_range in batches; following
                                             session: fix for a pre-existing collapsible_if lint)
rust/protocols/src/ftp/mod.rs              (short-read fix, no parallelism)
docker/network-tests/docker-compose.yml    (fix for the ftp-test :ro mount)
rust/core/src/decoder.rs                   (following session: HwDecoder::metrics())
rust/core/src/texture.rs                   (following session: removes a per-frame log)
rust/core/src/demuxer.rs                   (following session: ReadPacketOutcome,
                                             network_stats, caller fix in thumbnail.rs)
rust/core/src/thumbnail.rs                 (following session: adapts to the new read_packet)
rust/core/src/playback.rs                  (following session: instrumentation getters)
rust/bridge/src/lib.rs                     (following session: new getters + fix for the
                                             wraparound bug on lock contention)
native/src/vr_player_app_vulkan.cpp        (following session: new HUD, real LRU for the
                                             image cache, limit raised to 16)
native/src/vr_player_input_vulkan.h        (following session: new HUD)
native/src/vr_player_app.cpp               (following session: new HUD, GLES parity)
rust/bridge/Cargo.toml                     (following session: + log, for AndroidLogger)
app/src/main/java/com/tucavr/VRPresentation.kt
                                            (following session, found during the test: the
                                             URL/SMB/FTP/SFTP "Network" tabs had no
                                             ScrollView — the server registration form left
                                             the save button outside the visible/touchable
                                             1024x768 area; fixed with the same ScrollView
                                             pattern already used in the directory listings)
```

## 9. What Was NOT Done (outside the scope of that session)

- No hypothetical throughput goal (e.g. "16K@100fps") became a real test —
  discarded by explicit instruction as irrelevant to the problem.
- Multi-connection parallelism for FTP (see 4.4 — a deliberate decision).
- Tuning of `pool_max_idle_per_host`/fine-tuning of the `reqwest::blocking::Client`
  beyond the default (it did not prove necessary for the tests done).
- Making the `PrefetchReader`/`REMOTE_PREFETCH_BLOCK_SIZE` block size adaptive —
  today it stays fixed at 12MB for remote (`core/src/demuxer.rs`). It may become a
  next step if hardware validation shows it is still not enough.
- Real cooperative cancellation of a network read already in flight (see 7.3, the
  seek fix) — it would need a second connection or cancellation infrastructure at
  the protocol level; not implemented.
- Per-chunk retry (7.3) applied only to SFTP at the time — SMB and HTTP did not
  have the same "an error in one chunk takes down the whole batch" pattern, but
  were also not audited in depth for that class of problem in that session.
  **Current:** per-chunk retry now also exists for SMB
  (`SMB_CHUNK_RETRY_ATTEMPTS = 2`, `smb/mod.rs`), HTTP
  (`HTTP_CHUNK_RETRY_ATTEMPTS = 2`, `http.rs`) and FTP
  (`FTP_CHUNK_RETRY_ATTEMPTS = 2`, `ftp/mod.rs`).
- Fine-tuning which `nice` value is ideal for `vrplayer-prefetch-io` (`-10` is an
  initial guess) or core-affinity pinning — a deliberate decision to keep things
  simple and let hardware validation guide the next step, see 7.3.
