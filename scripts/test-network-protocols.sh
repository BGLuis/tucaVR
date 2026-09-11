#!/bin/bash
# Orquestra os testes de integracao de rede (T6/T7, docs/phases/PHASE-0.1-MVP.md,
# PHASE-0.2-3D-NETWORK.md, e Fase 0.4 Secoes 2/3 — achado R-04 de
# docs/reports/PHASE-0.4-08-VERIFICACAO-PROFUNDA.md): sobe servidores reais de SMB2/3,
# HTTP, HTTPS, FTP, SFTP, DASH e WebDAV via Docker (docker/network-tests/), roda os
# testes Rust marcados #[ignore] em rust/protocols/tests/*_integration.rs contra eles,
# e derruba tudo no final. Substitui a validacao manual que faltava em
# T6.1/T6.3/T7.1/R-04 ("nunca testado contra um servidor real").
#
# Uso:
#   ./scripts/test-network-protocols.sh            # roda tudo e derruba os containers
#   ./scripts/test-network-protocols.sh --keep      # deixa os containers de pe pra debug
#
# Requisitos: docker + docker compose (plugin), curl, sha256sum, ffmpeg (gera as
# fixtures DASH em tempo de teste — ver gerar_fixtures_dash abaixo). Nenhum headset
# ou hardware Quest 3 necessario — isto roda inteiramente no host de desenvolvimento.
set -euo pipefail

for bin in docker curl sha256sum ffmpeg; do
    command -v "$bin" >/dev/null 2>&1 || { echo "Requisito ausente: $bin"; exit 1; }
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
        echo "Derrubando containers..."
        "${DC[@]}" down -v >/dev/null 2>&1 || true
    else
        echo "Containers mantidos de pe (--keep). Derrube com: docker compose -f $COMPOSE_DIR/docker-compose.yml down -v"
    fi
}
trap cleanup EXIT

echo "== Gerando fixture de teste =="
mkdir -p "$COMPOSE_DIR/fixtures"
FIXTURE="$COMPOSE_DIR/fixtures/testfile.bin"
head -c 262144 /dev/urandom > "$FIXTURE"
SHA256=$(sha256sum "$FIXTURE" | cut -d' ' -f1)
echo "testfile.bin: $((262144 / 1024))KB, sha256=$SHA256"

echo "== Gerando fixture WebDAV (R-04) =="
mkdir -p "$COMPOSE_DIR/fixtures/webdav"
WEBDAV_FIXTURE="$COMPOSE_DIR/fixtures/webdav/testfile.bin"
head -c 131072 /dev/urandom > "$WEBDAV_FIXTURE"
WEBDAV_SHA256=$(sha256sum "$WEBDAV_FIXTURE" | cut -d' ' -f1)
echo "webdav/testfile.bin: $((131072 / 1024))KB, sha256=$WEBDAV_SHA256"

# R-04: gera dois manifestos MPD reais (via ffmpeg) contra um clipe sintético de 6s —
# um em SegmentTemplate ($Number$, video com 3 segmentos + audio), outro em SegmentBase
# de arquivo único (concatenando init+chunks do primeiro, ver comentário abaixo). Servidos
# pelo próprio http-test (nginx já serve qualquer arquivo estático em fixtures/ com Range).
echo "== Gerando fixtures DASH (R-04) =="
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

# SegmentBase: `rust/protocols/src/dash` só entende SegmentBase+Initialization (não
# SegmentList, que é o que `ffmpeg -f dash -single_file 1` produz) — ver R-02/R-03.
# Em vez de depender do XML exato que uma versão de ffmpeg gera para SegmentList,
# concatena o init segment de vídeo com todos os chunks de vídeo já gerados acima
# (arquivo fMP4 contínuo válido — init+moof/mdat concatenados é exatamente o que um
# SegmentBase de arquivo único contém) e escreve o manifesto SegmentBase à mão, com
# os byte-ranges computados do tamanho real do init segment.
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
echo "dash/template e dash/singlefile gerados ($(du -sh "$DASH_DIR" | cut -f1))"

echo "== Subindo containers (smb-test, http-test, https-test, ftp-test, sftp-test, webdav-test) =="
"${DC[@]}" up -d --build

echo "== Aguardando HTTP (nginx) =="
for _ in $(seq 1 30); do
    if curl -sf -o /dev/null "http://127.0.0.1:18080/testfile.bin"; then
        break
    fi
    sleep 1
done
curl -sf -o /dev/null "http://127.0.0.1:18080/testfile.bin" || { echo "http-test nao respondeu a tempo"; "${DC[@]}" logs http-test; exit 1; }

echo "== Aguardando certificado TLS (https-test) =="
CERT_PATH="$COMPOSE_DIR/https-test/certs/cert.pem"
for _ in $(seq 1 30); do
    [[ -f "$CERT_PATH" ]] && break
    sleep 1
done
[[ -f "$CERT_PATH" ]] || { echo "certificado nao apareceu em $CERT_PATH a tempo"; "${DC[@]}" logs https-test; exit 1; }

echo "== Aguardando HTTPS (nginx + TLS) =="
for _ in $(seq 1 30); do
    if curl -sf --cacert "$CERT_PATH" -o /dev/null "https://127.0.0.1:18443/testfile.bin"; then
        break
    fi
    sleep 1
done
curl -sf --cacert "$CERT_PATH" -o /dev/null "https://127.0.0.1:18443/testfile.bin" || { echo "https-test nao respondeu a tempo"; "${DC[@]}" logs https-test; exit 1; }

echo "== Aguardando SMB (samba) =="
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
    echo "smb-test nao respondeu a tempo"
    "${DC[@]}" logs smb-test
    exit 1
fi
# A porta abrir nao significa que o smbd terminou de subir (nmbd/smbd sobem em
# sequencia) — da uma folga curta antes de bater com o client de verdade.
sleep 2

echo "== Aguardando FTP (vsftpd) =="
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
    echo "ftp-test nao respondeu a tempo"
    "${DC[@]}" logs ftp-test
    exit 1
fi
# Mesma folga do smb-test acima: a porta de controle (21) abre antes do
# vsftpd terminar de processar USERS/ADDRESS/MIN_PORT/MAX_PORT.
sleep 2

echo "== Aguardando SFTP (atmoz/sftp) =="
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
    echo "sftp-test nao respondeu a tempo"
    "${DC[@]}" logs sftp-test
    exit 1
fi
# Mesma folga do smb-test/ftp-test acima: a porta abre antes do sshd
# terminar de gerar host keys + processar create-sftp-user.
sleep 2

echo "== Aguardando WebDAV (bytemark/webdav) =="
for _ in $(seq 1 30); do
    if curl -sf -u vruser:vrpass123 -o /dev/null "http://127.0.0.1:18099/testfile.bin"; then
        break
    fi
    sleep 1
done
curl -sf -u vruser:vrpass123 -o /dev/null "http://127.0.0.1:18099/testfile.bin" || { echo "webdav-test nao respondeu a tempo"; "${DC[@]}" logs webdav-test; exit 1; }

echo "== Rodando testes de integracao Rust =="
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
export VRPLAYER_TEST_HTTP_URL_BASE="http://127.0.0.1:18080"
export VRPLAYER_TEST_WEBDAV_HOST="127.0.0.1"
export VRPLAYER_TEST_WEBDAV_PORT="18099"
export VRPLAYER_TEST_WEBDAV_USER="vruser"
export VRPLAYER_TEST_WEBDAV_PASS="vrpass123"
export VRPLAYER_TEST_WEBDAV_FILE="testfile.bin"
export VRPLAYER_TEST_WEBDAV_FILE_SHA256="$WEBDAV_SHA256"

set +e
(cd "$REPO_ROOT/rust" && cargo test -p protocols --features integration-tests -- --ignored --nocapture --test-threads=1)
RESULT=$?
set -e

if [[ "$RESULT" -eq 0 ]]; then
    echo "== Testes de integracao de rede: PASS =="
else
    echo "== Testes de integracao de rede: FAIL =="
fi
exit "$RESULT"
