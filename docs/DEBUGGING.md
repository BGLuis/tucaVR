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

## 3. HUD de debug na cena (inclui FPS/stutter/freeze/atraso de vídeo)

Com um build debuggable, o painel de controles (mesmo painel do
play/pause/seek) ganha uma linha extra de texto pequeno, atualizada ~10x/seg,
formato:

```
VULKAN | Sphere180 | stereoLayout=1 polar180=1 swap=0 | video=ativo vidGap=16ms vidFps=24 decFps=24 jitter=18ms | 89fps 11.2ms stutter=2 freeze=0
```

Mostra exatamente o que o pipeline de renderização está usando naquele
frame — sem precisar cruzar logcat com o que a UI do botão "modo 3D" diz que
está selecionado (que podem divergir se o Rust resetar o modo num novo
`playFile`, por exemplo — ver comentário em
`VRControlsPresentation.updateProgress`) — mais os números de performance.

**Importante**: `fps`/`ms`/`stutter`/`freeze` medem o **loop de render** (a
cena XR sendo desenhada e enviada pro compositor) — `vidGap` mede o
**vídeo em si**, e são coisas diferentes. O loop de render pode rodar liso a
90fps redesenhando o *mesmo* frame de vídeo repetido se o decode travar
(rede lenta, MediaCodec atrasado) — nesse caso `stutter`/`freeze` ficam
parados enquanto o usuário vê o vídeo engasgar. É por isso que existem os
dois:

- **`vidGap=Xms`**: tempo desde a última vez que o `AHardwareBuffer` de
  vídeo mudou de fato (não é resolução nem frame count — é só "há quanto
  tempo o frame na tela é o mesmo"). Some passo de decodificação nenhuma
  vez zera esse número sem que o *pointer* do buffer mude, então ele reflete
  literalmente "o que está na textura mudou ou não". Fica alto tanto se o
  usuário pausou quanto se o decode/rede travou — o app não distingue os
  dois casos (não há um `is_playing` exposto pro C++ ainda); se `vidGap`
  subir muito **enquanto você sabe que o vídeo deveria estar tocando**, é o
  pipeline de decode/rede, não a renderização. Passar de 500ms loga
  `video sem frame novo ha Xms (decode/rede travado? ou usuario pausou?)`
  (WARN) uma vez por episódio (não repete a cada frame parado).
- **`vidFps=X` / `jitter=Xms`**: `vidGap` sozinho só mostra o gap *atual*,
  amostrado no HUD a ~10Hz — um vídeo pode nunca passar de, digamos, 200ms
  e ainda assim ter cadência irregular (20/20/90/20/90ms...), percebida como
  falta de fluidez mesmo sem nenhuma amostra isolada ser alarmante. Por
  isso existe um histórico circular dos últimos 30 gaps (cada "gap" = quanto
  tempo o frame anterior ficou parado na tela antes do próximo chegar):
  `vidFps` é a taxa de entrega real medida a partir da média desses gaps
  (**diferente** do FPS de renderização acima — pode ser um vídeo de 24fps
  rodando dentro de um loop de render a 90fps); `jitter` é a amplitude
  (máximo − mínimo) da janela — jitter alto com `vidFps` "normal" pra aquele
  vídeo é a assinatura de judder (frames vindo em rajadas irregulares em vez
  de cadência estável). Cada gap novo que passa de 2× a média recente **e**
  passa de +20ms da média (não um limiar fixo — um vídeo de 24fps tem gaps
  ~41ms normalmente, isso não seria judder; o mesmo valor seria uma anomalia
  clara num vídeo de 60fps) loga
  `video judder — frame ficou Xms na tela (media recente Yms)` (WARN).
- **`decFps=X`**: `vidFps` acima só mede o que o **C++ consegue observar**
  via polling (uma vez por iteração do loop de render) — se o loop estiver
  saudável mas ainda assim `vidFps` cair, isso não prova que o *decode* em
  si ficou mais lento; pode ser só o consumo do lado C++ (import/cache
  Vulkan) perdendo frames que o decode já produziu. `decFps` é a taxa real
  de decode, contada direto na thread de decode do Rust
  (`TextureOutput::frames_decoded`, incrementado toda vez que
  `acquire_latest_buffer()` adquire uma imagem nova de verdade) e amostrada
  a ~1Hz via `get_video_frames_decoded_count()` — **completamente
  independente** de quantas vezes o C++ chama `get_current_video_frame()`.
  Se `vidFps` e `decFps` baterem, o decode é o gargalo (útil pra descartar
  hipóteses de import/cache Vulkan). Se `decFps` ficar alto e `vidFps`
  baixo, o gargalo está no consumo do lado C++, não no decode.
- **`Xfps`**: FPS instantâneo suavizado por média móvel exponencial
  (peso 0.1 no valor novo a cada frame).
- **`X.Xms`**: duração do último frame **do loop de render** (não do
  vídeo — ver `vidGap` acima). No GLES vem direto de `in.DeltaSeconds` (o
  OVRFW já mede isso). No Vulkan não existe esse equivalente pronto — é
  medido em wall-clock (`std::chrono::steady_clock`) entre chamadas
  consecutivas de `RenderFrame`, de propósito, **não** a partir de
  `predictedDisplayTime`: esse último é o horário que o *compositor*
  pretende mostrar o próximo frame, não quanto tempo o app realmente levou.
  Um stall real (decode bloqueando, `vkQueueWaitIdle` demorado) atrasa a
  *próxima* chamada de `RenderFrame`, que é exatamente o que o wall-clock
  capta.
- **`stutter=N`**: contador cumulativo de frames **de render** que levaram
  mais de 20ms (~1 vsync perdido a 90Hz) — cada ocorrência também loga
  `stutter — frame levou X.Xms` (nível WARN) no logcat, com timestamp, pra
  correlacionar com o que estava acontecendo na hora (seek? troca de modo?
  frame 8K chegando?).
- **`freeze=N`**: contador de frames que levaram mais de 250ms — um stall
  bem mais sério que reprojection normal. Loga nível ERROR
  (`FREEZE detectado — frame levou Xms`).

Os thresholds (`kStutterThresholdMs`/`kFreezeThresholdMs`/`kVideoStallThresholdMs`
— 20ms/250ms/500ms) são arbitrários — um ponto de partida razoável pra
distinguir "hitch perceptível" de "travamento de verdade", não calibrados
contra dados reais do Quest 3 (sem headset disponível nesta sessão). Ajuste
se gerarem falsos positivos/negativos demais no seu teste.

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
níveis WARN/ERROR habilitados por padrão). **Sem o arquivo `.so` da layer, é
um no-op silencioso** — nada quebra, a instância Vulkan é criada normalmente
sem a layer.

`app/src/main/jniLibs/arm64-v8a/libVkLayer_khronos_validation.so` já está
presente nesta máquina — build arm64-v8a legítima (NDK r28c, o mesmo major
usado por este projeto), obtida do cache local do Gradle (artefato do motor
do Flutter, que embute essa mesma layer pra debug; resolvido originalmente
via Maven oficial do Google por outro projeto nesta máquina, não baixado
manualmente). `sha256sum` conferido igual em duas cópias independentes no
disco. O arquivo de origem tem ~223MB **não stripado** (símbolos de debug
completos) — o `stripDebugDebugSymbols` do AGP já reduz isso pra ~15MB antes
de empacotar no APK (confirmado nesta sessão: `app-debug.apk` final ficou
com 64MB no total, nada fora do normal).

**Este arquivo não é versionado** —
`app/src/main/jniLibs/` está no `.gitignore` (mesmo diretório onde
`scripts/build.sh` já copia `libbridge.so`/ffmpeg a cada build; nem esse
script nem `cargo ndk` limpam o diretório antes de copiar, então o arquivo
da layer sobrevive a rebuilds normais). Se você clonar o repo numa outra
máquina, o arquivo não vem junto — para reobter, use a mesma fonte que deu
certo aqui (`~/.gradle/caches/**/transformed/*_debug-*/arm64-v8a/`, se você
já tiver algum projeto Flutter/Android buildado nessa máquina) ou baixe um
release oficial de
[`KhronosGroup/Vulkan-ValidationLayers`](https://github.com/KhronosGroup/Vulkan-ValidationLayers/releases)
(asset Android, variante **arm64-v8a** — o único ABI que este projeto
builda, ver `native/CMakeLists.txt`) e copie pro mesmo caminho.

No primeiro frame com a layer ativa, o logcat deve mostrar
`VK_LAYER_KHRONOS_validation encontrada, habilitando`.

**Apague `app/src/main/jniLibs/arm64-v8a/libVkLayer_khronos_validation.so`
antes de qualquer build que não seja pra você mesmo testar** — validation
layers têm custo de performance real e não devem ir num build de
release/distribuição (o projeto não publica em loja no momento, mas o
hábito vale a pena). Removê-lo volta automaticamente ao comportamento
no-op descrito acima, sem precisar mudar nenhum código.

Nada disso foi validado num Quest 3 físico — só compilação/empacotamento
verificados nesta sessão (sem headset disponível). Se o runtime do Quest não expuser a layer
mesmo com o `.so` presente (algumas plataformas Android restringem quais
processos podem carregar layers), o fallback continua sendo os logs comuns
da seção 1.
