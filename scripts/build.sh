#!/bin/bash
set -e

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "🚀 Iniciando o build unificado (tucaVR)..."

# 1. Compilar Rust via cargo ndk
echo "🦀 Compilando Rust Core (aarch64-linux-android)..."
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/26.3.11579264}
export ANDROID_NDK_ROOT=$ANDROID_NDK_HOME
export PKG_CONFIG_ALLOW_CROSS=1
export PKG_CONFIG_PATH="$ROOT_DIR/ffmpeg-android-maker/build/ffmpeg/arm64-v8a/lib/pkgconfig"
export BINDGEN_EXTRA_CLANG_ARGS="-I$ROOT_DIR/ffmpeg-android-maker/output/include/arm64-v8a -I$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include --sysroot=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot"

cd rust
# cargo ndk copia automaticamente os .so se usarmos o -o
cargo ndk -t aarch64-linux-android -P 26 -o ../app/src/main/jniLibs build --release

# Copy FFmpeg shared libraries
cp ../ffmpeg-android-maker/build/ffmpeg/arm64-v8a/lib/*.so ../app/src/main/jniLibs/arm64-v8a/ || true

cd ..

# 2. Invocar Gradle Build
echo "🤖 Compilando Android App..."
./gradlew assembleDebug

echo "✅ Build concluído com sucesso!"
