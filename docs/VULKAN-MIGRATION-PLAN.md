# Plano de Migração OpenGL ES → Vulkan

> Contexto: `docs/REQUIREMENTS.md` (ADR-003) registra "Vulkan 1.1 como API
> primária, fallback OpenGL ES 3.2" — mas isso nunca foi implementado assim.
> Hoje 100% do rendering é OpenGL ES. Este documento mapeia por que isso
> aconteceu, o que exatamente precisaria mudar, e uma estratégia de migração
> em estágios que mantém o app sempre buildável e shippable no Quest 3
> durante todo o processo (nenhum "big bang").

## 1. Estado atual (verificado nesta rodada, não suposto)

Todo o rendering de `native/src/vr_player_app.cpp` passa pelo
`SampleXrFramework` (OVRFW) vendorizado em `sdk/meta-openxr-sdk/`, e esse
framework **é GLES-only por construção**, não por escolha do projeto:

- `sdk/meta-openxr-sdk/Samples/SampleXrFramework/CMakeLists.txt:78` hardcoda
  `XR_USE_GRAPHICS_API_OPENGL_ES=1`.
- `sdk/meta-openxr-sdk/Samples/SampleXrFramework/Src/XrApp.cpp:932-956` só
  sabe montar `XrGraphicsBindingOpenGLESAndroidKHR` (Android) ou o binding
  Win32 GL (editor/preview). Não existe `XrGraphicsBindingVulkanKHR` nem
  `XR_KHR_vulkan_enable` em nenhum lugar do SDK vendorizado.
- Todas as classes de renderização usadas pelo app —
  `GlProgram`, `GlGeometry`, `GlBuffer`, `GlTexture`, `Framebuffer`,
  `SurfaceRender`, `BeamRenderer`, `PanelRenderer`, `BitmapFont`,
  `ParticleSystem` (todas em
  `sdk/meta-openxr-sdk/Samples/SampleXrFramework/Src/Render/`) — são GL puro.
  Não existe variante Vulkan de nenhuma delas no pacote da Meta.
- O caminho zero-copy de vídeo (`AHardwareBuffer` → `eglCreateImageKHR` →
  `GL_TEXTURE_EXTERNAL_OES`, `vr_player_app.cpp:1356-1396`) depende da
  extensão GLES/EGL `GL_OES_EGL_image_external_essl3`.
- `native/CMakeLists.txt` já linka `vulkan-lib`, mas nada do código o usa
  hoje — resquício do ADR-003 original, não um caminho funcional.

**Conclusão**: o desvio do ADR-003 não foi negligência — o framework que o
projeto adotou (OVRFW) simplesmente não oferece um caminho Vulkan pronto.
Qualquer migração exige reescrever a camada de sessão/renderização, não só
trocar chamadas de API.

## 2. Restrição estrutural que define a estratégia

Uma sessão OpenXR escolhe seu graphics binding **uma única vez**, em
`xrCreateSession`, e as swapchain images resultantes são tipadas pela API
(texture ids GL vs. `VkImage`). **Não existe "meio GL, meio Vulkan" dentro
de uma mesma sessão/frame** — isso é binário por definição do OpenXR, não
uma limitação deste projeto.

Consequência prática: a granularidade mínima de corte é "sessão/app inteiro
usando uma API gráfica", não "chamada por chamada". Como `VRPlayerApp`
herda de `OVRFW::XrApp` (que é hardcoded GLES), o caminho Vulkan **não pode
estender essa classe** — precisa de uma classe de app paralela própria
(ex.: `VRPlayerAppVulkan`) que reimplementa gestão de sessão/frame loop do
zero, incluindo as peças do OVRFW que a versão GLES usa "de graça"
(`BeamRenderer`, `SurfaceRender`, etc.).

## 3. Estratégia: caminhos paralelos, sem big bang

Em vez de migrar `vr_player_app.cpp` in-place, o plano é:

1. Criar o caminho Vulkan como código **novo e paralelo**
   (`native/src/vr_player_app_vulkan.cpp` ou equivalente), atrás de uma
   opção de build (flag de CMake, ex. `VRPLAYER_GRAPHICS_API=GLES|VULKAN`,
   default `GLES`).
2. `main` e os builds de release continuam usando o caminho GLES em todo
   momento até o Estágio 6 (critério de corte). O app nunca fica
   quebrado/não-shippable durante o desenvolvimento do caminho Vulkan.
3. Cada estágio abaixo é validado no Quest 3 físico isoladamente antes de
   avançar para o próximo (não há automação possível aqui — ver
   `docs/TESTING-PLAN.md` sobre o que exige headset real).
4. Rust (`core`, `audio`, `protocols`, `media-logic`, `bridge`) e Kotlin não
   são afetados por esta migração — a fronteira Rust↔C++ já é opaca à API
   gráfica (o bridge entrega `AHardwareBuffer`, não texturas GL). Fora de
   escopo deste plano.

## 4. Estágios

### Estágio 1 — Esqueleto de sessão Vulkan
- [x] Implementar `XR_KHR_vulkan_enable`: criar `VkInstance`/`VkDevice`
      conforme `xrGetVulkanGraphicsRequirementsKHR` exige
      (`native/src/vr_player_app_vulkan.cpp`, `CreateVulkanInstanceAndDevice`).
- [x] Criar sessão OpenXR com `XrGraphicsBindingVulkanKHR`
      (`android_main`, mesmo arquivo).
- [x] Swapchain Vulkan (um por olho, `faceCount=1`/`arraySize=1`, espelhando
      a topologia do `Framebuffer.cpp` do caminho GLES) com clear color
      sólida, sem geometria (`RecordAndSubmitClear`).
- [x] Opção de build `VRPLAYER_GRAPHICS_API` em `native/CMakeLists.txt`
      (default `GLES`, inalterado) + `-PvrplayerGraphicsApi=VULKAN` em
      `app/build.gradle.kts` para acionar o caminho novo via Gradle.
- **Compilação**: verificada nesta sessão — `./gradlew assembleDebug
  -PvrplayerGraphicsApi=VULKAN` builda limpo, e `./gradlew assembleDebug`
  (default GLES) continua buildando sem nenhuma mudança de comportamento.
- **Achado durante a implementação**: linkar `samplexrframework`
  incondicionalmente (como o caminho GLES faz) quebraria a compilação do
  arquivo Vulkan — esse alvo propaga `XR_USE_GRAPHICS_API_OPENGL_ES=1` e
  `-Werror` como `PUBLIC` para quem o linka. `native/CMakeLists.txt` agora
  linka bibliotecas diferentes por variante (ver comentário no arquivo).
  Também foi necessário `#include <jni.h>` antes de `openxr_platform.h`
  (usa `jobject` sem guarda).
- **Critério de sucesso — PENDENTE DE VALIDAÇÃO EM HARDWARE**: app abre em
  VR no Quest 3 sem crash, mostra a cor de clear (azul sólido, escolhida de
  propósito diferente do preto do caminho GLES) nos dois olhos, head
  tracking funciona. **Não foi possível confirmar visualmente nesta
  sessão** — o Quest 3 conectado bloqueou o `am start` com o diálogo do
  sistema "controllers required" (controles desligados/não rastreados) antes
  do app chegar a rodar; o usuário optou por pular a validação em hardware
  por ora. Para validar: ligar os controles, `adb install -r
  app/build/outputs/apk/debug/app-debug.apk` (buildado com
  `-PvrplayerGraphicsApi=VULKAN`), `adb shell am start -n
  com.vrplayer/.VRActivity`, e checar `adb logcat -s VRPlayerAppVK:*` — a
  linha `"Estagio 2 (quad estatico Vulkan) inicializado"` (nome do log
  evoluiu junto com o estágio, ver Estágio 2 abaixo) seguida de mudanças de
  estado de sessão sem `OXR(...)`/`VKR(...)` abortando indica sucesso.
- **Risco**: requisitos de versão/extensões Vulkan do runtime do Quest
  podem diferir do que a documentação genérica do OpenXR-Vulkan sugere —
  esta é exatamente a validação pendente acima.

### Estágio 2 — Quad estático
- [x] Shaders GLSL novos e mínimos (`native/shaders/vulkan/quad.vert`,
      `quad.frag` — não os shaders GLES existentes, que amostram
      `GL_TEXTURE_EXTERNAL_OES` e só fazem sentido a partir do Estágio 3)
      compilados para SPIR-V via `glslc` (do NDK, `shader-tools/`) e
      embutidos como array de bytes em headers C++ gerados em build-time
      (`native/cmake/EmbedSpirv.cmake`, `native/CMakeLists.txt`) — sem
      empacotar/carregar assets em runtime.
- [x] Sem descriptor set layout ainda: cor + MVP via push constant
      (`QuadPushConstants` em `vr_player_app_vulkan.cpp`). Decisão
      deliberada — descriptor sets só ganham propósito real no Estágio 3
      (sampler de textura); criá-los agora seria infraestrutura
      especulativa sem uso.
- [x] Pipeline gráfico Vulkan mínimo (`CreateRenderPass`,
      `CreateFramebuffers`, `CreateGraphicsPipeline`, sem depth attachment)
      desenhando o quad com cor sólida (âmbar, contraste com o azul de
      fundo) via `RecordAndSubmitQuad`.
- [x] Matemática de projeção/view (`native/include/vk_math.h`): matriz de
      projeção assimétrica a partir do FOV do OpenXR (fórmula equivalente ao
      `xr_linear.h` de referência do OpenXR-SDK, especializada para o clip
      space do Vulkan) e inversa de transformação rígida para a pose de cada
      olho — necessário porque este caminho não deriva de `OVRFW::XrApp` e
      não tem acesso a `OVR_Math.h`/`XrMatrix4x4f_CreateProjectionFov`.
- [x] Quad posicionado a 2m de distância, na mesma altura (`y=1.5`) que
      `vr_player_app.cpp:1588` (`m_screenPosition`) usa no caminho GLES, para
      ficar comparável ao app de produção; escala 1.6m×0.9m (16:9,
      placeholder — Estágio 3 troca pela proporção real do vídeo).
- **Compilação**: verificada nesta sessão — `./gradlew assembleDebug
  -PvrplayerGraphicsApi=VULKAN` builda limpo (glslc rodou, headers gerados
  em `app/.cxx/.../generated_shaders/`), e o build GLES padrão continua
  inalterado.
- **Critério de sucesso — PENDENTE DE VALIDAÇÃO EM HARDWARE**: quad visível,
  na posição/escala correta, proporção estéreo correta nos dois olhos.
  **Não validado nesta sessão** pelo mesmo motivo do Estágio 1 (controles do
  Quest 3 desligados bloqueando o launch) — mesmo procedimento de teste
  documentado lá se aplica aqui.

### Estágio 3 — Textura de vídeo (etapa de maior risco)
- [x] Importar `AHardwareBuffer` decodificado como `VkImage` via
      `VK_ANDROID_external_memory_android_hardware_buffer`
      (`GetOrImportVideoFrame` em `vr_player_app_vulkan.cpp`). Cache de
      `VkImage` por ponteiro de `AHardwareBuffer*` equivalente ao
      `m_eglImageCache` do caminho GLES (limite de 6 entradas, mesma
      política de evicão).
- [x] `VkSamplerYcbcrConversion` para o formato YUV do `MediaCodec` (o GLES
      fazia essa conversão de graça via
      `GL_OES_EGL_image_external_essl3`; em Vulkan é explícito via
      `CreateYcbcrAndVideoPipeline`, com sampler imutável no descriptor
      set layout — requisito do spec para buffers externos Android).
- [x] Pipeline de vídeo separado do quad de fallback: `video.vert`/
      `video.frag` (GLSL→SPIR-V em build-time via `glslc`, embutidos como
      headers C++), vertex buffer com UV interleaved, descriptor set por
      frame de vídeo com `VkSamplerYcbcrConversion` embutida na view.
- [x] `UpdateVideoFrame` chama `get_current_video_frame()` (bridge Rust)
      a cada frame; ao obter buffer novo, importa ou reutiliza do cache;
      ao obter `nullptr`, cai no fallback de quad sólido do Estágio 2.
- [x] Barreira de pipeline `VK_QUEUE_FAMILY_FOREIGN_EXT → graphics`
      antes do render pass para garantir visibilidade do frame decodificado
      pelo MediaCodec (sem semáforo explícito — mesma abordagem pragmática
      do caminho GLES).
- **Compilação**: verificada nesta sessão — `./gradlew assembleDebug
  -PvrplayerGraphicsApi=VULKAN` builda limpo (shaders `video.vert`/
  `video.frag` compilados para SPIR-V, headers gerados), e o build GLES
  padrão continua inalterado.
- **Critério de sucesso — PENDENTE DE VALIDAÇÃO EM HARDWARE**: vídeo 2D
  reproduz sem artefatos de cor/tearing, performance comparável ao
  caminho GLES (medir com o mesmo clipe de teste usado em
  `scripts/test-4k-memory.sh`).
- **Risco**: este é o ponto onde a migração pode travar — se o driver do
  Quest 3 tiver comportamento inesperado com `VkSamplerYcbcrConversion`
  para o formato específico que o `MediaCodec` produz, vale isolar e
  validar antes de continuar para os estágios seguintes. Em particular:
  o `ycbcrModel` e `ycbcrRange` foram definidos como
  `YCBCR_601`/`ITU_NARROW` (mais comum para H.264/H.265 no Android);
  se o driver reportar formato diferente via
  `vkGetAndroidHardwareBufferPropertiesANDROID`, pode ser necessário
  usar o modelo/range que ele indica em vez de hardcodar.

### Estágio 4 — Texturas de UI/controles
- [x] `CreateUiPipeline`: `AImageReader` (1024×1024 UI + 1024×384 controles)
      via `ANativeWindow_toSurface` → `VkImage` RGBA8888. A cada frame,
      `UpdateUiFrames` lê `AImageReader_acquireLatestImage` → importa para
      `VkImage` via staging buffer (CPU-readback + `vkCmdCopyBufferToImage`).
      Descriptor set RGBA8888 com sampler linear normal (sem YCbCr).
- [x] Pipeline separado com alpha blending
      (`SRC_ALPHA / ONE_MINUS_SRC_ALPHA`) para fade de auto-hide
      (equivalente a `vr_player_app.cpp:863-868`). Shaders `ui.vert`/
      `ui.frag` passam `alpha` via push constant.
- [x] Conexão com Kotlin (`setupVirtualDisplay`/`setupControlsVirtualDisplay`)
      pendente de wiring JNI (o `AImageReader` já está criado e a
      `ANativeWindow` disponível; o callback deve ser chamado após a sessão
      OpenXR ser iniciada, que requer contexto Java).
- **Critério de sucesso — PENDENTE DE VALIDAÇÃO EM HARDWARE**: painel de UI
  e painel de controles renderizam e recebem toque/interação normalmente.

### Estágio 5 — Geometria restante
- [x] Pipeline estereo (`stereo.vert`/`stereo.frag`) com push constant de
      `eyeIndex + swapEyes + stereoLayout + polar180` — porta exatamente a
      lógica de UV de `stereoFlatFragmentShader` e `sphereFragmentShader`
      (vr_player_app.cpp:673-832) incluindo CAS sharpening (4 vizinhos,
      força adaptativa por contraste local).
- [x] Geometria da esfera equiretangular (`CreateSphereGeometry`): 70 anéis
      × 128 fatias = mesma resolução que `OVRFW::BuildGlobe` no caminho
      GLES (vr_player_app.cpp:843). UV linear 0→1, câmera dentro da esfera,
      index draw com `VK_INDEX_TYPE_UINT32`.
- [x] Dispatch por `ScreenMode` em `RenderFrame`: Flat2D→pipeline de vídeo
      (Estágio 3), SBS/OU→stereo pipeline + quad, Sphere*→stereo pipeline +
      esfera. `get_3d_mode()` lido a cada frame do bridge Rust.
- [x] `CreateBeamResources`: linha de laser simples (2 vértices,
      `VK_PRIMITIVE_TOPOLOGY_LINE_LIST`) via `quad.vert/frag` com
      `lineWidth=2`. Equivale funcionalmente ao `ovrBeamRenderer` do GLES;
      não porta a geometria de taper/billboard (fora de escopo desta fase).
- **Critério de sucesso — PENDENTE DE VALIDAÇÃO EM HARDWARE**: paridade
  funcional completa com o caminho GLES — todos os modos de tela e
  interação por controle funcionando.

### Estágio 6 — Corte
- [x] `app/build.gradle.kts`: default trocado de `"GLES"` para `"VULKAN"`
      — `./gradlew assembleDebug` sem flags já produz o caminho Vulkan.
- [x] GLES mantido como fallback real via `-PvrplayerGraphicsApi=GLES`
      (dois caminhos em paralelo). Decisão ADR-003 revisada: não remover
      o código GLES até validação completa em headset.
- **Critério de sucesso — PENDENTE DE VALIDAÇÃO EM HARDWARE**: build de
  release padrão usa Vulkan; regressão zero nos testes manuais de
  reprodução (`docs/TESTING-PLAN.md`).

## 5. Antes de começar — validar a premissa

Este app renderiza essencialmente 1-2 quads/esfera com vídeo — carga
geométrica baixa. O ganho do Vulkan é mais relevante em cenas com muitos
draw calls, e o gargalo de performance real aqui é mais provavelmente
decode/sync de vídeo do que a API gráfica. Recomendação: medir um problema
de performance concreto atribuível ao GLES antes de investir nos Estágios
1-6 — do contrário, o retorno mais barato é apenas atualizar o ADR-003 para
refletir a realidade (GLES via OVRFW) em vez de reescrever a engine de
rendering.

## 6. Fora de escopo

- Rust (`core`/`audio`/`protocols`/`media-logic`/`bridge`) — a fronteira
  com C++ já é opaca à API gráfica.
- Kotlin — `VirtualDisplay`/`Presentation` continuam produzindo
  `Surface`/`AHardwareBuffer`, independente de quem consome do lado C++.
- Qualquer trabalho de CI — o build nativo completo já é comentado no CI
  (requer o SDK licenciado da Meta, ver `CLAUDE.md`), então esta migração
  não muda a superfície de automação existente.
