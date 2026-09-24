#!/bin/bash
# =============================================================================
# tucaVR — Script de conveniência para build via Docker
#
# Uso:
#   ./docker-build.sh           # Builda a imagem (se necessário) e compila o APK
#   ./docker-build.sh --rebuild # Força rebuild da imagem Docker
#   ./docker-build.sh --shell   # Abre um shell interativo no container
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

IMAGE_NAME="tucavr-builder"
DOCKERFILE_DIR="docker/build"

# ── Argumentos ───────────────────────────────────────────────────────────────
FORCE_REBUILD=false
INTERACTIVE_SHELL=false

for arg in "$@"; do
    case "$arg" in
        --rebuild) FORCE_REBUILD=true ;;
        --shell)   INTERACTIVE_SHELL=true ;;
        --help|-h)
            echo "Uso: $0 [opções]"
            echo ""
            echo "Opções:"
            echo "  --rebuild   Força rebuild da imagem Docker"
            echo "  --shell     Abre shell interativo no container (não builda)"
            echo "  --help      Mostra esta ajuda"
            exit 0
            ;;
        *)
            echo "❌ Argumento desconhecido: $arg"
            echo "   Use --help para ver as opções"
            exit 1
            ;;
    esac
done

# ── Verifica Docker ──────────────────────────────────────────────────────────
if ! command -v docker &>/dev/null; then
    echo "❌ Docker não encontrado. Instale com:"
    echo ""
    echo "   sudo apt-get update"
    echo "   sudo apt-get install -y docker.io"
    echo "   sudo systemctl enable --now docker"
    echo "   sudo usermod -aG docker \$USER"
    echo "   # Faça logout e login novamente"
    echo ""
    exit 1
fi

# ── Verifica Meta OpenXR SDK ─────────────────────────────────────────────────
if [ ! -d "sdk/meta-openxr-sdk/Samples/SampleXrFramework" ]; then
    echo "⚠️  Meta OpenXR SDK não encontrado em sdk/meta-openxr-sdk/"
    echo ""
    echo "   Baixe manualmente em:"
    echo "   https://developers.meta.com/horizon/downloads/package/oculus-openxr-mobile-sdk/"
    echo ""
    echo "   Extraia para sdk/meta-openxr-sdk/ antes de buildar."
    exit 1
fi

# ── Build da imagem Docker ───────────────────────────────────────────────────
if $FORCE_REBUILD || ! docker image inspect "$IMAGE_NAME" &>/dev/null; then
    echo "🐳 Construindo imagem Docker '$IMAGE_NAME'..."
    echo "   (primeira vez leva ~15-20 minutos — SDK, NDK, Rust, FFmpeg)"
    echo ""
    docker build -t "$IMAGE_NAME" "$DOCKERFILE_DIR"
    echo ""
    echo "✅ Imagem '$IMAGE_NAME' construída com sucesso!"
    echo ""
else
    echo "✅ Imagem '$IMAGE_NAME' já existe (use --rebuild para recriar)"
fi

# ── Monta volumes e executa ──────────────────────────────────────────────────
DOCKER_RUN_ARGS=(
    --rm
    -v "$(pwd):/project"
    -e "HOST_UID=$(id -u)"
    -e "HOST_GID=$(id -g)"
    # Caches persistentes para builds incrementais
    -v "tucavr-gradle-cache:/root/.gradle"
    -v "tucavr-cargo-cache:/root/.cargo/registry"
)

if $INTERACTIVE_SHELL; then
    echo "🐚 Abrindo shell interativo no container..."
    echo "   Projeto montado em /project"
    echo "   FFmpeg pré-compilado em /opt/ffmpeg-android-maker"
    echo ""
    docker run -it "${DOCKER_RUN_ARGS[@]}" --entrypoint /bin/bash "$IMAGE_NAME"
else
    echo "🚀 Iniciando build do APK no container..."
    echo ""
    docker run "${DOCKER_RUN_ARGS[@]}" "$IMAGE_NAME"
fi
