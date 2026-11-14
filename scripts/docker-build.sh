#!/bin/bash
# =============================================================================
# tucaVR — Builds the debug APK inside a container (no SDK/NDK/Rust installed
# on the host).
#
# Usage:
#   ./scripts/docker-build.sh            # builds the image (cached) and compiles the debug APK
#   ./scripts/docker-build.sh --release  # compiles the release APK (signed if KEYSTORE_* env vars are set)
#   ./scripts/docker-build.sh --clean    # discards build caches and recompiles everything
#   ./scripts/docker-build.sh --rebuild  # rebuilds the image from scratch (--no-cache --pull)
#   ./scripts/docker-build.sh --shell    # interactive shell in the container
#
# Variables: APP_VERSION_NAME, APP_VERSION_CODE, KEYSTORE_PATH, KEYSTORE_PASSWORD,
# KEY_ALIAS, KEY_PASSWORD (passed to Gradle) and DOCKER (default: docker; e.g. DOCKER=podman).
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DOCKER="${DOCKER:-docker}"
IMAGE_NAME="tucavr-builder"
DOCKERFILE_DIR="docker/build"

FORCE_REBUILD=false
INTERACTIVE_SHELL=false
FORCE_CLEAN=false
RELEASE_BUILD=false

for arg in "$@"; do
    case "$arg" in
        --rebuild) FORCE_REBUILD=true ;;
        --shell)   INTERACTIVE_SHELL=true ;;
        --clean)   FORCE_CLEAN=true ;;
        --release) RELEASE_BUILD=true ;;
        --help|-h)
            sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "❌ Unknown argument: $arg (use --help)" >&2
            exit 1
            ;;
    esac
done

if $INTERACTIVE_SHELL && $FORCE_CLEAN; then
    echo "❌ --clean cannot be combined with --shell" >&2
    exit 1
fi

if ! command -v "$DOCKER" &> /dev/null; then
    echo "❌ '$DOCKER' not found. Install Docker: https://docs.docker.com/engine/install/" >&2
    exit 1
fi

# Prepares the prerequisites in case the SDK has not been downloaded yet.
if [ ! -d "sdk/meta-openxr-sdk/Samples/SampleXrFramework" ] ||
   [ ! -d "sdk/meta-openxr-sdk/OpenXR" ]; then
    echo "📥 Meta OpenXR SDK missing; preparing dependencies..."
    ./scripts/setup-deps.sh
fi

if [ ! -d "sdk/meta-openxr-sdk/Samples/SampleXrFramework" ] ||
   [ ! -d "sdk/meta-openxr-sdk/OpenXR" ]; then
    echo "⚠️  Meta OpenXR SDK not found in sdk/meta-openxr-sdk/" >&2
    echo "   Confirm the download and run ./scripts/setup-deps.sh again." >&2
    exit 1
fi

# Always runs the image build: with no changes it is just cache (seconds), and
# changes to the Dockerfile/entrypoint never leave a stale image behind.
BUILD_ARGS=()
if $FORCE_REBUILD; then
    BUILD_ARGS+=(--no-cache --pull)
fi
echo "🐳 Preparing the image '$IMAGE_NAME' (the first time takes several minutes)..."
"$DOCKER" build "${BUILD_ARGS[@]}" -t "$IMAGE_NAME" "$DOCKERFILE_DIR"

# Runs with the host's UID/GID: generated files are already yours, with no chown/root.
RUN_ARGS=(
    --rm
    --init
    --user "$(id -u):$(id -g)"
    --cap-drop ALL
    --security-opt no-new-privileges:true
    -v "$ROOT_DIR:/project"
    # Persistent caches for incremental builds
    -v tucavr-gradle-cache:/cache/gradle
    -v tucavr-cargo-cache:/cache/cargo
    -e APP_VERSION_NAME
    -e APP_VERSION_CODE
    -e KEYSTORE_PATH
    -e KEYSTORE_PASSWORD
    -e KEY_ALIAS
    -e KEY_PASSWORD
)
# Rootless Podman: keeps the host's UID inside the container.
if [[ "$(basename "$DOCKER")" == podman* ]]; then
    RUN_ARGS+=(--userns=keep-id)
fi

# AGP records absolute SDK/Ninja paths in those generated native folders.
# Keep the Docker state apart so Android Studio uses the host's paths.
NATIVE_STATE_DIR="$ROOT_DIR/.docker-native-state.$$"
NATIVE_STATE_ACTIVE=false
NATIVE_PATHS=(
    "app/.cxx"
    "app/build/intermediates/cxx"
)

native_state_is_usable_on_host() {
    local cmake_cache ninja_path
    local -a cmake_caches=()

    mapfile -t cmake_caches < <(
        find app/.cxx -type f -name CMakeCache.txt -print 2>/dev/null
    )
    ((${#cmake_caches[@]} > 0)) || return 1

    for cmake_cache in "${cmake_caches[@]}"; do
        ninja_path="$(sed -n 's/^CMAKE_MAKE_PROGRAM:[^=]*=//p' "$cmake_cache" | head -n 1)"
        [[ -x "$ninja_path" ]] || return 1
    done
    return 0
}

restore_native_state() {
    local exit_code=$? path
    trap - EXIT

    if $NATIVE_STATE_ACTIVE; then
        for path in "${NATIVE_PATHS[@]}"; do
            if ! rm -rf -- "$ROOT_DIR/$path"; then
                echo "❌ Could not remove the Docker native cache: $ROOT_DIR/$path" >&2
                exit_code=1
            fi
            if [[ -e "$NATIVE_STATE_DIR/$path" ]]; then
                mkdir -p "$(dirname "$ROOT_DIR/$path")" &&
                    mv -- "$NATIVE_STATE_DIR/$path" "$ROOT_DIR/$path" || {
                        echo "❌ Could not restore the host native cache: $ROOT_DIR/$path" >&2
                        exit_code=1
                    }
            fi
        done
        rm -rf -- "$NATIVE_STATE_DIR" || {
            echo "❌ Could not remove the temporary directory $NATIVE_STATE_DIR" >&2
            exit_code=1
        }
    fi

    exit "$exit_code"
}

if native_state_is_usable_on_host; then
    mkdir -p "$NATIVE_STATE_DIR"
    for path in "${NATIVE_PATHS[@]}"; do
        if [[ -e "$path" ]]; then
            mkdir -p "$NATIVE_STATE_DIR/$(dirname "$path")"
            mv -- "$path" "$NATIVE_STATE_DIR/$path"
        fi
    done
elif [[ -e app/.cxx || -e app/build/intermediates/cxx ]]; then
    echo "⚠️  The CMake/Ninja cache points to tools unavailable on the host; removing the old native cache."
    rm -rf -- app/.cxx app/build/intermediates/cxx
fi
NATIVE_STATE_ACTIVE=true
trap restore_native_state EXIT

if $INTERACTIVE_SHELL; then
    echo "🐚 Shell in the container (project in /project, FFmpeg in /opt/ffmpeg-android-maker)"
    "$DOCKER" run -it "${RUN_ARGS[@]}" --entrypoint /bin/bash "$IMAGE_NAME"
else
    echo "🚀 Building the APK in the container..."
    BUILD_ARGS=()
    if $FORCE_CLEAN; then
        BUILD_ARGS+=(--clean)
    fi
    if $RELEASE_BUILD; then
        BUILD_ARGS+=(--release)
    fi
    "$DOCKER" run "${RUN_ARGS[@]}" "$IMAGE_NAME" "${BUILD_ARGS[@]}"
    if $RELEASE_BUILD; then
        if [[ -f "app/build/outputs/apk/release/app-release.apk" ]]; then
            echo "📦 APK: app/build/outputs/apk/release/app-release.apk"
        else
            echo "📦 APK: app/build/outputs/apk/release/app-release-unsigned.apk"
        fi
    else
        echo "📦 APK: app/build/outputs/apk/debug/app-debug.apk"
    fi
fi
