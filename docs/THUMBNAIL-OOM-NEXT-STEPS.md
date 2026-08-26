# Thumbnails de rede: OOM em pastas 8K + geração ciente de formato 3D

> Documento de handoff — escrito para uma sessão SEM contexto prévio.
> Tudo que é preciso saber está aqui; os `arquivo:linha` foram verificados
> em `develop` (base: `6ac4975`).

---

## 1. O que aconteceu

O app foi morto pelo `lmkd` (low memory killer) do Horizon OS enquanto o
usuário navegava por uma pasta SMB contendo majoritariamente vídeos 8K.
**Não foi crash**: não há tombstone, `SIGSEGV`, `SIGABRT` nem
`OutOfMemoryError`.

Evidência (logcat do dispositivo, 2026-08-25):

```
23:18:41.455  killinfo: [15504,10062,0,201,4641532,3,242472,256272,...]
23:18:42.452  oom_reaper: reaped process 15504 (com.tucavr)
23:18:42.951  ActivityManager: Process com.tucavr (pid 15504) has died: fg TOP
```

Decodificando o `killinfo` do lmkd: pid `15504`, `oom_score_adj=0`
(foreground), **RSS = 4.641.532 KB ≈ 4,43 GB**, `kill_reason=3`
(`LOW_MEM_AND_SWAP`).

Calibração: as outras ~20 vítimas da mesma janela tinham RSS entre 88 MB e
160 MB. O tucaVR estava com **58% dos 7,6 GB totais** do aparelho
(`MemTotal: 7943148 kB`), mais ~3,5 GB empurrados para swap.

Nos 20 s anteriores o lmkd desceu a escada inteira de `oom_adj` — matou
background, serviços e até apps visíveis — antes de sacrificar o app em
foreground. O swap livre caiu de 4,47 GB para 0,91 GB nesse intervalo.

**Os logs próprios do app se perderam**: o buffer `main` do logcat é de
256 KiB e a tempestade de kills rotacionou tudo; o buffer começa 3 s
*depois* da morte. Antes de qualquer reprodução, rodar
`adb logcat -G 16M`.

---

## 2. Causa raiz

1. Usuário entra numa pasta SMB — `screens/NetworkSmbScreen.kt:461`.
2. `FileAdapter.onBindViewHolder` dispara **uma corrotina de thumbnail por
   item visível** — `screens/adapters/FileAdapter.kt:237`.
3. Cada uma chama `nativeSmbGenerateThumbnail`, uma chamada **JNI
   bloqueante** → `core::thumbnail::generate`
   (`rust/core/src/thumbnail.rs:93`).
4. `generate` faz **decode por software em resolução nativa**. Isso é
   deliberado e documentado em `thumbnail.rs:80-86`: decode de hardware
   exigiria Surface/AHardwareBuffer/GL, considerado peso desnecessário
   para um frame estático.
5. Usuário rola a lista. `FileAdapter.kt:126` chama
   `holder.thumbnailJob?.cancel()` — **e isso não cancela nada**.
   Cancelamento de corrotina é cooperativo e não interrompe uma chamada
   JNI já em andamento.
6. **Nada limita concorrência.** `Dispatchers.IO` (64 threads por padrão)
   enche de decoders 8K órfãos que ninguém mais espera.

### O código já sabia disso

`rust/core/src/thumbnail.rs:10-16`:

> *"o cancelamento cooperativo do Kotlin (Job.cancel()) nao interrompe uma
> chamada JNI ja em andamento, entao o cancelamento real precisa ser um
> flag do lado Rust checado a cada posicao."*

O flag `STRIP_CANCELLED` (`thumbnail.rs:16`) foi criado — **mas só para
`generate_strip`**. O `generate`, que é o caminho da listagem, não tem
equivalente. O diagnóstico já estava escrito; a correção cobriu metade
dos casos.

### Por que só apareceu com rede

O caminho local usa `MediaMetadataRetriever.getScaledFrameAtTime`
(`filebrowser/ThumbnailGenerator.kt:95`), que decodifica **no processo do
mediaserver**, fora do espaço de endereçamento do app, e devolve o bitmap
já escalado. O RSS do tucaVR nunca inflava.

O caminho de rede decodifica **dentro do processo**, em resolução cheia.
Mesma tela, mesmo scroll, modelo de memória completamente diferente.

### A aritmética

Frame VR180 8K (7680×3840, YUV420p 8 bits) = 7680×3840×1,5 ≈ **42 MB**.
`thumbnail.rs` nunca configura `thread_count`, então o ffmpeg usa o padrão
(frame-threading, um por core — 8 no XR2 Gen 2), e cada decoder mantém seu
pool de frames de referência (DPB). Uma instância passa facilmente de
500 MB. Meia dúzia em paralelo chega nos 4,43 GB observados.

Os comentários em `thumbnail.rs:57-60` confirmam que o corpus de teste é
VR180 8K, e o usuário confirmou que a pasta era majoritariamente 8K.

---

## 3. Escopo: quais protocolos

Verificado — os três protocolos com thumbnail caem no **mesmo**
`core::thumbnail::generate`:

| Protocolo | Tela | Bridge | Afetado |
|---|---|---|---|
| SMB | `NetworkSmbScreen.kt:461` | `bridge/src/lib.rs:1345` | **sim** |
| FTP | `NetworkFtpScreen.kt:437` | `bridge/src/lib.rs:1384` | **sim** |
| SFTP | `NetworkSftpScreen.kt:455` | `bridge/src/lib.rs:1419` | **sim** |
| NFS | `NetworkNfsScreen.kt` | — | não gera thumbnail |
| DLNA | `NetworkDlnaScreen.kt` | — | não gera thumbnail |
| Local | `ThumbnailGenerator.kt` | — | não (mediaserver) |

Corrigir em `core::thumbnail::generate` + `NetworkThumbnailGenerator`
cobre os três de uma vez. **Não** duplicar a correção por protocolo.

### Já descartados (não perder tempo)

- `rust/protocols/src/prefetch.rs` — `PrefetchReader` é disciplinado:
  no máximo um prefetch em voo (`pending_start`), cache de bloco único de
  4 MB, drena resultados órfãos. Não é o vazamento.
- `native/src/vr_player_app_vulkan.cpp:3126` — `videoImageCache` limitado
  a 16 com evicção LRU real e destruição completa. Correto.
- `rust/core/src/playback.rs:369` — filas de pacotes limitadas (90/100).
- `rust/core/src/texture.rs:31` — `ImageReader` com `maxImages=4`.
- `demuxer.rs:21-22` — `probesize`/`analyzeduration` já em 1 MB / 1 s.

---

## 4. Trabalho 1 — Gate de concorrência adaptativo por custo

### A pergunta

Um semáforo fixo de 1 resolve o OOM mas é exagerado para 1080p, onde
dezenas poderiam rodar em paralelo. Queremos paralelismo proporcional ao
custo real de cada vídeo.

### Veredito de viabilidade: **preciso e implementável**, com uma ressalva

**A ressalva importa:** a listagem de rede devolve só nome/tipo/tamanho —
SMB/FTP/SFTP não entregam resolução (ver comentário em
`NetworkThumbnailGenerator.kt:78-84` e `MediaEntry.kt:18-28`). Portanto:

> **Usar `sizeBytes` como proxy de custo NÃO é preciso e deve ser
> rejeitado.** Tamanho de arquivo = duração × bitrate, o que não determina
> resolução. Um clipe 8K de 3 min pode ser menor que um filme 1080p de 2 h.
> A heurística erraria justamente nos casos que causam o OOM.

**O que torna a heurística precisa:** um desenho em duas fases. A
resolução real pode ser lida **antes de decodificar**, barato.

`core::metadata::extract` (`rust/core/src/metadata.rs`) já faz exatamente
isso: abre o demuxer (probe ≤ 1 MB, `demuxer.rs:21`), **não decodifica
frame nenhum**, e devolve `TrackInfo.width/height`
(`media-logic/src/metadata_wire.rs:41-42`). Já está exposto por protocolo:
`smb_read_metadata` (`bridge/src/lib.rs:1640`), `ftp_read_metadata`
(`:1671`), `sftp_read_metadata` (`:1698`).

### Desenho

```
para cada item visível:
  1. cache em disco existe?  -> devolve, custo zero (curto-circuito já existe)
  2. probe  (gate leve: Semaphore(4))       ~1 MB de rede, sem decode
  3. custo = f(width, height, bits)
  4. acquire(custo) no gate de orçamento    <- espera aqui
  5. decode + scale
  6. release(custo)
```

### Modelo de custo

Com `thread_count = 1` (Trabalho 3), a memória vira previsível:

```
frame_bytes = width * height * 1.5        (8 bits 4:2:0)
frame_bytes = width * height * 3.0        (10 bits)
custo       = frame_bytes * K
```

`K` cobre DPB + frames de trabalho. **`K` precisa de calibração empírica
no dispositivo** — não inventar o valor. Ponto de partida: `K = 10`.

Estimativas com `K = 10`:

| Resolução | frame | custo | paralelas @ 900 MB |
|---|---|---|---|
| 8K VR180 (7680×3840) | 42 MB | ~420 MB | 2 |
| 8K (7680×4320) | 50 MB | ~500 MB | 1–2 |
| 4K (3840×2160) | 12,4 MB | ~124 MB | 7 |
| 1080p (1920×1080) | 3,1 MB | ~31 MB | 29 |

É exatamente o comportamento pedido: 8K serializa, 1080p paraleliza.

### Implementação do gate

`kotlinx.coroutines.Semaphore` é apenas contador e **não** suporta
aquisição atômica de N permits — usar `acquire()` N vezes causa deadlock
(um pedido grande nunca junta todos os permits enquanto pequenos passam
na frente).

Implementar um `MemoryBudgetGate` próprio (~60 linhas): mutex + fila de
espera. Requisitos:

- Se `custo > orçamento total`, clampar para o orçamento inteiro e deixar
  rodar sozinho — nunca fazer deadlock.
- Orçamento inicial sugerido: **900 MiB**, derivado de
  `ActivityManager.MemoryInfo.availMem` em vez de hardcoded.
  Não usar `getMemoryClass()`/`getLargeMemoryClass()`: essas medem heap
  Java, e o consumo aqui é **nativo**.
- Reagir a `ComponentCallbacks2.onTrimMemory` encolhendo o orçamento.

#### Cargas mistas e política de fila

Por ser orçamento (e não slots por classe de resolução), mixes
heterogêneos funcionam sem caso especial — cada job debita seu próprio
custo. Com K=10 e 900 MiB:

| Mix | Custo | Cabe? |
|---|---|---|
| 1×8K + 4×4K | 896,6 MiB | sim, por 3 MiB |
| 2×8K | 843,8 MiB | sim |
| 2×8K + 1×4K | 962,5 MiB | não |
| 1×8K + 3×4K + 4×1080p | 896,7 MiB | sim |

Margens desta ordem são apertadas: **a validade do mix depende
inteiramente da calibração de `K`.** Com K=12 o primeiro mix já estoura
(1076 MiB). Calibrar antes de fixar o orçamento.

**Não usar FIFO estrito.** Ele evita starvation do 8K, mas causa
*head-of-line blocking*: um 8K na cabeça esperando 420 MiB bloqueia todos
os 4K/1080p atrás dele mesmo com 300 MiB livres. Numa pasta mista isso
desperdiça orçamento e torna a listagem visivelmente mais lenta.

Usar **barging limitado por prazo**:

- Pedidos menores podem ultrapassar a cabeça da fila enquanto o job da
  cabeça esperar menos que `STARVATION_DEADLINE_MS` (sugestão: 2000 ms).
- Vencido o prazo, o gate para de admitir novos pedidos e reserva
  orçamento até o job da cabeça caber.
- Isso limita a espera do 8K a ~prazo + duração do job em curso mais
  longo, mantendo vazão dos pequenos.

Testes JVM obrigatórios para esta política (`./gradlew testDebugUnitTest`):

- mix 1×8K + 4×4K admitido simultaneamente dentro do orçamento;
- pequenos furam a fila enquanto o prazo da cabeça não vence;
- job grande **sempre** entra depois do prazo (anti-starvation), mesmo sob
  fluxo contínuo de pequenos;
- clamp do pedido maior que o orçamento;
- release em exceção e em cancelamento;
- ausência de deadlock.

Colocar como **lógica pura testável na JVM** (padrão do projeto, ver
`docs/TESTING-PLAN.md`) e cobrir com `./gradlew testDebugUnitTest`:
FIFO, clamp do pedido gigante, release em exceção, ausência de deadlock.

### Ponto de inserção

Dentro de `NetworkThumbnailGenerator.getThumbnail`
(`NetworkThumbnailGenerator.kt:25`) — um único lugar cobre as 3 telas de
rede, mais `FileDetailScreen.kt:161` e `PlayerScreen.kt:140`.

Checar `coroutineContext.isActive` **antes** de adquirir permit e logo
depois: um item já fora da tela não deve nem entrar na fila.

### Otimização recomendada

Cachear o resultado do probe em disco, com a mesma chave sha256 de
`cacheKeyFor` (`NetworkThumbnailGenerator.kt:104`), reusando
`metadata_wire::encode`. Reentrar numa pasta passa a custar zero probes.

---

## 5. Trabalho 2 — Cancelamento real (lado Rust)

`STRIP_CANCELLED` é um `AtomicBool` **global** — serve para
`generate_strip` porque só existe uma geração de trilha por vez. Para
`generate` há várias concorrentes, então um bool global cancelaria todas.

Desenho: token por geração.

- Kotlin gera um `token: Long` por chamada e passa nas assinaturas
  `nativeSmb/Ftp/SftpGenerateThumbnail`.
- Rust mantém `Mutex<HashSet<u64>>` de tokens cancelados; nova bridge fn
  `cancel_thumbnail_generation(token)`.
- `decode_and_scale` checa o token no laço de pacotes
  (`thumbnail.rs:~142`, já limitado por `MAX_PACKETS_TRIED = 500`) e
  `generate` checa entre as tentativas de seek de fallback
  (`thumbnail.rs:110`).
- Kotlin dispara o cancel via `Job.invokeOnCompletion` / `onViewRecycled`.
- Remover o token do set ao concluir, senão o `HashSet` vaza.

Com o gate do Trabalho 1 o pile-up já cai muito; este trabalho evita que
um scroll rápido siga decodificando 8K que ninguém vai ver.

---

## 6. Trabalho 3 — `thread_count = 1` no decoder de thumbnail

Uma linha, e corta o consumo por instância em várias vezes. Frame-threading
não traz nada para um frame estático, e é o que multiplica o pool de
frames.

Em `thumbnail.rs`, entre as linhas 103 e 104:

```rust
let mut codec_context = ffmpeg::codec::context::Context::from_parameters(stream.parameters()).ok()?;
codec_context.set_threading(ffmpeg::threading::Config {
    kind: ffmpeg::threading::Type::None,
    count: 1,
});
let mut decoder = codec_context.decoder().video().ok()?;
```

Verificado: `set_threading` existe em `ffmpeg-next 9.0`
(`codec/context.rs:114`). Note que `codec_context` precisa virar `mut`.

**Fazer este trabalho primeiro** — é o de menor risco, e muda a constante
`K` do modelo de custo do Trabalho 1. Calibrar `K` só depois dele.

---

## 7. Trabalho 4 — Thumbnails cientes de formato 3D

### Problema

`decode_and_scale` escala o frame decodificado inteiro para 512×288
(`thumbnail.rs:~169`) **ignorando o layout estéreo**. Resultado hoje:

- SBS → miniatura com dois olhos espremidos lado a lado.
- Over/Under → duas imagens empilhadas.
- VR180 SBS → dois círculos fisheye.

### A detecção já existe e é gratuita aqui

`format3d_detect::detect(&demuxer, path, width, height)`
(`rust/core/src/format3d_detect.rs:21`) devolve `(Format3D,
DetectionConfidence)` a partir de tags MKV `stereo_mode`, side data
`Stereo3d`/`DataSpherical`, nome do arquivo e dimensões.

`generate` **já tem o `Demuxer` e o `path` em mãos** — detectar ali não
custa I/O adicional. Ver `metadata.rs:46-50` para o uso exato.

Cuidado de borrow: calcular o `Format3D` **antes** do empréstimo mutável
do demuxer em `decode_and_scale` (`detect` toma `&demuxer`, `decode_and_scale`
toma `&mut demuxer`).

### Recorte por variante

Variantes em `rust/media-logic/src/format3d.rs:79`.

| `Format3D` | Recorte | Correção de aspecto |
|---|---|---|
| `Flat2D`, `Spherical360Mono`, `Vr180Mono` | nenhum | — |
| `SbsFull`, `Spherical360SbsFull`, `Vr180Sbs` | metade esquerda | nenhuma |
| `SbsHalf`, `Spherical360SbsHalf` | metade esquerda | esticar 2× na horizontal |
| `OverUnderFull`, `Spherical360OverUnderFull` | metade superior | nenhuma |
| `OverUnderHalf`, `Spherical360OverUnderHalf` | metade superior | esticar 2× na vertical |

A distinção Full/Half existe no enum exatamente por isto: em `Half` cada
olho está comprimido e precisa ser reexpandido, senão a miniatura sai com
aspecto errado.

### Como recortar

O `ScalingContext` recebe um `Frame` inteiro, então há duas opções:

- **(a) Copiar a região recortada para um `Frame` novo antes de escalar.**
  Simples e seguro. Custo: um buffer extra de até ~21 MB por thumbnail —
  irrelevante perto dos ~420 MB do decoder. **Recomendada para a v1.**
- **(b) Ajustar `AVFrame.data[i]`/`width`/`height` via `unsafe`.** Zero
  cópia. O projeto já faz `unsafe` sobre `AVCodecParameters`
  (`metadata.rs:111`), então há precedente. Deixar como otimização
  posterior, se medição justificar.

Atenção em (a): respeitar o `stride`/linesize de cada plano e o
subsampling do croma (em 4:2:0 o offset horizontal do plano U/V é metade
do offset do Y; um offset ímpar quebra o alinhamento).

### Fora de escopo nesta v1

Reprojetar equirretangular para uma vista retilínea. Para 360/180, pegar
um olho e escalar é aceitável por ora. Anotar como trabalho futuro.

### Interação com `is_effectively_black`

`generate` tenta até 3 pontos de seek verificando se o frame saiu preto
(`thumbnail.rs:100-124`). O recorte deve acontecer **antes** dessa
verificação, senão a métrica considera a área do olho errado ou barras
pretas de um layout que nem é o real.

---

## 8. Achados secundários (não são a causa desta morte)

Dois caches de `Bitmap` sem limite algum. Nenhum é alimentado durante
navegação, então não causaram este kill — mas são vazamentos reais numa
sessão longa:

- `filebrowser/FolderPreviewGenerator.kt:31` —
  `private val mosaicCache = mutableMapOf<String, Bitmap>()`, sem
  evicção. Mosaicos 512×288 ARGB_8888 ≈ 590 KB cada.
- `filebrowser/NetworkThumbnailGenerator.kt:139` — `scrubMemoryCache`,
  com comentário assumindo explicitamente que "nunca invalidado" é
  aceitável. Uma `ScrubStrip` de filme de 2 h ≈ 6,9 MB.

Correção sugerida: trocar por `LruCache` dimensionado **por bytes**
(`sizeOf` = `bitmap.byteCount`), não por contagem.

---

## 9. Ordem de execução sugerida

Branch: `feat/fix-smb-thumbnail-oom` a partir de `develop`.

1. **Trabalho 3** (`thread_count = 1`) — uma linha, menor risco, muda a
   constante do modelo de custo.
2. **Medir `K`** no dispositivo com 8K/4K/1080p (ver §10).
3. **Trabalho 1** (gate de orçamento) — é o que sozinho teria evitado o
   kill.
4. **Trabalho 2** (cancelamento por token).
5. **Trabalho 4** (recorte 3D) — independente dos anteriores, pode ir em
   paralelo ou em branch separada.
6. **§8** (caches LRU) — oportunístico.

Se for preciso entregar só uma coisa: **Trabalho 1 + 3**.

---

## 10. Validação

**Não há cobertura automatizada para o caminho real** — decode de rede e
render exigem o Quest 3 físico (`docs/TESTING-PLAN.md`). Não declarar como
verificado o que não rodou no headset.

Automatizável:

```bash
cd rust && cargo test -p protocols -p media-logic
cd rust && cargo clippy -- -D warnings
./gradlew testDebugUnitTest      # MemoryBudgetGate entra aqui
```

- Lógica de recorte 3D (mapa `Format3D` → retângulo + fator de aspecto):
  extrair para `media-logic` e testar lá, seguindo a razão documentada em
  `docs/TESTING-PLAN.md` §2. `format3d.rs` já vive em `media-logic`.
- `MemoryBudgetGate`: teste JVM puro.

No dispositivo:

```bash
adb logcat -G 16M                      # ANTES de reproduzir
adb shell dumpsys meminfo com.tucavr   # a cada ~2 s durante o scroll
```

`dumpsys meminfo` separa `Native Heap` de `Gfx dev`/`EGL mtrack` — é o que
distingue "decoders acumulando" de "frames gráficos vazando".

Critério de aceite: rolar a pasta 8K de ponta a ponta com RSS estável
abaixo de ~1,5 GB, e nenhuma linha `killinfo` mencionando `com.tucavr`.

### Lacuna de telemetria

`native/src/debug_stats.h` não tem **nenhum** contador de memória — nem
RSS, nem allocations vivas. Justamente o modo de falha que aconteceu não
tem instrumentação. Considerar adicionar RSS + número de gerações de
thumbnail em voo ao HUD de debug (`docs/DEBUGGING.md`), o que também dá
leitura direta do gate do Trabalho 1.

---

## 11. Armadilhas

- **Não** usar `sizeBytes` da listagem como proxy de custo (§4).
- **Não** duplicar correção por protocolo: SMB/FTP/SFTP compartilham
  `core::thumbnail::generate` (§3).
- **Não** usar `Semaphore` contador para o orçamento — deadlock (§4).
- **Não** usar FIFO estrito no gate — head-of-line blocking em pastas
  mistas; usar barging limitado por prazo (§4).
- **Não** reaproveitar `STRIP_CANCELLED` para `generate`: é global e
  cancelaria todas as gerações concorrentes (§5).
- **Não** dimensionar orçamento por `getMemoryClass()` — mede heap Java,
  o consumo aqui é nativo (§4).
- Bitmaps do Android têm pixels no **heap nativo** desde o Android 8: um
  vazamento de Bitmap aparece como RSS e **nunca** dispara
  `OutOfMemoryError`. Ausência de OOM não é prova de ausência de vazamento.
- Se `ScreenMode` for tocado, o enum numérico precisa ficar em sincronia
  nos **três** lugares (ver `CLAUDE.md`) — mas o recorte do §7 não deve
  precisar mexer nele.
