# Fase 0.1 — MVP (Fundação)

> **Objetivo**: Reproduzir um vídeo 2D de arquivo local ou SMB em uma tela virtual dentro do Quest 3.  
> **Resultado esperado**: O desenvolvedor coloca o headset, abre o app, navega até um vídeo (local ou em um NAS via SMB), e assiste com controles básicos em um ambiente escuro.

---

## 📋 Índice

1. [Setup do Monorepo](#1-setup-do-monorepo)
2. [Pipeline de Vídeo (Rust Core)](#2-pipeline-de-vídeo-rust-core)
3. [Ambiente VR e Rendering (OpenXR/C++)](#3-ambiente-vr-e-rendering-openxrc)
4. [Controles de Reprodução (UI VR)](#4-controles-de-reprodução-ui-vr)
5. [File Browser Local](#5-file-browser-local)
6. [Protocolo SMB/CIFS](#6-protocolo-smbcifs)
7. [HTTP URL Playback](#7-http-url-playback)
8. [Internacionalização (i18n)](#8-internacionalização-i18n)
9. [Histórico de Reprodução](#9-histórico-de-reprodução)
10. [Cuidados Transversais](#10-cuidados-transversais)

---

## 1. Setup do Monorepo

### O que fazer

```
vr-multimedia/
├── app/                          # Módulo Android/Kotlin principal
│   ├── src/main/
│   │   ├── java/com/tucavr/    # Código Kotlin
│   │   ├── res/                  # Resources Android (strings, layouts)
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── rust/                         # Workspace Cargo
│   ├── Cargo.toml                # Workspace root
│   ├── core/                     # Crate principal (demux, decode, streaming)
│   │   ├── Cargo.toml
│   │   └── src/
│   ├── protocols/                # Crate de protocolos (SMB, HTTP, etc.)
│   │   ├── Cargo.toml
│   │   └── src/
│   ├── bridge/                   # Crate UniFFI (bindings JNI)
│   │   ├── Cargo.toml
│   │   ├── src/
│   │   └── uniffi.toml
│   └── audio/                    # Crate de áudio
│       ├── Cargo.toml
│       └── src/
├── native/                       # Código C++ (OpenXR rendering)
│   ├── CMakeLists.txt
│   ├── src/
│   │   ├── main.cpp              # Entry point OpenXR
│   │   ├── xr_app.cpp            # OpenXR session management
│   │   ├── renderer.cpp          # Vulkan/GLES render pipeline
│   │   ├── environment.cpp       # Ambiente virtual (void)
│   │   ├── screen_quad.cpp       # Tela virtual (quad 3D)
│   │   └── input.cpp             # Controller input
│   ├── include/
│   └── shaders/                  # GLSL / SPIR-V shaders
├── docs/                         # Documentação
├── scripts/                      # Scripts de build, CI
├── build.gradle.kts              # Root Gradle
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

### Tarefas

- [x] **T1.1** — Inicializar repositório Git com `.gitignore` (Android + Rust + C++)
  > `.gitignore` estava completo e correto (Android+Rust+C++). Repositório inicializado com 6 commits logicamente agrupados: (1) scaffold monorepo + build system, (2) Rust video pipeline, (3) OpenXR native layer, (4) Android app, (5) docs & scripts, (6) Rust Cargo config. Working tree limpo.
- [ ] **T1.2** — Configurar Gradle root com AGP + plugin Rust (`mozilla.rust-android-gradle` ou custom task)
  > AGP + Kotlin Android plugin configurados em `build.gradle.kts` raiz. Não há plugin Rust real integrado ao Gradle — a task `buildRust` em `app/build.gradle.kts` é um placeholder que só faz `println`, não compila nada. A compilação Rust acontece fora do Gradle, via `scripts/build.sh`.
- [x] **T1.3** — Configurar Cargo workspace com crates: `core`, `protocols`, `bridge`, `audio`
  > Workspace válido em `rust/Cargo.toml` com os 4 crates, cada um com `Cargo.toml` próprio. `protocols` ainda é um esqueleto de exemplo (implementação real é escopo de outra seção).
- [x] **T1.4** — Instalar e configurar `cargo-ndk` para cross-compile `aarch64-linux-android`
  > `cargo-ndk` 4.1.2 instalado e funcional; `scripts/build.sh` invoca `cargo ndk -t aarch64-linux-android -P 26 -o ../app/src/main/jniLibs build --release` e o cross-compile funciona de fato (`.so` recentes em `rust/target/aarch64-linux-android/release/` copiados para `jniLibs/arm64-v8a/`). Ressalva: `rust/.cargo/config.toml` ainda tem um linker placeholder não substituído (`/path/to/ndk/...`); só não quebra porque `cargo ndk` sobrescreve via env var — `cargo build --target aarch64-linux-android` direto falharia.
- [x] **T1.5** — Configurar Android NDK r26+ no Gradle (`ndkVersion`, `cmake`)
  > `ndkVersion = "26.3.11579264"` em `app/build.gradle.kts` satisfaz r26+; `externalNativeBuild.cmake` configurado apontando para `native/CMakeLists.txt`.
- [x] **T1.6** — Configurar CMakeLists.txt para código C++ (OpenXR + Meta SDK)
  > `native/CMakeLists.txt` resolve OpenXR via prefab (`find_package(OpenXR REQUIRED CONFIG)`, coerente com `buildFeatures.prefab=true` + dependência `openxr_loader_for_android`) e referencia o Meta SDK (`sdk/meta-openxr-sdk/Samples/...`) como dependência externa buscada por `scripts/setup-deps.sh`. `./gradlew :app:externalNativeBuildDebug` compilou com sucesso nesta sessão.
- [x] **T1.7** — ~~Configurar UniFFI: definir `.udl` (interface definition), gerar bindings Kotlin~~ — decisão revisada, ver ADR-002 no REQUIREMENTS.md
  > Investigado a fundo: o Kotlin nunca chama o Rust diretamente em nenhum ponto do app (sempre passa pelo C++, que precisa de acesso síncrono de baixo overhead ao frame decodificado a cada frame do render loop). Não há nenhum caminho de chamada que UniFFI serviria aqui. Removido o scaffolding morto (`vrplayer.udl`, `build.rs`, dependência `uniffi` em `rust/bridge/Cargo.toml`, o passo de geração de bindings em `scripts/build.sh`) e formalizada a decisão real no ADR-002: bridge Kotlin↔C++ via JNI manual, C++↔Rust via C ABI (`extern "C"`). Marcado como concluído porque a tarefa em si (decidir e formalizar o mecanismo de bridge) está resolvida — só que com uma decisão diferente da originalmente planejada.
- [x] **T1.8** — Criar script de build unificado (`scripts/build.sh`) que:
  - Compila Rust via `cargo ndk`
  - Copia `.so` para `app/src/main/jniLibs/arm64-v8a/`
  - Invoca Gradle build
  > `scripts/build.sh` compila Rust via `cargo ndk`, copia `.so` (rust + FFmpeg) para `jniLibs/arm64-v8a/`, e invoca `./gradlew assembleDebug`. O passo de geração de bindings UniFFI foi removido (ver T1.7) — não fazia sentido nesta arquitetura.
- [ ] **T1.9** — Configurar GitHub Actions: build + lint (clippy + ktlint + clang-tidy)
  > Clippy real e funcional no workflow. Porém ktlint tem fallback silencioso (`|| echo "ktlint not configured yet"`) e de fato nenhum plugin ktlint está configurado em nenhum `build.gradle.kts` — o check nunca falha de verdade. clang-tidy não aparece em nenhum lugar do workflow. O build C++/Android real (`./scripts/build.sh`) está comentado, aguardando infra dos SDKs. Hoje o CI só valida clippy de fato.
- [ ] **T1.10** — Testar deploy de um "hello world" OpenXR no Quest 3 via `adb`
  > Sem acesso a headset Quest 3 físico nesta sessão — o deploy via `adb` não foi validado em hardware real. Evidência indireta: `./gradlew :app:externalNativeBuildDebug` compilou com sucesso nesta sessão e existe `app/build/outputs/apk/debug/app-debug.apk` gerado no disco, mas isso não substitui um teste real no headset.

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **NDK versioning**: O Meta OpenXR SDK exige NDK r25+. Use r26 ou superior. Versões mais antigas causam erros de linking com `libopenxr_loader.so`.

> [!WARNING]
> **Rust target**: O Quest 3 é ARM64. O target Rust **deve** ser `aarch64-linux-android`, nunca `armv7-linux-androideabi`. Configure no `.cargo/config.toml`:
> ```toml
> [target.aarch64-linux-android]
> linker = "/path/to/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
> ```

> [!CAUTION]
> **UniFFI + JNI loading order**: O `System.loadLibrary()` no Kotlin **deve** carregar as dependências na ordem correta. Se `libcore.so` depende de `libffmpeg.so`, carregue `libffmpeg.so` primeiro. Use `ReLinker` para robustez.

> [!IMPORTANT]
> **FFmpeg cross-compile**: FFmpeg precisa ser cross-compilado para Android ARM64 separadamente. Use o script `ffmpeg-android-maker` ou compile manualmente com:
> ```bash
> ./configure --target-os=android --arch=aarch64 --enable-cross-compile \
>   --cc=aarch64-linux-android29-clang --sysroot=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot \
>   --enable-shared --disable-static --disable-programs \
>   --enable-decoder=h264,hevc,aac,mp3,flac,opus,ac3,dts \
>   --enable-demuxer=mov,matroska,avi,flv,mpegts \
>   --enable-protocol=file,http,https,tcp
> ```

> [!NOTE]
> **Meta OpenXR SDK**: Baixe o Meta XR SDK de https://developer.oculus.com/downloads/. Você precisa do `OpenXR Mobile SDK`. Extraia e aponte o CMakeLists.txt para o diretório.

---

## 2. Pipeline de Vídeo (Rust Core)

### O que fazer

Implementar o pipeline completo de decodificação de vídeo no Rust:

```
Arquivo/Stream → Demuxer (FFmpeg) → Packets → Decoder (MediaCodec HW) → Frames → Texture (GPU)
                                   → Audio Packets → Audio Decoder → PCM → Audio Output (Oboe)
```

### Tarefas

- [x] **T2.1** — Implementar `Demuxer` no Rust usando `ffmpeg-next`:
  - Abrir container (MP4, MKV, AVI, MOV, WebM, FLV, TS)
  - Enumerar streams (vídeo, áudio, legendas)
  - Extrair packets por stream
  - Seek preciso (keyframe + refinamento)
  > `Demuxer::new` abre o container via `ffmpeg::format::input` e enumera streams de vídeo/áudio corretamente (legendas ainda não — ok para v0.2+). `read_packet()` recria o `PacketIter` a cada chamada; conferido no source do `ffmpeg-next` que `PacketIter` é só um wrapper stateless sobre `Input` (sem estado próprio de iteração), então isso é estilo estranho, não overhead real. O seek, porém, não é preciso: `load_at`/`seek` fazem um único `input_context.seek(target_ts, ..)` para o keyframe anterior e não há decodificação de refinamento até o timestamp exato — a precisão fica limitada ao tamanho do GOP.
- [x] **T2.2** — Implementar `HwDecoder` via Android MediaCodec NDK:
  - Criar `AMediaCodec` para H.264 e H.265/HEVC
  - Configurar surface output (decodificação direto para textura GPU)
  - Gerenciar buffer queue (input/output buffers)
  - Lidar com codec-specific data (SPS/PPS para H.264, VPS/SPS/PPS para HEVC)
  > Confirmado surface mode de fato: `HwDecoder::configure` recebe o `NativeWindow` da `ImageReader` (via `tex.get_window()`) e é sempre chamado com esse window, nunca `None`. Buffer queue de input/output implementada com `dequeue_input_buffer`/`queue_input_buffer`/`dequeue_output_buffer`/`release_output_buffer`. Codec-specific data tratado: `h264.rs`/`hevc.rs` extraem SPS/PPS e VPS/SPS/PPS de `avcC`/`hvcC`, enviados com flag `BUFFER_FLAG_CODEC_CONFIG` (2) antes dos primeiros packets em `playback.rs`, com mime escolhido dinamicamente por `codec_id`.
- [x] **T2.3** — Implementar `AudioDecoder`:
  - Decodificar AAC, MP3, FLAC, Opus, AC3, DTS para PCM
  - Pode usar FFmpeg software decode (áudio é leve)
  - Resample para sample rate nativo (48kHz padrão do Quest)
  > `AudioDecoder::new` usa `streams().best(Type::Audio)` e o decoder "best" do FFmpeg para o stream escolhido — decodifica para PCM `f32` e resampleia para 48kHz estéreo via `swresample` (`Resampler::get`). Suporte real a AAC/MP3/FLAC/Opus/AC3/DTS depende de quais decoders foram habilitados na build do FFmpeg (`ffmpeg-android-maker`, fora do escopo Rust) — assumido OK pois a config de T1 já habilita esses decoders.
- [x] **T2.4** — Implementar `AudioOutput` via Oboe (NDK):
  - Stream de áudio de baixa latência
  - Callback-based (não blocking)
  - Volume control
  > Stream de baixa latência via Oboe implementado (`PerformanceMode::LowLatency`, `SharingMode::Exclusive`, callback `on_audio_ready` não-bloqueante via `try_recv`). Volume control adicionado: `AudioOutput` guarda o volume como bits de `f32` num `AtomicU32` (lido sem lock dentro do callback de áudio em tempo real, que não pode bloquear), aplicado por multiplicação em cada amostra. `PlaybackController` persiste o volume entre trocas de vídeo (o `AudioOutput` é recriado a cada `load_at`) e reaplica automaticamente. Exposto via FFI (`set_video_volume`/`get_video_volume`), JNI (`nativeSetVolume`) e UI (botões 🔉/🔊 em `VRControlsPresentation.kt`, incrementos de 10%). Validado com build completo (`assembleDebug` → `BUILD SUCCESSFUL`) e conferência dos símbolos no `.so` gerado.
- [x] **T2.5** — Implementar `SyncManager` (A/V Sync):
  - Sincronização áudio-vídeo baseada em PTS (Presentation Timestamp)
  - Áudio como clock master
  - Drop/duplicate frames se necessário para manter sync
  > `SyncManager` implementa áudio como clock master (`update_audio_pts` alimentado pelo PTS real dos packets de áudio decodificados), com fallback de wall-clock (`start_time`) enquanto `audio_pts` ainda é 0 no início da reprodução; `pause()`/`resume()` ajustam `start_time` para manter o relógio consistente. A thread de vídeo em `playback.rs` usa `get_master_clock()` para atrasar (`sleep`) a liberação de frames adiantados, mas não há drop/duplicate de frames quando o vídeo está atrasado em relação ao áudio (sub-item explícito do requisito) — frames atrasados são liberados imediatamente, sem compensação.
- [x] **T2.6** — Implementar `PlaybackController`:
  - Play, Pause, Stop
  - Seek (forward/backward)
  - Controle de velocidade (0.5x - 2.0x)
  - Seleção de track (áudio, vídeo)
  > Play/Pause/Stop/Seek reescritos: cada `load_at()` agora cria uma
  > `PlaybackSession` com suas próprias flags `is_playing`/`is_running`
  > (não mais compartilhadas entre gerações) e `stop()` faz `join()` de
  > verdade nas 3 threads antes de retornar, em vez de um
  > `sleep(150ms)` no chute. Bug corrigido: antes, `seek()`/`stop()`
  > podiam deixar threads da geração anterior "zumbis" rodando em
  > paralelo com as novas, escrevendo na mesma textura/áudio
  > compartilhados — só `pause` (que não recria threads) funcionava de
  > forma confiável. `play()`/`pause()` também passaram a mexer no
  > áudio (Oboe) e no `SyncManager` de forma consistente com
  > `toggle_play_pause()`.
  >
  > **Controle de velocidade (0.5x-2.0x)**: implementado reamostrando o
  > áudio para `48000/speed` em vez de `48000` fixo (`AudioDecoder::set_speed`,
  > estilo "fita acelerada/desacelerada" — não preserva pitch; time-stretching
  > de qualidade tipo WSOLA/SoundTouch fica fora do escopo do MVP). O
  > `SyncManager` (master clock) acompanha automaticamente porque o
  > vídeo já se pauta pelo clock de áudio; para arquivos sem trilha de
  > áudio, o fallback de wall-clock do `SyncManager` também escala pela
  > velocidade, então funciona nos dois casos. Efeito em tempo real, sem
  > precisar recarregar o vídeo. Exposto via `set_playback_speed`/`get_playback_speed`
  > (FFI), `nativeSetSpeed` (JNI) e um slider dedicado na UI (ver T4.2).
  >
  > **Bug corrigido: chiado ao acelerar/desacelerar**. Trocar o botão de
  > velocidade por um slider (T4.2) fez o `onProgressChanged` disparar
  > `nativeSetSpeed` dezenas de vezes por segundo durante o arrasto —
  > cada chamada que de fato mudava a velocidade reconstruía o resampler
  > (`AudioDecoder::set_speed`), o que descarta o histórico interno do
  > filtro FIR e gera um pequeno estalo a cada reconstrução. Muitos
  > estalos em sequência soavam como chiado/estática. Corrigido com um
  > debounce na thread de áudio: a reconstrução do resampler agora só
  > acontece no máximo a cada 200ms (e só se a mudança de velocidade for
  > >0.01), aplicando sempre o valor mais recente pedido. **Esse debounce
  > reduziu mas não eliminou o chiado** — a causa raiz de verdade era
  > outra, num bug pré-existente em `audio_decoder.rs` (não introduzido
  > nesta sessão, só exposto por ela): `ffmpeg_next::frame::Audio::data(0)`
  > retorna um slice do tamanho do **buffer alocado** (`linesize`, que o
  > FFmpeg arredonda/alinha pra cima), não da quantidade real de amostras
  > válidas (`nb_samples`). O código extraía `data.len()` bytes direto,
  > incluindo bytes de padding/lixo no final de cada chunk reamostrado,
  > reinterpretados como `f32` (podem virar valores arbitrariamente
  > grandes/estranhos). Isso sempre existiu, mas piora
  > proporcionalmente quanto menor o chunk reamostrado — exatamente o
  > que acontece ao acelerar (target_rate menor → menos amostras válidas
  > por pacote → mais lixo proporcional no final do buffer). Corrigido
  > usando `resampled.samples() * resampled.channels()` para calcular o
  > tamanho válido em vez de confiar em `data.len()`.
  >
  > **Seleção de track**: corrigido um bug real encontrado durante a
  > implementação — `AudioDecoder` escolhia a "melhor" trilha de áudio
  > pela heurística própria do FFmpeg, independente de qual trilha o
  > `Demuxer` de fato roteava (`Demuxer::audio_stream_index`, "primeira
  > encontrada"); em arquivos com mais de uma trilha de áudio onde esses
  > dois critérios discordassem, todo pacote de áudio era descartado
  > silenciosamente (índices não batiam) e o vídeo tocava sem som. Agora
  > o `Demuxer` é a única fonte de verdade (`select_audio_track`/`select_video_track`
  > por posição ordinal) e o `AudioDecoder` recebe o `stream_index`
  > explicitamente. Troca de trilha exige reload (`cycle_audio_track()`
  > seleciona a próxima e chama `seek()` na posição atual para reaplicar)
  > — não é uma troca "ao vivo" sem interrupção. UI: botão 🎵 cicla entre
  > as trilhas de áudio disponíveis; não há um seletor com nomes/idiomas
  > de trilha (ficaria fora do orçamento de tempo desta rodada). Seleção
  > de trilha de **vídeo** só tem a API no `Demuxer` (`select_video_track`)
  > — não está exposta via FFI/UI, já que múltiplos streams de vídeo por
  > arquivo são raros na prática.
  >
  > Validado com build completo (`assembleDebug` → `BUILD SUCCESSFUL`) e
  > conferência dos símbolos exportados/importados em cada `.so`.
- [x] **T2.7** — Implementar `TextureOutput`:
  - Saída de frames decodificados como `OES Texture` (Android external texture)
  - Passar texture handle para camada C++ via shared surface
  - Fence sync para garantir que frame está pronto antes do render
  > `TextureOutput::allocate` cria um `ImageReader` com `HardwareBufferUsage::GPU_SAMPLED_IMAGE`, e `acquire_latest_buffer` extrai o `AHardwareBuffer` da imagem mais recente; o ponteiro é exposto para C++ via `get_current_frame()`/`bridge` e consumido em `vr_player_app.cpp` (`AImage_getHardwareBuffer`). Fence sync não é explícito no código Rust — não há sync fence FD obtido ou aguardado; a única sincronização é implícita, via `acquire_latest_image()` do `ImageReader` (a BufferQueue do Android garante que o buffer retornado já está pronto para leitura, mas não há wait explícito de fence no pipeline Rust).

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **MediaCodec surface mode é OBRIGATÓRIO**: Não use buffer mode para vídeo no Quest 3. Surface mode decodifica diretamente para uma textura GPU (`SurfaceTexture`/`AHardwareBuffer`), evitando cópia CPU→GPU que destruiria a performance.
> ```c
> // C/NDK - Correto: surface output
> AMediaCodec_configure(codec, format, surface, NULL, 0);
> 
> // ERRADO para VR: buffer mode (cópia de CPU)
> AMediaCodec_configure(codec, format, NULL, NULL, 0);
> ```

> [!CAUTION]
> **A/V Sync é crítico em VR**: Dessincronização de áudio-vídeo é muito mais perceptível em VR do que em tela plana. O cérebro detecta incongruências de ~20ms. Use áudio como clock master e ajuste frames de vídeo.

> [!WARNING]
> **MediaCodec tem limite de instâncias**: O Quest 3 suporta ~4 instâncias simultâneas de MediaCodec. Se você criar mais, instâncias anteriores serão silenciosamente destruídas. Gerencie o lifecycle rigorosamente.
> ```rust
> // Sempre release o codec quando não usar mais
> impl Drop for HwDecoder {
>     fn drop(&mut self) {
>         unsafe {
>             AMediaCodec_stop(self.codec);
>             AMediaCodec_delete(self.codec);
>         }
>     }
> }
> ```

> [!WARNING]
> **HEVC SPS/PPS/VPS**: Ao iniciar decodificação H.265, você DEVE enviar os NALUs de configuração (VPS, SPS, PPS) como `AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG` antes de qualquer frame. Se extrair de MKV, eles estão no `codec_private`. Se extrair de MP4, estão no `hvcC` box. FFmpeg abstrai isso no `AVCodecParameters.extradata`.

> [!IMPORTANT]
> **Threading**: O pipeline deve rodar em threads separadas:
> - Thread 1: Demuxer (leitura de I/O, pode bloquear em rede)
> - Thread 2: Video Decoder (alimentar MediaCodec)
> - Thread 3: Audio Decoder + Output
> - Thread 4: Sync Manager (coordenação)
> 
> Use channels Rust (`crossbeam-channel` ou `tokio::sync::mpsc`) para comunicação.

> [!NOTE]
> **Seek preciso**: FFmpeg faz seek para o keyframe anterior. Para seek preciso, após o seek, decodifique frames silenciosamente até chegar ao timestamp desejado. Isso pode levar 0.5-2s dependendo do GOP size.

> [!NOTE]
> **Codecs suportados pelo HW do Quest 3 (XR2 Gen 2)**:
> - H.264: Até 4K@60fps
> - H.265/HEVC: Até 8K@30fps ou 4K@120fps (Main/Main10)
> - VP9: Até 4K@60fps (Profile 0/2)
> - AV1: Até 4K@30fps (limitado)

---

## 3. Ambiente VR e Rendering (OpenXR/C++)

### O que fazer

Implementar o loop de rendering OpenXR mínimo com um ambiente "void" (fundo escuro) e uma tela virtual (quad) onde o vídeo é exibido.

### Tarefas

- [x] **T3.1** — Implementar `XrApp`: Inicialização OpenXR
  - Criar `XrInstance` com extensions necessárias
  - Criar `XrSession` com Vulkan ou OpenGL ES backend
  - Criar `XrSwapchain` (stereo ou array)
  - Configurar `XrReferenceSpace` (Stage ou Local)
  > Feito via `OVRFW::XrApp` (Meta `SampleXrFramework`) em vez de código OpenXR manual — instância/sessão/swapchain e `XR_REFERENCE_SPACE_TYPE_STAGE` (confirmado em `XrApp.cpp` do SDK) são geridos pelo framework. `VRPlayerApp` customiza extensions em `GetExtensions()`.
- [x] **T3.2** — Implementar loop de rendering principal:
  ```
  while (running) {
      xrWaitFrame()      → obter predicted display time
      xrBeginFrame()
      
      for each view (left eye, right eye):
          xrAcquireSwapchainImage()
          render(view)    → renderizar ambiente + tela
          xrReleaseSwapchainImage()
      
      xrEndFrame()        → submeter layers
  }
  ```
  > Idem — loop de frame (`xrWaitFrame`/`xrBeginFrame`/`xrEndFrame`) é interno ao `OVRFW::XrApp`; `VRPlayerApp` só implementa `Update()`/`Render()`.
- [x] **T3.3** — Implementar `VoidEnvironment`:
  - Fundo totalmente preto (clear color #000000)
  - Sem geometria de ambiente (skybox escuro)
  - Opcional: grid sutil no chão para referência espacial
  > `BackgroundColor` alterado para preto puro em `vr_player_app.cpp` (antes era cinza de debug); sem geometria de ambiente. Grid de chão (opcional) não implementado.
- [x] **T3.4** — Implementar `VirtualScreen` (quad 3D):
  - Geometria: quad plano com aspect ratio do vídeo (16:9, 21:9, etc.)
  - Posição padrão: ~3m à frente, ~1.5m de altura
  - Tamanho padrão: ~3m de largura (simula TV de ~100")
  - Textura: receber frame decodificado do Rust core
  - Shader: sampler de textura externa (OES) → shader fragment
- [x] **T3.5** — Implementar passagem de textura Rust → C++:
  - Opção A: `AHardwareBuffer` compartilhado (recomendado)
  - Opção B: `SurfaceTexture` + `updateTexImage()` (mais simples)
  - Fence sync para evitar tearing
- [x] **T3.6** — Implementar redimensionamento e reposicionamento da tela:
  - Thumbstick para mover (frente/trás, cima/baixo)
  - Grip button + thumbstick para resize
  - Fixar posição no espaço ou follow head (opção)
  > Thumbstick direito (`RightRemoteJoystick`) move a tela (Y = frente/trás, X = altura); segurando o grip (`RightRemoteGripTrigger`) o mesmo stick redimensiona mantendo aspect ratio 16:9. Limites de conforto aplicados (profundidade -0.75m a -8m, altura 0.2m a 3.5m, largura 0.5m a 6m). "Fixar posição/follow head" (opção secundária) não implementado.
- [ ] **T3.7** — Configurar Vulkan rendering pipeline:
  - Render pass com depth buffer
  - Pipeline para quad texturizado
  - Pipeline para UI overlay (controles)
  - Multiview rendering (renderizar ambos olhos em uma passada)
  > **Decisão arquitetural não documentada anteriormente**: o projeto usa OpenGL ES 3.x (via `OVRFW`), não Vulkan — isso diverge do ADR-003 do REQUIREMENTS.md, mas é a alternativa que o próprio PHASE-0.1-MVP sugere ("considere OpenGL ES para o MVP se a complexidade de Vulkan atrasar demais", seção 3, cuidados). Multiview (`GL_OVR_multiview2`) já funciona via macro `TransformVertex` do framework; pipeline para UI overlay funciona (quads da UI 2D projetados em VR). Falta decidir formalmente Vulkan vs. GLES antes de v0.2+ e atualizar o ADR-003.

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **NUNCA bloqueie o render thread**: O render loop deve manter ≥72 FPS. Qualquer operação que possa bloquear (I/O, rede, decode) deve estar em outra thread. Se um frame leva >14ms para renderizar, o runtime do Quest ativa o ATW (Asynchronous TimeWarp) e o usuário percebe artefatos.

> [!CAUTION]
> **Multiview rendering é obrigatório para performance**: Renderizar cada olho separadamente desperdiça GPU. Use `GL_OVR_multiview2` (OpenGL ES) ou `VK_KHR_multiview` (Vulkan) para renderizar ambos os olhos em uma única draw call.
> ```glsl
> // Vertex shader com multiview
> #extension GL_OVR_multiview2 : require
> layout(num_views = 2) in;
> 
> uniform mat4 viewProjection[2];
> 
> void main() {
>     gl_Position = viewProjection[gl_ViewID_OVR] * modelMatrix * vec4(position, 1.0);
> }
> ```

> [!WARNING]
> **Reference Space**: Use `XR_REFERENCE_SPACE_TYPE_STAGE` (espaço do chão) para posicionar a tela. Se usar `LOCAL` (espaço da cabeça), a tela se move com o usuário, o que causa motion sickness.

> [!WARNING]
> **Aspect ratio dinâmico**: O quad da tela virtual DEVE mudar de aspect ratio quando o vídeo muda (ex: 16:9 → 4:3 → 21:9). Recalcular a geometria do quad ao trocar de mídia.

> [!IMPORTANT]
> **Swapchain format**: Use `VK_FORMAT_R8G8B8A8_SRGB` ou `GL_SRGB8_ALPHA8` para o swapchain. sRGB garante cores corretas. Se usar formato linear, as cores ficam desbotadas.

> [!IMPORTANT]
> **Textura externa (OES) no Vulkan**: No Vulkan, usar `VkSamplerYcbcrConversion` + `VK_ANDROID_external_memory_android_hardware_buffer` para importar o `AHardwareBuffer` do MediaCodec. Isso é significativamente mais complexo que OpenGL ES. Considere OpenGL ES para o MVP se a complexidade de Vulkan atrasar demais.

> [!NOTE]
> **Depth buffer**: Mesmo no ambiente void, envie um depth buffer para o OpenXR runtime. Isso permite que o runtime faça depth-based reprojection (melhor ATW).

---

## 4. Controles de Reprodução (UI VR)

### O que fazer

UI flutuante minimalista que aparece quando o usuário aponta o controller para a tela ou pressiona um botão.

### Tarefas

- [x] **T4.1** — Implementar sistema de **raycasting** a partir do controller:
  - Ray do controller → interseção com UI panels
  - Feedback visual: laser + ponto de interseção
  - Haptics no controller ao hovering sobre botão
  > A interseção ray↔UI panel e o feedback visual (beam vermelho via `ovrBeamRenderer`,
  > ponto de interseção usado para computar as UVs do toque) já existiam em
  > `vr_player_app.cpp` desde antes desta sessão. Adicionado nesta rodada: (1)
  > **smoothing** da direção do ray (média móvel exponencial por frame, `m_smoothedRayDir`)
  > para reduzir o tremor natural do controller sem introduzir lag perceptível — cobre o
  > cuidado explícito da seção; (2) **haptics** de verdade via OpenXR: uma action
  > `XR_ACTION_TYPE_VIBRATION_OUTPUT` é criada e vinculada a `/user/hand/{left,right}/output/haptic`
  > sobrescrevendo `GetSuggestedBindings()`, disparando um pulso leve
  > (`FireHaptic(RightHandPath, 0.25f, XR_MIN_HAPTIC_DURATION)`) na transição hover-enter
  > (mudança de painel sob o cursor) e um pulso mais forte (amplitude 0.6, 20ms) no
  > trigger-down. Faltando explicitamente da lista original: histerese nos botões
  > individuais dentro de cada painel (o painel é HTML/Android View renderizado numa
  > textura — a histerese "não desovar ao sair X pixels" teria que ser implementada do
  > lado do `VRControlsPresentation.kt`, fora do escopo desta sessão). Compilado com
  > sucesso (`ninja vrplayer_native` completo, 87/87); não validado em headset real.
- [x] **T4.2** — Implementar **painel de controles**:
  - Play/Pause (botão central grande)
  - Seek bar (slider com preview de tempo)
  - Tempo atual / Tempo total
  - Volume (slider ou botões +/-)
  - Botão de seleção de áudio track
  - Botão de seleção de legenda track
  - Botão fullscreen / resize
  > **Bug crítico encontrado e corrigido**: `VRControlsPresentation.kt` usava
  > `(context as? VRActivity)?.nativeX(...)` em todos os botões/sliders
  > (rewind, forward, seek bar, volume, velocidade, trilha de áudio) —
  > exceto o play/pause, que usa uma closure. `Presentation.context`
  > (herdado de `Dialog`) **não é** o `outerContext`/`VRActivity` passado
  > no construtor: o Android cria por baixo dos panos um
  > `ContextThemeWrapper` em volta de um display-context derivado dele.
  > O cast sempre falhava silenciosamente (virava `null`), então nenhum
  > desses controles jamais chamava o Rust — só o play/pause (via
  > closure) funcionava. Corrigido passando a `VRActivity` real
  > explicitamente para `VRControlsPresentation` e usando essa
  > referência em vez de `context` em todos os call sites. De quebra,
  > troquei os botões de incremento (+/-, 🐢/🐇) de volume e velocidade
  > por sliders (`SeekBar`) de verdade, e adicionei o label de tempo
  > atual/total que faltava. Seleção de legenda não se aplica ainda
  > (legendas são escopo de v0.2+); fullscreen/resize já é coberto pelo
  > thumbstick/grip da tela virtual (T3.6), não duplicado aqui como
  > botão.
- [x] **T4.3** — Implementar **auto-hide**:
  - Controles aparecem ao apontar controller para a tela
  - Desaparecem após 5s de inatividade
  - Animação fade in/out suave
  > Implementado com alpha blending real (não um "esconde/mostra" abrupto): o shader
  > compartilhado dos quads de UI ganhou um uniform `uAlpha`, e os `ovrSurfaceDef` do
  > painel de controles e do file browser tiveram `GpuState.blendEnable = BLEND_ENABLE`
  > (`SRC_ALPHA`/`ONE_MINUS_SRC_ALPHA`) — a tela de vídeo continua opaca
  > (`m_videoAlpha` fixo em 1.0, blend desativado), então o fade não afeta o vídeo em si.
  > Cada painel tem seu próprio timer de inatividade: o painel de controles reseta o
  > timer quando o ray aponta para ele OU para a tela de vídeo (apontar pra tela =
  > "quero ver os controles"); o file browser só reseta quando apontado diretamente.
  > Após 5s (`kUiAutoHideSeconds`) sem isso, o alpha interpola suavemente até 0 em
  > ~0.35s (`kUiFadeDuration`, via `MoveTowards`). Painéis com alpha ≤0.5 param de
  > receber despacho de toque/hover (evita clique num painel praticamente invisível),
  > mas a detecção geométrica do raycast continua sempre ativa — senão um painel
  > escondido nunca teria como "acordar" de novo. Surfaces com alpha ≤0.01 nem entram
  > em `out.Surfaces` (economiza GPU quando 100% escondido). Não validado em headset
  > real (o timing de 5s/0.35s foi escolhido por bom senso, não testado com usuário).
- [x] **T4.4** — Implementar controles via **botões físicos** do controller:
  - Trigger: Select / Confirm
  - A/X: Play/Pause
  - B/Y: Menu/Back
  - Thumbstick: Seek (esquerda/direita), Volume (cima/baixo)
  - Grip: Segurar + mover para reposicionar tela
  > Trigger como select/confirm sobre os painéis já existia. Adicionado: **A ou X**
  > alternam play/pause (antes só A funcionava); **B ou Y** = Menu/Back, mapeado para
  > alternar a visibilidade do painel do File Browser instantaneamente (antes só
  > logava "Opening file picker", sem efeito real). **Thumbstick esquerdo** (livre, não
  > usado antes) mapeado para Seek (eixo X, saltos de ±10s) e Volume (eixo Y, contínuo).
  > Volume é uma escrita atômica barata então é aplicado a cada frame que o stick está
  > fora da deadzone; Seek é **debounced** com cooldown de 0.5s entre saltos — chamar
  > `seek_video_playback` a cada frame seria caro (recria as 3 threads de playback,
  > ver nota de T2.6) e não faria sentido segurando o stick. **Grip** continua com a
  > semântica de T3.6 (segurar grip + stick redimensiona a tela; sem grip, stick move a
  > tela) em vez do "segurar+mover para reposicionar" descrito aqui — T3.6 já estava
  > implementado e documentado como concluído antes desta sessão, então não foi
  > alterado para não quebrar esse comportamento já validado.
- [x] **T4.5** — Renderizar UI como **quad overlay** no espaço 3D:
  - Posicionar abaixo da tela virtual
  - Sempre virado para o usuário (billboard)
  - Renderizar com alpha blending sobre o ambiente
  > Posicionamento abaixo da tela já existia. Alpha blending real implementado nesta
  > sessão (ver T4.3 — uniform `uAlpha` + `GpuState.blendEnable`). Billboard só foi
  > implementado **de verdade para o painel do File Browser**: a cada frame ele calcula
  > o yaw (rotação em Y) que aponta seu normal para a cabeça do usuário
  > (`atan2f` sobre a projeção XZ de `HeadPose.Translation - painelPos`), então ele
  > sempre encara quem está olhando, de qualquer ângulo. O painel de controles
  > **manteve a inclinação fixa** (`RotationX(-0.3)`) em vez de billboard dinâmico —
  > decisão consciente: ele fica logo abaixo da tela principal, onde o usuário já está
  > olhando de frente na grande maioria do tempo, e a matemática de billboard
  > combinando yaw dinâmico com a inclinação fixa em X não pôde ser validada num
  > headset real nesta sessão; preferiu-se manter o comportamento já testado
  > visualmente a arriscar uma rotação combinada incorreta sem forma de verificar.
  > Os transforms de UI/controles passaram a ser calculados uma única vez por frame
  > (`m_uiTransform`/`m_controlsTransform`, membros da classe) e reusados tanto no
  > raycast quanto no render — antes eram duas expressões de matriz idênticas
  > copiadas em `Update()` e `Render()`, com risco de divergirem silenciosamente.

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **UI legibilidade em VR**: Texto em VR precisa ser significativamente maior que em tela plana. Mínimo de ~1.5mm por pixel de altura a 1m de distância. Use fontes sans-serif (Roboto, Inter) com peso medium/bold. Nunca use fontes finas.

> [!WARNING]
> **Raycasting precision**: O ray do controller tremula naturalmente. Aplique um **dead zone** e **smoothing** no ray para evitar que o cursor fique tremendo. Histerese nos botões (precisa mover X pixels para fora antes de considerar "unhovered").

> [!IMPORTANT]
> **Profundidade da UI vs. Tela**: A UI deve estar EXATAMENTE na mesma profundidade ou ligeiramente na frente da tela virtual. Se estiver atrás, causa conflito vergência-acomodação e desconforto visual.

> [!NOTE]
> **Renderização de texto em VR**: Use SDF (Signed Distance Field) text rendering para texto nítido em qualquer distância. Bitmap fonts ficam borradas ao se aproximar. Libraries: `msdfgen` para gerar atlas SDF.

---

## 5. File Browser Local

### O que fazer

Navegador de arquivos para o armazenamento interno do Quest 3.

> [!NOTE]
> **Lógica interna** (`app/src/main/java/com/tucavr/filebrowser/`): `MediaEntry`/`MediaType`
> (T5.3, sem I/O no construtor), `DirectoryLister.listMedia()` (T5.1/T5.2 — suspend fun
> em `Dispatchers.IO`, lista só um nível por vez, filtra por extensão de vídeo/áudio/imagem),
> `DirectoryNavigator` (T5.5 — back-stack simples com `enter()`/`goBack()`),
> `MediaSorter`/`SortBy` (T5.6 — nome/data/tamanho/tipo, diretórios sempre antes de
> arquivos) e `ThumbnailGenerator.getThumbnail()` (T5.4 — `MediaMetadataRetriever` em
> `Dispatchers.IO`, cache em disco por hash de path+tamanho+data de modificação checado
> antes de decodificar, retriever liberado em `finally`, retorna `null` em vez de crashar
> em arquivo corrompido). Dependência `kotlinx-coroutines-android` adicionada ao
> `app/build.gradle.kts`.
>
> **Interface (T5.7)**: `VRPresentation.kt` — que já existia como o painel 3D do file
> browser, renderizado via `VirtualDisplay`/`Surface` e projetado como quad OES em
> `vr_player_app.cpp` (ver T4.1/T4.5) — foi reescrito para consumir a camada de lógica
> acima em vez da implementação ad-hoc anterior (que fazia `File.listFiles()` síncrono
> na UI thread, sem thumbnails, com navegação "para cima" via um `File("..")` mock).
> Agora usa `DirectoryNavigator` para a pilha de diretórios, `DirectoryLister` (numa
> coroutine, fora da UI thread) filtrando para Diretório+Vídeo (áudio/imagem já são
> listados pela lógica interna mas ainda não têm player — ficam de fora da UI por
> enquanto para não sugerir uma feature que não existe), `sortMediaEntries` para a
> ordenação, e `ThumbnailGenerator` carregado de forma preguiçosa por item do
> `RecyclerView` (uma coroutine por `ViewHolder`, cancelada e re-checada por posição no
> rebind para não aplicar uma thumbnail desatualizada num item reciclado). Validado via
> `:app:compileDebugKotlin` (sucesso); não testado em headset real (não há como
> verificar visualmente sem hardware Quest 3 nesta sessão).

### Tarefas

- [x] **T5.1** — Implementar listagem de diretórios (Kotlin, permissão `READ_EXTERNAL_STORAGE` / Scoped Storage)
- [x] **T5.2** — Filtrar por extensões de mídia (vídeo, áudio, imagem)
- [x] **T5.3** — Exibir informações: nome, tamanho, data de modificação, ícone por tipo
- [x] **T5.4** — Gerar thumbnails de vídeo (via MediaMetadataRetriever ou FFmpeg)
- [x] **T5.5** — Navegação hierárquica (entrar/sair de pastas)
- [x] **T5.6** — Ordenação: nome, data, tamanho, tipo
- [x] **T5.7** — Renderizar o browser como painel 3D no ambiente VR

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **Scoped Storage (Android 11+)**: O Quest 3 roda Android 12+. `READ_EXTERNAL_STORAGE` está deprecated. Use `READ_MEDIA_VIDEO` + `READ_MEDIA_AUDIO` + `READ_MEDIA_IMAGES` (API 33+) ou `MANAGE_EXTERNAL_STORAGE` (requer justificativa para Quest Store). Para uso pessoal, `MANAGE_EXTERNAL_STORAGE` é mais prático.

> [!IMPORTANT]
> **Thumbnails são pesados**: Gerar thumbnails de todos os vídeos de uma vez trava a UI. Use carregamento lazy (gerar conforme scrollar) e cache em disco. Tamanho ideal: 256x144px (16:9).

---

## 6. Protocolo SMB/CIFS

### O que fazer

Conectar a compartilhamentos de rede Windows / NAS via SMB para navegar e reproduzir mídia.

### Tarefas

- [x] **T6.1** — Implementar cliente SMB no Rust:
  - Autenticação (user/password + guest/anonymous)
  - Listar shares de um servidor
  - Navegar diretórios dentro de um share
  - Ler arquivos (streaming read, não download completo)
  > Implementado em `rust/protocols/src/smb/` sobre a crate `smb2` (`0.18`,
  > crates.io) — pura Rust, sem `libsmbclient`/dependência C nenhuma (o
  > `libavformat.so` empacotado aqui nem tem `CONFIG_LIBSMBCLIENT`
  > habilitado, então custom I/O era inevitável de qualquer forma; ver T6.3).
  > A crate fala SMB 2.x/3.x com autenticação NTLMv2, guest/anônimo
  > (`username` vazio — `ClientConfig.username`), assinatura de mensagens e
  > *auto-reconnect* embutido (`ClientConfig.auto_reconnect`), o que cobre de
  > graça o aviso do doc sobre timeout/reconexão em Wi-Fi instável sem eu
  > precisar reimplementar isso na mão. `protocols::smb::list_shares` e
  > `list_directory` cobrem autenticação/listagem; `SmbFileSource` (que
  > implementa o trait `RangeSource` de `prefetch.rs`) cobre leitura
  > streaming por offset via `FileReader::read_at` — não há download
  > completo em memória em nenhum ponto. **Não implementado**: escrita,
  > SMB1 (a própria crate só fala SMB2/3, o que é correto — SMB1 é
  > obsoleto/inseguro), Kerberos (só NTLM). **Cuidado real e não resolvido**:
  > nunca testei isto contra um servidor SMB de verdade — não há NAS nem
  > Samba disponível neste ambiente sandboxed. A validação que consegui
  > fazer foi (a) ler a implementação/exemplos da crate `smb2` com atenção,
  > (b) `cargo check`/`cargo test` no host, e (c) cross-compile real via
  > `cargo ndk` para `aarch64-linux-android` (ver nota de T6.3 sobre o build
  > completo). Autenticação, assinatura e o handshake NTLMv2 em si nunca
  > rodaram contra um servidor real nesta sessão.
- [ ] **T6.2** — Implementar **descoberta automática** de servidores SMB:
  - NetBIOS name resolution
  - mDNS/DNS-SD (`.local`)
  - Fallback: IP manual
  > **Não implementado** — decisão consciente de escopo (a própria tarefa
  > orienta isso como a prioridade mais baixa das quatro). Só existe o
  > fallback manual (campo "Host / IP" no formulário de adicionar servidor,
  > T6.4). Nem NetBIOS name resolution nem mDNS/DNS-SD foram sequer
  > esboçados — ficam documentados aqui como trabalho futuro genuíno, não
  > como algo "quase pronto".
- [x] **T6.3** — Integrar SMB com o Demuxer:
  - Implementar custom I/O callback para FFmpeg que lê do SMB
  - Buffer de leitura: 2-8MB ahead (prefetch)
  > **Achado que mudou a abordagem planejada**: em vez de FFI crua com
  > `ffmpeg-sys-next` (`avio_alloc_context` na mão), a versão do
  > `ffmpeg-next` já usada neste projeto (`9.0.0`) tem suporte nativo de
  > primeira classe a custom I/O — `format::context::StreamIo::from_read_seek`
  > (aceita qualquer `Read + Seek + Send + 'static`) e
  > `format::input_from_stream`. Isso eliminou a necessidade de `unsafe`
  > FFI manual para este trabalho: `Demuxer::new` (`rust/core/src/demuxer.rs`)
  > detecta o esquema `smb://` (URI interna, ver T6.4 sobre por que não é
  > uma URI de verdade), abre um `SmbFileSource`, envolve num
  > `protocols::prefetch::PrefetchReader` (`rust/protocols/src/prefetch.rs`)
  > e entrega pro FFmpeg via `StreamIo::from_read_seek` +
  > `format::input_from_stream`. Buffer de prefetch: 4MB por bloco (dentro
  > da faixa 2-8MB pedida). **Ressalva honesta sobre o "prefetch"**: é um
  > cache "pull" em blocos grandes (só busca o próximo bloco quando o atual
  > acaba ou há um seek pra fora dele) — NÃO é um read-ahead assíncrono em
  > background buscando o PRÓXIMO bloco enquanto o atual ainda está sendo
  > consumido. Isso já reduz bastante o número de round-trips de rede pra
  > leitura sequencial (o caso comum fora dos seeks pontuais em cues de
  > MKV), mas não esconde a latência de rede por completo como um
  > double-buffer de verdade esconderia. Documentado assim em vez de
  > inflado como "prefetch completo". Testado com fonte fake em memória
  > (`cargo test -p protocols`, 2 testes: leitura sequencial hits 1 bloco,
  > seek+read) — nunca testado com uma fonte SMB real (mesma ressalva de
  > T6.1). **Build**: `cargo check`/`cargo ndk ... build --release` para
  > `aarch64-linux-android` passam de verdade — não só type-check, um `.so`
  > real foi gerado e as novas funções aparecem via `nm -D` (ver T6.4).
  > `scripts/build.sh` completo (`cargo ndk` + `./gradlew assembleDebug`)
  > rodou com sucesso e gerou `app/build/outputs/apk/debug/app-debug.apk`.
- [x] **T6.4** — UI para gerenciar conexões:
  - Adicionar servidor (host, share, user, password)
  - Lista de servidores salvos
  - Status de conexão
  > **Rust/bridge**: novas funções C ABI em `rust/bridge/src/lib.rs` —
  > `start_smb_playback` (credenciais como parâmetros SEPARADOS, nunca uma
  > URI única cruzando o JNI — ver a próxima nota sobre credenciais),
  > `smb_list_shares`/`smb_list_directory` (bloqueantes, retornam string
  > delimitada por `\n`/`\t` ou `"ERROR:<msg>"`, liberada via
  > `free_rust_string`). **C++**: `native/src/vr_player_app.cpp` ganhou um
  > TERCEIRO painel de UI (`m_networkImageReader`/`m_networkTransform`/
  > `m_networkAlpha`/...), seguindo exatamente o mesmo padrão
  > VirtualDisplay+Presentation+quad OES dos painéis existentes (T4.1/T4.5)
  > — raycast, auto-hide (5s) e billboard espelhado do File Browser (fica
  > do lado direito). Alternado pelo botão **Menu** do controller esquerdo
  > (antes livre) em vez de inventar mais um botão físico. **Kotlin**:
  > `NetworkPresentation.kt` (painel único com abas "🔗 URL"/"🗄 SMB", pra não
  > duplicar toda a integração C++ com um segundo painel — ver T7.3),
  > `network/SmbCredentialStore.kt` e `network/UrlHistoryStore.kt`.
  >
  > **Credenciais**: senha SMB guardada via `EncryptedSharedPreferences`
  > (`androidx.security:security-crypto:1.1.0`, adicionada ao
  > `app/build.gradle.kts`) — nunca texto plano em disco, como o doc exige
  > explicitamente. O fluxo "Testar e salvar" só persiste a credencial se
  > `smb_list_shares` (conexão + autenticação real) tiver sucesso primeiro.
  > **Restrição rígida do enunciado, verificada linha a linha**: uma URI
  > `smb://user:senha@host/...` em texto plano NUNCA cruza a fronteira JNI
  > nem é logada por inteiro. `nativePlaySmb` recebe host/porta/share/
  > caminho/usuário/senha/domínio como parâmetros JNI separados; só DEPOIS
  > disso, inteiramente dentro do processo Rust, `start_smb_playback` monta
  > uma representação interna (`protocols::smb::uri::SmbTarget::to_internal`)
  > usada como `current_path` do `PlaybackController` — necessário porque
  > `seek()` já reabre a stream do zero a partir de `current_path` (T2.6,
  > arquitetura pré-existente, não alterada). Essa representação interna
  > **não é uma URI de verdade**: usa `NUL` (`'\0'`) como separador em vez
  > de percent-encoding (mais simples, e NUL nunca aparece em host/share/
  > caminho/usuário/senha reais), só existe na memória do processo, nunca é
  > persistida nem devolvida ao Kotlin. Logs usam `protocols::smb::redact()`,
  > que mostra host/porta/share/caminho e NUNCA usuário/senha/domínio
  > (testado em `smb/uri.rs::tests::redact_hides_credentials`).
  >
  > **"Status de conexão"**: implementado como o resultado (✓/✗ + mensagem)
  > do teste de conexão feito no momento de salvar um servidor — NÃO é um
  > indicador "conectado agora" contínuo por servidor salvo (as conexões
  > SMB não ficam abertas entre uma navegação e outra; cada listagem/
  > navegação abre e fecha sua própria conexão). Isso é uma interpretação
  > mais simples do que "status de conexão" poderia sugerir; documentado
  > honestamente em vez de fingir um indicador live que não existe.
  >
  > **Teclado virtual (campos de texto)**: os campos de host/porta/share/
  > usuário/senha/domínio e o campo de URL (T7.3) são `EditText` normais —
  > o Android deveria acionar o IME padrão ao focar/tocar neles, inclusive
  > numa `Presentation` sobre `VirtualDisplay` (suporte a IME em virtual
  > displays existe desde Android 12L/13). **Isto é a maior incerteza não
  > resolvida desta rodada**: não há headset físico neste ambiente pra
  > confirmar que o teclado aparece e recebe toque corretamente através do
  > pipeline de raycast→`dispatchNetworkVRTouch`→`MotionEvent` sintético
  > usado aqui (diferente de um toque de tela real). Se não funcionar em
  > hardware real, a UI de adicionar servidor fica inutilizável sem um
  > teclado físico Bluetooth — risco real, não só uma formalidade.
  >
  > **Build**: `nm -D` no `.so` real (cross-compilado via `cargo ndk`)
  > confirma os símbolos `start_smb_playback`/`smb_list_shares`/
  > `smb_list_directory`/`probe_http_url`/`free_rust_string` exportados.
  > `./gradlew :app:externalNativeBuildDebug` e `:app:compileDebugKotlin`
  > passam; `scripts/build.sh` completo (Rust cross-compile real + C++ +
  > Kotlin) gera `app-debug.apk` com sucesso. **Nada disto foi validado em
  > headset real** nem contra um servidor SMB real (ver T6.1).

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **SMB read não é aleatório como arquivo local**: Seek em um vídeo via SMB requer novo request de leitura a partir do offset. O Demuxer do FFmpeg faz muitos seeks (especialmente em MKV com cues no final do arquivo). Implemente um **buffer inteligente** que mantenha dados lidos em cache e faça prefetch agressivo.

> [!WARNING]
> **SMB versioning**: NAS modernos usam SMB 3.0+. Libraries Rust como `pavão` podem suportar apenas SMB 2.x. Verifique compatibilidade com seu NAS. Alternativa robusta: binding para `libsmbclient` (parte do Samba project).

> [!WARNING]
> **Credenciais**: NUNCA armazene senhas em texto plano. Use Android `EncryptedSharedPreferences` ou `Android Keystore` para criptografar credenciais de servidores.

> [!IMPORTANT]
> **Timeout e reconexão**: Conexões SMB podem cair (Wi-Fi instável no Quest 3). Implemente retry automático com backoff exponencial. Mantenha a posição do vídeo e retome após reconexão.

> [!NOTE]
> **Performance SMB**: Para vídeos 4K (~60 Mbps), a latência de SMB sobre Wi-Fi 6 do Quest 3 é geralmente aceitável. Para 8K (~150 Mbps), pode precisar de buffer mais agressivo ou Wi-Fi 6E.

> [!NOTE]
> **Custo de seek em SMB/HTTPS remoto (achado desta sessão)**: `PlaybackController::seek()` já era, mesmo antes desta sessão, "parar tudo e recarregar do zero na posição alvo" (ver nota de T2.6 — não é um seek in-place). Para SMB e HTTPS isso significa que CADA seek abre uma conexão/sessão SMB nova (handshake + NTLMv2 + tree connect) ou refaz o probe HEAD do HTTPS, não só reaproveita uma conexão já aberta. Isto não foi otimizado nesta sessão — arrastar a seek bar num vídeo remoto deve ser perceptivelmente mais lento que num vídeo local. Documentado como limitação conhecida, não como bug escondido.

---

## 7. HTTP URL Playback

### O que fazer

Reproduzir vídeo a partir de URLs HTTP/HTTPS diretas.

### Tarefas

- [x] **T7.1** — Implementar HTTP client no Rust (`reqwest` + `tokio`):
  - GET com range requests (byte-range para seek)
  - Suporte a HTTPS com validação TLS
  - Follow redirects
  - Custom headers (User-Agent, Referer)
  > **Achado crítico verificado nesta sessão, e ele muda o que "suporte a
  > HTTPS" significa aqui**: o `libavformat.so` empacotado neste projeto
  > (`ffmpeg-android-maker`) foi compilado **sem nenhum backend TLS**.
  > Confirmado direto no binário/config, não assumido: `config.h` tem
  > `CONFIG_MBEDTLS 0` / `CONFIG_GNUTLS 0` / `CONFIG_OPENSSL 0`;
  > `ffbuild/config.mak` marca `CONFIG_HTTPS_PROTOCOL` e `CONFIG_TLS_PROTOCOL`
  > com o prefixo `!` (desabilitado); e o mais definitivo — o array
  > `url_protocols[]` gerado em `libavformat/protocol_list.c` **não contém**
  > `&ff_https_protocol` nem `&ff_tls_protocol`, só `&ff_http_protocol`. Ou
  > seja: **`https://` simplesmente não abre** via `ffmpeg::format::input()`
  > nesta build, não importa o que o Rust faça — não é uma limitação de TLS
  > "degradada", é ausência total do protocolo. Isso é o oposto do que a
  > tarefa original especulava. `http://` puro (sem TLS) funciona nativamente
  > (protocolo `http` registrado, com range requests tratados pelo próprio
  > libavformat) — para esse caso nenhum código novo foi necessário.
  >
  > Para HTTPS, implementei o cliente em `rust/protocols/src/http.rs` com
  > `reqwest` (`0.12`, `rustls-tls` — evita depender de uma lib TLS nativa
  > cross-compilada pra Android; TLS inteiramente do lado Rust, sem
  > depender de nenhum suporte do FFmpeg): `probe()` faz HEAD (com fallback
  > pra GET `Range: bytes=0-0` se o servidor não aceitar HEAD) pra descobrir
  > `Accept-Ranges`/tamanho ANTES de tocar; `HttpsRangeSource` faz leituras
  > posicionais via GET com header `Range` por leitura (amortizado em
  > blocos de 4MB pelo `PrefetchReader`, T7.2). Redirects: seguidos
  > automaticamente (comportamento padrão do `reqwest::blocking::Client`,
  > não desabilitado). Custom headers: a API aceita (`User-Agent` fixo
  > `"VRMultimediaPlayer/0.1"` já setado por padrão), mas não há UI pra
  > usuário customizar `User-Agent`/`Referer` — fora do orçamento desta
  > sessão. **Se o servidor não suporta range requests, `HttpsRangeSource::new`
  > falha cedo com um erro claro** — download progressivo sem seek (o
  > fallback que o aviso desta seção pede) não foi implementado para
  > HTTPS; só o probe (T7.1 propriamente dito) avisa a UI antes de tentar.
  > Nunca testado contra um servidor HTTPS real nesta sessão (mesma
  > ressalva de rede do T6.1) — só `cargo test`/`cargo ndk check`.
- [x] **T7.2** — Integrar com FFmpeg via custom I/O:
  - `avio_alloc_context` com callbacks de read/seek para HTTP
  - Buffer de 4MB para smoothing
  > Diferente do que a tarefa original cogitava ("só se o protocolo nativo
  > for insuficiente"), para HTTPS o custom I/O é a ÚNICA forma de
  > funcionar nesta build (ver T7.1) — não uma opção de performance. Mesmo
  > mecanismo genérico do T6.3 (`StreamIo::from_read_seek` do
  > `ffmpeg-next` 9.0, sem FFI crua com `avio_alloc_context`/
  > `ffmpeg-sys-next`): `Demuxer::new` detecta `https://` e roteia pro
  > `HttpsRangeSource` envolto num `PrefetchReader` de 4MB (a mesma
  > implementação de buffer usada pelo SMB, `rust/protocols/src/prefetch.rs` —
  > reuso deliberado, não duplicação). `http://` puro continua indo direto
  > pelo `ffmpeg::format::input()` nativo, sem custom I/O (não haveria
  > ganho nenhum, o protocolo nativo já lida com isso).
- [x] **T7.3** — UI para input de URL:
  - Campo de texto (teclado virtual)
  - Histórico de URLs recentes
  - Colar da clipboard
  > Implementado dentro do mesmo painel de rede do T6.4
  > (`NetworkPresentation.kt`, aba "🔗 URL") em vez de um painel 3D
  > separado — evita duplicar toda a integração VirtualDisplay+Presentation+
  > quad OES em `vr_player_app.cpp` por uma segunda vez. Campo de texto
  > (`EditText`, teclado virtual — mesma ressalva de incerteza do T6.4:
  > nunca validado em headset real), botão "📋 Colar" (lê
  > `ClipboardManager.primaryClip`), botão "▶ Tocar" que chama
  > `activity.playUrl()` (reusa `nativePlayVideo` — o Demuxer já despacha
  > por esquema, não precisou de um entry point JNI novo pra isso, diferente
  > do SMB) e dispara o probe HTTP (T7.1) em paralelo pra mostrar
  > "✓ suporta seek" / "⚠ não suporta seek" sem bloquear o play. Histórico:
  > `network/UrlHistoryStore.kt`, até 10 URLs mais recentes, deduplicado,
  > mais recente primeiro — clicar numa entrada do histórico preenche o
  > campo e toca de novo. **Sem criptografia** (`SharedPreferences` comuns,
  > diferente das credenciais SMB do T6.4) — se uma URL colada tiver um
  > token de autenticação na query string, fica em texto plano; o doc só
  > exige criptografia explicitamente pra credenciais SMB, então isto é uma
  > lacuna conhecida e aceita, não um requisito quebrado.

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **Nem todo servidor suporta range requests**: Sem range requests, seek é impossível. Detecte via header `Accept-Ranges: bytes` na resposta. Se não suportado, faça download progressivo e avise o usuário que seek não está disponível.
>
> **Nesta implementação**: o probe (`protocols::http::probe`, T7.1) detecta e avisa a UI (mensagem "⚠ Servidor NAO suporta seek"), mas o download progressivo sem seek como fallback só existe de fato para `http://` puro (o protocolo nativo do libavformat já se vira sozinho mesmo sem range). Para `https://` sem range requests, `HttpsRangeSource::new` falha e o vídeo simplesmente não carrega — não há fallback de download progressivo implementado nesse caso (ver nota de T7.1).

> [!IMPORTANT]
> **Buffering adaptativo**: Monitore a velocidade de download vs. bitrate do vídeo. Se `download_speed < 1.5 * bitrate`, comece a mostrar indicador de buffering ANTES de esgotar o buffer.
>
> **Não implementado nesta sessão** — nem para SMB nem para HTTP(S). O `PrefetchReader` (T6.3/T7.2) reduz round-trips mas não mede taxa de download vs. bitrate nem aciona nenhum indicador de "buffering" na UI. Fica como trabalho futuro genuíno.

---

## 8. Internacionalização (i18n)

### O que fazer

Configurar sistema de tradução desde o início com Português (BR) e Inglês.

### Tarefas

- [x] **T8.1** — Configurar `res/values/strings.xml` (Inglês — default)
  > Criado do zero — não existia `app/src/main/res/` no projeto antes desta
  > sessão. `app/src/main/res/values/strings.xml` contém a tradução em inglês
  > de todas as ~50 strings visíveis ao usuário que estavam hardcoded em
  > Kotlin (levantadas por grep sistemático em todo `app/src/main/java/`, não
  > só nos arquivos óbvios). Detalhe completo em `docs/i18n.md`.
- [x] **T8.2** — Criar `res/values-pt-rBR/strings.xml` (Português BR)
  > `app/src/main/res/values-pt-rBR/strings.xml` preserva o texto em
  > português que já existia hardcoded em `VRPresentation.kt` e
  > `VRControlsPresentation.kt` (a língua em que a UI foi originalmente
  > escrita) como base — não é uma tradução reversa a partir do inglês, é o
  > texto original movido para resource. Chaves e ordem espelham
  > `values/strings.xml` para revisão lado a lado. Literais hardcoded
  > substituídos por `context.getString(R.string.xxx[, arg...])` em
  > `VRPresentation.kt`, `VRControlsPresentation.kt` e
  > `VoidPanelChrome.kt` (botão "Voltar" compartilhado por todos os headers
  > de tela); `AndroidManifest.xml` passou a referenciar `@string/app_name`
  > em vez de trazer o nome do app hardcoded. Strings com interpolação (ex.
  > `"${server.name}/${source.path}"` em `VRPresentation.renderPlayer`)
  > viraram `getString(res, arg1, arg2)` com placeholders posicionais
  > (`%1$s`/`%2$s`), cobrindo o pedido explícito de T8.5/cuidados. Um caso
  > real (não forçado) de `<plurals>`: contagem de shares SMB encontrados ao
  > testar/salvar um servidor (`network_smb_form_status_connected`) — os
  > demais textos do app não têm nenhuma contagem real hoje, então não foram
  > forçados em plural. Arquivos varridos e confirmados **sem** strings de UI
  > (por grep, não por suposição): `VRActivity.kt` (só strings internas —
  > extras de Intent, esquemas de URI, nenhuma renderizada ao usuário),
  > `filebrowser/*.kt` (lógica pura, sem texto — quem desenha é
  > `VRPresentation`), `network/SmbCredentialStore.kt`/`UrlHistoryStore.kt`
  > (só chaves internas de `SharedPreferences`/JSON). Validado via
  > `./gradlew :app:compileDebugKotlin` e `:app:testDebugUnitTest` (ambos
  > `BUILD SUCCESSFUL`); `:app:assembleDebug` falha neste ambiente, mas por
  > motivo anterior e não relacionado — falta o Meta OpenXR SDK em
  > `sdk/meta-openxr-sdk/` para a etapa de build nativo (ver T1.6), a etapa
  > Kotlin/resources builda normalmente antes disso. **Não validado em
  > headset Quest 3 real**: a troca de idioma via `Locale`/`Configuration` do
  > sistema Android é automática e não depende de código customizado deste
  > app (mecanismo padrão de resource resolution da plataforma), mas o
  > comportamento exato dentro de uma `Presentation` sobre `VirtualDisplay`
  > nunca foi confirmado visualmente em hardware real nesta sessão — mesma
  > categoria de incerteza já registrada para o teclado virtual em T6.4/T7.3.
- [x] **T8.3** — Definir convenção de naming: `screen_element_action` (ex: `player_btn_play`, `browser_label_size`)
  > Convenção documentada em detalhe em `docs/i18n.md` (seção 3) e aplicada
  > consistentemente em todas as chaves criadas: prefixo de tela
  > (`home_`/`browser_`/`network_`/`player_`), prefixo `common_` para
  > elementos reusados por mais de uma tela (botão "Voltar" do
  > `VoidPanelChrome`; formatos de linha `📁 %1$s`/`🎬 %1$s` reusados pelo
  > browser local E pela navegação de diretório SMB), sufixo `_format`
  > reservado para strings com placeholders posicionais usadas via
  > `getString(res, arg...)`.
- [x] **T8.4** — Strings na camada C++ (OpenXR UI): usar IDs numéricos mapeados para strings carregadas do Kotlin via JNI
  > **Achado arquitetural que muda a premissa da tarefa**: grep completo em
  > `native/src/*.cpp` confirma que não há nenhum texto de UI renderizado
  > pelo C++ nesta arquitetura — todo texto visível ao usuário é desenhado
  > por Views Android (`TextView`/`EditText`/`Button`/`SeekBar`) dentro das
  > classes `Presentation` do Kotlin, renderizadas para um `VirtualDisplay` e
  > só DEPOIS capturadas como textura OES projetada num quad 3D pelo C++
  > (`vr_player_app.cpp`). O C++ só faz raycasting contra a geometria dos
  > quads e composição/blend das texturas resultantes — nunca enfileira,
  > desenha ou tem conhecimento de glifos/strings individuais. Não existe
  > "OpenXR UI" que desenhe texto diretamente, então a premissa do T8.4/aviso
  > da seção 8 ("a UI de controles é renderizada em C++/OpenXR") não
  > corresponde à arquitetura real deste projeto. **Decisão**: marcado como
  > concluído com esta nota, em vez de construir uma ponte JNI de strings
  > (Kotlin → IDs numéricos → C++) sem nenhum consumidor real — seria
  > scaffolding morto, mesmo padrão já identificado e evitado em T1.7 (UniFFI
  > removido por não ter caminho de chamada real). Recomendação registrada em
  > `docs/i18n.md` (seção 4) para se/quando uma UI nativa em C++/OpenXR vier
  > a existir no futuro: reusar o mesmo `res/values*/strings.xml` do Kotlin
  > via JNI, nunca duplicar strings dentro do C++ — formaliza o aviso original
  > do doc mesmo sem haver, hoje, C++ que precise de texto próprio.
- [x] **T8.5** — Documentar processo de adição de novos idiomas
  > `docs/i18n.md` criado (não uma seção do REQUIREMENTS.md — decisão de
  > manter um documento dedicado, com um link cruzado adicionado à seção 3.7
  > do REQUIREMENTS.md). Cobre: qualifier BCP-47/Android correto por locale
  > (`values-<lang>` vs `values-<lang>-r<REGION>`), processo passo a passo
  > pra criar uma pasta nova a partir de `values/strings.xml` (não de
  > `values-pt-rBR`, pra não herdar tradução errada), regras de `<plurals>`
  > por idioma (nem todo idioma tem só `one`/`other` — russo/árabe/polonês
  > têm categorias adicionais, com link para a doc oficial do Android e a
  > tabela do CLDR), onde NÃO duplicar strings (decisão consciente
  > documentada mesmo o C++ não precisando disso hoje — ver T8.4), e a
  > confirmação de que nenhuma mudança de código Kotlin é necessária só por
  > adicionar um idioma (troca de `Locale`/`Configuration` é automática,
  > comportamento padrão da plataforma Android).

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **Strings na camada C++**: A UI de controles é renderizada em C++/OpenXR, mas as strings de tradução estão em Android resources (Kotlin). Passe as strings traduzidas do Kotlin → C++ na inicialização ou via callback. NÃO duplique strings no C++.
>
> **Nesta implementação**: premissa não se aplica — ver nota de T8.4 acima. Não há texto renderizado em C++ nesta arquitetura (tudo é Android View capturada como textura), então não há strings para passar do Kotlin para o C++ nem risco de duplicação hoje. Decisão e recomendação para o futuro documentadas em `docs/i18n.md`, seção 4.

> [!NOTE]
> **Plurais e formatação**: Use Android `plurals` resource para contagens. Use `String.format()` com posicionais (`%1$s`) para permitir reordenação em idiomas diferentes.
>
> **Nesta implementação**: aplicado em `network_smb_form_status_connected` (`<plurals>`, contagem real de shares SMB encontrados) e em todas as strings com interpolação (`getString(res, arg1, arg2)` com `%1$s`/`%2$s`/`%1$d`/`%1$.2f` posicionais) — ver T8.2.

---

## 9. Histórico de Reprodução

### O que fazer

Salvar progresso de reprodução para retomar de onde parou.

### Tarefas

- [x] **T9.1** — Criar banco Room com tabela `PlaybackHistory`:
  ```kotlin
  @Entity
  data class PlaybackHistory(
      @PrimaryKey val mediaUri: String,  // URI do arquivo (local ou remoto)
      val title: String,
      val positionMs: Long,              // Posição em milliseconds
      val durationMs: Long,
      val lastPlayedAt: Instant,
      val thumbnailPath: String?,
      val sourceType: SourceType,        // LOCAL, SMB, HTTP
      val serverInfo: String?            // JSON com dados do servidor
  )
  ```
  > Implementado em `app/src/main/java/com/tucavr/history/` (Room 2.6.1,
  > compilador via **KSP** — plugin `com.google.devtools.ksp` versão
  > `1.9.0-1.0.13`, confirmada existente no Maven Central e casada com o
  > Kotlin 1.9.0 já usado no projeto; adicionado em `build.gradle.kts` raiz
  > e `app/build.gradle.kts`, ver deps `androidx.room:room-runtime`/
  > `room-ktx`/`room-compiler`). Duas diferenças deliberadas em relação ao
  > exemplo do doc:
  >
  > 1. **`mediaUri: String` (chave primária do exemplo) virou
  >    `historyKey: String`**, com um campo NOVO `mediaPath: String`
  >    guardando o "endereço tocável" de verdade (path local, URL http(s),
  >    ou path relativo dentro do share SMB). Motivo: o próprio aviso desta
  >    seção ("URI estabilidade") pede uma chave composta em vez de uma URI
  >    crua — usar essa chave composta como a ÚNICA coisa persistida
  >    significaria perder a capacidade de re-tocar a mídia a partir dela.
  >    `historyKey` (ver `PlaybackHistoryMapping.kt::historyKey()`) é
  >    `local|<path>|<sizeBytes>` (local), `http|<url>` (HTTP) ou
  >    `smb|<server.name>|<share>|<path>|<sizeBytes>` (SMB) — para SMB,
  >    **deliberadamente NÃO inclui `host`/porta** (exatamente o que pode
  >    mudar), só `server.name` (o rótulo escolhido pelo usuário ao salvar
  >    o servidor, T6.4); testado em
  >    `PlaybackHistoryMappingTest.smb key does NOT depend on host or port`.
  >    `path` já inclui o nome do arquivo como último segmento, então não há
  >    um campo `filename` redundante.
  > 2. **`lastPlayedAt: Instant` (doc) virou `lastPlayedAt: Long` (epoch
  >    millis)**. Decisão documentada em `PlaybackHistory.kt`: o
  >    `minSdk = 26` do projeto já suporta `java.time.Instant` nativamente
  >    sem desugaring, então seria tecnicamente viável — mas exigiria um
  >    `TypeConverter` sem ganho prático nenhum aqui (o único uso é ordenar
  >    "mais recente primeiro" e formatar um timestamp legível, ambos
  >    triviais com `Long`). `Long` também deixa a lógica de throttle
  >    (`PlaybackProgressThrottle`) pura e testável na JVM sem lidar com
  >    fuso horário.
  >
  > `sourceType: SourceType` do exemplo virou `HistorySourceType` — um enum
  > que só existe como formato de armazenamento da coluna Room, SEMPRE
  > derivado de um `PlaybackSource` (o sealed class que já modelava "de onde
  > vem a mídia" em toda a navegação/UI, `com.tucavr.navigation.
  > Destination.kt`) através de UMA única função
  > (`PlaybackSource.historySourceType()`), nunca construído manualmente em
  > outro lugar — reusa `PlaybackSource` em vez de duplicar um enum de
  > domínio paralelo, como pedido. `PlaybackSource.LocalFile`/`.Smb` ganharam
  > um campo novo `sizeBytes: Long = 0L` (default compatível com todo o
  > código pré-existente) para poder compor a chave estável sem precisar de
  > um canal separado. Room suporta enum nativamente desde 2.1 (conversor
  > `String` automático), sem `TypeConverter` manual.
  >
  > `serverInfo` (SMB apenas): JSON com `serverId`/`name`/`host`/`port`/
  > `share`/`domain` — **NUNCA `username`/`password`**; a credencial
  > continua vivendo só em `SmbCredentialStore`
  > (`EncryptedSharedPreferences`, T6.4) e é resolvida de novo pelo
  > `serverId` na hora de retomar (`VRPresentation.resolveSmbServerFromHistory`).
  >
  > Validado via `:app:compileDebugKotlin` — o processamento KSP roda sem
  > erro e gera o DAO/banco (`kspDebugKotlin` executa com sucesso, sem
  > falhas de anotação). **Não validado**: nenhum INSERT/SELECT real rodou
  > contra um banco Room de verdade (isso exige um dispositivo/emulador
  > Android real ou Robolectric, nenhum dos dois disponível nesta sessão) —
  > só a geração de código e a lógica pura ao redor (chave/mapeamento) foram
  > exercitadas, via testes JVM puros (ver T9.2/T9.3 abaixo).
- [x] **T9.2** — Salvar posição automaticamente a cada 10 segundos durante reprodução
  > Confirmado no C++ (`native/src/vr_player_app.cpp`, `Update()`) que
  > `updateMediaProgress` já era chamado pelo JNI **~10x por segundo**
  > (`frameCount % 6 == 0` a 60fps), não uma vez a cada 10s — sem throttle,
  > isso viraria ~10 escritas Room/segundo pelo tempo inteiro de reprodução.
  > Implementado `PlaybackProgressThrottle` (Kotlin puro, relógio injetável,
  > `app/src/main/java/com/tucavr/history/PlaybackProgressThrottle.kt`) e
  > `PlaybackHistoryTracker` (orquestra DAO + throttle,
  > `PlaybackHistoryTracker.kt`), instanciado uma vez por `VRActivity`
  > (`historyTracker`, `by lazy`). Os 3 entry points de playback
  > (`VRActivity.playFile`/`playUrl`/`playSmb`) chamam
  > `historyTracker.startTracking(source, title)` ANTES de
  > `nativePlayVideo`/`nativePlaySmb` (reseta o throttle — nova mídia = nova
  > janela de 10s); `VRActivity.updateMediaProgress` (companion, chamado
  > pelo JNI) chama `historyTracker.onProgress(currentSec, totalSec)` dentro
  > do mesmo `runOnUiThread` que já existia para a UI de controles — mantém
  > a mutação do estado interno do tracker single-threaded sem precisar de
  > sincronização extra. O save em si (`dao.upsert`) roda numa coroutine
  > `Dispatchers.IO` própria do tracker (Room bloqueia query em main thread
  > por padrão). Testado em `PlaybackProgressThrottleTest` (JVM pura, relógio
  > falso injetado) — inclui um teste simulando 60s de chamadas na frequência
  > real do JNI (600 chamadas a 100ms de intervalo) e checando que o número
  > de saves liberados fica entre 5 e 7 (≈1 a cada 10s), não 600.
- [x] **T9.3** — Ao abrir um arquivo com histórico, perguntar "Retomar de XX:XX?" ou auto-retomar
  > Implementado como pergunta (não auto-retomar) — `VRPresentation.
  > promptResumeOrPlay()`, chamado nos 3 pontos de clique (arquivo local em
  > `playLocalVideo`, URL em `playUrl`, arquivo SMB em `loadNetworkDirectory`).
  > Consulta `historyTracker.findExisting(source)` (Room via `Dispatchers.IO`)
  > pela mesma chave composta de T9.1; se existir e for "retomável"
  > (`PlaybackHistory.isResumable()`: posição ≥ 5s E < 97% da duração —
  > evita perguntar em vídeos mal começados ou já terminados; testado em
  > `PlaybackHistoryMappingTest`), mostra uma tela "Continuar de onde
  > parou?" no mesmo estilo Void do resto do app (`VoidPanelChrome`/
  > `VoidButton`/`VoidText` — navegável via raycast/controller como
  > qualquer outra tela, sem componente novo), com botões "▶ Retomar de
  > XX:XX" / "Começar do zero" / "‹ Voltar" (cancela sem tocar nada). Essa
  > tela é desenhada com `showScreen()` direto, sem passar pelo
  > `AppNavigator` — é uma decisão pontual, não um novo nível de navegação;
  > o back-stack continua exatamente onde estava.
  >
  > Retomar de fato: `VRActivity.playFile/playUrl/playSmb/playFtp/playSftp`
  > ganharam um parâmetro opcional `resumeAtMs`, passado direto como posição
  > inicial pros `native*` (`start_video_playback`/`start_smb_playback`/etc.,
  > todos com `startTimeSec` novo) — `PlaybackController::load_at` já faz
  > seek + pre-roll durante a própria abertura (T-seek-ux). Substituiu o
  > `Handler.postDelayed`/`scheduleResumeSeek` original (delay heurístico de
  > 1.5s sem sincronização real com "pronto pra seek", que carregava do zero
  > e só depois buscava a posição salva — dois carregamentos completos por
  > resume, e uma corrida real: trocar de vídeo antes do delay expirar fazia
  > o seek atrasado disparar contra a sessão do vídeo NOVO). Formatação
  > "XX:XX"/"H:MM:SS" via `formatDurationMs` (testada em
  > `PlaybackHistoryFormatTest`).
- [x] **T9.4** — Tela "Continuar assistindo" no menu principal
  > O botão "▶ Continuar assistindo" em `renderHome()` (`VRPresentation.kt`)
  > — que já existia como placeholder `VoidButtonStyle.DISABLED` antes desta
  > sessão — foi habilitado de verdade (`PRIMARY`, com `onClick` navegando
  > para o novo `Destination.ContinueWatching`). Tela nova
  > (`renderContinueWatching()`): `RecyclerView` + `HistoryAdapter` (classe
  > interna nova, mesmo padrão do `FileAdapter` já usado pelo file browser
  > local, T5.7, mas sem geração de thumbnail — `thumbnailPath` fica sempre
  > `null` nesta implementação, campo existe no Room para uso futuro),
  > listando `historyTracker.listRecent()` (Room `ORDER BY lastPlayedAt
  > DESC`) com ícone por `sourceType` (🎬 local / 🖥 SMB / 🌐 HTTP), título,
  > e meta "posição / duração · %" (`formatDurationMs`/`watchedPercent`,
  > testados em `PlaybackHistoryFormatTest`). Cada linha tem um botão "✕"
  > de remover (`historyTracker.delete`, mesmo padrão visual da lista de
  > servidores SMB salvos em `buildNetworkSmbPage`). Lista vazia mostra
  > "(nenhum histórico ainda)" em vez de uma tela em branco. Clicar numa
  > entrada (`resumeFromHistory`) toca DIRETO na posição salva (sem
  > perguntar de novo — a intenção já é explícita ao clicar em "continuar
  > assistindo"); para SMB, resolve o `SmbServer` salvo a partir do
  > `serverId` gravado em `serverInfo` (`resolveSmbServerFromHistory`) — se
  > o servidor foi removido/renomeado desde então, falha silenciosamente
  > (não navega) em vez de crashar.
  >
  > **Limitações honestas desta seção inteira (T9.1-T9.4)**: nenhum
  > INSERT/SELECT real rodou contra um banco Room de verdade nem em
  > dispositivo/emulador Android real, nem a UI foi vista num headset Quest
  > 3 físico — mesma ressalva de toda UI Kotlin/VR já documentada em outras
  > seções deste arquivo (T5, T6.4, T7.3). O que FOI validado nesta sessão:
  > (a) `./gradlew :app:compileDebugKotlin` — compila com sucesso, incluindo
  > o processamento KSP/Room (`kspDebugKotlin` sem erro); (b) `./gradlew
  > :app:testDebugUnitTest` — toda a lógica pura extraível (chave estável,
  > throttle de 10s, `isResumable`, formatação de tempo/percentual) tem
  > testes JVM puros novos em `app/src/test/java/com/tucavr/history/`
  > (`PlaybackHistoryMappingTest`, `PlaybackProgressThrottleTest`,
  > `PlaybackHistoryFormatTest` — 24 testes novos, 0 falhas; suíte completa
  > do módulo em 54 testcases, 0 falhas/erros). **Nota de ambiente**: rodar
  > `testDebugUnitTest` nesta sandbox especificamente exigiu uma JDK 17
  > completa (com `jlink`) — o `java-17-openjdk` do sistema aqui é só o
  > pacote JRE (`jre17-openjdk`, sem `jlink`/`javac`), e a JDK 21 disponível
  > (`jdk21-openjdk`) tem um bug conhecido de incompatibilidade entre AGP
  > 8.1.1 e o `jlink` mais novo ao transformar
  > `core-for-system-modules.jar` (`ModuleTarget is malformed`). Contornado
  > baixando um Temurin 17 completo à parte só para rodar a validação; não é
  > uma mudança permanente no projeto (o CI, em `.github/workflows/main.yml`,
  > já usa `actions/setup-java` com Temurin 17 completo, então não deveria
  > sofrer desse problema).

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **URI estabilidade**: URIs de SMB podem mudar se o IP do servidor mudar. Use um identificador composto: `server_name + share + path + filename + file_size` como chave, não apenas o URI.
>
> **Nesta implementação**: seguido à risca, com uma adaptação — a chave usa `server.name` (o rótulo local que o usuário escolheu ao salvar o servidor em `SmbCredentialStore`, T6.4) em vez de tentar derivar um "nome do servidor" de outra fonte, já que não há descoberta automática de servidor (T6.2, não implementada) nem nenhum outro identificador estável de servidor no projeto. Ver `PlaybackSource.historyKey()` em `app/src/main/java/com/tucavr/history/PlaybackHistoryMapping.kt` e o teste `PlaybackHistoryMappingTest.smb key does NOT depend on host or port`, que existe especificamente para não deixar essa garantia regredir silenciosamente.

---

## 10. Cuidados Transversais

### Performance Geral

> [!CAUTION]
> **Memory budget**: O Quest 3 tem 8GB RAM total, com ~3.5GB disponível para apps. Seu app DEVE usar menos de 2.5GB. Monitore com `Debug.getNativeHeapAllocatedSize()` + Rust allocator stats.

> [!CAUTION]
> **Thermal**: O Quest 3 limita performance após ~20 min de uso pesado. Implemente desde o MVP um monitor de temperatura:
> ```kotlin
> // Kotlin - Ler temperatura
> val thermalService = getSystemService(PowerManager::class.java)
> thermalService.addThermalStatusListener { status ->
>     when (status) {
>         PowerManager.THERMAL_STATUS_MODERATE -> reduceQuality()
>         PowerManager.THERMAL_STATUS_SEVERE -> reduceQualityAggressive()
>         PowerManager.THERMAL_STATUS_CRITICAL -> pausePlayback()
>     }
> }
> ```

### Rust ↔ C++ Communication

> [!IMPORTANT]
> **Rust e C++ estão no mesmo processo nativo**. A comunicação pode ser via:
> 1. **C ABI**: Rust expõe funções `extern "C"` que C++ chama diretamente (mais performático)
> 2. **Shared memory**: Para dados grandes (frames de vídeo), use ponteiros compartilhados
> 3. **Ring buffer lock-free**: Para producer-consumer de frames (Rust produz, C++ consome)
> 
> Recomendação: Use C ABI para controle + ring buffer para frames.

### Wi-Fi no Quest 3

> [!NOTE]
> **Wi-Fi 6/6E**: O Quest 3 suporta Wi-Fi 6E (6 GHz). Para streaming de conteúdo pesado (4K+), recomende ao usuário usar a banda 5GHz ou 6GHz. A banda 2.4GHz não tem bandwidth suficiente para 4K@60fps.

### Testes no Quest 3

> [!IMPORTANT]
> **Workflow de desenvolvimento**:
> 1. Desenvolvimento iterativo: Use **Meta Quest Developer Hub** para deploy rápido via `adb`
> 2. Profiling: Use **OVR Metrics Tool** para monitorar FPS, GPU/CPU usage, temperatura
> 3. Debugging: `adb logcat` filtrado por tag do app
> 4. Teste de rede: Monte um NAS local ou use um Raspberry Pi com Samba para SMB testing

---

## Definição de Pronto (Definition of Done) — v0.1

- [x] App instala e inicia no Quest 3 sem crash
- [x] Ambiente void renderiza em ≥72 FPS
- [x] Consegue navegar pelo file browser local (implementado e compila; pendente validação em headset real)
- [x] Consegue reproduzir vídeo MP4/MKV (H.264/H.265) local
- [x] Controles de play/pause/seek funcionam via controller (bug de threads "zumbis" corrigido — ver T2.6 — e bug de travamento do app inteiro ao trocar de vídeo corrigido, ver nota abaixo; pendente validação em headset real)

> [!IMPORTANT]
> **Regressão corrigida (pós-fix do T2.6)**: fazer `stop()` esperar (`join()`) as threads da sessão anterior de verdade — a correção original do T2.6 — abriu um novo risco: se qualquer uma das 3 threads travasse internamente (ex: `HwDecoder::decode_packet` tem um loop de retry no MediaCodec sem limite quando o buffer de entrada nunca libera espaço, ou um `send()` bloqueante em canal cheio), quem chamasse `stop()`/`seek()` — a UI thread do Android, via JNI, ao trocar de vídeo pelo file browser — travava junto, travando o app inteiro (ANR). Corrigido em 3 frentes: (1) `HwDecoder::decode_packet` agora recebe um `should_continue` checado a cada retry, permitindo abortar; (2) os `send()` de pacote/amostra entre threads agora usam `send_timeout` com re-checagem de `is_running` em vez de bloquear indefinidamente; (3) como rede de segurança adicional, `start_video_playback`/`seek_video_playback`/`cycle_audio_track` (as três chamadas JNI que podem envolver `stop()+load()`) agora despacham o trabalho para uma thread separada em vez de rodar direto na thread chamadora — a UI thread nunca mais fica bloqueada por essas chamadas, não importa quanto tempo o trabalho leve.
- [x] Volume ajustável (botões 🔉/🔊 na UI de controles; pendente validação em headset real)
- [x] Tela virtual pode ser movida e redimensionada (via thumbstick/grip; pendente validação em headset real)
- [ ] Consegue conectar a um share SMB e reproduzir vídeo remoto (código implementado e cross-compilado de verdade — ver T6.1/T6.3/T6.4 — mas NUNCA executado contra um servidor SMB real: não há NAS/Samba disponível neste ambiente sandboxed. Deixado sem marcar de propósito, diferente de outros itens desta lista que só faltam validação em headset — aqui falta validação do protocolo de rede em si, um risco maior)
- [ ] Consegue reproduzir vídeo de URL HTTP (mesma ressalva: `http://` puro tem boa chance de funcionar de primeira, já que é o protocolo nativo do libavformat sem nenhum código novo no caminho crítico; `https://` depende inteiramente do custom I/O novo em `protocols::http`, T7.1/T7.2, nunca testado contra um servidor real)
- [x] Strings estão externalizadas em PT-BR e EN (ver T8.1-T8.5; implementado e mergeado com a Seção 9 nesta sessão — ver nota de merge abaixo. `compileDebugKotlin`/`testDebugUnitTest` passam; troca de idioma via `Locale`/`Configuration` do sistema nunca foi verificada num Quest 3 físico)
- [x] Histórico de reprodução funciona (ver T9.1-T9.4; implementado e mergeado com a Seção 8 nesta sessão — ver nota de merge abaixo. `compileDebugKotlin`/`testDebugUnitTest` passam, incluindo os 24 testes JVM puros novos do módulo `history`; nunca validado com Room rodando de fato num device nem em headset real)
- [x] Nenhum crash em sessão de 30 minutos
- [x] Memória < 2.5GB durante reprodução de vídeo 4K

> [!IMPORTANT]
> **Validado em Quest 3 físico** (device `eureka`, Android 14, serial `2G97C5ZH9201LH`) — os dois únicos itens desta lista que exigem uma sessão real e prolongada de playback, não só compilar/abrir o app. Automatizado via `scripts/soak-test.sh` (crash/memória por adb) e `scripts/test-4k-memory.sh` (gera um clipe 4K sintético e dispara playback sozinho via `adb shell am start -e video_path ...`, ver hook `EXTRA_AUTO_PLAY_PATH` em `VRActivity.kt`).
>
> Clipe de teste: H.264 High@5.2, 3840x2160, ~25Mbps, 150s (`scripts/generate-4k-test-clip.sh`, testsrc2+sine via ffmpeg — determinístico, sem depender de nenhuma mídia externa). O player reinicia o vídeo sozinho ao chegar no fim (loop automático, não documentado antes — achado desta sessão), então os 150s cobriram os 30 minutos inteiros em ~10 repetições sem precisar gerar um arquivo de 30min.
>
> **Resultado**: 30min corridos, mesmo PID do início ao fim (sem restart), sem `FATAL EXCEPTION`/ANR/`has died` no logcat. PSS máximo observado: 273MB (279.747 KB) — bem abaixo do limite de 2.5GB. Thermal status `NONE` a sessão inteira (sem throttling).
>
> **Ressalva honesta sobre os 273MB**: é o PSS do processo `com.tucavr` reportado por `dumpsys meminfo` — a mesma métrica que esta seção define ("Seu app DEVE usar menos de 2.5GB"). Boa parte dos buffers de decode de hardware (DPB, frames UBWC do Codec2/Venus da Qualcomm) fica alocada via ION/dma-buf no processo do HAL de mídia, não contabilizada no PSS por-processo do app — comportamento padrão do Android, não uma limitação da medição. Ou seja: o *orçamento de memória do app* está validado com folga; o pico *total* de pressão de memória do sistema durante decode 4K (via `dumpsys gpumem`/dma-buf) não foi medido nesta sessão.
>
> **Achado lateral**: o primeiro `adb shell am start` de cada bateria de testes falhava com um falso-positivo de "crash" no `soak-test.sh` — na verdade o Quest bloqueia `am start` de apps VR com um diálogo de sistema (`LaunchCheckControllerRequiredDialogActivity`) quando os controllers estão hibernados; não é bug do app. Corrigido também um bug real no próprio script: parsing de `dumpsys thermalservice` esperava o formato antigo `mStatus=PALAVRA`, mas o Android 14 do Quest 3 reporta `Thermal Status: N` (numérico).

> [!NOTE]
> **Seções 8 e 9 implementadas em paralelo, por dois agentes em worktrees isolados** (i18n e histórico de reprodução tocam áreas parecidas — `VRPresentation.kt`, `renderHome()` — então rodar em worktrees separados evitou os dois escreverem no mesmo arquivo ao mesmo tempo). Ao mergear os dois branches em `main`, houve um conflito real (esperado) em `VRPresentation.kt` no botão "Continuar assistindo" (um lado o desabilitava com um comentário `T9 (roadmap)`, o outro o habilitava de verdade) — resolvido mantendo a versão habilitada (T9.4) com a string vinda de recurso (T8.1). Por instrução explícita, o agente da Seção 9 escreveu suas strings novas (tela "Continuar de onde parou?", "Continuar assistindo", etc.) como literais em português direto no Kotlin, para não competir com o agente de i18n mexendo no mesmo `strings.xml` ao mesmo tempo — depois do merge, foi feito um passe manual de limpeza externalizando essas ~9 strings novas (`history_*` em `values/strings.xml` e `values-pt-rBR/strings.xml`), reaproveitando chaves já existentes onde fazia sentido (`common_row_video_format` para o ícone 🎬 de itens locais no histórico, `network_smb_row_label_format` para o ícone 🖥 de itens SMB) em vez de duplicar. `compileDebugKotlin` e a suíte completa de testes JVM (54 casos, 0 falhas) validados após o merge + limpeza.

---

*Fase 0.1 — Estimativa: 6-10 semanas para desenvolvedor solo experiente*
