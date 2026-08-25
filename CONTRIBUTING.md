# Contributing to tucaVR

Thanks for taking the time to contribute! tucaVR is a three-language project (Kotlin + C++ + Rust) targeting a physical VR headset, which makes it a little unusual to work on. This guide covers what you need to know before opening your first pull request.

By participating, you agree to keep the discussion respectful and constructive.

---

## Table of contents

- [Ways to contribute](#ways-to-contribute)
- [Setting up your environment](#setting-up-your-environment)
- [Knowing which layer you are in](#knowing-which-layer-you-are-in)
- [Coding style](#coding-style)
- [Cross-cutting rules that are easy to get wrong](#cross-cutting-rules-that-are-easy-to-get-wrong)
- [Testing your changes](#testing-your-changes)
- [Commits and branches](#commits-and-branches)
- [Opening a pull request](#opening-a-pull-request)
- [Reporting bugs and requesting features](#reporting-bugs-and-requesting-features)
- [Security issues](#security-issues)

---

## Ways to contribute

You do **not** need a Meta Quest 3 to contribute. Plenty of the project is testable on a plain laptop:

| No headset required | Headset required |
|---------------------|------------------|
| `rust/protocols` — SMB, NFS, FTP, SFTP, DLNA, HTTP(S), HLS clients | OpenXR rendering and the Vulkan/GLES render loop |
| `rust/media-logic` — A/V sync, resample math, playback clamps | Controller input and haptics |
| Kotlin pure logic (`app/src/test`) — sorting, navigation, formatting | Hardware `MediaCodec` decode |
| Docs, i18n strings, CI workflows | Anything about comfort, framerate or visual quality |

If you have a headset, reports that include `adb logcat` output and the exact media file characteristics (container, codec, resolution, 3D layout, source protocol) are especially valuable.

## Setting up your environment

Follow the [Requirements and Installation sections of the README](README.md#-getting-started). In short:

```sh
git clone https://github.com/bgluis/tucaVR.git
cd tucaVR
./scripts/setup-deps.sh     # clones ffmpeg-android-maker, checks the Meta SDK
```

Two things commonly trip people up on the first build:

1. **The Meta OpenXR Mobile SDK is not in the repository.** It requires accepting a license on Meta's portal, so you have to download and extract it into `sdk/meta-openxr-sdk/` yourself. Without it, the native C++ layer will not compile.
2. **FFmpeg has to be cross-compiled once** before the Rust build can link against it (see step 4 in the README). It takes several minutes but only needs to happen once.

If you only intend to touch `protocols` or `media-logic`, you can skip both: those crates build and test with plain `cargo test` on any host.

## Knowing which layer you are in

```
Kotlin (app/) <-JNI-> C++ (native/) <-C ABI-> Rust (rust/bridge -> core/protocols/audio/media-logic)
```

- **Kotlin** (`app/src/main/java/com/vrplayer/`) — the Android shell and the UI. The UI is drawn as plain Android `View`s inside an `android.app.Presentation` on a `VirtualDisplay`, and the native layer projects it onto 3D quads. It is **not** XML layouts as a screen hierarchy and **not** Jetpack Compose. Also owns credential storage, Room-backed history and i18n.
- **C++** (`native/src/`) — OpenXR session, swapchains and the render loop, built on Meta's `SampleXrFramework` (OVRFW). Vulkan is the default backend; the OpenGL ES path is kept as a real fallback (`-PvrplayerGraphicsApi=GLES`).
- **Rust** (`rust/`) — demuxing (`ffmpeg-next`), hardware decode (`ndk::MediaCodec`), audio (Oboe) and every network protocol client.

**The one rule that is non-negotiable:** Kotlin never calls Rust directly. Kotlin talks to C++ over JNI, and C++ is the only caller of the `bridge` crate's flat `extern "C"` API. Do not introduce a Kotlin → Rust UniFFI path — that was considered and deliberately rejected (ADR-002 in `docs/REQUIREMENTS.md`), because there is no call path where Kotlin needs to reach Rust without going through the per-frame render loop in C++.

### The Rust workspace

| Crate | What it is | Builds on a normal host? |
|-------|------------|--------------------------|
| `core` | Demuxer, MediaCodec decoder, playback/sync state | ❌ needs the NDK toolchain |
| `audio` | Oboe audio output | ❌ needs the NDK toolchain |
| `bridge` | The `cdylib`/`staticlib` that C++ links against | ❌ needs the NDK toolchain |
| `protocols` | SMB2/3, NFS, FTP, SFTP, DLNA, HTTP(S), HLS — all pure Rust | ✅ |
| `media-logic` | Pure logic, zero Android dependencies, by design | ✅ |

`media-logic` exists specifically so that sync, resample and playback-parameter logic can run under plain `cargo test`. **Before adding logic of that kind to `core`, check whether it belongs in `media-logic` instead** — `core` usually delegates rather than reimplementing. See `docs/TESTING-PLAN.md` section 2.

## Coding style

- **Rust** — `cargo fmt`, and `cargo clippy -- -D warnings` must pass. CI enforces the clippy gate.
- **Kotlin** — `./gradlew ktlintCheck`. It is currently non-blocking in CI, but please keep it clean anyway.
- **C++** — C++20, matching the surrounding file. Respect the OVRFW shader conventions: the framework injects `FragmentHeader`/`VertexHeader` into custom shaders, so things like `fragColor` and `TransformVertex` are already declared for you.
- **Comments and docs are written in Portuguese (BR).** That is the existing convention across the codebase — match it when adding comments to existing files. Issues, PRs and this guide are in English.
- Prefer explaining *why* in a comment over restating *what* the code does. The existing comments tend to record the reasoning behind a version pin or a workaround; that style has been genuinely useful here.

## Cross-cutting rules that are easy to get wrong

**Screen/stereo mode enum.** The numeric encoding for 2D/SBS/OU/360/180 variants must stay in sync across **three** places:

1. the `SCREEN_MODE` comments in `rust/bridge/src/lib.rs`
2. `enum class ScreenMode` in `native/src/vr_player_app.cpp`
3. the positional lookup in `modeLabelResIds` in `VRControlsPresentation.kt`

Changing one without the others produces a silently wrong projection, not a compile error.

**i18n.** UI strings live in `app/src/main/res/values/strings.xml` (English, default) and `app/src/main/res/values-pt-rBR/strings.xml` (Portuguese, mirroring the key order exactly so the two diff side by side). Interpolated strings use positional placeholders (`%1$s`, `%1$d`) through `getString(R.string.xxx, arg1, …)` — **never** Kotlin string concatenation, so that argument order can change per locale. See `docs/i18n.md`, including the section on adding a new locale.

**Credentials.** Server passwords go through `EncryptedSharedPreferences` (see `app/src/main/java/com/vrplayer/network/`). Never store or log a credential in plain text, and make sure URIs are redacted before they reach a log line.

## Testing your changes

```sh
# Rust — host-testable crates only
cd rust && cargo test -p protocols -p media-logic
cd rust && cargo clippy -- -D warnings

# A single Rust test
cd rust && cargo test -p media-logic sync::tests::some_test_name

# Kotlin JVM unit tests
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests "com.vrplayer.filebrowser.MediaSorterTest"

# Kotlin lint
./gradlew ktlintCheck
```

**Network protocol integration tests** run against real SMB/HTTP/HTTPS/FTP/SFTP servers in Docker. They are `#[ignore]`d by default and need docker, the compose plugin, curl and sha256sum:

```sh
./scripts/test-network-protocols.sh          # up, run, tear down
./scripts/test-network-protocols.sh --keep   # leave containers up for debugging
```

Read `docker/network-tests/README.md` before touching `docker-compose.yml` — it documents the per-protocol gotchas (FTP passive-mode addressing, SFTP chroot ownership rules, TLS certificate `basicConstraints`, samba `-s` field ordering) that will otherwise cost you an afternoon.

**Be honest about what you verified.** Anything involving actual OpenXR rendering, controller haptics or hardware `MediaCodec` decode has no automated coverage. If you did not run it on a physical Quest 3, say so explicitly in the PR rather than implying it was validated. `docs/DEBUGGING.md` covers the on-device debug HUD, forcing a `ScreenMode` over adb without having a file in that format, transition logging and the optional Vulkan validation layers — all debug-build-only.

There are also longer-running scripts for stability and memory work: `scripts/soak-test.sh`, `scripts/test-4k-memory.sh` and `scripts/generate-4k-test-clip.sh`.

## Commits and branches

- `main` is the release branch; `develop` is the integration branch. Branch your work off `develop`.
- Name branches by intent: `feat/spatial-audio`, `fix/smb-reconnect`, `docs/readme`.
- Write commit messages in the [Conventional Commits](https://www.conventionalcommits.org/) style where it fits — `feat(player): …`, `fix(build): …`, `docs: …`. Scope names generally track the layer or module you touched.
- Keep unrelated changes out of the same commit. A build fix and a feature are two commits.

## Opening a pull request

1. Make sure the checks above pass locally.
2. Target `develop` unless you are fixing something that must go straight to a release.
3. Fill in the [pull request template](.github/PULL_REQUEST_TEMPLATE.md) — in particular the "How has this been tested?" section, including whether you tested on a real headset.
4. For anything visual, attach a screenshot or a short clip captured from the Quest. Describing a rendering change in prose rarely survives review.
5. CI (`.github/workflows/main.yml`) runs, in order: `cargo clippy -D warnings`, `cargo test -p protocols -p media-logic`, `ktlintCheck` (non-blocking) and `./gradlew testDebugUnitTest`. The full native build runs in a separate job that needs the licensed Meta SDK.

Small, focused pull requests get reviewed much faster than large ones. If you are planning something substantial — a new protocol, a rendering change, a new phase task — open an issue first so the approach can be discussed before you invest the time.

## Reporting bugs and requesting features

Use the issue templates:

- [🐛 Bug report](.github/ISSUE_TEMPLATE/bug_report.yml) — include the commit or version, the headset model, the graphics API in use, reproduction steps and `adb logcat` output.
- [✨ Feature request](.github/ISSUE_TEMPLATE/feature_request.yml) — describe the problem first, then the proposed solution, keeping VR interaction in mind (controllers, immersion, 3D panels).

For questions and setup help, please use [Discussions](https://github.com/BGLuis/tucaVR/discussions) rather than opening an issue.

Before filing, it is worth checking `docs/REQUIREMENTS.md` and `docs/phases/` — many features are already specified with a task ID and a target phase, so your request may be a matter of prioritization rather than a new idea.

## Security issues

Please do **not** open a public issue for a security vulnerability — especially anything touching credential storage or the network protocol clients. Report it privately to the maintainer instead, and allow reasonable time for a fix before disclosing.

---

Happy hacking! 🥽
