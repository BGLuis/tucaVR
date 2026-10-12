#!/bin/bash
# Baixa/prepara dependencias externas que NAO sao versionadas no repositorio
# (veja .gitignore): ffmpeg-android-maker e o Meta OpenXR SDK.
#
# Rode uma vez apos clonar o repo, antes de scripts/build.sh.
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FFMPEG_MAKER_REPO="https://github.com/Javernaut/ffmpeg-android-maker.git"
FFMPEG_MAKER_COMMIT="dd72b161ae5c759fd25a5cab971a3ff710f0bdba"

# 1. ffmpeg-android-maker (ferramenta de cross-compile do FFmpeg, publica no GitHub)
if [ -d "ffmpeg-android-maker/.git" ]; then
    echo "✅ ffmpeg-android-maker já presente em ./ffmpeg-android-maker"
else
    echo "🎬 Clonando ffmpeg-android-maker..."
    git clone "$FFMPEG_MAKER_REPO" ffmpeg-android-maker
    git -C ffmpeg-android-maker checkout "$FFMPEG_MAKER_COMMIT"
fi

# 2. Meta OpenXR SDK (último release público do GitHub).
META_OPENXR_SDK_DIR="sdk/meta-openxr-sdk"
META_OPENXR_SDK_URL="https://github.com/meta-quest/Meta-OpenXR-SDK/releases/latest/download/meta-openxr-sdk.zip"

if [ -d "$META_OPENXR_SDK_DIR/Samples/SampleXrFramework" ] &&
   [ -d "$META_OPENXR_SDK_DIR/OpenXR" ]; then
    echo "✅ Meta OpenXR SDK já presente em ./sdk/meta-openxr-sdk"
elif [ -e "$META_OPENXR_SDK_DIR" ]; then
    echo "❌ ./sdk/meta-openxr-sdk existe, mas não contém a estrutura esperada." >&2
    echo "   Mova ou remova essa pasta e rode ./scripts/setup-deps.sh novamente." >&2
    exit 1
else
    command -v curl >/dev/null 2>&1 || {
        echo "❌ curl não encontrado; instale curl para baixar o Meta OpenXR SDK." >&2
        exit 1
    }
    command -v unzip >/dev/null 2>&1 || {
        echo "❌ unzip não encontrado; instale unzip para extrair o Meta OpenXR SDK." >&2
        exit 1
    }

    SDK_TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/meta-openxr-sdk.XXXXXX")"
    trap 'rm -rf "$SDK_TMP_DIR"' EXIT
    echo "⬇️  Baixando o último Meta OpenXR SDK do GitHub..."
    curl -fL --retry 3 --retry-delay 2 "$META_OPENXR_SDK_URL" -o "$SDK_TMP_DIR/meta-openxr-sdk.zip"
    unzip -q "$SDK_TMP_DIR/meta-openxr-sdk.zip" -d "$SDK_TMP_DIR/extracted"

    if [ ! -d "$SDK_TMP_DIR/extracted/Samples/SampleXrFramework" ] ||
       [ ! -d "$SDK_TMP_DIR/extracted/OpenXR" ]; then
        echo "❌ O arquivo baixado não contém a estrutura esperada do Meta OpenXR SDK." >&2
        exit 1
    fi

    mkdir -p sdk
    mv "$SDK_TMP_DIR/extracted" "$META_OPENXR_SDK_DIR"
    echo "✅ Meta OpenXR SDK instalado em ./$META_OPENXR_SDK_DIR"
fi

echo "Concluído."
