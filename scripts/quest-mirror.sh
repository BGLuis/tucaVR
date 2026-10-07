#!/bin/bash
# ==============================================================================
# quest-mirror.sh — Espelhamento em tempo real do Meta Quest 3 no PC via scrcpy
#
# Permite assistir ao vivo na tela do computador o que está sendo renderizado
# no headset pelo tucaVR, com latência ultrabaixa (~20ms) e 60 a 90 FPS.
# ==============================================================================

set -e

# Cores para o terminal
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${CYAN}==================================================================${NC}"
echo -e "${CYAN}🥽 tucaVR — Espelhamento do Meta Quest 3 no Computador${NC}"
echo -e "${CYAN}==================================================================${NC}"

# 1. Verificar dependência: scrcpy
if ! command -v scrcpy >/dev/null 2>&1; then
    echo -e "${RED}❌ ERRO: O utilitário 'scrcpy' não está instalado neste computador.${NC}"
    echo -e ""
    echo -e "${YELLOW}O scrcpy é a ferramenta padrão recomendada para espelhar o Quest 3 com 90 FPS.${NC}"
    echo -e "Para instalar no seu sistema Linux:"
    echo -e "  • ${GREEN}Arch Linux / Manjaro:${NC}   sudo pacman -S scrcpy"
    echo -e "  • ${GREEN}Ubuntu / Debian:${NC}        sudo apt install scrcpy"
    echo -e "  • ${GREEN}Fedora:${NC}                 sudo dnf install scrcpy"
    echo -e ""
    echo -e "Caso prefira transmitir sem instalar nada, acesse o Meta Casting oficial no navegador:"
    echo -e "  👉 ${CYAN}https://www.oculus.com/casting${NC}"
    echo -e "=================================================================="
    exit 1
fi

# 2. Verificar dependência: adb
if ! command -v adb >/dev/null 2>&1; then
    echo -e "${RED}❌ ERRO: 'adb' não encontrado no PATH.${NC}"
    exit 1
fi

# 3. Verificar dispositivo conectado
DEVICE_STATUS=$(adb get-state 2>/dev/null || echo "offline")
if [ "$DEVICE_STATUS" != "device" ]; then
    echo -e "${YELLOW}⚠️ Nenhum dispositivo detectado em modo 'device'.${NC}"
    echo -e "Verificando lista de dispositivos ADB:"
    adb devices
    echo -e ""
    echo -e "${YELLOW}Por favor, certifique-se de que:${NC}"
    echo -e "  1. O Meta Quest 3 está conectado ao PC via cabo USB."
    echo -e "  2. A Depuração USB está autorizada no headset (caixa de diálogo no óculos)."
    exit 1
fi

DEVICE_MODEL=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo -e "Dispositivo conectado: ${GREEN}${DEVICE_MODEL:-Meta Quest}${NC}"

# 4. Parâmetros padrão de recorte (Crop) para Meta Quest 3
# O display nativo do Quest renderiza os dois olhos lado a lado.
# O recorte abaixo extrai o olho direito em proporção limpa e centralizada.
CROP_RIGHT_EYE="1600:1600:400:300"
CROP_LEFT_EYE="1600:1600:0:300"
SELECTED_CROP="$CROP_RIGHT_EYE"
MAX_FPS=90
BITRATE="25M"
WINDOW_TITLE="tucaVR — Meta Quest 3 Display Mirror"

# Processar argumentos opcionais
while [[ $# -gt 0 ]]; do
    case "$1" in
        --left-eye)
            SELECTED_CROP="$CROP_LEFT_EYE"
            shift
            ;;
        --right-eye)
            SELECTED_CROP="$CROP_RIGHT_EYE"
            shift
            ;;
        --full|--both-eyes)
            SELECTED_CROP=""
            shift
            ;;
        --fps)
            MAX_FPS="$2"
            shift 2
            ;;
        --bitrate)
            BITRATE="$2"
            shift 2
            ;;
        --help|-h)
            echo "Uso: $0 [OPÇÕES]"
            echo ""
            echo "Opções:"
            echo "  --right-eye     (Padrão) Recorta e centraliza o olho direito em alta nitidez."
            echo "  --left-eye      Recorta e centraliza o olho esquerdo."
            echo "  --full          Exibe a imagem estéreo completa (ambos os olhos)."
            echo "  --fps <n>       Define o limite de FPS (padrão: 90)."
            echo "  --bitrate <n>   Define a taxa de bits de streaming (padrão: 25M)."
            exit 0
            ;;
        *)
            echo "Opção desconhecida: $1"
            echo "Use '$0 --help' para ver as opções disponíveis."
            exit 1
            ;;
    esac
done

SCRCPY_ARGS=(
    --max-size 1600
    --max-fps "$MAX_FPS"
    -b "$BITRATE"
    --stay-awake
    --window-title "$WINDOW_TITLE"
)

if [ -n "$SELECTED_CROP" ]; then
    SCRCPY_ARGS+=(--crop "$SELECTED_CROP")
fi

echo -e "Iniciando espelhamento via scrcpy (FPS: ${MAX_FPS}, Bitrate: ${BITRATE})..."
echo -e "Dica: Para fechar o espelhamento, feche a janela ou pressione Ctrl+C no terminal."
echo -e "------------------------------------------------------------------"

exec scrcpy "${SCRCPY_ARGS[@]}"
