#!/usr/bin/env bash
set -euo pipefail

# Diretório raiz do repositório
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "=== Verificando Integridade dos Shaders Vulkan (GLSL -> SPIR-V) ==="

GLSLC=""
POSSIBLE_PATHS=(
    "glslc"
    "${ANDROID_NDK_HOME:-}/shader-tools/linux-x86_64/glslc"
    "/opt/android-ndk/shader-tools/linux-x86_64/glslc"
    "${ANDROID_HOME:-}/ndk/26.3.11579264/shader-tools/linux-x86_64/glslc"
)

for p in "${POSSIBLE_PATHS[@]}"; do
    if [ -n "$p" ] && command -v "$p" >/dev/null 2>&1; then
        GLSLC="$p"
        break
    fi
done

if [ -z "$GLSLC" ] && [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/ndk" ]; then
    FOUND=$(find "${ANDROID_HOME}/ndk" -name glslc -type f 2>/dev/null | head -n 1 || true)
    if [ -n "$FOUND" ] && [ -x "$FOUND" ]; then
        GLSLC="$FOUND"
    fi
fi

if [ -z "$GLSLC" ]; then
    echo "⚠️  Aviso: glslc não encontrado no PATH nem no NDK. Tentando glslangValidator..."
    if command -v glslangValidator >/dev/null 2>&1; then
        echo "-> Utilizando glslangValidator para validar shaders..."
        SHADERS=(native/shaders/vulkan/*.vert native/shaders/vulkan/*.frag)
        FAILED=0
        for s in "${SHADERS[@]}"; do
            if ! glslangValidator -V "$s" -o /dev/null >/dev/null 2>&1; then
                echo "❌ Falha na validação do shader: $s" >&2
                FAILED=1
            else
                echo "✓ $s"
            fi
        done
        if [ "$FAILED" -ne 0 ]; then
            exit 1
        fi
        echo "=== Todos os ${#SHADERS[@]} shaders validados com sucesso via glslangValidator! ==="
        exit 0
    else
        echo "❌ Erro: glslc ou glslangValidator são necessários para verificar os shaders." >&2
        exit 1
    fi
fi

echo "-> Utilizando compilador: $GLSLC"
SHADERS=(native/shaders/vulkan/*.vert native/shaders/vulkan/*.frag)
FAILED=0
TMP_OUT=$(mktemp)

for s in "${SHADERS[@]}"; do
    if ! "$GLSLC" --target-env=vulkan1.1 -c "$s" -o "$TMP_OUT"; then
        echo "❌ Erro de compilação no shader: $s" >&2
        FAILED=1
    else
        echo "✓ $s"
    fi
done

rm -f "$TMP_OUT"

if [ "$FAILED" -ne 0 ]; then
    echo "❌ Um ou mais shaders falharam na compilação." >&2
    exit 1
fi

echo "=== Todos os ${#SHADERS[@]} shaders Vulkan foram compilados e validados com sucesso! ==="
