#!/bin/bash
# Orchestrates the network integration tests (T6/T7, docs/phases/PHASE-0.1-MVP.md,
# PHASE-0.2-3D-NETWORK.md, and Phase 0.4 Sections 2/3): brings up real SMB2/3,
# HTTP, HTTPS, FTP, SFTP, DASH and WebDAV servers through Docker (docker/network-tests/),
# runs the Rust tests marked #[ignore] in rust/protocols/tests/*_integration.rs against
# them, and tears everything down at the end. It replaces the manual validation that was
# missing in T6.1/T6.3/T7.1 ("never tested against a real server").
#
# Usage:
#   ./scripts/test-network-protocols.sh            # runs everything and tears the containers down
#   ./scripts/test-network-protocols.sh --keep      # leaves the containers up for debugging
#
# Requirements: docker + docker compose (plugin), curl, sha256sum, ffmpeg (it generates the
# DASH fixtures at test time — see the DASH fixtures section below). No headset or
# Quest 3 hardware is needed — this runs entirely on the development host.
set -euo pipefail

for bin in docker curl sha256sum ffmpeg; do
    command -v "$bin" >/dev/null 2>&1 || { echo "Missing requirement: $bin"; exit 1; }
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_DIR="$REPO_ROOT/docker/network-tests"

KEEP=0
if [[ "${1:-}" == "--keep" ]]; then
    KEEP=1
fi

DC=(docker compose -f "$COMPOSE_DIR/docker-compose.yml" --project-directory "$COMPOSE_DIR")

cleanup() {
    if [[ "$KEEP" -eq 0 ]]; then
        echo "Tearing the containers down..."
        "${DC[@]}" down -v >/dev/null 2>&1 || true
    else
        echo "Containers kept up (--keep). Tear them down with: docker compose -f $COMPOSE_DIR/docker-compose.yml down -v"
    fi
}
trap cleanup EXIT

echo "== Generating the test fixture =="
mkdir -p "$COMPOSE_DIR/fixtures"
FIXTURE="$COMPOSE_DIR/fixtures/testfile.bin"
head -c 262144 /dev/urandom > "$FIXTURE"
SHA256=$(sha256sum "$FIXTURE" | cut -d' ' -f1)
echo "testfile.bin: $((262144 / 1024))KB, sha256=$SHA256"

# Folder-scan fixtures: three folders without media (one with a nested level) and one with a
# media file. They live under scan/ because the fixtures root also holds dash/, which has media.
echo "== Generating the folder-scan fixtures =="
SCAN_DIR="$COMPOSE_DIR/fixtures/scan"
rm -rf "$SCAN_DIR"
mkdir -p "$SCAN_DIR/empty-a/nested" "$SCAN_DIR/empty-b" "$SCAN_DIR/empty-c" "$SCAN_DIR/with-media"
for note in empty-a/nested empty-b empty-c with-media; do
    echo "not media" > "$SCAN_DIR/$note/note.txt"
done
echo "placeholder" > "$SCAN_DIR/with-media/clip.mp4"

# NFS: file with more than one READ RPC (1 MiB) and a size that is not a multiple of it, to exercise reassembly.
NFS_MULTIBLOCK="$COMPOSE_DIR/fixtures/nfs-multiblock.bin"
head -c $((3 * 1024 * 1024 + 12345)) /dev/urandom > "$NFS_MULTIBLOCK"
NFS_MULTIBLOCK_SHA256=$(sha256sum "$NFS_MULTIBLOCK" | cut -d' ' -f1)

echo "== Generating the WebDAV fixture =="
# The bytemark/webdav entrypoint chowns this directory to uid 82, so a previous run leaves it unwritable.
if [[ -d "$COMPOSE_DIR/fixtures/webdav" && ! -w "$COMPOSE_DIR/fixtures/webdav" ]]; then
    docker run --rm -v "$COMPOSE_DIR/fixtures:/fixtures" alpine rm -rf /fixtures/webdav
fi
mkdir -p "$COMPOSE_DIR/fixtures/webdav"
WEBDAV_FIXTURE="$COMPOSE_DIR/fixtures/webdav/testfile.bin"
head -c 131072 /dev/urandom > "$WEBDAV_FIXTURE"
WEBDAV_SHA256=$(sha256sum "$WEBDAV_FIXTURE" | cut -d' ' -f1)
echo "webdav/testfile.bin: $((131072 / 1024))KB, sha256=$WEBDAV_SHA256"

# Generates two real MPD manifests (through ffmpeg) from a synthetic 6s clip — one using
# SegmentTemplate ($Number$, video with 3 segments + audio), the other using a single-file
# SegmentBase (concatenating the first one's init+chunks, see the comment below). Served by
# http-test itself (nginx already serves any static file in fixtures/ with Range support).
echo "== Generating the DASH fixtures =="
DASH_DIR="$COMPOSE_DIR/fixtures/dash"
rm -rf "$DASH_DIR"
mkdir -p "$DASH_DIR/template" "$DASH_DIR/singlefile"

TINY_MP4=$(mktemp --suffix=.mp4)
ffmpeg -y -hide_banner -loglevel error \
    -f lavfi -i "testsrc=duration=6:size=320x240:rate=25" \
    -f lavfi -i "sine=duration=6:frequency=440" \
    -c:v libx264 -profile:v baseline -pix_fmt yuv420p -g 50 -keyint_min 50 -sc_threshold 0 \
    -c:a aac -shortest "$TINY_MP4"

ffmpeg -y -hide_banner -loglevel error -i "$TINY_MP4" -map 0:v -map 0:a -c copy \
    -f dash -seg_duration 2 -use_template 1 -use_timeline 0 \
    -init_seg_name 'init-$RepresentationID$.m4s' \
    -media_seg_name 'chunk-$RepresentationID$-$Number%05d$.m4s' \
    "$DASH_DIR/template/manifest.mpd"

# SegmentBase: `rust/protocols/src/dash` only understands SegmentBase+Initialization (not
# SegmentList, which is what `ffmpeg -f dash -single_file 1` produces).
# Instead of depending on the exact XML a given ffmpeg version generates for SegmentList,
# concatenate the video init segment with all the video chunks generated above
# (a valid continuous fMP4 file — init+moof/mdat concatenated is exactly what a
# single-file SegmentBase contains) and write the SegmentBase manifest by hand, with the
# byte ranges computed from the init segment's real size.
INIT_FILE="$DASH_DIR/template/init-0.m4s"
INIT_LEN=$(stat -c%s "$INIT_FILE")
cat "$INIT_FILE" "$DASH_DIR"/template/chunk-0-*.m4s > "$DASH_DIR/singlefile/video.mp4"
cat > "$DASH_DIR/singlefile/manifest.mpd" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT6.0S" type="static">
  <Period id="0">
    <AdaptationSet mimeType="video/mp4" contentType="video">
      <Representation id="v0" bandwidth="67414" width="320" height="240" codecs="avc1.42c00d">
        <BaseURL>video.mp4</BaseURL>
        <SegmentBase timescale="1000000">
          <Initialization range="0-$((INIT_LEN - 1))" />
        </SegmentBase>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>
EOF
rm -f "$TINY_MP4"
echo "dash/template and dash/singlefile generated ($(du -sh "$DASH_DIR" | cut -f1))"

echo "== Starting containers (smb-test, http-test, https-test, ftp-test, sftp-test, webdav-test, nfs-test) =="
"${DC[@]}" up -d --build

echo "== Waiting for HTTP (nginx) =="
for _ in $(seq 1 30); do
    if curl -sf -o /dev/null "http://127.0.0.1:18080/testfile.bin"; then
        break
    fi
    sleep 1
done
curl -sf -o /dev/null "http://127.0.0.1:18080/testfile.bin" || { echo "http-test did not respond in time"; "${DC[@]}" logs http-test; exit 1; }

echo "== Waiting for the TLS certificate (https-test) =="
CERT_PATH="$COMPOSE_DIR/https-test/certs/cert.pem"
for _ in $(seq 1 30); do
    [[ -f "$CERT_PATH" ]] && break
    sleep 1
done
[[ -f "$CERT_PATH" ]] || { echo "the certificate did not appear at $CERT_PATH in time"; "${DC[@]}" logs https-test; exit 1; }

echo "== Waiting for HTTPS (nginx + TLS) =="
for _ in $(seq 1 30); do
    if curl -sf --cacert "$CERT_PATH" -o /dev/null "https://127.0.0.1:18443/testfile.bin"; then
        break
    fi
    sleep 1
done
curl -sf --cacert "$CERT_PATH" -o /dev/null "https://127.0.0.1:18443/testfile.bin" || { echo "https-test did not respond in time"; "${DC[@]}" logs https-test; exit 1; }

echo "== Waiting for SMB (samba) =="
SMB_UP=0
for _ in $(seq 1 30); do
    if (exec 3<>/dev/tcp/127.0.0.1/14450) 2>/dev/null; then
        exec 3>&- 3<&-
        SMB_UP=1
        break
    fi
    sleep 1
done
if [[ "$SMB_UP" -eq 0 ]]; then
    echo "smb-test did not respond in time"
    "${DC[@]}" logs smb-test
    exit 1
fi
# The port opening does not mean smbd has finished starting (nmbd/smbd start in
# sequence) — allow a short grace period before hitting it with the real client.
sleep 2

echo "== Waiting for FTP (vsftpd) =="
FTP_UP=0
for _ in $(seq 1 30); do
    if (exec 3<>/dev/tcp/127.0.0.1/12121) 2>/dev/null; then
        exec 3>&- 3<&-
        FTP_UP=1
        break
    fi
    sleep 1
done
if [[ "$FTP_UP" -eq 0 ]]; then
    echo "ftp-test did not respond in time"
    "${DC[@]}" logs ftp-test
    exit 1
fi
# Same grace period as smb-test above: the control port (21) opens before
# vsftpd finishes processing USERS/ADDRESS/MIN_PORT/MAX_PORT.
sleep 2

echo "== Waiting for SFTP (atmoz/sftp) =="
SFTP_UP=0
for _ in $(seq 1 30); do
    if (exec 3<>/dev/tcp/127.0.0.1/12222) 2>/dev/null; then
        exec 3>&- 3<&-
        SFTP_UP=1
        break
    fi
    sleep 1
done
if [[ "$SFTP_UP" -eq 0 ]]; then
    echo "sftp-test did not respond in time"
    "${DC[@]}" logs sftp-test
    exit 1
fi
# Same grace period as smb-test/ftp-test above: the port opens before sshd
# finishes generating host keys + processing create-sftp-user.
sleep 2

echo "== Waiting for WebDAV (bytemark/webdav) =="
for _ in $(seq 1 30); do
    if curl -sf -u vruser:vrpass123 -o /dev/null "http://127.0.0.1:18099/testfile.bin"; then
        break
    fi
    sleep 1
done
curl -sf -u vruser:vrpass123 -o /dev/null "http://127.0.0.1:18099/testfile.bin" || { echo "webdav-test did not respond in time"; "${DC[@]}" logs webdav-test; exit 1; }

echo "== Waiting for NFS (NFS-Ganesha + rpcbind) =="
# Ganesha registers NFS and MOUNT with rpcbind only after starting: wait for MOUNT (100005) to appear.
NFS_UP=0
for _ in $(seq 1 30); do
    if "${DC[@]}" exec -T nfs-test rpcinfo -p 2>/dev/null | grep -q " 100005 "; then
        NFS_UP=1
        break
    fi
    sleep 1
done
if [[ "$NFS_UP" -eq 0 ]]; then
    echo "nfs-test did not register MOUNT with rpcbind in time"
    "${DC[@]}" logs nfs-test
    exit 1
fi

echo "== Running the Rust integration tests =="
export VRPLAYER_TEST_HTTP_URL="http://127.0.0.1:18080/testfile.bin"
export VRPLAYER_TEST_HTTPS_URL="https://127.0.0.1:18443/testfile.bin"
export VRPLAYER_TEST_CA_CERT="$CERT_PATH"
export VRPLAYER_TEST_FILE_SHA256="$SHA256"
export VRPLAYER_TEST_SMB_HOST="127.0.0.1"
export VRPLAYER_TEST_SMB_PORT="14450"
export VRPLAYER_TEST_SMB_USER="vruser"
export VRPLAYER_TEST_SMB_PASS="vrpass123"
export VRPLAYER_TEST_SMB_SHARE_AUTH="authshare"
export VRPLAYER_TEST_SMB_SHARE_GUEST="guestshare"
export VRPLAYER_TEST_SMB_FILE="testfile.bin"
export VRPLAYER_TEST_FTP_HOST="127.0.0.1"
export VRPLAYER_TEST_FTP_PORT="12121"
export VRPLAYER_TEST_FTP_USER="vruser"
export VRPLAYER_TEST_FTP_PASS="vrpass123"
export VRPLAYER_TEST_FTP_FILE="testfile.bin"
export VRPLAYER_TEST_SFTP_HOST="127.0.0.1"
export VRPLAYER_TEST_SFTP_PORT="12222"
export VRPLAYER_TEST_SFTP_USER="vruser"
export VRPLAYER_TEST_SFTP_PASS="vrpass123"
export VRPLAYER_TEST_SFTP_FILE="testfile.bin"
export VRPLAYER_TEST_SFTP_KEY_PATH="$COMPOSE_DIR/ssh-keys/vrplayer_test_key"
export VRPLAYER_TEST_NFS_HOST="127.0.0.1"
export VRPLAYER_TEST_NFS_PORT="2049"
export VRPLAYER_TEST_NFS_EXPORT="/export"
export VRPLAYER_TEST_NFS_FILE="testfile.bin"
export VRPLAYER_TEST_NFS_MULTIBLOCK_FILE="nfs-multiblock.bin"
export VRPLAYER_TEST_NFS_MULTIBLOCK_SHA256="$NFS_MULTIBLOCK_SHA256"
export VRPLAYER_TEST_HTTP_URL_BASE="http://127.0.0.1:18080"
export VRPLAYER_TEST_WEBDAV_HOST="127.0.0.1"
export VRPLAYER_TEST_WEBDAV_PORT="18099"
export VRPLAYER_TEST_WEBDAV_USER="vruser"
export VRPLAYER_TEST_WEBDAV_PASS="vrpass123"
export VRPLAYER_TEST_WEBDAV_FILE="testfile.bin"
export VRPLAYER_TEST_WEBDAV_FILE_SHA256="$WEBDAV_SHA256"

set +e
(cd "$REPO_ROOT/rust" && cargo test -p protocols --features integration-tests --no-fail-fast -- --ignored --nocapture --test-threads=1)
RESULT=$?
set -e

if [[ "$RESULT" -eq 0 ]]; then
    echo "== Network integration tests: PASS =="
else
    echo "== Network integration tests: FAIL =="
fi
exit "$RESULT"
