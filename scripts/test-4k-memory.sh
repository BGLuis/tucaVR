#!/bin/bash
# Teste objetivo e automatizado do criterio de DoD "Memoria < 2.5GB durante
# reproducao de video 4K" (docs/phases/PHASE-0.1-MVP.md) — sem precisar de um
# arquivo 4K real nem de interacao manual com o controller: gera um clipe H.264
# 4K sintetico e reprodutivel, envia pro headset via adb, dispara playback
# automatico (EXTRA_AUTO_PLAY_PATH, ver VRActivity.kt) e reusa a amostragem de
# memoria de scripts/soak-test.sh.
#
# Uso:
#   ./scripts/test-4k-memory.sh                         # padrao: clipe de 150s, monitora 2min
#   ./scripts/test-4k-memory.sh --duration-min 4 --clip-duration-sec 300
#
# Diferenca para scripts/soak-test.sh puro: aquele mede o app do jeito que
# estiver quando chamado (idle, ou com --video-path se voce ja tiver um arquivo
# no headset); este script garante uma carga de trabalho 4K real e reproduzivel
# de ponta a ponta, o que o criterio da DoD pede especificamente.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DURATION_MIN=2
INTERVAL_SEC=10
CLIP_DURATION_SEC=150
BITRATE="25M"
SERIAL=""
FORCE_REGEN=0
KEEP_ON_DEVICE=0
DEVICE_PATH="/sdcard/Movies/vrplayer-4k-soak-test.mp4"

usage() {
    cat <<EOF
Uso: $0 [opcoes]
  --duration-min N        Duracao do monitoramento de memoria (padrao: 2)
  --interval-sec N        Intervalo entre amostras (padrao: 10)
  --clip-duration-sec N   Duracao do clipe 4K gerado (padrao: 150; deve ser
                           maior que duration-min*60 + ~30s de folga)
  --bitrate RATE           Bitrate do clipe, formato ffmpeg (padrao: 25M)
  --serial SERIAL          Serial do dispositivo adb
  --force-regen             Regera o clipe mesmo se ja existir em testdata/
  --keep-on-device          Nao apaga o clipe do headset ao final
  -h, --help                 Mostra esta ajuda
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --duration-min) DURATION_MIN="$2"; shift 2 ;;
        --interval-sec) INTERVAL_SEC="$2"; shift 2 ;;
        --clip-duration-sec) CLIP_DURATION_SEC="$2"; shift 2 ;;
        --bitrate) BITRATE="$2"; shift 2 ;;
        --serial) SERIAL="$2"; shift 2 ;;
        --force-regen) FORCE_REGEN=1; shift ;;
        --keep-on-device) KEEP_ON_DEVICE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Opcao desconhecida: $1"; usage; exit 1 ;;
    esac
done

NEEDED_SEC=$((DURATION_MIN * 60 + 30))
if [[ "$CLIP_DURATION_SEC" -lt "$NEEDED_SEC" ]]; then
    echo "AVISO: clip-duration-sec ($CLIP_DURATION_SEC) e menor que duration-min*60+30 ($NEEDED_SEC)." >&2
    echo "         O video pode acabar antes do monitoramento terminar, medindo o app parado no fim." >&2
fi

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB=(adb -s "$SERIAL")
fi

DEVICE_COUNT=$("${ADB[@]}" devices | grep -c -E "device$" || true)
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo "Nenhum dispositivo adb encontrado. Conecte o Quest 3 via USB e autorize a Depuracao USB." >&2
    exit 1
fi

echo "== Gerando clipe 4K de teste =="
GEN_ARGS=(--duration-sec "$CLIP_DURATION_SEC" --bitrate "$BITRATE")
[[ "$FORCE_REGEN" -eq 1 ]] && GEN_ARGS+=(--force)
"$SCRIPT_DIR/generate-4k-test-clip.sh" "${GEN_ARGS[@]}"
CLIP="$REPO_ROOT/testdata/4k-soak-clip.mp4"

echo "== Enviando clipe para o headset ($DEVICE_PATH) =="
"${ADB[@]}" shell mkdir -p /sdcard/Movies
"${ADB[@]}" push "$CLIP" "$DEVICE_PATH"

cleanup() {
    if [[ "$KEEP_ON_DEVICE" -eq 0 ]]; then
        "${ADB[@]}" shell rm -f "$DEVICE_PATH" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

echo "== Rodando soak-test.sh com auto-play do clipe 4K =="
SOAK_ARGS=(--video-path "$DEVICE_PATH" --duration-min "$DURATION_MIN" --interval-sec "$INTERVAL_SEC")
[[ -n "$SERIAL" ]] && SOAK_ARGS+=(--serial "$SERIAL")

"$SCRIPT_DIR/soak-test.sh" "${SOAK_ARGS[@]}"
