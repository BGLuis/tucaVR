#!/bin/bash
# ==============================================================================
# quest-mirror.sh — Real-time mirroring of the Meta Quest 3 on the PC through scrcpy
#
# Lets you watch live, on the computer screen, what tucaVR is rendering in the
# headset, with ultra-low latency (~20ms) and 60 to 90 FPS.
# ==============================================================================

set -e

# Terminal colors
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${CYAN}==================================================================${NC}"
echo -e "${CYAN}🥽 tucaVR — Meta Quest 3 Mirroring on the Computer${NC}"
echo -e "${CYAN}==================================================================${NC}"

# 1. Check dependency: scrcpy
if ! command -v scrcpy >/dev/null 2>&1; then
    echo -e "${RED}❌ ERROR: The 'scrcpy' utility is not installed on this computer.${NC}"
    echo -e ""
    echo -e "${YELLOW}scrcpy is the recommended standard tool for mirroring the Quest 3 at 90 FPS.${NC}"
    echo -e "To install it on your Linux system:"
    echo -e "  • ${GREEN}Arch Linux / Manjaro:${NC}   sudo pacman -S scrcpy"
    echo -e "  • ${GREEN}Ubuntu / Debian:${NC}        sudo apt install scrcpy"
    echo -e "  • ${GREEN}Fedora:${NC}                 sudo dnf install scrcpy"
    echo -e ""
    echo -e "If you prefer to cast without installing anything, use the official Meta Casting in the browser:"
    echo -e "  👉 ${CYAN}https://www.oculus.com/casting${NC}"
    echo -e "=================================================================="
    exit 1
fi

# 2. Check dependency: adb
if ! command -v adb >/dev/null 2>&1; then
    echo -e "${RED}❌ ERROR: 'adb' not found in PATH.${NC}"
    exit 1
fi

# 3. Check the connected device
DEVICE_STATUS=$(adb get-state 2>/dev/null || echo "offline")
if [ "$DEVICE_STATUS" != "device" ]; then
    echo -e "${YELLOW}⚠️ No device detected in 'device' mode.${NC}"
    echo -e "Checking the ADB device list:"
    adb devices
    echo -e ""
    echo -e "${YELLOW}Please make sure that:${NC}"
    echo -e "  1. The Meta Quest 3 is connected to the PC through a USB cable."
    echo -e "  2. USB debugging is authorized on the headset (dialog box in the glasses)."
    exit 1
fi

DEVICE_MODEL=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo -e "Device connected: ${GREEN}${DEVICE_MODEL:-Meta Quest}${NC}"

# 4. Default crop parameters for the Meta Quest 3
# The Quest's native display renders both eyes side by side.
# The crop below extracts the right eye in a clean, centered proportion.
CROP_RIGHT_EYE="1600:1600:400:300"
CROP_LEFT_EYE="1600:1600:0:300"
SELECTED_CROP="$CROP_RIGHT_EYE"
MAX_FPS=90
BITRATE="25M"
WINDOW_TITLE="tucaVR — Meta Quest 3 Display Mirror"

# Process optional arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --left-eye)
            SELECTED_CROP="$CROP_LEFT_EYE"
            shift
            ;;
        --right-eye)
            SELECTED_CROP="$CROP_RIGHT_EYE"
            shift
            ;;
        --full|--both-eyes)
            SELECTED_CROP=""
            shift
            ;;
        --fps)
            MAX_FPS="$2"
            shift 2
            ;;
        --bitrate)
            BITRATE="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --right-eye     (Default) Crops and centers the right eye in high sharpness."
            echo "  --left-eye      Crops and centers the left eye."
            echo "  --full          Shows the full stereo image (both eyes)."
            echo "  --fps <n>       Sets the FPS limit (default: 90)."
            echo "  --bitrate <n>   Sets the streaming bitrate (default: 25M)."
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use '$0 --help' to see the available options."
            exit 1
            ;;
    esac
done

SCRCPY_ARGS=(
    --max-size 1600
    --max-fps "$MAX_FPS"
    -b "$BITRATE"
    --stay-awake
    --window-title "$WINDOW_TITLE"
)

if [ -n "$SELECTED_CROP" ]; then
    SCRCPY_ARGS+=(--crop "$SELECTED_CROP")
fi

echo -e "Starting mirroring through scrcpy (FPS: ${MAX_FPS}, Bitrate: ${BITRATE})..."
echo -e "Tip: To stop mirroring, close the window or press Ctrl+C in the terminal."
echo -e "------------------------------------------------------------------"

exec scrcpy "${SCRCPY_ARGS[@]}"
