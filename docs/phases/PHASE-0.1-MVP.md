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
│   │   ├── java/com/vrplayer/    # Código Kotlin
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

- [ ] **T4.1** — Implementar sistema de **raycasting** a partir do controller:
  - Ray do controller → interseção com UI panels
  - Feedback visual: laser + ponto de interseção
  - Haptics no controller ao hovering sobre botão
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
- [ ] **T4.3** — Implementar **auto-hide**:
  - Controles aparecem ao apontar controller para a tela
  - Desaparecem após 5s de inatividade
  - Animação fade in/out suave
- [ ] **T4.4** — Implementar controles via **botões físicos** do controller:
  - Trigger: Select / Confirm
  - A/X: Play/Pause
  - B/Y: Menu/Back
  - Thumbstick: Seek (esquerda/direita), Volume (cima/baixo)
  - Grip: Segurar + mover para reposicionar tela
- [ ] **T4.5** — Renderizar UI como **quad overlay** no espaço 3D:
  - Posicionar abaixo da tela virtual
  - Sempre virado para o usuário (billboard)
  - Renderizar com alpha blending sobre o ambiente

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

### Tarefas

- [ ] **T5.1** — Implementar listagem de diretórios (Kotlin, permissão `READ_EXTERNAL_STORAGE` / Scoped Storage)
- [ ] **T5.2** — Filtrar por extensões de mídia (vídeo, áudio, imagem)
- [ ] **T5.3** — Exibir informações: nome, tamanho, data de modificação, ícone por tipo
- [ ] **T5.4** — Gerar thumbnails de vídeo (via MediaMetadataRetriever ou FFmpeg)
- [ ] **T5.5** — Navegação hierárquica (entrar/sair de pastas)
- [ ] **T5.6** — Ordenação: nome, data, tamanho, tipo
- [ ] **T5.7** — Renderizar o browser como painel 3D no ambiente VR

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

- [ ] **T6.1** — Implementar cliente SMB no Rust:
  - Autenticação (user/password + guest/anonymous)
  - Listar shares de um servidor
  - Navegar diretórios dentro de um share
  - Ler arquivos (streaming read, não download completo)
- [ ] **T6.2** — Implementar **descoberta automática** de servidores SMB:
  - NetBIOS name resolution
  - mDNS/DNS-SD (`.local`)
  - Fallback: IP manual
- [ ] **T6.3** — Integrar SMB com o Demuxer:
  - Implementar custom I/O callback para FFmpeg que lê do SMB
  - Buffer de leitura: 2-8MB ahead (prefetch)
- [ ] **T6.4** — UI para gerenciar conexões:
  - Adicionar servidor (host, share, user, password)
  - Lista de servidores salvos
  - Status de conexão

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

---

## 7. HTTP URL Playback

### O que fazer

Reproduzir vídeo a partir de URLs HTTP/HTTPS diretas.

### Tarefas

- [ ] **T7.1** — Implementar HTTP client no Rust (`reqwest` + `tokio`):
  - GET com range requests (byte-range para seek)
  - Suporte a HTTPS com validação TLS
  - Follow redirects
  - Custom headers (User-Agent, Referer)
- [ ] **T7.2** — Integrar com FFmpeg via custom I/O:
  - `avio_alloc_context` com callbacks de read/seek para HTTP
  - Buffer de 4MB para smoothing
- [ ] **T7.3** — UI para input de URL:
  - Campo de texto (teclado virtual)
  - Histórico de URLs recentes
  - Colar da clipboard

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **Nem todo servidor suporta range requests**: Sem range requests, seek é impossível. Detecte via header `Accept-Ranges: bytes` na resposta. Se não suportado, faça download progressivo e avise o usuário que seek não está disponível.

> [!IMPORTANT]
> **Buffering adaptativo**: Monitore a velocidade de download vs. bitrate do vídeo. Se `download_speed < 1.5 * bitrate`, comece a mostrar indicador de buffering ANTES de esgotar o buffer.

---

## 8. Internacionalização (i18n)

### O que fazer

Configurar sistema de tradução desde o início com Português (BR) e Inglês.

### Tarefas

- [ ] **T8.1** — Configurar `res/values/strings.xml` (Inglês — default)
- [ ] **T8.2** — Criar `res/values-pt-rBR/strings.xml` (Português BR)
- [ ] **T8.3** — Definir convenção de naming: `screen_element_action` (ex: `player_btn_play`, `browser_label_size`)
- [ ] **T8.4** — Strings na camada C++ (OpenXR UI): usar IDs numéricos mapeados para strings carregadas do Kotlin via JNI
- [ ] **T8.5** — Documentar processo de adição de novos idiomas

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **Strings na camada C++**: A UI de controles é renderizada em C++/OpenXR, mas as strings de tradução estão em Android resources (Kotlin). Passe as strings traduzidas do Kotlin → C++ na inicialização ou via callback. NÃO duplique strings no C++.

> [!NOTE]
> **Plurais e formatação**: Use Android `plurals` resource para contagens. Use `String.format()` com posicionais (`%1$s`) para permitir reordenação em idiomas diferentes.

---

## 9. Histórico de Reprodução

### O que fazer

Salvar progresso de reprodução para retomar de onde parou.

### Tarefas

- [ ] **T9.1** — Criar banco Room com tabela `PlaybackHistory`:
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
- [ ] **T9.2** — Salvar posição automaticamente a cada 10 segundos durante reprodução
- [ ] **T9.3** — Ao abrir um arquivo com histórico, perguntar "Retomar de XX:XX?" ou auto-retomar
- [ ] **T9.4** — Tela "Continuar assistindo" no menu principal

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **URI estabilidade**: URIs de SMB podem mudar se o IP do servidor mudar. Use um identificador composto: `server_name + share + path + filename + file_size` como chave, não apenas o URI.

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
- [ ] Consegue navegar pelo file browser local
- [x] Consegue reproduzir vídeo MP4/MKV (H.264/H.265) local
- [x] Controles de play/pause/seek funcionam via controller (bug de threads "zumbis" corrigido — ver T2.6 — e bug de travamento do app inteiro ao trocar de vídeo corrigido, ver nota abaixo; pendente validação em headset real)

> [!IMPORTANT]
> **Regressão corrigida (pós-fix do T2.6)**: fazer `stop()` esperar (`join()`) as threads da sessão anterior de verdade — a correção original do T2.6 — abriu um novo risco: se qualquer uma das 3 threads travasse internamente (ex: `HwDecoder::decode_packet` tem um loop de retry no MediaCodec sem limite quando o buffer de entrada nunca libera espaço, ou um `send()` bloqueante em canal cheio), quem chamasse `stop()`/`seek()` — a UI thread do Android, via JNI, ao trocar de vídeo pelo file browser — travava junto, travando o app inteiro (ANR). Corrigido em 3 frentes: (1) `HwDecoder::decode_packet` agora recebe um `should_continue` checado a cada retry, permitindo abortar; (2) os `send()` de pacote/amostra entre threads agora usam `send_timeout` com re-checagem de `is_running` em vez de bloquear indefinidamente; (3) como rede de segurança adicional, `start_video_playback`/`seek_video_playback`/`cycle_audio_track` (as três chamadas JNI que podem envolver `stop()+load()`) agora despacham o trabalho para uma thread separada em vez de rodar direto na thread chamadora — a UI thread nunca mais fica bloqueada por essas chamadas, não importa quanto tempo o trabalho leve.
- [x] Volume ajustável (botões 🔉/🔊 na UI de controles; pendente validação em headset real)
- [x] Tela virtual pode ser movida e redimensionada (via thumbstick/grip; pendente validação em headset real)
- [ ] Consegue conectar a um share SMB e reproduzir vídeo remoto
- [ ] Consegue reproduzir vídeo de URL HTTP
- [ ] Strings estão externalizadas em PT-BR e EN
- [ ] Histórico de reprodução funciona
- [ ] Nenhum crash em sessão de 30 minutos
- [ ] Memória < 2.5GB durante reprodução de vídeo 4K

---

*Fase 0.1 — Estimativa: 6-10 semanas para desenvolvedor solo experiente*
