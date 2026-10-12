#!/bin/bash
# Automated debug and telemetry collector — level N3 of docs/DEBUGGING.md (section 7)
#
# Usage:
#   ./scripts/collect-debug.sh
#   ./scripts/collect-debug.sh --serial <serial>
#   ./scripts/collect-debug.sh --out debug-reports/my-test
#   ./scripts/collect-debug.sh --bugreport   # includes the full Android bugreport
#
set -euo pipefail

PACKAGE="com.tucavr"
SERIAL=""
OUT_DIR=""
INCLUDE_BUGREPORT=0

usage() {
    cat <<EOF
Usage: $0 [options]
  --serial SERIAL     adb device serial (equivalent to 'adb -s')
  --package NAME      Application ID (default: com.tucavr)
  --out DIR           Output directory (default: debug-reports/<timestamp>)
  --bugreport         Generates and includes the full Android bugreport (slow and heavy)
  -h, --help          Shows this help
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --package) PACKAGE="$2"; shift 2 ;;
        --out) OUT_DIR="$2"; shift 2 ;;
        --bugreport) INCLUDE_BUGREPORT=1; shift ;;
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
    echo "❌ No adb device found. Connect the Quest 3 over USB, authorize USB debugging on the headset and try again." >&2
    exit 1
fi
if [[ "$DEVICE_COUNT" -gt 1 && -z "$SERIAL" ]]; then
    echo "⚠️  More than one adb device connected — use --serial <serial> (see 'adb devices')." >&2
    exit 1
fi

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
if [[ -z "$OUT_DIR" ]]; then
    OUT_DIR="debug-reports/$TIMESTAMP"
fi
mkdir -p "$OUT_DIR"

echo "=== Collecting diagnostic data into $OUT_DIR ==="

# 1. Manifest
MANIFEST_FILE="$OUT_DIR/manifest.txt"
DEVICE_MODEL=$("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "Unknown")
ANDROID_BUILD=$("${ADB[@]}" shell getprop ro.build.display.id 2>/dev/null | tr -d '\r' || echo "Unknown")
ANDROID_VER=$("${ADB[@]}" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || echo "Unknown")
APP_VERSION=$("${ADB[@]}" shell dumpsys package "$PACKAGE" 2>/dev/null | grep -m1 "versionName" | awk -F= '{print $2}' | tr -d '\r' || echo "Not installed")
GIT_COMMIT=$(git rev-parse HEAD 2>/dev/null || echo "Unknown")

echo "device_serial: ${SERIAL:-default}" > "$MANIFEST_FILE"
echo "device_model: $DEVICE_MODEL" >> "$MANIFEST_FILE"
echo "android_version: $ANDROID_VER" >> "$MANIFEST_FILE"
echo "android_build: $ANDROID_BUILD" >> "$MANIFEST_FILE"
echo "app_package: $PACKAGE" >> "$MANIFEST_FILE"
echo "app_version: $APP_VERSION" >> "$MANIFEST_FILE"
echo "git_commit: $GIT_COMMIT" >> "$MANIFEST_FILE"
echo "collection_time: $(date -Iseconds)" >> "$MANIFEST_FILE"

# 2. Logcat
echo "Capturing logcat logs..."
"${ADB[@]}" logcat -d -v time > "$OUT_DIR/logcat.txt" 2>/dev/null || true
grep -E "VRPlayerApp|VRPlayerAppVK|VRPlayerJNI_VK|VRPlayer_Rust|VRPlayer_App|ThermalMonitor" "$OUT_DIR/logcat.txt" > "$OUT_DIR/logcat-filtered.txt" 2>/dev/null || true

# Detects the active graphics backend from logcat
if grep -q "VRPlayerAppVK" "$OUT_DIR/logcat.txt"; then
    GRAPHICS_BACKEND="VULKAN"
elif grep -q "VRPlayerApp" "$OUT_DIR/logcat.txt"; then
    GRAPHICS_BACKEND="GLES"
else
    GRAPHICS_BACKEND="UNKNOWN"
fi
echo "graphics_backend_effective: $GRAPHICS_BACKEND" >> "$MANIFEST_FILE"

# 3. Telemetry, event log (F6) and crash reports saved by the app — a single recursive
# pull already brings the time-series CSVs, the session-*-events.log files (F6) and the
# crash-*.txt files, all in the same remote directory.
echo "Pulling CSV time series, event logs and crash reports..."
REMOTE_DEBUG_DIR="/sdcard/Android/data/$PACKAGE/files/debug"
mkdir -p "$OUT_DIR/telemetry"
if "${ADB[@]}" shell test -d "$REMOTE_DEBUG_DIR" 2>/dev/null; then
    "${ADB[@]}" pull "$REMOTE_DEBUG_DIR" "$OUT_DIR/telemetry/" >/dev/null 2>&1 || true
    echo "  Telemetry files transferred successfully."

    # D-05/F0: schema_version is the 1st column of each CSV — record the value(s) found in
    # the manifest, and how many event-log files (F6) came along.
    SCHEMA_VERSIONS=$(find "$OUT_DIR/telemetry" -iname "session-*.csv" -exec sh -c 'head -1 "$1" | cut -d, -f1' _ {} \; 2>/dev/null | sort -u | paste -sd, -)
    EVENT_LOG_COUNT=$(find "$OUT_DIR/telemetry" -iname "session-*-events.log" 2>/dev/null | wc -l | tr -d ' ')
    echo "csv_schema_versions: ${SCHEMA_VERSIONS:-no_csv_found}" >> "$MANIFEST_FILE"
    echo "event_log_files: $EVENT_LOG_COUNT" >> "$MANIFEST_FILE"
else
    echo "  The remote telemetry folder is empty or missing."
fi

# 4. System diagnostics
echo "Collecting dumpsys meminfo and thermalservice..."
"${ADB[@]}" shell dumpsys meminfo "$PACKAGE" > "$OUT_DIR/meminfo.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys thermalservice > "$OUT_DIR/thermalservice.txt" 2>&1 || true

# 5. DropBox / Crashes
"${ADB[@]}" shell dumpsys dropbox --print data_app_crash data_app_anr > "$OUT_DIR/dropbox_crashes.txt" 2>&1 || true

# 6. Bugreport (opcional)
if [[ "$INCLUDE_BUGREPORT" -eq 1 ]]; then
    echo "Generating the Android bugreport (this can take 2 to 5 minutes)..."
    "${ADB[@]}" bugreport "$OUT_DIR/bugreport.zip" 2>/dev/null || echo "Failed to generate the bugreport" >&2
fi

# 7. Packaging
ARCHIVE_PATH="${OUT_DIR}.tar.gz"
echo "Compressing the artifact into $ARCHIVE_PATH..."
tar -czf "$ARCHIVE_PATH" -C "$(dirname "$OUT_DIR")" "$(basename "$OUT_DIR")"

echo "✅ Collection finished successfully!"
echo "   Folder: $OUT_DIR"
echo "   Compressed file: $ARCHIVE_PATH"
echo "   Detected backend: $GRAPHICS_BACKEND"
