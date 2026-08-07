# Status do Projeto VR Multimedia Player

Este documento consolida o estado atual do desenvolvimento do player de vídeo VR, focando na integração entre Android, Rust (Decodificação) e C++ (OpenXR).

## 1. O Que Foi Aprendido (Arquitetura e Lições)

*   **Transições 2D/3D no Meta Quest:** Descobrimos que misturar uma interface clássica 2D do Android (`MainActivity`) com uma renderização imersiva 3D (`NativeActivity`) na mesma task gera comportamentos imprevisíveis (o app fica invisível ou renderiza como um painel plano transparente). A melhor prática é rodar a aplicação em um ambiente 100% 3D e usar JNI para invocar interfaces nativas do Android (como o seletor de arquivos) por cima da camada VR.
*   **Modo Imersivo (Oculus VR Mode):** Para o sistema do Quest reconhecer o app como Realidade Virtual, é estritamente necessário declarar a tag `<meta-data android:name="com.oculus.vr.mode" android:value="vr_only" />` no `AndroidManifest.xml`. Sem isso, as inicializações do OpenXR podem até funcionar por baixo dos panos, mas a tela principal do Quest não vai engajar no modo imersivo.
*   **Shaders no OVRFW (Oculus VR Framework):** A framework de exemplos do Meta injeta cabeçalhos (`FragmentHeader` e `VertexHeader`) automaticamente em todos os shaders customizados. Isso significa que variáveis como `fragColor` e macros como `TransformVertex` já vêm declaradas. Shaders feitos à mão precisam respeitar essas assinaturas para que o `GlProgram` compile com sucesso.
*   **Mapeamento de HardwareBuffer (Zero-Copy):** Compartilhar frames entre o NDK do Android (via Rust) e o OpenGL ES (C++) exige o uso da extensão OES. Conseguimos estruturar a passagem do ponteiro do `AHardwareBuffer` decodificado para o loop gráfico, convertendo-o via `eglCreateImageKHR` e mapeando-o para uma `GL_TEXTURE_EXTERNAL_OES`.
*   **Eventos e Threads:** Chamadas de Intents do Android (como o File Picker) não podem rodar na thread nativa do OpenXR. É preciso atrelar o ambiente Java (`AttachCurrentThread`) e rodar a ação obrigatoriamente dentro de `runOnUiThread`.
*   **Decodificador e Threads (Rust):** O controle de estado (ex: `is_playing`) deve ditar a suspensão (`sleep`) do loop de decodificação de pacotes em vez de encerrar o loop (`while is_playing`). Pausar matando a thread exige reconstrução pesada ou causa travamentos permanentes.
*   **Coordenadas em VR (Altura e UV):** O espaço de referência `XR_REFERENCE_SPACE_TYPE_STAGE` define Y=0 como o nível do chão. Painéis de UI/Video devem ter `Translation` positiva no eixo Y (ex: 1.5f para altura dos olhos). Além disso, a inversão padrão de texturas OES no Android é absorvida diretamente por frameworks imersivos como o `OVRFW`, não exigindo o clássico flip `1.0 - vTexCoord.y` no fragment shader.

## 2. Tarefas Já Implementadas

*   [x] **Infraestrutura de Build:** Unificação de scripts de compilação usando Gradle, CMake (C++) e Cargo (Rust). Cross-compilação para `aarch64-linux-android` validada e funcional.
*   [x] **Backend Rust (Decoder):** Demuxer implementado usando `ffmpeg-next`. Decodificação via Aceleração de Hardware (`ndk::MediaCodec`) acoplada ao sistema de Superfícies do Android (`ImageReader`), repassando buffers decodificados.
*   [x] **Ponte C++/Rust (FFI):** Implementação de JNI e C-ABI (stubs) para comunicação entre a Activity, a engine gráfica (C++) e o decoder (Rust).
*   [x] **Motor Gráfico e OpenXR (C++):** Integração com o Meta OpenXR Mobile SDK e setup do `NativeActivity`. Configuração do loop de swapchains, sessão OpenXR, tracking de câmera e instanciamento do Quad 3D.
*   [x] **Arquitetura 100% VR:** Criação da classe Kotlin `VRActivity` para herdar o comportamento de inicialização do C++, com JNI bidirecional ligando inputs físicos dos controles (Botões A, X, Y, Gatilho) à abertura de Intents e play/pause de vídeo.
*   [x] **Integração de Renderização de Vídeo (OES):** O problema da "tela preta" foi superado. O ponteiro de hardware (`AHardwareBuffer`) do MediaCodec via Rust é convertido dinamicamente para `EGLImageKHR` e mapeado com sucesso em um `samplerExternalOES` dentro do ambiente Void.
*   [x] **Play/Pause Control:** Os botões físicos do Quest (Gatilho e A) controlam efetivamente a thread de decodificação do Rust, sincronizando play/pause sem quebrar o loop do ffmpeg.

## 3. Próximos Passos (Para Fechar o MVP)

Com o núcleo crítico provado (Rust Demuxer -> Rust MediaCodec -> C++ OpenXR Quad), o foco retorna para a usabilidade e expansão das fontes de vídeo:

1.  **File Picker Robusto:** Consertar o acionamento dos botões (X/Y) nos controles do Quest para invocar a interface 2D do Android e permitir a seleção de arquivos livre pelo usuário sem hardcoding.
2.  **Sincronização de Áudio (A/V Sync):** Finalizar a thread de áudio e atrelar a reprodução do `Oboe` com o framerate do vídeo (usando o áudio como clock master).
3.  **UI de Controles em VR (Hover/Raycast):** Substituir as chamadas cruas dos botões físicos por uma interface de painel com botões Play, Pause e SeekBar flutuando na frente do usuário, usando raycast a partir dos controles.
4.  **Protocolos de Rede:** Começar a integração de fontes remotas (HTTP e SMB).

---
*Fim do Relatório*
