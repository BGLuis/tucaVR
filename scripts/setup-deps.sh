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

# 2. Meta OpenXR SDK — requer download manual autenticado, nao ha URL direta.
if [ -d "sdk/meta-openxr-sdk" ]; then
    echo "✅ Meta OpenXR SDK já presente em ./sdk/meta-openxr-sdk"
else
    cat <<'EOF'

⚠️  Meta OpenXR SDK não encontrado em ./sdk/meta-openxr-sdk

Esse SDK não pode ser baixado automaticamente (exige aceite de licença no
portal da Meta). Para prepará-lo:

  1. Baixe o "OpenXR Mobile SDK" em https://developer.oculus.com/downloads/
  2. Extraia o conteúdo de forma que exista o caminho:
       sdk/meta-openxr-sdk/Samples/SampleXrFramework/...
       sdk/meta-openxr-sdk/OpenXR/...

Essa pasta é ignorada pelo git (.gitignore) — cada dev/máquina de CI
precisa colocá-la localmente.
EOF
fi

echo "Concluído."
