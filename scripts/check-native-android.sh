#!/usr/bin/env bash
set -euo pipefail

# Fast syntax/type check for the Android-only Vulkan C++ translation units using the
# Android NDK clang++ toolchain (`-fsyntax-only`), without building Rust, FFmpeg, or Gradle.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

EXPECTED_NDK_VERSION="26.3.11579264"

find_ndk() {
    local candidates=(
        "${ANDROID_NDK_HOME:-}"
        "${ANDROID_HOME:-}/ndk/${EXPECTED_NDK_VERSION}"
        "${ANDROID_NDK_LATEST_HOME:-}"
        "/usr/local/lib/android/sdk/ndk/${EXPECTED_NDK_VERSION}"
        "$HOME/Android/Sdk/ndk/${EXPECTED_NDK_VERSION}"
    )

    for dir in "${candidates[@]}"; do
        if [ -n "$dir" ] && [ -x "$dir/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++" ]; then
            echo "$dir"
            return 0
        fi
    done

    local roots=(
        "${ANDROID_HOME:-}/ndk"
        "/usr/local/lib/android/sdk/ndk"
        "$HOME/Android/Sdk/ndk"
    )
    for root in "${roots[@]}"; do
        if [ -d "$root" ]; then
            local latest
            latest=$(find "$root" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1 || true)
            if [ -n "$latest" ] && [ -x "$latest/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++" ]; then
                echo "$latest"
                return 0
            fi
        fi
    done

    return 1
}

NDK_DIR="$(find_ndk || true)"
if [ -z "$NDK_DIR" ]; then
    echo "❌ Android NDK (linux-x86_64 clang++) not found. Set ANDROID_NDK_HOME or ANDROID_HOME." >&2
    exit 1
fi

CLANGXX="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
SYSROOT="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot"

OPENXR_VERSION="$(sed -nE 's/.*openxr_loader_for_android:([0-9.]+).*/\1/p' app/build.gradle.kts | head -n 1)"
if [ -z "$OPENXR_VERSION" ]; then
    echo "❌ Could not extract openxr_loader_for_android version from app/build.gradle.kts." >&2
    exit 1
fi

TMP_DIR="$(mktemp -d /tmp/tucavr_native_android_check_XXXXXX)"
cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

resolve_openxr_include_dir() {
    if [ -n "${OPENXR_INCLUDE_DIR:-}" ] && [ -f "${OPENXR_INCLUDE_DIR}/openxr/openxr.h" ]; then
        echo "$OPENXR_INCLUDE_DIR"
        return 0
    fi

    local cached_dir="$REPO_ROOT/build/openxr-headers/${OPENXR_VERSION}"
    if [ -f "$cached_dir/openxr/openxr.h" ]; then
        echo "$cached_dir"
        return 0
    fi

    if [ -d "$HOME/.gradle/caches" ]; then
        local gradle_header
        gradle_header="$(find "$HOME/.gradle/caches" -path "*/openxr_loader_for_android-${OPENXR_VERSION}/prefab/modules/headers/include/openxr/openxr.h" -print -quit 2>/dev/null || true)"
        if [ -n "$gradle_header" ]; then
            dirname "$(dirname "$gradle_header")"
            return 0
        fi
    fi

    local aar_url="https://repo1.maven.org/maven2/org/khronos/openxr/openxr_loader_for_android/${OPENXR_VERSION}/openxr_loader_for_android-${OPENXR_VERSION}.aar"
    local aar_file="$TMP_DIR/openxr.aar"
    echo "-> Downloading OpenXR ${OPENXR_VERSION} headers from Maven Central..." >&2
    curl -sSfL "$aar_url" -o "$aar_file"
    mkdir -p "$cached_dir/openxr"
    unzip -q -o -j "$aar_file" 'prefab/modules/headers/include/openxr/*' -d "$cached_dir/openxr"
    if [ ! -f "$cached_dir/openxr/openxr.h" ]; then
        echo "❌ Failed to extract openxr.h from $aar_url" >&2
        return 1
    fi
    echo "$cached_dir"
}

OPENXR_INC="$(resolve_openxr_include_dir)"

# Generate minimal SPIR-V header stubs matching native/cmake/EmbedSpirv.cmake from native/CMakeLists.txt.
SHADER_STUB_DIR="$TMP_DIR/generated_shaders"
mkdir -p "$SHADER_STUB_DIR"
SHADER_COUNT=0
while read -r shader_name var_name; do
    [ -z "$shader_name" ] && continue
    cat > "$SHADER_STUB_DIR/${shader_name}.h" <<EOF
#pragma once
#include <cstdint>
alignas(4) static const unsigned char ${var_name}[] = { 0x00 };
static const uint32_t ${var_name}_size = sizeof(${var_name});
EOF
    SHADER_COUNT=$((SHADER_COUNT + 1))
done < <(sed -nE 's/^[[:space:]]*vrplayer_compile_shader\(([^[:space:]]+)[[:space:]]+([^)[:space:]]+)\).*/\1 \2/p' native/CMakeLists.txt)

if [ "$SHADER_COUNT" -eq 0 ]; then
    echo "❌ No vrplayer_compile_shader entries found in native/CMakeLists.txt." >&2
    exit 1
fi

echo "=== Checking Android C++ compilation (NDK -fsyntax-only) ==="
echo "-> NDK: $NDK_DIR"
echo "-> OpenXR headers (${OPENXR_VERSION}): $OPENXR_INC"
echo "-> Shader header stubs generated: $SHADER_COUNT"

"$CLANGXX" \
    --target=aarch64-none-linux-android26 \
    --sysroot="$SYSROOT" \
    -DANDROID \
    -std=c++20 \
    -fsyntax-only \
    -I native/include \
    -I "$SHADER_STUB_DIR" \
    -I "$NDK_DIR/sources/android/native_app_glue" \
    -isystem "$OPENXR_INC" \
    native/src/vr_player_app_vulkan.cpp \
    native/src/vr_player_jni_vulkan.cpp

echo "=== Android C++ syntax check passed! ==="
