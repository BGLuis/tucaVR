# syntax=docker/dockerfile:1
# =============================================================================
# tucaVR — debug APK build image
#
# Independent stages (BuildKit builds sdk, ffmpeg and rust in parallel):
#   sdk    -> Android SDK 34, NDK 26.3, CMake 3.22.1, build-tools
#   ffmpeg -> FFmpeg cross-compiled for arm64-v8a (ffmpeg-android-maker)
#   rust   -> Pinned Rust + the aarch64-linux-android target + cargo-ndk
#   final  -> only what the build needs at runtime (no nasm/yasm/curl/wget,
#             no .git or FFmpeg sources)
#
# The source code and the Meta OpenXR SDK are mounted into /project as a volume.
# The container runs as a non-root user; use scripts/docker-build.sh, which
# passes --user with the host's UID/GID (generated files stay yours, with no chown).
# =============================================================================

# Pins. Update digests/versions on purpose, never "latest".
ARG UBUNTU_IMAGE=ubuntu:22.04@sha256:b8b6ee6aa931ecd9d0d952abc34dc0e5f7c6a30c6bb71b079fe399fde0329c02

FROM ${UBUNTU_IMAGE} AS base
ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8
# Without this, docker-clean wipes the apt cache and the cache mount stays empty.
RUN rm -f /etc/apt/apt.conf.d/docker-clean

# ── sdk: Android SDK + NDK + CMake ───────────────────────────────────────────
FROM base AS sdk
ARG CMDLINE_TOOLS_VERSION=11076708
ARG CMDLINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
ARG NDK_VERSION=26.3.11579264
ENV ANDROID_HOME=/opt/android-sdk

RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        openjdk-17-jdk-headless \
        unzip

RUN <<EOT
    set -eux
    curl -fsSL -o /tmp/cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
    echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum -c -
    mkdir -p "${ANDROID_HOME}/cmdline-tools"
    unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools"
    mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"
    rm /tmp/cmdline-tools.zip
    yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null
    "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" \
        "platforms;android-34" \
        "build-tools;34.0.0" \
        "ndk;${NDK_VERSION}" \
        "cmake;3.22.1"
    # Nothing here writes to the SDK afterwards; make it readable by any UID.
    chmod -R a+rX "${ANDROID_HOME}"
EOT

# ── ffmpeg: cross-compile for arm64-v8a ─────────────────────────────────────
FROM base AS ffmpeg
# The same commit as scripts/setup-deps.sh (FFMPEG_MAKER_COMMIT).
ARG FFMPEG_MAKER_REF=dd72b161ae5c759fd25a5cab971a3ff710f0bdba
ARG NDK_VERSION=26.3.11579264
ENV ANDROID_SDK_HOME=/opt/android-sdk \
    ANDROID_NDK_HOME=/opt/android-sdk/ndk/${NDK_VERSION}

RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        cmake \
        curl \
        git \
        nasm \
        ninja-build \
        pkg-config \
        python3 \
        unzip \
        wget \
        yasm

# Only the NDK: the rest of the SDK takes no part in the FFmpeg build.
COPY --from=sdk /opt/android-sdk/ndk /opt/android-sdk/ndk

# The prefix of the generated .pc files is /opt/ffmpeg-android-maker/...; the final stage
# copies it to the same path so pkg-config keeps resolving.
RUN <<EOT
    set -eux
    git clone https://github.com/Javernaut/ffmpeg-android-maker.git /opt/ffmpeg-android-maker
    cd /opt/ffmpeg-android-maker
    git checkout "${FFMPEG_MAKER_REF}"
    ./ffmpeg-android-maker.sh --target-abis=arm64-v8a --android-api-level=26
    # Only the result: no sources, .git or the maker script.
    mkdir -p /out/build/ffmpeg /out/output/include
    cp -a build/ffmpeg/arm64-v8a /out/build/ffmpeg/arm64-v8a
    # scripts/build-rust.sh references output/include/arm64-v8a (a relative symlink).
    ln -s ../../build/ffmpeg/arm64-v8a/include /out/output/include/arm64-v8a
EOT

# ── rust: pinned toolchain + cargo-ndk ─────────────────────────────────────────
FROM base AS rust
ARG RUSTUP_VERSION=1.28.2
ARG RUSTUP_INIT_SHA256=20a06e644b0d9bd2fbdbfd52d42540bdde820ea7df86e92e533c073da0cdd43c
ARG RUST_VERSION=1.97.1
ARG CARGO_NDK_VERSION=4.1.2
ENV RUSTUP_HOME=/opt/rustup \
    CARGO_HOME=/opt/cargo \
    PATH=/opt/cargo/bin:${PATH}

RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        curl

RUN <<EOT
    set -eux
    curl -fsSL -o /tmp/rustup-init \
        "https://static.rust-lang.org/rustup/archive/${RUSTUP_VERSION}/x86_64-unknown-linux-gnu/rustup-init"
    echo "${RUSTUP_INIT_SHA256}  /tmp/rustup-init" | sha256sum -c -
    chmod +x /tmp/rustup-init
    /tmp/rustup-init -y --no-modify-path --profile minimal \
        --default-toolchain "${RUST_VERSION}" --target aarch64-linux-android
    rm /tmp/rustup-init
EOT

RUN --mount=type=cache,target=/opt/cargo/registry \
    --mount=type=cache,target=/opt/cargo/git \
    cargo install cargo-ndk --locked --version "${CARGO_NDK_VERSION}" \
    && chmod -R a+rX /opt/rustup /opt/cargo

# ── final ────────────────────────────────────────────────────────────────────
FROM base AS final
ARG NDK_VERSION=26.3.11579264

LABEL org.opencontainers.image.title="tucaVR builder" \
      org.opencontainers.image.description="Toolchain de build do APK debug do tucaVR" \
      org.opencontainers.image.source="https://github.com/BGLuis/tucaVR"

# build-essential: the host linker for build.rs/proc-macros. libclang-dev: bindgen.
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        git \
        libclang-dev \
        openjdk-17-jdk-headless \
        pkg-config

COPY --link --from=sdk /opt/android-sdk /opt/android-sdk
COPY --link --from=rust /opt/rustup /opt/rustup
COPY --link --from=rust /opt/cargo/bin /opt/cargo/bin
COPY --link --from=ffmpeg /out/ /opt/ffmpeg-android-maker/

# Caches and HOME at a path writable by any UID (the container runs with the
# host's UID through --user). Named volumes inherit these permissions.
RUN mkdir -p -m 1777 /cache/gradle /cache/cargo /cache/android /cache/home

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_NDK_HOME=/opt/android-sdk/ndk/${NDK_VERSION} \
    ANDROID_NDK_ROOT=/opt/android-sdk/ndk/${NDK_VERSION} \
    ANDROID_USER_HOME=/cache/android \
    FFMPEG_MAKER_DIR=/opt/ffmpeg-android-maker \
    RUSTUP_HOME=/opt/rustup \
    CARGO_HOME=/cache/cargo \
    GRADLE_USER_HOME=/cache/gradle \
    HOME=/cache/home \
    PATH=/opt/cargo/bin:${PATH} \
    GIT_CONFIG_COUNT=1 \
    GIT_CONFIG_KEY_0=safe.directory \
    GIT_CONFIG_VALUE_0=/project

WORKDIR /project
COPY --chmod=755 entrypoint.sh /usr/local/bin/entrypoint.sh

# A static, non-root UID by default; scripts/docker-build.sh overrides it with
# the host's UID/GID. No HEALTHCHECK: this is a build container, not a service.
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
