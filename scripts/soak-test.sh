#!/bin/bash
# Automated soak test over adb — covers 2 items of Phase 0.1's Definition of
# Done (docs/phases/PHASE-0.1-MVP.md) that today can only be validated on a
# physical Quest 3 connected over USB:
#   - "No crash in a 30-minute session"
#   - "Memory < 2.5GB during 4K video playback"
#
# Usage:
#   ./scripts/soak-test.sh --video-path /sdcard/Movies/test-4k.mp4
#   ./scripts/soak-test.sh --duration-min 5 --interval-sec 5   # quick smoke test
#
# The headset needs USB debugging authorized and to be visible in
# `adb devices`. If --video-path is passed, the app receives the extra
# EXTRA_AUTO_PLAY_PATH (see VRActivity.kt) and starts playing on its own ~3s
# after opening — without it the test only measures the idle app on the
# void/home screen, which does not match the DoD's "4K video playback" scenario.
set -euo pipefail

PACKAGE="com.tucavr"
ACTIVITY=".VRActivity"
DURATION_MIN=30
INTERVAL_SEC=15
VIDEO_PATH=""
APK=""
SKIP_INSTALL=0
MEMORY_LIMIT_KB=$((2621440)) # 2.5GB in KB, the DoD limit
SERIAL=""

usage() {
    cat <<EOF
Usage: $0 [options]
  --duration-min N     Test duration in minutes (default: 30)
  --interval-sec N     Interval between samples in seconds (default: 15)
  --video-path PATH    Path of the video on the device for auto-play (e.g. /sdcard/Movies/x.mp4)
  --apk PATH           Installs this APK before the test (e.g. app/build/outputs/apk/debug/app-debug.apk)
  --serial SERIAL      adb device serial (equivalent to 'adb -s')
  --package NAME       Application ID (default: com.tucavr)
  -h, --help            Shows this help
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --duration-min) DURATION_MIN="$2"; shift 2 ;;
        --interval-sec) INTERVAL_SEC="$2"; shift 2 ;;
        --video-path) VIDEO_PATH="$2"; shift 2 ;;
        --apk) APK="$2"; shift 2 ;;
        --serial) SERIAL="$2"; shift 2 ;;
        --package) PACKAGE="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1"; usage; exit 1 ;;
    esac
done

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB=(adb -s "$SERIAL")
fi

DEVICE_COUNT=$("${ADB[@]}" devices | grep -c -E "device$" || true)
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo "No adb device found. Connect the Quest 3 over USB, authorize USB debugging on the headset and try again." >&2
    exit 1
fi
if [[ "$DEVICE_COUNT" -gt 1 && -z "$SERIAL" ]]; then
    echo "More than one adb device connected — use --serial <serial> (see 'adb devices')." >&2
    exit 1
fi

if [[ -n "$APK" ]]; then
    echo "Installing $APK..."
    "${ADB[@]}" install -r "$APK"
fi

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUT_DIR="soak-test-results/$TIMESTAMP"
mkdir -p "$OUT_DIR"
MEMORY_CSV="$OUT_DIR/memory.csv"
LOGCAT_FILE="$OUT_DIR/logcat.txt"
SUMMARY_FILE="$OUT_DIR/summary.txt"

echo "timestamp,elapsed_s,pss_kb,pid_alive,thermal_status" > "$MEMORY_CSV"

LOGCAT_PID=""
cleanup() {
    if [[ -n "$LOGCAT_PID" ]] && kill -0 "$LOGCAT_PID" 2>/dev/null; then
        kill "$LOGCAT_PID" 2>/dev/null || true
        wait "$LOGCAT_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

echo "Clearing logcat and starting the background capture..."
"${ADB[@]}" logcat -c
"${ADB[@]}" logcat -v time > "$LOGCAT_FILE" &
LOGCAT_PID=$!

echo "Force-stopping the app (clean state) and starting it..."
"${ADB[@]}" shell am force-stop "$PACKAGE" || true
sleep 1

START_ARGS=(shell am start -n "$PACKAGE/$ACTIVITY")
if [[ -n "$VIDEO_PATH" ]]; then
    START_ARGS+=(-e video_path "$VIDEO_PATH")
    echo "Auto-play enabled: $VIDEO_PATH (starts ~3s after the app opens)"
fi
"${ADB[@]}" "${START_ARGS[@]}"

TOTAL_SEC=$((DURATION_MIN * 60))
ELAPSED=0
MAX_PSS_KB=0
CRASH_DETECTED=0

get_pss_kb() {
    # "TOTAL PSS:" or layout variants of dumpsys meminfo depending on the Android version.
    "${ADB[@]}" shell dumpsys meminfo "$PACKAGE" 2>/dev/null \
        | grep -m1 -E "TOTAL( PSS)?:" \
        | grep -oE "[0-9]+" \
        | head -1 || true
}

get_thermal_status() {
    # "Thermal Status: N" (PowerManager.THERMAL_STATUS_*: 0=NONE 1=LIGHT
    # 2=MODERATE 3=SEVERE 4=CRITICAL 5=EMERGENCY 6=SHUTDOWN). Numeric format
    # confirmed in a real dumpsys thermalservice on the Quest 3 (Android
    # 14) — not "mStatus=WORD" as in older AOSP versions.
    local code
    code=$("${ADB[@]}" shell dumpsys thermalservice 2>/dev/null | grep -m1 -oE "Thermal Status: [0-9]+" | grep -oE "[0-9]+")
    case "$code" in
        0) echo "NONE" ;;
        1) echo "LIGHT" ;;
        2) echo "MODERATE" ;;
        3) echo "SEVERE" ;;
        4) echo "CRITICAL" ;;
        5) echo "EMERGENCY" ;;
        6) echo "SHUTDOWN" ;;
        *) echo "unknown" ;;
    esac
}

echo "Monitoring for $DURATION_MIN min (a sample every ${INTERVAL_SEC}s). Ctrl-C stops with a partial report."
echo "Results in: $OUT_DIR"

while [[ "$ELAPSED" -lt "$TOTAL_SEC" ]]; do
    sleep "$INTERVAL_SEC"
    ELAPSED=$((ELAPSED + INTERVAL_SEC))

    PID=$("${ADB[@]}" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)
    if [[ -z "$PID" ]]; then
        echo "[${ELAPSED}s] Process $PACKAGE not found — possible crash." >&2
        echo "$(date -Iseconds),$ELAPSED,,dead," >> "$MEMORY_CSV"
        CRASH_DETECTED=1
        break
    fi

    PSS_KB=$(get_pss_kb)
    THERMAL=$(get_thermal_status)
    PSS_KB=${PSS_KB:-0}
    if [[ "$PSS_KB" -gt "$MAX_PSS_KB" ]]; then
        MAX_PSS_KB=$PSS_KB
    fi

    echo "$(date -Iseconds),$ELAPSED,$PSS_KB,alive,$THERMAL" >> "$MEMORY_CSV"
    printf "[%5ds] PSS=%sMB thermal=%s\n" "$ELAPSED" "$((PSS_KB / 1024))" "$THERMAL"
done

cleanup
LOGCAT_PID=""

CRASH_IN_LOG=0
if grep -qE "FATAL EXCEPTION|ANR in $PACKAGE|Process $PACKAGE .* has died" "$LOGCAT_FILE"; then
    CRASH_IN_LOG=1
fi

MEMORY_OK="PASS"
if [[ "$MAX_PSS_KB" -gt "$MEMORY_LIMIT_KB" || "$MAX_PSS_KB" -eq 0 ]]; then
    MEMORY_OK="FAIL"
fi

CRASH_OK="PASS"
if [[ "$CRASH_DETECTED" -eq 1 || "$CRASH_IN_LOG" -eq 1 ]]; then
    CRASH_OK="FAIL"
fi

{
    echo "Soak test — $PACKAGE"
    echo "Date: $(date -Iseconds)"
    echo "Requested duration: ${DURATION_MIN}min | Actual duration: $((ELAPSED / 60))min ${ELAPSED}s total"
    echo "Video auto-play: ${VIDEO_PATH:-"(none — idle app)"}"
    echo
    echo "== Criterion: no crash in session (DoD) =="
    echo "  Process died during monitoring: $([[ $CRASH_DETECTED -eq 1 ]] && echo yes || echo no)"
    echo "  FATAL EXCEPTION / ANR / 'has died' in logcat: $([[ $CRASH_IN_LOG -eq 1 ]] && echo yes || echo no)"
    echo "  Result: $CRASH_OK"
    echo
    echo "== Criterion: memory < 2.5GB (DoD) =="
    echo "  Maximum PSS observed: $((MAX_PSS_KB / 1024))MB ($MAX_PSS_KB KB)"
    echo "  Limit: $((MEMORY_LIMIT_KB / 1024))MB"
    echo "  Result: $MEMORY_OK"
    echo
    echo "Files: $MEMORY_CSV | $LOGCAT_FILE"
} | tee "$SUMMARY_FILE"

if [[ "$CRASH_OK" == "FAIL" || "$MEMORY_OK" == "FAIL" ]]; then
    exit 1
fi
exit 0
