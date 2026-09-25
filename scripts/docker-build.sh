#!/bin/bash
# =============================================================================
# tucaVR — Build do APK debug dentro de um container (sem instalar SDK/NDK/Rust
# no host).
#
# Uso:
#   ./scripts/docker-build.sh            # constrói a imagem (cache) e compila o APK
#   ./scripts/docker-build.sh --rebuild  # refaz a imagem do zero (--no-cache --pull)
#   ./scripts/docker-build.sh --shell    # shell interativo no container
#
# Variáveis: APP_VERSION_NAME, APP_VERSION_CODE (repassadas ao Gradle) e
# DOCKER (padrão: docker; ex.: DOCKER=podman).
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DOCKER="${DOCKER:-docker}"
IMAGE_NAME="tucavr-builder"
DOCKERFILE_DIR="docker/build"

FORCE_REBUILD=false
INTERACTIVE_SHELL=false

for arg in "$@"; do
    case "$arg" in
        --rebuild) FORCE_REBUILD=true ;;
        --shell)   INTERACTIVE_SHELL=true ;;
        --help|-h)
            sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "❌ Argumento desconhecido: $arg (use --help)" >&2
            exit 1
            ;;
    esac
done

if ! command -v "$DOCKER" &> /dev/null; then
    echo "❌ '$DOCKER' não encontrado. Instale o Docker: https://docs.docker.com/engine/install/" >&2
    exit 1
fi

# A checagem do Meta OpenXR SDK fica no entrypoint (uma só fonte); aqui só
# evitamos esperar o build da imagem para descobrir que ele falta.
if [ ! -d "sdk/meta-openxr-sdk/Samples/SampleXrFramework" ]; then
    echo "⚠️  Meta OpenXR SDK não encontrado em sdk/meta-openxr-sdk/" >&2
    echo "   Baixe em https://developers.meta.com/horizon/downloads/package/oculus-openxr-mobile-sdk/" >&2
    echo "   e extraia para sdk/meta-openxr-sdk/." >&2
    exit 1
fi

# Sempre roda o build da imagem: sem mudanças é só cache (segundos), e mudanças
# no Dockerfile/entrypoint nunca ficam com imagem velha.
BUILD_ARGS=()
if $FORCE_REBUILD; then
    BUILD_ARGS+=(--no-cache --pull)
fi
echo "🐳 Preparando a imagem '$IMAGE_NAME' (a primeira vez leva vários minutos)..."
"$DOCKER" build "${BUILD_ARGS[@]}" -t "$IMAGE_NAME" "$DOCKERFILE_DIR"

# Roda com o UID/GID do host: arquivos gerados já nascem seus, sem chown/root.
RUN_ARGS=(
    --rm
    --init
    --user "$(id -u):$(id -g)"
    --cap-drop ALL
    --security-opt no-new-privileges:true
    -v "$ROOT_DIR:/project"
    # Caches persistentes para builds incrementais
    -v tucavr-gradle-cache:/cache/gradle
    -v tucavr-cargo-cache:/cache/cargo
    -e APP_VERSION_NAME
    -e APP_VERSION_CODE
)
# Podman rootless: mantém o UID do host dentro do container.
if [[ "$(basename "$DOCKER")" == podman* ]]; then
    RUN_ARGS+=(--userns=keep-id)
fi

if $INTERACTIVE_SHELL; then
    echo "🐚 Shell no container (projeto em /project, FFmpeg em /opt/ffmpeg-android-maker)"
    "$DOCKER" run -it "${RUN_ARGS[@]}" --entrypoint /bin/bash "$IMAGE_NAME"
else
    echo "🚀 Compilando o APK no container..."
    "$DOCKER" run "${RUN_ARGS[@]}" "$IMAGE_NAME"
    echo "📦 APK: app/build/outputs/apk/debug/app-debug.apk"
fi
