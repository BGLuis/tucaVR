# =============================================================================
# tucaVR — Dockerfile de build completo (APK debug)
#
# Instala todas as toolchains necessárias para compilar o projeto:
#   JDK 17, Android SDK 34, NDK 26.3, CMake 3.22.1,
#   Rust stable + aarch64-linux-android, cargo-ndk,
#   FFmpeg cross-compilado para arm64-v8a.
#
# O código-fonte do projeto e o Meta OpenXR SDK são montados em runtime
# via volume (-v), NÃO copiados para a imagem.
#
# Uso:
#   docker build -t tucavr-builder docker/build/
#   docker run --rm \
#     -v "$(pwd)":/project \
#     -e HOST_UID=$(id -u) -e HOST_GID=$(id -g) \
#     -v tucavr-gradle-cache:/root/.gradle \
#     -v tucavr-cargo-cache:/root/.cargo/registry \
#     tucavr-builder
# =============================================================================

FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

# ── 1. Pacotes do sistema ────────────────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl wget unzip git ca-certificates \
        build-essential pkg-config \
        python3 cmake ninja-build nasm yasm \
        openjdk-17-jdk-headless \
        libclang-dev clang \
    && rm -rf /var/lib/apt/lists/*

# ── 2. Android SDK + NDK + CMake ─────────────────────────────────────────────
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

# Baixa o cmdline-tools
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" && \
    wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
         -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" && \
    mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" && \
    rm /tmp/cmdline-tools.zip

# Aceita licenças e instala SDK 34, NDK 26.3, CMake 3.22.1, build-tools
RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager --no_https \
        "platform-tools" \
        "platforms;android-34" \
        "build-tools;34.0.0" \
        "ndk;26.3.11579264" \
        "cmake;3.22.1"

ENV ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/26.3.11579264"
ENV ANDROID_NDK_ROOT="${ANDROID_NDK_HOME}"
# CMake do SDK no PATH (Gradle encontra automaticamente, mas cargo/oboe pode precisar)
ENV PATH="${ANDROID_HOME}/cmake/3.22.1/bin:${PATH}"

# ── 3. Rust toolchain ────────────────────────────────────────────────────────
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | \
        sh -s -- -y --default-toolchain stable --profile minimal
ENV PATH="/root/.cargo/bin:${PATH}"

RUN rustup target add aarch64-linux-android && \
    cargo install cargo-ndk

# ── 4. FFmpeg cross-compile para arm64-v8a ────────────────────────────────────
# Pinado no mesmo commit usado por scripts/setup-deps.sh
ENV FFMPEG_MAKER_DIR=/opt/ffmpeg-android-maker
RUN git clone https://github.com/Javernaut/ffmpeg-android-maker.git "${FFMPEG_MAKER_DIR}" && \
    cd "${FFMPEG_MAKER_DIR}" && \
    git checkout dd72b161ae5c759fd25a5cab971a3ff710f0bdba

RUN cd "${FFMPEG_MAKER_DIR}" && \
    ANDROID_SDK_HOME="${ANDROID_HOME}" \
    ANDROID_NDK_HOME="${ANDROID_NDK_HOME}" \
    ./ffmpeg-android-maker.sh --target-abis=arm64-v8a --android-api-level=26

# Cria symlink output/include/arm64-v8a → build/.../include (build.sh referencia ambos)
RUN mkdir -p "${FFMPEG_MAKER_DIR}/output/include" && \
    ln -sf "${FFMPEG_MAKER_DIR}/build/ffmpeg/arm64-v8a/include" \
           "${FFMPEG_MAKER_DIR}/output/include/arm64-v8a"

# ── 5. Entrypoint ────────────────────────────────────────────────────────────
WORKDIR /project
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
