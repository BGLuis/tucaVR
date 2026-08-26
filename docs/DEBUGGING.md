# Ferramentas de Debug de Vídeo

> Contexto: depurar os modos SBS/OU/360/180 exigia reproduzir um arquivo real
> naquele formato, ler logcat sem filtro nenhum, e adivinhar que estado
> interno (`ScreenMode`, `stereoLayout`, `polar180`, `swapEyes`) o app achava
> que estava ativo — e não havia nenhum dado de performance (FPS, frames
> lentos, travamentos) pra saber se um problema era de renderização ou de
> desempenho. Este documento registra as ferramentas adicionadas pra isso:
> logging de transição e performance, um jeito de forçar qualquer modo via
> adb sem precisar do arquivo real naquele formato, um HUD na própria cena
> mostrando o estado atual mais FPS/stutter/freeze, e suporte opcional a
> Vulkan validation layers.
> As ferramentas interativas (seções 2 e 3 — broadcast de debug e HUD na
> cena) só existem em build debuggable. O logging (seções 1 e a parte de
> FPS/stutter/freeze da seção 3) roda sempre, os dois caminhos, independente
> de build type — são só `__android_log_print`, mesmo custo de qualquer log
> já existente no projeto.

## 1. Logging de transição e performance (sempre ativo, os dois caminhos)

Toda troca de `ScreenMode`, toda transição "sem frame de vídeo" ↔ "frame
ativo", e todo frame anormalmente lento (stutter/freeze, ver seção 3) loga
uma linha, em vez de silêncio total ou spam a cada frame:

```bash
# Vulkan (caminho padrao)
adb logcat -s VRPlayerAppVK:I VRPlayerAppVK:W VRPlayerAppVK:E

# GLES (-PvrplayerGraphicsApi=GLES)
adb logcat -s VRPlayerApp:I VRPlayerApp:W VRPlayerApp:E
```

Procure por:
- `ScreenMode -> <nome> (stereoLayout=... polar180=... swapEyes=...)` — toda
  vez que o modo muda (botão 3D na UI, `nativeSetScreenMode`, ou o broadcast
  de debug abaixo).
- `video comecou a produzir frames` / `video parou de produzir frames` (ou
  `sem frame disponivel, usando fallback quad solido` no Vulkan) — indica se
  o problema é decode/rede (nunca chega a "comecou") ou renderização (chega,
  mas nada aparece na tela).
- `stutter — frame levou X.Xms` (WARN) / `FREEZE detectado — frame levou
  Xms` (ERROR) — loop de render. `video sem frame novo ha Xms` (WARN) —
  vídeo parado por muito tempo. `video judder — frame ficou Xms na tela
  (media recente Yms)` (WARN) — cadência irregular do vídeo mesmo sem
  parar. Os três últimos são do vídeo em si (decode/rede), diferente do
  render. Ver seção 3 pros thresholds e a diferença entre eles.
- `GetOrImportVideoFrame falhou` (só Vulkan) — falha ao importar o
  `AHardwareBuffer` decodificado como `VkImage` (ver Estágio 3 em
  `docs/VULKAN-MIGRATION-PLAN.md`).

## 2. Forçar um ScreenMode via adb (só em build debuggable)

Útil pra testar todos os modos (SBS, OU, 360, 180, Vr180SBS) **no mesmo
arquivo já carregado**, sem precisar de um arquivo real gravado naquele
formato específico:

```bash
adb shell am broadcast -a com.tucavr.debug.SET_SCREEN_MODE --ei mode 6
adb shell am broadcast -a com.tucavr.debug.CYCLE_SCREEN_MODE
```

Índice de `mode` (precisa bater com `enum class ScreenMode` em
`vr_player_app.cpp`/`vr_player_app_vulkan.cpp` e a codificação em
`rust/bridge/src/lib.rs::cycle_3d_mode` — os três lugares mudam juntos):

| mode | Nome | | mode | Nome |
|---|---|---|---|---|
| 0 | Flat2D | | 5 | Sphere360 |
| 1 | SBS | | 6 | Sphere180 |
| 2 | SBSHalf | | 7 | Sphere360SBS |
| 3 | OU | | 8 | Sphere360OU |
| 4 | OUHalf | | 9 | Vr180SBS |

Existe também um mecanismo mais antigo pra **lançar** o app já num modo
específico com um arquivo (soak test, ver `scripts/soak-test.sh`):

```bash
adb shell am start -n com.tucavr/.VRActivity \
  -e video_path /sdcard/Movies/teste_8k_180.mp4 --ei screen_mode 9
```

Diferença: este último só dispara ~3s depois do cold start (delay
heurístico, ver `AUTO_PLAY_DELAY_MS`) e só uma vez por processo; o broadcast
acima funciona a qualquer momento, com o player já rodando.

O receiver só é registrado se `ApplicationInfo.FLAG_DEBUGGABLE` estiver
setada (verdadeiro pro build `debug` do Gradle por padrão) — nunca existe
num APK de release, então não é uma superfície de ataque nesse caso.

## 3. Modal de Estatísticas Técnicas ("Stats for Nerds")

O app possui um modal completo de diagnóstico em tempo real ("Stats for Nerds"), acessível através do botão de estatísticas na barra de controles do player quando ativado em **Configurações > Avançado > Estatísticas Técnicas**.

### Ativação e Zero Overhead
- **Configurações**: O toggle `DEBUG_STATS_PANEL` persiste a preferência do usuário e notifica instantaneamente o motor nativo via JNI (`nativeSetDebugStatsEnabled`).
- **Zero Overhead**: Quando desativado, o motor nativo (C++/Rust) realiza early-exit com flag atômica (`g_debugStatsEnabled`), eliminando chamadas JNI periódicas, alocações de string e coletas de métricas no loop de render.

### Seções e Métricas Disponíveis

1. **Vídeo & Renderização**:
   - **Resolução / Codec**: Resolução nativa do vídeo e codec decodificado (ex.: `3840x2160 (HEVC)`).
   - **Taxa de Quadros / Display**: `decoded_fps` (taxa de decodificação real na thread Rust) / `output_fps` (taxa de entrega no compositor) e taxa de atualização do headset (`90 Hz`).
   - **Quadros Descartados**: FPS e percentual de frames descartados (`dropped_fps`).
   - **Stutter / Freeze**: Contadores cumulativos de stutters (>20ms) e freezes (>250ms) no loop de render.
   - **Jitter de Vídeo**: Variação do intervalo entre quadros (`jitter_ms`) e tempo decorrido desde a última mudança do buffer (`frame_gap_ms`).
   - **Modo de Tela / Estéreo**: Projeção e layout 3D (ex.: `Sphere180`, `OverUnder [Swap]`).
   - **Escala de Resolução / Foveation**: Fator de escala da viewport e status de Foveated Rendering.
   - **Backend Gráfico**: Backend ativo (`VULKAN` ou `GLES`).

2. **Áudio & Sincronização**:
   - **Codec de Áudio / Canais**: Formato de áudio e configuração de canais (ex.: `AAC (6ch, 48kHz)`).
   - **Desvio A/V (Drift)**: Diferença de sincronização entre áudio e vídeo em milissegundos (`av_drift_ms`).
   - **Áudio Espacial**: Modo de espacialização (`Binaural (5.1/7.1)`, `Ambisonics`, `Off`) e rastreamento de cabeça (`HeadTrack`).
   - **Faixa de Áudio**: Índice da faixa ativa e total de faixas disponíveis.
   - **Legendas / Sincronia**: Faixa de legenda ativa e offset de sincronização aplicado.

3. **Rede & Buffer**:
   - **Origem / Protocolo**: Tipo de fonte (`Local Storage`, `SMB`, `FTP`, `SFTP`, `NFS`, `DLNA`, `HTTP(S)`).
   - **Taxa de Rede**: Throughput recente de leitura em MB/s (`net_mbs`).
   - **Fila de Buffer**: Quantidade de pacotes/blocos na fila de decodificação (`queue_depth`).
   - **Latência por Bloco**: Tempo de busca do último bloco em milissegundos (`net_last_fetch_ms`).
   - **Blocos**: Total de blocos de rede buscados e descartados após seeks (`net_blocks_fetched` / `net_blocks_discarded`).
   - **Latência do Último Seek**: Tempo total medido no último seek (`seek_latency_ms`).

4. **Sistema & Hardware**:
   - **Status Térmico**: Nível de estresse térmico reportado pelo sistema (`Normal`, `Light`, `Moderate`, `Severe`, `Critical`).
   - **Bateria**: Nível de carga percentual e status de carregamento.
   - **Versão do App**: Versão do aplicativo e tipo de build (`Debug` / `Release`).

Implementação: `native/src/vr_player_app.cpp` / `vr_player_input_vulkan.h`
chamam `VRActivity.updateDebugHud(texto)` via JNI no mesmo throttle que já
existe pra `updateMediaProgress`; o texto é construído inteiramente do lado
nativo (sem string de recurso/i18n — é diagnóstico técnico, não UI de
produção). `VRActivity.isDebuggable` filtra antes de tocar a `View`; builds
de release recebem a chamada mas ela é descartada sem custo.

## 4. Vulkan validation layers (já habilitadas no build local)

`CreateVulkanInstanceAndDevice` (`vr_player_app_vulkan.cpp`) checa em
runtime (`vkEnumerateInstanceLayerProperties`) se `VK_LAYER_KHRONOS_validation`
está disponível e, se estiver, habilita a layer + `VK_EXT_debug_utils` com um
callback que loga pro logcat (`VkValidation: ...`, tag `VRPlayerAppVK`,
níveis WARN/ERROR habilitados por padrão).

**Gating por flag de build:**
A validation layer vem desabilitada por padrão tanto no código C++ quanto no empacotamento do APK. Para habilitá-la:
1. Adicione o `.so` da layer em `app/src/main/jniLibs/arm64-v8a/libVkLayer_khronos_validation.so`.
2. Compile passando a flag Gradle `-PenableVulkanValidation=true`:
   ```bash
   ./gradlew assembleDebug -PenableVulkanValidation=true
   ```
Sem essa flag, o Gradle exclui o `.so` do APK via `packaging.jniLibs.excludes` e o CMake não define `ENABLE_VK_VALIDATION_LAYERS`, garantindo que builds padrão e de produção não sofram sobrecarga de CPU/GPU nem interceptações no hot path de submissão de comandos e fences.

**Obtendo o arquivo `.so` (não versionado):**
`app/src/main/jniLibs/` está no `.gitignore`. Para reobter o binário arm64-v8a da layer:
- Baixe uma release oficial de [`KhronosGroup/Vulkan-ValidationLayers`](https://github.com/KhronosGroup/Vulkan-ValidationLayers/releases) (asset Android, variante **arm64-v8a**) e copie para `app/src/main/jniLibs/arm64-v8a/libVkLayer_khronos_validation.so`.
- Ao compilar com `-PenableVulkanValidation=true`, o primeiro frame com a layer ativa exibirá no logcat: `Vulkan: VK_LAYER_KHRONOS_validation encontrada, habilitando`. Em builds comuns sem a flag, o log registrará: `Vulkan: Validation layers desabilitadas por configuracao de build`.

## 5. Session IDs e Rastreabilidade Multi-Camada (N1 e N6)

Para rastrear o ciclo de vida completo de cada reprodução através das 3 linguagens do projeto (Kotlin $\rightarrow$ C++ $\rightarrow$ Rust), cada início de reprodução (`playFile`, `playUrl`, `playSmb`, etc.) gera um identificador de sessão pseudo-aleatório de 8 caracteres hexadecimais (ex: `a1b2c3d4`).

O Session ID é propagado imediatamente via JNI para o C++ e via C-ABI para a camada Rust. Todos os logs do sistema passam a incluir o prefixo `[s:<session_id>]`:

- **Kotlin (`VRPlayer_App`):** logs via `VRLog` (ex: `[s:a1b2c3d4] Iniciando sessao de reproducao...`).
- **C++ (`VRPlayerApp` / `VRPlayerAppVK`):** macros `LOGI`, `LOGW`, `LOGE`.
- **Rust (`VRPlayer_Rust`):** macros `log_info!`, `log_warn!`, `log_error!`, `log_debug!`.

### Histórico de Erros e Crash Reporter
- **Rust Error Ring Buffer:** A bridge Rust mantém um buffer circular não destrutivo com capacidade para os últimos 16 erros (`ErrorRingBuffer`), preservando timestamp e session ID mesmo após consumo pelo Toast da UI.
- **Crash Reporter:** Exceções não capturadas no Kotlin acionam o `UncaughtExceptionHandler`, gravando o stack trace e metadados da sessão em `/sdcard/Android/data/com.tucavr/files/debug/crash-<sessionId>-<timestamp>.txt`.

## 6. Exportação de Séries Temporais de Telemetria em CSV (N2)

O aplicativo suporta gravação periódica de métricas de desempenho em arquivos CSV para diagnóstico aprofundado sem necessidade de conexão USB em tempo real.

### Ativação
- Acesse **Configurações > Avançado > Exportar Telemetria de Debug (CSV)** ou ative via `FeatureFlags.Flag.DEBUG_STATS_EXPORT`.
- Os arquivos são gravados em `/sdcard/Android/data/com.tucavr/files/debug/session-<sessionId>-<timestamp>.csv`.

### Formato do Arquivo CSV
```csv
timestamp_ms,session_id,elapsed_s,backend,screen_mode,video_status,video_fps,decoded_fps,output_fps,dropped_fps,jitter_ms,net_mbs,video_q_depth,seek_ms,smoothed_fps,frame_ms,stutter_count,freeze_count,thermal_level,scale,source_type,source_redacted
```

> [!IMPORTANT]
> **Privacidade:** Senhas e tokens em URLs de rede (SMB, FTP, SFTP, HTTP) são sanitizados automaticamente (`redactSource`) antes da gravação no CSV ou logcat.

## 7. Coleta Automatizada de Pacote de Debug (`collect-debug.sh` - N3)

Para obter um diagnóstico completo do headset com um único comando:

```bash
./scripts/collect-debug.sh
# ou com serial específico e bugreport do Android:
./scripts/collect-debug.sh --serial <SERIAL> --bugreport
```

O script gera um pacote `.tar.gz` contendo:
1. `manifest.txt`: Metadados do Quest 3, versão do app, build e commit git.
2. `logcat.txt` e `logcat-filtered.txt`: Registros completos e filtrados pelas tags do player.
3. `telemetry/`: Arquivos CSV de telemetria e relatórios de crash transferidos do headset.
4. `meminfo.txt` e `thermalservice.txt`: Diagnósticos de memória e estrangulamento térmico.
5. `dropbox_crashes.txt`: Registros de falhas do sistema Android.

## 8. Captura Visual de Frames no Vulkan e GLES (N4)

Como o compositor OpenXR desenha diretamente no display em modo `vr_only`, ferramentas padrão como `screencap` não capturam a cena do vídeo.

O player fornece captura direta de frames renderizados através de `nativeRequestFrameCapture`, suportado em ambos os backends (Vulkan e GLES):
- Salva o frame do olho esquerdo e direito como imagens PPM (`.left.ppm` e `.right.ppm`).
- O script `scripts/test-3d-playback.sh` utiliza esse mecanismo para validar projeções estereoscópicas e converte automaticamente os frames para PNG usando `ffmpeg`.

## 9. Diagnóstico de Falhas Nativas e Ciclo de Vida (C-01 a C-04)

> [!WARNING]
> O manipulador de crash padrão da JVM (`Thread.setDefaultUncaughtExceptionHandler`, Seção 5) grava arquivos `crash-*.txt` **apenas** para exceções Java (`Throwable`). Falhas nativas em C++/Rust (como `SIGABRT` gerado pelo ART ou `SIGSEGV` no driver gráfico Adreno) são sinais POSIX que derrubam o processo imediatamente, sem passar pelo manipulador Kotlin.

Para capturar e diagnosticar falhas nativas no Meta Quest:

```bash
# 1. Visualizar o buffer de crash do logcat:
adb logcat -d -b crash

# 2. Inspecionar arquivos de tombstone gerados pelo SO:
adb shell ls -la /data/tombstones/

# 3. Sessão de monitoramento focada em ciclo de vida e renderização:
adb logcat -c
# execute a ação (ex: abrir, reproduzir, fechar pelo Horizon OS, reabrir)
adb logcat -d -b main -b crash -s \
  DEBUG:* AndroidRuntime:* libc:* art:* VRPlayerAppVK:* VkValidation:*
```

### Interpretação dos Padrões de Falha:
- `Native thread exited without calling DetachCurrentThread` (art): Indica que a thread nativa encerrou sem chamar `DetachCurrentThread` (**C-01**).
- `SIGSEGV`/`SIGABRT` envolvendo `libvulkan.so` ou driver `adreno`: Trabalho pendente na GPU durante destruição do dispositivo por ausência de `vkDeviceWaitIdle` (**C-02**).
- `VUID-vkDestroyDevice-device-05137` / `VUID-vkDestroyCommandPool-...`: Objetos Vulkan destruídos fora de ordem ou após o `VkDevice` (**C-03**).
- `WindowLeaked` com `VRPresentation`: `Presentation` ou `VirtualDisplay` não foram liberadas no `onDestroy` da Activity (**R-01**).
- Comportamento de reabertura suja (ex: tocar mídia anterior ou nascer em 3D incorreto): Variáveis estáticas retidas no processo em cache sem reset na reinicialização (**C-04**). Consulte [`docs/reports/CICLO-DE-VIDA-CRASH-FECHAMENTO.md`](./reports/CICLO-DE-VIDA-CRASH-FECHAMENTO.md) para a análise detalhada.
