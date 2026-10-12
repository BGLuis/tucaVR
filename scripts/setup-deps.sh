#!/bin/bash
# Downloads/prepares the external dependencies that are NOT versioned in the
# repository (see .gitignore): ffmpeg-android-maker and the Meta OpenXR SDK.
#
# Run it once after cloning the repo, before scripts/build.sh.
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FFMPEG_MAKER_REPO="https://github.com/Javernaut/ffmpeg-android-maker.git"
FFMPEG_MAKER_COMMIT="dd72b161ae5c759fd25a5cab971a3ff710f0bdba"

# 1. ffmpeg-android-maker (the FFmpeg cross-compile tool, public on GitHub)
if [ -d "ffmpeg-android-maker/.git" ]; then
    echo "✅ ffmpeg-android-maker already present in ./ffmpeg-android-maker"
else
    echo "🎬 Cloning ffmpeg-android-maker..."
    git clone "$FFMPEG_MAKER_REPO" ffmpeg-android-maker
    git -C ffmpeg-android-maker checkout "$FFMPEG_MAKER_COMMIT"
fi

# 2. Meta OpenXR SDK (the latest public GitHub release).
META_OPENXR_SDK_DIR="sdk/meta-openxr-sdk"
META_OPENXR_SDK_URL="https://github.com/meta-quest/Meta-OpenXR-SDK/releases/latest/download/meta-openxr-sdk.zip"

if [ -d "$META_OPENXR_SDK_DIR/Samples/SampleXrFramework" ] &&
   [ -d "$META_OPENXR_SDK_DIR/OpenXR" ]; then
    echo "✅ Meta OpenXR SDK already present in ./sdk/meta-openxr-sdk"
elif [ -e "$META_OPENXR_SDK_DIR" ]; then
    echo "❌ ./sdk/meta-openxr-sdk exists but does not contain the expected structure." >&2
    echo "   Move or remove that folder and run ./scripts/setup-deps.sh again." >&2
    exit 1
else
    command -v curl >/dev/null 2>&1 || {
        echo "❌ curl not found; install curl to download the Meta OpenXR SDK." >&2
        exit 1
    }
    command -v unzip >/dev/null 2>&1 || {
        echo "❌ unzip not found; install unzip to extract the Meta OpenXR SDK." >&2
        exit 1
    }

    SDK_TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/meta-openxr-sdk.XXXXXX")"
    trap 'rm -rf "$SDK_TMP_DIR"' EXIT
    echo "⬇️  Downloading the latest Meta OpenXR SDK from GitHub..."
    curl -fL --retry 3 --retry-delay 2 "$META_OPENXR_SDK_URL" -o "$SDK_TMP_DIR/meta-openxr-sdk.zip"
    unzip -q "$SDK_TMP_DIR/meta-openxr-sdk.zip" -d "$SDK_TMP_DIR/extracted"

    if [ ! -d "$SDK_TMP_DIR/extracted/Samples/SampleXrFramework" ] ||
       [ ! -d "$SDK_TMP_DIR/extracted/OpenXR" ]; then
        echo "❌ The downloaded archive does not contain the expected Meta OpenXR SDK structure." >&2
        exit 1
    fi

    mkdir -p sdk
    mv "$SDK_TMP_DIR/extracted" "$META_OPENXR_SDK_DIR"
    echo "✅ Meta OpenXR SDK installed in ./$META_OPENXR_SDK_DIR"
fi

echo "Done."
