#!/bin/bash
# =============================================================================
# tucaVR — Build do APK debug dentro de um container (sem instalar SDK/NDK/Rust
# no host).
#
# Uso:
#   ./scripts/docker-build.sh            # constrói a imagem (cache) e compila o APK
#   ./scripts/docker-build.sh --clean    # descarta caches de compilação e recompila tudo
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
FORCE_CLEAN=false

for arg in "$@"; do
    case "$arg" in
        --rebuild) FORCE_REBUILD=true ;;
        --shell)   INTERACTIVE_SHELL=true ;;
        --clean)   FORCE_CLEAN=true ;;
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

if $INTERACTIVE_SHELL && $FORCE_CLEAN; then
    echo "❌ --clean não pode ser combinado com --shell" >&2
    exit 1
fi

if ! command -v "$DOCKER" &> /dev/null; then
    echo "❌ '$DOCKER' não encontrado. Instale o Docker: https://docs.docker.com/engine/install/" >&2
    exit 1
fi

# Prepara os pré-requisitos caso o SDK ainda não tenha sido baixado.
if [ ! -d "sdk/meta-openxr-sdk/Samples/SampleXrFramework" ] ||
   [ ! -d "sdk/meta-openxr-sdk/OpenXR" ]; then
    echo "📥 Meta OpenXR SDK ausente; preparando dependências..."
    ./scripts/setup-deps.sh
fi

if [ ! -d "sdk/meta-openxr-sdk/Samples/SampleXrFramework" ] ||
   [ ! -d "sdk/meta-openxr-sdk/OpenXR" ]; then
    echo "⚠️  Meta OpenXR SDK não encontrado em sdk/meta-openxr-sdk/" >&2
    echo "   Confirme o download e rode ./scripts/setup-deps.sh novamente." >&2
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

# O AGP grava caminhos absolutos do SDK/Ninja nessas pastas nativas geradas.
# Separe o estado do Docker para o Android Studio usar os caminhos do host.
NATIVE_STATE_DIR="$ROOT_DIR/.docker-native-state.$$"
NATIVE_STATE_ACTIVE=false
NATIVE_PATHS=(
    "app/.cxx"
    "app/build/intermediates/cxx"
)

native_state_is_usable_on_host() {
    local cmake_cache ninja_path
    local -a cmake_caches=()

    mapfile -t cmake_caches < <(
        find app/.cxx -type f -name CMakeCache.txt -print 2>/dev/null
    )
    ((${#cmake_caches[@]} > 0)) || return 1

    for cmake_cache in "${cmake_caches[@]}"; do
        ninja_path="$(sed -n 's/^CMAKE_MAKE_PROGRAM:[^=]*=//p' "$cmake_cache" | head -n 1)"
        [[ -x "$ninja_path" ]] || return 1
    done
    return 0
}

restore_native_state() {
    local exit_code=$? path
    trap - EXIT

    if $NATIVE_STATE_ACTIVE; then
        for path in "${NATIVE_PATHS[@]}"; do
            if ! rm -rf -- "$ROOT_DIR/$path"; then
                echo "❌ Não foi possível remover o cache nativo Docker: $ROOT_DIR/$path" >&2
                exit_code=1
            fi
            if [[ -e "$NATIVE_STATE_DIR/$path" ]]; then
                mkdir -p "$(dirname "$ROOT_DIR/$path")" &&
                    mv -- "$NATIVE_STATE_DIR/$path" "$ROOT_DIR/$path" || {
                        echo "❌ Não foi possível restaurar o cache nativo do host: $ROOT_DIR/$path" >&2
                        exit_code=1
                    }
            fi
        done
        rm -rf -- "$NATIVE_STATE_DIR" || {
            echo "❌ Não foi possível remover o diretório temporário $NATIVE_STATE_DIR" >&2
            exit_code=1
        }
    fi

    exit "$exit_code"
}

if native_state_is_usable_on_host; then
    mkdir -p "$NATIVE_STATE_DIR"
    for path in "${NATIVE_PATHS[@]}"; do
        if [[ -e "$path" ]]; then
            mkdir -p "$NATIVE_STATE_DIR/$(dirname "$path")"
            mv -- "$path" "$NATIVE_STATE_DIR/$path"
        fi
    done
elif [[ -e app/.cxx || -e app/build/intermediates/cxx ]]; then
    echo "⚠️  Cache CMake/Ninja aponta para ferramentas indisponíveis no host; removendo cache nativo antigo."
    rm -rf -- app/.cxx app/build/intermediates/cxx
fi
NATIVE_STATE_ACTIVE=true
trap restore_native_state EXIT

if $INTERACTIVE_SHELL; then
    echo "🐚 Shell no container (projeto em /project, FFmpeg em /opt/ffmpeg-android-maker)"
    "$DOCKER" run -it "${RUN_ARGS[@]}" --entrypoint /bin/bash "$IMAGE_NAME"
else
    echo "🚀 Compilando o APK no container..."
    BUILD_ARGS=()
    if $FORCE_CLEAN; then
        BUILD_ARGS+=(--clean)
    fi
    "$DOCKER" run "${RUN_ARGS[@]}" "$IMAGE_NAME" "${BUILD_ARGS[@]}"
    echo "📦 APK: app/build/outputs/apk/debug/app-debug.apk"
fi
