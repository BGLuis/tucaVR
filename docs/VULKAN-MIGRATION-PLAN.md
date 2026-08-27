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
  com.tucavr/.VRActivity`, e checar `adb logcat -s VRPlayerAppVK:*` — a
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
- [x] `CreateUiPipeline`: `AImageReader` (`kUiTexWidth×kUiTexHeight` = 1024×768
      pro file browser, `kControlsTexWidth×kControlsTexHeight` = 1024×384 pros
      controles) via `ANativeWindow_toSurface` → `VkImage` RGBA8888. A cada
      frame, `UpdateUiFrames` lê `AImageReader_acquireLatestImage` → importa
      para `VkImage` via staging buffer (CPU-readback + `vkCmdCopyBufferToImage`).
      Descriptor set RGBA8888 com sampler linear normal (sem YCbCr).
      **Correção pós-migração**: o file browser tinha nascido em 1024×1024
      neste arquivo, mas `VRActivity.dispatchVRTouch` já assumia 1024×768
      (contrato compartilhado com o caminho GLES) — o Y do toque saía
      comprimido nos 75% superiores do painel. Unificado pra 1024×768 (ver
      revisão pós-migração abaixo).
- [x] Pipeline separado com alpha blending
      (`SRC_ALPHA / ONE_MINUS_SRC_ALPHA`) para fade de auto-hide
      (equivalente a `vr_player_app.cpp:863-868`). Shaders `ui.vert`/
      `ui.frag` passam `alpha` via push constant — `DrawUiQuads` agora
      alimenta isso com `state.uiAlpha`/`state.controlsAlpha` (antes era
      `1.0f` fixo, sem fade nenhum).
- [x] Conexão com Kotlin (`setupVirtualDisplay`/`setupControlsVirtualDisplay`)
      — wiring JNI feito (`android_main`, após `xrCreateSession`); o
      `AImageReader` é criado e a `ANativeWindow` convertida pra `Surface`
      Java direto em `CreateUiPipeline`.
- **Critério de sucesso**: painel de UI e painel de controles renderizam e
  recebem toque/interação — **verificado no sentido "hit-test bate com o
  texel certo"** na revisão pós-migração (matrizes/dimensões corrigidas);
  **ainda PENDENTE DE VALIDAÇÃO VISUAL EM HARDWARE** (não há headset físico
  disponível nesta sessão).

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
      `VK_PRIMITIVE_TOPOLOGY_LINE_LIST`) via `quad.vert/frag`. Equivale
      funcionalmente ao `ovrBeamRenderer` do GLES; não porta a geometria de
      taper/billboard (fora de escopo desta fase). `lineWidth` voltou pra
      `1.0f` na revisão pós-migração — o valor original (`2.0f`) requer a
      feature Vulkan `wideLines`, não habilitada em `vkCreateDevice` (nem
      garantida no Adreno do Quest 3); pedi-la sem checar era uso inválido
      da API.
- **Critério de sucesso**: **não tinha paridade funcional completa** como
  este documento afirmava antes da revisão pós-migração — ver seção
  "Revisão pós-migração" abaixo pro que realmente faltava e foi corrigido
  nesta rodada. Ainda **PENDENTE DE VALIDAÇÃO EM HARDWARE** (todos os itens
  abaixo foram corrigidos por leitura/análise de código, sem headset físico
  disponível nesta sessão).

### Estágio 6 — Corte
- [x] `app/build.gradle.kts`: default trocado de `"GLES"` para `"VULKAN"`
      — `./gradlew assembleDebug` sem flags já produz o caminho Vulkan.
- [x] GLES mantido como fallback real via `-PvrplayerGraphicsApi=GLES`
      (dois caminhos em paralelo). Decisão ADR-003 revisada: não remover
      o código GLES até validação completa em headset.
- **Critério de sucesso — PENDENTE DE VALIDAÇÃO EM HARDWARE**: build de
  release padrão usa Vulkan; regressão zero nos testes manuais de
  reprodução (`docs/TESTING-PLAN.md`).

## 4.1 Revisão pós-migração — achados e correções

Sessão de revisão (posterior aos Estágios 1–6 acima) motivada por dois
problemas reportados: um build quebrado por uma refatoração concorrente de
recenter no caminho GLES, e uma câmera/UI com geometria visivelmente errada
em capturas de tela do caminho Vulkan (fundo escuro `0.02,0.02,0.05`, laser
azul — identificável como Vulkan, não GLES, pelas cores). A investigação
achou que o Estágio 6 já tinha trocado o default do Gradle pra `VULKAN`, mas
o caminho Vulkan não tinha nem a correção de câmera nem boa parte da
paridade de interação que os Estágios 4/5 acima declaravam pronta. Achados,
mais graves primeiro:

- **Billboard calculado por olho, não por cabeça**: `DrawUiQuads` era
  chamado dentro do laço de olhos com `views[eye].pose.position` — o yaw do
  billboard da UI saía diferente pra cada olho (~6cm de separação
  interpupilar), quebrando a disparidade estéreo do painel. É a causa mais
  provável da geometria estranha nas capturas de tela. Corrigido: um
  `headCenter` (média dos dois olhos) calculado uma vez por frame em
  `RenderFrame`, usado por `UpdateInteraction` e por todas as chamadas de
  `DrawUiQuads`/`RecordAndSubmitVideo`/`RecordAndSubmitQuad`.
- **Dimensões de textura divergentes do contrato Kotlin**: ver nota no
  Estágio 4 acima (1024×1024 vs. os 1024×768 que `VRActivity.dispatchVRTouch`
  já assumia).
- **`rayHitsQuad` recebendo matriz com escala embutida**: `Mat4RigidInverse`
  assume rotação ortonormal; passar uma matriz com `Mat4Scale` quebrava a
  inversa e por tabela o hit-test do painel de controles. Corrigido:
  `rayHitsQuad` agora recebe a matriz *sem* escala e os fatores de escala
  como parâmetros separados, dividindo o ponto local (mesma técnica que já
  existia, informalmente, só pro painel de UI).
- **Matemática de transform duplicada e já divergente**: `UpdateInteraction`
  e `DrawUiQuads` recalculavam as matrizes de UI/controles cada um por
  conta própria — uma aplicava a escala embutida na matriz, a outra dividia
  o ponto local depois. Extraído em `ComputeSceneTransforms` (compartilhado)
  em `vr_player_input_vulkan.h`.
- **Sem recenter nenhum**: nem o evento
  `XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING` (recenter do sistema)
  nem o long-press do Menu (recenter manual, T4.3 no GLES) existiam no
  caminho Vulkan. Portado `sceneYawOffset`/`sceneTranslationOffset` (mesmo
  conceito do GLES) mais a calibração inicial no 1º frame com pose válida.
- **Esfera 360 fixa na origem**: MVP da esfera era `proj * view` puro, sem
  acompanhar a translação da cabeça — andando fisicamente no Guardian o
  usuário saía de dentro da esfera. Corrigido pra espelhar
  `m_sphereTransform` do GLES.
- **Beam sempre desenhado**: o teste "controle detectado" (`lastRayDir.z !=
  0.0f`) era sempre verdadeiro, já que `lastRayDir` nasce com `z=-1`.
  Substituído por uma flag (`state.hasRay`) setada pelo resultado real de
  `xrLocateSpace`.
- **`samplerAnisotropy` pedido sem checar suporte**: mesma categoria do
  `wideLines` acima (ver Estágio 5) — `vkCreateDevice` agora consulta
  `vkGetPhysicalDeviceFeatures` e só habilita/pede o que o device suporta.
- **Sem paridade de interação**: auto-hide com fade, gating de
  dispatch por painel visível, haptics, A/X/B/Y/Menu, thumbsticks (mover/
  redimensionar tela, seek/volume), progresso periódico e cursor no ponto de
  acerto não existiam no caminho Vulkan — portados em `UpdateInteraction`
  (novas OpenXR actions em `SetupOpenXrInputs`: `a_click`/`x_click`/
  `b_click`/`y_click`/`menu_click`/`thumbstick`/`squeeze`/`haptic`). O cursor
  no ponto de acerto usa um único segmento (em vez dos dois do GLES) porque
  o pipeline de beam deste caminho (`quad.frag`) não tem o fade radial que
  o `BeamRenderer` do GLES tem — um segmento sólido já resolve.

Nada disso foi validado no Quest 3 físico na sessão em que foi escrito —
apenas revisão de código e checagem de compilação lógica.

### 4.1.1 Bug de sinal no yaw de recenter — confirmado em hardware

Teste real em headset (sessão seguinte) reportou três sintomas que pareciam
não relacionados: painéis de UI/controles "muito longe, serrilhados,
impossível ler o texto"; um vídeo 180° via SMB "não tocando"; e a rotação
automática do recenter "incorreta" (esperado: a tela ficar na frente do
usuário). Os três eram o mesmo bug: a fórmula de yaw usada nos 4 pontos de
recenter (`RenderFrame`/`UpdateInteraction` no Vulkan, os dois blocos
equivalentes no GLES) —

```cpp
sceneYawOffset = atan2f(fwd.x, -fwd.z);
```

— gira o conteúdo **180° do lado errado**. Verificado algebricamente contra
`OVR::Matrix4f::RotationY` (`OVR_Math.h:3437-3441`, mesma convenção usada
por `Mat4RotationY` em `vk_math.h`): para um usuário virado 90° em relação
ao mundo, a fórmula original coloca o conteúdo atrás dele em vez de na
frente. Isso explica os 3 sintomas: painéis fora do campo de visão central
aparecem só de raspão na borda do FOV (foreshortening severo = "serrilhado,
ilegível"); o hemisfério frontal de um vídeo 180° acaba renderizado atrás do
usuário (parece "não estar tocando" quando na verdade está, só que fora de
vista). Corrigido negando o argumento X do `atan2` nos 4 pontos:
`atan2f(-fwd.x, -fwd.z)`. Este era exatamente o cenário que o comentário
original ("sinal do ângulo nunca validado em headset físico") avisava que
podia acontecer.

Ver `docs/TESTING-PLAN.md` pro que mais exige headset físico e ainda não foi
validado nesta revisão.

### 4.1.2 Bugs de geometria/pipeline nos modos SBS/OU/180 — confirmado em hardware

Teste real após o fix do §4.1.1 reportou mais três sintomas nos modos
estéreo/esfera (exclusivos do caminho Vulkan — GLES não tem esses bugs, sua
geometria vem de `OVRFW::BuildGlobe`, não reimplementada à mão):

- **"Alguns players de vídeo ficam cortados em um triângulo"** (modos SBS/OU
  planos, `ScreenMode::SBS`/`SBSHalf`/`OU`/`OUHalf`): `CreateStereoPipeline`
  criava um único `VkPipeline` com `VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST`,
  usado tanto para o index-draw da esfera (correto, os índices formam pares
  de triângulos discretos) quanto para o quad plano SBS/OU — que desenha os
  mesmos 4 vértices de `state.videoVertexBuffer` (ordenados BL,BR,TL,TR,
  comentário explícito "triangle strip" em `CreateVideoVertexBuffer`) via
  `vkCmdDraw(cmd, 4, ...)` sem índices. Com `TRIANGLE_LIST`, 4 vértices só
  formam **um** triângulo completo (0,1,2); o 4º vértice é descartado —
  literalmente metade do quad, cortada na diagonal. Corrigido criando um
  segundo pipeline (`state.stereoFlatPipeline`, mesmos shaders/layout, só
  `TRIANGLE_STRIP`) e escolhendo qual bindar em `RenderFrame` conforme
  `sphereMode`.
- **"O 180 não está sendo exibido"**: `CreateSphereGeometry` (a
  reimplementação manual da esfera pro Vulkan) parametriza a longitude com
  `theta = 2π·s/slices`, o que coloca `uu=0` (não `uu=0.5`) em -Z (frente do
  usuário). O fragment shader (`stereo.frag`, porta fiel do GLES) descarta
  fora de `uv.x ∈ [0.25, 0.75]` assumindo a convenção do
  `BuildGlobeDescriptor` do GLES — verificada direto no SDK
  (`GlGeometryDescriptor.cpp:606`: `lon = (0.25+xf)·2π`, que calcula
  `xf=0.5` → -Z) — onde **`uu=0.5` é a frente**. Como as duas convenções
  divergiam em meia volta, o recorte de 180° descartava exatamente o
  hemisfério da frente (o único visível) e mantinha o de trás. Corrigido
  deslocando a **posição** (não o `uu`) em meia volta:
  `theta = 2π·s/slices - π`. Deslocar o `uu` diretamente teria sido mais
  simples mas quebraria a técnica de vértice duplicado que fecha a costura
  da esfera sem salto de interpolação (`s=0`/`s=slices`, mesma posição,
  `uu=0` vs `uu=1`) — com o deslocamento na posição em vez do UV, essa
  costura continua intacta, só que agora alinhada com a convenção correta.
- **"Um dos modelos 360 ficou com o vídeo invertido / textos lidos como em espelho"**:
  além do deslocamento de longitude original (frente/trás), a fórmula de vértices
  de `CreateSphereMesh` continha um sinal negativo indevido no cálculo da coordenada X:
  `float x = -radius * sinf(phi) * sinf(theta)`. Com isso, qualquer ponto com $\theta > 0$
  (lado direito da textura equirretangular, $uu > 0.5$) era projetado em $X < 0$
  (lado esquerdo no espaço VR), invertendo horizontalmente a projeção (efeito espelho,
  onde textos e detalhes da cena eram lidos ao contrário). Corrigido removendo o sinal
  negativo (`float x = radius * sinf(phi) * sinf(theta)`), alinhando a malha com a
  convenção do `BuildGlobeDescriptor` do GLES e validado com teste unitário automatizado
  em `native/tests/test_vk_math.cpp` (`TestSphereMeshCoordinates`).

Ver `docs/TESTING-PLAN.md` pro que mais exige headset físico e ainda não foi
validado nesta revisão.

### 4.1.3 Painéis de UI/controles muito distantes/pequenos para ler — confirmado em hardware

Mesmo depois do fix de yaw (§4.1.1), o teste real em headset reportou que o
texto dos painéis de File Browser e controles continuava ilegível. Não era
mais um bug de rotação — os valores de posição/escala em si (idênticos nos
dois caminhos, GLES e Vulkan, já que foram unificados em §4.1) colocavam o
painel de UI a ~2.66m de distância com só 0.8m de largura, um ângulo visual
de ~17° — pequeno demais pra ler texto confortavelmente num headset. Esses
valores nunca tinham sido validados em hardware (herdados do design
original do T4.5, ver comentários em `vr_player_app.cpp`).

Corrigido trazendo os dois painéis pra mais perto e aumentando 50%,
preservando a proporção de cada textura (4:3 pro UI, 1024×768; 2.667:1 pros
controles, 1024×384):

| | Antes | Depois |
|---|---|---|
| UI: posição base | `(-2.2, 1.5, -1.5)` | `(-1.3, 1.5, -1.0)` |
| UI: distância / escala | ~2.66m / 0.8×0.6 | ~1.64m / 1.2×0.9 |
| Controles: posição base | `(0, 0.4, -1.9)` | `(0, 0.4, -1.3)` |
| Controles: distância / escala | 1.9m / 0.8×0.3 | 1.3m / 1.2×0.45 |

Fonte única em cada caminho — `baseUiPos`/`baseControlsPos` em
`vr_player_app.cpp` (GLES), `kBaseUiPos`/`kUiPanelScaleX/Y`/
`kBaseControlsPos`/`kControlsPanelScaleX/Y` em `vr_player_input_vulkan.h`
(Vulkan, consumidos tanto pelo hit-test quanto pelo render via
`ComputeSceneTransforms`) — então ajustar esses valores no futuro não corre
o risco de hit-test e render divergirem de novo.

Ver `docs/TESTING-PLAN.md` pro que mais exige headset físico e ainda não foi
validado nesta revisão.

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
