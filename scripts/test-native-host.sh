#!/usr/bin/env bash
set -euo pipefail

# Repository root directory
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BUILD_DIR="/tmp/tucavr_native_tests_build"
mkdir -p "$BUILD_DIR"

echo "=== Building and Running the Native C++ Unit Tests (Host) ==="

CXX="${CXX:-g++}"
# ccache speeds up rebuilds when available on PATH (e.g. CI with ccache-action).
if command -v ccache >/dev/null 2>&1 && [ "$CXX" = "g++" ]; then
    CXX="ccache g++"
fi

TESTS=(
    "test_vk_math"
    "test_input_fsm"
    "test_screen_mode"
    "test_subtitle_layout"
    "test_hand_tracking"
    "test_environment_config"
    "test_environment_validator"
    "test_browser_pose"
    "test_panel_pick"
    "test_cylinder_hit"
    "test_controls_pose"
    "test_astc_transcode"
    "test_frame_time_histogram"
)

EXTRA_FLAGS=()
if [ "${ENABLE_ASAN:-0}" = "1" ]; then
    echo "-> Enabling AddressSanitizer and UndefinedBehaviorSanitizer (-fsanitize=address,undefined)..."
    EXTRA_FLAGS+=("-fsanitize=address,undefined" "-fno-omit-frame-pointer")
fi

OPENXR_INCLUDE_FLAGS=()
if ! echo '#include <openxr/openxr.h>' | $CXX -E - >/dev/null 2>&1; then
    OPENXR_HEADER=$(find "$HOME/.gradle/caches" -path "*/openxr/openxr.h" -print -quit 2>/dev/null || true)
    if [ -n "$OPENXR_HEADER" ]; then
        OPENXR_INCLUDE_FLAGS+=("-I" "$(dirname "$(dirname "$OPENXR_HEADER")")")
    fi
fi

# Extra sources a test links against (the vendored encoder is third-party code).
ASTC_SOURCES=(native/src/astc_transcode.cpp native/third_party/astcenc/astcenc_*.cpp)

compile_test() {
    local name="$1"
    local extra_sources=()
    if [ "$name" = "test_astc_transcode" ]; then
        extra_sources=("${ASTC_SOURCES[@]}")
    fi
    $CXX -std=c++20 -O2 -Wall -Wextra -Werror \
        "${EXTRA_FLAGS[@]}" \
        -I native/include \
        "${OPENXR_INCLUDE_FLAGS[@]}" \
        "native/tests/${name}.cpp" \
        "${extra_sources[@]}" \
        -pthread -o "$BUILD_DIR/${name}"
}

echo "-> Building the ${#TESTS[@]} test binaries in parallel..."
PIDS=()
for name in "${TESTS[@]}"; do
    compile_test "$name" &
    PIDS+=("$!")
done

FAILED=0
for pid in "${PIDS[@]}"; do
    if ! wait "$pid"; then
        FAILED=1
    fi
done

if [ "$FAILED" -ne 0 ]; then
    echo "❌ Failed to build one or more test binaries." >&2
    exit 1
fi

for name in "${TESTS[@]}"; do
    echo "-> Running ${name}..."
    "$BUILD_DIR/${name}"
done

echo "=== All ${#TESTS[@]} C++ test binaries passed! ==="
rm -rf "$BUILD_DIR"
