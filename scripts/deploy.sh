#!/usr/bin/env bash
# =============================================================================
# deploy.sh — Fast and resilient APK deployment to Meta Quest 3 over ADB
# =============================================================================
#
# Usage:
#   ./scripts/deploy.sh                      # Standard fast deploy + auto-launch
#   ./scripts/deploy.sh --no-fast            # Disable --fastdeploy
#   ./scripts/deploy.sh --no-launch          # Install without launching VRActivity
#   ./scripts/deploy.sh --keep-awake         # Bypass proximity sensor (prox_close)
#   ./scripts/deploy.sh -s <device_serial>   # Target a specific device
#   ./scripts/deploy.sh --apk <path>         # Custom APK path
#
# Environment variables:
#   DEVICE_SERIAL / ANDROID_SERIAL : Specific device serial
#   FAST=0                         : Disable --fastdeploy
#   LAUNCH=0                       : Disable auto-launch
#   KEEP_AWAKE=1                   : Bypass proximity sensor
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Default configurations
DEFAULT_APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
APK_PATH="${APK_PATH:-$DEFAULT_APK}"
APP_PACKAGE="com.tucavr"
APP_ACTIVITY="$APP_PACKAGE/.VRActivity"

FAST="${FAST:-0}"
LAUNCH="${LAUNCH:-1}"
KEEP_AWAKE="${KEEP_AWAKE:-0}"
TARGET_SERIAL="${DEVICE_SERIAL:-${ANDROID_SERIAL:-}}"

# ANSI Colors
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

info()    { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()      { echo -e "${GREEN}[ OK ]${NC} $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()     { echo -e "${RED}[ERR ]${NC} $*"; }

# ---------------------------------------------------------------------------
# Parse CLI arguments
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --fast)            FAST=1; shift ;;
        --no-fast)         FAST=0; shift ;;
        --launch)          LAUNCH=1; shift ;;
        --no-launch)       LAUNCH=0; shift ;;
        --keep-awake)      KEEP_AWAKE=1; shift ;;
        --no-keep-awake)   KEEP_AWAKE=0; shift ;;
        -s|--device)       TARGET_SERIAL="$2"; shift 2 ;;
        --apk)             APK_PATH="$2"; shift 2 ;;
        -h|--help)
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  --fast / --no-fast          Enable/disable adb --fastdeploy (default: enabled)"
            echo "  --launch / --no-launch      Enable/disable auto-launching VRActivity (default: enabled)"
            echo "  --keep-awake                Bypass proximity sensor (keep displays on)"
            echo "  -s, --device <SERIAL>       Specify ADB device serial"
            echo "  --apk <PATH>                Specify custom APK path"
            echo "  -h, --help                  Show this help message"
            exit 0
            ;;
        *)
            warn "Unknown argument: $1"
            shift
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Validate ADB and APK
# ---------------------------------------------------------------------------
find_adb() {
    # 1. Explicit override via ADB environment variable
    if [[ -n "${ADB:-}" && -x "$ADB" ]]; then
        echo "$ADB"
        return 0
    fi

    # 2. Check local.properties in repo root (standard Android Studio / Gradle configuration)
    if [[ -f "$REPO_ROOT/local.properties" ]]; then
        local sdk_dir
        sdk_dir="$(grep -E '^[[:space:]]*sdk\.dir[[:space:]]*=' "$REPO_ROOT/local.properties" 2>/dev/null | head -1 | cut -d'=' -f2- | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/\\:/:/g' -e 's/\\ / /g')"
        if [[ -n "$sdk_dir" && -x "$sdk_dir/platform-tools/adb" ]]; then
            echo "$sdk_dir/platform-tools/adb"
            return 0
        fi
    fi

    # 3. Standard environment variables: ANDROID_HOME or ANDROID_SDK_ROOT
    for env_var in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        if [[ -n "$env_var" && -x "$env_var/platform-tools/adb" ]]; then
            echo "$env_var/platform-tools/adb"
            return 0
        fi
    done

    # 4. Standard platform default paths (Linux and macOS)
    local candidate_paths=(
        "$HOME/Android/Sdk/platform-tools/adb"                 # Linux Android Studio default
        "$HOME/Library/Android/sdk/platform-tools/adb"         # macOS Android Studio default
        "/opt/android-sdk/platform-tools/adb"                  # Common Linux manual install
        "/usr/lib/android-sdk/platform-tools/adb"              # Debian/Ubuntu package path
    )
    for path in "${candidate_paths[@]}"; do
        if [[ -x "$path" ]]; then
            echo "$path"
            return 0
        fi
    done

    # 5. System PATH fallback
    if command -v adb &>/dev/null; then
        command -v adb
        return 0
    fi

    return 1
}

ADB_BIN="$(find_adb || true)"

if [[ -z "$ADB_BIN" || ! -x "$ADB_BIN" ]]; then
    err "adb command not found. Please install Android Platform Tools or set ANDROID_HOME."
    exit 1
fi

if [[ ! -f "$APK_PATH" ]]; then
    err "APK not found at: $APK_PATH"
    echo "    Run './scripts/build.sh' or 'make build' first."
    exit 1
fi

# ---------------------------------------------------------------------------
# Detect Target Device
# ---------------------------------------------------------------------------
DEVICES_OUTPUT=$("$ADB_BIN" devices 2>/dev/null | grep -v "^List" | grep "device$" || true)

if [[ -z "$DEVICES_OUTPUT" ]]; then
    err "No authorized ADB devices detected."
    echo ""
    echo -e "${YELLOW}To connect your Meta Quest 3:${NC}"
    echo "  1. Ensure Developer Mode is enabled in Meta Quest mobile app."
    echo "  2. Connect USB cable (or connect via Wi-Fi: adb connect <IP>:5555)."
    echo "  3. Put on the headset and click 'Always allow from this computer'."
    echo "  4. Check 'adb devices' output and run again."
    exit 1
fi

if [[ -z "$TARGET_SERIAL" ]]; then
    DEVICE_COUNT=$(echo "$DEVICES_OUTPUT" | wc -l)
    TARGET_SERIAL=$(echo "$DEVICES_OUTPUT" | head -1 | awk '{print $1}')
    if [[ "$DEVICE_COUNT" -gt 1 ]]; then
        warn "Multiple devices detected ($DEVICE_COUNT). Using first device: $TARGET_SERIAL"
        warn "To target a specific device, use: $0 -s <serial>"
    fi
fi

ADB_CMD="$ADB_BIN -s $TARGET_SERIAL"

MODEL=$($ADB_CMD shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "Android Device")
info "Target device: ${BOLD}$TARGET_SERIAL${NC} ($MODEL)"

# ---------------------------------------------------------------------------
# Install APK
# ---------------------------------------------------------------------------
APK_SIZE_MB=$(du -m "$APK_PATH" | cut -f1)
info "Deploying APK (${APK_SIZE_MB} MB) to $TARGET_SERIAL..."

# Filter noise from stderr (e.g. absl::InitializeLog warning on modern Linux ADB)
run_adb_install() {
    local flags=("$@")
    $ADB_CMD install "${flags[@]}" "$APK_PATH" 2> >(grep -v "absl::InitializeLog" >&2)
}

INSTALL_SUCCESS=false

if [[ "$FAST" == "1" ]]; then
    info "Attempting fast deploy (--fastdeploy)..."
    if run_adb_install -r -t -g --fastdeploy; then
        INSTALL_SUCCESS=true
        ok "Installed successfully via FastDeploy!"
    else
        warn "FastDeploy failed or is unsupported on this device. Falling back to standard install..."
    fi
fi

if ! $INSTALL_SUCCESS; then
    info "Running standard adb install (-r -t -g)..."
    if run_adb_install -r -t -g; then
        INSTALL_SUCCESS=true
        ok "Installed successfully via standard install!"
    else
        err "Failed to install APK on device $TARGET_SERIAL."
        exit 1
    fi
fi

# ---------------------------------------------------------------------------
# VR Headset Post-Deploy Automation
# ---------------------------------------------------------------------------
info "Waking headset display..."
$ADB_CMD shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true

if [[ "$KEEP_AWAKE" == "1" ]]; then
    info "Bypassing Quest proximity sensor (keeping displays awake)..."
    $ADB_CMD shell am broadcast -a com.oculus.vrpowermanager.prox_close >/dev/null 2>&1 || true
fi

if [[ "$LAUNCH" == "1" ]]; then
    info "Launching $APP_ACTIVITY (clean restart)..."
    $ADB_CMD shell am start -S -n "$APP_ACTIVITY" >/dev/null 2>&1 || true
    ok "Application started!"
fi

ok "Deploy completed successfully!"
