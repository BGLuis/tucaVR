#!/bin/bash
# Generates a synthetic 4K H.264 clip (test pattern + sine tone), reproducible and
# decodable by the Quest 3's hardware (H.264 High@5.2, doc section 2: "H.264 up to
# 4K@60fps"), to serve as an objective test load for the DoD criterion "Memory <
# 2.5GB during 4K video playback" (docs/phases/PHASE-0.1-MVP.md). Used by
# scripts/test-4k-memory.sh — you normally do not need to run this by hand.
#
# It deliberately does not generate "real" video (footage): testsrc2 and sine are
# deterministic, depend on no external media file, and generate in seconds even at
# 4K (measured: ~2.8s for 10s of content with the veryfast preset).
set -euo pipefail

DURATION_SEC=150
BITRATE="25M"
OUT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/testdata/4k-soak-clip.mp4"
FORCE=0

usage() {
    cat <<EOF
Usage: $0 [options]
  --duration-sec N   Clip duration in seconds (default: 150)
  --bitrate RATE      Video bitrate, ffmpeg format (default: 25M)
  --out PATH           Output path (default: testdata/4k-soak-clip.mp4)
  --force               Regenerates even if the file already exists
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

echo "Generating the 4K test clip (${DURATION_SEC}s @ ${BITRATE})..."
ffmpeg -y -hide_banner -loglevel warning \
    -f lavfi -i "testsrc2=size=3840x2160:rate=30" \
    -f lavfi -i "sine=frequency=440:sample_rate=48000" \
    -t "$DURATION_SEC" \
    -c:v libx264 -preset veryfast -profile:v high -level 5.2 -pix_fmt yuv420p \
    -b:v "$BITRATE" -maxrate "$BITRATE" -bufsize "50M" \
    -c:a aac -b:a 128k \
    -movflags +faststart \
    "$OUT"

echo "Generated: $OUT ($(du -h "$OUT" | cut -f1))"
