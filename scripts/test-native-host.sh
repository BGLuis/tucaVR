#!/usr/bin/env bash
set -euo pipefail

# Diretório raiz do repositório
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BUILD_DIR="/tmp/tucavr_native_tests_build"
mkdir -p "$BUILD_DIR"

echo "=== Compilando e Executando Testes Unitários Nativos C++ (Host) ==="

CXX="${CXX:-g++}"

# 1. Testes de Álgebra 3D e Quaternions (vk_math.h)
echo "-> Compilando test_vk_math..."
"$CXX" -std=c++20 -O2 -Wall -Wextra -Werror \
    -I native/include \
    native/tests/test_vk_math.cpp \
    -o "$BUILD_DIR/test_vk_math"

echo "-> Executando test_vk_math..."
"$BUILD_DIR/test_vk_math"

# 2. Testes da Máquina de Estados de Input / Debounce (C-01)
echo "-> Compilando test_input_fsm..."
"$CXX" -std=c++20 -O2 -Wall -Wextra -Werror \
    -I native/include \
    native/tests/test_input_fsm.cpp \
    -o "$BUILD_DIR/test_input_fsm"

echo "-> Executando test_input_fsm..."
"$BUILD_DIR/test_input_fsm"

# 3. Testes do Contrato ScreenMode
echo "-> Compilando test_screen_mode..."
"$CXX" -std=c++20 -O2 -Wall -Wextra -Werror \
    -I native/include \
    native/tests/test_screen_mode.cpp \
    -o "$BUILD_DIR/test_screen_mode"

echo "-> Executando test_screen_mode..."
"$BUILD_DIR/test_screen_mode"

echo "=== Todos os 3 binários de teste C++ passaram com sucesso! ==="
rm -rf "$BUILD_DIR"
