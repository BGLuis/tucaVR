#!/bin/bash
# Soak test automatizado via adb — cobre 2 itens da Definition of Done da
# Fase 0.1 (docs/phases/PHASE-0.1-MVP.md) que hoje só têm como ser validados
# num Quest 3 físico conectado por USB:
#   - "Nenhum crash em sessão de 30 minutos"
#   - "Memória < 2.5GB durante reprodução de vídeo 4K"
#
# Uso:
#   ./scripts/soak-test.sh --video-path /sdcard/Movies/teste-4k.mp4
#   ./scripts/soak-test.sh --duration-min 5 --interval-sec 5   # smoke test rápido
#
# O headset precisa estar com Depuração USB autorizada e visível em
# `adb devices`. Se --video-path for passado, o app recebe o extra
# EXTRA_AUTO_PLAY_PATH (ver VRActivity.kt) e começa a tocar sozinho ~3s
# depois de abrir — sem isso o teste só mede o app ocioso na tela void/home,
# o que não corresponde ao cenário "reprodução de vídeo 4K" da DoD.
set -euo pipefail

PACKAGE="com.vrplayer"
ACTIVITY=".VRActivity"
DURATION_MIN=30
INTERVAL_SEC=15
VIDEO_PATH=""
APK=""
SKIP_INSTALL=0
MEMORY_LIMIT_KB=$((2621440)) # 2.5GB em KB, limite da DoD
SERIAL=""

usage() {
    cat <<EOF
Uso: $0 [opcoes]
  --duration-min N     Duracao do teste em minutos (padrao: 30)
  --interval-sec N     Intervalo entre amostras em segundos (padrao: 15)
  --video-path PATH    Caminho do video no dispositivo para auto-play (ex: /sdcard/Movies/x.mp4)
  --apk PATH           Instala este APK antes do teste (ex: app/build/outputs/apk/debug/app-debug.apk)
  --serial SERIAL      Serial do dispositivo adb (equivalente a 'adb -s')
  --package NAME       Application ID (padrao: com.vrplayer)
  -h, --help            Mostra esta ajuda
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --duration-min) DURATION_MIN="$2"; shift 2 ;;
        --interval-sec) INTERVAL_SEC="$2"; shift 2 ;;
        --video-path) VIDEO_PATH="$2"; shift 2 ;;
        --apk) APK="$2"; shift 2 ;;
        --serial) SERIAL="$2"; shift 2 ;;
        --package) PACKAGE="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Opcao desconhecida: $1"; usage; exit 1 ;;
    esac
done

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB=(adb -s "$SERIAL")
fi

DEVICE_COUNT=$("${ADB[@]}" devices | grep -c -E "device$" || true)
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo "Nenhum dispositivo adb encontrado. Conecte o Quest 3 via USB, autorize a Depuracao USB no headset e tente de novo." >&2
    exit 1
fi
if [[ "$DEVICE_COUNT" -gt 1 && -z "$SERIAL" ]]; then
    echo "Mais de um dispositivo adb conectado — use --serial <serial> (veja 'adb devices')." >&2
    exit 1
fi

if [[ -n "$APK" ]]; then
    echo "Instalando $APK..."
    "${ADB[@]}" install -r "$APK"
fi

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUT_DIR="soak-test-results/$TIMESTAMP"
mkdir -p "$OUT_DIR"
MEMORY_CSV="$OUT_DIR/memory.csv"
LOGCAT_FILE="$OUT_DIR/logcat.txt"
SUMMARY_FILE="$OUT_DIR/summary.txt"

echo "timestamp,elapsed_s,pss_kb,pid_alive,thermal_status" > "$MEMORY_CSV"

LOGCAT_PID=""
cleanup() {
    if [[ -n "$LOGCAT_PID" ]] && kill -0 "$LOGCAT_PID" 2>/dev/null; then
        kill "$LOGCAT_PID" 2>/dev/null || true
        wait "$LOGCAT_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

echo "Limpando logcat e iniciando captura em background..."
"${ADB[@]}" logcat -c
"${ADB[@]}" logcat -v time > "$LOGCAT_FILE" &
LOGCAT_PID=$!

echo "Forcando parada do app (estado limpo) e iniciando..."
"${ADB[@]}" shell am force-stop "$PACKAGE" || true
sleep 1

START_ARGS=(shell am start -n "$PACKAGE/$ACTIVITY")
if [[ -n "$VIDEO_PATH" ]]; then
    START_ARGS+=(-e video_path "$VIDEO_PATH")
    echo "Auto-play habilitado: $VIDEO_PATH (comeca ~3s apos o app abrir)"
fi
"${ADB[@]}" "${START_ARGS[@]}"

TOTAL_SEC=$((DURATION_MIN * 60))
ELAPSED=0
MAX_PSS_KB=0
CRASH_DETECTED=0

get_pss_kb() {
    # "TOTAL PSS:" ou variantes de layout do dumpsys meminfo dependendo da versao do Android.
    "${ADB[@]}" shell dumpsys meminfo "$PACKAGE" 2>/dev/null \
        | grep -m1 -E "TOTAL( PSS)?:" \
        | grep -oE "[0-9]+" \
        | head -1 || true
}

get_thermal_status() {
    "${ADB[@]}" shell dumpsys thermalservice 2>/dev/null \
        | grep -m1 -oE "mStatus=[A-Za-z_]+" \
        | cut -d= -f2 || echo "unknown"
}

echo "Monitorando por $DURATION_MIN min (amostra a cada ${INTERVAL_SEC}s). Ctrl-C interrompe com relatorio parcial."
echo "Resultados em: $OUT_DIR"

while [[ "$ELAPSED" -lt "$TOTAL_SEC" ]]; do
    sleep "$INTERVAL_SEC"
    ELAPSED=$((ELAPSED + INTERVAL_SEC))

    PID=$("${ADB[@]}" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)
    if [[ -z "$PID" ]]; then
        echo "[${ELAPSED}s] Processo $PACKAGE nao encontrado — possivel crash." >&2
        echo "$(date -Iseconds),$ELAPSED,,dead," >> "$MEMORY_CSV"
        CRASH_DETECTED=1
        break
    fi

    PSS_KB=$(get_pss_kb)
    THERMAL=$(get_thermal_status)
    PSS_KB=${PSS_KB:-0}
    if [[ "$PSS_KB" -gt "$MAX_PSS_KB" ]]; then
        MAX_PSS_KB=$PSS_KB
    fi

    echo "$(date -Iseconds),$ELAPSED,$PSS_KB,alive,$THERMAL" >> "$MEMORY_CSV"
    printf "[%5ds] PSS=%sMB thermal=%s\n" "$ELAPSED" "$((PSS_KB / 1024))" "$THERMAL"
done

cleanup
LOGCAT_PID=""

CRASH_IN_LOG=0
if grep -qE "FATAL EXCEPTION|ANR in $PACKAGE|Process $PACKAGE .* has died" "$LOGCAT_FILE"; then
    CRASH_IN_LOG=1
fi

MEMORY_OK="PASS"
if [[ "$MAX_PSS_KB" -gt "$MEMORY_LIMIT_KB" || "$MAX_PSS_KB" -eq 0 ]]; then
    MEMORY_OK="FAIL"
fi

CRASH_OK="PASS"
if [[ "$CRASH_DETECTED" -eq 1 || "$CRASH_IN_LOG" -eq 1 ]]; then
    CRASH_OK="FAIL"
fi

{
    echo "Soak test — $PACKAGE"
    echo "Data: $(date -Iseconds)"
    echo "Duracao solicitada: ${DURATION_MIN}min | Duracao real: $((ELAPSED / 60))min ${ELAPSED}s totais"
    echo "Video auto-play: ${VIDEO_PATH:-"(nenhum — app ocioso)"}"
    echo
    echo "== Criterio: nenhum crash em sessao (DoD) =="
    echo "  Processo caiu durante monitoramento: $([[ $CRASH_DETECTED -eq 1 ]] && echo sim || echo nao)"
    echo "  FATAL EXCEPTION / ANR / 'has died' no logcat: $([[ $CRASH_IN_LOG -eq 1 ]] && echo sim || echo nao)"
    echo "  Resultado: $CRASH_OK"
    echo
    echo "== Criterio: memoria < 2.5GB (DoD) =="
    echo "  PSS maximo observado: $((MAX_PSS_KB / 1024))MB ($MAX_PSS_KB KB)"
    echo "  Limite: $((MEMORY_LIMIT_KB / 1024))MB"
    echo "  Resultado: $MEMORY_OK"
    echo
    echo "Arquivos: $MEMORY_CSV | $LOGCAT_FILE"
} | tee "$SUMMARY_FILE"

if [[ "$CRASH_OK" == "FAIL" || "$MEMORY_OK" == "FAIL" ]]; then
    exit 1
fi
exit 0
