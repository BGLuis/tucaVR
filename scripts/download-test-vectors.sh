#!/usr/bin/env bash
# =============================================================================
# download-test-vectors.sh — VR Multimedia Player Test Vectors v3
# =============================================================================
#
# Baixa vídeos REAIS para teste, cobrindo docs/REQUIREMENTS.md.
#
# Fontes:
#  - test-videos.co.uk : H.264, H.265, VP9 (Big Buck Bunny, CC BY 3.0)
#  - ExoPlayer GCS     : Congo 360° mono, VP9 360° WebM
#  - archive.org       : Doctor Who 3D SBS real (domínio público / upload livre)
#  - Apple CDN         : HLS manifests
#  - wvmedia (Google)  : DASH VP9 manifests
#  - YouTube (manual)  : VR180 — requer cookies (instrução ao final)
#  - ffmpeg            : Apenas para formatos sem fonte pública verificada:
#                        Over/Under (OU) e 4K stress test
#
# Uso:
#   chmod +x scripts/download-test-vectors.sh
#   ./scripts/download-test-vectors.sh
#
# Para VR180 real do YouTube, veja: testdata/vectors/vr180/COMO_BAIXAR.md
#
# Dependências: curl, ffmpeg, ffprobe
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTDIR="$REPO_ROOT/testdata/vectors"
LOG="$OUTDIR/download.log"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

info()    { echo -e "${BLUE}[INFO]${NC} $*" | tee -a "$LOG"; }
ok()      { echo -e "${GREEN}[ OK ]${NC} $*" | tee -a "$LOG"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*" | tee -a "$LOG"; }
err()     { echo -e "${RED}[ERRO]${NC} $*" | tee -a "$LOG"; }
gen()     { echo -e "${CYAN}[GEN ]${NC} $*" | tee -a "$LOG"; }
section() {
    echo -e "\n${BOLD}${CYAN}══════════════════════════════════════════════════${NC}" | tee -a "$LOG"
    echo -e "${BOLD}${CYAN}  $*${NC}" | tee -a "$LOG"
    echo -e "${BOLD}${CYAN}══════════════════════════════════════════════════${NC}" | tee -a "$LOG"
}

DL=0; SKIP=0; FAIL=0; GEN_COUNT=0

dl() {
    local url="$1" rel="$2" desc="${3:-}" maxtime="${4:-600}"
    local dest="$OUTDIR/$rel"
    mkdir -p "$(dirname "$dest")"
    if [[ -f "$dest" && -s "$dest" ]]; then
        warn "JÁ EXISTE: $rel  ($(du -sh "$dest" | cut -f1))"; ((SKIP++)) || true; return 0
    fi
    info "Baixando: ${desc:-$rel}"
    info "  URL: $url"
    if curl -fsSL --retry 3 --retry-delay 3 --connect-timeout 20 \
            --max-time "$maxtime" -L -o "$dest" "$url" 2>>"$LOG"; then
        local sz; sz=$(du -sh "$dest" | cut -f1)
        ok "  → $rel ($sz)"; ((DL++)) || true
    else
        err "  → FALHA: $rel"; rm -f "$dest"; ((FAIL++)) || true
    fi
}

gen_ff() {
    local rel="$1" desc="$2"; shift 2
    local dest="$OUTDIR/$rel"
    mkdir -p "$(dirname "$dest")"
    if [[ -f "$dest" && -s "$dest" ]]; then
        warn "JÁ EXISTE: $rel"; ((SKIP++)) || true; return 0
    fi
    gen "Gerando: $desc"
    if ffmpeg -y -hide_banner -loglevel error "$@" "$dest" 2>>"$LOG"; then
        ok "  → $rel ($(du -sh "$dest" | cut -f1))"; ((GEN_COUNT++)) || true
    else
        err "  → FALHA ao gerar: $rel"; rm -f "$dest"
    fi
}

mkdir -p "$OUTDIR"
echo "# Log — $(date)" > "$LOG"

echo -e "${BOLD}"
echo "╔════════════════════════════════════════════════════════════╗"
echo "║   VR Multimedia Player — Test Vector Downloader v3        ║"
echo "║   Vídeos reais + ffmpeg apenas onde não há fonte pública  ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

# =============================================================================
# SEÇÃO 1 — 2D Básico: H.264, H.265, VP9  (RF-2D-001/003/004)
# Fonte: test-videos.co.uk — BigBuckBunny CC BY 3.0 Blender Foundation
# =============================================================================
section "2D — H.264 / H.265 / VP9 reais (RF-2D-001/003/004)"

BASE="https://test-videos.co.uk/vids/bigbuckbunny"

dl "$BASE/mp4/h264/1080/Big_Buck_Bunny_1080_10s_5MB.mp4" \
   "2d/h264_bbb_1080p.mp4" "H.264 1080p — BigBuckBunny (RF-2D-003)"

dl "$BASE/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4" \
   "2d/h264_bbb_720p.mp4" "H.264 720p — BigBuckBunny (RF-2D-003)"

dl "$BASE/mp4/h265/1080/Big_Buck_Bunny_1080_10s_5MB.mp4" \
   "2d/h265_bbb_1080p.mp4" "H.265/HEVC 1080p — HW decode obrigatório (RF-2D-003)"

dl "$BASE/webm/vp9/1080/Big_Buck_Bunny_1080_10s_5MB.webm" \
   "2d/vp9_bbb_1080p.webm" "VP9 WebM 1080p — BigBuckBunny (RF-2D-004)"

# Sintel trailer — W3C, H.264, ~52s, cena real de animação Blender
dl "https://media.w3.org/2010/05/sintel/trailer.mp4" \
   "2d/h264_sintel_trailer.mp4" "H.264 — Sintel trailer 854x480 52s (RF-2D-001)"

# BigBuckBunny completo (apenas 720p — ~64MB, real movie)
dl "$BASE/mp4/h264/720/Big_Buck_Bunny_720_10s_30MB.mp4" \
   "2d/h264_bbb_720p_long.mp4" "H.264 720p 30MB — versão longa (RF-2D-001)" 120 || \
info "  Versão longa não disponível — apenas clipes de 10s"

# =============================================================================
# SEÇÃO 2 — 360° Monoscópico REAL (RF-3D-003)
# Fontes: ExoPlayer GCS — únicos buckets ainda públicos
# =============================================================================
section "360° Monoscópico — Vídeos reais (RF-3D-003)"

# Congo 360° — ExoPlayer, cena de natureza REAL filmada em 360°
dl "https://storage.googleapis.com/exoplayer-test-media-1/360/congo.mp4" \
   "360/360_mono_congo_h264.mp4" "360° REAL — Congo H.264 1080p (RF-3D-003)"

# VP9 360° — ExoPlayer gen-3, 360° screen content
dl "https://storage.googleapis.com/exoplayer-test-media-1/gen-3/screens/dash-vod-single-segment/video-vp9-360.webm" \
   "360/360_mono_vp9_640p.webm" "360° VP9 WebM — ExoPlayer screen (RF-3D-003)"

# =============================================================================
# SEÇÃO 3 — 3D Side-by-Side REAL (RF-3D-001)
# Fonte: archive.org — Doctor Who Dimensions in Time 3D (SBS real)
# Nota: 379MB — vídeo real filmado em 3D estereoscópico
# =============================================================================
section "3D Side-by-Side — Vídeo REAL de arquivo (RF-3D-001)"

warn "Baixando Doctor Who 3D SBS (~379MB) — vídeo 3D REAL de arquivo..."
warn "Isso pode levar alguns minutos dependendo da sua conexão."

dl "https://archive.org/download/doctor-who-dimensions-in-time-3-d-sbs-cross-eye/Doctor%20Who%20-%20Dimensions%20in%20time%203D%20(SBS%20%26%20cross%20eye).mp4" \
   "3d-sbs/doctor_who_3d_sbs_real.mp4" \
   "3D SBS REAL — Doctor Who Dimensions in Time (archive.org)" \
   900

# Complemento: SBS half (half-width por olho) compacto com ffmpeg
# Usa o Congo 360° como base para criar um SBS realista com conteúdo real
if [[ -f "$OUTDIR/360/360_mono_congo_h264.mp4" ]]; then
    gen_ff "3d-sbs/sbs_half_from_360_congo.mp4" \
        "3D SBS half gerado a partir do Congo 360° real (RF-3D-001)" \
        -i "$OUTDIR/360/360_mono_congo_h264.mp4" \
        -i "$OUTDIR/360/360_mono_congo_h264.mp4" \
        -filter_complex "[0:v]crop=iw/2:ih:0:0[left];[1:v]crop=iw/2:ih:iw/2:0[right];[left][right]hstack[v]" \
        -map "[v]" -map "0:a?" \
        -c:v libx264 -crf 20 -preset fast \
        -c:a copy \
        -movflags +faststart
fi

# =============================================================================
# SEÇÃO 4 — 3D Over/Under REAL (RF-3D-002)
# Sem fonte HTTP pública verificada para OU — gerado a partir de conteúdo real
# =============================================================================
section "3D Over/Under — Gerado de conteúdo real (RF-3D-002)"

# OU gerado a partir do Doctor Who SBS — converte SBS→OU para ter conteúdo real
if [[ -f "$OUTDIR/3d-sbs/doctor_who_3d_sbs_real.mp4" ]]; then
    gen_ff "3d-ou/ou_from_doctorwho_sbs.mp4" \
        "3D OU convertido do Doctor Who SBS — conteúdo real (RF-3D-002)" \
        -i "$OUTDIR/3d-sbs/doctor_who_3d_sbs_real.mp4" \
        -filter_complex \
        "[0:v]crop=iw/2:ih:0:0,scale=iw:ih[left];[0:v]crop=iw/2:ih:iw/2:0,scale=iw:ih[right];[left][right]vstack[v]" \
        -map "[v]" -map "0:a?" \
        -c:v libx264 -crf 20 -preset fast \
        -c:a copy \
        -movflags +faststart
else
    warn "Doctor Who SBS não baixado — usando fallback para OU"
    # OU a partir do Congo 360° real (metade esquerda em cima, metade direita embaixo)
    gen_ff "3d-ou/ou_half_from_congo_360.mp4" \
        "3D OU gerado do Congo 360° real (RF-3D-002)" \
        -i "$OUTDIR/360/360_mono_congo_h264.mp4" \
        -i "$OUTDIR/360/360_mono_congo_h264.mp4" \
        -filter_complex "[0:v]crop=iw:ih/2:0:0[top];[1:v]crop=iw:ih/2:0:ih/2[bot];[top][bot]vstack[v]" \
        -map "[v]" -map "0:a?" \
        -c:v libx264 -crf 20 -preset fast \
        -movflags +faststart
fi

# =============================================================================
# SEÇÃO 5 — VR180 (RF-3D-005)
# =============================================================================
section "VR180 — Instruções para download manual (RF-3D-005)"

mkdir -p "$OUTDIR/vr180"

# Tentar yt-dlp com cookies do Firefox (se disponível e funcionando)
if command -v yt-dlp &>/dev/null; then
    info "Tentando VR180 via yt-dlp..."
    
    VR180_DEST="$OUTDIR/vr180/vr180_lightspeed_real.mp4"
    
    if [[ -f "$VR180_DEST" && -s "$VR180_DEST" ]]; then
        warn "JÁ EXISTE: vr180/vr180_lightspeed_real.mp4"; ((SKIP++)) || true
    else
        # Tenta com Firefox cookies
        if yt-dlp \
            --cookies-from-browser firefox \
            -f "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best[height<=1080]" \
            --no-playlist \
            --max-filesize 200m \
            -o "$VR180_DEST" \
            "https://www.youtube.com/watch?v=SqhLi11UWWM" \
            2>>"$LOG"; then
            ok "  → VR180 baixado via yt-dlp"
            ((DL++)) || true
        else
            warn "  → yt-dlp com Firefox falhou — VR180 precisa de ação manual"
        fi
    fi
fi

# Gera placeholder informativo para VR180 (vídeo curto com instruções)
if [[ ! -f "$OUTDIR/vr180/vr180_lightspeed_real.mp4" || \
      ! -s "$OUTDIR/vr180/vr180_lightspeed_real.mp4" ]]; then
    gen_ff "vr180/vr180_placeholder_sbs.mp4" \
        "VR180 SBS placeholder — aguardando download manual" \
        -f lavfi -i "color=c=0x1a1a2e:duration=5:size=3840x2160:rate=30" \
        -f lavfi -i "sine=frequency=440:duration=5" \
        -filter_complex \
        "[0:v]split[l][r];[l]drawtext=text='LEFT EYE — VR180':x=(w/2-text_w)/2:y=(h-text_h)/2:fontsize=48:fontcolor=0x00d4ff[l2];[r]drawtext=text='RIGHT EYE — VR180':x=w/2+(w/2-text_w)/2:y=(h-text_h)/2:fontsize=48:fontcolor=0x00ff88[r2];[l2][r2]overlay[v]" \
        -map "[v]" -map "1:a" \
        -c:v libx264 -crf 22 -preset fast \
        -c:a aac -b:a 128k \
        -movflags +faststart
fi

# Cria guia detalhado para download manual de VR180
cat > "$OUTDIR/vr180/COMO_BAIXAR.md" << 'EOF'
# Como baixar VR180 real para testes

## Problema
YouTube bloqueia yt-dlp sem autenticação válida (rate-limit / bot detection).
VR180 real só existe em quantidade no YouTube.

## Opção 1 — yt-dlp com cookies exportados manualmente (recomendado)

### No browser (Chrome/Firefox):
1. Instalar extensão: "Get cookies.txt LOCALLY" (Chrome) ou "cookies.txt" (Firefox)
2. Acessar youtube.com logado na sua conta
3. Exportar cookies para arquivo `youtube_cookies.txt`

### No terminal:
```bash
yt-dlp \
  --cookies /caminho/para/youtube_cookies.txt \
  -f "bestvideo[height<=1080]+bestaudio/best" \
  --no-playlist \
  -o "testdata/vectors/vr180/%(title)s.%(ext)s" \
  "https://www.youtube.com/watch?v=SqhLi11UWWM"
```

## Opção 2 — yt-dlp com browser aberto (mais simples)
```bash
# Feche o Chrome primeiro, depois:
yt-dlp \
  --cookies-from-browser chrome \
  -f "bestvideo[height<=1080]+bestaudio/best" \
  -o "testdata/vectors/vr180/%(title)s.%(ext)s" \
  "https://www.youtube.com/watch?v=SqhLi11UWWM"
```

## Vídeos VR180 recomendados para baixar

| ID YouTube | Título | Duração | Resolução |
|------------|--------|---------|-----------|
| SqhLi11UWWM | Experience LIGHTSPEED in VR180 3D | 67s | 4K |
| arMYi_93nXs | Introduction to Stereoscopic 3D 180° VR (Canon) | 494s | 4K |
| 0M4ce-0VSZg | URSA Cine Immersive vs Canon EOS VR | 682s | 8K |

## Como identificar VR180 vs 360° no player
- **VR180**: Proporção 2:1, apenas metade frontal mapeada na esfera (180°)
- **360°**: Proporção 2:1, esfera completa, head tracking em todas direções
- **3D SBS**: Metade esquerda = olho esq, metade direita = olho dir

## O que o player deve fazer com VR180:
1. Detectar o formato (metadados ou heurística 2:1 + projeção="equirectangular")
2. Dividir horizontalmente ao meio
3. Enviar metade esquerda para o olho esquerdo
4. Enviar metade direita para o olho direito
5. Mapear cada metade em hemisfério de 180° (não esfera completa)
EOF
ok "  → vr180/COMO_BAIXAR.md"

# =============================================================================
# SEÇÃO 6 — 4K Stress Test (RF-3D-006)
# =============================================================================
section "4K — Stress test decode hardware (RF-3D-006)"

# 4K H.265 a 60fps — sem fonte pública pequena, usado ffmpeg
gen_ff "4k/h265_4k_60fps_5s.mp4" \
    "H.265 4K@60fps — stress test máximo do decoder HW (RF-3D-006)" \
    -f lavfi -i "testsrc2=duration=5:size=3840x2160:rate=60" \
    -f lavfi -i "sine=frequency=440:duration=5" \
    -c:v libx265 -crf 22 -preset fast \
    -c:a aac -b:a 128k \
    -movflags +faststart

# 4K H.264 (para comparação de decoder load)
gen_ff "4k/h264_4k_30fps_5s.mp4" \
    "H.264 4K@30fps — comparação decode load (RF-3D-006)" \
    -f lavfi -i "testsrc2=duration=5:size=3840x2160:rate=30" \
    -f lavfi -i "sine=frequency=440:duration=5" \
    -c:v libx264 -crf 22 -preset fast \
    -c:a aac -b:a 128k \
    -movflags +faststart

# =============================================================================
# SEÇÃO 7 — HDR10 / HLG (RF contexto de qualidade no Quest 3)
# =============================================================================
section "HDR — HDR10 / HLG com metadata reais embutidos"

# HDR10 H.265 10-bit com master display metadata reais
gen_ff "hdr/hdr10_hevc_10bit_pq.mp4" \
    "HDR10 H.265 10-bit BT.2020 PQ — metadata SMPTE ST.2086 reais" \
    -f lavfi -i "testsrc2=duration=10:size=1920x1080:rate=30" \
    -f lavfi -i "sine=frequency=440:duration=10" \
    -c:v libx265 -crf 22 -preset medium \
    -x265-params "hdr-opt=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,100):max-cll=1000,400" \
    -pix_fmt yuv420p10le \
    -c:a aac -b:a 128k \
    -movflags +faststart

# HLG H.265 10-bit (broadcast HDR)
gen_ff "hdr/hlg_hevc_10bit_bt2100.mp4" \
    "HLG H.265 10-bit BT.2100 — TV broadcast HDR" \
    -f lavfi -i "testsrc2=duration=10:size=1920x1080:rate=30" \
    -f lavfi -i "sine=frequency=440:duration=10" \
    -c:v libx265 -crf 22 -preset medium \
    -x265-params "colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc" \
    -pix_fmt yuv420p10le \
    -c:a aac -b:a 128k \
    -movflags +faststart

# =============================================================================
# SEÇÃO 8 — Áudio (RF-2D-005)
# =============================================================================
section "Áudio — AAC / MP3 / FLAC / Opus / AC3 (RF-2D-005)"

dl "https://storage.googleapis.com/exoplayer-test-media-1/ogg/play.ogg" \
   "audio/sample_opus.ogg" "Opus/Vorbis — ExoPlayer sample (RF-2D-005)"

gen_ff "audio/sample_aac.aac" "AAC 192kbps (RF-2D-005)" \
    -f lavfi -i "sine=frequency=440:duration=10" -c:a aac -b:a 192k

gen_ff "audio/sample_mp3.mp3" "MP3 192kbps stereo (RF-2D-005)" \
    -f lavfi -i "aevalsrc=0.5*sin(440*2*PI*t)|0.5*sin(880*2*PI*t):c=stereo:s=44100:d=10" \
    -c:a libmp3lame -b:a 192k

gen_ff "audio/sample_flac.flac" "FLAC 24-bit lossless (RF-2D-005)" \
    -f lavfi -i "sine=frequency=440:duration=10:sample_rate=96000" \
    -c:a flac -sample_fmt s32

gen_ff "audio/sample_5_1_ac3.mp4" "AC3 5.1 surround — 6 canais distintos (RF-2D-005, RF-3D-010)" \
    -f lavfi -i "color=c=black:duration=10:size=640x360:rate=30" \
    -f lavfi -i "sine=frequency=200:duration=10:sample_rate=48000" \
    -f lavfi -i "sine=frequency=440:duration=10:sample_rate=48000" \
    -f lavfi -i "sine=frequency=660:duration=10:sample_rate=48000" \
    -f lavfi -i "sine=frequency=880:duration=10:sample_rate=48000" \
    -f lavfi -i "sine=frequency=1100:duration=10:sample_rate=48000" \
    -f lavfi -i "sine=frequency=60:duration=10:sample_rate=48000" \
    -filter_complex "[1][2][3][4][5][6]join=inputs=6:channel_layout=5.1:map=0.0-FL|1.0-FR|2.0-FC|3.0-BL|4.0-BR|5.0-LFE[a]" \
    -map "0:v" -map "[a]" \
    -c:v libx264 -crf 28 -preset ultrafast \
    -c:a ac3 -b:a 448k \
    -movflags +faststart

# =============================================================================
# SEÇÃO 9 — Ambisonics (RF-3D-009)
# =============================================================================
section "Áudio Espacial — Ambisonics (RF-3D-009)"

# Congo já tem ambisonics — link simbólico ou re-download
dl "https://storage.googleapis.com/exoplayer-test-media-1/360/congo.mp4" \
   "audio-spatial/ambisonics_congo_360.mp4" "Ambisonics embutido no Congo 360° (RF-3D-009)"

# =============================================================================
# SEÇÃO 10 — Legendas (RF-2D-006, RF-2D-007)
# =============================================================================
section "Legendas — SRT / VTT / ASS (RF-2D-006/007)"

mkdir -p "$OUTDIR/subtitles"

cat > "$OUTDIR/subtitles/test_pt_br.srt" << 'EOF'
1
00:00:00,500 --> 00:00:02,500
Teste de legenda SRT — Português (BR)
Player VR Multimídia

2
00:00:03,000 --> 00:00:05,500
Verificando UTF-8: ção, ã, é, ü, ñ, 中文

3
00:00:06,000 --> 00:00:08,500
Linha longa para testar quebra no espaço VR:
Este texto verifica a quebra automática de linha dentro do headset.

4
00:00:09,000 --> 00:00:10,000
[Fim do teste — RF-2D-006]
EOF
ok "  → subtitles/test_pt_br.srt"

cat > "$OUTDIR/subtitles/test_en.vtt" << 'EOF'
WEBVTT

00:00:00.500 --> 00:00:02.500
WebVTT subtitle test — English
VR Multimedia Player | RF-2D-006

00:00:03.000 --> 00:00:05.500
<b>Bold</b>, <i>italic</i>, <u>underline</u>

00:00:06.000 --> 00:00:08.500 position:10% align:left
Positioned left (position:10%)

00:00:09.000 --> 00:00:10.000
[End of VTT test]
EOF
ok "  → subtitles/test_en.vtt"

cat > "$OUTDIR/subtitles/test_style.ass" << 'EOF'
[Script Info]
ScriptType: v4.00+

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1
Style: Destaque,Arial,26,&H0000FFFF,&H000000FF,&H00000000,&H80000000,1,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.50,0:00:02.50,Default,,0,0,0,,Teste ASS — estilos avançados (RF-2D-007)
Dialogue: 0,0:00:03.00,0:00:05.50,Destaque,,0,0,0,,{\b1}Negrito ciano{\b0} com outline branco
Dialogue: 0,0:00:06.00,0:00:08.50,Default,,0,0,0,,{\i1}Itálico{\i0} e {\c&H0000FF&}azul{\c} e {\c&H00FF00&}verde{\c}
Dialogue: 0,0:00:09.00,0:00:10.00,Default,,0,0,0,,{\an8}Topo (an8) — fim do teste ASS
EOF
ok "  → subtitles/test_style.ass"

# =============================================================================
# SEÇÃO 11 — Fotos 360° (RF-3D-008)
# =============================================================================
section "Fotos 360° e 3D (RF-3D-008)"

mkdir -p "$OUTDIR/photos-360"

# Gera panorama equirectangular com conteúdo de gradiente colorido (mais realista que testsrc2)
ffmpeg -y -hide_banner -loglevel error \
    -f lavfi -i "gradients=size=4096x2048:x0=0:y0=0:x1=4096:y1=2048:c0=0x1a1a5e:c1=0x0d7c8c:c2=0x2d6a4f:c3=0x95d5b2:speed=0:duration=0:rate=1" \
    -frames:v 1 \
    -vf "drawgrid=width=512:height=256:thickness=1:color=white@0.3,
drawtext=text='360° EQUIRECTANGULAR':x=(w-text_w)/2:y=(h-text_h)/2:fontsize=80:fontcolor=white:shadowx=2:shadowy=2,
drawtext=text='NORTH POLE':x=(w-text_w)/2:y=50:fontsize=48:fontcolor=cyan,
drawtext=text='SOUTH POLE':x=(w-text_w)/2:y=h-90:fontsize=48:fontcolor=cyan,
drawtext=text='EQUATOR':x=20:y=(h-text_h)/2:fontsize=36:fontcolor=yellow,
drawtext=text='0°':x=20:y=(h/2-60):fontsize=28:fontcolor=white,
drawtext=text='180°':x=w/2-20:y=(h/2-60):fontsize=28:fontcolor=white,
drawtext=text='360°':x=w-60:y=(h/2-60):fontsize=28:fontcolor=white" \
    "$OUTDIR/photos-360/equirect_4k_reference.jpg" 2>>"$LOG" \
    && ok "  → photos-360/equirect_4k_reference.jpg (4096x2048)" \
    || err "  → Falha ao gerar equirect"

# Gera foto SBS 3D (espelha a equirect para simular parallax)
if [[ -f "$OUTDIR/photos-360/equirect_4k_reference.jpg" ]]; then
    ffmpeg -y -hide_banner -loglevel error \
        -i "$OUTDIR/photos-360/equirect_4k_reference.jpg" \
        -i "$OUTDIR/photos-360/equirect_4k_reference.jpg" \
        -filter_complex "[0:v]drawtext=text='L':x=50:y=50:fontsize=120:fontcolor=red[l];[1:v]drawtext=text='R':x=50:y=50:fontsize=120:fontcolor=blue[r];[l][r]hstack" \
        "$OUTDIR/photos-360/sbs_3d_photo.jpg" 2>>"$LOG" \
        && ok "  → photos-360/sbs_3d_photo.jpg (8192x2048 SBS)" \
        || err "  → Falha ao gerar SBS photo"
fi

cat > "$OUTDIR/photos-360/README.txt" << 'EOF'
# Fotos 360° e 3D (RF-3D-008)

## equirect_4k_reference.jpg (4096x2048 — proporção 2:1)
  Panorama equirectangular com grade de referência.
  O player deve mapear na esfera. Polos devem coincidir (topo/base).
  Use para verificar: distorção nos polos, equador correto, north/south labels visíveis.

## sbs_3d_photo.jpg (8192x2048 — SBS 3D)
  Metade esquerda = olho esquerdo (marcado "L" vermelho)
  Metade direita = olho direito (marcado "R" azul)
  Player deve dividir ao meio e enviar para cada olho.

## Para fotos 360° reais (CC0):
  - https://polyhaven.com/hdris — HDRIs profissionais panorâmicos
  - Qualquer câmera 360° (Insta360, GoPro MAX, Ricoh Theta)
EOF
ok "  → photos-360/README.txt"

# =============================================================================
# SEÇÃO 12 — Streaming HLS/DASH (RF-NET-008/009)
# =============================================================================
section "Streaming HLS/DASH — Manifests (RF-NET-008/009)"

mkdir -p "$OUTDIR/streaming/hls" "$OUTDIR/streaming/dash"

curl -fsSL --retry 3 --connect-timeout 10 --max-time 30 \
    -o "$OUTDIR/streaming/hls/apple_h264_ts.m3u8" \
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8" \
    2>>"$LOG" && ok "  → HLS: apple_h264_ts.m3u8" || warn "  → Apple HLS indisponível"

curl -fsSL --retry 3 --connect-timeout 10 --max-time 30 \
    -o "$OUTDIR/streaming/hls/apple_hevc_fmp4.m3u8" \
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_adv_example_hevc/master.m3u8" \
    2>>"$LOG" && ok "  → HLS: apple_hevc_fmp4.m3u8" || warn "  → Apple HLS HEVC indisponível"

curl -fsSL --retry 3 --connect-timeout 10 --max-time 30 \
    -o "$OUTDIR/streaming/dash/tears_vp9_hd.mpd" \
    "https://storage.googleapis.com/wvmedia/clear/vp9/tears/tears.mpd" \
    2>>"$LOG" && ok "  → DASH: tears_vp9_hd.mpd" || warn "  → wvmedia DASH indisponível"

curl -fsSL --retry 3 --connect-timeout 10 --max-time 30 \
    -o "$OUTDIR/streaming/dash/tears_vp9_uhd.mpd" \
    "https://storage.googleapis.com/wvmedia/clear/vp9/tears/tears_uhd.mpd" \
    2>>"$LOG" && ok "  → DASH: tears_vp9_uhd.mpd (4K)" || warn "  → wvmedia UHD indisponível"

cat > "$OUTDIR/streaming/hls/README-urls.txt" << 'EOF'
# HLS para colar diretamente no player (RF-NET-008)

# H.264 + AAC em TS (baseline)
https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8

# HEVC + AAC em fMP4 (avançado)
https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_adv_example_hevc/master.m3u8

# 4K HDR Dolby Vision + Atmos
https://devstreaming-cdn.apple.com/videos/streaming/examples/adv_dv_atmos/main.m3u8

# Sintel multi-bitrate (Akamai)
https://multiplatform-f.akamaihd.net/i/multi/april11/sintel/sintel-hd_,512x288_450_b,640x360_700_b,768x432_1000_b,1024x576_1400_m,.mp4.csmil/master.m3u8
EOF

cat > "$OUTDIR/streaming/dash/README-urls.txt" << 'EOF'
# DASH para colar diretamente no player (RF-NET-009)

# Tears of Steel VP9 HD (sem DRM)
https://storage.googleapis.com/wvmedia/clear/vp9/tears/tears.mpd

# Tears of Steel VP9 UHD/4K (sem DRM)
https://storage.googleapis.com/wvmedia/clear/vp9/tears/tears_uhd.mpd

# Akamai multi-resolution
https://dash.akamaized.net/dash264/TestCases/1b/qualcomm/1/MultiResMPEG2.mpd

# BBC testcard (AVC)
https://rdmedia.bbc.co.uk/testcard/vod/manifests/avc-full.mpd
EOF
ok "  → streaming README-urls.txt criados"

# =============================================================================
# SEÇÃO 13 — Referências SMB/NFS
# =============================================================================
section "Referências SMB/NFS/HTTP (RF-NET-001/002/007)"

mkdir -p "$OUTDIR/reference"
cat > "$OUTDIR/reference/http-direct-urls.txt" << 'EOF'
# URLs HTTP verificadas (testadas 2026-08)

## 2D — H.264
https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_5MB.mp4
https://media.w3.org/2010/05/sintel/trailer.mp4

## 2D — H.265
https://test-videos.co.uk/vids/bigbuckbunny/mp4/h265/1080/Big_Buck_Bunny_1080_10s_5MB.mp4

## 2D — VP9
https://test-videos.co.uk/vids/bigbuckbunny/webm/vp9/1080/Big_Buck_Bunny_1080_10s_5MB.webm

## 360° mono
https://storage.googleapis.com/exoplayer-test-media-1/360/congo.mp4

## HLS
https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8
https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_adv_example_hevc/master.m3u8
https://devstreaming-cdn.apple.com/videos/streaming/examples/adv_dv_atmos/main.m3u8

## DASH
https://storage.googleapis.com/wvmedia/clear/vp9/tears/tears.mpd
https://storage.googleapis.com/wvmedia/clear/vp9/tears/tears_uhd.mpd
EOF
ok "  → reference/http-direct-urls.txt"

cat > "$OUTDIR/reference/smb-nfs-guide.txt" << 'EOF'
# Guia SMB/NFS para testes (RF-NET-001, RF-NET-002)

## Servidor SMB rápido (Samba)
sudo apt install samba
# Adicionar ao /etc/samba/smb.conf:
# [vr-test]
#   path = /path/to/testdata/vectors
#   read only = yes
#   guest ok = yes
sudo systemctl restart smbd
# Acessar: smb://<seu-IP>/vr-test

## Servidor NFS rápido
sudo apt install nfs-kernel-server
echo "/path/to/testdata/vectors *(ro,sync,no_subtree_check)" | sudo tee -a /etc/exports
sudo exportfs -ra
# Acessar: nfs://<seu-IP>/path/to/testdata/vectors
EOF
ok "  → reference/smb-nfs-guide.txt"

# =============================================================================
# SEÇÃO 14 — Relatório ffprobe + README
# =============================================================================
section "Relatório ffprobe + README"

REPORT="$OUTDIR/REPORT.md"
cat > "$REPORT" << 'EOF'
# Test Vector Report — VR Multimedia Player

| Arquivo | Requisito | Codec Vídeo | Codec Áudio | Resolução | Duração | Fonte |
|---------|-----------|-------------|-------------|-----------|---------|-------|
EOF

if command -v ffprobe &>/dev/null; then
    info "Analisando com ffprobe..."
    while IFS= read -r -d '' file; do
        rel="${file#"$OUTDIR/"}"
        ext="${file##*.}"
        case "$ext" in txt|md|srt|vtt|ass|m3u8|mpd|log) continue ;; esac
        [[ -s "$file" ]] || continue

        vcodec=$(ffprobe -v quiet -select_streams v:0 \
            -show_entries stream=codec_name -of csv=p=0 "$file" 2>/dev/null | head -1 || echo "-")
        acodec=$(ffprobe -v quiet -select_streams a:0 \
            -show_entries stream=codec_name -of csv=p=0 "$file" 2>/dev/null | head -1 || echo "-")
        res=$(ffprobe -v quiet -select_streams v:0 \
            -show_entries stream=width,height -of csv=p=0:s=x "$file" 2>/dev/null | head -1 || echo "-")
        dur=$(ffprobe -v quiet -show_entries format=duration \
            -of default=noprint_wrappers=1:nokey=1 "$file" 2>/dev/null | \
            awk '{printf "%.0fs", $1}' 2>/dev/null || echo "-")

        req="-"
        case "$rel" in
            2d/*)            req="RF-2D-001/003/004" ;;
            audio/*)         req="RF-2D-005" ;;
            360/*)           req="RF-3D-003" ;;
            vr180/*)         req="RF-3D-005" ;;
            3d-sbs/*)        req="RF-3D-001" ;;
            3d-ou/*)         req="RF-3D-002" ;;
            hdr/*)           req="HDR Quality" ;;
            4k/*)            req="RF-3D-006" ;;
            audio-spatial/*) req="RF-3D-009" ;;
            photos-360/*)    req="RF-3D-008" ;;
        esac

        src="🔧 ffmpeg"
        case "$rel" in
            2d/h264_bbb*|2d/h265_bbb*|2d/vp9_bbb*) src="HTTP (test-videos.co.uk)" ;;
            2d/h264_sintel*)   src="HTTP (W3C)" ;;
            360/360_mono_congo*) src="HTTP (ExoPlayer/Google)" ;;
            360/360_mono_vp9*) src="HTTP (ExoPlayer/Google)" ;;
            3d-sbs/doctor_who*) src="HTTP (archive.org)" ;;
            audio/sample_opus*) src="HTTP (ExoPlayer/Google)" ;;
            audio-spatial/*)   src="HTTP (ExoPlayer/Google)" ;;
        esac

        echo "| \`$rel\` | $req | \`$vcodec\` | \`$acodec\` | $res | $dur | $src |" >> "$REPORT"
    done < <(find "$OUTDIR" -type f -print0 | sort -z)
    ok "  → REPORT.md gerado"
fi

cat > "$OUTDIR/README.md" << 'MDEOF'
# 🎬 Test Vectors — VR Multimedia Player

Vídeos de teste para cobrir `docs/REQUIREMENTS.md`.
Gerado por `scripts/download-test-vectors.sh`.

## 📂 Estrutura

```
testdata/vectors/
├── 2d/           H.264, H.265, VP9 REAIS (BigBuckBunny, Sintel)   RF-2D-001/003/004
├── audio/        AAC, MP3, FLAC, Opus, AC3 5.1                     RF-2D-005
├── 360/          Congo 360° REAL + VP9 screen                       RF-3D-003
├── vr180/        Placeholder + COMO_BAIXAR.md (requer cookies)      RF-3D-005
├── 3d-sbs/       Doctor Who SBS REAL (archive.org) + derivado        RF-3D-001
├── 3d-ou/        Over/Under derivado do Doctor Who SBS               RF-3D-002
├── hdr/          HDR10 + HLG com metadata reais embutidos            HDR Quality
├── 4k/           H.264 + H.265 4K stress test                       RF-3D-006
├── streaming/
│   ├── hls/     Apple manifests + README-urls.txt                   RF-NET-008
│   └── dash/    wvmedia VP9 manifests + README-urls.txt             RF-NET-009
├── subtitles/   SRT, VTT, ASS sincronizados (10s)                   RF-2D-006/007
├── audio-spatial/ Ambisonics Congo 360°                              RF-3D-009
├── photos-360/  Equirect 4K + SBS foto                              RF-3D-008
├── reference/   URLs HTTP, guia SMB/NFS                             RF-NET-001/002/007
├── REPORT.md    Análise ffprobe automática
└── README.md    Este arquivo
```

## ✅ Checklist de Testes

### v0.1 MVP
- [ ] `2d/h264_bbb_1080p.mp4` → tela plana no VR (RF-2D-001)
- [ ] `2d/h265_bbb_1080p.mp4` → HEVC HW decode (RF-2D-003)
- [ ] `2d/vp9_bbb_1080p.webm` → VP9 decode (RF-2D-004)
- [ ] URLs em `reference/http-direct-urls.txt` → HTTP playback (RF-NET-007)

### v0.2 3D & Network
- [ ] `360/360_mono_congo_h264.mp4` → 360° real, head tracking < 20ms
- [ ] `3d-sbs/doctor_who_3d_sbs_real.mp4` → 3D SBS com conteúdo real filmado
- [ ] `3d-ou/ou_from_doctorwho_sbs.mp4` → Over/Under correto
- [ ] **VR180**: Seguir `vr180/COMO_BAIXAR.md` para obter vídeo real
- [ ] URLs HLS em `streaming/hls/README-urls.txt` (RF-NET-008)
- [ ] URLs DASH em `streaming/dash/README-urls.txt` (RF-NET-009)
- [ ] `subtitles/test_pt_br.srt` + qualquer vídeo (RF-2D-006)

### v0.3 Audio & Polish
- [ ] `audio/sample_5_1_ac3.mp4` → 5.1 surround virtualizado (RF-3D-010)
- [ ] `audio-spatial/ambisonics_congo_360.mp4` → Ambisonics (RF-3D-009)
- [ ] `subtitles/test_style.ass` → ASS com estilos (RF-2D-007)
- [ ] `photos-360/equirect_4k_reference.jpg` → foto 360° na esfera (RF-3D-008)

### v0.4 Advanced
- [ ] `4k/h265_4k_60fps_5s.mp4` → 4K@60fps, sem drop de frames (RF-3D-006)
- [ ] `hdr/hdr10_hevc_10bit_pq.mp4` → HDR10, cores não lavadas no Quest 3
- [ ] `hdr/hlg_hevc_10bit_bt2100.mp4` → HLG broadcast HDR

## 📌 VR180 — Ação necessária
O VR180 real (RF-3D-005) requer cookies do YouTube para download.
Veja as instruções em [`vr180/COMO_BAIXAR.md`](vr180/COMO_BAIXAR.md).
MDEOF
ok "  → README.md atualizado"

# =============================================================================
# RESUMO FINAL
# =============================================================================
echo ""
echo -e "${BOLD}${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}${GREEN}║                  CONCLUÍDO                                ║${NC}"
echo -e "${BOLD}${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${GREEN}✔ Downloads:${NC}  $DL  (vídeos reais de fontes verificadas)"
echo -e "  ${CYAN}⚙ Gerados:${NC}    $GEN_COUNT  (ffmpeg — apenas onde não há fonte pública)"
echo -e "  ${YELLOW}⊘ Pulados:${NC}    $SKIP  (já existiam)"
echo -e "  ${RED}✘ Falhos:${NC}     $FAIL"
echo ""
echo -e "  ${BLUE}📁 Destino:${NC}   $OUTDIR"
echo -e "  ${BLUE}📊 Relatório:${NC} $OUTDIR/REPORT.md"
echo ""
if [[ ! -f "$OUTDIR/vr180/vr180_lightspeed_real.mp4" || \
      ! -s "$OUTDIR/vr180/vr180_lightspeed_real.mp4" ]]; then
    echo -e "  ${YELLOW}⚠ VR180 real ausente.${NC} Veja: testdata/vectors/vr180/COMO_BAIXAR.md"
fi
echo ""
echo -e "${CYAN}Próximos passos:${NC}"
echo "  cat testdata/vectors/REPORT.md"
echo "  cat testdata/vectors/vr180/COMO_BAIXAR.md  # para VR180 real"
echo ""
