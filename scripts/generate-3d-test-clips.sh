#!/bin/bash
# Gera clipes sinteticos H.264 pra testar visualmente cada ScreenMode (ver enum em
# native/src/vr_player_app.cpp / rust/bridge/src/lib.rs). Cada clipe rotula os dois
# olhos (SBS/OU) ou marca 0/25/50/75/100% da largura (mono), pra ficar obvio num
# screenshot se o crop/split esta certo ou se a imagem esta duplicada/cortada.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$REPO_ROOT/testdata/3d-test-clips"
RESOLUTION="4k"
DURATION_SEC=15
FORCE=0
FONT="/usr/share/fonts/liberation/LiberationSans-Bold.ttf"

usage() {
    cat <<EOF
Uso: $0 [opcoes]
  --resolution 4k|5.7k|8k   Largura combinada do frame (padrao: 4k = 3840px)
  --duration-sec N          Duracao de cada clipe (padrao: 15)
  --out-dir PATH             Diretorio de saida (padrao: testdata/3d-test-clips)
  --force                     Regera mesmo se os arquivos ja existirem
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --resolution) RESOLUTION="$2"; shift 2 ;;
        --duration-sec) DURATION_SEC="$2"; shift 2 ;;
        --out-dir) OUT_DIR="$2"; shift 2 ;;
        --force) FORCE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Opcao desconhecida: $1"; usage; exit 1 ;;
    esac
done

case "$RESOLUTION" in
    4k) W=3840; H=2160 ;;
    5.7k) W=5760; H=2880 ;;
    8k) W=7680; H=4320 ;;
    *) echo "Resolucao desconhecida: $RESOLUTION (use 4k, 5.7k ou 8k)" >&2; exit 1 ;;
esac

if ! command -v ffmpeg >/dev/null 2>&1; then
    echo "ffmpeg nao encontrado no PATH." >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

encode() {
    local out="$1" filter_complex="$2"
    if [[ -f "$out" && "$FORCE" -eq 0 ]]; then
        echo "Ja existe: $out (use --force pra regerar)."
        return
    fi
    ffmpeg -y -hide_banner -loglevel warning \
        -f lavfi -i "sine=frequency=440:sample_rate=48000" \
        -filter_complex "$filter_complex" \
        -t "$DURATION_SEC" -map "[out]" -map 0:a \
        -c:v libx264 -preset veryfast -profile:v high -level 5.2 -pix_fmt yuv420p -b:v 30M -maxrate 30M -bufsize 60M \
        -c:a aac -b:a 128k -movflags +faststart \
        "$out"
    echo "Gerado: $out ($(du -h "$out" | cut -f1))"
}

# Marcadores 0/25/50/75/100% da largura — verifica crop/duplicacao no modo mono.
mono_markers() {
    local w="$1" fs=$(( $1 / 40 ))
    echo "drawtext=fontfile=$FONT:text='0pct':x=10:y=10:fontsize=$fs:fontcolor=white,"\
"drawtext=fontfile=$FONT:text='25pct':x=w*0.25-40:y=10:fontsize=$fs:fontcolor=yellow,"\
"drawtext=fontfile=$FONT:text='50pct':x=w*0.5-40:y=10:fontsize=$fs:fontcolor=cyan,"\
"drawtext=fontfile=$FONT:text='75pct':x=w*0.75-40:y=10:fontsize=$fs:fontcolor=magenta,"\
"drawtext=fontfile=$FONT:text='100pct':x=w-160:y=10:fontsize=$fs:fontcolor=white"
}

echo "== mono (180/360 mono — a diferenca e so o modo escolhido no player) =="
encode "$OUT_DIR/mono.mp4" \
    "testsrc2=size=${W}x${H}:rate=30,$(mono_markers "$W")[out]"

echo "== sbs (flat/360/180 SBS — a diferenca e so o modo escolhido no player) =="
EW=$((W/2))
encode "$OUT_DIR/sbs.mp4" \
    "color=c=green:size=${EW}x${H}:rate=30,drawtext=fontfile=$FONT:text='ESQUERDA':x=(w-tw)/2:y=(h-th)/2:fontsize=$((EW/8)):fontcolor=white,$(mono_markers "$EW")[left];\
color=c=maroon:size=${EW}x${H}:rate=30,drawtext=fontfile=$FONT:text='DIREITA':x=(w-tw)/2:y=(h-th)/2:fontsize=$((EW/8)):fontcolor=white,$(mono_markers "$EW")[right];\
[left][right]hstack=inputs=2[out]"

echo "== ou (flat/360 OU — a diferenca e so o modo escolhido no player) =="
EH=$((H/2))
encode "$OUT_DIR/ou.mp4" \
    "color=c=green:size=${W}x${EH}:rate=30,drawtext=fontfile=$FONT:text='CIMA':x=(w-tw)/2:y=(h-th)/2:fontsize=$((W/20)):fontcolor=white[top];\
color=c=maroon:size=${W}x${EH}:rate=30,drawtext=fontfile=$FONT:text='BAIXO':x=(w-tw)/2:y=(h-th)/2:fontsize=$((W/20)):fontcolor=white[bottom];\
[top][bottom]vstack=inputs=2[out]"

echo ""
echo "Clipes em $OUT_DIR — use com scripts/test-3d-playback.sh <arquivo> <screen_mode>"
