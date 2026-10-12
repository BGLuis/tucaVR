#!/usr/bin/env bash
# =============================================================================
# push-to-quest.sh — Pushes test vectors to the Meta Quest 3 over ADB
# =============================================================================
#
# Usage:
#   ./scripts/push-to-quest.sh                      # pushes everything
#   ./scripts/push-to-quest.sh --wifi 192.168.1.X   # connects over WiFi and pushes
#   ./scripts/push-to-quest.sh --only 2d hdr 360    # pushes only the given categories
#   ./scripts/push-to-quest.sh --only youtube-downloads  # only the YouTube downloads
#   ./scripts/push-to-quest.sh --list               # lists what is on the Quest
#   ./scripts/push-to-quest.sh --clean              # removes the folder from the Quest
#
# Prerequisites:
#   - Quest 3 in Developer Mode (Settings → Developer → USB Debugging: ON)
#   - Connected over USB *or* WiFi (--wifi <IP>)
#   - adb installed: sudo apt install adb
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VECTORS_DIR="$REPO_ROOT/testdata/vectors"
DOWNLOAD_DIR="$REPO_ROOT/testdata/download"  # real YouTube videos

# Destination on the Quest — shows up in the File Manager and media apps
QUEST_BASE="/sdcard/Movies/VRTestVectors"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

info()    { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()      { echo -e "${GREEN}[ OK ]${NC} $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()     { echo -e "${RED}[ERR ]${NC} $*"; }
section() {
    echo -e "\n${BOLD}${CYAN}══════════════════════════════════════════════${NC}"
    echo -e "${BOLD}${CYAN}  $*${NC}"
    echo -e "${BOLD}${CYAN}══════════════════════════════════════════════${NC}"
}

PUSH_COUNT=0
SKIP_COUNT=0
FAIL_COUNT=0
TOTAL_BYTES=0

# ---------------------------------------------------------------------------
# Parse args
# ---------------------------------------------------------------------------
WIFI_IP=""
ONLY_CATS=()
DO_LIST=false
DO_CLEAN=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --wifi)    WIFI_IP="$2"; shift 2 ;;
        --only)    shift; while [[ $# -gt 0 && "$1" != --* ]]; do ONLY_CATS+=("$1"); shift; done ;;
        --list)    DO_LIST=true; shift ;;
        --clean)   DO_CLEAN=true; shift ;;
        -h|--help)
            echo "Usage: $0 [--wifi IP] [--only cat1 cat2...] [--list] [--clean]"
            echo "Categories: 2d 360 vr180 3d-sbs 3d-ou hdr 4k audio audio-spatial subtitles photos-360 streaming reference"
            exit 0 ;;
        *) warn "Unknown argument: $1"; shift ;;
    esac
done

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
echo -e "${BOLD}"
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║        tucaVR — Push Test Vectors to Quest 3                 ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

# ---------------------------------------------------------------------------
# Check ADB
# ---------------------------------------------------------------------------
section "Checking ADB and the Quest"

if ! command -v adb &>/dev/null; then
    err "adb not found. Install it with: sudo apt install adb"
    exit 1
fi
ok "adb found: $(adb version 2>/dev/null | head -1)"

# Connect over WiFi if requested
if [[ -n "$WIFI_IP" ]]; then
    info "Connecting over WiFi: $WIFI_IP:5555"
    # First enable ADB TCP on the connected USB device (if any)
    adb tcpip 5555 2>/dev/null || true
    sleep 2
    if adb connect "$WIFI_IP:5555" 2>&1 | grep -q "connected"; then
        ok "Connected over WiFi: $WIFI_IP"
    else
        err "Failed to connect to $WIFI_IP:5555"
        err "Make sure that:"
        err "  1. The Quest and the PC are on the same WiFi network"
        err "  2. USB Debugging is enabled on the Quest"
        err "  3. If you have never used WiFi ADB: connect over USB first, run the script,"
        err "     then unplug the USB and reconnect with --wifi <IP>"
        exit 1
    fi
fi

# Check whether a device is connected
DEVICES=$(adb devices 2>/dev/null | grep -v "^List" | grep -v "^$" | grep "device$")
if [[ -z "$DEVICES" ]]; then
    err "No ADB device connected."
    echo ""
    echo -e "${YELLOW}To connect the Quest 3:${NC}"
    echo "  1. Settings → Developer → USB Debugging: ON"
    echo "  2. Plug in the USB-C cable"
    echo "  3. Accept the debug permission that shows up on the headset"
    echo "  4. Run this script again"
    echo ""
    echo -e "${YELLOW}To connect over WiFi (no cable):${NC}"
    echo "  1. Connect over USB once and run: $0 --wifi <Quest-IP>"
    echo "  2. Quest IP: Settings → Wi-Fi → tap the connected network"
    exit 1
fi

DEVICE_COUNT=$(echo "$DEVICES" | wc -l)
info "$DEVICE_COUNT device(s) found:"
echo "$DEVICES" | while IFS= read -r line; do
    SERIAL=$(echo "$line" | awk '{print $1}')
    MODEL=$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "?")
    MANUFACTURER=$(adb -s "$SERIAL" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r' || echo "?")
    ok "  $SERIAL → $MANUFACTURER $MODEL"
done

# Use the first device (or the only one)
SERIAL=$(echo "$DEVICES" | head -1 | awk '{print $1}')
ADB="adb -s $SERIAL"

# Check the available space
AVAIL_KB=$($ADB shell df /sdcard 2>/dev/null | awk 'NR==2 {print $4}' | tr -d '\r' || echo "0")
AVAIL_MB=$((AVAIL_KB / 1024))
AVAIL_GB=$((AVAIL_MB / 1024))
info "Space available on the Quest: ~${AVAIL_GB}GB (${AVAIL_MB}MB)"

# Compute the total size to push
LOCAL_SIZE=$(du -sm "$VECTORS_DIR" 2>/dev/null | cut -f1 || echo "?")
info "Total size of the local vectors: ~${LOCAL_SIZE}MB"

if [[ "$AVAIL_MB" -lt 500 ]]; then
    warn "Little space available on the Quest (<500MB). Consider using --only to push specific categories."
fi

# ---------------------------------------------------------------------------
# --list: show what is on the Quest
# ---------------------------------------------------------------------------
if $DO_LIST; then
    section "Files in $QUEST_BASE"
    $ADB shell ls -lhR "$QUEST_BASE" 2>/dev/null || warn "The folder $QUEST_BASE does not exist yet on the Quest"
    exit 0
fi

# ---------------------------------------------------------------------------
# --clean: remove the folder from the Quest
# ---------------------------------------------------------------------------
if $DO_CLEAN; then
    section "Removing $QUEST_BASE from the Quest"
    warn "WARNING: This will delete ALL the test vectors on the Quest!"
    read -rp "Confirm removal? [y/N] " confirm
    if [[ "${confirm,,}" == "y" ]]; then
        $ADB shell rm -rf "$QUEST_BASE" 2>/dev/null && ok "Removed: $QUEST_BASE" || err "Failed to remove"
    else
        info "Cancelled."
    fi
    exit 0
fi

# ---------------------------------------------------------------------------
# Create the directory structure on the Quest
# ---------------------------------------------------------------------------
section "Creating the structure on the Quest"

CATEGORIES=(
    "2d"
    "360"
    "vr180"
    "3d-sbs"
    "3d-ou"
    "hdr"
    "4k"
    "audio"
    "audio-spatial"
    "subtitles"
    "photos-360"
    "streaming/hls"
    "streaming/dash"
    "reference"
)

# Filter the categories if --only was specified
if [[ ${#ONLY_CATS[@]} -gt 0 ]]; then
    FILTERED=()
    for cat in "${CATEGORIES[@]}"; do
        for only in "${ONLY_CATS[@]}"; do
            if [[ "$cat" == "$only" || "$cat" == "$only"/* || "$cat" == *"/$only" ]]; then
                FILTERED+=("$cat")
                break
            fi
        done
    done
    CATEGORIES=("${FILTERED[@]}")
    info "Pushing only: ${CATEGORIES[*]}"
fi

# Create the directories on the Quest
for cat in "${CATEGORIES[@]}"; do
    $ADB shell mkdir -p "$QUEST_BASE/$cat" 2>/dev/null
done
ok "Structure created in $QUEST_BASE"

# ---------------------------------------------------------------------------
# Push function with an integrity check
# ---------------------------------------------------------------------------
push_file() {
    local src="$1"
    local dest_dir="$2"
    local filename
    filename=$(basename "$src")
    local dest="$dest_dir/$filename"
    local src_size
    src_size=$(stat -c%s "$src" 2>/dev/null || echo 0)

    # Check whether it already exists with the right size
    local quest_size
    quest_size=$($ADB shell stat -c%s "$dest" 2>/dev/null | tr -d '\r\n' || echo "0")

    if [[ "$quest_size" == "$src_size" && "$src_size" -gt 0 ]]; then
        local sz_human; sz_human=$(du -sh "$src" | cut -f1)
        warn "  ALREADY EXISTS (${sz_human}): $filename"
        ((SKIP_COUNT++)) || true
        return 0
    fi

    local sz_human; sz_human=$(du -sh "$src" | cut -f1)
    info "  Pushing (${sz_human}): $filename"

    if $ADB push "$src" "$dest" 2>&1 | tail -1 | grep -qE "pushed|file pushed"; then
        ok "    → OK: $filename"
        ((PUSH_COUNT++)) || true
        TOTAL_BYTES=$((TOTAL_BYTES + src_size))
    else
        # try anyway and verify
        if $ADB push "$src" "$dest" 2>/dev/null; then
            ok "    → OK: $filename"
            ((PUSH_COUNT++)) || true
            TOTAL_BYTES=$((TOTAL_BYTES + src_size))
        else
            err "    → FAILED: $filename"
            ((FAIL_COUNT++)) || true
        fi
    fi
}

push_category() {
    local cat="$1"
    local src_dir="$VECTORS_DIR/$cat"
    local dest_dir="$QUEST_BASE/$cat"

    [[ -d "$src_dir" ]] || return 0

    local file_count
    file_count=$(find "$src_dir" -maxdepth 1 -type f | wc -l)
    [[ "$file_count" -eq 0 ]] && return 0

    section "Pushing: $cat ($file_count file(s))"

    while IFS= read -r -d '' file; do
        local ext="${file##*.}"
        # Skip logs
        [[ "$ext" == "log" ]] && continue
        push_file "$file" "$dest_dir"
    done < <(find "$src_dir" -maxdepth 1 -type f -print0 | sort -z)
}

# ---------------------------------------------------------------------------
# Push per category
# ---------------------------------------------------------------------------
for cat in "${CATEGORIES[@]}"; do
    push_category "$cat"
done

# ---------------------------------------------------------------------------
# Check on the Quest what was pushed
# ---------------------------------------------------------------------------
section "Checking on the Quest"

QUEST_COUNT=$($ADB shell find "$QUEST_BASE" -type f 2>/dev/null | wc -l | tr -d '\r' || echo "?")
QUEST_SIZE=$($ADB shell du -sh "$QUEST_BASE" 2>/dev/null | cut -f1 | tr -d '\r' || echo "?")

ok "Files on the Quest: $QUEST_COUNT"
ok "Total size on the Quest: $QUEST_SIZE"

# Scan the media so it shows up in the Quest's apps
info "Scanning media on the Quest (so it shows up in the File Manager)..."
$ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file://$QUEST_BASE" 2>/dev/null || true
# A more effective recursive scan
$ADB shell find "$QUEST_BASE" -name "*.mp4" -o -name "*.webm" -o -name "*.mkv" \
    2>/dev/null | while IFS= read -r f; do
    $ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
        -d "file://$f" 2>/dev/null || true
done
ok "Media scan finished"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
TOTAL_MB=$((TOTAL_BYTES / 1024 / 1024))

echo ""
echo -e "${BOLD}${GREEN}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}${GREEN}║                    PUSH FINISHED                            ║${NC}"
echo -e "${BOLD}${GREEN}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${GREEN}✔ Pushed:${NC}      $PUSH_COUNT files (${TOTAL_MB}MB)"
echo -e "  ${YELLOW}⊘ Skipped:${NC}     $SKIP_COUNT (already existed)"
echo -e "  ${RED}✘ Failed:${NC}      $FAIL_COUNT"
echo ""
echo -e "  ${BLUE}📁 Quest:${NC}       $QUEST_BASE"
echo -e "  ${BLUE}📊 Files:${NC}      $QUEST_COUNT total | ${QUEST_SIZE} on the headset"
echo ""
echo -e "${CYAN}On the Quest 3:${NC}"
echo "  1. Files → VRTestVectors/"
echo "     → to browse and open files directly"
echo ""
echo "  2. In the player under development:"
echo "     → Browse → /sdcard/Movies/VRTestVectors/"
echo ""
if [[ -f "$VECTORS_DIR/vr180/vr180_placeholder_sbs.mp4" ]] && \
   ! [[ -f "$VECTORS_DIR/vr180/vr180_lightspeed_real.mp4" ]]; then
    echo -e "  ${YELLOW}⚠ Real VR180 missing.${NC} See: testdata/vectors/vr180/HOW_TO_DOWNLOAD.md"
    echo ""
fi
echo -e "${CYAN}To connect over WiFi (no cable):${NC}"
echo "  1. Quest IP: Settings → Wi-Fi → network name → IP address"
echo "  2. Run: $0 --wifi <QUEST-IP>"
echo ""
# (this line is not executed — the block above is the complete script)
