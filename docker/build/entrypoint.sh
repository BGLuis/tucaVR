#!/bin/bash
# =============================================================================
# tucaVR — Entrypoint do container de build
#
# Só valida o ambiente e delega para scripts/build.sh, a única fonte de verdade
# do pipeline (cargo ndk -> .so do FFmpeg -> gradle). O container roda com o
# UID do host, então nada aqui precisa de chown nem toca em arquivos do projeto.
# =============================================================================
set -euo pipefail

die() {
    echo "❌ $1" >&2
    shift
    for line in "$@"; do echo "   $line" >&2; done
    exit 1
}

cd /project

[ -f build.gradle.kts ] || die "Código-fonte do projeto não encontrado em /project" \
    "Monte o projeto com: -v \"\$(pwd)\":/project"

[ -d sdk/meta-openxr-sdk/Samples/SampleXrFramework ] || die "Meta OpenXR SDK não encontrado em sdk/meta-openxr-sdk/" \
    "Esse SDK exige download manual com aceite de licença:" \
    "https://developers.meta.com/horizon/downloads/package/oculus-openxr-mobile-sdk/"

# Num git worktree o .git é um arquivo que aponta para um caminho do host,
# inexistente aqui; o Gradle usa git e falharia tarde e com erro confuso.
[ ! -f .git ] || die "/project é um git worktree (.git é um arquivo)" \
    "O caminho gitdir dele não existe dentro do container." \
    "Use o checkout principal ou um clone completo (git clone)."

[ -d "${FFMPEG_MAKER_DIR}/build/ffmpeg/arm64-v8a/lib" ] || die "FFmpeg pré-compilado ausente em ${FFMPEG_MAKER_DIR}" \
    "A imagem está corrompida; refaça com: scripts/docker-build.sh --rebuild"

echo "📋 NDK=${ANDROID_NDK_HOME}  FFMPEG_MAKER_DIR=${FFMPEG_MAKER_DIR}  UID=$(id -u):$(id -g)"

exec ./scripts/build.sh "$@"
