# 🏛️ Arquitetura do Sistema — tucaVR (Resumo Executivo)

> **Documento de Referência Rápida**  
> Para a documentação arquitetural completa, exaustiva e com diagramas de sequência detalhados, consulte:  
> 👉 **[docs/ARCHITECTURE.md](file:///home/luis/Documents/hand-on/vr-multmidia/docs/ARCHITECTURE.md)**

---

## 1. Visão Geral

O **tucaVR** é um player multimídia 2D e 3D estereoscópico projetado para a plataforma **Meta Quest 3** (Qualcomm Snapdragon XR2 Gen 2). 

A aplicação opera como uma `NativeActivity` 100% imersiva em OpenXR (`<meta-data android:name="com.oculus.vr.mode" android:value="vr_only" />`), sem o uso de janelas 2D planas ou Jetpack Compose.

```
┌────────────────────────────────────────────────────────┐
│                   Kotlin (app/)                        │
│  UI (VirtualDisplay/Presentation), Room, Network Auth  │
└───────────────────────────▲────────────────────────────┘
                            │ JNI
┌───────────────────────────▼────────────────────────────┐
│                    C++ (native/)                       │
│ OpenXR Session, Vulkan/GLES Render Loop, Touch Inputs  │
└───────────────────────────▲────────────────────────────┘
                            │ C ABI (extern "C")
┌───────────────────────────▼────────────────────────────┐
│                    Rust (rust/)                        │
│ Demuxer (ffmpeg-next), MediaCodec, Oboe Audio, Network │
└────────────────────────────────────────────────────────┘
```

---

## 2. Responsabilidades por Camada

| Camada | Diretório | Tecnologia | Papel Principal |
| :--- | :--- | :--- | :--- |
| **Shell & UI** | `app/` | Kotlin | Shell Android, interfaces nativas desenhadas em `VirtualDisplay` via `VRPresentation`, credenciais com `EncryptedSharedPreferences` e histórico com Room DB. |
| **Engine Gráfica** | `native/` | C++17, OpenXR, Vulkan (primário) / GLES (fallback) | Ciclo de vida da sessão OpenXR, render loop (72/90/120 Hz), projeções 2D/3D (SBS, OU, 180°, 360°), raycast de controles Touch Plus e importação Zero-Copy de texturas. |
| **Núcleo de Mídia** | `rust/` | Rust (NDK + `media-logic`) | Demuxing (`ffmpeg-next`), decodificação de hardware com `ndk::MediaCodec`, áudio de baixa latência com `Oboe`, clientes de rede (SMB 2/3, HTTP, FTP, SFTP) e sincronismo A/V (`SyncManager`). |

---

## 3. Invariantes Críticos da Arquitetura

1. **Fronteira FFI (ADR-002):** O **Kotlin NUNCA chama o Rust diretamente**. Toda comunicação de reprodução passa pelo C++ via JNI, garantindo que o ciclo de render em tempo real sincronize com a entrega de frames.
2. **Streaming Zero-Copy:** Os frames decodificados pelo `MediaCodec` são expostos como ponteiros opacos `AHardwareBuffer*` e importados diretamente para memória GPU (`VkImage` com `VkSamplerYcbcrConversion` em Vulkan ou `EGLImageKHR` em GLES), sem tráfego de pixels pela CPU.
3. **Loop Gráfico Não-Bloqueante:** `get_current_video_frame()` utiliza `try_lock()` sobre o controller de playback. Operações lentas de rede ou seek em background nunca bloqueiam a thread de renderização do OpenXR, preservando o framerate de VR.
4. **Áudio como Relógio Mestre:** A sincronização A/V utiliza o PTS real do stream `Oboe` como master clock, aplicando skips preventivos de frames atrasados (`LATE_FRAME_RENDER_SKIP_SEC` = 100ms) e descarte de pacotes não-chave (`CATCH_UP_SKIP_THRESHOLD_SEC` = 500ms) para sanar gargalos.
5. **UI em Espaço 3D via VirtualDisplay:** As telas do Android são renderizadas em instâncias de `VirtualDisplay` conectadas a `AImageReader`s nativos e projetadas em quads flutuantes no espaço VR.

---

## 4. Índice de Documentação Detalhada

* 📘 **[docs/ARCHITECTURE.md](file:///home/luis/Documents/hand-on/vr-multmidia/docs/ARCHITECTURE.md):** Especificação canônica integral, pipeline Vulkan/GLES, modelo de concorrência e diagramas de sequência Mermaid.
* 📋 **[docs/REQUIREMENTS.md](file:///home/luis/Documents/hand-on/vr-multmidia/docs/REQUIREMENTS.md):** Requisitos funcionais, fases de desenvolvimento e Architecture Decision Records (ADRs).
* 🌋 **[docs/VULKAN-MIGRATION-PLAN.md](file:///home/luis/Documents/hand-on/vr-multmidia/docs/VULKAN-MIGRATION-PLAN.md):** Detalhes da migração e arquitetura do backend Vulkan.
* 🧪 **[docs/TESTING-PLAN.md](file:///home/luis/Documents/hand-on/vr-multmidia/docs/TESTING-PLAN.md):** Estratégia de testes host, testes de integração Docker e testes em hardware.
* 🐛 **[docs/DEBUGGING.md](file:///home/luis/Documents/hand-on/vr-multmidia/docs/DEBUGGING.md):** Procedimentos de depuração em headset Quest 3, HUD overlay e ADB triggers.
* 🌐 **[docs/NETWORK-IO-PERFORMANCE.md](file:///home/luis/Documents/hand-on/vr-multmidia/docs/NETWORK-IO-PERFORMANCE.md):** Arquitetura do prefetcher e streaming de alta performance.
