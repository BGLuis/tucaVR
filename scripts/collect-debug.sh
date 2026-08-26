#!/bin/bash
# Coletor automatizado de debug e telemetria — Nível N3 de DEBUG-TELEMETRY-EXPORT.md
#
# Uso:
#   ./scripts/collect-debug.sh
#   ./scripts/collect-debug.sh --serial <serial>
#   ./scripts/collect-debug.sh --out debug-reports/meu-teste
#   ./scripts/collect-debug.sh --bugreport   # inclui bugreport completo do Android
#
set -euo pipefail

PACKAGE="com.tucavr"
SERIAL=""
OUT_DIR=""
INCLUDE_BUGREPORT=0

usage() {
    cat <<EOF
Uso: $0 [opcoes]
  --serial SERIAL     Serial do dispositivo adb (equivalente a 'adb -s')
  --package NAME      Application ID (padrao: com.tucavr)
  --out DIR           Diretorio de saida (padrao: debug-reports/<timestamp>)
  --bugreport         Gera e inclui o bugreport completo do Android (lento e pesado)
  -h, --help          Mostra esta ajuda
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --package) PACKAGE="$2"; shift 2 ;;
        --out) OUT_DIR="$2"; shift 2 ;;
        --bugreport) INCLUDE_BUGREPORT=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Opcao desconhecida: $1"; usage; exit 1 ;;
    esac
done

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB=(adb -s "$SERIAL")
fi

DEVICE_COUNT=$("${ADB[@]}" devices | grep -c -E "device$" || true)
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo "❌ Nenhum dispositivo adb encontrado. Conecte o Quest 3 via USB, autorize a Depuracao USB no headset e tente de novo." >&2
    exit 1
fi
if [[ "$DEVICE_COUNT" -gt 1 && -z "$SERIAL" ]]; then
    echo "⚠️  Mais de um dispositivo adb conectado — use --serial <serial> (veja 'adb devices')." >&2
    exit 1
fi

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
if [[ -z "$OUT_DIR" ]]; then
    OUT_DIR="debug-reports/$TIMESTAMP"
fi
mkdir -p "$OUT_DIR"

echo "=== Coletando dados de diagnóstico para $OUT_DIR ==="

# 1. Manifest
MANIFEST_FILE="$OUT_DIR/manifest.txt"
DEVICE_MODEL=$("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "Desconhecido")
ANDROID_BUILD=$("${ADB[@]}" shell getprop ro.build.display.id 2>/dev/null | tr -d '\r' || echo "Desconhecido")
ANDROID_VER=$("${ADB[@]}" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || echo "Desconhecido")
APP_VERSION=$("${ADB[@]}" shell dumpsys package "$PACKAGE" 2>/dev/null | grep -m1 "versionName" | awk -F= '{print $2}' | tr -d '\r' || echo "Nao instalado")
GIT_COMMIT=$(git rev-parse HEAD 2>/dev/null || echo "Desconhecido")

echo "device_serial: ${SERIAL:-default}" > "$MANIFEST_FILE"
echo "device_model: $DEVICE_MODEL" >> "$MANIFEST_FILE"
echo "android_version: $ANDROID_VER" >> "$MANIFEST_FILE"
echo "android_build: $ANDROID_BUILD" >> "$MANIFEST_FILE"
echo "app_package: $PACKAGE" >> "$MANIFEST_FILE"
echo "app_version: $APP_VERSION" >> "$MANIFEST_FILE"
echo "git_commit: $GIT_COMMIT" >> "$MANIFEST_FILE"
echo "collection_time: $(date -Iseconds)" >> "$MANIFEST_FILE"

# 2. Logcat
echo "Capturando logs do logcat..."
"${ADB[@]}" logcat -d -v time > "$OUT_DIR/logcat.txt" 2>/dev/null || true
grep -E "VRPlayerApp|VRPlayerAppVK|VRPlayerJNI_VK|VRPlayer_Rust|VRPlayer_App|ThermalMonitor" "$OUT_DIR/logcat.txt" > "$OUT_DIR/logcat-filtered.txt" 2>/dev/null || true

# Detecta backend gráfico ativo a partir do logcat
if grep -q "VRPlayerAppVK" "$OUT_DIR/logcat.txt"; then
    GRAPHICS_BACKEND="VULKAN"
elif grep -q "VRPlayerApp" "$OUT_DIR/logcat.txt"; then
    GRAPHICS_BACKEND="GLES"
else
    GRAPHICS_BACKEND="DESCONHECIDO"
fi
echo "graphics_backend_effective: $GRAPHICS_BACKEND" >> "$MANIFEST_FILE"

# 3. Telemetria e Crashes salvos no app
echo "Puxando séries temporais CSV e relatórios de crash..."
REMOTE_DEBUG_DIR="/sdcard/Android/data/$PACKAGE/files/debug"
mkdir -p "$OUT_DIR/telemetry"
if "${ADB[@]}" shell test -d "$REMOTE_DEBUG_DIR" 2>/dev/null; then
    "${ADB[@]}" pull "$REMOTE_DEBUG_DIR" "$OUT_DIR/telemetry/" >/dev/null 2>&1 || true
    echo "  Arquivos de telemetria transferidos com sucesso."
else
    echo "  Pasta de telemetria remota vazia ou inexistente."
fi

# 4. Diagnósticos do Sistema
echo "Coletando dumpsys meminfo e thermalservice..."
"${ADB[@]}" shell dumpsys meminfo "$PACKAGE" > "$OUT_DIR/meminfo.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys thermalservice > "$OUT_DIR/thermalservice.txt" 2>&1 || true

# 5. DropBox / Crashes
"${ADB[@]}" shell dumpsys dropbox --print data_app_crash data_app_anr > "$OUT_DIR/dropbox_crashes.txt" 2>&1 || true

# 6. Bugreport (opcional)
if [[ "$INCLUDE_BUGREPORT" -eq 1 ]]; then
    echo "Gerando bugreport do Android (isso pode levar de 2 a 5 minutos)..."
    "${ADB[@]}" bugreport "$OUT_DIR/bugreport.zip" 2>/dev/null || echo "Falha ao gerar bugreport" >&2
fi

# 7. Empacotamento
ARCHIVE_PATH="${OUT_DIR}.tar.gz"
echo "Compactando artefato em $ARCHIVE_PATH..."
tar -czf "$ARCHIVE_PATH" -C "$(dirname "$OUT_DIR")" "$(basename "$OUT_DIR")"

echo "✅ Coleta concluída com sucesso!"
echo "   Pasta: $OUT_DIR"
echo "   Arquivo compactado: $ARCHIVE_PATH"
echo "   Backend detectado: $GRAPHICS_BACKEND"
