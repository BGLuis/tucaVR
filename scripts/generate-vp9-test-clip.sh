#!/bin/bash
# Gera clipes sintéticos de teste para validação de decodificação por hardware (T6.1-T6.3)
# dos codecs VP9 Profile 0 (8-bit), VP9 Profile 2 (10-bit) e AV1 no Meta Quest 3.
#
# Utiliza geradores de sinal do FFmpeg (testsrc2 e sine) para garantir testes
# determinísticos e reprodutíveis, sem necessidade de mídia externa ou download.
#
# Formatos gerados:
# 1. VP9 Profile 0: YUV 4:2:0 8-bit SDR em container WebM
# 2. VP9 Profile 2: YUV 4:2:0 10-bit (yuv420p10le) em container WebM
# 3. AV1: YUV 4:2:0 8-bit em container MP4 (ou WebM se encoder preferir)
set -euo pipefail

DURATION_SEC=10
RESOLUTION="1080p"
OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/testdata/codecs"
FORCE=0

GEN_VP9_P0=1
GEN_VP9_P2=1
GEN_AV1=1

usage() {
    cat <<EOF
Uso: $0 [opcoes]
  --duration-sec N   Duração de cada clipe em segundos (padrão: 10)
  --res RES          Resolução: '1080p' (1920x1080) ou '4k' (3840x2160) (padrão: 1080p)
  --out-dir PATH     Diretório de saída (padrão: testdata/codecs)
  --force            Regera mesmo se os arquivos já existirem
  --vp9-prof0        Gera apenas VP9 Profile 0 (8-bit)
  --vp9-prof2        Gera apenas VP9 Profile 2 (10-bit)
  --av1              Gera apenas AV1
  -h, --help         Exibe esta ajuda
EOF
}

# Se alguma flag específica de codec for passada, desativa a geração de todos por padrão
SPECIFIED_CODEC=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --duration-sec) DURATION_SEC="$2"; shift 2 ;;
        --res) RESOLUTION="$2"; shift 2 ;;
        --out-dir) OUT_DIR="$2"; shift 2 ;;
        --force) FORCE=1; shift ;;
        --vp9-prof0)
            if [[ $SPECIFIED_CODEC -eq 0 ]]; then
                GEN_VP9_P0=0; GEN_VP9_P2=0; GEN_AV1=0; SPECIFIED_CODEC=1
            fi
            GEN_VP9_P0=1; shift ;;
        --vp9-prof2)
            if [[ $SPECIFIED_CODEC -eq 0 ]]; then
                GEN_VP9_P0=0; GEN_VP9_P2=0; GEN_AV1=0; SPECIFIED_CODEC=1
            fi
            GEN_VP9_P2=1; shift ;;
        --av1)
            if [[ $SPECIFIED_CODEC -eq 0 ]]; then
                GEN_VP9_P0=0; GEN_VP9_P2=0; GEN_AV1=0; SPECIFIED_CODEC=1
            fi
            GEN_AV1=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Opção desconhecida: $1"; usage; exit 1 ;;
    esac
done

if ! command -v ffmpeg >/dev/null 2>&1; then
    echo "Erro: ffmpeg não encontrado no PATH — obrigatório para gerar clipes sintéticos." >&2
    exit 1
fi

case "$RESOLUTION" in
    1080p|1080)
        DIMENSIONS="1920x1080"
        BITRATE_VP9="8M"
        BITRATE_AV1="6M"
        ;;
    4k|4K|2160p)
        DIMENSIONS="3840x2160"
        BITRATE_VP9="20M"
        BITRATE_AV1="16M"
        ;;
    *)
        echo "Resolução inválida: $RESOLUTION. Escolha '1080p' ou '4k'." >&2
        exit 1
        ;;
esac

mkdir -p "$OUT_DIR"

# 1. VP9 Profile 0 (8-bit)
if [[ $GEN_VP9_P0 -eq 1 ]]; then
    OUT_FILE="$OUT_DIR/vp9-profile0-clip.webm"
    if [[ -f "$OUT_FILE" && "$FORCE" -eq 0 ]]; then
        echo "Já existe: $OUT_FILE ($(du -h "$OUT_FILE" | cut -f1)). Use --force para regerar."
    else
        echo "Gerando clipe VP9 Profile 0 8-bit (${DIMENSIONS}, ${DURATION_SEC}s)..."
        ffmpeg -y -hide_banner -loglevel warning \
            -f lavfi -i "testsrc2=size=${DIMENSIONS}:rate=30" \
            -f lavfi -i "sine=frequency=440:sample_rate=48000" \
            -t "$DURATION_SEC" \
            -c:v libvpx-vp9 -profile:v 0 -pix_fmt yuv420p \
            -b:v "$BITRATE_VP9" -deadline realtime -cpu-used 4 \
            -c:a libopus -b:a 128k \
            "$OUT_FILE"
        echo "Gerado com sucesso: $OUT_FILE ($(du -h "$OUT_FILE" | cut -f1))"
    fi
fi

# 2. VP9 Profile 2 (10-bit)
if [[ $GEN_VP9_P2 -eq 1 ]]; then
    OUT_FILE="$OUT_DIR/vp9-profile2-10bit-clip.webm"
    if [[ -f "$OUT_FILE" && "$FORCE" -eq 0 ]]; then
        echo "Já existe: $OUT_FILE ($(du -h "$OUT_FILE" | cut -f1)). Use --force para regerar."
    else
        echo "Gerando clipe VP9 Profile 2 10-bit (${DIMENSIONS}, ${DURATION_SEC}s)..."
        ffmpeg -y -hide_banner -loglevel warning \
            -f lavfi -i "testsrc2=size=${DIMENSIONS}:rate=30" \
            -f lavfi -i "sine=frequency=440:sample_rate=48000" \
            -t "$DURATION_SEC" \
            -c:v libvpx-vp9 -profile:v 2 -pix_fmt yuv420p10le \
            -b:v "$BITRATE_VP9" -deadline realtime -cpu-used 4 \
            -c:a libopus -b:a 128k \
            "$OUT_FILE"
        echo "Gerado com sucesso: $OUT_FILE ($(du -h "$OUT_FILE" | cut -f1))"
    fi
fi

# 3. AV1 (8-bit)
if [[ $GEN_AV1 -eq 1 ]]; then
    OUT_FILE="$OUT_DIR/av1-test-clip.mp4"
    if [[ -f "$OUT_FILE" && "$FORCE" -eq 0 ]]; then
        echo "Já existe: $OUT_FILE ($(du -h "$OUT_FILE" | cut -f1)). Use --force para regerar."
    else
        echo "Detectando encoder AV1 no FFmpeg..."
        AV1_ENCODER=""
        AV1_ARGS=()
        ENCODERS=$(ffmpeg -encoders 2>&1 || true)
        if echo "$ENCODERS" | grep -w "libsvtav1" >/dev/null; then
            AV1_ENCODER="libsvtav1"
            AV1_ARGS=(-preset 10)
        elif echo "$ENCODERS" | grep -w "libaom-av1" >/dev/null; then
            AV1_ENCODER="libaom-av1"
            AV1_ARGS=(-cpu-used 6 -crf 35)
        elif echo "$ENCODERS" | grep -w "librav1e" >/dev/null; then
            AV1_ENCODER="librav1e"
            AV1_ARGS=(-speed 10)
        else
            echo "Aviso: Nenhum encoder AV1 suportado encontrado no ffmpeg (libsvtav1, libaom-av1 ou librav1e). Pulando AV1." >&2
        fi

        if [[ -n "$AV1_ENCODER" ]]; then
            echo "Gerando clipe AV1 (${AV1_ENCODER}, ${DIMENSIONS}, ${DURATION_SEC}s)..."
            ffmpeg -y -hide_banner -loglevel warning \
                -f lavfi -i "testsrc2=size=${DIMENSIONS}:rate=30" \
                -f lavfi -i "sine=frequency=440:sample_rate=48000" \
                -t "$DURATION_SEC" \
                -c:v "$AV1_ENCODER" "${AV1_ARGS[@]}" -pix_fmt yuv420p \
                -b:v "$BITRATE_AV1" \
                -c:a libopus -b:a 128k \
                "$OUT_FILE"
            echo "Gerado com sucesso: $OUT_FILE ($(du -h "$OUT_FILE" | cut -f1))"
        fi
    fi
fi

echo "Concluído! Clipes de teste prontos em: $OUT_DIR"
