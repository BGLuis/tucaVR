#!/bin/bash
# Toca um clipe no Quest 3 num ScreenMode especifico (via EXTRA_SCREEN_MODE, ver
# VRActivity.kt/nativeSetScreenMode) e tira um screenshot pra inspecao visual.
# Uso: ./scripts/test-3d-playback.sh <clipe.mp4> <modo> [--serial X] [--wait-sec N]
#   <modo> pode ser o indice numerico (0-9) ou um nome: 2d sbs sbshalf ou ouhalf
#          mono360 mono180 sbs360 ou360 sbs180
set -euo pipefail

PACKAGE="com.tucavr"
ACTIVITY=".VRActivity"
DEVICE_PATH="/sdcard/Movies/vr-test-clip.mp4"
WAIT_SEC=8
SERIAL=""
OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/testdata/3d-test-clips/screenshots"

if [[ $# -lt 2 ]]; then
    echo "Uso: $0 <clipe.mp4> <modo> [--serial X] [--wait-sec N]" >&2
    exit 1
fi
CLIP="$1"; MODE_ARG="$2"; shift 2

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --wait-sec) WAIT_SEC="$2"; shift 2 ;;
        *) echo "Opcao desconhecida: $1" >&2; exit 1 ;;
    esac
done

case "$MODE_ARG" in
    2d) MODE=0 ;; sbs) MODE=1 ;; sbshalf) MODE=2 ;; ou) MODE=3 ;; ouhalf) MODE=4 ;;
    mono360) MODE=5 ;; mono180) MODE=6 ;; sbs360) MODE=7 ;; ou360) MODE=8 ;; sbs180) MODE=9 ;;
    [0-9]) MODE="$MODE_ARG" ;;
    *) echo "Modo desconhecido: $MODE_ARG" >&2; exit 1 ;;
esac

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

if [[ ! -f "$CLIP" ]]; then
    echo "Arquivo nao encontrado: $CLIP" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

echo "Enviando $CLIP para $DEVICE_PATH..."
"${ADB[@]}" shell mkdir -p /sdcard/Movies
"${ADB[@]}" push "$CLIP" "$DEVICE_PATH"

echo "Reiniciando app com modo $MODE..."
"${ADB[@]}" shell am force-stop "$PACKAGE" || true
sleep 1
"${ADB[@]}" shell am start -n "$PACKAGE/$ACTIVITY" -e video_path "$DEVICE_PATH" --ei screen_mode "$MODE"

echo "Aguardando ${WAIT_SEC}s pra playback estabilizar..."
sleep "$WAIT_SEC"

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
CAPTURE_DEVICE="/sdcard/vr-frame-capture.ppm"
CAPTURE_LOCAL_PPM="$OUT_DIR/${TIMESTAMP}_mode${MODE}.ppm"
CAPTURE_LOCAL_PNG="$OUT_DIR/${TIMESTAMP}_mode${MODE}.png"

# screencap/screenrecord nao funcionam em vr_only (compositor OpenXR direto, sem
# layer 2D capturavel) — usa o dump via glReadPixels (nativeRequestFrameCapture).
"${ADB[@]}" shell rm -f "$CAPTURE_DEVICE"
"${ADB[@]}" shell am start -n "$PACKAGE/$ACTIVITY" -e capture_path "$CAPTURE_DEVICE"
sleep 2
"${ADB[@]}" pull "$CAPTURE_DEVICE" "$CAPTURE_LOCAL_PPM" >/dev/null
"${ADB[@]}" shell rm -f "$CAPTURE_DEVICE"

if command -v ffmpeg >/dev/null 2>&1; then
    ffmpeg -y -hide_banner -loglevel error -i "$CAPTURE_LOCAL_PPM" "$CAPTURE_LOCAL_PNG"
    rm -f "$CAPTURE_LOCAL_PPM"
    echo "Screenshot: $CAPTURE_LOCAL_PNG"
else
    echo "Screenshot (PPM, ffmpeg nao encontrado pra converter): $CAPTURE_LOCAL_PPM"
fi
