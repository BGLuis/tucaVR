#!/bin/bash
# Objective, automated test of the DoD criterion "Memory < 2.5GB during 4K video
# playback" (docs/phases/PHASE-0.1-MVP.md) — with no need for a real 4K file or
# manual interaction with the controller: it generates a synthetic, reproducible
# 4K H.264 clip, sends it to the headset over adb, triggers automatic playback
# (EXTRA_AUTO_PLAY_PATH, see VRActivity.kt) and reuses the memory sampling of
# scripts/soak-test.sh.
#
# Usage:
#   ./scripts/test-4k-memory.sh                         # default: 150s clip, monitors for 2min
#   ./scripts/test-4k-memory.sh --duration-min 4 --clip-duration-sec 300
#
# Difference from plain scripts/soak-test.sh: that one measures the app however it
# is when called (idle, or with --video-path if you already have a file on the
# headset); this script guarantees a real, reproducible end-to-end 4K workload,
# which is what the DoD criterion specifically asks for.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DURATION_MIN=2
INTERVAL_SEC=10
CLIP_DURATION_SEC=150
BITRATE="25M"
SERIAL=""
FORCE_REGEN=0
KEEP_ON_DEVICE=0
DEVICE_PATH="/sdcard/Movies/vrplayer-4k-soak-test.mp4"

usage() {
    cat <<EOF
Usage: $0 [options]
  --duration-min N        Duration of the memory monitoring (default: 2)
  --interval-sec N        Interval between samples (default: 10)
  --clip-duration-sec N   Duration of the generated 4K clip (default: 150; must be
                           greater than duration-min*60 + ~30s of margin)
  --bitrate RATE           Clip bitrate, ffmpeg format (default: 25M)
  --serial SERIAL          adb device serial
  --force-regen             Regenerates the clip even if it already exists in testdata/
  --keep-on-device          Does not delete the clip from the headset at the end
  -h, --help                 Shows this help
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --duration-min) DURATION_MIN="$2"; shift 2 ;;
        --interval-sec) INTERVAL_SEC="$2"; shift 2 ;;
        --clip-duration-sec) CLIP_DURATION_SEC="$2"; shift 2 ;;
        --bitrate) BITRATE="$2"; shift 2 ;;
        --serial) SERIAL="$2"; shift 2 ;;
        --force-regen) FORCE_REGEN=1; shift ;;
        --keep-on-device) KEEP_ON_DEVICE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1"; usage; exit 1 ;;
    esac
done

NEEDED_SEC=$((DURATION_MIN * 60 + 30))
if [[ "$CLIP_DURATION_SEC" -lt "$NEEDED_SEC" ]]; then
    echo "WARNING: clip-duration-sec ($CLIP_DURATION_SEC) is smaller than duration-min*60+30 ($NEEDED_SEC)." >&2
    echo "         The video may end before the monitoring finishes, measuring the idle app at the end." >&2
fi

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB=(adb -s "$SERIAL")
fi

DEVICE_COUNT=$("${ADB[@]}" devices | grep -c -E "device$" || true)
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo "No adb device found. Connect the Quest 3 over USB and authorize USB debugging." >&2
    exit 1
fi

echo "== Generating the 4K test clip =="
GEN_ARGS=(--duration-sec "$CLIP_DURATION_SEC" --bitrate "$BITRATE")
[[ "$FORCE_REGEN" -eq 1 ]] && GEN_ARGS+=(--force)
"$SCRIPT_DIR/generate-4k-test-clip.sh" "${GEN_ARGS[@]}"
CLIP="$REPO_ROOT/testdata/4k-soak-clip.mp4"

echo "== Sending the clip to the headset ($DEVICE_PATH) =="
"${ADB[@]}" shell mkdir -p /sdcard/Movies
"${ADB[@]}" push "$CLIP" "$DEVICE_PATH"

cleanup() {
    if [[ "$KEEP_ON_DEVICE" -eq 0 ]]; then
        "${ADB[@]}" shell rm -f "$DEVICE_PATH" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

echo "== Running soak-test.sh with auto-play of the 4K clip =="
SOAK_ARGS=(--video-path "$DEVICE_PATH" --duration-min "$DURATION_MIN" --interval-sec "$INTERVAL_SEC")
[[ -n "$SERIAL" ]] && SOAK_ARGS+=(--serial "$SERIAL")

"$SCRIPT_DIR/soak-test.sh" "${SOAK_ARGS[@]}"
