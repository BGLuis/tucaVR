#!/bin/bash
# ==============================================================================
# capture-screen.sh — Instant capture of tucaVR's Vulkan framebuffer
#
# Sends a signal over ADB to the app, dumps the OpenXR swapchain directly
# (left and right eye), transfers it to the PC as PNG and opens it on screen.
# ==============================================================================

set -e

CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT_DIR/captures"
mkdir -p "$OUT_DIR"

echo -e "${CYAN}==================================================================${NC}"
echo -e "${CYAN}📸 tucaVR — Instant Frame Capture (Vulkan/GLES)${NC}"
echo -e "${CYAN}==================================================================${NC}"

# 1. Check adb
if ! command -v adb >/dev/null 2>&1; then
    echo -e "${RED}❌ ERROR: 'adb' not found in PATH.${NC}"
    exit 1
fi

DEVICE_STATUS=$(adb get-state 2>/dev/null || echo "offline")
if [ "$DEVICE_STATUS" != "device" ]; then
    echo -e "${RED}❌ ERROR: Device not detected. Connect the Quest 3 over USB with debugging enabled.${NC}"
    exit 1
fi

# 2. Wake the device if it is asleep
WAKEFULNESS=$(adb shell dumpsys power 2>/dev/null | grep -i "mWakefulness=" | head -n1 || true)
if [[ "$WAKEFULNESS" =~ "Asleep" ]]; then
    echo -e "${YELLOW}⚡ Waking the sleeping headset...${NC}"
    adb shell input keyevent 26 >/dev/null 2>&1 || true
    sleep 1
fi

# 3. Clean old files on the device
DEVICE_PPM="/sdcard/vr-frame-capture.ppm"
adb shell rm -f "$DEVICE_PPM" "${DEVICE_PPM}.left.ppm" "${DEVICE_PPM}.right.ppm" 2>/dev/null || true

# 4. Send the broadcast that triggers the native capture
echo -e "Sending the capture command to tucaVR..."
adb shell am broadcast -a com.tucavr.debug.CAPTURE_FRAME --es capture_path "$DEVICE_PPM" >/dev/null 2>&1 || true

# 5. Wait up to 3 seconds for the generated file
FOUND=0
for i in {1..15}; do
    if adb shell test -f "${DEVICE_PPM}.left.ppm" 2>/dev/null; then
        FOUND=1
        break
    fi
    sleep 0.2
done

if [ "$FOUND" -eq 0 ]; then
    echo -e "${YELLOW}⚠️ The file '${DEVICE_PPM}.left.ppm' was not generated right away through the broadcast.${NC}"
    echo -e "Trying through a direct intent..."
    adb shell am start -n com.tucavr/.VRActivity -e capture_path "$DEVICE_PPM" >/dev/null 2>&1 || true
    for i in {1..15}; do
        if adb shell test -f "${DEVICE_PPM}.left.ppm" 2>/dev/null; then
            FOUND=1
            break
        fi
        sleep 0.2
    done
fi

if [ "$FOUND" -eq 0 ]; then
    echo -e "${RED}❌ ERROR: Could not capture the frame.${NC}"
    echo -e "Make sure the tucaVR app is actively running on the Meta Quest."
    exit 1
fi

# 6. Download the PPM files and convert them to PNG
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOCAL_LEFT_PPM="$OUT_DIR/${TIMESTAMP}_left.ppm"
LOCAL_RIGHT_PPM="$OUT_DIR/${TIMESTAMP}_right.ppm"
LOCAL_LEFT_PNG="$OUT_DIR/${TIMESTAMP}_left.png"
LOCAL_RIGHT_PNG="$OUT_DIR/${TIMESTAMP}_right.png"

adb pull "${DEVICE_PPM}.left.ppm" "$LOCAL_LEFT_PPM" >/dev/null 2>&1
adb shell rm -f "${DEVICE_PPM}.left.ppm" 2>/dev/null || true

if adb shell test -f "${DEVICE_PPM}.right.ppm" 2>/dev/null; then
    adb pull "${DEVICE_PPM}.right.ppm" "$LOCAL_RIGHT_PPM" >/dev/null 2>&1
    adb shell rm -f "${DEVICE_PPM}.right.ppm" 2>/dev/null || true
fi

# PPM -> PNG conversion
if command -v ffmpeg >/dev/null 2>&1; then
    ffmpeg -y -hide_banner -loglevel error -i "$LOCAL_LEFT_PPM" "$LOCAL_LEFT_PNG"
    rm -f "$LOCAL_LEFT_PPM"
    if [ -f "$LOCAL_RIGHT_PPM" ]; then
        ffmpeg -y -hide_banner -loglevel error -i "$LOCAL_RIGHT_PPM" "$LOCAL_RIGHT_PNG"
        rm -f "$LOCAL_RIGHT_PPM"
    fi
else
    # Fall back to Python with Pillow if ffmpeg is not installed
    python3 -c "
from PIL import Image
Image.open('$LOCAL_LEFT_PPM').save('$LOCAL_LEFT_PNG')
" 2>/dev/null || true
    rm -f "$LOCAL_LEFT_PPM"
fi

if [ -f "$LOCAL_LEFT_PNG" ]; then
    echo -e "${GREEN}✅ Capture completed successfully!${NC}"
    echo -e "Image saved at: ${CYAN}$LOCAL_LEFT_PNG${NC}"
    if [ -f "$LOCAL_RIGHT_PNG" ]; then
        echo -e "Right eye at:     ${CYAN}$LOCAL_RIGHT_PNG${NC}"
    fi

    # 7. Open the image automatically in the system viewer (if there is a graphical interface)
    if [ -n "$DISPLAY" ] || [ -n "$WAYLAND_DISPLAY" ]; then
        if command -v xdg-open >/dev/null 2>&1; then
            xdg-open "$LOCAL_LEFT_PNG" >/dev/null 2>&1 &
        fi
    fi
else
    echo -e "${RED}❌ Failed to convert the captured image to PNG.${NC}"
    exit 1
fi
echo -e "=================================================================="
