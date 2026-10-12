# tucaVR — Docker Build

Builds the debug APK inside a container, with no Android SDK, NDK, Rust or
FFmpeg installed on the host. The container delegates to `scripts/build.sh`, the
same pipeline as the local build and CI.

## Prerequisites

1. A working **Docker** (or Podman): <https://docs.docker.com/engine/install/>.
2. The **Meta OpenXR SDK** in `sdk/meta-openxr-sdk/`. Run
   `./scripts/setup-deps.sh` to download the latest public GitHub release:
   <https://github.com/meta-quest/Meta-OpenXR-SDK/releases>
3. A **normal checkout or clone** of the repository. `git worktree` does not
   work: a worktree's `.git` points to a path that does not exist in the
   container (the entrypoint detects this and warns).

## Usage

From the project root:

```bash
./scripts/docker-build.sh            # builds the image (cached) and compiles the APK
./scripts/docker-build.sh --clean    # discards build caches and recompiles everything
./scripts/docker-build.sh --rebuild  # rebuilds the image from scratch (--no-cache --pull)
./scripts/docker-build.sh --shell    # interactive shell in the container
make docker-build                    # shortcut for the first one
```

The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.

`APP_VERSION_NAME` and `APP_VERSION_CODE`, if set on the host, are passed to
Gradle. `DOCKER=podman ./scripts/docker-build.sh` uses another runtime (the
rootless Podman path has not been tested).

To confirm a change with a build that has no previous artifacts, use
`./scripts/docker-build.sh --clean`. That cleans the Cargo target and the
Android/CMake build state before compiling again. On the host, the equivalent is
`./scripts/build.sh --clean`. The normal build already tracks changes and
recompiles the affected files; the clean option is for diagnosis or double-checking,
and is not needed after every edit.

## Building From Android Studio

Android Studio does not need Docker: the `buildRust` Gradle task
(`scripts/build-rust.sh`) runs automatically while the APK is assembled. In the
default `auto` mode it uses Docker when the host has no Rust toolchain and builds
directly on the host when it does; override it with `-PrustBuildMode=docker`,
`-PrustBuildMode=host` or `-PrustBuildMode=skip` (or the `RUST_BUILD_MODE`
environment variable).

Use **Build > Make Project** (or run `assembleDebug` in the Gradle window). The
host always needs the JDK and the Android SDK/NDK configured in the project; C++
is compiled by CMake/the Android Gradle Plugin. For **host mode** you additionally
need Rust with `cargo-ndk` installed (`cargo install cargo-ndk`) and FFmpeg
compiled as in the installation step of the `README.md` (`setup-deps.sh` downloads
the sources but does not compile FFmpeg); in Docker mode, Rust and FFmpeg are
compiled inside the image. For a build with no previous artifacts, run the Gradle
`clean` task and then `assembleDebug` in Android Studio; `clean` also cleans the
Cargo artifacts.

## How It Works

- **Multi-stage**: `sdk`, `ffmpeg` and `rust` are independent (BuildKit builds
  them in parallel). The final image carries only what is needed to compile: no
  `nasm`/`yasm`/`curl`/`wget`, no `ffmpeg-android-maker` `.git` and no FFmpeg
  sources.
- **Non-root**: the container runs with the host's UID/GID (`--user`), so
  everything it generates is already yours, with no `chown` and no root-owned
  files in the project. It also uses `--cap-drop ALL` and `no-new-privileges`.
  Without `--user`, the image uses `10001:10001` (which does not write to the
  mounted project).
- **Switching with Android Studio**: before the build, the script saves the host's
  native state (`app/.cxx` and `app/build/intermediates/cxx`) and restores it on
  exit. If a cache is detected with a Ninja path unavailable on the host, it is
  removed and recreated in the container. That way, the container's absolute SDK
  paths do not break `clean` or Android Studio builds.
- **Nothing is written outside the caches and the project**: the prebuilt FFmpeg
  lives in `/opt/ffmpeg-android-maker` and `build.sh` finds it through
  `FFMPEG_MAKER_DIR`; there is no symlink inside your project.
- **Reproducible**: an Ubuntu base by digest, with Rust, `cargo-ndk`, `rustup-init`
  and `commandlinetools` pinned (the last two with SHA-256), and FFmpeg at the same
  commit as `scripts/setup-deps.sh`. Update the Dockerfile `ARG`s on purpose.
  Note that CI's Rust job uses the `stable` toolchain, while this image pins the
  version listed below.

## Image Contents

| Component | Version |
|-----------|---------|
| Ubuntu | 22.04 (by digest) |
| JDK | 17 (OpenJDK headless) |
| Android SDK | Platform 34, build-tools 34.0.0 |
| Android NDK | 26.3.11579264 |
| CMake | 3.22.1 (via the SDK) |
| Rust | 1.97.1 + `aarch64-linux-android` |
| cargo-ndk | 4.1.2 |
| FFmpeg | `arm64-v8a`, API 26 |

## Caches

Named volumes persist between builds:

| Volume | Contents |
|--------|----------|
| `tucavr-gradle-cache` | Gradle dependencies and cache (`/cache/gradle`) |
| `tucavr-cargo-cache` | Crate registry and git (`/cache/cargo`) |

To clean them: `docker volume rm tucavr-gradle-cache tucavr-cargo-cache`.

## Manual Use (without the script)

```bash
docker build -t tucavr-builder docker/build/
docker run --rm --init --user "$(id -u):$(id -g)" \
  --cap-drop ALL --security-opt no-new-privileges:true \
  -v "$(pwd)":/project \
  -v tucavr-gradle-cache:/cache/gradle \
  -v tucavr-cargo-cache:/cache/cargo \
  tucavr-builder
```

## Troubleshooting

- `/project is a git worktree`: use a full clone (see Prerequisites).
- `Meta OpenXR SDK not found`: run `./scripts/setup-deps.sh`.
- A corrupted or slow cache: delete the volumes above and run with `--rebuild`.
