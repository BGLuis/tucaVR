# Performance de I/O de rede para playback remoto

> Registro do trabalho de uma sessão: investigação de degradação de playback
> 8K60 via SFTP, redesenho do buffer de prefetch e paralelização de leitura
> para os 4 protocolos (SMB, SFTP, HTTP/S, FTP), a regressão encontrada em
> hardware real e a correção aplicada. Como o resto do repo, este documento
> registra o que foi **validado** (testes automatizados + Docker) vs. o que
> ainda depende do **Quest 3 físico** — ver `docs/TESTING-PLAN.md`.

## 1. Motivação

Um vídeo 8K60 HEVC transmitido via SFTP degradava progressivamente durante a
reprodução no Quest 3. Evidência que descartou a hipótese inicial ("teto
térmico/decode de hardware"):

- O mesmo arquivo, em **outro player**, toca sem problema via SFTP no mesmo
  servidor — descarta problema de servidor.
- Um vídeo 4K60 **local** (sem rede) toca normalmente no nosso player —
  descarta problema geral de decode/render.
- MKV ainda não é suportado pela aplicação, então o teste comparativo ficou
  restrito a HEVC puro.

Conclusão: o gargalo era a **implementação do cliente SFTP deste projeto**,
não o hardware, o decode nem o servidor.

## 2. Diagnóstico

Causa raiz em `rust/protocols/src/sftp/mod.rs` (`SftpFileSource::try_read_range`,
antes desta sessão): o laço de `SSH_FXP_READ` era **inteiramente sequencial**
— cada leitura de até `SFTP_MAX_READ_CHUNK` (~252KB) esperava a resposta
antes de disparar a próxima, apesar de `RawSftpSession::read` tomar `&self`
(não `&mut self`) *exatamente* para permitir chamadas posicionais concorrentes
multiplexadas por request-id sobre a mesma sessão SSH — confirmado lendo o
código-fonte da crate `russh-sftp` (`~/.cargo/registry/.../russh-sftp-2.4.0/src/client/rawsession.rs`),
não só a doc. `smb2::client::stream::FileReader::read_at` tem a mesma
propriedade (multiplexado por `MessageId`), documentada explicitamente no
doc-comment da própria crate.

Segundo achado, ortogonal: `PrefetchReader` (`rust/protocols/src/prefetch.rs`)
era um cache **pull** puro — só buscava o próximo bloco de 4-12MB quando o
atual acabava, sem nenhum read-ahead em background. Isso afeta os 4
protocolos igualmente, não só SFTP.

Terceiro achado, também ortogonal (encontrado ao revisar os 4 protocolos
juntos, não relacionado ao caso SFTP original): `FtpFileSource::read_range`
(`rust/protocols/src/ftp/mod.rs`) fazia uma única chamada `Read::read()` sem
laço — um socket TCP pode "short-read" (devolver menos do que foi pedido sem
que seja EOF), o que minava silenciosamente o tamanho de bloco assumido pelo
`PrefetchReader`.

## 3. Decisão de escopo

Instrução explícita: não fazer só a correção mínima do SFTP — usar a
oportunidade para levar **todos** os protocolos de rede ao mesmo padrão de
performance (pelo menos no cenário same-LAN, servidor e Quest na mesma rede),
com testes host-testáveis escritos **antes** da implementação. Sem perseguir
metas hipotéticas de throughput sem relação com o problema real (ex.:
"16K@100fps" foi citado só como teto ilustrativo, nunca implementado como
meta de teste).

## 4. O que foi implementado

### 4.1 `PrefetchReader` — read-ahead real em background (`prefetch.rs`)

Redesenhado para possuir uma **thread de background dedicada** que:

1. É disparada na construção (`kick_prefetch(0)`), buscando o bloco 0 antes
   do primeiro `read()`.
2. A cada bloco consumido pela thread principal, dispara em background a
   busca do **próximo** bloco sequencial (`ensure_cache` → `kick_prefetch`),
   sobrepondo o RTT de rede do próximo bloco ao tempo de consumo/decode do
   bloco atual — em vez de somá-los, como no design "pull" anterior.
3. Um seek para fora do bloco atual **e** do bloco em pré-busca invalida o
   prefetch em voo (resultado obsoleto é descartado quando chega — não há
   cancelamento cooperativo real de uma leitura de rede já em andamento).
4. `Drop` manda `Shutdown` para a thread e dá `join()` de forma síncrona,
   fechando a conexão de rede (dentro do worker) de forma determinística.

Comunicação com o worker via `std::sync::mpsc` (`Command::Fetch`/`Shutdown` →
`io::Result<Block>`), no máximo 1 pedido em voo por vez (double-buffering
simples: bloco atual + bloco seguinte).

Isto beneficia **todos** os protocolos igualmente (é puramente do lado do
cliente, agnóstico de SMB/SFTP/HTTP/FTP) — é a melhoria "de buffer" que
ajuda o player em geral, além da paralelização específica de cada protocolo.

### 4.2 `chunking::split_range` — divisão pura de range em sub-chunks (novo, `chunking.rs`)

Função determinística, sem I/O, usada pelos 3 protocolos abaixo para decidir
em quantos pedaços (e de que tamanho) dividir um `read_range` antes de
disparar as leituras de verdade:

```rust
pub fn split_range(offset: u64, total_len: u32, chunk_size: u32) -> Vec<(u64, u32)>
```

### 4.3 Leitura concorrente por protocolo

| Protocolo | Mecanismo | Tamanho de chunk | Concorrência |
|---|---|---|---|
| SMB (`smb/mod.rs`) | `FileReader::read_at(&self, ...)` via `futures_util::future::join_all` dentro do `block_on` existente | 1MB (`SMB_CONCURRENT_CHUNK_SIZE`) | até 4 em voo (`SMB_MAX_CONCURRENT_CHUNKS`) |
| SFTP (`sftp/mod.rs`) | `RawSftpSession::read(&self, ...)` via `join_all` | ~252KB (`SFTP_MAX_READ_CHUNK`, já existia) | até 4 em voo (`SFTP_MAX_CONCURRENT_CHUNKS`) |
| HTTP/S (`http.rs`) | `std::thread::scope` + `reqwest::blocking::Client` clonado (pool de conexões compartilhado) | 2MB (`HTTP_CONCURRENT_CHUNK_SIZE`) | até 4 threads em voo (`HTTP_MAX_CONCURRENT_CHUNKS`) |
| FTP (`ftp/mod.rs`) | **sem paralelismo** — decisão deliberada (ver 4.4) | — | — |

Em todos os três, os chunks de um `read_range` são processados em **lotes**
de até N (hoje N=4 nos três — ver seção 6, essa é a correção pós-regressão),
com `join_all`/`thread::scope` concorrente **dentro** de cada lote e os lotes
em si sequenciais. Um chunk curto/EOF no meio de um lote interrompe a
remontagem ali (os chunks já buscados depois dele são descartados) — mesma
semântica de short-read que o `PrefetchReader` já esperava.

### 4.4 FTP — correção de short-read, sem paralelismo (`ftp/mod.rs`)

`FtpFileSource::read_range` agora faz um laço em `Read::read()` até encher o
buffer pedido ou bater EOF, em vez de uma única chamada (que podia devolver
bem menos que o bloco de 4-12MB pedido, sem sinalizar erro).

Decisão deliberada de **não** paralelizar FTP: o protocolo não multiplexa
requests numa única conexão de controle como SMB2/SFTP — paralelismo real
exigiria múltiplas conexões de dados simultâneas (bem mais invasivo), para um
protocolo que já é o secundário/legado do projeto (sem TLS, sem
auto-reconexão — ver `docs/phases/PHASE-0.1-MVP.md` seção 6). Leitura
sequencial contígua já é quase ótima para FTP (stream contínuo, sem overhead
de request por chunk uma vez que o `RETR` está aberto).

### 4.5 Nova dependência

`futures-util = { version = "0.3", default-features = false, features = ["alloc"] }`
em `rust/protocols/Cargo.toml` — só para `future::join_all` (não precisa de
executor/runtime próprio: roda dentro do `block_on` que SMB/SFTP já usavam).
Já estava no `Cargo.lock` transitivamente (via `russh`/`reqwest`), então não
adiciona uma árvore de dependências nova.

## 5. Testes escritos (antes da implementação, como pedido)

### 5.1 Host-testável, sem rede/Docker (`cargo test -p protocols`)

`prefetch.rs` (7 testes novos/adaptados):
- `background_prefetch_overlaps_network_delay_with_consumption` — **prova
  por medição** (com atraso artificial + `Instant`) que o tempo total para
  ler N blocos com um consumidor lento fica bem abaixo de
  `N * (delay_rede + tempo_consumo)`, comparando com uma baseline "sem
  overlap" calculada no próprio teste.
- `seek_during_pending_prefetch_returns_correct_data` — seek longe enquanto
  um prefetch sequencial está em voo não corrompe a leitura seguinte.
- `mixed_sequential_and_random_access_matches_reference` — padrão misto de
  seeks comparado byte a byte contra um buffer de referência.
- `drop_joins_background_thread_without_hanging` — `Drop` encerra a thread
  de forma síncrona, com timeout no próprio teste para não travar a suíte se
  houver regressão de deadlock.
- `source_error_propagates_to_read_caller` — erro da fonte não é engolido.
- `sequential_reads_hit_one_block_per_prefetch` / `seek_and_read` — versões
  adaptadas dos testes originais do cache "pull".

`chunking.rs` (6 testes): casos de borda de `split_range` (vazio, menor que
um chunk, múltiplo exato, resto, range de 1 byte, cobertura contígua exata
de um range grande e "feio" — offset/tamanho não múltiplos do chunk).

Total: **33 testes** em `cargo test -p protocols` (mais os já existentes de
URI/redação por protocolo), **0 falhas**, roda em <1s.

### 5.2 Integração contra servidores reais via Docker (`./scripts/test-network-protocols.sh`)

Os testes `#[ignore]` existentes em `rust/protocols/tests/*_integration.rs`
(comparação de sha256 do arquivo inteiro reconstruído via
`PrefetchReader` + cada `*FileSource`) já servem como regressão de
correção para as novas implementações concorrentes — não precisaram ser
reescritos, só continuar passando com o código novo por baixo. **18/18
passaram** contra SMB (Samba real), SFTP (OpenSSH real, senha e chave),
HTTP e HTTPS (nginx real) e FTP (vsftpd real) — confirma que a
paralelização e o fix de short-read não corromperam nada.

Achado colateral (infra, não relacionado à lógica de protocolo): o container
`ftp-test` não subia — `docker-compose.yml` montava `./fixtures` como
`:ro` diretamente sobre o home dir que o entrypoint da imagem
(`delfer/alpine-ftp-server`) precisa escrever (`adduser`/`chown`) no boot.
Corrigido removendo o `:ro` (container efêmero de teste, sem risco real).

## 6. Regressão encontrada em hardware real + correção

A primeira versão desta otimização (chunks de um `read_range` disparados
**todos de uma vez**, sem limite) foi só validada via Docker/localhost antes
do deploy. No Quest 3 físico, testando o mesmo vídeo 8K60 via SFTP, os
números **pioraram** em relação à baseline anterior a esta sessão:

| Métrica | Antes desta sessão | Após 1ª versão (sem limite) |
|---|---|---|
| `vidFps` | ~30 (20 em modo 180°) | fixo em 10 |
| `decFps` | — | média 10 (variando 7–14) |
| `jitter` | ~200ms | ~300ms |

Um bloco de 12MB (`REMOTE_PREFETCH_BLOCK_SIZE` em `core/src/demuxer.rs`)
dividido em chunks de ~252KB para SFTP dá **~48 chunks** — a primeira versão
disparava os 48 concorrentemente via `join_all`. Duas causas plausíveis
(não confirmadas por log — sem acesso a `adb logcat` neste momento):

1. **Contenção de CPU com a thread de decode**: cada `SSH_FXP_READ` custa
   criptografia SSH de verdade (CPU, ao contrário do decode de vídeo, que
   roda em hardware dedicado). Antes, esse custo de CPU acontecia
   serializado com o decode (thread de decode dormia enquanto a leitura de
   rede rodava). Com o read-ahead em background, esse trabalho de CPU passou
   a rodar **concorrentemente** com o decode ativo — um burst de 48
   requisições de uma vez pode ter competido por CPU com a thread de decode
   muito mais do que o design pretendia.
2. **Estouro de limite do lado do servidor/canal SSH**: um burst desse
   tamanho pode estourar a janela de fluxo do canal SSH ou algum limite do
   servidor. Como `SftpFileSource::read_range` reconecta do zero (handshake
   SSH completo + auth + reabrir arquivo) e tenta de novo a leitura inteira
   assim que **qualquer** chunk falha, uma única falha nesse cenário vira um
   evento bem mais caro que o RTT que a concorrência deveria estar
   economizando — indistinguível, do ponto de vista do HUD, de um stall
   grande.

### Correção aplicada

Mesma ideia nos três protocolos que paralelizam (SMB, SFTP, HTTP/S): os
chunks de um `read_range` passaram a ser processados em **lotes de até 4**
(`SFTP_MAX_CONCURRENT_CHUNKS` / `SMB_MAX_CONCURRENT_CHUNKS` /
`HTTP_MAX_CONCURRENT_CHUNKS`, todas = 4 hoje), concorrentes dentro do lote,
lotes em si sequenciais — ainda pipeline (4x melhor que sequencial puro),
sem o burst de dezenas de requisições simultâneas.

Também adicionado `log::warn!("SftpFileSource: reconectando apos falha de
leitura")` em `SftpFileSource::reconnect` — se a causa (2) acima for a real,
isso vai aparecer repetidamente no log durante um stall.

**Estado**: correção reaplicada e reverificada via `cargo test -p protocols`
(33 testes) e `./scripts/test-network-protocols.sh` (18 testes, servidores
reais) — ambos voltaram a passar 100%. **Ainda não testada no Quest 3
físico** — é o próximo passo antes de considerar este trabalho concluído.
Se o problema persistir, o log de reconexão acima ajuda a decidir entre
baixar ainda mais o limite de concorrência (causa 1) ou revisar o
tratamento de erro/reconexão do SFTP (causa 2).

## 7. Sessão seguinte: causa raiz confirmada em hardware + correções

Nova sessão, com Quest 3 físico conectado (`adb devices`) e acesso a
`adb logcat` — o que faltava na sessão anterior para confirmar ou refutar as
hipóteses (1)/(2) da §6.

### 7.1 O A/B que faltava

Mesmo arquivo 8192×4320, mesmo build Vulkan, dois cenários:

| Cenário | `decFps` | `vidFps` |
|---|---|---|
| Local (armazenamento do Quest, sem rede) | 60 | 50–55 |
| SFTP | 17 | 10 |

Isto refuta a hipótese "burst de 48 requisições" da §6 como causa principal:
o APK que produziu os números de 10fps (`app-debug.apk`, build de 22:55) já
continha a correção de lote-de-4 (`SFTP_MAX_CONCURRENT_CHUNKS`, arquivos-fonte
editados às 22:52–22:53) — a mitigação já estava ativa e a regressão persistiu.
O logcat da sessão de 23:11 também mostra **0 eventos de `stutter` (>20ms) e 0
`FREEZE` (>250ms)** no loop de render, contra 26 de `judder` (frame de vídeo
parado na tela) — o caminho Vulkan/render está exonerado como gargalo
principal; o problema é throughput sustentado do cliente de rede
(~2.2 MB/s efetivos contra os ~7.85MB/s que o stream 8K60 exige).

### 7.2 Dois problemas de instrumentação encontrados

1. **`decFps` não media o decoder.** `HwDecoder::release_output_frames_with_sync`
   (`decoder.rs`) só contava um frame como "decodificado"
   (`TextureOutput::frames_decoded`) quando o callback de sync decidia
   RENDERIZAR — frames que o MediaCodec produziu mas foram descartados por
   atraso não entravam na conta. `decFps ≈ vidFps` era portanto um artefato da
   métrica, não prova de que o decode estava saudável.
2. **O `log::warn!` de reconexão SFTP (§6) nunca chegava no logcat** — nenhum
   backend de `log` (`android_logger` ou similar) era inicializado em lugar
   nenhum do workspace; todo log que de fato aparecia ia por
   `__android_log_print` cru.

### 7.3 Correções aplicadas

**Instrumentação nova** (`decoder.rs`, `texture.rs`, `playback.rs`,
`prefetch.rs`, `demuxer.rs`, `bridge/lib.rs`, HUD em
`vr_player_input_vulkan.h`/`vr_player_app.cpp`):
- `HwDecoder::metrics()` — contadores REAIS de saída do MediaCodec
  (`frames_output`) e de descarte por atraso (`frames_dropped`), incrementados
  ANTES do callback de sync, independente do resultado.
- `PrefetchStats` (`prefetch.rs`) — bytes lidos, duração do último fetch de
  bloco, blocos buscados/descartados, exposto via `PrefetchReader::stats()`.
- Profundidade da fila demux→decode exposta via clone do `Sender` (`.len()`).
- HUD ganhou `outFps`, `drop`, `net=X MB/s`, `q=N` ao lado de `decFps`/`vidFps`
  existentes, nos dois caminhos (Vulkan e GLES).
- Corrigido um bug real de contagem: `get_video_frames_decoded_count()` (e as
  novas funções análogas) devolviam `0` quando `CONTROLLER.try_lock()` falhava
  por contenção — o C++ calcula um delta em `uint64_t` contra a última
  amostra, e `0` produzia um wraparound e um pico de `decFps` espúrio na
  amostra seguinte. Agora devolvem o último valor conhecido (delta = 0).
- `Demuxer::read_packet` agora distingue EOF de erro de I/O real (antes os
  dois casos eram indistinguíveis — `Input::packets()` devolve `None` para
  ambos — e uma falha de rede virava um restart silencioso pro início do
  arquivo). O erro agora é logado; a recuperação (seek pro início) continua a
  mesma por ora.

**Prioridade da thread de I/O** (`prefetch.rs`, `raise_io_thread_priority`) —
suspeita inicial para os ~2.2MB/s: nenhuma thread do workspace definia
prioridade/afinidade explícita. Best-effort via
`libc::setpriority(PRIO_PROCESS, 0, -10)`, logado (`nice antes -> depois`).
**Refutada em hardware (§7.4 abaixo)**: mantida no código como best-effort
de baixo risco (não é a causa, mas tampouco atrapalha), porém a doc não
deve mais ser lida como "isto resolveu o throughput" — não resolveu.

**Seek não dispara mais prefetch especulativo em cadeia**
(`PrefetchReader::ensure_cache`) — um miss não-sequencial (seek de verdade)
já não dispara mais a busca do próximo bloco imediatamente; só dispara no
primeiro acesso seguinte que confirma que a leitura continua dentro do bloco
que o seek acabou de instalar. Evita que uma sequência de seeks encadeados
(probe do container, cues no fim do arquivo) pague por prefetches que o
próximo seek só ia descartar — sem verdadeiro cancelamento de I/O em voo
(que exigiria uma segunda conexão), esse continua sendo o limite de
`ensure_cache`, documentado no header do módulo.

**SFTP: erro de um chunk não derruba mais o lote inteiro**
(`SftpFileSource::try_read_range`) — antes, qualquer chunk que falhasse
dentro de um lote de 4 concorrentes propagava o erro pra `read_range`, que
reconectava do zero (handshake SSH completo) e refazia os 12MB inteiros,
descartando os outros chunks bem-sucedidos. Agora repete só o(s) chunk(s)
que falharam (até `SFTP_CHUNK_RETRY_ATTEMPTS = 2` vezes) antes de escalar
para reconexão completa.

**Cache de imagem Vulkan corrigido** (`vr_player_app_vulkan.cpp`) —
confirmado no logcat: `evicao de entrada antiga` + `importado AHardwareBuffer`
em TODO frame, ~10 ponteiros distintos circulando contra
`kVideoImageCacheLimit = 6`, e a evicção usava `unordered_map::begin()`
(bucket arbitrário do hash, não o mais antigo de verdade, apesar do
comentário dizer "LRU"). Limite subido pra 16 e LRU real implementado via
carimbo de uso (`VideoFrame::lastUsedFrame`).

**Estado**: `cargo test -p protocols -p media-logic` (69+33 testes),
`cargo clippy -p protocols -p media-logic -- -D warnings` e
`./scripts/test-network-protocols.sh` (18 testes, servidores reais) — todos
passando. Cross-compile completo verificado via `cargo ndk ... build
--release` (workspace inteiro) e via `./gradlew assembleDebug` nas duas
variantes gráficas (`-PvrplayerGraphicsApi=VULKAN` e `=GLES`).

### 7.4 Teste em hardware: prioridade de thread refutada, causa raiz real encontrada

Com o HUD novo instalado no Quest 3, o teste decisivo que faltava (§1) foi
finalmente possível: o MESMO arquivo, via SMB, tocou liso (`net=11.2` a
`12MB/s`, igual ao local) — **sem nenhuma mudança de prioridade de thread
envolvida nesse caminho**, já que SMB usa exatamente a mesma infraestrutura
de `PrefetchReader`. Isso refuta a hipótese de escalonamento de núcleo do
XR2 Gen 2 como causa do throughput baixo: se fosse isso, SMB teria o mesmo
problema.

O log com `blockFetchMs`/`blocksFetched` novos (§7.3) apontou a causa real
na hora: durante SFTP, `blockFetchMs` ficava em 5-10ms (deveria ser ~500ms
pra um bloco de 12MB, o que SMB de fato mostrava) e `blocksFetched` subia
~80/s — aritmética simples (`netMBs / blocksFetched`) dava **~30KB por
"bloco"**, não os 12MB pretendidos nem os ~252KB de
`SFTP_MAX_READ_CHUNK`.

Causa raiz: `SftpFileSource::try_read_range` (`sftp/mod.rs`) pedia cada
chunk em ate 252KB, mas quando o servidor devolvia MENOS do que pedido —
permitido pelo protocolo SFTP sem sinalizar EOF explícito, o próprio
comentário do código já citava essa possibilidade — o código tratava isso
como fim de dados e parava de remontar o bloco ali (`break 'batches`). O
servidor SFTP do NAS usado no teste aparentemente limita cada resposta de
`SSH_FXP_READ` a algo em torno de 30KB, bem abaixo do que o cliente pedia.
Toda troca de "bloco" pagava overhead completo de round-trip SSH/canal
mpsc/wake de thread por uma fração minúscula dos dados — isso, não CPU ou
prioridade, era o gargalo real desde antes desta sessão (herdado de antes
de `3d682c0`, só nunca medido com granularidade suficiente pra aparecer).

**Correção**: nova função `SftpFileSource::read_chunk_filling` — em vez de
uma única chamada `raw.read(handle, chunk_offset, chunk_len)`, faz um laço
que continua chamando `raw.read()` pro RESTANTE do chunk atual até
preenchê-lo por completo, e só para de verdade em EOF explícito
(`StatusCode::Eof`) ou erro real. Aplicada nas duas chamadas de
`raw.read()` dentro de `try_read_range` (lote inicial e retry de chunk).

**Validado em hardware real**: com o fix, `net=12MB/s` via SFTP — igual ao
SMB e ao local — e a taxa de frames do vídeo emparelhou com a reprodução
local. **A regressão 30fps→10fps está resolvida.**

Testes: `cargo test -p protocols -p media-logic` (69+33) e
`./scripts/test-network-protocols.sh` (18 testes, servidores reais,
incluindo verificação de sha256 do arquivo inteiro via SFTP — sem
corrupção introduzida pelo laço novo) — todos passando.

## 8. Arquivos tocados

```
rust/protocols/Cargo.toml                  (+ futures-util, + libc)
rust/protocols/src/lib.rs                  (+ pub mod chunking)
rust/protocols/src/chunking.rs             (novo — split_range + testes)
rust/protocols/src/prefetch.rs             (redesenho: thread de background + testes;
                                             sessao seguinte: PrefetchStats, prioridade de
                                             thread, sem prefetch especulativo pos-seek)
rust/protocols/src/smb/mod.rs              (read_range concorrente em lotes)
rust/protocols/src/sftp/mod.rs             (read_range concorrente em lotes + log de reconexao;
                                             sessao seguinte: retry por chunk antes de reconectar;
                                             sessao seguinte, causa raiz real: read_chunk_filling
                                             — nao trata mais leitura curta como EOF)
rust/protocols/src/http.rs                 (read_range concorrente em lotes; sessao
                                             seguinte: fix de lint collapsible_if pre-existente)
rust/protocols/src/ftp/mod.rs              (fix de short-read, sem paralelismo)
docker/network-tests/docker-compose.yml    (fix do mount :ro do ftp-test)
rust/core/src/decoder.rs                   (sessao seguinte: HwDecoder::metrics())
rust/core/src/texture.rs                   (sessao seguinte: remove log por frame)
rust/core/src/demuxer.rs                   (sessao seguinte: ReadPacketOutcome,
                                             network_stats, fix de caller em thumbnail.rs)
rust/core/src/thumbnail.rs                 (sessao seguinte: adapta ao novo read_packet)
rust/core/src/playback.rs                  (sessao seguinte: getters de instrumentacao)
rust/bridge/src/lib.rs                     (sessao seguinte: novos getters + fix do
                                             bug de wraparound em contencao de lock)
native/src/vr_player_app_vulkan.cpp        (sessao seguinte: HUD novo, LRU real do
                                             cache de imagem, limite subido pra 16)
native/src/vr_player_input_vulkan.h        (sessao seguinte: HUD novo)
native/src/vr_player_app.cpp               (sessao seguinte: HUD novo, paridade GLES)
rust/bridge/Cargo.toml                     (sessao seguinte: + log, pra AndroidLogger)
app/src/main/java/com/vrplayer/VRPresentation.kt
                                            (sessao seguinte, achado durante o teste: as
                                             abas URL/SMB/FTP/SFTP de "Rede" nao tinham
                                             ScrollView — formulario de cadastro de servidor
                                             ficava com o botao de salvar fora da area
                                             visivel/tocavel de 1024x768; corrigido com o
                                             mesmo padrao de ScrollView ja usado nas
                                             listagens de diretorio)
```

## 9. O que NÃO foi feito (fora do escopo desta sessão)

- Nenhuma meta de throughput hipotética (ex. "16K@100fps") virou teste real
  — descartada por instrução explícita por ser irrelevante ao problema.
- Paralelismo multi-conexão para FTP (ver 4.4 — decisão deliberada).
- Ajuste de `pool_max_idle_per_host`/tuning fino do `reqwest::blocking::Client`
  além do default (não se mostrou necessário para os testes feitos).
- Tornar o tamanho de bloco do `PrefetchReader`/`REMOTE_PREFETCH_BLOCK_SIZE`
  adaptativo — hoje continua fixo em 12MB para remoto (`core/src/demuxer.rs`).
  Pode virar um próximo passo se a validação em hardware mostrar que ainda
  não é suficiente.
- Cancelamento cooperativo real de uma leitura de rede já em voo (ver 7.3,
  fix do seek) — precisaria de uma segunda conexão ou de infraestrutura de
  cancelamento no nível do protocolo; não implementado.
- Retry por chunk (7.3) aplicado só a SFTP — SMB e HTTP não têm o mesmo
  padrão de "erro de um chunk derruba o lote inteiro" hoje, mas também não
  foram auditados a fundo para essa classe de problema nesta sessão.
- Ajuste fino de qual valor de `nice` é ideal para `vrplayer-prefetch-io`
  (`-10` é um chute inicial) ou pinning de afinidade de núcleo — decisão
  deliberada de manter simples e deixar a validação em hardware guiar o
  próximo passo, ver 7.3.
