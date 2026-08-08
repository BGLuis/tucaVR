#!/bin/bash
# Gera um clipe H.264 4K sintetico (padrao de teste + tom senoidal), reprodutivel e
# decodificavel pelo hardware do Quest 3 (H.264 High@5.2, doc secao 2: "H.264 ate
# 4K@60fps"), para servir de carga de teste objetiva do criterio da DoD "Memoria <
# 2.5GB durante reproducao de video 4K" (docs/phases/PHASE-0.1-MVP.md). Usado por
# scripts/test-4k-memory.sh — nao precisa rodar isto na mao normalmente.
#
# Nao gera video "de verdade" (filmagem) de proposito: testsrc2 e sine sao
# deterministicos, nao dependem de nenhum arquivo de midia externo, e geram em
# segundos mesmo em 4K (medido: ~2.8s para 10s de conteudo com preset veryfast).
set -euo pipefail

DURATION_SEC=150
BITRATE="25M"
OUT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/testdata/4k-soak-clip.mp4"
FORCE=0

usage() {
    cat <<EOF
Uso: $0 [opcoes]
  --duration-sec N   Duracao do clipe em segundos (padrao: 150)
  --bitrate RATE      Bitrate de video, formato ffmpeg (padrao: 25M)
  --out PATH           Caminho de saida (padrao: testdata/4k-soak-clip.mp4)
  --force               Regera mesmo se o arquivo ja existir
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --duration-sec) DURATION_SEC="$2"; shift 2 ;;
        --bitrate) BITRATE="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --force) FORCE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Opcao desconhecida: $1"; usage; exit 1 ;;
    esac
done

if ! command -v ffmpeg >/dev/null 2>&1; then
    echo "ffmpeg nao encontrado no PATH — necessario para gerar o clipe de teste." >&2
    exit 1
fi

if [[ -f "$OUT" && "$FORCE" -eq 0 ]]; then
    echo "Ja existe: $OUT ($(du -h "$OUT" | cut -f1)). Use --force para regerar."
    exit 0
fi

mkdir -p "$(dirname "$OUT")"

echo "Gerando clipe 4K de teste (${DURATION_SEC}s @ ${BITRATE})..."
ffmpeg -y -hide_banner -loglevel warning \
    -f lavfi -i "testsrc2=size=3840x2160:rate=30" \
    -f lavfi -i "sine=frequency=440:sample_rate=48000" \
    -t "$DURATION_SEC" \
    -c:v libx264 -preset veryfast -profile:v high -level 5.2 -pix_fmt yuv420p \
    -b:v "$BITRATE" -maxrate "$BITRATE" -bufsize "50M" \
    -c:a aac -b:a 128k \
    -movflags +faststart \
    "$OUT"

echo "Gerado: $OUT ($(du -h "$OUT" | cut -f1))"
