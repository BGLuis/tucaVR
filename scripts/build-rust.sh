#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PATH="${CARGO_HOME:-$HOME/.cargo}/bin:$PATH"
export PATH

# Modes: auto | docker | host | skip
RUST_BUILD_MODE="${RUST_BUILD_MODE:-auto}"
DOCKER_BIN="${DOCKER:-docker}"
if ! command -v "$DOCKER_BIN" &>/dev/null && command -v podman &>/dev/null; then
    DOCKER_BIN="podman"
fi

INSIDE_DOCKER=false
IS_CLEAN=false

for arg in "$@"; do
    case "$arg" in
        --clean) IS_CLEAN=true ;;
        --inside-docker) INSIDE_DOCKER=true ;;
        *)
            echo "❌ Unknown argument: $arg (use --clean or --inside-docker)" >&2
            exit 1
            ;;
    esac
done

has_host_toolchain() {
    command -v cargo &>/dev/null || return 1
    cargo ndk --version &>/dev/null || return 1

    local ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
    if [[ -z "$ndk" && -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
        ndk="${ANDROID_HOME:-${ANDROID_SDK_ROOT}}/ndk/26.3.11579264"
    fi
    [[ -n "$ndk" && -d "$ndk" ]] || return 1

    local ffmpeg_dir="${FFMPEG_MAKER_DIR:-$ROOT_DIR/ffmpeg-android-maker}"
    ls "$ffmpeg_dir"/build/ffmpeg/arm64-v8a/lib/*.so &>/dev/null || return 1

    return 0
}

has_docker() {
    command -v "$DOCKER_BIN" &>/dev/null || return 1
    "$DOCKER_BIN" info &>/dev/null || return 1
    return 0
}

has_prebuilt_libs() {
    [[ -f "$ROOT_DIR/app/src/main/jniLibs/arm64-v8a/libbridge.so" || -f "$ROOT_DIR/app/src/main/jniLibs/arm64-v8a/librust_bridge.so" ]] || return 1
    ls "$ROOT_DIR"/app/src/main/jniLibs/arm64-v8a/libavcodec.so &>/dev/null || return 1
    return 0
}

if $IS_CLEAN; then
    if $INSIDE_DOCKER || [[ "$RUST_BUILD_MODE" == "host" ]] || { [[ "$RUST_BUILD_MODE" == "auto" ]] && has_host_toolchain; }; then
        if command -v cargo &>/dev/null; then
            cd rust && cargo clean
        fi
        exit 0
    elif has_docker; then
        echo "🧹 Cleaning Rust artifacts via Docker..."
        "$DOCKER_BIN" run --rm \
            --user "$(id -u):$(id -g)" \
            -v "$ROOT_DIR:/project" \
            -v tucavr-cargo-cache:/cache/cargo \
            --entrypoint /bin/bash \
            tucavr-builder -c "bash ./scripts/build-rust.sh --clean --inside-docker"
        exit 0
    else
        echo "🧹 Removing Rust artifacts from the target directory..."
        rm -rf rust/target
        exit 0
    fi
fi

if [[ "$RUST_BUILD_MODE" == "skip" ]]; then
    echo "⏩ Skipping Rust compilation ('skip' mode enabled)."
    exit 0
fi

USE_DOCKER_RUN=false
if $INSIDE_DOCKER || [[ "$RUST_BUILD_MODE" == "host" ]]; then
    USE_DOCKER_RUN=false
elif [[ "$RUST_BUILD_MODE" == "docker" ]]; then
    USE_DOCKER_RUN=true
elif [[ "$RUST_BUILD_MODE" == "auto" ]]; then
    if has_host_toolchain; then
        USE_DOCKER_RUN=false
    elif has_docker; then
        USE_DOCKER_RUN=true
    elif has_prebuilt_libs; then
        echo "⚠️ Neither the host Rust toolchain nor Docker was found. Using existing JNI libraries."
        exit 0
    else
        echo "❌ Neither the Rust toolchain (cargo, cargo-ndk, ffmpeg) nor Docker was found on the host." >&2
        echo "   To compile the Rust code, install Docker or install the toolchain on the host." >&2
        exit 1
    fi
fi

if $USE_DOCKER_RUN; then
    if ! has_docker; then
        echo "❌ Docker is not running or was not found ($DOCKER_BIN)." >&2
        exit 1
    fi

    if ! "$DOCKER_BIN" image inspect tucavr-builder >/dev/null 2>&1; then
        echo "🐳 Docker image 'tucavr-builder' not found. Building image..."
        "$DOCKER_BIN" build -t tucavr-builder "$ROOT_DIR/docker/build"
    fi

    echo "🐳 Building Rust libraries via Docker (tucavr-builder)..."
    "$DOCKER_BIN" run --rm \
        --user "$(id -u):$(id -g)" \
        -v "$ROOT_DIR:/project" \
        -v tucavr-cargo-cache:/cache/cargo \
        --entrypoint /bin/bash \
        tucavr-builder -c "bash ./scripts/build-rust.sh --inside-docker"

    mkdir -p "$ROOT_DIR/rust/target/aarch64-linux-android/release"
    if [[ -f "$ROOT_DIR/app/src/main/jniLibs/arm64-v8a/libbridge.so" && ! -f "$ROOT_DIR/rust/target/aarch64-linux-android/release/libbridge.so" ]]; then
        cp "$ROOT_DIR/app/src/main/jniLibs/arm64-v8a/libbridge.so" "$ROOT_DIR/rust/target/aarch64-linux-android/release/libbridge.so"
    fi
    exit 0
fi

# Native host compilation
ANDROID_SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ANDROID_NDK_HOME" && -n "$ANDROID_SDK_DIR" ]]; then
    ANDROID_NDK_HOME="$ANDROID_SDK_DIR/ndk/26.3.11579264"
fi
if [[ -z "$ANDROID_NDK_HOME" || ! -d "$ANDROID_NDK_HOME" ]]; then
    echo "❌ Android NDK not found. Set ANDROID_HOME or ANDROID_NDK_HOME." >&2
    exit 1
fi
export ANDROID_NDK_HOME
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export PKG_CONFIG_ALLOW_CROSS=1

FFMPEG_MAKER_DIR="${FFMPEG_MAKER_DIR:-$ROOT_DIR/ffmpeg-android-maker}"
if ! ls "$FFMPEG_MAKER_DIR"/build/ffmpeg/arm64-v8a/lib/*.so > /dev/null 2>&1; then
    echo "❌ FFmpeg for arm64-v8a not found in $FFMPEG_MAKER_DIR" >&2
    echo "   Run ./scripts/setup-deps.sh and compile FFmpeg (see the README)." >&2
    exit 1
fi
export PKG_CONFIG_PATH="$FFMPEG_MAKER_DIR/build/ffmpeg/arm64-v8a/lib/pkgconfig"
export BINDGEN_EXTRA_CLANG_ARGS="-I$FFMPEG_MAKER_DIR/output/include/arm64-v8a -I$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include --sysroot=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot"

cd rust
cargo ndk -t aarch64-linux-android -P 26 -o ../app/src/main/jniLibs build --release

cp "$FFMPEG_MAKER_DIR"/build/ffmpeg/arm64-v8a/lib/*.so ../app/src/main/jniLibs/arm64-v8a/

mkdir -p "$ROOT_DIR/rust/target/aarch64-linux-android/release"
if [[ -f "$ROOT_DIR/app/src/main/jniLibs/arm64-v8a/libbridge.so" && ! -f "$ROOT_DIR/rust/target/aarch64-linux-android/release/libbridge.so" ]]; then
    cp "$ROOT_DIR/app/src/main/jniLibs/arm64-v8a/libbridge.so" "$ROOT_DIR/rust/target/aarch64-linux-android/release/libbridge.so"
fi
