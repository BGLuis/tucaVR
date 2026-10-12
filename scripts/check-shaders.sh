#!/usr/bin/env bash
set -euo pipefail

# Repository root directory
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "=== Checking Vulkan Shader Integrity (GLSL -> SPIR-V) ==="

GLSLC=""
POSSIBLE_PATHS=(
    "glslc"
    "${ANDROID_NDK_HOME:-}/shader-tools/linux-x86_64/glslc"
    "/opt/android-ndk/shader-tools/linux-x86_64/glslc"
    "${ANDROID_HOME:-}/ndk/26.3.11579264/shader-tools/linux-x86_64/glslc"
)

for p in "${POSSIBLE_PATHS[@]}"; do
    if [ -n "$p" ] && command -v "$p" >/dev/null 2>&1; then
        GLSLC="$p"
        break
    fi
done

if [ -z "$GLSLC" ] && [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/ndk" ]; then
    FOUND=$(find "${ANDROID_HOME}/ndk" -name glslc -type f 2>/dev/null | head -n 1 || true)
    if [ -n "$FOUND" ] && [ -x "$FOUND" ]; then
        GLSLC="$FOUND"
    fi
fi

if [ -z "$GLSLC" ]; then
    echo "⚠️  Warning: glslc not found in PATH or in the NDK. Trying glslangValidator..."
    if command -v glslangValidator >/dev/null 2>&1; then
        echo "-> Using glslangValidator to validate shaders..."
        SHADERS=(native/shaders/vulkan/*.vert native/shaders/vulkan/*.frag)
        FAILED=0
        for s in "${SHADERS[@]}"; do
            if ! glslangValidator -V "$s" -o /dev/null >/dev/null 2>&1; then
                echo "❌ Shader validation failed: $s" >&2
                FAILED=1
            else
                echo "✓ $s"
            fi
        done
        if [ "$FAILED" -ne 0 ]; then
            exit 1
        fi
        echo "=== All ${#SHADERS[@]} shaders validated successfully through glslangValidator! ==="
        exit 0
    else
        echo "❌ Error: glslc or glslangValidator is required to check the shaders." >&2
        exit 1
    fi
fi

echo "-> Using compiler: $GLSLC"
SHADERS=(native/shaders/vulkan/*.vert native/shaders/vulkan/*.frag)
FAILED=0
TMP_OUT=$(mktemp)

for s in "${SHADERS[@]}"; do
    if ! "$GLSLC" --target-env=vulkan1.1 -c "$s" -o "$TMP_OUT"; then
        echo "❌ Shader compilation error: $s" >&2
        FAILED=1
    else
        echo "✓ $s"
    fi
done

rm -f "$TMP_OUT"

if [ "$FAILED" -ne 0 ]; then
    echo "❌ One or more shaders failed to compile." >&2
    exit 1
fi

echo "=== All ${#SHADERS[@]} Vulkan shaders were compiled and validated successfully! ==="
