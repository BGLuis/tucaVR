#!/bin/bash
# Generates a synthetic 4K H.264 test clip with 1px, 2px, and 3px line pairs
# (horizontal and vertical) alongside multi-scale text patterns, reproducible and
# decodable by the Quest 3 hardware decoder (H.264 High@5.2).
#
# Used for evaluating flat video downscaling and anti-aliasing/shimmering reduction
# on the Meta Quest 3 panel (Issue #250).
set -euo pipefail

DURATION_SEC=30
BITRATE="30M"
OUT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/testdata/4k-downscale-test-clip.mp4"
FORCE=0

usage() {
    cat <<EOF
Usage: $0 [options]
  --duration-sec N   Clip duration in seconds (default: 30)
  --bitrate RATE     Video bitrate, ffmpeg format (default: 30M)
  --out PATH         Output path (default: testdata/4k-downscale-test-clip.mp4)
  --force            Regenerates even if the file already exists
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --duration-sec) DURATION_SEC="$2"; shift 2 ;;
        --bitrate) BITRATE="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --force) FORCE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1"; usage; exit 1 ;;
    esac
done

if ! command -v ffmpeg >/dev/null 2>&1; then
    echo "ffmpeg not found in PATH — required to generate the test clip." >&2
    exit 1
fi

if [[ -f "$OUT" && "$FORCE" -eq 0 ]]; then
    echo "Already exists: $OUT ($(du -h "$OUT" | cut -f1)). Use --force to regenerate."
    exit 0
fi

mkdir -p "$(dirname "$OUT")"

echo "Generating the 4K downscale test clip (${DURATION_SEC}s @ ${BITRATE})..."

FONT_OPT=""
for f in /usr/share/fonts/liberation/LiberationSans-Regular.ttf \
         /usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf \
         /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf; do
    if [[ -f "$f" ]]; then
        FONT_OPT=":fontfile=$f"
        break
    fi
done

ffmpeg -y -hide_banner -loglevel warning \
    -f lavfi -i "color=c=0x181818:s=3840x2160:r=30" \
    -f lavfi -i "color=c=black:s=1000x500:r=30,drawgrid=w=3840:h=2:t=1:c=white" \
    -f lavfi -i "color=c=black:s=1000x500:r=30,drawgrid=w=3840:h=4:t=2:c=white" \
    -f lavfi -i "color=c=black:s=1000x500:r=30,drawgrid=w=3840:h=6:t=3:c=white" \
    -f lavfi -i "color=c=black:s=1000x500:r=30,drawgrid=w=2:h=2160:t=1:c=white" \
    -f lavfi -i "color=c=black:s=1000x500:r=30,drawgrid=w=4:h=2160:t=2:c=white" \
    -f lavfi -i "color=c=black:s=1000x500:r=30,drawgrid=w=6:h=2160:t=3:c=white" \
    -f lavfi -i "sine=frequency=440:sample_rate=48000" \
    -filter_complex "\
[0:v][1:v]overlay=160:300[v1]; \
[v1][2:v]overlay=1420:300[v2]; \
[v2][3:v]overlay=2680:300[v3]; \
[v3][4:v]overlay=160:920[v4]; \
[v4][5:v]overlay=1420:920[v5]; \
[v5][6:v]overlay=2680:920[v6]; \
[v6]\
drawtext=text='tucaVR 4K Downscaling & Anti-Aliasing Test Pattern (3840x2160)'${FONT_OPT}:fontsize=54:fontcolor=white:x=160:y=80,\
drawtext=text='Evaluation of line pair resolution and micro-contrast on Meta Quest 3 virtual screens (Issue #250)'${FONT_OPT}:fontsize=32:fontcolor=0xAAAAAA:x=160:y=155,\
drawtext=text='1px Horizontal Line Pairs (2160 lp/ph - Nyquist)'${FONT_OPT}:fontsize=28:fontcolor=white:x=160:y=255,\
drawtext=text='2px Horizontal Line Pairs (1080 lp/ph)'${FONT_OPT}:fontsize=28:fontcolor=white:x=1420:y=255,\
drawtext=text='3px Horizontal Line Pairs (720 lp/ph)'${FONT_OPT}:fontsize=28:fontcolor=white:x=2680:y=255,\
drawtext=text='1px Vertical Line Pairs (3840 lp/pw - Nyquist)'${FONT_OPT}:fontsize=28:fontcolor=white:x=160:y=875,\
drawtext=text='2px Vertical Line Pairs (1920 lp/pw)'${FONT_OPT}:fontsize=28:fontcolor=white:x=1420:y=875,\
drawtext=text='3px Vertical Line Pairs (1280 lp/pw)'${FONT_OPT}:fontsize=28:fontcolor=white:x=2680:y=875,\
drawtext=text='LEGIBILITY & CONTRAST THRESHOLD TEST'${FONT_OPT}:fontsize=38:fontcolor=0xFFCC00:x=160:y=1520,\
drawtext=text='32px - The quick brown fox jumps over the lazy dog. 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ'${FONT_OPT}:fontsize=32:fontcolor=white:x=160:y=1580,\
drawtext=text='24px - The quick brown fox jumps over the lazy dog. 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ'${FONT_OPT}:fontsize=24:fontcolor=white:x=160:y=1630,\
drawtext=text='20px - The quick brown fox jumps over the lazy dog. 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ'${FONT_OPT}:fontsize=20:fontcolor=white:x=160:y=1672,\
drawtext=text='16px - The quick brown fox jumps over the lazy dog. 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ'${FONT_OPT}:fontsize=16:fontcolor=white:x=160:y=1710,\
drawtext=text='14px - The quick brown fox jumps over the lazy dog. 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ'${FONT_OPT}:fontsize=14:fontcolor=white:x=160:y=1742,\
drawtext=text='12px - The quick brown fox jumps over the lazy dog. 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ'${FONT_OPT}:fontsize=12:fontcolor=white:x=160:y=1770,\
drawtext=text='10px - The quick brown fox jumps over the lazy dog. 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ'${FONT_OPT}:fontsize=10:fontcolor=white:x=160:y=1795,\
drawtext=text='Multi-tap area downsampling eliminates shimmering and moire artifacts during head rotation.'${FONT_OPT}:fontsize=28:fontcolor=0x44DD66:x=160:y=1845\
[outv]" \
    -map "[outv]" -map 7:a \
    -t "$DURATION_SEC" \
    -c:v libx264 -preset veryfast -profile:v high -level 5.2 -pix_fmt yuv420p \
    -b:v "$BITRATE" -maxrate "$BITRATE" -bufsize "50M" \
    -c:a aac -b:a 128k \
    -movflags +faststart \
    "$OUT"

echo "Generated: $OUT ($(du -h "$OUT" | cut -f1))"
