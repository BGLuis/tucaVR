#!/bin/bash
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FORCE_CLEAN=false
for arg in "$@"; do
    case "$arg" in
        --clean) FORCE_CLEAN=true ;;
        *)
            echo "❌ Argumento desconhecido: $arg (use --clean)" >&2
            exit 1
            ;;
    esac
done

echo "🚀 Iniciando o build unificado (tucaVR)..."

if $FORCE_CLEAN; then
    echo "🧹 Limpando artefatos Android..."
    # Remove CMake state explicitly: its Ninja/SDK paths can belong to Docker.
    rm -rf -- app/.cxx app/build
fi

# Gradle runs buildRust before preBuild, then builds the native C++ layer and APK.
echo "🤖 Compilando Android App..."
GRADLE_ARGS=()
if $FORCE_CLEAN; then
    ./gradlew clean
fi
if [ -n "$APP_VERSION_NAME" ]; then
    GRADLE_ARGS+=("-PappVersionName=$APP_VERSION_NAME")
fi
if [ -n "$APP_VERSION_CODE" ]; then
    GRADLE_ARGS+=("-PappVersionCode=$APP_VERSION_CODE")
fi

./gradlew assembleDebug "${GRADLE_ARGS[@]}"

echo "✅ Build concluído com sucesso!"
