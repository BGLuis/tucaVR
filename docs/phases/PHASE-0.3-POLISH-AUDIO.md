# Fase 0.3 — Polish & Audio

> **Objetivo**: Elevar a experiência do player com ambientes virtuais imersivos, áudio espacial completo (Ambisonics + 5.1/7.1), hand tracking, codecs adicionais (VP9/AV1), legendas avançadas (ASS/PGS), fotos 3D/360°, playlists e mais um idioma.  
> **Pré-requisito**: Fase 0.2 completa e estável.  
> **Resultado esperado**: O usuário tem uma experiência imersiva completa — assiste conteúdo num cinema virtual ou com passthrough, controla via gestos de mão, ouve áudio espacial, e tem suporte a praticamente qualquer formato de legenda.

---

## 📋 Índice

1. [Ambientes Virtuais: Cinema e Sala](#1-ambientes-virtuais-cinema-e-sala)
2. [Passthrough / Mixed Reality](#2-passthrough--mixed-reality)
3. [Áudio Espacial — Ambisonics](#3-áudio-espacial--ambisonics)
4. [Áudio Multicanal — 5.1 e 7.1 Virtualizado](#4-áudio-multicanal--51-e-71-virtualizado)
5. [Hand Tracking](#5-hand-tracking)
6. [Codecs VP9 e AV1](#6-codecs-vp9-e-av1)
7. [Legendas Avançadas — ASS/SSA e PGS](#7-legendas-avançadas--assssa-e-pgs)
8. [Fotos 360° e Fotos 3D](#8-fotos-360-e-fotos-3d)
9. [Playlists](#9-playlists)
10. [i18n — Espanhol](#10-i18n--espanhol)
11. [Cuidados Transversais da Fase 0.3](#11-cuidados-transversais-da-fase-03)

---

## 1. Ambientes Virtuais: Cinema e Sala

### Conceito

Substituir o void escuro por ambientes 3D imersivos que simulam espaços físicos reais. O vídeo é projetado numa "tela" que faz parte do ambiente.

```
Cinema Virtual:                         Sala Virtual:
┌────────────────────────────────┐     ┌──────────────────────────────┐
│          ┌──────────┐          │     │     🪟         🖼️            │
│          │  TELA    │          │     │   ┌──────────┐              │
│          │  GRANDE  │          │     │   │   TV     │   🛋️         │
│          │          │          │     │   │  VIRTUAL │              │
│          └──────────┘          │     │   └──────────┘              │
│  🪑  🪑  🪑  🪑  🪑  🪑  🪑  │     │   📦         🪴             │
│  🪑  🪑  🪑  🪑  🪑  🪑  🪑  │     │          🛋️                 │
│     🎬  Cinema Floor  🎬      │     │      Living Room Floor      │
└────────────────────────────────┘     └──────────────────────────────┘
```

### Tarefas

- [ ] **T1.1** — Definir pipeline de **loading de ambientes 3D**:
  - Formato de modelos: glTF 2.0 (.glb) — padrão da indústria, compacto
  - Loader: `cgltf` (C, header-only) ou `tinygltf` (C++)
  - Estrutura de um ambiente:
    ```
    environments/
    ├── cinema/
    │   ├── cinema.glb          # Modelo 3D completo
    │   ├── textures/           # Texturas (compactadas em KTX2/ASTC)
    │   │   ├── floor_diffuse.ktx2
    │   │   ├── walls_diffuse.ktx2
    │   │   ├── ceiling_emissive.ktx2
    │   │   └── screen_frame.ktx2
    │   ├── lightmap.ktx2       # Lightmap pré-calculado (GI)
    │   └── config.json         # Metadados: posição da tela, spawn point
    └── living_room/
        ├── living_room.glb
        ├── textures/
        ├── lightmap.ktx2
        └── config.json
    ```
- [ ] **T1.2** — Implementar **glTF loader** (C++):
  - Parsear nodes, meshes, materials, textures
  - Suportar PBR metallic-roughness workflow
  - Carregar texturas em formato ASTC (compressão nativa do Quest 3)
  - Instanciar buffers Vulkan/GLES a partir dos dados glTF
  ```cpp
  struct Environment {
      std::vector<Mesh> meshes;
      std::vector<Material> materials;
      std::vector<Texture> textures;
      Texture lightmap;
      
      // Metadados do ambiente
      glm::vec3 screenPosition;    // Onde a tela de vídeo fica
      glm::vec3 screenScale;       // Tamanho padrão da tela
      glm::quat screenRotation;    // Orientação da tela
      glm::vec3 spawnPoint;        // Onde o usuário "aparece"
      glm::vec3 spawnDirection;    // Para onde olha ao iniciar
  };
  ```
- [ ] **T1.3** — Criar **ambiente Cinema**:
  - Modelar em Blender ou obter modelo open-source (CC0/MIT)
  - Elementos: tela grande (16:9), poltronas, paredes laterais, teto com iluminação sutil, cortinas
  - Iluminação: lightmap pré-calculado (baked) — NÃO use luzes dinâmicas (caro demais para VR)
  - Tela posicionada a ~8m de distância, ~5m de largura (simula IMAX)
  - Ambient lighting: sutil, moldura da tela com emissivo suave
  - Som ambiente: silêncio com reverb leve (opcional)
- [ ] **T1.4** — Criar **ambiente Sala (Living Room)**:
  - Elementos: TV (tela menor, ~2m de largura a ~3m), sofá, mesa de centro, janela com luz, estante
  - Iluminação: mais quente que o cinema, lamp emissiva
  - Tela posicionada como uma TV real na parede
  - Sensação acolhedora e doméstica
- [ ] **T1.5** — Implementar **seletor de ambientes** no menu:
  - Preview thumbnail de cada ambiente
  - Transição suave entre ambientes (fade to black → load → fade in)
  - Persistir ambiente preferido em DataStore
- [ ] **T1.6** — Implementar **anchor da tela** no ambiente:
  - No cinema: tela fixa na posição definida pelo `config.json`
  - Na sala: tela fixa como "TV na parede"
  - O usuário pode opcionalmente deslocar a tela (override)
- [ ] **T1.7** — Implementar **PBR rendering pipeline** (se não existir):
  - Shader PBR com metallic-roughness
  - Image-Based Lighting (IBL) usando cubemap do ambiente
  - Lightmap sampling para iluminação indireta

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Polígonos e draw calls**: O Quest 3 suporta ~750K triângulos por frame com margem confortável. Cada ambiente deve ter **< 100K triângulos** para deixar orçamento para UI e tela de vídeo. Use LOD (Level of Detail) agressivo e merge meshes no Blender.

> [!CAUTION]
> **Lightmaps, NÃO luzes dinâmicas**: Luzes dinâmicas (point lights, spot lights) com sombras são proibitivamente caras em VR mobile. TODAS as sombras e iluminação global devem ser pré-calculadas (baked) como lightmaps no Blender. O único "lighting" dinâmico deve ser a emissão da tela do vídeo (aproximada como ambient term, sem shadow casting).

> [!WARNING]
> **Texturas ASTC**: O Quest 3 suporta ASTC nativamente. Converta todas as texturas para ASTC 6x6 ou 8x8 (bom equilíbrio qualidade/tamanho). Use `astcenc` ou `KTX-Software` para compressão. Texturas PNG/JPEG descompactadas na GPU consomem 4-8x mais VRAM.
> ```bash
> # Converter textura para KTX2 com ASTC
> toktx --t2 --encode astc --astc_blk_d 6x6 output.ktx2 input.png
> ```

> [!WARNING]
> **Tamanho dos assets**: Cada ambiente com texturas pode ter 20-50MB. O APK do Quest tem limite de ~1GB na Quest Store (e o armazenamento do Quest 3 é 128GB/512GB). Mantenha ambientes totais < 200MB na v0.3.

> [!IMPORTANT]
> **Profundidade de tela vs. ambiente**: A tela de vídeo e o ambiente DEVEM estar no mesmo depth space. Se a tela "flutuar" através de objetos do ambiente (z-fighting), quebra a imersão completamente. Garanta que a tela sempre renderiza NA FRENTE de qualquer geometria do ambiente na sua posição.

> [!NOTE]
> **Modelos open-source**: Procure no Sketchfab (filtro: CC0 license) ou crie no Blender. Para um cinema VR, modelos como "Virtual Cinema" no Sketchfab são um bom ponto de partida. Garanta a licença antes de incluir.

---

## 2. Passthrough / Mixed Reality

### Conceito

Usar as câmeras do Quest 3 para mostrar o mundo real como fundo, com a tela de vídeo flutuando no espaço físico do usuário.

```
Passthrough Mode:
┌───────────────────────────────────────┐
│  🏠 Sala Real do Usuário              │
│                                       │
│         ┌──────────────┐              │
│         │  TELA VIRTUAL│              │
│         │  (flutuando) │              │
│         │   📺 Vídeo   │              │
│         └──────────────┘              │
│     🛋️ Sofá Real    🪴 Planta Real   │
│                                       │
└───────────────────────────────────────┘
```

### Tarefas

- [ ] **T2.1** — Habilitar **Meta Passthrough API** via OpenXR extension:
  ```cpp
  // Extensions necessárias na criação da XrInstance
  const char* extensions[] = {
      XR_FB_PASSTHROUGH_EXTENSION_NAME,
      XR_FB_PASSTHROUGH_KEYBOARD_HANDS_EXTENSION_NAME, // Opcional
      // ... outras extensions
  };
  
  // Criar feature de passthrough
  XrPassthroughCreateInfoFB passthroughInfo = {XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
  passthroughInfo.flags = XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB;
  
  XrPassthroughFB passthrough;
  xrCreatePassthroughFB(session, &passthroughInfo, &passthrough);
  
  // Criar layer de passthrough
  XrPassthroughLayerCreateInfoFB layerInfo = {XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
  layerInfo.passthrough = passthrough;
  layerInfo.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
  
  XrPassthroughLayerFB passthroughLayer;
  xrCreatePassthroughLayerFB(session, &layerInfo, &passthroughLayer);
  ```
- [ ] **T2.2** — Integrar passthrough no **render loop**:
  - Submeter `XrCompositionLayerPassthroughFB` como layer de fundo no `xrEndFrame()`
  - Ordem de layers: passthrough (fundo) → tela de vídeo (quad) → UI (overlay)
  ```cpp
  // No xrEndFrame, ordenar layers:
  std::vector<const XrCompositionLayerBaseHeader*> layers;
  
  // 1. Passthrough como fundo
  XrCompositionLayerPassthroughFB passthroughCompLayer = {
      XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB
  };
  passthroughCompLayer.layerHandle = passthroughLayer;
  layers.push_back((XrCompositionLayerBaseHeader*)&passthroughCompLayer);
  
  // 2. Tela de vídeo (projection ou quad layer)
  layers.push_back((XrCompositionLayerBaseHeader*)&projectionLayer);
  
  // 3. UI overlay
  layers.push_back((XrCompositionLayerBaseHeader*)&uiLayer);
  ```
- [ ] **T2.3** — Implementar **toggle** entre ambientes e passthrough:
  - Botão no menu: "Ambiente Virtual" ↔ "Mixed Reality"
  - Transição: fade out do ambiente → enable passthrough → fade in tela
  - Quando em passthrough: não renderizar geometria de ambiente
- [ ] **T2.4** — **Ajustar opacidade/brilho** do passthrough:
  - Slider de opacidade (útil para escurecer o ambiente real)
  - Edge rendering (estilo preto-e-branco) como opção estilística
  ```cpp
  // Ajustar estilo do passthrough
  XrPassthroughStyleFB style = {XR_TYPE_PASSTHROUGH_STYLE_FB};
  style.textureOpacityFactor = 0.8f; // 80% opacidade
  xrPassthroughLayerSetStyleFB(passthroughLayer, &style);
  ```
- [ ] **T2.5** — **Posicionamento espacial** da tela no mundo real:
  - A tela deve se ancorar no espaço real (não seguir a cabeça)
  - O usuário pode "colocar" a tela onde quiser no ambiente real
  - Usar `XR_REFERENCE_SPACE_TYPE_STAGE` para âncora estável

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Passthrough layer deve ser o PRIMEIRO layer na composição**: Se enviar o passthrough depois do projection layer, o passthrough vai sobrescrever o vídeo. Ordem SEMPRE: passthrough → conteúdo → UI.

> [!WARNING]
> **Performance**: Passthrough consome recursos do sistema (câmeras + composição). Com passthrough ativo, o budget de GPU para rendering é ~15-20% menor. Evite ambientes 3D complexos + passthrough simultaneamente (não faz sentido mesmo — ou um ou outro).

> [!WARNING]
> **Não renderize clear color opaco com passthrough**: Quando passthrough está ativo, seu swapchain deve ter `clear color = (0, 0, 0, 0)` (totalmente transparente) nas áreas sem geometria. Caso contrário, o passthrough fica coberto por fundo opaco.
> ```cpp
> // CORRETO: clear com alpha 0 para passthrough
> glClearColor(0.0f, 0.0f, 0.0f, 0.0f);  // Transparente
> ```

> [!IMPORTANT]
> **Requisitos de manifest**: Para usar passthrough, adicione ao `AndroidManifest.xml`:
> ```xml
> <uses-feature android:name="com.oculus.feature.PASSTHROUGH" android:required="false" />
> <uses-permission android:name="com.oculus.permission.USE_SCENE" />
> ```

> [!NOTE]
> **Quest 3 vs Quest 2**: O Quest 3 tem passthrough colorido de alta qualidade. O Quest 2 tem passthrough em escala de cinza e baixa resolução. O código é o mesmo (mesma API), mas a experiência é muito diferente.

---

## 3. Áudio Espacial — Ambisonics

### Conceito

Ambisonics é um formato de áudio que captura o campo sonoro completo em 360°. Quando o usuário vira a cabeça, os sons se reposicionam automaticamente — perfeito para conteúdo 360° VR.

```
Ambisonics Field-of-Sound:

         ↑ Cima
         │
  Esq ←──●──→ Dir     O ouvinte está no centro.
         │              Sons vêm de todas as direções.
         ↓ Baixo        Ao virar a cabeça, a cena sonora
                        permanece fixa no espaço.

1ª Ordem (FOA): 4 canais (W, X, Y, Z) — qualidade básica
2ª Ordem (SOA): 9 canais — qualidade melhor
3ª Ordem (TOA): 16 canais — qualidade alta
```

### Tarefas

- [ ] **T3.1** — Detectar streams de áudio **Ambisonics** no demuxer:
  ```rust
  // Metadados que indicam Ambisonics
  // - YouTube 360° usa 4 canais Opus/AAC em ACN/SN3D ordering
  // - MKV pode ter tag "AMBISONICS" no codec private
  // - MP4 pode ter SA3D box (Spatial Audio)
  
  struct AmbisonicsInfo {
      order: AmbiOrder,           // First, Second, Third
      channel_ordering: ChannelOrdering, // ACN (Ambisonics Channel Number)
      normalization: Normalization,      // SN3D ou N3D
      num_channels: u32,          // 4 (FOA), 9 (SOA), 16 (TOA)
  }
  
  enum AmbiOrder { First, Second, Third }
  enum ChannelOrdering { ACN, FuMa }  // ACN é o padrão moderno
  enum Normalization { SN3D, N3D, FuMa }
  ```
- [ ] **T3.2** — Implementar **decodificador Ambisonics → binaural** (Rust):
  - Converter Ambisonics multicanal para stereo binaural (headphones)
  - Aplicar HRTF (Head-Related Transfer Function) para espacialização
  - Rotacionar o campo sonoro baseado na orientação da cabeça do usuário
  ```rust
  // Pipeline de Ambisonics para binaural:
  // 1. Receber PCM multicanal (4/9/16 ch) do decoder
  // 2. Aplicar rotação (head tracking quaternion → rotation matrix)
  // 3. Decodificar para speakers virtuais (virtual loudspeaker decode)
  // 4. Aplicar HRTF para cada speaker virtual → stereo binaural
  // 5. Mixar outputs → 2ch PCM para headphones
  
  struct AmbisonicsRenderer {
      hrtf_dataset: HrtfDataset,  // HRTF filters (ex: SADIE II, MIT KEMAR)
      rotation_matrix: [[f32; 3]; 3],
      order: AmbiOrder,
      block_size: usize,          // Processar em blocos de 512-1024 samples
  }
  
  impl AmbisonicsRenderer {
      fn process(&mut self, input: &[&[f32]], head_orientation: Quaternion) -> [Vec<f32>; 2] {
          // 1. Atualizar rotation matrix do head tracking
          self.rotation_matrix = quaternion_to_rotation_matrix(head_orientation);
          
          // 2. Rotacionar canais Ambisonics
          let rotated = self.rotate_ambisonic_channels(input);
          
          // 3. Decode para speakers virtuais
          let speakers = self.decode_to_virtual_speakers(&rotated);
          
          // 4. Aplicar HRTF a cada speaker → binaural
          let binaural = self.apply_hrtf(&speakers);
          
          binaural // [left_channel, right_channel]
      }
  }
  ```
- [ ] **T3.3** — Integrar **HRTF datasets**:
  - Opção A: SADIE II database (Creative Commons, boa qualidade)
  - Opção B: MIT KEMAR (domínio público, clássico)
  - Opção C: Meta Spatial Audio SDK (se disponível para uso com OpenXR)
  - Formato: SOFA (Spatially Oriented Format for Acoustics) → parsear com crate customizado
  - Pré-processar HRTF como filtros FIR (convolution) — tamanho típico: 128-256 taps
  - Embalar como asset no APK (~2-5MB)
- [ ] **T3.4** — **Sincronizar rotação do áudio com head tracking**:
  - Obter orientação da cabeça a cada bloco de áudio (~5-10ms)
  - Interpolar entre orientações para evitar saltos audíveis
  - Latência máxima aceitável: < 20ms (motion-to-sound)
  ```rust
  // No callback de áudio (Oboe):
  fn audio_callback(&mut self, output_buffer: &mut [f32], num_frames: usize) {
      // Obter orientação mais recente da cabeça (thread-safe)
      let head_orientation = self.head_tracker.get_latest_orientation();
      
      // Decodificar Ambisonics com a orientação atual
      let binaural = self.ambi_renderer.process(
          &self.ambi_input_channels,
          head_orientation,
      );
      
      // Interleave L/R para output
      for i in 0..num_frames {
          output_buffer[i * 2] = binaural[0][i];
          output_buffer[i * 2 + 1] = binaural[1][i];
      }
  }
  ```
- [ ] **T3.5** — **Fallback** para stereo quando Ambisonics não está disponível:
  - Se áudio é stereo regular → reproduzir normalmente sem processamento espacial
  - Chaveamento automático baseado na detecção do stream

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **HRTF convolution é computacionalmente pesado**: Para FOA (4ch) com HRTF de 256 taps a 48kHz, são ~4 milhões de multiplicações por segundo. Use **FFT-based convolution** (overlap-save ou overlap-add) em vez de convolução no domínio do tempo. Com FFT, fica ~10x mais rápido.
> ```rust
> // Convolução via FFT (muito mais rápido):
> // 1. FFT do bloco de áudio (block_size + filter_size - 1)
> // 2. Multiplicação ponto-a-ponto no domínio da frequência
> // 3. IFFT do resultado
> // 4. Overlap-add com bloco anterior
> use rustfft::FftPlanner;
> ```

> [!CAUTION]
> **Channel ordering ACN vs FuMa**: YouTube usa ACN/SN3D. Conteúdo mais antigo pode usar FuMa. Se aplicar a rotação com o ordering errado, o áudio gira na direção OPOSTA ao movimento da cabeça, causando desconforto. Detecte e converta se necessário.

> [!WARNING]
> **Ambisonics 2ª+ ordem com baixa resolução de HRTF**: Se seu dataset HRTF tem poucas direções (< 50 pontos), Ambisonics de ordem alta não melhora a qualidade. Use no mínimo 64-128 direções HRTF para SOA, e 256+ para TOA.

> [!IMPORTANT]
> **Thread de áudio é real-time**: O callback de áudio do Oboe roda em thread real-time. NUNCA aloque memória, faça I/O, ou adquira mutexes no callback. Pré-aloque todos os buffers. Use atomics ou lock-free queues para comunicação.
> ```rust
> // ERRADO no callback de áudio:
> let buffer = Vec::new();  // Alocação! Pode causar audio glitch
> file.read();              // I/O! Pode bloquear por ms
> mutex.lock();             // Lock! Pode causar priority inversion
> 
> // CORRETO: pré-alocar e usar atomics
> let head_rot = self.head_orientation.load(Ordering::Relaxed); // Atomic read
> // processar em buffers pré-alocados
> ```

> [!NOTE]
> **Meta Spatial Audio SDK**: A Meta oferece SDK de áudio espacial, mas é proprietário. Para open source MIT, implemente Ambisonics manualmente usando HRTF de domínio público. O resultado é comparável.

---

## 4. Áudio Multicanal — 5.1 e 7.1 Virtualizado

### Conceito

Conteúdo de cinema frequentemente usa 5.1 (6 canais) ou 7.1 (8 canais). Virtualizar esses canais em headphones para simular uma sala de cinema.

```
Layout 5.1:                    Layout 7.1:
        C                            C
       / \                          / \
     FL   FR                     FL   FR
    /       \                   /       \
   SL       SR               SL    ●    SR
      \   /                    \  BL BR /
       LFE                       LFE

FL=Front Left, FR=Front Right, C=Center
SL=Surround Left, SR=Surround Right
BL=Back Left, BR=Back Right, LFE=Subwoofer
```

### Tarefas

- [ ] **T4.1** — Detectar layout de canais no demuxer (Rust):
  ```rust
  enum ChannelLayout {
      Mono,
      Stereo,
      Surround5_1 { channels: [ChannelType; 6] },
      Surround7_1 { channels: [ChannelType; 8] },
      Ambisonics(AmbisonicsInfo),
  }
  
  // FFmpeg channel layout detection
  fn detect_layout(codec_params: &AVCodecParameters) -> ChannelLayout {
      match codec_params.channel_layout {
          AV_CH_LAYOUT_5POINT1 => ChannelLayout::Surround5_1 { /* ... */ },
          AV_CH_LAYOUT_7POINT1 => ChannelLayout::Surround7_1 { /* ... */ },
          _ if codec_params.channels == 4 => /* check ambisonics */,
          _ => ChannelLayout::Stereo,
      }
  }
  ```
- [ ] **T4.2** — Implementar **virtualização 5.1/7.1 → binaural**:
  - Posicionar cada canal num speaker virtual 3D
  - Aplicar HRTF correspondente à posição de cada speaker
  - Mixar para stereo binaural
  ```rust
  struct SurroundVirtualizer {
      speaker_positions: Vec<SphericalCoord>,  // Posição de cada speaker
      hrtf_filters: Vec<HrtfFilter>,           // HRTF pré-carregado por posição
  }
  
  impl SurroundVirtualizer {
      fn new_5_1() -> Self {
          Self {
              speaker_positions: vec![
                  SphericalCoord::new(-30.0, 0.0),   // Front Left
                  SphericalCoord::new(30.0, 0.0),    // Front Right
                  SphericalCoord::new(0.0, 0.0),     // Center
                  SphericalCoord::new(0.0, -30.0),   // LFE (abaixo)
                  SphericalCoord::new(-110.0, 0.0),  // Surround Left
                  SphericalCoord::new(110.0, 0.0),   // Surround Right
              ],
              // Carregar HRTF para cada posição
              hrtf_filters: /* ... */,
          }
      }
      
      fn process(&self, channels: &[&[f32]; 6], head_rotation: Quaternion) -> [Vec<f32>; 2] {
          let mut left = vec![0.0f32; block_size];
          let mut right = vec![0.0f32; block_size];
          
          for (i, channel) in channels.iter().enumerate() {
              // Rotacionar posição do speaker pela orientação da cabeça
              let rotated_pos = rotate_position(self.speaker_positions[i], head_rotation);
              
              // Obter HRTF para posição rotacionada
              let (hrtf_l, hrtf_r) = self.hrtf_filters[i].get_for_direction(rotated_pos);
              
              // Convolução
              let conv_l = convolve(channel, hrtf_l);
              let conv_r = convolve(channel, hrtf_r);
              
              // Acumular
              add_to(&mut left, &conv_l);
              add_to(&mut right, &conv_r);
          }
          
          [left, right]
      }
  }
  ```
- [ ] **T4.3** — Tratar **canal LFE (subwoofer)**:
  - Headphones não reproduzem sub-graves com fidelidade
  - Mixar LFE nos canais frontais com filtro low-pass + boost sutil
  - Opcional: haptics do controller para simular sub-graves (Quest 3 suporta)
- [ ] **T4.4** — Integrar head tracking com virtualização surround:
  - Quando o usuário vira a cabeça no cinema, os speakers virtuais ficam no lugar
  - Cria sensação de "sala real" com áudio posicional
- [ ] **T4.5** — **Toggle** entre modos de áudio:
  - Stereo direto (sem processamento)
  - Virtualizado (HRTF-based)
  - Downmix stereo (simples L+R mix — menos CPU)

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **5.1/7.1 + head tracking simultaneamente**: Se o áudio é de um filme 2D em tela plana, o head tracking no áudio pode ser INDESEJÁVEL. O usuário espera que o áudio "venha da tela". Opção: fixar speakers na posição da tela virtual, não no espaço absoluto. Ofereça ambas opções.

> [!WARNING]
> **DTS e AC3 decode**: Decodificar DTS-HD e Dolby TrueHD requer licenças. Para uso open source pessoal, use FFmpeg que implementa decoders livres. DTS core e AC3 são decodificáveis sem licença. Dolby Atmos (object-based) NÃO é suportado nesta fase.

> [!IMPORTANT]
> **Channel mapping varia por codec**: AAC 5.1 pode ter ordering L/R/C/LFE/SL/SR. AC3 pode ter L/C/R/SL/SR/LFE. FFmpeg normaliza isso, mas verifique o `channel_layout` do `AVFrame` depois do decode, não assuma.

---

## 5. Hand Tracking

### Conceito

Controlar o player com gestos de mão naturais, sem controllers.

```
Gestos Básicos:
  👆 Pinch (polegar + indicador) = Select/Click
  ✋ Palm up = Mostrar controles
  ✊ Fist = Ocultar controles
  👉 Point = Raycasting (apontar para UI)
  🤏 Pinch + drag = Seek na timeline / Mover tela
```

### Tarefas

- [ ] **T5.1** — Habilitar **hand tracking** via OpenXR:
  ```cpp
  // Extension necessária
  XR_EXT_hand_tracking
  
  // Criar hand tracker
  XrHandTrackerCreateInfoEXT createInfo = {XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT};
  createInfo.hand = XR_HAND_LEFT_EXT;
  createInfo.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
  
  XrHandTrackerEXT leftHandTracker;
  xrCreateHandTrackerEXT(session, &createInfo, &leftHandTracker);
  // Repetir para mão direita
  ```
- [ ] **T5.2** — Obter **joint positions** a cada frame:
  ```cpp
  // 26 joints por mão (XR_HAND_JOINT_COUNT_EXT)
  XrHandJointLocationEXT jointLocations[XR_HAND_JOINT_COUNT_EXT];
  XrHandJointLocationsEXT locations = {XR_TYPE_HAND_JOINT_LOCATIONS_EXT};
  locations.jointCount = XR_HAND_JOINT_COUNT_EXT;
  locations.jointLocations = jointLocations;
  
  xrLocateHandJointsEXT(leftHandTracker, &locateInfo, &locations);
  
  // Joints úteis:
  // XR_HAND_JOINT_INDEX_TIP_EXT  → ponta do dedo indicador (raycasting)
  // XR_HAND_JOINT_THUMB_TIP_EXT  → ponta do polegar (pinch detection)
  // XR_HAND_JOINT_PALM_EXT       → centro da palma
  ```
- [ ] **T5.3** — Implementar **detecção de gestos**:
  ```cpp
  struct GestureDetector {
      bool detectPinch(const XrHandJointLocationEXT* joints) {
          // Pinch = distância entre polegar e indicador < threshold
          auto thumbTip = joints[XR_HAND_JOINT_THUMB_TIP_EXT].pose.position;
          auto indexTip = joints[XR_HAND_JOINT_INDEX_TIP_EXT].pose.position;
          float distance = glm::distance(
              glm::vec3(thumbTip.x, thumbTip.y, thumbTip.z),
              glm::vec3(indexTip.x, indexTip.y, indexTip.z)
          );
          return distance < 0.02f; // 2cm threshold
      }
      
      bool detectPointingRay(const XrHandJointLocationEXT* joints, 
                             glm::vec3& origin, glm::vec3& direction) {
          // Ray do dedo indicador
          auto indexTip = joints[XR_HAND_JOINT_INDEX_TIP_EXT];
          auto indexDistal = joints[XR_HAND_JOINT_INDEX_DISTAL_EXT];
          
          origin = toGlm(indexTip.pose.position);
          glm::vec3 distal = toGlm(indexDistal.pose.position);
          direction = glm::normalize(origin - distal);
          return true;
      }
  };
  ```
- [ ] **T5.4** — Implementar **raycasting** a partir do dedo indicador:
  - Ray saindo da ponta do indicador na direção de apontar
  - Intersecção com painéis de UI (mesmo sistema do controller)
  - Visual feedback: bolinha na ponta do dedo + ponto de interseção
- [ ] **T5.5** — Mapear **gestos a ações**:
  | Gesto | Ação |
  |-------|------|
  | Pinch tap (rápido) | Click / Select |
  | Pinch hold + drag | Seek na timeline / Mover tela |
  | Palm up (palma aberta virada para cima) | Mostrar controles |
  | Palm down / fist | Ocultar controles |
  | Pinch + duas mãos afastando | Zoom / Resize da tela |
- [ ] **T5.6** — **Fallback graceful**: Se hand tracking perde rastreamento (mão fora do campo de visão), não crashar — esconder feedback visual e aguardar retorno.
- [ ] **T5.7** — **Haptics feedback** (não disponível com hands — usar feedback visual/sonoro):
  - Som sutil de "click" ao selecionar
  - Animação do botão ao ser pressionado

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Hand tracking é impreciso comparado a controllers**: Pinch detection tem ~5mm de incerteza. Botões da UI DEVEM ser maiores quando hand tracking está ativo (mínimo 48x48mm virtual). Adicione hysteresis: pinch start em 1.5cm, pinch end em 2.5cm para evitar flickering.

> [!CAUTION]
> **Jitter no raycasting**: O ray do dedo indicador treme MUITO mais que o ray do controller. Aplique smoothing agressivo (exponential moving average com alpha = 0.3) e dead zones maiores. Sem smoothing, a UI é impossível de usar.
> ```cpp
> // Smoothing do ray de hand tracking
> smoothedDirection = glm::mix(smoothedDirection, rawDirection, 0.3f);
> // mix com alpha baixo = mais suave, mas mais lag
> // Encontrar equilíbrio: 0.2 (muito suave) a 0.5 (responsivo mas tremido)
> ```

> [!WARNING]
> **Performance**: Hand tracking consome ~5-8% de CPU adicional. Monitore o impacto térmico quando hand tracking + decodificação de vídeo + ambiente 3D rodam simultaneamente.

> [!WARNING]
> **Transição controller ↔ hands**: O Quest 3 alterna automaticamente entre controllers e hand tracking. Detecte qual input está ativo e adapte a UI (tamanho de botões, smoothing do ray, feedback):
> ```cpp
> // Verificar se controllers estão ativos
> XrPath interactionProfile;
> xrGetCurrentInteractionProfile(session, topLevelPath, &interactionProfile);
> // Se interactionProfile == hand tracking, adaptar UI
> ```

> [!IMPORTANT]
> **Não combine gestos ambíguos**: "Fechar a mão" é parecido com "pinch". Use gestos bem distintos. Evite gestos que requerem poses desconfortáveis mantidas por longo tempo (ergonomics).

---

## 6. Codecs VP9 e AV1

### O que fazer

Adicionar suporte de decodificação por hardware para VP9 e AV1 (codecs de nova geração usados pelo YouTube e conteúdo WebM).

### Tarefas

- [ ] **T6.1** — Verificar suporte de **hardware decode** no Quest 3:
  ```rust
  // Verificar via MediaCodecList (JNI/Kotlin)
  fn check_hw_codec_support() -> CodecSupport {
      // Quest 3 (XR2 Gen 2) suporta:
      // - VP9 Profile 0/2: até 4K@60fps HW decode ✅
      // - AV1: suporte parcial, depende da ROM/firmware ⚠️
      
      let vp9_decoder = MediaCodecList.findDecoderForFormat("video/x-vnd.on2.vp9");
      let av1_decoder = MediaCodecList.findDecoderForFormat("video/av01");
      
      CodecSupport {
          vp9: vp9_decoder.is_some(),
          av1: av1_decoder.is_some(),
      }
  }
  ```
- [ ] **T6.2** — Implementar **VP9 HW decoder** no Rust:
  - Criar `AMediaCodec` para `"video/x-vnd.on2.vp9"`
  - VP9 não tem SPS/PPS — configurar apenas resolução e color format
  - Suportar VP9 Profile 0 (8-bit) e Profile 2 (10-bit HDR)
- [ ] **T6.3** — Implementar **AV1 HW decoder** no Rust:
  - MIME type: `"video/av01"`
  - AV1 tem `OBU` (Open Bitstream Unit) como unidade de acesso
  - Codec-specific data: `av1C` configuration record
  - Fallback para software decode (`dav1d` via FFmpeg) se HW não disponível
- [ ] **T6.4** — **Fallback para software decode**:
  - Se HW decode não disponível, usar FFmpeg software decoder
  - AVISO ao usuário: "Decodificação por software — performance reduzida"
  - Limitar resolução em software mode (máximo 1080p para VP9, 720p para AV1)
- [ ] **T6.5** — Atualizar UI de metadados para mostrar codec usado (HW vs SW)

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **AV1 HW no Quest 3 é inconsistente**: O suporte AV1 no XR2 Gen 2 depende do firmware da Meta. Algumas versões suportam HW decode, outras não. SEMPRE verifique em runtime via `MediaCodecList`. Nunca assuma que está disponível.

> [!WARNING]
> **VP9 Profile 2 (10-bit HDR)**: O Quest 3 suporta VP9 10-bit, mas o display é SDR (não HDR). Você precisará de tone mapping para converter HDR → SDR antes de exibir. Isso adiciona complexidade ao shader:
> ```glsl
> // Tone mapping HDR → SDR (Reinhard simples)
> vec3 tonemapReinhard(vec3 hdr) {
>     return hdr / (hdr + vec3(1.0));
> }
> ```

> [!IMPORTANT]
> **WebM containers**: VP9 e AV1 frequentemente vêm em containers WebM (Matroska subset). O demuxer FFmpeg já suporta WebM, mas garanta que o demuxer está configurado com `--enable-demuxer=matroska,webm`.

> [!NOTE]
> **AV1 software decode (dav1d) é pesado**: dav1d é o decoder AV1 mais otimizado, mas ainda usa ~200% CPU para 4K. No Quest 3 (8 cores ARM), isso é aceitável para 1080p mas problemático para 4K. Priorize HW decode.

---

## 7. Legendas Avançadas — ASS/SSA e PGS

### O que fazer

Suportar legendas estilizadas ASS/SSA (texto com posicionamento, cores, fontes, efeitos) e PGS (bitmap — Blu-ray).

### Tarefas

- [ ] **T7.1** — Implementar parser **ASS/SSA** no Rust:
  ```rust
  struct AssSubtitle {
      script_info: AssScriptInfo,
      styles: Vec<AssStyle>,
      events: Vec<AssEvent>,
  }
  
  struct AssStyle {
      name: String,
      font_name: String,
      font_size: f32,
      primary_color: Color,      // &HAABBGGRR format
      secondary_color: Color,
      outline_color: Color,
      back_color: Color,
      bold: bool,
      italic: bool,
      border_style: u8,
      outline: f32,
      shadow: f32,
      alignment: u8,             // Numpad position (1-9)
      margin_l: i32,
      margin_r: i32,
      margin_v: i32,
  }
  
  struct AssEvent {
      layer: i32,
      start_ms: u64,
      end_ms: u64,
      style: String,
      name: String,              // Character name
      text: String,              // Com override tags: {\pos(x,y)\fad(in,out)}
  }
  ```
- [ ] **T7.2** — Implementar renderizador **ASS** (C++):
  - Parsear override tags: `\pos`, `\an`, `\fad`, `\c`, `\fs`, `\b`, `\i`, `\move`
  - Posicionamento na tela baseado em alignment (1-9, estilo numpad)
  - Cores e estilos per-character
  - Usar SDF text rendering com suporte a múltiplas fontes
  - Tags avançadas para futura implementação: `\t` (animation), `\clip`, `\drawing`
- [ ] **T7.3** — Implementar parser **PGS** (Presentation Graphic Stream):
  ```rust
  // PGS são legendas bitmap (usadas em Blu-ray)
  // Formato: SUP container com segments
  
  struct PgsSubtitle {
      segments: Vec<PgsSegment>,
  }
  
  enum PgsSegment {
      PresentationComposition(PCS),  // Timing e posição
      WindowDefinition(WDS),          // Região na tela
      PaletteDefinition(PDS),         // Paleta de cores
      ObjectDefinition(ODS),          // Imagem bitmap RLE-compressed
      End,
  }
  
  struct PgsBitmap {
      width: u16,
      height: u16,
      x: u16,          // Posição na tela
      y: u16,
      pixels: Vec<u8>, // Indexados na paleta
      palette: Vec<[u8; 4]>, // RGBA
  }
  ```
- [ ] **T7.4** — Renderizar **PGS como textura** (C++):
  - Decodificar RLE do bitmap PGS
  - Aplicar paleta de cores
  - Criar textura GPU com o bitmap resultante
  - Posicionar como quad overlay na parte inferior da tela de vídeo
  - Escalar proporcionalmente ao tamanho da tela virtual
- [ ] **T7.5** — Seleção de **track de legenda** (embedded):
  - Listar todos os subtitle tracks do container (MKV, MP4)
  - Mostrar idioma de cada track
  - Selecionar via UI de controles
- [ ] **T7.6** — **Prioridade de carregamento** de legendas:
  1. Legenda externa selecionada pelo usuário
  2. Legenda embedded selecionada pelo usuário
  3. Auto-selecionar legenda no idioma do sistema

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **ASS rendering é extremamente complexo**: O formato ASS suporta animações, clipping paths, desenho vetorial, e efeitos em tempo real. Uma implementação completa é equivalente a um mini-engine gráfico. Para v0.3, implemente APENAS: posicionamento, cores, fontes, bold/italic, outline, fade. Tags de animação (`\t`, `\move` avançado) ficam para futuro.

> [!WARNING]
> **PGS resolução vs. tela virtual**: Bitmaps PGS são projetados para resolução fixa (1920x1080 no Blu-ray). Em VR, a "tela" pode ter qualquer tamanho. Escale o bitmap proporcionalmente ao tamanho da tela virtual, mas isso pode resultar em legendas borradas se muito ampliadas. Não há solução perfeita — PGS é bitmap.

> [!WARNING]
> **Múltiplas legendas simultâneas**: Alguns MKV têm legendas ASS de comentários que ocupam a tela inteira com efeitos. Se a renderização dessas legendas estiver causando drops de frame, permita que o usuário desabilite renderização de tags avançadas.

> [!IMPORTANT]
> **Fontes para ASS**: Legendas ASS referenciam fontes por nome (ex: "Arial", "Times New Roman"). O Quest 3 não tem essas fontes instaladas. Opções:
> 1. Embalar fontes comuns no APK (licenças permitem: Liberation Sans, Noto Sans)
> 2. Mapear nomes de fontes para fallbacks (Arial → Roboto, Times → Noto Serif)
> 3. Carregamento de fontes externas (pasta `fonts/` no armazenamento)

---

## 8. Fotos 360° e Fotos 3D

### O que fazer

Visualizar imagens estáticas em formato 360° e estereoscópico 3D.

### Tarefas

- [ ] **T8.1** — Detectar fotos 360° por **metadados EXIF/XMP**:
  ```rust
  // Tags que indicam foto 360°:
  // - EXIF: GPano:ProjectionType = "equirectangular"
  // - XMP: GPano:FullPanoWidthPixels, GPano:CroppedAreaImageWidthPixels
  // - Aspect ratio 2:1 + resolução alta = forte indicador
  
  fn detect_360_photo(path: &str) -> PhotoProjection {
      let exif = read_exif(path)?;
      if let Some(projection) = exif.get_xmp("GPano:ProjectionType") {
          return match projection.as_str() {
              "equirectangular" => PhotoProjection::Equirect360,
              _ => PhotoProjection::Flat,
          };
      }
      // Heurística: aspect ratio 2:1 + resolução > 4000px
      let (w, h) = get_dimensions(path)?;
      if (w as f32 / h as f32 - 2.0).abs() < 0.1 && w > 4000 {
          PhotoProjection::Equirect360
      } else {
          PhotoProjection::Flat
      }
  }
  ```
- [ ] **T8.2** — Carregar e decodificar imagens de alta resolução:
  - JPEG: `image` crate ou `turbojpeg` (mais rápido para imagens grandes)
  - PNG, WebP: `image` crate
  - Fazer decode em thread de background, mostrar placeholder enquanto carrega
  - Para imagens > 8K: decimate progressivamente (tiled loading)
- [ ] **T8.3** — Renderizar foto 360° na esfera VR:
  - Reutilizar a esfera e shaders de vídeo 360° (da fase 0.2)
  - Diferença: textura estática em vez de textura de vídeo atualizada por frame
  - Head tracking funciona igual
- [ ] **T8.4** — Renderizar foto 3D (SBS/OU) no quad virtual:
  - Reutilizar shaders SBS/OU da fase 0.2
  - Detectar por filename (`_sbs`, `_3d`, `_lr`)
- [ ] **T8.5** — **Viewer de fotos** com controles:
  - Próximo / Anterior (na pasta ou playlist)
  - Zoom (pinch ou thumbstick)
  - Pan (para fotos flat)
  - Slideshow automático (timer configurável)
- [ ] **T8.6** — Gerar thumbnails para fotos na biblioteca

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **Fotos 360° de alta resolução**: Uma foto equirectangular de 12K × 6K tem ~216 milhões de pixels. Descompactada em RGBA, ocupa ~864MB. NÃO carregue na memória de uma vez. Use tiled loading ou downscale para o tamanho máximo de textura suportado (Quest 3: 16384×16384 max texture size, mas 8192×4096 é mais prático para VRAM).

> [!IMPORTANT]
> **JPEG EXIF orientation**: Muitas câmeras salvam a orientação em EXIF em vez de rotacionar os pixels. Aplique a rotação EXIF antes de criar a textura, caso contrário a foto aparece girada.

---

## 9. Playlists

### O que fazer

Permitir ao usuário criar listas de reprodução organizadas.

### Tarefas

- [ ] **T9.1** — Criar tabela Room para playlists:
  ```kotlin
  @Entity
  data class Playlist(
      @PrimaryKey(autoGenerate = true) val id: Long = 0,
      val name: String,
      val createdAt: Instant,
      val updatedAt: Instant,
      val thumbnailPath: String?,
  )
  
  @Entity(
      primaryKeys = ["playlistId", "mediaUri"],
      foreignKeys = [ForeignKey(
          entity = Playlist::class,
          parentColumns = ["id"],
          childColumns = ["playlistId"],
          onDelete = ForeignKey.CASCADE
      )]
  )
  data class PlaylistItem(
      val playlistId: Long,
      val mediaUri: String,
      val title: String,
      val position: Int,          // Ordem na playlist
      val addedAt: Instant,
      val sourceType: SourceType,
  )
  ```
- [ ] **T9.2** — CRUD de playlists na UI:
  - Criar playlist (nome)
  - Adicionar mídia a uma playlist (long press no file browser → "Adicionar a playlist")
  - Remover itens
  - Reordenar itens (drag and drop — simplificado: botões ↑↓)
  - Renomear / excluir playlist
- [ ] **T9.3** — **Reprodução sequencial**:
  - Ao terminar um item, iniciar o próximo automaticamente
  - Opções: repeat all, repeat one, shuffle
- [ ] **T9.4** — UI de playlist durante reprodução:
  - Painel lateral com lista de itens
  - Highlight do item atual
  - Click para pular para item
- [ ] **T9.5** — Playlist especial **"Continuar Assistindo"** (automática):
  - Itens com reprodução parcial (não completados)
  - Ordenados por `lastPlayedAt` decrescente

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **Itens de playlist em servidores remotos**: Se uma playlist contém itens de um servidor SMB que está offline, o item deve ser marcado como "indisponível" sem crashar. Pule para o próximo item disponível.

> [!IMPORTANT]
> **Migration do banco**: Adição de tabelas `Playlist` e `PlaylistItem` requer Room migration da v0.2.

---

## 10. i18n — Espanhol

### O que fazer

Adicionar tradução para Espanhol como terceiro idioma.

### Tarefas

- [ ] **T10.1** — Criar `res/values-es/strings.xml`
- [ ] **T10.2** — Traduzir todas as strings existentes (PT-BR → ES)
- [ ] **T10.3** — Revisar plurais e formatação específica do Espanhol
- [ ] **T10.4** — Testar com locale Espanhol no Quest 3
- [ ] **T10.5** — Documentar processo de contribuição de traduções no README

### ⚠️ Cuidados e Armadilhas

> [!NOTE]
> **Espanhol regional**: Use `es` genérico (não `es-ES` ou `es-MX`). Isso cobre todos os locales espanhóis como fallback. Se quiser especializações futuras, crie `values-es-rMX` para México, etc.

> [!WARNING]
> **Strings no C++ (OpenXR UI)**: Garanta que TODAS as strings novas da v0.3 (controles de hand tracking, seletor de ambientes, etc.) passem pelo sistema de i18n. É fácil esquecer e hardcodar strings em inglês no C++.

---

## 11. Cuidados Transversais da Fase 0.3

### Budget de Performance Combinado

> [!CAUTION]
> **A fase 0.3 combina os features mais pesados**. Cenário worst-case: vídeo 4K 360° stereo + ambiente Cinema + áudio Ambisonics + hand tracking. Budget estimado:
>
> | Componente | CPU % | GPU % | RAM |
> |------------|-------|-------|-----|
> | Video HW Decode (4K HEVC) | ~5% | ~10% | ~200MB |
> | Ambisonics Rendering (FOA) | ~8% | 0% | ~50MB |
> | Environment Rendering (Cinema) | ~3% | ~25% | ~150MB |
> | Hand Tracking | ~7% | ~2% | ~30MB |
> | OpenXR Runtime | ~10% | ~15% | ~200MB |
> | OS + Android | ~15% | ~5% | ~1.5GB |
> | **TOTAL** | **~48%** | **~57%** | **~2.1GB** |
>
> Isso deixa margem apertada. Se térmico subir, simplifique o ambiente primeiro (switch para void), depois reduza áudio (Ambisonics → stereo downmix).

### Qualidade de Áudio

> [!IMPORTANT]
> **Sample rate**: O Quest 3 opera nativamente a 48kHz. Todos os processamentos de áudio (Ambisonics, HRTF, surround) devem operar a 48kHz. Se o conteúdo é 44.1kHz, faça resample ANTES do processamento espacial para evitar artefatos.

### Testes Especializados

> [!IMPORTANT]
> **Testes de conforto**: A fase 0.3 adiciona muitos elementos que podem causar desconforto VR (motion sickness). Teste cada feature com sessões de ≥ 30 min:
> - Áudio Ambisonics com head tracking: a rotação do campo sonoro está correta?
> - Hand tracking: é confortável usar por 10+ minutos sem fadiga de braços?
> - Passthrough: a tela virtual é estável no espaço real?
> - Ambientes: algum elemento causa desconforto visual?

### Backward Compatibility

> [!NOTE]
> **Quest 2 / Quest Pro**: 
> - Quest 2: NÃO tem passthrough colorido (usar monocromático). Hand tracking funciona mas com precisão menor. VP9 HW decode OK, AV1 HW não suportado.
> - Quest Pro: Passthrough colorido. Eye tracking disponível (mas eye tracking é v0.5). Hand tracking superior.
> 
> Use feature detection para adaptar:
> ```cpp
> bool hasColorPassthrough = xrExtensionSupported("XR_FB_passthrough");
> bool hasHandTracking = xrExtensionSupported("XR_EXT_hand_tracking");
> ```

---

## Definição de Pronto (Definition of Done) — v0.3

- [ ] Ambiente Cinema renderiza com ≥ 72 FPS e < 100K triângulos
- [ ] Ambiente Sala renderiza com ≥ 72 FPS
- [ ] Transição suave entre ambientes (< 3s de loading)
- [ ] Passthrough funciona com tela virtual estável no espaço
- [ ] Toggle ambiente ↔ passthrough funciona sem crash
- [ ] Áudio Ambisonics FOA reproduz com head tracking correto
- [ ] Áudio 5.1 virtualizado reproduz com posicionamento correto
- [ ] Áudio 7.1 virtualizado reproduz corretamente
- [ ] Toggle entre modos de áudio (stereo/virtualizado) funciona
- [ ] Hand tracking: pinch select funciona em todos os botões da UI
- [ ] Hand tracking: seek na timeline funciona via pinch drag
- [ ] Hand tracking: transição controller ↔ hands é seamless
- [ ] VP9 Profile 0 decodifica via HW no Quest 3
- [ ] AV1 decodifica (HW se disponível, fallback SW com aviso)
- [ ] Legendas ASS renderizam com estilo correto (cores, fontes, posição)
- [ ] Legendas PGS renderizam como bitmap escalado
- [ ] Fotos 360° exibem com head tracking
- [ ] Fotos 3D (SBS/OU) exibem com profundidade correta
- [ ] Playlists: criar, adicionar, remover, reordenar, reproduzir sequencialmente
- [ ] Espanhol: todas as strings traduzidas, testado com locale ES
- [ ] Session de 45 min com ambiente Cinema + vídeo 4K + áudio 5.1 sem crash ou throttling severo
- [ ] Nenhuma regressão nos testes da v0.1 e v0.2

---

*Fase 0.3 — Estimativa: 10-16 semanas para desenvolvedor solo experiente*  
*Esta é a fase mais complexa do projeto. Considere dividir em sub-sprints.*
