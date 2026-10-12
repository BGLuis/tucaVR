#!/bin/bash
# Plays a clip on the Quest 3 in a specific ScreenMode (through EXTRA_SCREEN_MODE, see
# VRActivity.kt/nativeSetScreenMode) and takes a screenshot for visual inspection.
# Usage: ./scripts/test-3d-playback.sh <clip.mp4> <mode> [--serial X] [--wait-sec N]
#   <mode> can be the numeric index (0-9) or a name: 2d sbs sbshalf ou ouhalf
#          mono360 mono180 sbs360 ou360 sbs180
set -euo pipefail

PACKAGE="com.tucavr"
ACTIVITY=".VRActivity"
DEVICE_PATH="/sdcard/Movies/vr-test-clip.mp4"
WAIT_SEC=8
SERIAL=""
OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/testdata/3d-test-clips/screenshots"

if [[ $# -lt 2 ]]; then
    echo "Usage: $0 <clip.mp4> <mode> [--serial X] [--wait-sec N]" >&2
    exit 1
fi
CLIP="$1"; MODE_ARG="$2"; shift 2

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --wait-sec) WAIT_SEC="$2"; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

case "$MODE_ARG" in
    2d) MODE=0 ;; sbs) MODE=1 ;; sbshalf) MODE=2 ;; ou) MODE=3 ;; ouhalf) MODE=4 ;;
    mono360) MODE=5 ;; mono180) MODE=6 ;; sbs360) MODE=7 ;; ou360) MODE=8 ;; sbs180) MODE=9 ;;
    [0-9]) MODE="$MODE_ARG" ;;
    *) echo "Unknown mode: $MODE_ARG" >&2; exit 1 ;;
esac

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

if [[ ! -f "$CLIP" ]]; then
    echo "File not found: $CLIP" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

echo "Sending $CLIP to $DEVICE_PATH..."
"${ADB[@]}" shell mkdir -p /sdcard/Movies
"${ADB[@]}" push "$CLIP" "$DEVICE_PATH"

echo "Restarting the app with mode $MODE..."
"${ADB[@]}" shell am force-stop "$PACKAGE" || true
sleep 1
"${ADB[@]}" shell am start -n "$PACKAGE/$ACTIVITY" -e video_path "$DEVICE_PATH" --ei screen_mode "$MODE"

echo "Waiting ${WAIT_SEC}s for playback to settle..."
sleep "$WAIT_SEC"

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
CAPTURE_DEVICE="/sdcard/vr-frame-capture.ppm"
CAPTURE_LOCAL_PPM="$OUT_DIR/${TIMESTAMP}_mode${MODE}.ppm"
CAPTURE_LOCAL_PNG="$OUT_DIR/${TIMESTAMP}_mode${MODE}.png"

# screencap/screenrecord do not work in vr_only (direct OpenXR compositor, no
# capturable 2D layer) — use the native dump (nativeRequestFrameCapture).
"${ADB[@]}" shell rm -f "$CAPTURE_DEVICE" "${CAPTURE_DEVICE}.left.ppm" "${CAPTURE_DEVICE}.right.ppm"
"${ADB[@]}" shell am start -n "$PACKAGE/$ACTIVITY" -e capture_path "$CAPTURE_DEVICE"
sleep 2

# Check that the file was generated on the device before trying to pull it
if "${ADB[@]}" shell test -f "${CAPTURE_DEVICE}.left.ppm"; then
    "${ADB[@]}" pull "${CAPTURE_DEVICE}.left.ppm" "$CAPTURE_LOCAL_PPM" >/dev/null
    "${ADB[@]}" shell rm -f "${CAPTURE_DEVICE}.left.ppm" "${CAPTURE_DEVICE}.right.ppm"
elif "${ADB[@]}" shell test -f "$CAPTURE_DEVICE"; then
    "${ADB[@]}" pull "$CAPTURE_DEVICE" "$CAPTURE_LOCAL_PPM" >/dev/null
    "${ADB[@]}" shell rm -f "$CAPTURE_DEVICE"
else
    echo "❌ ERROR: Frame capture failed — no file generated at $CAPTURE_DEVICE (.left.ppm) on the device." >&2
    echo "   Check that the player is playing video and that the active graphics backend supports readback." >&2
    exit 1
fi

if command -v ffmpeg >/dev/null 2>&1; then
    ffmpeg -y -hide_banner -loglevel error -i "$CAPTURE_LOCAL_PPM" "$CAPTURE_LOCAL_PNG"
    rm -f "$CAPTURE_LOCAL_PPM"
    echo "Screenshot: $CAPTURE_LOCAL_PNG"
else
    echo "Screenshot (PPM, ffmpeg not found to convert it): $CAPTURE_LOCAL_PPM"
fi
