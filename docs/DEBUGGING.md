# Ferramentas de Debug de Vídeo

> Contexto: depurar os modos SBS/OU/360/180 exigia reproduzir um arquivo real
> naquele formato, ler logcat sem filtro nenhum, e adivinhar que estado
> interno (`ScreenMode`, `stereoLayout`, `polar180`, `swapEyes`) o app achava
> que estava ativo. Este documento registra as quatro ferramentas adicionadas
> pra isso: logging de transição, um jeito de forçar qualquer modo via adb
> sem precisar do arquivo real naquele formato, um HUD na própria cena
> mostrando o estado atual, e suporte opcional a Vulkan validation layers.
> Nenhuma delas aparece em builds de release.

## 1. Logging de transição (sempre ativo, os dois caminhos)

Toda troca de `ScreenMode` e toda transição "sem frame de vídeo" ↔ "frame
ativo" loga uma linha, em vez de silêncio total ou spam a cada frame:

```bash
# Vulkan (caminho padrao)
adb logcat -s VRPlayerAppVK:I VRPlayerAppVK:W VRPlayerAppVK:E

# GLES (-PvrplayerGraphicsApi=GLES)
adb logcat -s VRPlayerApp:I VRPlayerApp:E
```

Procure por:
- `ScreenMode -> <nome> (stereoLayout=... polar180=... swapEyes=...)` — toda
  vez que o modo muda (botão 3D na UI, `nativeSetScreenMode`, ou o broadcast
  de debug abaixo).
- `video comecou a produzir frames` / `video parou de produzir frames` (ou
  `sem frame disponivel, usando fallback quad solido` no Vulkan) — indica se
  o problema é decode/rede (nunca chega a "comecou") ou renderização (chega,
  mas nada aparece na tela).
- `GetOrImportVideoFrame falhou` (só Vulkan) — falha ao importar o
  `AHardwareBuffer` decodificado como `VkImage` (ver Estágio 3 em
  `docs/VULKAN-MIGRATION-PLAN.md`).

## 2. Forçar um ScreenMode via adb (só em build debuggable)

Útil pra testar todos os modos (SBS, OU, 360, 180, Vr180SBS) **no mesmo
arquivo já carregado**, sem precisar de um arquivo real gravado naquele
formato específico:

```bash
adb shell am broadcast -a com.vrplayer.debug.SET_SCREEN_MODE --ei mode 6
adb shell am broadcast -a com.vrplayer.debug.CYCLE_SCREEN_MODE
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
adb shell am start -n com.vrplayer/.VRActivity \
  -e video_path /sdcard/Movies/teste_8k_180.mp4 --ei screen_mode 9
```

Diferença: este último só dispara ~3s depois do cold start (delay
heurístico, ver `AUTO_PLAY_DELAY_MS`) e só uma vez por processo; o broadcast
acima funciona a qualquer momento, com o player já rodando.

O receiver só é registrado se `ApplicationInfo.FLAG_DEBUGGABLE` estiver
setada (verdadeiro pro build `debug` do Gradle por padrão) — nunca existe
num APK de release, então não é uma superfície de ataque nesse caso.

## 3. HUD de debug na cena

Com um build debuggable, o painel de controles (mesmo painel do
play/pause/seek) ganha uma linha extra de texto pequeno, atualizada ~10x/seg,
formato:

```
VULKAN | Sphere180 | stereoLayout=1 polar180=1 swap=0 | video=ativo
```

Mostra exatamente o que o pipeline de renderização está usando naquele
frame — sem precisar cruzar logcat com o que a UI do botão "modo 3D" diz que
está selecionado (que podem divergir se o Rust resetar o modo num novo
`playFile`, por exemplo — ver comentário em
`VRControlsPresentation.updateProgress`).

Implementação: `native/src/vr_player_app.cpp` / `vr_player_input_vulkan.h`
chamam `VRActivity.updateDebugHud(texto)` via JNI no mesmo throttle que já
existe pra `updateMediaProgress`; o texto é construído inteiramente do lado
nativo (sem string de recurso/i18n — é diagnóstico técnico, não UI de
produção). `VRActivity.isDebuggable` filtra antes de tocar a `View`; builds
de release recebem a chamada mas ela é descartada sem custo.

## 4. Vulkan validation layers (opcional — requer um arquivo que você precisa obter)

`CreateVulkanInstanceAndDevice` (`vr_player_app_vulkan.cpp`) já checa em
runtime (`vkEnumerateInstanceLayerProperties`) se `VK_LAYER_KHRONOS_validation`
está disponível e, se estiver, habilita a layer + `VK_EXT_debug_utils` com um
callback que loga pro logcat (`VkValidation: ...`, tag `VRPlayerAppVK`,
níveis WARN/ERROR habilitados por padrão). **Sem o arquivo `.so` da layer, é
um no-op silencioso** — nada quebra, a instância Vulkan é criada normalmente
sem a layer.

O NDK não inclui mais esse binário (removido dos pacotes distribuídos). Pra
habilitar:

1. Baixe o release mais recente de
   [`KhronosGroup/Vulkan-ValidationLayers`](https://github.com/KhronosGroup/Vulkan-ValidationLayers/releases)
   (procure o asset Android, ex. `android-binaries-<versão>.zip`) — ou, se
   você já tem o Vulkan SDK instalado numa máquina de desenvolvimento, ele
   inclui os binários Android em `$VULKAN_SDK/.../android-*`.
2. Extraia `libVkLayer_khronos_validation.so` (variante **arm64-v8a** — o
   único ABI que este projeto builda, ver `native/CMakeLists.txt`).
3. Copie pra `app/src/main/jniLibs/arm64-v8a/libVkLayer_khronos_validation.so`
   (crie os diretórios se não existirem — o AGP empacota automaticamente
   qualquer `.so` sob `jniLibs/<abi>/`).
4. Rebuild (`./gradlew assembleDebug`) e instale. No primeiro frame, o
   logcat deve mostrar `VK_LAYER_KHRONOS_validation encontrada, habilitando`.

**Tire o arquivo de `jniLibs/` antes de qualquer build que não seja pra você
mesmo testar** — validation layers têm custo de performance real e não
devem ir num build de release/distribuição (o projeto não publica em loja
no momento, mas o hábito vale a pena).

Nada disso foi validado num Quest 3 físico — só compilação verificada nesta
sessão (sem headset disponível). Se o runtime do Quest não expuser a layer
mesmo com o `.so` presente (algumas plataformas Android restringem quais
processos podem carregar layers), o fallback continua sendo os logs comuns
da seção 1.
