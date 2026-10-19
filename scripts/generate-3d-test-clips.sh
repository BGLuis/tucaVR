#!/bin/bash
# Generates synthetic H.264 clips to visually test each ScreenMode (see the enum in
# native/include/screen_mode.h / rust/bridge/src/lib.rs). Each clip labels the two
# eyes (SBS/OU) or marks 0/25/50/75/100% of the width (mono), so it is obvious in a
# screenshot whether the crop/split is right or the image is duplicated/cut.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$REPO_ROOT/testdata/3d-test-clips"
RESOLUTION="4k"
DURATION_SEC=15
FORCE=0
FONT="/usr/share/fonts/liberation/LiberationSans-Bold.ttf"

usage() {
    cat <<EOF
Usage: $0 [options]
  --resolution 4k|5.7k|8k   Combined frame width (default: 4k = 3840px)
  --duration-sec N          Duration of each clip (default: 15)
  --out-dir PATH             Output directory (default: testdata/3d-test-clips)
  --force                     Regenerates even if the files already exist
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --resolution) RESOLUTION="$2"; shift 2 ;;
        --duration-sec) DURATION_SEC="$2"; shift 2 ;;
        --out-dir) OUT_DIR="$2"; shift 2 ;;
        --force) FORCE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1"; usage; exit 1 ;;
    esac
done

case "$RESOLUTION" in
    4k) W=3840; H=2160 ;;
    5.7k) W=5760; H=2880 ;;
    8k) W=7680; H=4320 ;;
    *) echo "Unknown resolution: $RESOLUTION (use 4k, 5.7k or 8k)" >&2; exit 1 ;;
esac

if ! command -v ffmpeg >/dev/null 2>&1; then
    echo "ffmpeg not found in PATH." >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

encode() {
    local out="$1" filter_complex="$2"
    if [[ -f "$out" && "$FORCE" -eq 0 ]]; then
        echo "Already exists: $out (use --force to regenerate)."
        return
    fi
    ffmpeg -y -hide_banner -loglevel warning \
        -f lavfi -i "sine=frequency=440:sample_rate=48000" \
        -filter_complex "$filter_complex" \
        -t "$DURATION_SEC" -map "[out]" -map 0:a \
        -c:v libx264 -preset veryfast -profile:v high -level 5.2 -pix_fmt yuv420p -b:v 30M -maxrate 30M -bufsize 60M \
        -c:a aac -b:a 128k -movflags +faststart \
        "$out"
    echo "Generated: $out ($(du -h "$out" | cut -f1))"
}

# Markers at 0/25/50/75/100% of the width — checks crop/duplication in mono mode.
mono_markers() {
    local w="$1" fs=$(( $1 / 40 ))
    echo "drawtext=fontfile=$FONT:text='0pct':x=10:y=10:fontsize=$fs:fontcolor=white,"\
"drawtext=fontfile=$FONT:text='25pct':x=w*0.25-40:y=10:fontsize=$fs:fontcolor=yellow,"\
"drawtext=fontfile=$FONT:text='50pct':x=w*0.5-40:y=10:fontsize=$fs:fontcolor=cyan,"\
"drawtext=fontfile=$FONT:text='75pct':x=w*0.75-40:y=10:fontsize=$fs:fontcolor=magenta,"\
"drawtext=fontfile=$FONT:text='100pct':x=w-160:y=10:fontsize=$fs:fontcolor=white"
}

echo "== mono (180/360 mono — the only difference is the mode chosen in the player) =="
encode "$OUT_DIR/mono.mp4" \
    "testsrc2=size=${W}x${H}:rate=30,$(mono_markers "$W")[out]"

echo "== sbs (flat/360/180 SBS — the only difference is the mode chosen in the player) =="
EW=$((W/2))
encode "$OUT_DIR/sbs.mp4" \
    "color=c=green:size=${EW}x${H}:rate=30,drawtext=fontfile=$FONT:text='LEFT':x=(w-tw)/2:y=(h-th)/2:fontsize=$((EW/8)):fontcolor=white,$(mono_markers "$EW")[left];\
color=c=maroon:size=${EW}x${H}:rate=30,drawtext=fontfile=$FONT:text='RIGHT':x=(w-tw)/2:y=(h-th)/2:fontsize=$((EW/8)):fontcolor=white,$(mono_markers "$EW")[right];\
[left][right]hstack=inputs=2[out]"

echo "== ou (flat/360 OU — the only difference is the mode chosen in the player) =="
EH=$((H/2))
encode "$OUT_DIR/ou.mp4" \
    "color=c=green:size=${W}x${EH}:rate=30,drawtext=fontfile=$FONT:text='TOP':x=(w-tw)/2:y=(h-th)/2:fontsize=$((W/20)):fontcolor=white[top];\
color=c=maroon:size=${W}x${EH}:rate=30,drawtext=fontfile=$FONT:text='BOTTOM':x=(w-tw)/2:y=(h-th)/2:fontsize=$((W/20)):fontcolor=white[bottom];\
[top][bottom]vstack=inputs=2[out]"

echo ""
echo "Clips in $OUT_DIR — use them with scripts/test-3d-playback.sh <file> <screen_mode>"
