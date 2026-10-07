#!/bin/bash
# ==============================================================================
# capture-screen.sh — Captura instantânea do framebuffer Vulkan do tucaVR
#
# Envia sinal via ADB para o aplicativo, faz o dump direto do swapchain OpenXR
# (olho esquerdo e direito), transfere para o PC em formato PNG e abre na tela.
# ==============================================================================

set -e

CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT_DIR/captures"
mkdir -p "$OUT_DIR"

echo -e "${CYAN}==================================================================${NC}"
echo -e "${CYAN}📸 tucaVR — Captura Instantânea de Frame (Vulkan/GLES)${NC}"
echo -e "${CYAN}==================================================================${NC}"

# 1. Verificar adb
if ! command -v adb >/dev/null 2>&1; then
    echo -e "${RED}❌ ERRO: 'adb' não encontrado no PATH.${NC}"
    exit 1
fi

DEVICE_STATUS=$(adb get-state 2>/dev/null || echo "offline")
if [ "$DEVICE_STATUS" != "device" ]; then
    echo -e "${RED}❌ ERRO: Dispositivo não detectado. Conecte o Quest 3 via USB com depuração ativada.${NC}"
    exit 1
fi

# 2. Acordar o dispositivo se estiver suspenso
WAKEFULNESS=$(adb shell dumpsys power 2>/dev/null | grep -i "mWakefulness=" | head -n1 || true)
if [[ "$WAKEFULNESS" =~ "Asleep" ]]; then
    echo -e "${YELLOW}⚡ Acordando o headset suspenso...${NC}"
    adb shell input keyevent 26 >/dev/null 2>&1 || true
    sleep 1
fi

# 3. Limpar arquivos antigos no dispositivo
DEVICE_PPM="/sdcard/vr-frame-capture.ppm"
adb shell rm -f "$DEVICE_PPM" "${DEVICE_PPM}.left.ppm" "${DEVICE_PPM}.right.ppm" 2>/dev/null || true

# 4. Enviar broadcast para disparar a captura nativa
echo -e "Enviando comando de captura para o tucaVR..."
adb shell am broadcast -a com.tucavr.debug.CAPTURE_FRAME --es capture_path "$DEVICE_PPM" >/dev/null 2>&1 || true

# 5. Aguardar até 3 segundos pelo arquivo gerado
FOUND=0
for i in {1..15}; do
    if adb shell test -f "${DEVICE_PPM}.left.ppm" 2>/dev/null; then
        FOUND=1
        break
    fi
    sleep 0.2
done

if [ "$FOUND" -eq 0 ]; then
    echo -e "${YELLOW}⚠️ O arquivo '${DEVICE_PPM}.left.ppm' não foi gerado imediatamente via broadcast.${NC}"
    echo -e "Tentando via intent direto..."
    adb shell am start -n com.tucavr/.VRActivity -e capture_path "$DEVICE_PPM" >/dev/null 2>&1 || true
    for i in {1..15}; do
        if adb shell test -f "${DEVICE_PPM}.left.ppm" 2>/dev/null; then
            FOUND=1
            break
        fi
        sleep 0.2
    done
fi

if [ "$FOUND" -eq 0 ]; then
    echo -e "${RED}❌ ERRO: Não foi possível capturar o frame.${NC}"
    echo -e "Certifique-se de que o app tucaVR está em execução ativa no Meta Quest."
    exit 1
fi

# 6. Baixar arquivos PPM e converter para PNG
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOCAL_LEFT_PPM="$OUT_DIR/${TIMESTAMP}_left.ppm"
LOCAL_RIGHT_PPM="$OUT_DIR/${TIMESTAMP}_right.ppm"
LOCAL_LEFT_PNG="$OUT_DIR/${TIMESTAMP}_left.png"
LOCAL_RIGHT_PNG="$OUT_DIR/${TIMESTAMP}_right.png"

adb pull "${DEVICE_PPM}.left.ppm" "$LOCAL_LEFT_PPM" >/dev/null 2>&1
adb shell rm -f "${DEVICE_PPM}.left.ppm" 2>/dev/null || true

if adb shell test -f "${DEVICE_PPM}.right.ppm" 2>/dev/null; then
    adb pull "${DEVICE_PPM}.right.ppm" "$LOCAL_RIGHT_PPM" >/dev/null 2>&1
    adb shell rm -f "${DEVICE_PPM}.right.ppm" 2>/dev/null || true
fi

# Conversão PPM -> PNG
if command -v ffmpeg >/dev/null 2>&1; then
    ffmpeg -y -hide_banner -loglevel error -i "$LOCAL_LEFT_PPM" "$LOCAL_LEFT_PNG"
    rm -f "$LOCAL_LEFT_PPM"
    if [ -f "$LOCAL_RIGHT_PPM" ]; then
        ffmpeg -y -hide_banner -loglevel error -i "$LOCAL_RIGHT_PPM" "$LOCAL_RIGHT_PNG"
        rm -f "$LOCAL_RIGHT_PPM"
    fi
else
    # Fallback para Python com Pillow caso ffmpeg não esteja instalado
    python3 -c "
from PIL import Image
Image.open('$LOCAL_LEFT_PPM').save('$LOCAL_LEFT_PNG')
" 2>/dev/null || true
    rm -f "$LOCAL_LEFT_PPM"
fi

if [ -f "$LOCAL_LEFT_PNG" ]; then
    echo -e "${GREEN}✅ Captura realizada com sucesso!${NC}"
    echo -e "Imagem salva em: ${CYAN}$LOCAL_LEFT_PNG${NC}"
    if [ -f "$LOCAL_RIGHT_PNG" ]; then
        echo -e "Olho direito em:  ${CYAN}$LOCAL_RIGHT_PNG${NC}"
    fi

    # 7. Abrir a imagem automaticamente no visualizador do sistema (se houver interface gráfica)
    if [ -n "$DISPLAY" ] || [ -n "$WAYLAND_DISPLAY" ]; then
        if command -v xdg-open >/dev/null 2>&1; then
            xdg-open "$LOCAL_LEFT_PNG" >/dev/null 2>&1 &
        fi
    fi
else
    echo -e "${RED}❌ Falha ao converter imagem capturada para PNG.${NC}"
    exit 1
fi
echo -e "=================================================================="
