#!/usr/bin/env bash
# Compiles FFmpeg for arm64-v8a (Android API 26) via ffmpeg-android-maker,
# omitting unused encoders, muxers, programs, docs, and unused sub-libraries
# (avdevice, avfilter, postproc) while preserving -O3 and all decoders/demuxers.
set -euo pipefail

MAKER_DIR="${1:-ffmpeg-android-maker}"

export TUCAVR_FFMPEG_FLAGS="${TUCAVR_FFMPEG_FLAGS:-} \
  --disable-doc \
  --disable-programs \
  --disable-avdevice \
  --disable-avfilter \
  --disable-encoders \
  --disable-muxers \
  --disable-devices \
  --disable-filters \
  --disable-outdevs \
  --disable-indevs"

cd "$MAKER_DIR"
# ffmpeg-android-maker/scripts/export-build-variables.sh clears EXTRA_BUILD_CONFIGURATION_FLAGS
# on every ABI iteration, so inject TUCAVR_FFMPEG_FLAGS there instead.
if grep -q '^export EXTRA_BUILD_CONFIGURATION_FLAGS=$' scripts/export-build-variables.sh; then
    sed -i 's|^export EXTRA_BUILD_CONFIGURATION_FLAGS=$|export EXTRA_BUILD_CONFIGURATION_FLAGS="${TUCAVR_FFMPEG_FLAGS:-}"|' scripts/export-build-variables.sh
fi
grep -q 'TUCAVR_FFMPEG_FLAGS' scripts/export-build-variables.sh

./ffmpeg-android-maker.sh --target-abis=arm64-v8a --android-api-level=26
