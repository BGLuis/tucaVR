#!/bin/bash
# Orquestra os testes de integracao de rede (T6/T7, docs/phases/PHASE-0.1-MVP.md):
# sobe servidores reais de SMB2/3, HTTP e HTTPS via Docker (docker/network-tests/),
# roda os testes Rust marcados #[ignore] em rust/protocols/tests/*_integration.rs
# contra eles, e derruba tudo no final. Substitui a validacao manual que faltava
# em T6.1/T6.3/T7.1 ("nunca testado contra um servidor real").
#
# Uso:
#   ./scripts/test-network-protocols.sh            # roda tudo e derruba os containers
#   ./scripts/test-network-protocols.sh --keep      # deixa os containers de pe pra debug
#
# Requisitos: docker + docker compose (plugin), curl, sha256sum. Nenhum headset
# ou hardware Quest 3 necessario — isto roda inteiramente no host de desenvolvimento.
set -euo pipefail

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

echo "== Subindo containers (smb-test, http-test, https-test) =="
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
