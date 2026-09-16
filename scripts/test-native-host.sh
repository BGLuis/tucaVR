#!/usr/bin/env bash
set -euo pipefail

# Diretório raiz do repositório
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BUILD_DIR="/tmp/tucavr_native_tests_build"
mkdir -p "$BUILD_DIR"

echo "=== Compilando e Executando Testes Unitários Nativos C++ (Host) ==="

CXX="${CXX:-g++}"
# ccache acelera recompilações quando disponível no PATH (ex.: CI com ccache-action).
if command -v ccache >/dev/null 2>&1 && [ "$CXX" = "g++" ]; then
    CXX="ccache g++"
fi

TESTS=(
    "test_vk_math"
    "test_input_fsm"
    "test_screen_mode"
    "test_subtitle_layout"
    "test_hand_tracking"
    "test_environment_config"
)

compile_test() {
    local name="$1"
    $CXX -std=c++20 -O2 -Wall -Wextra -Werror \
        -I native/include \
        "native/tests/${name}.cpp" \
        -o "$BUILD_DIR/${name}"
}

echo "-> Compilando os ${#TESTS[@]} binários de teste em paralelo..."
PIDS=()
for name in "${TESTS[@]}"; do
    compile_test "$name" &
    PIDS+=("$!")
done

FAILED=0
for pid in "${PIDS[@]}"; do
    if ! wait "$pid"; then
        FAILED=1
    fi
done

if [ "$FAILED" -ne 0 ]; then
    echo "❌ Falha ao compilar um ou mais binários de teste." >&2
    exit 1
fi

for name in "${TESTS[@]}"; do
    echo "-> Executando ${name}..."
    "$BUILD_DIR/${name}"
done

echo "=== Todos os ${#TESTS[@]} binários de teste C++ passaram com sucesso! ==="
rm -rf "$BUILD_DIR"
