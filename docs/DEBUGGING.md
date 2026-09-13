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

O app possui um modal completo de diagnóstico em tempo real ("Stats for Nerds"), acessível através do botão de estatísticas na barra de controles do player quando ativado em **Configurações > Avançado > Estatísticas Técnicas**. Esta seção foi reescrita após a triagem de
`docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md` (fases F0–F8): contrato único
de campos, frescor por grupo, atribuição de estágio, 5 gráficos e log de
eventos.

### Ativação e Zero Overhead
- **Configurações**: O toggle `DEBUG_STATS_PANEL` persiste a preferência do usuário e notifica instantaneamente o motor nativo via JNI (`nativeSetDebugStatsEnabled`).
- **Zero Overhead**: Quando desativado, o motor nativo (C++/Rust) realiza early-exit com flag atômica (`g_debugStatsEnabled`), eliminando chamadas JNI periódicas, alocações de string e coletas de métricas no loop de render.

### Contrato único de campos (F0)

Cada campo do wire tem exatamente **2** pontos de definição — não mais:
1. **`native/src/debug_stats.h`** (`struct DebugStats` + `SerializeDebugStats`) — dono da forma do wire TSV (`chave\tvalor`), populado em `native/src/vr_player_input_vulkan.h` (caminho Vulkan; o caminho GLES em `vr_player_app.cpp` não recebe campos novos — ver "O que não fazer" abaixo).
2. **`app/.../debug/DebugStats.kt`** (`DebugStatsParser`) — único parser do TSV; `NativeDebugStats` é a representação estruturada usada por `DebugTelemetryExporter` (CSV) e `DebugStatsModal` (UI). Não existe mais um segundo `when(key)` reimplementando o parsing no exportador.

Para adicionar um campo novo: uma entrada em `DebugStats` (native), uma linha em `SerializeDebugStats`, um campo em `NativeDebugStats` + um `when` em `DebugStatsParser`, e (se for para o CSV) uma posição em `CSV_HEADER` — bump o `SCHEMA_VERSION` sempre que a forma do CSV mudar (D-05).

### Frescor por grupo (D-04)

Quatro campos (`video_stats_age_ms`, `network_stats_age_ms`, `audio_stats_age_ms`, `render_stats_age_ms`) dizem há quanto tempo o grupo correspondente não recebe um dado genuinamente novo — `render` é sempre 0 (computado a cada frame no próprio loop de render). No modal, um valor com idade acima de ~2 amostras (~200ms a 10Hz) aparece esmaecido com a idade ao lado, em vez de parecer uma leitura atual quando não é.

### Seções e Métricas Disponíveis

1. **Vídeo & Renderização** (`DebugStatsModal.kt`, seção `debug_stats_section_video_render`):
   - **Estágio do Gargalo** (`bottleneck` — F5, primeira linha do painel): `NONE`/`NETWORK`/`PRESENTATION`, derivado de `frame_gap_ms` + `video_q_depth` (ver [`BottleneckStageAnalyzer`](../app/src/main/java/com/tucavr/debug/BottleneckStageAnalyzer.kt)) — responde "onde travou" sem reconstruir a leitura à mão.
   - **Resolução / Codec**, **Decodificador de Vídeo**, **Modo de Tela / Estéreo**, **Backend Gráfico**: metadados estáticos da sessão.
   - **Framerate / Display** e **Quadros Descartados**: `decoded_fps` / `output_fps` / `dropped_fps`, com idade do grupo vídeo.
   - **Stutter / Freeze**: contadores cumulativos do **loop de render** (>20ms / >250ms) — não confundir com stall de vídeo (D-02, ver Log de Eventos abaixo), que mede a apresentação de frames de vídeo, não o loop.
   - **Jitter de Vídeo**, **Escala de Resolução / Foveation**.
   - **Tempo de GPU**, **Qualidade Adaptativa**, **Upscaling**, **Draw Calls / Triângulos** (F5 G5): 7 campos que já eram coletados e trafegavam no wire, mas nunca apareciam no modal antes de F5.

2. **Áudio & Sincronização**: codec/canais, **Desvio A/V** (`av_drift_ms`, agora EMA — ver F4 abaixo), áudio espacial, faixa e legendas.

3. **Rede & Buffer**: origem/protocolo, taxa de rede, fila de buffer, latência por bloco, blocos buscados/descartados, latência do último seek.

4. **Sistema & Hardware**: status térmico, bateria, versão do app.

5. **Gráficos** (F5 — `debug_stats_section_charts`, primeiro `View` com `onDraw` customizado do projeto, ver [`VoidChart.kt`](../app/src/main/java/com/tucavr/designsystem/VoidChart.kt)):

   | # | Gráfico | Responde | Fonte |
   |---|---|---|---|
   | G1 | Estágio do pipeline (60s) | Faixas coloridas por `bottleneck_stage`, histórico rolante acumulado em `DebugStatsModal` a partir das amostras de ~10Hz — nenhum campo novo no wire. | Verde=saudável, azul=`NETWORK`, vermelho=`PRESENTATION`. |
   | G2 | Histograma de frame time | 8 buckets cumulativos (`hist_bucket_0..7`, bordas em 11.1/16.7/20/33.3/50/100/250ms) acumulados a cada frame no loop de render — captura a distribuição a 90Hz sem transportar 90 amostras/s. | `native/src/vr_player_app_vulkan.cpp` (`kFrameTimeHistogramEdgesMs`). |
   | G3 | Saúde do buffer (s) / Rede (MB/s) | `video_q_depth / video_fps` (derivado no cliente) sobreposto a `net_mbs` — paridade com o "segundos de buffer" de players convencionais; `queue_depth` em pacotes não dizia nada por si só. | Kotlin (`DebugStatsModal.updateCharts`). |
   | G4 | Drift A/V (ms) | `av_drift_ms` com banda de referência ±40ms. | Wire (EMA, ver F4). |
   | G5 | GPU time vs. orçamento (ms) | `smoothed_gpu_time_ms` contra a mesma fórmula de orçamento do `QualityController` (`frame_interval_ms × 0.85`, `rust/media-logic/src/quality.rs`). | Wire + fórmula espelhada no cliente. |

   Reprodutível offline (sem headset) a partir de qualquer CSV coletado: `scripts/plot-session.py` gera os mesmos 5 gráficos no PC (ver seção 6).

### Log de Eventos (F6)

Além da série a 1Hz do CSV (que não vê um stutter de 20ms — são 90 frames por amostra), cada sessão grava `session-<id>-events.log` em paralelo ao CSV, com uma linha por episódio: `STUTTER`, `FREEZE`, `VIDEO_STALL_ENDED` (D-02 — duração aproximada pelo pico de `frame_gap_ms` da amostra anterior ao fim do stall) e `QUALITY_TRANSITION` (nível anterior/novo + motivo), cada um com o `bottleneck_stage` e um snapshot do pipeline (`frame_ms`, `gpu_ms`, `queue`, `net_mbs`, `drift_ms`, `quality`, `scale`) no momento detectado. Lógica pura e testável em [`DebugEventLog.kt`](../app/src/main/java/com/tucavr/debug/DebugEventLog.kt) (`detectEvents`/`formatEventLine`); a escrita em arquivo fica em `DebugEventLogWriter`, acionado do mesmo `updateDebugHud` que já alimenta o CSV.

### Fluxo de triagem recomendado

1. Cheque **Estágio do Gargalo** primeiro — se `NETWORK`/`PRESENTATION` aparecer com frequência, já aponta o lado certo do pipeline.
2. Vá ao gráfico correspondente: G1 para ver quando/quanto tempo cada estágio dominou; G2 se a suspeita é uma cauda de frame time (não a média); G3/G4/G5 para os sinais clássicos de rede/sync/GPU.
3. Consulte o log de eventos (`session-<id>-events.log`) para o snapshot exato do pipeline no início/fim de cada episódio — é o que permite reconstruir "o que estava acontecendo quando travou" sem grep manual no logcat.

### O que não fazer (ver relatório, seção 2.6)
- Não instrumentar o caminho GLES (`vr_player_app.cpp`) — backend congelado até 1.0, já falsifica `renderResolutionScale`/`displayRefreshRate` e não tem GPU timing.
- Não portar o painel para C++ — já é Kotlin ponta a ponta, e o custo de composição do quad é o mesmo.
- Não realimentar o `QualityController` com os contadores de `XR_META_performance_metrics` (seção 10) — a especificação Khronos proíbe isso explicitamente; são diagnóstico, nunca entrada de controle.

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

A 1ª coluna é sempre `schema_version` (D-05) — incrementada sempre que o formato muda de forma incompatível; ver `DebugTelemetryExporter.SCHEMA_VERSION` para o histórico de versões e o que cada uma adicionou. Cabeçalho completo (versão atual):
```csv
schema_version,timestamp_ms,session_id,elapsed_s,backend,screen_mode,stereo_layout,polar_180,swap_eyes,video_status,frame_gap_ms,video_fps,decoded_fps,output_fps,dropped_fps,jitter_ms,net_mbs,video_q_depth,seek_ms,smoothed_fps,frame_ms,gpu_time_ms,smoothed_gpu_time_ms,upscaling_mode,upscaling_sharpness,mqsr_enabled,stutter_count,freeze_count,thermal_level,scale,refresh_rate,av_drift_ms,net_last_fetch_ms,net_blocks_fetched,net_blocks_discarded,foveation,spatial_audio,head_tracking,speed,volume,audio_track,audio_track_count,sub_track,sub_offset_ms,quality_level,quality_reason,draw_call_count,triangle_count,video_stall_count,video_stats_age_ms,network_stats_age_ms,audio_stats_age_ms,render_stats_age_ms,network_fetch_failures,network_sequential_streak,network_throttled,audio_queue_depth,decode_error_count,demux_corrupt_packet_count,audio_underrun_count,load_phase_demux_open_ms,load_phase_decoder_ready_ms,load_phase_audio_ready_ms,perf_metrics_valid_mask,perf_app_cpu_frametime_ms,perf_app_gpu_frametime_ms,perf_motion_to_photon_latency_ms,perf_compositor_cpu_frametime_ms,perf_compositor_gpu_frametime_ms,perf_compositor_dropped_frame_count,perf_compositor_spacewarp_mode,perf_device_cpu_util_average,perf_device_cpu_util_worst,perf_device_gpu_util,hist_bucket_0,hist_bucket_1,hist_bucket_2,hist_bucket_3,hist_bucket_4,hist_bucket_5,hist_bucket_6,hist_bucket_7,source_type,source_redacted
```

Ao lado de cada CSV, a mesma sessão grava `session-<id>-events.log` (seção 3, Log de Eventos) — ver `scripts/plot-session.py` para regenerar os 5 gráficos do modal a partir do CSV, no PC, sem headset.

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

## 10. `XR_META_performance_metrics` (F3)

Extensão OpenXR habilitada em `CreateVulkanInstanceAndDevice` (`state.supportsPerfMetrics`), com o **sistema** de métricas habilitado separadamente logo após a criação da sessão (`SetupPerformanceMetrics`, `vr_player_app_vulkan.cpp`) via `xrSetPerformanceMetricsStateMETA` — habilitar só a extensão não é suficiente; sem essa segunda chamada, `xrQueryPerformanceMetricsCounterMETA` devolve `XR_ERROR_VALIDATION_FAILURE` para qualquer contador.

10 contadores amostrados a ~1Hz (`PollPerformanceMetrics`, mesma cadência do poll de FPS/rede): `app/cpu_frametime`, `app/gpu_frametime`, `app/motion_to_photon_latency`, `compositor/cpu_frametime`, `compositor/gpu_frametime`, `compositor/dropped_frame_count`, `compositor/spacewarp_mode`, `device/cpu_utilization_average`, `device/cpu_utilization_worst`, `device/gpu_utilization` — per-core (`device/cpuN_utilization`) deliberadamente fora de escopo (custaria um campo por núcleo do XR2 Gen 2 pelo mesmo diagnóstico já coberto pelos agregados average/worst).

`perf_metrics_valid_mask` no wire/CSV é um bitmask — bit N = 1 quando o contador N trouxe um valor válido nesta amostra. Um contador "não suportado" ou momentaneamente indisponível fica com o bit **zerado**, nunca aparece como "0" ambíguo (D-04). `compositor/spacewarp_mode` é o contador de maior valor para stalls de vídeo com o loop de render aparentemente saudável: se o compositor estava reprojetando, o `frame_ms` do app não mostra isso.

**Restrição da especificação**: estes contadores são diagnóstico — nunca devem alimentar o `QualityController`. Os intervalos de amostragem são definidos pelo runtime, não pelo app.

## 11. `ApplicationExitInfo`, Thermal Headroom e ADPF (F8)

- **`ApplicationExitInfo`** ([`ApplicationExitInfoReporter.kt`](../app/src/main/java/com/tucavr/debug/ApplicationExitInfoReporter.kt)): lido uma vez no arranque (`VRActivity.onCreate`, API 30+). Cobre `REASON_ANR` e `REASON_CRASH_NATIVE` — as duas classes de morte do processo que o `UncaughtExceptionHandler` da JVM (seção 5) não vê, por serem falhas fora do runtime Java. Grava `exit-info-<timestamp>.txt` em `getExternalFilesDir("debug")` e, a partir da API 31, o tombstone bruto em protobuf (`.pb`, não parseado — usar ferramenta externa). Deduplica via `SharedPreferences` (só reporta eventos mais recentes que o último processado).
- **Thermal Headroom contínuo**: `ThermalMonitor.getThermalHeadroom(forecastSeconds)` (API 30+) complementa o nível térmico discreto 0–5 já existente com uma previsão de 0.0 (sem throttle) a 1.0 (limiar SEVERE). Devolve `null` se chamado mais rápido que ~1Hz (a própria API devolve `NaN` nesse caso) — não pollar por frame.
- **ADPF** (`SetupAdpfSession`/`ReportAdpfWorkDuration`, `vr_player_app_vulkan.cpp`, API 33+): diferente dos itens acima, é uma API de **escrita** — o app declara a duração de frame alvo e reporta a real a cada frame; o sistema ajusta escalonamento/frequência de CPU/GPU. Os símbolos `APerformanceHint_*` são resolvidos via `dlopen("libandroid.so")`/`dlsym` (não chamada direta: o clang do NDK recusa compilar uma chamada direta a um símbolo `__INTRODUCED_IN(33)` com `minSdk=26`, mesmo dentro de um `if` checando a API em runtime — mesmo padrão já usado neste arquivo para funções de extensão OpenXR via `xrGetInstanceProcAddr`). Sessão criada na mesma thread já registrada como crítica ao runtime XR (`xrSetAndroidApplicationThreadKHR`) — este app não tem uma render thread separada.
