#!/usr/bin/env bash
# =============================================================================
# push-to-quest.sh — Envia test vectors para o Meta Quest 3 via ADB
# =============================================================================
#
# Uso:
#   ./scripts/push-to-quest.sh                      # envia tudo
#   ./scripts/push-to-quest.sh --wifi 192.168.1.X   # conecta por WiFi e envia
#   ./scripts/push-to-quest.sh --only 2d hdr 360    # envia apenas categorias
#   ./scripts/push-to-quest.sh --only youtube-downloads  # só os downloads do YT
#   ./scripts/push-to-quest.sh --list               # lista o que está no Quest
#   ./scripts/push-to-quest.sh --clean              # remove pasta do Quest
#
# Pré-requisitos:
#   - Quest 3 em Developer Mode (Settings → Developer → USB Debugging: ON)
#   - Conectado via USB *ou* WiFi (--wifi <IP>)
#   - adb instalado: sudo apt install adb
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VECTORS_DIR="$REPO_ROOT/testdata/vectors"
DOWNLOAD_DIR="$REPO_ROOT/testdata/download"  # vídeos reais do YouTube

# Destino no Quest — aparece no File Manager e apps de mídia
QUEST_BASE="/sdcard/Movies/VRTestVectors"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

info()    { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()      { echo -e "${GREEN}[ OK ]${NC} $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()     { echo -e "${RED}[ERRO]${NC} $*"; }
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
            echo "Uso: $0 [--wifi IP] [--only cat1 cat2...] [--list] [--clean]"
            echo "Categorias: 2d 360 vr180 3d-sbs 3d-ou hdr 4k audio audio-spatial subtitles photos-360 streaming reference"
            exit 0 ;;
        *) warn "Argumento desconhecido: $1"; shift ;;
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
# Verificar ADB
# ---------------------------------------------------------------------------
section "Verificando ADB e Quest"

if ! command -v adb &>/dev/null; then
    err "adb não encontrado. Instale com: sudo apt install adb"
    exit 1
fi
ok "adb encontrado: $(adb version 2>/dev/null | head -1)"

# Conectar via WiFi se solicitado
if [[ -n "$WIFI_IP" ]]; then
    info "Conectando via WiFi: $WIFI_IP:5555"
    # Primeiro habilita ADB TCP no dispositivo USB conectado (se houver)
    adb tcpip 5555 2>/dev/null || true
    sleep 2
    if adb connect "$WIFI_IP:5555" 2>&1 | grep -q "connected"; then
        ok "Conectado via WiFi: $WIFI_IP"
    else
        err "Falha ao conectar em $WIFI_IP:5555"
        err "Certifique-se que:"
        err "  1. Quest e PC estão na mesma rede WiFi"
        err "  2. USB Debugging está habilitado no Quest"
        err "  3. Se nunca usou WiFi ADB: conecte via USB primeiro, rode o script,"
        err "     depois desconecte o USB e reconecte com --wifi <IP>"
        exit 1
    fi
fi

# Verificar se há dispositivo conectado
DEVICES=$(adb devices 2>/dev/null | grep -v "^List" | grep -v "^$" | grep "device$")
if [[ -z "$DEVICES" ]]; then
    err "Nenhum dispositivo ADB conectado."
    echo ""
    echo -e "${YELLOW}Para conectar o Quest 3:${NC}"
    echo "  1. Settings → Developer → USB Debugging: ON"
    echo "  2. Conecte o cabo USB-C"
    echo "  3. Aceite a permissão de debug que aparece no headset"
    echo "  4. Execute novamente este script"
    echo ""
    echo -e "${YELLOW}Para conectar por WiFi (sem cabo):${NC}"
    echo "  1. Conecte via USB uma vez e rode: $0 --wifi <IP-do-Quest>"
    echo "  2. IP do Quest: Settings → Wi-Fi → toque na rede conectada"
    exit 1
fi

DEVICE_COUNT=$(echo "$DEVICES" | wc -l)
info "$DEVICE_COUNT dispositivo(s) encontrado(s):"
echo "$DEVICES" | while IFS= read -r line; do
    SERIAL=$(echo "$line" | awk '{print $1}')
    MODEL=$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "?")
    MANUFACTURER=$(adb -s "$SERIAL" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r' || echo "?")
    ok "  $SERIAL → $MANUFACTURER $MODEL"
done

# Usa o primeiro dispositivo (ou o único)
SERIAL=$(echo "$DEVICES" | head -1 | awk '{print $1}')
ADB="adb -s $SERIAL"

# Verifica espaço disponível
AVAIL_KB=$($ADB shell df /sdcard 2>/dev/null | awk 'NR==2 {print $4}' | tr -d '\r' || echo "0")
AVAIL_MB=$((AVAIL_KB / 1024))
AVAIL_GB=$((AVAIL_MB / 1024))
info "Espaço disponível no Quest: ~${AVAIL_GB}GB (${AVAIL_MB}MB)"

# Calcula tamanho total a enviar
LOCAL_SIZE=$(du -sm "$VECTORS_DIR" 2>/dev/null | cut -f1 || echo "?")
info "Tamanho total dos vectors locais: ~${LOCAL_SIZE}MB"

if [[ "$AVAIL_MB" -lt 500 ]]; then
    warn "Pouco espaço disponível no Quest (<500MB). Considere usar --only para enviar categorias específicas."
fi

# ---------------------------------------------------------------------------
# --list: mostrar o que está no Quest
# ---------------------------------------------------------------------------
if $DO_LIST; then
    section "Arquivos em $QUEST_BASE"
    $ADB shell ls -lhR "$QUEST_BASE" 2>/dev/null || warn "Pasta $QUEST_BASE não existe ainda no Quest"
    exit 0
fi

# ---------------------------------------------------------------------------
# --clean: remover pasta do Quest
# ---------------------------------------------------------------------------
if $DO_CLEAN; then
    section "Removendo $QUEST_BASE do Quest"
    warn "ATENÇÃO: Isso apagará TODOS os test vectors do Quest!"
    read -rp "Confirmar remoção? [s/N] " confirm
    if [[ "${confirm,,}" == "s" ]]; then
        $ADB shell rm -rf "$QUEST_BASE" 2>/dev/null && ok "Removido: $QUEST_BASE" || err "Falha ao remover"
    else
        info "Cancelado."
    fi
    exit 0
fi

# ---------------------------------------------------------------------------
# Criar estrutura de diretórios no Quest
# ---------------------------------------------------------------------------
section "Criando estrutura no Quest"

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

# Filtra categorias se --only foi especificado
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
    info "Enviando apenas: ${CATEGORIES[*]}"
fi

# Cria diretórios no Quest
for cat in "${CATEGORIES[@]}"; do
    $ADB shell mkdir -p "$QUEST_BASE/$cat" 2>/dev/null
done
ok "Estrutura criada em $QUEST_BASE"

# ---------------------------------------------------------------------------
# Função de push com verificação de integridade
# ---------------------------------------------------------------------------
push_file() {
    local src="$1"
    local dest_dir="$2"
    local filename
    filename=$(basename "$src")
    local dest="$dest_dir/$filename"
    local src_size
    src_size=$(stat -c%s "$src" 2>/dev/null || echo 0)

    # Verifica se já existe com tamanho correto
    local quest_size
    quest_size=$($ADB shell stat -c%s "$dest" 2>/dev/null | tr -d '\r\n' || echo "0")

    if [[ "$quest_size" == "$src_size" && "$src_size" -gt 0 ]]; then
        local sz_human; sz_human=$(du -sh "$src" | cut -f1)
        warn "  JÁ EXISTE (${sz_human}): $filename"
        ((SKIP_COUNT++)) || true
        return 0
    fi

    local sz_human; sz_human=$(du -sh "$src" | cut -f1)
    info "  Enviando (${sz_human}): $filename"

    if $ADB push "$src" "$dest" 2>&1 | tail -1 | grep -qE "pushed|file pushed"; then
        ok "    → OK: $filename"
        ((PUSH_COUNT++)) || true
        TOTAL_BYTES=$((TOTAL_BYTES + src_size))
    else
        # tenta de qualquer forma e verifica
        if $ADB push "$src" "$dest" 2>/dev/null; then
            ok "    → OK: $filename"
            ((PUSH_COUNT++)) || true
            TOTAL_BYTES=$((TOTAL_BYTES + src_size))
        else
            err "    → FALHA: $filename"
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

    section "Enviando: $cat ($file_count arquivo(s))"

    while IFS= read -r -d '' file; do
        local ext="${file##*.}"
        # Pula logs
        [[ "$ext" == "log" ]] && continue
        push_file "$file" "$dest_dir"
    done < <(find "$src_dir" -maxdepth 1 -type f -print0 | sort -z)
}

# ---------------------------------------------------------------------------
# Push por categoria
# ---------------------------------------------------------------------------
for cat in "${CATEGORIES[@]}"; do
    push_category "$cat"
done

# ---------------------------------------------------------------------------
# Verificar no Quest o que foi enviado
# ---------------------------------------------------------------------------
section "Verificando no Quest"

QUEST_COUNT=$($ADB shell find "$QUEST_BASE" -type f 2>/dev/null | wc -l | tr -d '\r' || echo "?")
QUEST_SIZE=$($ADB shell du -sh "$QUEST_BASE" 2>/dev/null | cut -f1 | tr -d '\r' || echo "?")

ok "Arquivos no Quest: $QUEST_COUNT"
ok "Tamanho total no Quest: $QUEST_SIZE"

# Scaneia a mídia para aparecer nos apps do Quest
info "Escaneando mídia no Quest (para aparecer no File Manager)..."
$ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file://$QUEST_BASE" 2>/dev/null || true
# Scan recursivo mais efetivo
$ADB shell find "$QUEST_BASE" -name "*.mp4" -o -name "*.webm" -o -name "*.mkv" \
    2>/dev/null | while IFS= read -r f; do
    $ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
        -d "file://$f" 2>/dev/null || true
done
ok "Media scan concluído"

# ---------------------------------------------------------------------------
# Resumo
# ---------------------------------------------------------------------------
TOTAL_MB=$((TOTAL_BYTES / 1024 / 1024))

echo ""
echo -e "${BOLD}${GREEN}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}${GREEN}║                    PUSH CONCLUÍDO                           ║${NC}"
echo -e "${BOLD}${GREEN}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${GREEN}✔ Enviados:${NC}     $PUSH_COUNT arquivos (${TOTAL_MB}MB)"
echo -e "  ${YELLOW}⊘ Pulados:${NC}      $SKIP_COUNT (já existiam)"
echo -e "  ${RED}✘ Falhos:${NC}       $FAIL_COUNT"
echo ""
echo -e "  ${BLUE}📁 Quest:${NC}       $QUEST_BASE"
echo -e "  ${BLUE}📊 Arquivos:${NC}    $QUEST_COUNT total | ${QUEST_SIZE} no headset"
echo ""
echo -e "${CYAN}No Quest 3:${NC}"
echo "  1. Files → VRTestVectors/"
echo "     → para navegar e abrir arquivos diretamente"
echo ""
echo "  2. No player em desenvolvimento:"
echo "     → Browse → /sdcard/Movies/VRTestVectors/"
echo ""
if [[ -f "$VECTORS_DIR/vr180/vr180_placeholder_sbs.mp4" ]] && \
   ! [[ -f "$VECTORS_DIR/vr180/vr180_lightspeed_real.mp4" ]]; then
    echo -e "  ${YELLOW}⚠ VR180 real ausente.${NC} Veja: testdata/vectors/vr180/COMO_BAIXAR.md"
    echo ""
fi
echo -e "${CYAN}Para conectar por WiFi (sem cabo):${NC}"
echo "  1. IP do Quest: Settings → Wi-Fi → nome da rede → IP address"
echo "  2. Rode: $0 --wifi <IP-DO-QUEST>"
echo ""
# (esta linha não é executada — o bloco acima é o script completo)
