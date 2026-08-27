# Fase 0.5 — Premium

> **Objetivo**: Implementar funcionalidades premium que elevam a experiência — múltiplas telas virtuais simultâneas no espaço 3D, interface controlada por rastreamento ocular (Eye Tracking), novos ambientes imersivos (Espaço cósmico), e iluminação dinâmica reativa ao conteúdo em reprodução.  
> **Pré-requisito**: Fase 0.4 completa e estável.  
> **Resultado esperado**: O player VR se torna um ambiente de multitarefa avançado com seleção intuitiva por olhar, ambientes ricos que reagem à mídia reproduzida, e a possibilidade de assistir a múltiplos conteúdos lado a lado.

---

## 📋 Índice

1. [Múltiplas Telas Virtuais](#1-múltiplas-telas-virtuais)
2. [Eye Tracking — Seleção por Olhar](#2-eye-tracking--seleção-por-olhar)
3. [Ambientes Virtuais Avançados](#3-ambientes-virtuais-avançados)
4. [Ajuste de Iluminação e Cor do Ambiente](#4-ajuste-de-iluminação-e-cor-do-ambiente)
5. [Cuidados Transversais da Fase 0.5](#5-cuidados-transversais-da-fase-05)
6. [Definição de Pronto (Definition of Done) — v0.5](#6-definição-de-pronto-definition-of-done--v05)

---

## 1. Múltiplas Telas Virtuais

### Conceito

Atende ao requisito **RF-2D-009** (Múltiplas telas virtuais simultâneas). O usuário pode abrir 2-3 telas flutuantes no espaço VR, cada uma reproduzindo conteúdo independente. Isso permite cenários como picture-in-picture, telas lado a lado, ou assistir um filme enquanto monitora uma câmera IP.

```
Múltiplas Telas no Espaço VR:

    Layout PIP (Picture-in-Picture):         Layout Lado a Lado:

    ┌─────────────────────┐                 ┌──────────┐  ┌──────────┐
    │                     │  ┌──────┐       │          │  │          │
    │   Tela Principal    │  │ PIP  │       │  Tela 1  │  │  Tela 2  │
    │     (foco ativo)    │  │ (25%)│       │  (50%)   │  │  (50%)   │
    │                     │  └──────┘       │          │  │          │
    └─────────────────────┘                 └──────────┘  └──────────┘
    🔊 Áudio 100%          🔇 Mudo          🔊 100%       🔈 20%

Pipeline por tela:
  Arquivo → Demuxer → Decoder (MediaCodec) → Textura OES → Quad 3D
                    → Audio Decoder → Oboe Output (mixado)
```

### Tarefas

- [ ] **T1.1** — Refatorar para arquitetura **multi-sessão** (Rust):
  - O `PlaybackController` atual gerencia UMA sessão. Criar `SessionManager` que gerencia múltiplas `PlaybackSession` independentes
  - Cada sessão tem seu próprio demuxer, decoder, sync manager e audio output
  ```rust
  /// Identificador único de uma sessão de reprodução
  pub type SessionId = u32;

  /// Gerencia múltiplas sessões de reprodução simultâneas
  pub struct SessionManager {
      sessions: HashMap<SessionId, PlaybackController>,
      focused_id: Option<SessionId>,
      next_id: SessionId,
      max_sessions: usize, // Limite imposto: 3 (hardware do Quest 3)
  }

  impl SessionManager {
      pub fn new() -> Self {
          Self {
              sessions: HashMap::new(),
              focused_id: None,
              next_id: 0,
              max_sessions: 3,
          }
      }

      /// Cria uma nova sessão de reprodução
      /// Retorna None se o limite de sessões foi atingido
      pub fn create_session(&mut self) -> Option<SessionId> {
          if self.sessions.len() >= self.max_sessions {
              return None;
          }
          let id = self.next_id;
          self.next_id += 1;
          self.sessions.insert(id, PlaybackController::new());
          
          // Se é a primeira sessão, auto-foco
          if self.focused_id.is_none() {
              self.focused_id = Some(id);
          }
          Some(id)
      }

      /// Define qual sessão tem foco (áudio principal + controles)
      pub fn set_focus(&mut self, id: SessionId) {
          if self.sessions.contains_key(&id) {
              self.focused_id = Some(id);
              self.update_audio_mix();
          }
      }

      /// Atualiza volume de cada sessão baseado no foco
      fn update_audio_mix(&self) {
          for (id, session) in &self.sessions {
              if Some(*id) == self.focused_id {
                  session.set_volume(1.0); // Volume total
              } else {
                  session.set_volume(0.15); // Atenuado (~15%)
              }
          }
      }

      /// Fecha uma sessão e libera recursos
      pub fn close_session(&mut self, id: SessionId) {
          if let Some(session) = self.sessions.remove(&id) {
              session.stop();
              // Se a sessão fechada era a focada, focar noutra
              if self.focused_id == Some(id) {
                  self.focused_id = self.sessions.keys().next().copied();
              }
          }
      }
  }
  ```

- [ ] **T1.2** — Implementar **múltiplos quads de vídeo** no C++:
  - Cada sessão tem seu próprio `ImageReader`, `AHardwareBuffer` e quad 3D
  - O render loop itera sobre todos os quads ativos
  ```cpp
  struct VirtualScreen {
      SessionId sessionId;
      
      // Textura/Surface
      AImageReader* imageReader = nullptr;
      GLuint oesTextureId = 0;
      ANativeWindow* nativeWindow = nullptr;
      
      // Transform no espaço 3D
      glm::vec3 position = {0, 1.5f, -3.0f};
      glm::vec3 scale = {3.0f, 1.6875f, 1.0f}; // 16:9
      float aspectRatio = 16.0f / 9.0f;
      
      // Estado
      bool isFocused = false;
      float alpha = 1.0f;         // Para fade de PIP
      float borderGlow = 0.0f;    // Indicador visual de foco
  };

  class MultiScreenRenderer {
      std::vector<VirtualScreen> m_screens;
      static constexpr int MAX_SCREENS = 3;
      
  public:
      /// Adiciona uma nova tela. Retorna nullptr se limite atingido.
      VirtualScreen* addScreen() {
          if (m_screens.size() >= MAX_SCREENS) return nullptr;
          
          VirtualScreen screen;
          screen.sessionId = /* from SessionManager */;
          
          // Criar ImageReader + OES texture (mesmo padrão da tela principal, T2.7 da v0.1)
          AImageReader_newWithUsage(1920, 1080, AIMAGE_FORMAT_PRIVATE,
              AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, 4, &screen.imageReader);
          
          glGenTextures(1, &screen.oesTextureId);
          glBindTexture(GL_TEXTURE_EXTERNAL_OES, screen.oesTextureId);
          // Configurar sampler...
          
          AImageReader_getWindow(screen.imageReader, &screen.nativeWindow);
          m_screens.push_back(screen);
          return &m_screens.back();
      }
      
      /// Renderiza todas as telas ativas
      void renderAll(const glm::mat4 viewProj[2]) {
          for (auto& screen : m_screens) {
              // Atualizar textura OES se novo frame disponível
              AImage* image = nullptr;
              if (AImageReader_acquireLatestImage(screen.imageReader, &image) == AMEDIA_OK) {
                  // Obter AHardwareBuffer e atualizar textura OES
                  // (mesmo mecanismo de T3.5 da v0.1)
                  AImage_delete(image);
              }
              
              // Culling: não renderizar se a tela está atrás do usuário
              // (economiza GPU significativamente com múltiplas telas)
              if (!isInFrontOfUser(screen.position)) continue;
              
              // Renderizar quad com shader de vídeo
              glUseProgram(m_videoShader);
              setUniform("uAlpha", screen.alpha);
              setUniform("uBorderGlow", screen.isFocused ? screen.borderGlow : 0.0f);
              
              glm::mat4 model = glm::translate(glm::mat4(1.0f), screen.position)
                              * glm::scale(glm::mat4(1.0f), screen.scale);
              setUniform("uModel", model);
              
              glBindTexture(GL_TEXTURE_EXTERNAL_OES, screen.oesTextureId);
              drawQuad();
          }
      }
  };
  ```

- [ ] **T1.3** — Implementar **mixagem de áudio** entre sessões (Rust):
  - Tela com foco: volume 100%
  - Telas sem foco: volume ~15% (atenuado, não mudo — permite monitorar)
  - Opção "Solo": mutar todas as telas exceto a focada
  - A troca de foco suaviza o volume (fade de ~200ms para evitar corte abrupto)
  ```rust
  /// Suavização de volume para evitar corte abrupto ao trocar foco
  struct VolumeSmooth {
      current: f32,
      target: f32,
      speed: f32, // Unidades por segundo
  }
  
  impl VolumeSmooth {
      fn new(initial: f32) -> Self {
          Self { current: initial, target: initial, speed: 5.0 }
      }
      
      fn set_target(&mut self, target: f32) {
          self.target = target.clamp(0.0, 1.0);
      }
      
      /// Chamar a cada bloco de áudio (~5ms)
      fn update(&mut self, dt: f32) -> f32 {
          if (self.current - self.target).abs() < 0.001 {
              self.current = self.target;
          } else {
              let direction = if self.target > self.current { 1.0 } else { -1.0 };
              self.current += direction * self.speed * dt;
              self.current = self.current.clamp(0.0, 1.0);
          }
          self.current
      }
  }
  ```

- [ ] **T1.4** — Implementar **interação espacial independente** por tela (C++):
  - Raycasting do controller detecta qual tela está sob o cursor
  - Grip + Thumbstick: mover/redimensionar a tela sob foco (mesma mecânica da T3.6 v0.1)
  - Trigger na tela: define foco (áudio + controles)
  - Double-tap A/X: abrir nova tela (abre file browser para selecionar conteúdo)
  - Long press B/Y: fechar a tela sob foco
  ```cpp
  void handleMultiScreenInput(const OVR::Vector2f& thumbstick, 
                               bool triggerDown, bool gripHeld) {
      // Raycast contra todos os quads de tela
      int hitScreenIndex = -1;
      float hitDistance = FLT_MAX;
      
      for (int i = 0; i < m_screens.size(); i++) {
          float d = raycastQuad(m_rayOrigin, m_rayDir, 
                                m_screens[i].position, m_screens[i].scale);
          if (d > 0 && d < hitDistance) {
              hitDistance = d;
              hitScreenIndex = i;
          }
      }
      
      if (triggerDown && hitScreenIndex >= 0) {
          // Trocar foco
          for (auto& s : m_screens) s.isFocused = false;
          m_screens[hitScreenIndex].isFocused = true;
          // Notificar Rust para atualizar mixagem de áudio
          set_session_focus(m_screens[hitScreenIndex].sessionId);
      }
      
      if (gripHeld && hitScreenIndex >= 0) {
          // Mover/redimensionar a tela focada
          m_screens[hitScreenIndex].position.z += thumbstick.y * 0.02f;
          m_screens[hitScreenIndex].position.y += thumbstick.x * 0.02f;
      }
  }
  ```

- [ ] **T1.5** — Implementar **UI de controles por tela** (Kotlin):
  - Cada tela tem seu próprio painel de controles (play/pause/seek/volume) posicionado abaixo dela
  - Apenas a tela focada mostra controles (as outras escondem, economizando GPU)
  - Botão "+" no menu para adicionar nova tela
  - Indicador visual de foco: borda luminosa na tela ativa

- [ ] **T1.6** — Implementar **layout presets** (C++/Kotlin):
  - Presets de arranjo: PIP (grande + pequena no canto), Lado a Lado (2 iguais), Empilhado (2 vertical)
  - Botão no menu para aplicar preset (reposiciona todas as telas automaticamente)
  ```cpp
  void applyLayoutPreset(LayoutPreset preset) {
      switch (preset) {
          case LayoutPreset::PIP:
              m_screens[0].position = {0, 1.5f, -3.0f};
              m_screens[0].scale = {3.0f, 1.6875f, 1.0f};
              if (m_screens.size() > 1) {
                  m_screens[1].position = {1.8f, 2.2f, -2.5f};
                  m_screens[1].scale = {0.8f, 0.45f, 1.0f};
              }
              break;
          case LayoutPreset::SIDE_BY_SIDE:
              m_screens[0].position = {-1.6f, 1.5f, -3.0f};
              m_screens[0].scale = {1.4f, 0.7875f, 1.0f};
              if (m_screens.size() > 1) {
                  m_screens[1].position = {1.6f, 1.5f, -3.0f};
                  m_screens[1].scale = {1.4f, 0.7875f, 1.0f};
              }
              break;
          // ... STACKED, etc.
      }
  }
  ```

- [ ] **T1.7** — Expor **bridge FFI** para multi-sessão (Rust → C++):
  - Novas funções `extern "C"` para gerenciar sessões
  ```rust
  #[no_mangle]
  pub extern "C" fn create_playback_session() -> i32 {
      // Retorna session_id ou -1 se limite atingido
  }

  #[no_mangle]
  pub extern "C" fn start_session_playback(session_id: i32, path: *const c_char) {
      // Inicia reprodução na sessão especificada
  }

  #[no_mangle]
  pub extern "C" fn set_session_focus(session_id: i32) {
      // Troca foco de áudio
  }

  #[no_mangle]
  pub extern "C" fn close_playback_session(session_id: i32) {
      // Para e libera a sessão
  }

  #[no_mangle]
  pub extern "C" fn get_session_frame(session_id: i32) -> *mut AHardwareBuffer {
      // Retorna o frame mais recente da sessão
  }
  ```

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Limite de instâncias MediaCodec**: O Quest 3 (XR2 Gen 2) suporta ~4 instâncias simultâneas de MediaCodec. Porém, na prática, 1 instância de 4K HW decode + 2 de 1080p é o máximo seguro. Exceder causa falha silenciosa (instâncias anteriores são destruídas pelo sistema). **Imponha limite rígido de 3 telas** e reduza resolução de telas não-focadas automaticamente para 1080p ou inferior.
> ```rust
> // Ao criar sessão, verificar capacidade real do hardware
> fn can_create_session(&self) -> bool {
>     let current_load: u64 = self.sessions.values()
>         .map(|s| s.video_resolution().pixels())
>         .sum();
>     // Budget total: ~2x 4K equivalente (2 * 3840*2160 = ~16.5M pixels)
>     current_load + (1920 * 1080) < 16_500_000
> }
> ```

> [!CAUTION]
> **Áudio caótico é insuportável**: Ouvir múltiplas trilhas de áudio não relacionadas simultaneamente causa confusão cognitiva severa. O automute para telas sem foco NÃO é opcional — é requisito de conforto. Mesmo o volume atenuado (15%) deve ser desativável via opção "Solo mode" (mudo total nas telas sem foco).

> [!WARNING]
> **GPU overdraw com múltiplas texturas OES**: Cada tela é um fullscreen quad texturizado com uma textura externa. 3 telas = 3x o custo de fragment shading de vídeo. Aplique **frustum culling** agressivo: telas que estão completamente atrás do usuário NÃO devem ser renderizadas. Telas parcialmente fora do FOV podem ser skipadas se o benefício justificar.

> [!WARNING]
> **Seek em múltiplas sessões**: O seek atual (`PlaybackController::seek`) mata e recria threads. Com 3 sessões, 3 seeks simultâneos criam/destroem 9 threads. Garanta que os `thread::join` não bloqueiem uns aos outros. Considere uma thread pool compartilhada em vez de threads dedicadas por sessão.

> [!IMPORTANT]
> **Memória**: Cada sessão de playback com textura 4K consome ~200-400MB. 3 sessões podem chegar a 1.2GB só de video pipeline. Monitore uso de memória e recuse criar sessão se `available_memory < 500MB`.

---

## 2. Eye Tracking — Seleção por Olhar

### Conceito

Atende aos requisitos **RF-UI-006** (Eye Tracking — seleção por olhar + foveated UI) e **RF-UI-009** (Gaze-based UI como fallback sem controller). O Quest 3 possui sensores de rastreamento ocular infravermelho capazes de detectar a posição do olhar do usuário. Isso permite:
- Navegação na UI sem mãos (dwell time activation)
- Foco automático na tela que o usuário está olhando (multi-telas)
- UI adaptativa que enfatiza elementos sob o olhar (foveated UI)

```
Eye Tracking Interaction Flow:

  Olhar do usuário ──► Ray do olho ──► Interseção com UI
                                            │
                              ┌─────────────┴─────────────┐
                              │ Hover: botão cresce/brilha │
                              │ (foveated UI feedback)     │
                              └─────────────┬─────────────┘
                                            │
                                    Dwell Timer ≥ 1.5s?
                                     /              \
                                   Sim              Não
                                    │                │
                              ┌─────┴─────┐   (continua esperando
                              │  Ação!    │    ou olhar saiu)
                              │ + feedback│
                              │  sonoro   │
                              └───────────┘

  Prioridade de Input:
  1. Controller (se ativo e apontando para UI) → raycast do controller
  2. Hand tracking (se ativo) → raycast do dedo indicador  
  3. Eye tracking (fallback) → raycast do olhar + dwell
```

### Tarefas

- [ ] **T2.1** — Inicializar **extensão OpenXR de eye tracking** (C++):
  - Solicitar `XR_EXT_eye_gaze_interaction` na criação da instância
  - Configurar action e action space para o gaze
  ```cpp
  // Em GetExtensions() do VRPlayerApp
  extensions.push_back(XR_EXT_EYE_GAZE_INTERACTION_EXTENSION_NAME);

  // Criar action de eye gaze
  XrActionCreateInfo eyeActionInfo{XR_TYPE_ACTION_CREATE_INFO};
  strcpy(eyeActionInfo.actionName, "eye_gaze_pose");
  strcpy(eyeActionInfo.localizedActionName, "Eye Gaze Pose");
  eyeActionInfo.actionType = XR_ACTION_TYPE_POSE_INPUT;
  XrAction eyeGazeAction;
  xrCreateAction(m_actionSet, &eyeActionInfo, &eyeGazeAction);

  // Suggested binding
  XrActionSuggestedBinding eyeBinding;
  eyeBinding.action = eyeGazeAction;
  xrStringToPath(m_instance, "/user/eyes_ext/input/gaze_ext/pose",
                 &eyeBinding.binding);

  // Criar action space para o gaze
  XrActionSpaceCreateInfo spaceInfo{XR_TYPE_ACTION_SPACE_CREATE_INFO};
  spaceInfo.action = eyeGazeAction;
  spaceInfo.poseInActionSpace = {{0, 0, 0, 1}, {0, 0, 0}};
  XrSpace eyeGazeSpace;
  xrCreateActionSpace(m_session, &spaceInfo, &eyeGazeSpace);
  ```

- [ ] **T2.2** — Obter **pose do olhar** a cada frame e fazer raycasting (C++):
  ```cpp
  struct EyeTrackingState {
      bool valid = false;
      glm::vec3 gazeOrigin;
      glm::vec3 gazeDirection;
      float confidence = 0.0f; // 0-1, quão confiável é o tracking
  };

  EyeTrackingState getEyeGaze(XrTime displayTime) {
      EyeTrackingState state;
      
      XrSpaceLocation gazeLocation{XR_TYPE_SPACE_LOCATION};
      
      // Verificar se há extensão de velocidade (Meta-specific)
      XrEyeGazeSampleTimeEXT sampleTime{XR_TYPE_EYE_GAZE_SAMPLE_TIME_EXT};
      gazeLocation.next = &sampleTime;
      
      XrResult result = xrLocateSpace(m_eyeGazeSpace, m_stageSpace,
                                       displayTime, &gazeLocation);
      
      if (XR_SUCCEEDED(result) && 
          (gazeLocation.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) {
          state.valid = true;
          state.gazeOrigin = toGlm(gazeLocation.pose.position);
          
          // Extrair direção do quaternion de orientação
          glm::quat q = toGlm(gazeLocation.pose.orientation);
          state.gazeDirection = q * glm::vec3(0, 0, -1); // Forward
          
          // Confiança baseada em quão recente é o sample
          XrDuration age = displayTime - sampleTime.time;
          state.confidence = (age < 50000000) ? 1.0f : 0.5f; // <50ms = bom
      }
      
      return state;
  }
  ```

- [ ] **T2.3** — Implementar **Dwell Time Activation** com feedback visual (C++):
  ```cpp
  struct DwellSelector {
      int hoveredElementId = -1;
      float dwellTimer = 0.0f;
      float dwellThreshold = 1.5f; // Configurável pelo usuário (1.0-2.5s)
      float dwellProgress = 0.0f;  // 0-1, para renderizar indicador circular
      
      // Anti-jitter: só muda de elemento se olhar ficou fora por >200ms
      int candidateElement = -1;
      float exitTimer = 0.0f;
      static constexpr float EXIT_HYSTERESIS = 0.2f;
      
      enum class Result { NONE, HOVERING, ACTIVATED };
      
      Result update(int hitElementId, float dt) {
          if (hitElementId == hoveredElementId && hitElementId >= 0) {
              // Continua olhando pro mesmo elemento
              dwellTimer += dt;
              dwellProgress = std::min(dwellTimer / dwellThreshold, 1.0f);
              exitTimer = 0.0f;
              
              if (dwellTimer >= dwellThreshold) {
                  dwellTimer = 0.0f;
                  dwellProgress = 0.0f;
                  return Result::ACTIVATED;
              }
              return Result::HOVERING;
          } else {
              // Olhar mudou de elemento
              if (hitElementId != candidateElement) {
                  candidateElement = hitElementId;
                  exitTimer = 0.0f;
              }
              
              exitTimer += dt;
              if (exitTimer >= EXIT_HYSTERESIS) {
                  // Confirma mudança após histerese
                  hoveredElementId = candidateElement;
                  dwellTimer = 0.0f;
                  dwellProgress = 0.0f;
              }
              
              return Result::NONE;
          }
      }
  };
  ```

- [ ] **T2.4** — Implementar **Foveated UI** — feedback visual no elemento sob olhar (Kotlin + C++):
  - Enviar coordenadas do olhar (UV no quad de UI) do C++ para Kotlin via JNI
  - No Kotlin: animar Views — botão sob olhar cresce sutilmente (scale 1.0 → 1.15), ganha borda luminosa
  - Renderizar indicador circular de progresso de dwell no C++ (overlay sobre o quad de UI)
  ```cpp
  // Renderizar indicador circular de dwell progress
  void renderDwellIndicator(const glm::vec3& hitPoint, float progress) {
      if (progress <= 0.0f) return;
      
      // Círculo preenchido proporcionalmente ao dwell progress
      glUseProgram(m_dwellShader);
      setUniform("uProgress", progress);  // 0-1
      setUniform("uColor", glm::vec4(1.0f, 1.0f, 1.0f, 0.8f));
      setUniform("uPosition", hitPoint);
      setUniform("uRadius", 0.02f);  // 2cm no espaço 3D
      
      drawCircle(); // Quad com shader circular
  }
  ```

- [ ] **T2.5** — Implementar **detecção automática de modo de input** (C++):
  - Se controllers estão ativos → usar raycast do controller (prioritário)
  - Se hand tracking ativo → usar raycast do dedo indicador
  - Se nenhum dos dois → ativar eye tracking + dwell como fallback
  - Transição suave: ao pousar os controllers, esperar 5s de inatividade antes de trocar para eye tracking
  ```cpp
  enum class InputMode { CONTROLLER, HAND, EYE_GAZE };

  InputMode detectActiveInput(float dt) {
      if (m_controllerActive) {
          m_inputIdleTimer = 0.0f;
          return InputMode::CONTROLLER;
      }
      
      if (m_handTrackingActive) {
          m_inputIdleTimer = 0.0f;
          return InputMode::HAND;
      }
      
      // Nenhum controller/mão detectado por 5s → eye tracking
      m_inputIdleTimer += dt;
      if (m_inputIdleTimer > 5.0f && m_eyeTrackingAvailable) {
          return InputMode::EYE_GAZE;
      }
      
      return m_lastInputMode; // Manter o último enquanto não há certeza
  }
  ```

- [ ] **T2.6** — Integrar eye tracking com **foco de múltiplas telas** (T1 desta fase):
  - Quando eye tracking está ativo, a tela para qual o usuário OLHA recebe foco automaticamente
  - Delay de ~1s antes de trocar foco (evitar troca acidental ao passar o olhar rapidamente)

- [ ] **T2.7** — Solicitar **permissão de eye tracking** no runtime (Kotlin):
  ```kotlin
  // AndroidManifest.xml
  // <uses-permission android:name="com.oculus.permission.EYE_TRACKING" />
  // <uses-feature android:name="oculus.software.eye_tracking" android:required="false" />

  // Runtime permission request
  fun requestEyeTrackingPermission(activity: Activity) {
      if (ContextCompat.checkSelfPermission(activity,
              "com.oculus.permission.EYE_TRACKING") != PackageManager.PERMISSION_GRANTED) {
          ActivityCompat.requestPermissions(activity,
              arrayOf("com.oculus.permission.EYE_TRACKING"), REQUEST_EYE_TRACKING)
      }
  }
  ```

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Eye tracking é ruidoso — sacadas e microsacadas**: Os olhos fazem movimentos involuntários constantes (sacadas: 2-3 por segundo, microsacadas: muito mais frequentes). Aplicar o raio do olhar diretamente ao cursor SEM filtro torna a UI inutilizável. Use **suavização exponencial** agressiva (alpha ~0.15-0.25) e **snapping** para o botão mais próximo dentro de um raio de tolerância (~3cm virtual).
> ```cpp
> // Suavização do ray de eye tracking
> m_smoothedGazeDir = glm::mix(m_smoothedGazeDir, rawGazeDir, 0.2f);
> ```

> [!CAUTION]
> **Problema de Midas Touch**: Em UIs controladas por olhar, TUDO que o usuário olha é potencialmente ativado. O dwell time resolve parcialmente, mas o usuário precisa de "zonas seguras" — áreas da cena onde olhar NÃO ativa nada. Nunca coloque botões destrutivos (fechar, deletar) em posições onde o olhar naturalmente passa ao transitar entre outros elementos.

> [!WARNING]
> **Privacidade do eye tracking**: Dados de rastreamento ocular são biometricamente sensíveis (podem identificar condições médicas, padrões de atenção). NUNCA persista dados brutos de eye tracking. Use-os apenas em memória, frame a frame, sem histórico. Declarar na política de privacidade.

> [!IMPORTANT]
> **Eye tracking consome CPU**: O subsistema de eye tracking do Quest 3 adiciona ~3-5% de CPU. Combinado com hand tracking (~5-8%), decodificação de vídeo, e rendering de ambiente, o orçamento de CPU fica apertado. Desabilite eye tracking quando controllers estão ativos (não há necessidade de rodar ambos).

> [!NOTE]
> **Quest 2 não tem eye tracking**: O Quest 2 não possui sensores de eye tracking. Use feature detection (`xrGetSystemProperties` com `XrSystemEyeGazeInteractionPropertiesEXT`) e desabilite graciosamente. Quest Pro tem eye tracking de alta qualidade.

---

## 3. Ambientes Virtuais Avançados

### Conceito

Atende ao requisito **RF-ENV-004** (Espaço — Ambiente cósmico/espacial). Além do Cinema e Sala de Estar da v0.3, a v0.5 adiciona ambientes temáticos premium. O primeiro é o "Espaço" — uma plataforma flutuante em meio a estrelas, nebulosas e planetas distantes.

```
Ambiente Espacial:

   ✦   .  *        *    .    ✦     *       .
      *      ╭─── Nebulosa (Skybox HDR) ───╮     ✦
  .     ✦    │                             │  *
      *      │   ┌───────────────────┐     │     .
   ✦         │   │                   │     │  ✦
        .    │   │   Filme Rodando   │     │
   *         │   │                   │     │     *
      ✦      │   └───────────────────┘     │  .
  .     *    │     🪑 Plataforma flutuante │     ✦
       .     ╰─────────────────────────────╯
   ✦     *       .    ✦      *        .   *
         .  ✦       *    .       ✦         .

  Elementos:
  - Skybox cubemap HDR (estrelas + nebulosas + via láctea)
  - Plataforma flutuante minimalista (low-poly, ~5K triângulos)
  - Partículas lentas de poeira estelar (~200 billboards)
  - Áudio ambiente: "space drone" em loop (Oboe)
  - Tela de vídeo: flutuante acima da plataforma
```

### Tarefas

- [ ] **T3.1** — Criar **modelo 3D da plataforma espacial**:
  - Low-poly minimalista (~5K triângulos) — plataforma metálica/cristalina
  - Exportar como GLB com lightmap baked (sem luzes dinâmicas)
  - Textura ASTC compactada
  - Incluir `config.json` com posição da tela e spawn point

- [ ] **T3.2** — Implementar **skybox cubemap de alta qualidade** (C++):
  - 6 faces de 2048×2048 (ou 4096×4096 para mais detalhe) em ASTC
  - Renderizar ANTES de qualquer geometria (depth write off)
  - Rotação apenas (não translação) — skybox é "infinitamente distante"
  ```cpp
  void renderSkybox(const glm::mat4 viewProj[2]) {
      glDepthMask(GL_FALSE);
      glDepthFunc(GL_LEQUAL);
      
      glUseProgram(m_skyboxShader);
      
      // Remover translação da view matrix (só rotação)
      // Com multiview, ajustar para ambos olhos
      for (int eye = 0; eye < 2; eye++) {
          glm::mat4 viewRotOnly = glm::mat4(glm::mat3(viewMatrix[eye]));
          glm::mat4 vp = projMatrix[eye] * viewRotOnly;
          setUniform("uViewProj[" + std::to_string(eye) + "]", vp);
      }
      
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_CUBE_MAP, m_spaceSkyboxTexture);
      drawCube(); // Unit cube com normals/UVs
      
      glDepthMask(GL_TRUE);
      glDepthFunc(GL_LESS);
  }
  ```

- [ ] **T3.3** — Implementar **sistema de partículas leve** para poeira estelar (C++):
  - ~200 partículas billboard (sempre viradas para a câmera)
  - Movimento muito lento e aleatório (0.01 m/s — praticamente estático)
  - Instanced rendering para eficiência
  ```cpp
  struct StarParticle {
      glm::vec3 position;
      float size;       // 0.005 - 0.02m
      float brightness; // 0.3 - 1.0
  };

  class StarfieldRenderer {
      std::vector<StarParticle> m_particles;
      GLuint m_instanceVBO;
      static constexpr int PARTICLE_COUNT = 200;
      
  public:
      void init() {
          m_particles.resize(PARTICLE_COUNT);
          std::mt19937 rng(42); // Determinístico para reprodutibilidade
          std::uniform_real_distribution<float> posDist(-15.0f, 15.0f);
          std::uniform_real_distribution<float> sizeDist(0.005f, 0.02f);
          std::uniform_real_distribution<float> brightDist(0.3f, 1.0f);
          
          for (auto& p : m_particles) {
              p.position = {posDist(rng), posDist(rng), posDist(rng)};
              p.size = sizeDist(rng);
              p.brightness = brightDist(rng);
          }
          
          // Upload para VBO instanciado
          glGenBuffers(1, &m_instanceVBO);
          glBindBuffer(GL_ARRAY_BUFFER, m_instanceVBO);
          glBufferData(GL_ARRAY_BUFFER, m_particles.size() * sizeof(StarParticle),
                       m_particles.data(), GL_STATIC_DRAW);
      }
      
      void render(const glm::mat4& viewProj) {
          glEnable(GL_BLEND);
          glBlendFunc(GL_SRC_ALPHA, GL_ONE); // Additive blending para estrelas
          
          glUseProgram(m_particleShader);
          glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, PARTICLE_COUNT);
          
          glDisable(GL_BLEND);
      }
  };
  ```

- [ ] **T3.4** — Implementar **áudio de ambiência por ambiente** (Rust/Kotlin):
  - Cada ambiente tem uma faixa de áudio opcional em loop (arquivo `.ogg` no assets)
  - Volume do áudio ambiente reduz automaticamente quando vídeo está tocando (ducking)
  - Transição: fade out ao trocar de ambiente, fade in no novo
  ```kotlin
  class AmbientAudioManager(private val context: Context) {
      private var currentPlayer: MediaPlayer? = null
      
      fun playAmbient(environmentId: String) {
          currentPlayer?.release()
          
          val assetFd = try {
              context.assets.openFd("environments/${environmentId}/ambient.ogg")
          } catch (e: Exception) {
              return // Sem áudio de ambiência para este ambiente
          }
          
          currentPlayer = MediaPlayer().apply {
              setDataSource(assetFd.fileDescriptor, assetFd.startOffset, assetFd.length)
              isLooping = true
              setVolume(0.3f, 0.3f) // Volume baixo por padrão
              prepare()
              start()
          }
      }
      
      fun duck(factor: Float) {
          // Reduzir quando vídeo está tocando (factor = 0.1)
          currentPlayer?.setVolume(0.3f * factor, 0.3f * factor)
      }
      
      fun stopAmbient() {
          currentPlayer?.release()
          currentPlayer = null
      }
  }
  ```

- [ ] **T3.5** — Preparar **infraestrutura para ambientes adicionais futuros**:
  - Estrutura de diretório padronizada por ambiente em `assets/environments/`
  - `EnvironmentRegistry` que lista todos os ambientes disponíveis (built-in + custom da v1.0)
  - Transição entre ambientes: fade to black → unload anterior → load novo → fade in

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Overdraw de partículas com alpha blending**: Partículas transparentes com additive blending são caras em GPU mobile. 200 partículas = 200 quads com alpha blending, potencialmente cobrindo pixels já renderizados. Mantenha o count BAIXO (~200 máximo) e os quads PEQUENOS (< 2cm). Se FPS cair, desabilite partículas automaticamente via `AdaptiveQualityManager`.

> [!WARNING]
> **Skybox e conforto visual**: O skybox DEVE parecer estático e infinitamente distante. Qualquer movimento perceptível (estrelas "passando") causa motion sickness. A rotação lenta de nebulosas é aceitável se for < 0.5°/min (quase imperceptível). Partículas próximas em velocidade alta são proibidas.

> [!WARNING]
> **VRAM da skybox**: Cubemap 4096×4096 × 6 faces × ASTC 6x6 ≈ 30MB de VRAM. Somado ao ambiente 3D (~50MB), texturas de UI (~20MB) e texturas de vídeo (~200MB para 4K), o total fica ~300MB. O Quest 3 tem ~3GB de VRAM disponível — estamos bem, mas monitore com `glGetIntegerv(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX)` (se disponível) ou via OVR Metrics.

> [!NOTE]
> **Assets de áudio**: Arquivos `.ogg` de ambient loop devem ser pequenos (< 5MB) e desenhados para loop contínuo sem click/pop na junção. Use Audacity para verificar que início e fim do loop são seamless.

---

## 4. Ajuste de Iluminação e Cor do Ambiente

### Conceito

Atende ao requisito **RF-ENV-007** (Ajuste de iluminação e cor do ambiente). Permite que o usuário controle a iluminação do ambiente virtual e adiciona efeito de "screen glow" — a luz emitida pela tela de vídeo ilumina sutilmente o ambiente ao redor, como acontece numa sala real com TV ligada no escuro.

```
Screen Glow Effect:

  Sem Screen Glow:               Com Screen Glow:
  ┌──────────────────┐           ┌──────────────────┐
  │  ████████████    │           │  ████████████    │
  │  ████ TELA ████  │           │  ████ TELA ████  │
  │  ████████████    │           │  ████████████    │
  │                  │           │ ░░░░░░░░░░░░░░░░ │  ← Luz azulada
  │  (tudo escuro)   │           │ ░░░ reflexo ░░░░ │    refletindo
  │                  │           │ ░░░░░░░░░░░░░░░░ │    nas paredes
  └──────────────────┘           └──────────────────┘

  Controles do Usuário:
  - Brilho do ambiente: 0% (void) ─────── 100% (totalmente iluminado)
  - Temperatura de cor: 2700K (quente) ── 6500K (frio/neutro)
  - Screen glow: Desligado / Sutil / Forte
  - Night mode: Reduz azul da tela para conforto noturno
```

### Tarefas

- [ ] **T4.1** — Implementar **Screen Glow** (C++/GLSL):
  - Amostrar cor média do frame de vídeo atual (via mipmap ou downscale FBO)
  - Usar essa cor como fonte de luz difusa que ilumina o ambiente ao redor da tela
  - Suavizar temporalmente para evitar cintilação em cenas de corte rápido
  ```cpp
  class ScreenGlow {
      glm::vec3 m_currentColor = {0, 0, 0};
      glm::vec3 m_targetColor = {0, 0, 0};
      GLuint m_downsampleFBO = 0;
      GLuint m_downsampleTexture = 0;
      static constexpr int SAMPLE_SIZE = 4; // 4x4 pixels
      
  public:
      void init() {
          // FBO de 4x4 para downscale extremo do frame de vídeo
          glGenFramebuffers(1, &m_downsampleFBO);
          glGenTextures(1, &m_downsampleTexture);
          glBindTexture(GL_TEXTURE_2D, m_downsampleTexture);
          glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SAMPLE_SIZE, SAMPLE_SIZE,
                       0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
          glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
          
          glBindFramebuffer(GL_FRAMEBUFFER, m_downsampleFBO);
          glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                 GL_TEXTURE_2D, m_downsampleTexture, 0);
      }
      
      /// Amostrar cor média do frame de vídeo (chamar 1x por frame)
      void sampleVideoFrame(GLuint videoOESTexture) {
          // Blit o frame de vídeo para o FBO 4x4
          glBindFramebuffer(GL_FRAMEBUFFER, m_downsampleFBO);
          glViewport(0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
          
          glUseProgram(m_blitShader); // Shader simples OES → RGBA
          glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoOESTexture);
          drawFullscreenQuad();
          
          // Ler os 16 pixels (SÓ 64 bytes — custo desprezível)
          uint8_t pixels[SAMPLE_SIZE * SAMPLE_SIZE * 4];
          glReadPixels(0, 0, SAMPLE_SIZE, SAMPLE_SIZE, GL_RGBA,
                       GL_UNSIGNED_BYTE, pixels);
          
          // Média
          glm::vec3 avg(0);
          for (int i = 0; i < SAMPLE_SIZE * SAMPLE_SIZE; i++) {
              avg.r += pixels[i * 4] / 255.0f;
              avg.g += pixels[i * 4 + 1] / 255.0f;
              avg.b += pixels[i * 4 + 2] / 255.0f;
          }
          avg /= float(SAMPLE_SIZE * SAMPLE_SIZE);
          m_targetColor = avg;
      }
      
      /// Atualizar com suavização temporal
      void update(float dt) {
          // Interpolação suave (~0.3s de transição)
          float alpha = 1.0f - std::exp(-dt * 3.0f);
          m_currentColor = glm::mix(m_currentColor, m_targetColor, alpha);
      }
      
      glm::vec3 getGlowColor() const { return m_currentColor; }
  };
  ```

- [ ] **T4.2** — Atualizar **shaders do ambiente** para receber screen glow (GLSL):
  ```glsl
  // Uniforms adicionados ao shader PBR do ambiente
  uniform vec3 uScreenGlowColor;     // Cor média da tela
  uniform float uScreenGlowIntensity; // 0.0 (desligado) a 1.0 (forte)
  uniform vec3 uScreenPosition;       // Posição da tela no mundo
  uniform float uEnvironmentBrightness; // Slider 0.0 a 1.0

  void main() {
      // Iluminação PBR padrão (lightmap + IBL)
      vec3 lighting = sampleLightmap(vLightmapUV) * uEnvironmentBrightness;
      
      // Screen glow: tratar a tela como uma área light difusa
      vec3 toScreen = uScreenPosition - vWorldPosition;
      float distToScreen = length(toScreen);
      float attenuation = 1.0 / (1.0 + distToScreen * distToScreen * 0.5);
      
      // Normal facing: só ilumina superfícies viradas para a tela
      float nDotL = max(dot(vWorldNormal, normalize(toScreen)), 0.0);
      
      vec3 glow = uScreenGlowColor * uScreenGlowIntensity * attenuation * nDotL;
      lighting += glow;
      
      vec3 finalColor = albedo * lighting;
      FragColor = vec4(finalColor, 1.0);
  }
  ```

- [ ] **T4.3** — Implementar **controle de brilho do ambiente** (Kotlin + C++):
  - Slider na UI de configurações (0% = void total, 100% = iluminação original do lightmap)
  - Persistir em DataStore, enviar ao C++ via JNI
  - Aplicar via uniform `uEnvironmentBrightness`

- [ ] **T4.4** — Implementar **temperatura de cor / Night Mode** (GLSL):
  ```glsl
  // Aplicar ao shader de vídeo (na tela virtual)
  uniform float uColorTemperature; // 2700 (quente) a 6500 (neutro)
  uniform bool uNightModeEnabled;

  vec3 applyColorTemperature(vec3 color, float tempK) {
      // Aproximação de Planckian locus para color temperature
      // Kelvin → RGB multiplier (simplificado)
      float t = tempK / 100.0;
      vec3 tempColor;
      
      // Red
      if (t <= 66.0) tempColor.r = 1.0;
      else tempColor.r = clamp(1.292 * pow(t - 60.0, -0.1332), 0.0, 1.0);
      
      // Green
      if (t <= 66.0) tempColor.g = clamp(0.3901 * log(t) - 0.6318, 0.0, 1.0);
      else tempColor.g = clamp(1.130 * pow(t - 60.0, -0.0755), 0.0, 1.0);
      
      // Blue
      if (t >= 66.0) tempColor.b = 1.0;
      else if (t <= 19.0) tempColor.b = 0.0;
      else tempColor.b = clamp(0.5432 * log(t - 10.0) - 1.1962, 0.0, 1.0);
      
      return color * tempColor;
  }

  void main() {
      vec4 videoColor = texture(videoTexture, vTexCoord);
      
      if (uNightModeEnabled) {
          // Night mode: reduzir azul para conforto (~3000K)
          videoColor.rgb = applyColorTemperature(videoColor.rgb, 3000.0);
      } else if (uColorTemperature != 6500.0) {
          videoColor.rgb = applyColorTemperature(videoColor.rgb, uColorTemperature);
      }
      
      FragColor = videoColor;
  }
  ```

- [ ] **T4.5** — Implementar **UI de ajustes de iluminação** (Kotlin):
  - Slider "Brilho do ambiente" (0-100%)
  - Slider "Temperatura de cor" (2700K Quente ↔ 6500K Neutro)
  - Toggle "Screen Glow" (Desligado / Sutil / Forte)
  - Toggle "Modo Noturno" (reduz azul, atalho rápido: long press volume down)
  - Persistir preferências em DataStore
  - Preview em tempo real: mudanças visíveis imediatamente enquanto ajusta os sliders

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **NÃO use `glReadPixels` no render loop principal**: O `glReadPixels` no T4.1 para amostrar a cor média é aceitável APENAS porque lê 16 pixels (64 bytes) de um FBO minúsculo de 4×4. `glReadPixels` em texturas maiores paralisa o pipeline GPU e destrói o framerate. Se mesmo a leitura de 4×4 causar problemas de performance, mova para um compute shader que escreve num SSBO lido por CPU no frame seguinte (duplo buffer).

> [!WARNING]
> **Screen glow "estroboscópico"**: Em cenas com cortes rápidos ou flashes (explosões, transições de clipe musical), a iluminação ambiente muda bruscamente e causa desconforto. A suavização temporal (T4.1, `mix` com alpha baseado em `exp(-dt * 3)`) é obrigatória. Se não for suficiente, aumentar o tempo de transição para ~0.5s.

> [!WARNING]
> **Night mode e fidelidade de cor**: Night mode reduz o componente azul, o que distorce as cores do vídeo. Avisar o usuário com tooltip: "Modo Noturno ativo — cores alteradas para reduzir luz azul." Não ativar automaticamente — sempre por escolha do usuário.

> [!IMPORTANT]
> **Performance do screen glow**: O cálculo de screen glow adiciona um draw call extra (blit do vídeo para FBO 4×4) e uniforms extras no shader do ambiente. Em cenários pesados (8K + ambiente complexo), o `AdaptiveQualityManager` deve desabilitar screen glow como uma das primeiras medidas de economia.

---

## 5. Cuidados Transversais da Fase 0.5

### Orçamento de Performance Combinado

> [!CAUTION]
> **A fase 0.5 combina as features mais ambiciosas com o hardware mais estressado**. Cenário worst-case: múltiplas telas (2-3 MediaCodec + 2-3 texturas OES) + eye tracking (~3-5% CPU) + ambiente Espaço (skybox + partículas + screen glow) + foveated rendering (v0.4):
>
> | Componente | CPU % | GPU % | RAM |
> |------------|-------|-------|-----|
> | Video HW Decode ×2 (4K + 1080p) | ~8% | ~15% | ~500MB |
> | Eye Tracking | ~4% | 0% | ~20MB |
> | Ambiente Espaço (skybox + partículas) | ~2% | ~15% | ~80MB |
> | Screen Glow (downsample + uniforms) | ~1% | ~3% | ~5MB |
> | OpenXR Runtime | ~10% | ~15% | ~200MB |
> | UI (3 painéis de controles) | ~5% | ~8% | ~60MB |
> | OS + Android | ~15% | ~5% | ~1.5GB |
> | **TOTAL** | **~45%** | **~61%** | **~2.4GB** |
>
> Margem apertada. **O `AdaptiveQualityManager` da v0.4 deve ser o árbitro final**: se FPS cair, a ordem de degradação é: (1) desabilitar screen glow, (2) reduzir partículas, (3) reduzir resolução de telas não-focadas, (4) desabilitar eye tracking e manter último modo de input ativo, (5) trocar para ambiente void.

### Gerenciamento de Inputs Conflitantes

> [!IMPORTANT]
> **Três sistemas de input competem**: Controllers (raycast), Hand tracking (raycast do dedo), Eye tracking (gaze + dwell). Apenas UM deve estar ativo por vez para evitar "cliques duplos" (o olhar ativa um botão enquanto o controller já está sobre outro). A máquina de estados do `InputMode` (T2.5) deve ser rigorosa:
> - Controller detectado → desabilitar hand + eye
> - Controllers inativos por 5s, mãos detectadas → ativar hand, desabilitar eye
> - Controllers inativos, mãos não detectadas → ativar eye como fallback

### Backward Compatibility

> [!WARNING]
> **Room migration v0.4 → v0.5**: A v0.5 pode precisar de novas tabelas/campos para configurações de multi-tela (layouts salvos, posições por sessão). Implementar `MIGRATION_4_5` com defaults saudáveis:
> ```kotlin
> val MIGRATION_4_5 = object : Migration(4, 5) {
>     override fun migrate(db: SupportSQLiteDatabase) {
>         db.execSQL("""CREATE TABLE IF NOT EXISTS MultiScreenLayout (
>             id INTEGER PRIMARY KEY AUTOINCREMENT,
>             name TEXT NOT NULL,
>             preset TEXT NOT NULL DEFAULT 'PIP',
>             screen_configs TEXT NOT NULL DEFAULT '[]'
>         )""")
>     }
> }
> ```

### Teste de Limites de Hardware

> [!IMPORTANT]
> **Teste de falha graciosa do MediaCodec**: O que acontece quando o app tenta criar a 4ª sessão de decode e o hardware recusa? O app DEVE:
> 1. Detectar a falha (retorno `nullptr` de `AMediaCodec_createDecoderByType`)
> 2. Mostrar mensagem: "Limite de hardware atingido — máximo 3 telas simultâneas"
> 3. NÃO crashar
> 4. Sugerir fechar uma tela existente
>
> Testar este cenário explicitamente em hardware real.

---

## 6. Definição de Pronto (Definition of Done) — v0.5

### Múltiplas Telas
- [ ] O usuário consegue abrir 2 telas flutuantes simultâneas reproduzindo conteúdo diferente
- [ ] 3ª tela abre com sucesso (se hardware permitir) ou mostra mensagem clara de limite
- [ ] Áudio da tela focada em 100%, telas sem foco atenuadas para ~15%
- [ ] Trocar foco (trigger na tela) atualiza áudio com fade suave (~200ms)
- [ ] Layout presets (PIP, Lado a Lado) posicionam as telas corretamente
- [ ] Mover e redimensionar cada tela independentemente via controller
- [ ] Fechar uma tela libera o MediaCodec e recursos corretamente
- [ ] Nenhum crash ao exceder limite de sessões do MediaCodec

### Eye Tracking
- [ ] Navegação completa pela UI usando apenas eye gaze + dwell time (sem controllers)
- [ ] Dwell time configurável pelo usuário (1.0s a 2.5s)
- [ ] Feedback visual de dwell: indicador circular preenchendo gradualmente
- [ ] Foveated UI: botão sob olhar cresce sutilmente (~15%)
- [ ] Anti-jitter: nenhum "piscar" de seleção durante movimento normal dos olhos
- [ ] Transição controller → eye tracking automática após 5s de inatividade
- [ ] Eye tracking desabilitado graciosamente em Quest 2 (sem eye tracking HW)
- [ ] Permissão solicitada em runtime com fallback limpo se negada
- [ ] Eye tracking + múltiplas telas: olhar para uma tela define foco automaticamente

### Ambientes
- [ ] Ambiente "Espaço" renderiza com ≥ 72 FPS no Quest 3
- [ ] Skybox cubemap visualmente rico e estático (sem motion sickness)
- [ ] Partículas de poeira estelar visíveis mas sutis (~200 partículas)
- [ ] Áudio de ambiência em loop sem click/pop na junção
- [ ] Ducking: áudio ambiente reduz ao iniciar reprodução de vídeo
- [ ] Transição entre ambientes com fade suave (< 3s de loading)

### Iluminação
- [ ] Screen glow reflete cor média do vídeo nas paredes do ambiente
- [ ] Suavização temporal: sem cintilação em cenas de corte rápido
- [ ] Slider de brilho do ambiente funcional (0% = void, 100% = original)
- [ ] Slider de temperatura de cor funcional (quente ↔ neutro)
- [ ] Night mode reduz componente azul da tela de vídeo
- [ ] Todas as preferências persistidas entre sessões

### Geral
- [ ] Nenhuma regressão nos testes das fases 0.1–0.4
- [ ] Sessão de 45 min com 2 telas + ambiente Espaço + eye tracking sem crash ou throttling severo
- [ ] Migração Room v0.4 → v0.5 preserva todos os dados existentes
- [ ] Fresh install funciona sem erros

---

*Fase 0.5 — Estimativa: 8-12 semanas para desenvolvedor solo experiente*  
*Esta fase tem alto risco de integração — múltiplas telas + eye tracking + novos ambientes devem coexistir sem exceder os limites térmicos e de GPU do Quest 3. Teste de integração extensivo é obrigatório.*  
*Dependência: Fase 0.4 DEVE estar completa e estável antes de iniciar v0.5*
