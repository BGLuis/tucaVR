# Plano de Testes Automatizados

> Contexto: hoje toda validação é manual, no headset Quest 3 físico. Isso é
> lento e caro para iterar em lógica que não depende de fato de
> hardware/GPU/rede reais. Este documento mapeia o que dá para automatizar
> hoje, o que continua exigindo o headset (e por quê), e registra o que já
> foi implementado nesta rodada.

## 1. Estado antes desta rodada

- Rust: só existiam testes em `rust/protocols/src/prefetch.rs` (fonte fake em
  memória, leitura sequencial + seek) e `rust/protocols/src/smb/uri.rs`
  (`redact_hides_credentials`, roundtrip). Nada em `rust/core` ou `rust/audio`.
- Kotlin: nenhum teste em `app/src/test` nem `app/src/androidTest` — os
  diretórios não existiam.
- CI (`.github/workflows/main.yml`): rodava `cargo clippy` (funcional) e
  `ktlintCheck` (fallback silencioso, nunca falha de verdade — ver
  PHASE-0.1-MVP.md, T1.9). Nenhum teste automatizado rodava no CI.

## 2. Uma restrição estrutural descoberta nesta rodada

`rust/core` **não compila num host comum** (confirmado nesta sessão, não
suposto): ele depende diretamente de `ndk`/`ndk-sys` e, transitivamente via
`audio`, de `oboe-sys`, cujo build script compila C++ contra os headers do
Oboe esperando o toolchain do NDK Android.

- `cargo check -p audio` no host falha porque `ndk-sys`/`oboe-sys` não
  compilam fora de um ambiente Android.
- `cargo check -p core` falha pela mesma cadeia de dependências (`core` ->
  `audio` -> `oboe-sys`).
- Cross-compilando para `aarch64-linux-android` via `cargo ndk` (com
  `ANDROID_NDK_HOME`/`ANDROID_NDK_ROOT` configurados e as libs do FFmpeg
  cross-compiladas em `PKG_CONFIG_PATH`), o workspace inteiro (`core`,
  `audio`, `protocols`, `bridge`) compila normalmente — mas um binário
  `aarch64` não roda no runner x86_64 do CI nem no host de desenvolvimento,
  então isso serve para **verificar que compila**, não para **rodar testes**.

Isso significa: qualquer lógica que precise ser testada com `cargo test` num
laptop/CI comum não pode viver dentro do módulo `core` como estava — mesmo
que o módulo específico (ex: o antigo `core::sync`) não use nenhuma API
Android diretamente, o *crate inteiro* falha ao compilar antes de chegar no
código dele.

**Solução aplicada**: nova crate `rust/media-logic`, com **zero dependências
Android/hardware**, para hospedar a lógica pura que antes vivia em `core`.
`core` agora depende de `media-logic` e delega para lá em vez de reimplementar
a mesma lógica — ver `rust/media-logic/src/lib.rs` para a justificativa
completa. Isso não é uma camada de arquitetura nova arbitrária: é
especificamente o mecanismo necessário para que "testável sem headset" seja
possível de verdade para esse código, dado o formato do workspace existente.

## 3. O que PODE ser (e foi) testado sem headset

### 3.1 Rust — `rust/media-logic` (nova crate, roda com `cargo test` no host)

| Módulo | O que cobre | Extraído de |
|---|---|---|
| `sync.rs` | `SyncManager`: áudio como clock master, fallback de wall-clock, escala por velocidade, pause/resume sem contar o tempo pausado, reset | `core/src/sync.rs` (agora um re-export fino) |
| `audio_resample.rs` | Matemática do controle de velocidade (`target_sample_rate = base/speed`, clamps) e do bug de padding/linesize do T2.6 (`valid_sample_count`) | `core/src/audio_decoder.rs::set_speed`/`decode` |
| `playback_params.rs` | Clamps de velocidade (0.5x-2.0x) e volume (0.0-1.0) | `core/src/playback.rs::set_speed`/`set_volume` |
| `session.rs` | Contrato de "geração" de playback do bug T2.6: gerações não compartilham flags `is_playing`/`is_running`; `stop_and_join` sinaliza parada, acorda threads pausadas, e só retorna depois do `join()` real | `core/src/playback.rs::PlaybackSession` (agora usa `Generation` internamente) |

`SyncManager` ganhou um ponto de injeção de clock (`trait Clock`, com
`SystemClock` em produção e `FakeClock` nos testes) — sem isso, testar
pause/resume e o fallback de wall-clock exigiria `sleep()`s reais e testes
lentos/flaky. É a única mudança de comportamento em relação ao código
original; a API pública (`new`, `start`, `pause`, `resume`,
`update_audio_pts`, `get_master_clock`, ...) é idêntica.

### 3.2 Rust — `rust/protocols` (já era host-testable, ganhou mais testes)

| Arquivo | O que cobre |
|---|---|
| `prefetch.rs` (já existia) | Cache de leitura em blocos com fonte fake em memória |
| `smb/uri.rs` (já existia) | Serialização/redação da URI interna SMB |
| `http.rs` (novo) | `probe()` (HEAD com fallback para GET+Range, detecção de `Accept-Ranges`, servidor inalcançável) e `HttpsRangeSource` (leitura por range, falha cedo sem suporte a range, leitura além do EOF), usando um servidor HTTP mock real (`httpmock`, novo dev-dependency) — sem precisar de servidor de verdade |

**Bug de produção real encontrado e corrigido ao escrever esses testes**: o
branch de HEAD de `probe()` usava `reqwest::Response::content_length()`, que
a própria documentação do reqwest descreve como não confiável quando a
resposta não tem corpo (exatamente o caso de HEAD) — na prática isso fazia
`probe()` reportar `content_length: Some(0)` para QUALQUER servidor, mesmo um
que respondesse `Content-Length: 123456` corretamente no HEAD, o que por sua
vez fazia `HttpsRangeSource::new`/`read_range` achar que qualquer offset
estava além do fim do arquivo e devolver 0 bytes silenciosamente. Corrigido
lendo o header `Content-Length` diretamente (`parse_content_length_header`)
em vez de confiar em `content_length()`. Isso é relevante porque T7.1 no
PHASE-0.1-MVP.md documenta explicitamente que o caminho HTTPS "nunca foi
testado contra um servidor real" — este bug teria aparecido exatamente
nesse teste manual, e o teste automatizado com mock server pegou antes.

### 3.3 Kotlin — `app/src/test` (novo, JVM puro, sem emulador/Robolectric)

| Arquivo | O que cobre |
|---|---|
| `MediaSorterTest.kt` | Ordenação por nome/data/tamanho/tipo, ascendente/descendente, diretórios sempre antes de arquivos em qualquer modo |
| `DirectoryNavigatorTest.kt` | `enter`/`goBack`/`canGoBack`, pilha de navegação com múltiplos níveis, erro ao entrar num arquivo que não é diretório |
| `DirectoryListerTest.kt` | Filtro por extensão (vídeo/áudio/imagem), case-insensitivity, diretórios não são reclassificados pela extensão do nome, listagem não-recursiva (um nível), diretório vazio, tamanho de arquivo vs. diretório, `mediaTypeForExtension` para todas as extensões declaradas |
| `ThumbnailGeneratorCacheKeyTest.kt` | Lógica de cache-key (`ThumbnailGenerator.cacheKeyFor`, extraída como função pura `internal`): mesma entrada → mesma chave, path diferente → chave diferente, arquivo sobrescrito no mesmo path (tamanho/data mudou) → chave nova, colisão entre arquivos não relacionados com mesmo tamanho/data → chaves diferentes, formato do hash (SHA-256 hex, 64 chars) |

`ThumbnailGenerator.getThumbnail()` continua precisando de `Context` e
`MediaMetadataRetriever` reais — só a lógica de chave de cache (que é o que
efetivamente decide corretude do cache: mesmo arquivo = mesma chave, arquivo
diferente/editado = chave diferente) foi extraída como função `internal`
pura e testada. O resto (decodificar frame, gravar em disco) é I/O real de
Android, fora do escopo automatizável sem Robolectric — e mesmo com
Robolectric, `MediaMetadataRetriever.getFrameAtTime` não teria conteúdo de
vídeo real para decodificar, então o teste seria de valor questionável;
melhor deixar para verificação manual (thumbnail aparece corretamente no
file browser).

## 4. O que CONTINUA exigindo o headset físico (e por quê)

- **Renderização OpenXR real** (`native/src/*.cpp`): swapchain, timing de
  `xrWaitFrame`/`xrBeginFrame`/`xrEndFrame`, reprojection/ATW, multiview —
  só existe com o runtime OpenXR do Quest rodando.
- **Input físico de controller e haptics**: raycasting sobre painéis reais,
  thumbstick/grip, pulsos de vibração — precisa do hardware do controller.
- **Decodificação de hardware via MediaCodec real**: `HwDecoder`
  (`rust/core/src/decoder.rs`) usa `AMediaCodec`, que só existe no runtime
  Android real; não há como simular fielmente sem o decoder de hardware do
  Quest 3 (XR2 Gen 2) por trás.
- **Textura compartilhada Rust→C++**: `AHardwareBuffer`/`ImageReader`
  (`rust/core/src/texture.rs`) é uma API de sistema Android real, sem
  equivalente em host.
- **Oboe / áudio de baixo nível real**: `rust/audio` fala com o driver de
  áudio do Android via Oboe; a lógica testável (resampling, clamps) já foi
  extraída para `media-logic` — o que resta (`AudioOutput`, streams,
  callback de verdade) é inerentemente hardware.
- **Comportamento térmico**: throttling do Quest 3 após uso prolongado só
  aparece em hardware real sob carga real.
- **Condições reais de rede (Wi-Fi/SMB/NAS)**: os testes de SMB e HTTP(S)
  cobrem a lógica de parsing/URI/redact/prefetch/probe com fakes e mock
  server — mas autenticação NTLMv2 real, comportamento de um NAS/Samba real,
  reconexão em Wi-Fi instável de verdade, e desempenho de streaming 4K via
  SMB/HTTPS continuam exigindo um servidor real (documentado como gap
  conhecido em T6.1/T7.1 do PHASE-0.1-MVP.md — este trabalho não muda essa
  situação, só evita que ela contamine testes de lógica pura).
- **UI renderizada via `VirtualDisplay`/`Presentation`** projetada como quad
  OES em VR (file browser, controles, rede): o *roteamento de eventos* (ray
  → `MotionEvent` sintético → `Presentation`) e a aparência real da UI em VR
  só se confirmam no headset. A lógica de dados por trás (listagem,
  ordenação, navegação, cache) já está coberta pelos testes JVM da seção 3.3.

## 5. Como rodar

```bash
# Rust — crates host-testable (protocols + media-logic)
cd rust && cargo test -p protocols -p media-logic

# Rust — verificar que o workspace inteiro ainda compila para Android
# (não roda testes, so confirma que compila — precisa de NDK + FFmpeg
# cross-compilado; ver scripts/build.sh para as env vars necessárias)
cd rust && cargo ndk -t aarch64-linux-android -P 26 build

# Kotlin — testes JVM puros
./gradlew testDebugUnitTest
```

Resultado nesta sessão: **68 testes, 0 falhas** (26 em `media-logic`, 12 em
`protocols`, 30 em Kotlin `app/src/test`). Workspace Rust completo
cross-compila para `aarch64-linux-android` sem erros.

## 6. CI (`.github/workflows/main.yml`)

Adicionados dois steps novos, além do `clippy`/`ktlintCheck` que já existiam:

- `cargo test -p protocols -p media-logic` — não o workspace inteiro,
  porque `core`/`audio`/`bridge` não compilam num runner genérico (ver
  seção 2). Compilar essas crates para Android no CI (via `cargo ndk`) e
  então *rodar* os testes exigiria um emulador Android/dispositivo real
  conectado ao runner — fora do escopo desta rodada, e o valor marginal é
  baixo já que a lógica que valeria a pena testar ali já foi extraída para
  `media-logic`.
- `./gradlew testDebugUnitTest` — os testes JVM do Kotlin.

## 7. Pequenos refactors feitos para viabilizar os testes

1. **Nova crate `rust/media-logic`** (zero deps Android): hospeda
   `SyncManager` (movido de `core::sync`, que virou um re-export),
   `audio_resample::{target_sample_rate, valid_sample_count}` (extraído de
   `AudioDecoder::set_speed`/`decode`), `playback_params::{clamp_speed,
   clamp_volume}` (extraído de `PlaybackController::set_speed`/`set_volume`),
   e `session::Generation` (generaliza o contrato de "geração" que o
   `PlaybackSession` de `core::playback` usa internamente desde esta rodada,
   substituindo a bookkeeping manual de `is_playing`/`is_running` que existia
   ali). Todas essas trocas em `core` são delegações mecânicas — mesma API
   pública, mesma assinatura — verificadas com cross-compile real
   (`cargo ndk build`, workspace inteiro) após cada mudança, já que `core`
   não compila no host deste ambiente.
2. **`SyncManager` ganhou injeção de clock** (`trait Clock` +
   `SystemClock`/`FakeClock`) para poder testar pause/resume e o fallback de
   wall-clock sem `sleep()` real. Não muda a API pública usada por
   `core::playback`.
3. **`ThumbnailGenerator.cacheKeyFor`** extraída como função `internal` pura
   (antes era um bloco inline dentro de `cacheFileFor`), sem tocar em
   `Context`/`MediaMetadataRetriever`/disco.

Nenhuma abstração nova além dessas foi introduzida — em particular, não foi
criada nenhuma camada de trait/mock para `HwDecoder`/`AudioOutput`/
`TextureOutput`/`Demuxer` em si; isso permitiria testar o
`PlaybackController` de ponta a ponta, mas exigiria uma refatoração maior
(esses tipos são usados com APIs Android concretas como `MediaFormat`,
`NativeWindow`, `AHardwareBuffer` espalhadas por vários arquivos) — fora do
orçamento desta rodada e desproporcional ao valor marginal, já que a lógica
que mais importava testar ali (matemática de sync/velocidade, contrato de
geração/shutdown) já foi extraída e coberta.
