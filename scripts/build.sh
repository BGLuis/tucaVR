#!/bin/bash
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FORCE_CLEAN=false
BUILD_TYPE="debug"
for arg in "$@"; do
    case "$arg" in
        --clean) FORCE_CLEAN=true ;;
        --release) BUILD_TYPE="release" ;;
        *)
            echo "❌ Unknown argument: $arg (use --clean or --release)" >&2
            exit 1
            ;;
    esac
done

echo "🚀 Starting the unified build (tucaVR, $BUILD_TYPE)..."

if $FORCE_CLEAN; then
    echo "🧹 Cleaning Android artifacts..."
    # Remove CMake state explicitly: its Ninja/SDK paths can belong to Docker.
    rm -rf -- app/.cxx app/build
fi

# Gradle runs buildRust before preBuild, then builds the native C++ layer and APK.
echo "🤖 Building the Android app..."
GRADLE_ARGS=()
if $FORCE_CLEAN; then
    ./gradlew clean
fi
if [ -n "$APP_VERSION_NAME" ]; then
    GRADLE_ARGS+=("-PappVersionName=$APP_VERSION_NAME")
fi
if [ -n "$APP_VERSION_CODE" ]; then
    GRADLE_ARGS+=("-PappVersionCode=$APP_VERSION_CODE")
fi

if [ "$BUILD_TYPE" = "release" ]; then
    ./gradlew assembleRelease "${GRADLE_ARGS[@]}"
else
    ./gradlew assembleDebug "${GRADLE_ARGS[@]}"
fi

echo "✅ Build finished successfully!"
