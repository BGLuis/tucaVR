#!/bin/bash
# =============================================================================
# tucaVR — Entrypoint do container Docker de build
#
# Orquestra o build completo:
#   1. Cria symlink do ffmpeg-android-maker pré-compilado
#   2. Cross-compila o workspace Rust via cargo ndk
#   3. Copia .so do FFmpeg para jniLibs
#   4. Executa ./gradlew assembleDebug (Kotlin + C++/CMake)
#   5. Corrige ownership dos artefatos de saída
# =============================================================================
set -euo pipefail

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║              tucaVR — Docker Build                         ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

ROOT_DIR="/project"
cd "$ROOT_DIR"

# ── Verifica que o projeto está montado ──────────────────────────────────────
if [ ! -f "build.gradle.kts" ]; then
    echo "❌ ERRO: Código-fonte do projeto não encontrado em /project"
    echo "   Monte o projeto com: -v \"\$(pwd)\":/project"
    exit 1
fi

# ── Verifica o Meta OpenXR SDK ───────────────────────────────────────────────
if [ ! -d "sdk/meta-openxr-sdk/Samples/SampleXrFramework" ]; then
    echo "❌ ERRO: Meta OpenXR SDK não encontrado em sdk/meta-openxr-sdk/"
    echo ""
    echo "   Esse SDK requer download manual com aceite de licença:"
    echo "   https://developers.meta.com/horizon/downloads/package/oculus-openxr-mobile-sdk/"
    echo ""
    echo "   Extraia para sdk/meta-openxr-sdk/ e tente novamente."
    exit 1
fi

# ── Symlink do ffmpeg-android-maker pré-compilado ────────────────────────────
FFMPEG_MAKER_DIR="/opt/ffmpeg-android-maker"
if [ ! -d "$ROOT_DIR/ffmpeg-android-maker" ]; then
    echo "📎 Criando symlink ffmpeg-android-maker → ${FFMPEG_MAKER_DIR}"
    ln -sf "$FFMPEG_MAKER_DIR" "$ROOT_DIR/ffmpeg-android-maker"
elif [ ! -d "$ROOT_DIR/ffmpeg-android-maker/build/ffmpeg/arm64-v8a" ]; then
    echo "📎 ffmpeg-android-maker existe mas sem build — redirecionando para pré-compilado"
    rm -rf "$ROOT_DIR/ffmpeg-android-maker"
    ln -sf "$FFMPEG_MAKER_DIR" "$ROOT_DIR/ffmpeg-android-maker"
else
    echo "✅ Usando ffmpeg-android-maker existente no projeto"
fi

# ── Variáveis de ambiente para cross-compilation ─────────────────────────────
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/26.3.11579264}"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export PKG_CONFIG_ALLOW_CROSS=1

# Detecta onde estão os pkgconfig do FFmpeg
FFMPEG_LIB_DIR=""
if [ -d "$ROOT_DIR/ffmpeg-android-maker/build/ffmpeg/arm64-v8a/lib" ]; then
    FFMPEG_LIB_DIR="$ROOT_DIR/ffmpeg-android-maker/build/ffmpeg/arm64-v8a/lib"
fi
export PKG_CONFIG_PATH="${FFMPEG_LIB_DIR}/pkgconfig"

# Detecta onde estão os headers do FFmpeg
FFMPEG_INCLUDE_DIR=""
if [ -d "$ROOT_DIR/ffmpeg-android-maker/build/ffmpeg/arm64-v8a/include" ]; then
    FFMPEG_INCLUDE_DIR="$ROOT_DIR/ffmpeg-android-maker/build/ffmpeg/arm64-v8a/include"
elif [ -d "$ROOT_DIR/ffmpeg-android-maker/output/include/arm64-v8a" ]; then
    FFMPEG_INCLUDE_DIR="$ROOT_DIR/ffmpeg-android-maker/output/include/arm64-v8a"
fi

NDK_SYSROOT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
export BINDGEN_EXTRA_CLANG_ARGS="-I${FFMPEG_INCLUDE_DIR} -I${NDK_SYSROOT}/usr/include --sysroot=${NDK_SYSROOT}"

echo ""
echo "📋 Configuração:"
echo "   ANDROID_NDK_HOME   = $ANDROID_NDK_HOME"
echo "   PKG_CONFIG_PATH    = $PKG_CONFIG_PATH"
echo "   FFMPEG_INCLUDE_DIR = $FFMPEG_INCLUDE_DIR"
echo ""

# ── 1. Cross-compilar Rust via cargo ndk ─────────────────────────────────────
echo "══════════════════════════════════════════════════════════════"
echo "🦀 [1/3] Compilando Rust workspace (aarch64-linux-android)..."
echo "══════════════════════════════════════════════════════════════"
cd "$ROOT_DIR/rust"
cargo ndk -t aarch64-linux-android -P 26 -o ../app/src/main/jniLibs build --release
cd "$ROOT_DIR"

# ── 2. Copiar .so do FFmpeg para jniLibs ─────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════════════════════"
echo "📦 [2/3] Copiando bibliotecas FFmpeg para jniLibs..."
echo "══════════════════════════════════════════════════════════════"
mkdir -p app/src/main/jniLibs/arm64-v8a
if [ -n "$FFMPEG_LIB_DIR" ]; then
    cp "${FFMPEG_LIB_DIR}"/*.so app/src/main/jniLibs/arm64-v8a/ 2>/dev/null || true
    echo "✅ FFmpeg .so copiados para app/src/main/jniLibs/arm64-v8a/"
    ls -la app/src/main/jniLibs/arm64-v8a/*.so 2>/dev/null | awk '{print "   " $NF " (" $5 " bytes)"}'
else
    echo "⚠️  Nenhum .so FFmpeg encontrado — build pode falhar"
fi

# ── 3. Gradle assembleDebug ──────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════════════════════"
echo "🤖 [3/3] Compilando Android App (Gradle assembleDebug)..."
echo "══════════════════════════════════════════════════════════════"
GRADLE_ARGS=()
if [ -n "${APP_VERSION_NAME:-}" ]; then
    GRADLE_ARGS+=("-PappVersionName=$APP_VERSION_NAME")
fi
if [ -n "${APP_VERSION_CODE:-}" ]; then
    GRADLE_ARGS+=("-PappVersionCode=$APP_VERSION_CODE")
fi

# Marca o diretório como seguro para git (evita erro "dubious ownership")
git config --global --add safe.directory /project

./gradlew assembleDebug "${GRADLE_ARGS[@]}"

# ── Resultado ────────────────────────────────────────────────────────────────
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "══════════════════════════════════════════════════════════════"
if [ -f "$APK_PATH" ]; then
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    echo "✅ Build concluído com sucesso!"
    echo ""
    echo "   APK: $APK_PATH ($APK_SIZE)"
    echo ""

    # Corrige ownership dos artefatos para o usuário do host
    if [ -n "${HOST_UID:-}" ] && [ -n "${HOST_GID:-}" ]; then
        chown -R "$HOST_UID:$HOST_GID" app/build/ 2>/dev/null || true
        chown -R "$HOST_UID:$HOST_GID" app/src/main/jniLibs/ 2>/dev/null || true
        chown -R "$HOST_UID:$HOST_GID" rust/target/ 2>/dev/null || true
        chown -R "$HOST_UID:$HOST_GID" .gradle/ 2>/dev/null || true
        echo "   (ownership corrigido para UID=$HOST_UID:GID=$HOST_GID)"
    fi
else
    echo "❌ Build falhou — APK não encontrado em $APK_PATH"
    exit 1
fi
echo "══════════════════════════════════════════════════════════════"
echo ""
