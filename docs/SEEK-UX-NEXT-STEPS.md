# Próximos passos: UX de seek/scrub e feedback visual

> Plano de continuação de uma sessão que implementou feedback de play/pause/
> seek, indicador de loading, frame congelado no seek, reuso de conexão de
> rede entre seeks (SFTP/SMB/HTTPS) e uma primeira versão do preview de
> arrasto no seekbar. Este documento cobre o que ficou pendente, com o
> raciocínio já levantado, para retomar em outra sessão — não é trabalho
> teórico: os itens 1-3 nasceram de comportamento **observado em hardware
> real** (Quest 3, `192.168.0.246`, acervo majoritariamente VR180 8K), não
> de suposição.

## Contexto — o que já está resolvido

- Feedback de play/pause/±10s (flash central no painel de controles).
- Indicador de loading (`get_playback_is_loading`) e sinal de play/pause real
  (`get_playback_is_playing`) via polling ~10Hz, paridade GLES/Vulkan.
- `seek()` não desfaz mais o estado de pausa (bug real: todo seek despausava
  sozinho, porque `Generation::new()` sempre começa com `is_playing=true`).
- Frame congelado durante o carregamento pós-seek (não pisca mais pro vazio),
  paridade GLES/Vulkan — no Vulkan, a segurança depende de `activeVideoFrame`
  nunca ser candidato a evicção LRU enquanto está em tela (documentado no
  código, `vr_player_app_vulkan.cpp::UpdateVideoFrame`).
- `ConnectionCache` (SFTP, SMB, HTTPS) — reaproveita a conexão de rede entre
  seeks no mesmo arquivo em vez de reabrir do zero. FTP ficou de fora
  (motivo: ver seção 3).
- Timeout de leitura pra SFTP (`SFTP_READ_TIMEOUT`, 20s) — sem isso, uma
  leitura que o servidor nunca responde (canal continua vivo, keepalive não
  pega) travava `PlaybackController` inteiro, porque `seek()`/`stop()`
  precisam dar `join()` na thread presa antes de prosseguir. **Achado real**:
  reproduzido em hardware como "vídeo trava no frame congelado
  indefinidamente" — o log mostrou `"sem frame novo há 506ms"` seguido de
  silêncio total, sem nenhum erro reportado (a trava acontecia ANTES de
  qualquer erro existir pra reportar).
- Descoberta de infraestrutura: o build padrão deste projeto usa **Vulkan**,
  não GLES (`build.gradle.kts` sobrescreve o default do `CMakeLists.txt`
  silenciosamente) — `CLAUDE.md` está desatualizado nesse ponto. Todo
  trabalho de C++ desta sessão foi portado pros dois caminhos.
- Correção do thumbnail preto (retry em 5s/15s se o frame em 1s sair preto —
  comum em VR180, que costuma ter fade-in longo).

## 1. Preview de arrasto (scrub) para vídeos acima de 4K

**Estado atual**: `core::thumbnail::generate_strip()` recusa gerar trilha
acima de `MAX_STRIP_SOURCE_PIXELS` (4K = 3840×2160) — decidido em sessão
depois de um crash real (provável OOM) ao arrastar o tracker num SFTP 8K60,
com a geração de trilha rodando concorrente à reprodução real. Como o acervo
de teste é quase todo 8K, **isso deixa o preview indisponível pro caso de uso
real hoje**.

**Por que "downscale" não resolve**: o custo caro é o *decode* (reconstrução
do frame a partir do HEVC/AV1), não o tamanho de saída — a saída já é
minúscula (80×45px). Decoders de software não têm como decodificar HEVC/AV1
"em resolução menor" a partir do bitstream; a redução só acontece depois,
via `sws_scale`, que é exatamente o que o código já faz. Não há ganho de
memória possível só ajustando parâmetros de escala.

**Caminho real**: decode por **hardware** (MediaCodec) para a geração da
trilha, em vez de software puro. Motivo do custo ser tão menor: um decoder de
hardware é um ASIC dedicado com pipeline de memória próprio, não compete pela
RAM/CPU geral do jeito que `avcodec` por software compete — especialmente
relevante rodando concorrente com a reprodução real (que já usa hardware).

Esboço do trabalho (não iniciado):
1. Reaproveitar `HwDecoder` (`rust/core/src/decoder.rs`, já usado pela
   reprodução real) em vez do decode por software de `thumbnail.rs`.
2. Precisa de uma `Surface`/`AHardwareBuffer` de saída — hoje
   `core::thumbnail` não toca nada disso (é por isso que existe: "decode de
   hardware exige Surface/AHardwareBuffer/GL, peso desnecessário para um
   frame estático" — comentário original, válido pra 1 frame, não mais válido
   pra uma trilha de centenas).
3. Ler de volta os pixels da `Surface`/`AHardwareBuffer` pra RGBA (glReadPixels
   ou equivalente) depois de cada seek interno — mais complexo que o
   `sws_scale` atual, precisa de contexto GL ativo (ou Vulkan, dado o
   achado da seção "Contexto" acima).
4. Alternativa mais simples de avaliar primeiro: gerar a trilha **em
   background, em baixa prioridade, um frame de cada vez com pausas entre
   decodes** (em vez de sequencial sem pausa) — reduziria a chance de pico de
   memória coincidir com o momento de maior demanda da reprodução real, sem
   precisar migrar pra hardware. Mais simples, mas não elimina o risco, só
   reduz a probabilidade — vale medir em hardware antes de decidir se é
   suficiente.

**Decisão pendente**: qual dos dois caminhos (hardware decode "de verdade" vs.
mitigação por espaçamento) vale o esforço primeiro. Recomendo medir a opção 4
(mais barata de implementar) antes de investir na opção 1-3.

**Atualização**: opção 4 implementada — `generate_strip()` agora dá uma pausa
de 100ms entre cada frame decodificado (`rust/core/src/thumbnail.rs`, dentro
do loop principal), reduzindo a chance de o pico de memória da trilha
coincidir com o pico da reprodução real. **Isso não elimina o risco, só
reduz a probabilidade** — não foi medido em hardware contra o acervo 8K real,
e o gate `MAX_STRIP_SOURCE_PIXELS` (4K) continua ativo. A decisão sobre
migrar pra hardware decode (passos 1-3 acima) segue em aberto até essa
medição acontecer.

## 2. O que aparece durante o arrasto (UX do preview)

Ainda não decidido/validado em hardware:
- **Posição na tela**: hoje o preview (quando existe) fica um retângulo fixo
  acima do seekbar, dentro do painel de controles (`VRControlsPresentation`).
  Fica sujeito ao mesmo problema da seção 3 abaixo — se o usuário está
  olhando pro vídeo, não pro painel, não vê o preview.
- **Frequência da trilha**: hoje 1 frame a cada 15s (`SCRUB_INTERVAL_SECONDS`
  em `NetworkThumbnailGenerator.kt`) — não validado se é granularidade fina o
  suficiente pra vídeos longos (filmes de 2h+) ou se deveria escalar com a
  duração total.
- **Local (`MediaMetadataRetriever`)**: implementado como decode "ao vivo"
  durante o arrasto (throttled), nunca testado em hardware nesta sessão —
  todo o tempo de teste real foi em conteúdo SFTP.
- **Fallback quando a trilha ainda não terminou de gerar** (primeiro arrasto
  num vídeo novo): hoje não mostra nada. Vale considerar um placeholder
  (spinner pequeno no lugar do preview) em vez de silêncio.

## 3. Feedback visual (play/pause/±10s) sobre o vídeo, não só no painel

**Pedido explícito do usuário — implementado.** Estado (`FEEDBACK_EVENT`,
sequência + tipo num único `AtomicU64`) em `rust/bridge/src/lib.rs`, exposto
via `get_playback_feedback_event()`, análogo ao `LOADING_COUNT` já existente.
Disparado a partir do mesmo ponto de chamada compartilhado por todos os
caminhos de play/pause (painel, atalho fora do painel, botão do controle) e
de seek (±10s, arrasto do seekbar) — `toggle_play_pause()` e
`seek_video_playback()`. Renderizado como geometria vetorial simples
(triângulo/barras/chevron, sem atlas de ícones/textura nova) sobreposto à
quad de vídeo, com fade seguindo o mesmo padrão de `uiTargetAlpha`/
`controlsTargetAlpha` (`kUiFadeDuration = 0.35f`, hold de 0.6s). Paridade
GLES+Vulkan confirmada via `assembleDebug` nas duas variantes
(`native/src/vr_player_feedback_overlay.h`, `vr_player_app.cpp`,
`vr_player_app_vulkan.cpp`, `vr_player_input_vulkan.h`).

**Decisão de escopo tomada**: como o único ponto de disparo compartilhado
pra seek é a posição absoluta (`seek_video_playback`), o arrasto do seekbar
também dispara o chevron, não só os botões ±10s — separar isso exigiria um
novo entry point Kotlin→JNI, fora do escopo desta rodada. Mantido assim
deliberadamente; ajustar se incomodar em uso real.

**Não validado em hardware**: timing do fade, legibilidade/contraste do
branco sobre o vídeo, ancoragem em modos 360/180 (onde não há quad de vídeo
plana — a âncora usa a posição "tela plana" a ~2m), e se disparar em todo
arrasto do seekbar é agradável ou ruidoso na prática.

## 4. Reuso de conexão para FTP

Deixado de fora do `ConnectionCache` (item 4 da sessão original) porque
`FtpFileSource` não tinha nenhuma resiliência a conexão parada (sem
retry/reconnect em erro de leitura, diferente de SFTP/SMB/HTTPS).

**Passo 1 implementado**: `FtpFileSource::read_range` agora reconecta e
tenta de novo uma vez em erro de leitura, mesmo padrão de
`SftpFileSource::read_range` (`rust/protocols/src/ftp/mod.rs`, novo método
`reconnect()` reaproveitando `connect_and_login()`). Sem timeout por request
ainda (passo 2 abaixo) e sem teste de integração dedicado (não há fixture de
fault-injection disponível em `docker/network-tests/` pra FTP nem SFTP hoje).

Restante, ainda pendente:
2. Timeout por request também (mesmo raciocínio da seção 5 — controle de FTP
   costuma ser ainda mais sensível a travar em `RETR` sem resposta).
3. Só depois disso faz sentido adicionar `ConnectionCache::Ftp(...)`.

## 5. Timeout de leitura para SMB

**Atualizado após auditoria**: HTTPS já está coberto —
`rust/protocols/src/http.rs:56-61` (`base_client()`) configura
`.timeout(Duration::from_secs(10))` no `reqwest::blocking::Client`, o que
cobre justamente o caso "servidor aceita mas nunca responde". Não precisa de
trabalho adicional aqui.

**Implementado**: `SMB_READ_TIMEOUT` (20s, mesmo valor do SFTP) em
`rust/protocols/src/smb/mod.rs`, envolvendo o `block_on(join_all(...))`
existente em `SmbFileSource::read_range` com `tokio::time::timeout` — igual
ao mecanismo do SFTP (que também usa `tokio::time::timeout` sobre
`block_on`, não thread+canal como se especulava originalmente). Timeout de
**conexão** (`client_config()`, 8s) mantido intocado, é uma configuração
diferente e já correta. **Sem retry/reconnect no timeout** (diferente do
SFTP) — só destrava o `join()`, não tenta de novo; ver item 4 se quiser esse
comportamento aqui também.

## 6. Validação pendente em hardware

Nada da sessão anterior (loading spinner, `updateMediaState`, thumbnail
strip para vídeos ≤4K, reuso de conexão SMB/HTTPS) foi de fato observado
funcionando em hardware ainda — só o timeout de SFTP e o frame congelado
foram exercitados por reprodução real do bug. Vale um passe de teste
dedicado cobrindo cada item da seção "Contexto" acima antes de considerar
a sessão anterior encerrada.

**Itens desta rodada também pendentes de validação em hardware** (todos só
compilados/build-verificados, nunca rodados no Quest 3): pacing de 100ms no
scrub >4K (item 1), overlay de feedback sobre a quad de vídeo em ambos os
modos GLES/Vulkan e nos modos 360/180 (item 3), reconnect de FTP em conexão
real derrubada (item 4), timeout de leitura SMB contra um travamento real
(item 5).
