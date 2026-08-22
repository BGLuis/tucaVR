//! Cache de leitura em blocos grandes para fontes de dados remotas (SMB,
//! SFTP, HTTP, FTP) — T6.3/T7.2, redesenhado nesta sessao (investigacao de
//! degradacao de throughput em streaming SFTP 8K60, ver
//! docs/DEBUGGING.md/PHASE-0.1-MVP.md secao 6) para read-ahead REAL em
//! background.
//!
//! O `AVIOContext` do FFmpeg (via `StreamIo` do `ffmpeg-next`) le em
//! pedacos de ~32KB por vez, e o Demuxer faz MUITOS seeks (doc, secoes 6/7:
//! MKV com cues no final do arquivo). Cada leitura/seek numa fonte "crua"
//! (SMB, SFTP, HTTP ou FTP) vira um round-trip de rede. Este modulo amortiza
//! isso de duas formas combinadas:
//!
//! 1. Blocos grandes (padrao 4MB local, 12MB no Demuxer para 8K/60 — ver
//!    `core/src/demuxer.rs`): ao ocorrer um cache-miss, le um bloco inteiro
//!    de uma vez, e serve leituras subsequentes dentro dele direto da
//!    memoria.
//! 2. Read-ahead assincrono de verdade: uma thread de background dedicada
//!    possui a `RangeSource` e busca o PROXIMO bloco sequencial enquanto o
//!    bloco atual ainda esta sendo consumido (decodificado) pela thread
//!    principal. Para o padrao de acesso comum durante playback (leitura
//!    sequencial, fora dos seeks pontuais de cues) isso sobrepoe o RTT de
//!    rede ao tempo de consumo em vez de soma-los — a versao anterior deste
//!    modulo era um cache "pull" puro (buscava o bloco seguinte so quando o
//!    atual acabava), documentado explicitamente como NAO fazendo isso; essa
//!    limitacao era o principal suspeito da degradacao de throughput
//!    observada em SFTP sob 8K60 e foi corrigida aqui.
//!
//! Um seek para fora do bloco atual E do bloco em pre-busca invalida o
//! prefetch em voo (o resultado e apenas descartado quando chega — nao ha
//! cancelamento cooperativo real de uma leitura de rede ja em andamento, so
//! do PROXIMO passo do worker). Ver `PrefetchReader::ensure_cache`.
//!
//! O primeiro bloco pos-seek (miss nao-sequencial, sincrono) usa
//! `seek_block_size` em vez do `block_size` cheio — blocos seguintes
//! (prefetch especulativo, leitura sequencial confirmada) voltam ao cheio.
//!
//! Refinamento desta sessao (investigacao da regressao 30fps->10fps
//! documentada em docs/NETWORK-IO-PERFORMANCE.md): apos um miss
//! NAO-sequencial (seek de verdade), `ensure_cache` NAO dispara mais o
//! prefetch especulativo do proximo bloco imediatamente — so no acesso
//! SEGUINTE, se ele confirmar que a leitura continua dentro do bloco que o
//! seek acabou de instalar. Um seek isolado seguido de leitura sequencial
//! ainda ganha read-ahead (soo adiado por um passo); uma sequencia de seeks
//! encadeados (probe do container, cues no fim do arquivo, ou o usuario
//! arrastando a barra) para de pagar por um prefetch que o proximo seek so
//! ia descartar de qualquer forma.

use std::io::{self, Read, Seek, SeekFrom};
use std::marker::PhantomData;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

/// Flag global de estresse térmico (T14.1/T14.2 `PAUSE_PREFETCH`).
/// Quando ativada (nível MODERATE ou superior), pausa a pre-busca especulativa
/// em segundo plano para aliviar CPU / tráfego de rede concorrente.
static THERMAL_THROTTLE_ACTIVE: AtomicBool = AtomicBool::new(false);

pub fn set_thermal_throttle_active(active: bool) {
    THERMAL_THROTTLE_ACTIVE.store(active, Ordering::Relaxed);
}

pub fn is_thermal_throttle_active() -> bool {
    THERMAL_THROTTLE_ACTIVE.load(Ordering::Relaxed)
}

/// Contadores de instrumentacao do `PrefetchReader` (docs/DEBUGGING.md),
/// expostos via `PrefetchReader::stats()` — quem pega o `Arc` pode fazer
/// polling de fora sem tocar os canais de comando/resultado. So atualizados
/// nos limites de bloco (dentro da thread de background), entao nao custam
/// nada por `read()`.
#[derive(Default)]
pub struct PrefetchStats {
    /// Bytes recebidos da rede, soma cumulativa de todo bloco buscado —
    /// inclusive blocos descartados por seek (reflete trafego real gerado,
    /// nao bytes uteis entregues ao chamador).
    pub bytes_fetched: AtomicU64,
    /// Duracao do ULTIMO fetch de bloco completo, em microssegundos.
    pub last_fetch_us: AtomicU64,
    /// Quantos blocos foram buscados no total (sucesso ou erro).
    pub blocks_fetched: AtomicU64,
    /// Quantos prefetches em voo foram descartados por um seek real (ver
    /// ensure_cache) — alto = padrao de acesso pouco sequencial.
    pub blocks_discarded: AtomicU64,
}

/// Melhor esforco pra elevar a prioridade da thread de I/O de rede acima do
/// nice padrao (0). Achado desta sessao: nenhuma thread deste workspace
/// definia prioridade/afinidade explicita, e no XR2 Gen 2 (nucleos
/// performance + eficiencia) uma thread nova sem prioridade pode ser
/// escalonada num nucleo de eficiencia — ruim para trabalho CPU-bound (cripto
/// SSH/TLS) que agora roda CONCORRENTEMENTE com o decode, em vez de
/// serializado como antes do read-ahead em background (ver
/// docs/NETWORK-IO-PERFORMANCE.md). Falha silenciosamente (processo sem
/// permissao pra baixar o nice) — so loga o resultado pra confirmar em
/// hardware se a chamada realmente pegou.
fn raise_io_thread_priority() {
    const TARGET_NICE: i32 = -10;
    unsafe {
        let before = libc::getpriority(libc::PRIO_PROCESS, 0);
        libc::setpriority(libc::PRIO_PROCESS, 0, TARGET_NICE);
        let after = libc::getpriority(libc::PRIO_PROCESS, 0);
        // sched_getcpu(): so um snapshot instantaneo (a thread pode migrar
        // depois), mas o suficiente pra confirmar se caiu num nucleo de
        // performance ou eficiencia do XR2 Gen 2 logo apos o setpriority.
        let cpu = libc::sched_getcpu();
        log::info!("vrplayer-prefetch-io: nice {before} -> {after} (pedido {TARGET_NICE}), cpu={cpu}");
    }
}

/// Fonte de bytes por offset absoluto — sem cursor proprio, sem estado de
/// "posicao atual". E o unico contrato que SMB, SFTP, HTTP(S) e FTP
/// precisam implementar; a semantica de `Read`/`Seek` (posicao corrente,
/// EOF, etc.) fica inteira no `PrefetchReader`.
#[allow(clippy::len_without_is_empty)] // "len" aqui e tamanho do arquivo remoto, nao de uma colecao em memoria
pub trait RangeSource: Send {
    /// Le ate `buf.len()` bytes comecando em `offset`. Retorna a quantidade
    /// de bytes efetivamente lidos (pode ser menor que `buf.len()` apenas no
    /// fim do arquivo — 0 significa EOF). Implementacoes devem preencher
    /// `buf` por completo sempre que houver dados suficientes disponiveis —
    /// um "short read" silencioso aqui (retornar menos do que existe, sem
    /// ser EOF) faz o `PrefetchReader` achar que o bloco acabou antes da
    /// hora.
    fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize>;

    /// Tamanho total em bytes, se conhecido de antemao (SMB/SFTP: retornado
    /// no open; HTTP: Content-Length do probe; FTP: `SIZE`).
    fn len(&self) -> Option<u64>;
}

/// `RangeSource` compartilhavel entre varios `PrefetchReader` sucessivos
/// (ex.: seeks) sem reabrir a conexao — ver `core::demuxer::ConnectionCache`.
/// So serializa acesso via mutex; quem decide reconectar em erro continua
/// sendo a implementacao de `S::read_range` (ex.: `SftpFileSource`).
pub struct SharedRangeSource<S>(Arc<Mutex<S>>);

impl<S> SharedRangeSource<S> {
    pub fn new(inner: Arc<Mutex<S>>) -> Self {
        Self(inner)
    }
}

impl<S: RangeSource> RangeSource for SharedRangeSource<S> {
    fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        self.0
            .lock()
            .map_err(|_| io::Error::other("SharedRangeSource: mutex envenenado"))?
            .read_range(offset, buf)
    }

    fn len(&self) -> Option<u64> {
        self.0.lock().ok()?.len()
    }
}

/// 4MB — dentro da faixa 2-8MB que o doc recomenda para o buffer de
/// prefetch (secao 6, "Cuidados e Armadilhas"). O Demuxer usa um valor maior
/// (12MB) para video remoto — ver `core/src/demuxer.rs`.
const DEFAULT_BLOCK_SIZE: usize = 4 * 1024 * 1024;

// 1MB: pequeno o bastante pro primeiro round-trip pos-seek nao travar perceptivelmente.
const DEFAULT_SEEK_BLOCK_SIZE: usize = 1024 * 1024;

struct Block {
    start: u64,
    data: Vec<u8>,
    len: usize,
}

enum Command {
    Fetch(u64, usize),
    Shutdown,
}

type FetchResult = io::Result<Block>;

fn worker_gone_err() -> io::Error {
    log::warn!("PrefetchReader: thread de leitura em background encerrada inesperadamente");
    io::Error::other("PrefetchReader: thread de leitura em background encerrada inesperadamente")
}

pub struct PrefetchReader<S: RangeSource> {
    pos: u64,
    total_len: Option<u64>,
    cache: Vec<u8>,
    cache_start: u64,
    cache_len: usize,
    cmd_tx: Sender<Command>,
    result_rx: Receiver<FetchResult>,
    /// Offset do bloco cuja busca em background foi disparada e ainda nao
    /// foi consumida — `None` quando nao ha nenhum prefetch em voo (ex.:
    /// acabou de instalar um bloco final/vazio, ver `ensure_cache`).
    pending_start: Option<u64>,
    worker: Option<JoinHandle<()>>,
    stats: Arc<PrefetchStats>,
    block_size: usize,
    seek_block_size: usize,
    /// Quantos blocos especulativos seguidos ja foram pedidos desde o
    /// ultimo miss nao-sequencial — ver `next_ramp_size`.
    sequential_streak: u32,
    thermal_throttled: Option<bool>,
    _source: PhantomData<S>,
}

impl<S: RangeSource + 'static> PrefetchReader<S> {
    pub fn new(source: S) -> Self {
        Self::with_block_size(source, DEFAULT_BLOCK_SIZE)
    }

    pub fn with_block_size(source: S, block_size: usize) -> Self {
        let block_size = block_size.max(64 * 1024);
        Self::with_block_sizes(source, block_size, block_size.min(DEFAULT_SEEK_BLOCK_SIZE))
    }

    /// Como `with_block_size`, mas com o tamanho do bloco pos-seek explicito.
    pub fn with_block_sizes(mut source: S, block_size: usize, seek_block_size: usize) -> Self {
        let block_size = block_size.max(64 * 1024);
        let seek_block_size = seek_block_size.max(64 * 1024).min(block_size);
        let total_len = source.len();

        let (cmd_tx, cmd_rx) = mpsc::channel::<Command>();
        let (result_tx, result_rx) = mpsc::channel::<FetchResult>();
        let stats = Arc::new(PrefetchStats::default());
        let worker_stats = stats.clone();

        let worker = std::thread::Builder::new()
            .name("vrplayer-prefetch-io".to_string())
            .spawn(move || {
                raise_io_thread_priority();
                for cmd in cmd_rx {
                    match cmd {
                        Command::Fetch(offset, size) => {
                            // Vec novo por bloco (nao reaproveita o buffer da
                            // thread principal): os dados precisam atravessar
                            // o canal por valor. Blocos sao grandes mas raros
                            // (segundos de video cada), entao a alocacao nao
                            // e o gargalo aqui.
                            log::info!("vrplayer-prefetch-io: buscando bloco offset={offset} ({size} bytes)");
                            let mut data = vec![0u8; size];
                            let fetch_start = std::time::Instant::now();
                            let result = source.read_range(offset, &mut data).map(|len| Block { start: offset, data, len });
                            if let Ok(ref block) = result {
                                worker_stats.bytes_fetched.fetch_add(block.len as u64, Ordering::Relaxed);
                                worker_stats.blocks_fetched.fetch_add(1, Ordering::Relaxed);
                                worker_stats.last_fetch_us.store(fetch_start.elapsed().as_micros() as u64, Ordering::Relaxed);
                            } else if let Err(ref e) = result {
                                log::warn!("vrplayer-prefetch-io: falha ao buscar bloco offset={offset}: {e}");
                            }
                            if result_tx.send(result).is_err() {
                                break;
                            }
                        }
                        Command::Shutdown => break,
                    }
                }
                // `source` (conexao SMB/SFTP/HTTP/FTP) e fechada aqui via Drop.
            })
            .expect("falha ao criar thread de prefetch em background");

        let mut this = Self {
            pos: 0,
            total_len,
            cache: Vec::new(),
            cache_start: 0,
            cache_len: 0,
            cmd_tx,
            result_rx,
            pending_start: None,
            worker: Some(worker),
            stats,
            block_size,
            seek_block_size,
            sequential_streak: 0,
            thermal_throttled: None,
            _source: PhantomData,
        };
        // Dispara a busca do primeiro bloco ja na construcao: o primeiro
        // `read()` (quase sempre offset 0) encontra o prefetch em andamento
        // em vez de comecar do zero. Tamanho pequeno (nao block_size): abrir
        // um arquivo novo e tao "cold start" quanto um seek — probe do
        // demuxer nao deveria pagar o mesmo round-trip grande que a leitura
        // sequencial em regime permanente.
        this.kick_prefetch_sized(0, seek_block_size);
        this
    }

    /// Permite forçar localmente o estado de thermal throttle neste reader
    /// (útil para testes unitários isolados de concorrência).
    pub fn set_local_thermal_throttle(&mut self, active: bool) {
        self.thermal_throttled = Some(active);
    }

    fn is_throttled(&self) -> bool {
        self.thermal_throttled.unwrap_or_else(is_thermal_throttle_active)
    }

    fn cache_hit(&self, pos: u64) -> bool {
        self.cache_len > 0 && pos >= self.cache_start && pos < self.cache_start + self.cache_len as u64
    }

    /// Clone do `Arc` de instrumentacao — pega isto ANTES de entregar o
    /// `PrefetchReader` para o `StreamIo` do FFmpeg (que o engole por
    /// dentro de um `Box<dyn Read+Seek+Send>` opaco); ver
    /// `core::demuxer::Demuxer::network_stats`.
    pub fn stats(&self) -> Arc<PrefetchStats> {
        self.stats.clone()
    }

    /// Dispara em background a busca do bloco iniciando em `offset`, se
    /// ainda fizer sentido (dentro do tamanho conhecido do arquivo). Nao
    /// bloqueia — o resultado chega por `result_rx` numa chamada futura.
    /// Tamanho em rampa (ver `next_ramp_size`): o primeiro bloco especulativo
    /// apos um seek nao pula direto pro `block_size` cheio — a leitura
    /// sequencial confirmada uma vez ja e um sinal razoavel, mas ainda nao
    /// forte o bastante pra pagar o bloco inteiro se o acesso for so um
    /// pulo curto (ex.: keyframe seguinte de um GOP menor que o esperado).
    fn kick_prefetch(&mut self, offset: u64) {
        if self.is_throttled() {
            // T14.1/T14.2: Em estresse térmico, suspende read-ahead especulativo
            return;
        }
        let size = self.next_ramp_size();
        self.kick_prefetch_sized(offset, size);
    }

    fn next_ramp_size(&mut self) -> usize {
        let size = if self.sequential_streak == 0 {
            (self.seek_block_size * 4).min(self.block_size)
        } else {
            self.block_size
        };
        self.sequential_streak = self.sequential_streak.saturating_add(1);
        size
    }

    fn kick_prefetch_sized(&mut self, offset: u64, size: usize) {
        if let Some(total) = self.total_len
            && offset >= total
        {
            return;
        }
        if self.cmd_tx.send(Command::Fetch(offset, size)).is_ok() {
            self.pending_start = Some(offset);
        }
    }

    fn install(&mut self, block: Block) {
        self.cache_start = block.start;
        self.cache_len = block.len;
        self.cache = block.data;
    }

    /// Garante que o bloco contendo `pos` esta em `self.cache`, buscando-o
    /// (sincronamente, se necessario) ou reaproveitando um prefetch em
    /// background ja em voo.
    fn ensure_cache(&mut self, pos: u64) -> io::Result<()> {
        if self.cache_hit(pos) {
            // Ja dentro do bloco corrente. Se ele veio de um miss
            // NAO-sequencial (seek) sem prefetch especulativo ainda em voo,
            // este e o primeiro acesso que confirma que a leitura continua
            // dentro dele — so agora vale disparar o proximo bloco (ver doc
            // do modulo sobre por que isso nao acontece direto no install).
            if self.pending_start.is_none() && self.cache_len > 0 {
                self.kick_prefetch(self.cache_start + self.cache_len as u64);
            }
            return Ok(());
        }

        if self.pending_start == Some(pos) {
            // Caminho feliz: leitura sequencial alcancou exatamente o bloco
            // que o prefetch anterior ja comecou a buscar.
            let result = self.result_rx.recv().map_err(|_| worker_gone_err())?;
            self.pending_start = None;
            self.install(result?);
            if self.cache_len > 0 {
                // Ainda sequencial — encadeia o proximo prefetch normalmente.
                self.kick_prefetch(self.cache_start + self.cache_len as u64);
            }
        } else {
            // Cache-miss que nao bate com o prefetch em andamento — ou e a
            // primeiríssima leitura, ou um seek de verdade tornou o
            // prefetch em voo obsoleto. O worker processa um Command por
            // vez; se havia um pedido pendente, drena a resposta (descartada)
            // antes de pedir o bloco certo, para nao deixar uma resposta
            // orfa no canal.
            if self.pending_start.take().is_some() {
                self.stats.blocks_discarded.fetch_add(1, Ordering::Relaxed);
                let _ = self.result_rx.recv();
            }
            self.sequential_streak = 0;
            self.cmd_tx.send(Command::Fetch(pos, self.seek_block_size)).map_err(|_| worker_gone_err())?;
            let result = self.result_rx.recv().map_err(|_| worker_gone_err())?;
            self.install(result?);
            // Deliberadamente NAO kicka o proximo bloco aqui — ver doc do
            // modulo e o comentario no ramo cache_hit acima.
        }
        Ok(())
    }
}

impl<S: RangeSource + 'static> Read for PrefetchReader<S> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        if let Some(total) = self.total_len
            && self.pos >= total
        {
            return Ok(0); // EOF
        }
        self.ensure_cache(self.pos)?;
        if self.cache_len == 0 {
            return Ok(0); // fonte esgotada de verdade
        }
        let offset_in_cache = (self.pos - self.cache_start) as usize;
        let available = self.cache_len - offset_in_cache;
        let n = available.min(buf.len());
        buf[..n].copy_from_slice(&self.cache[offset_in_cache..offset_in_cache + n]);
        self.pos += n as u64;
        Ok(n)
    }
}

impl<S: RangeSource + 'static> Seek for PrefetchReader<S> {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        let new_pos: i64 = match pos {
            SeekFrom::Start(p) => p as i64,
            SeekFrom::Current(d) => self.pos as i64 + d,
            SeekFrom::End(d) => {
                let len = self.total_len.ok_or_else(|| {
                    io::Error::new(
                        io::ErrorKind::Unsupported,
                        "tamanho desconhecido: SeekFrom::End nao suportado nesta fonte",
                    )
                })?;
                len as i64 + d
            }
        };
        if new_pos < 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "seek para posicao negativa"));
        }
        self.pos = new_pos as u64;
        Ok(self.pos)
    }
}

impl<S: RangeSource> Drop for PrefetchReader<S> {
    fn drop(&mut self) {
        // Se o worker ja caiu sozinho (canal fechado), nao ha nada a
        // reportar — so tenta dar join mesmo assim.
        let _ = self.cmd_tx.send(Command::Shutdown);
        if let Some(handle) = self.worker.take() {
            let _ = handle.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::{mpsc as std_mpsc, Arc};
    use std::time::{Duration, Instant};

    /// Fonte fake em memoria com atraso artificial configuravel por chamada
    /// e um contador de leituras compartilhado. A fonte real e movida para
    /// dentro da thread de background do `PrefetchReader`, entao os testes
    /// precisam de um handle externo (`Arc<AtomicUsize>`) para continuar
    /// observando quantas leituras aconteceram depois que o reader existe.
    struct DelayedMemSource {
        data: Vec<u8>,
        delay: Duration,
        reads: Arc<AtomicUsize>,
        fail_at: Option<u64>,
    }

    impl RangeSource for DelayedMemSource {
        fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
            self.reads.fetch_add(1, Ordering::SeqCst);
            if Some(offset) == self.fail_at {
                return Err(io::Error::other("falha simulada de rede"));
            }
            if !self.delay.is_zero() {
                std::thread::sleep(self.delay);
            }
            let offset = offset as usize;
            if offset >= self.data.len() {
                return Ok(0);
            }
            let n = (self.data.len() - offset).min(buf.len());
            buf[..n].copy_from_slice(&self.data[offset..offset + n]);
            Ok(n)
        }

        fn len(&self) -> Option<u64> {
            Some(self.data.len() as u64)
        }
    }

    fn instant_source(data: Vec<u8>, fail_at: Option<u64>) -> (DelayedMemSource, Arc<AtomicUsize>) {
        let reads = Arc::new(AtomicUsize::new(0));
        (DelayedMemSource { data, delay: Duration::ZERO, reads: reads.clone(), fail_at }, reads)
    }

    #[test]
    fn sequential_reads_hit_one_block_per_prefetch() {
        let data: Vec<u8> = (0..255u8).cycle().take(10_000).collect();
        let (source, reads) = instant_source(data.clone(), None);
        let mut reader = PrefetchReader::with_block_size(source, 4096);

        let mut out = vec![0u8; data.len()];
        let mut total = 0;
        while total < out.len() {
            let n = reader.read(&mut out[total..]).unwrap();
            if n == 0 {
                break;
            }
            total += n;
        }
        assert_eq!(out, data);
        // 10_000 bytes / 4096-byte blocks = 3 buscas (0, 4096, 8192), bem
        // menos que 10_000 reads individuais.
        assert!(reads.load(Ordering::SeqCst) <= 4, "leituras de rede: {}", reads.load(Ordering::SeqCst));
    }

    #[test]
    fn seek_and_read() {
        let data: Vec<u8> = (0..255u8).cycle().take(10_000).collect();
        let (source, _reads) = instant_source(data.clone(), None);
        let mut reader = PrefetchReader::with_block_size(source, 4096);

        reader.seek(SeekFrom::Start(9000)).unwrap();
        let mut out = [0u8; 500];
        let n = reader.read(&mut out).unwrap();
        assert_eq!(n, 500);
        assert_eq!(&out[..], &data[9000..9500]);
    }

    /// Prova a razao de existir do redesenho: com read-ahead em background,
    /// o tempo total para ler N blocos com um consumidor lento fica muito
    /// abaixo de N * (delay_de_rede + tempo_de_consumo) — o delay do
    /// PROXIMO bloco e pago em paralelo enquanto o bloco ATUAL e consumido,
    /// em vez de em serie.
    #[test]
    fn background_prefetch_overlaps_network_delay_with_consumption() {
        let block = 64 * 1024;
        let n_blocks = 4;
        let delay = Duration::from_millis(60);
        let consume_delay = Duration::from_millis(60);
        let data: Vec<u8> = (0..255u8).cycle().take(block * n_blocks).collect();
        let reads = Arc::new(AtomicUsize::new(0));
        let source = DelayedMemSource { data: data.clone(), delay, reads: reads.clone(), fail_at: None };
        let mut reader = PrefetchReader::with_block_size(source, block);

        let mut out = vec![0u8; data.len()];
        let start = Instant::now();
        let mut done = 0;
        while done < out.len() {
            let end = (done + block).min(out.len());
            let n = reader.read(&mut out[done..end]).unwrap();
            assert!(n > 0);
            done += n;
            std::thread::sleep(consume_delay); // simula o decoder consumindo o bloco
        }
        let elapsed = start.elapsed();

        assert_eq!(out, data);
        let without_overlap = (delay + consume_delay) * n_blocks as u32;
        assert!(
            elapsed < without_overlap - delay,
            "prefetch nao sobrepos rede e consumo: elapsed={elapsed:?} sem-overlap-estimado={without_overlap:?}"
        );
    }

    /// Um seek para longe enquanto um prefetch sequencial ainda esta em voo
    /// nao pode corromper a proxima leitura nem travar — o resultado obsoleto
    /// e apenas descartado (ver `ensure_cache`).
    #[test]
    fn seek_during_pending_prefetch_returns_correct_data() {
        let block = 4096;
        let data: Vec<u8> = (0..255u8).cycle().take(block * 5).collect();
        let source = DelayedMemSource { data: data.clone(), delay: Duration::from_millis(20), reads: Arc::new(AtomicUsize::new(0)), fail_at: None };
        let mut reader = PrefetchReader::with_block_size(source, block);

        // Consome o bloco 0 — dispara o prefetch do bloco 1 em background.
        let mut first = vec![0u8; block];
        reader.read_exact(&mut first).unwrap();
        assert_eq!(first, data[..block]);

        // Sem esperar o prefetch do bloco 1, pula bem mais longe: o
        // prefetch em voo fica obsoleto.
        let jump_to = (block * 4) as u64;
        reader.seek(SeekFrom::Start(jump_to)).unwrap();
        let mut tail = vec![0u8; block];
        reader.read_exact(&mut tail).unwrap();
        assert_eq!(tail, data[jump_to as usize..jump_to as usize + block]);

        // E volta para o meio do arquivo normalmente depois.
        reader.seek(SeekFrom::Start(block as u64)).unwrap();
        let mut middle = vec![0u8; block];
        reader.read_exact(&mut middle).unwrap();
        assert_eq!(middle, data[block..block * 2]);
    }

    /// Padrao misto de acesso sequencial e aleatorio comparado byte a byte
    /// contra o buffer de referencia — pega qualquer corrupcao sutil de
    /// offset introduzida pelo estado de prefetch (`pending_start`,
    /// instalacao de bloco) sob varios seeks em sequencia.
    #[test]
    fn mixed_sequential_and_random_access_matches_reference() {
        let block = 1024;
        let data: Vec<u8> = (0..255u8).cycle().take(block * 10).collect();
        let (source, _reads) = instant_source(data.clone(), None);
        let mut reader = PrefetchReader::with_block_size(source, block);

        let plan: &[(u64, usize)] = &[(0, 500), (500, 1200), (9500, 300), (0, 100), (3000, 2500), (10240 - 50, 50)];
        for &(offset, len) in plan {
            reader.seek(SeekFrom::Start(offset)).unwrap();
            let mut buf = vec![0u8; len];
            reader.read_exact(&mut buf).unwrap();
            assert_eq!(buf, data[offset as usize..offset as usize + len], "mismatch lendo offset={offset} len={len}");
        }
    }

    /// `Drop` deve mandar `Shutdown` e dar `join` na thread de background
    /// sincronamente — sem isso a conexao de rede (dentro do worker) so
    /// fecharia quando o SO decidisse, e um teste seguinte poderia rodar com
    /// a thread anterior ainda viva. Roda o `drop` numa thread separada com
    /// timeout para o proprio teste nao travar a suite inteira se houver
    /// regressao de deadlock.
    #[test]
    fn drop_joins_background_thread_without_hanging() {
        let block = 4096;
        let data: Vec<u8> = (0..255u8).cycle().take(block * 3).collect();
        let (source, _reads) = instant_source(data, None);
        let mut reader = PrefetchReader::with_block_size(source, block);

        let mut buf = vec![0u8; block];
        reader.read_exact(&mut buf).unwrap(); // deixa um prefetch do proximo bloco em voo

        let (done_tx, done_rx) = std_mpsc::channel();
        std::thread::spawn(move || {
            drop(reader);
            let _ = done_tx.send(());
        });
        done_rx.recv_timeout(Duration::from_secs(2)).expect("drop do PrefetchReader nao encerrou a thread de background a tempo");
    }

    /// Erro da fonte (falha de rede simulada) deve propagar como `Err` para
    /// quem chamou `read`, nao travar nem ser engolido silenciosamente.
    #[test]
    fn source_error_propagates_to_read_caller() {
        let block = 4096;
        let data: Vec<u8> = (0..255u8).cycle().take(block * 2).collect();
        let (source, _reads) = instant_source(data, Some(0));
        let mut reader = PrefetchReader::with_block_size(source, block);

        let mut buf = vec![0u8; block];
        let result = reader.read(&mut buf);
        assert!(result.is_err(), "erro da fonte deveria propagar, obteve: {result:?}");
    }

    /// Fonte fake que registra o TAMANHO de cada `read_range` pedido, para
    /// provar a rampa (T-seek-ux): o primeiro fetch pos-seek deve ser
    /// pequeno, os seguintes (leitura sequencial confirmada) devem voltar ao
    /// tamanho cheio.
    struct SizeTrackingSource {
        data: Vec<u8>,
        requested_sizes: Arc<Mutex<Vec<usize>>>,
    }

    impl RangeSource for SizeTrackingSource {
        fn read_range(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
            self.requested_sizes.lock().unwrap().push(buf.len());
            let offset = offset as usize;
            if offset >= self.data.len() {
                return Ok(0);
            }
            let n = (self.data.len() - offset).min(buf.len());
            buf[..n].copy_from_slice(&self.data[offset..offset + n]);
            Ok(n)
        }

        fn len(&self) -> Option<u64> {
            Some(self.data.len() as u64)
        }
    }

    #[test]
    fn fresh_open_fetches_small_first_block_instead_of_full_size() {
        let full_block = 8 * 1024 * 1024;
        let seek_block = 512 * 1024;
        let data: Vec<u8> = (0..255u8).cycle().take(full_block * 3).collect();
        let sizes = Arc::new(Mutex::new(Vec::new()));
        let source = SizeTrackingSource { data: data.clone(), requested_sizes: sizes.clone() };
        let mut reader = PrefetchReader::with_block_sizes(source, full_block, seek_block);

        let mut buf = vec![0u8; 100];
        reader.read_exact(&mut buf).unwrap();
        assert_eq!(buf, data[..100]);

        let requested = sizes.lock().unwrap();
        assert_eq!(
            requested.first(),
            Some(&seek_block),
            "esperava o primeiro fetch (construcao) ser de {seek_block} bytes, fetches pedidos: {requested:?}"
        );
    }

    #[test]
    fn seek_fetches_small_block_instead_of_full_size() {
        let full_block = 8 * 1024 * 1024;
        let seek_block = 512 * 1024;
        let data: Vec<u8> = (0..255u8).cycle().take(full_block * 3).collect();
        let sizes = Arc::new(Mutex::new(Vec::new()));
        let source = SizeTrackingSource { data: data.clone(), requested_sizes: sizes.clone() };
        let mut reader = PrefetchReader::with_block_sizes(source, full_block, seek_block);

        let jump_to = (full_block * 2) as u64;
        reader.seek(SeekFrom::Start(jump_to)).unwrap();
        let mut buf = vec![0u8; 100];
        reader.read_exact(&mut buf).unwrap();
        assert_eq!(buf, data[jump_to as usize..jump_to as usize + 100]);

        // O ultimo fetch e o que respondeu a leitura pos-seek.
        let requested = sizes.lock().unwrap();
        assert_eq!(
            requested.last(),
            Some(&seek_block),
            "esperava o fetch pos-seek ser de {seek_block} bytes, fetches pedidos: {requested:?}"
        );
    }

    #[test]
    fn sequential_reads_after_seek_ramp_back_to_full_block_size() {
        let full_block = 4 * 1024 * 1024;
        let seek_block = 256 * 1024;
        let data: Vec<u8> = (0..255u8).cycle().take(full_block * 4).collect();
        let sizes = Arc::new(Mutex::new(Vec::new()));
        let source = SizeTrackingSource { data: data.clone(), requested_sizes: sizes.clone() };
        let mut reader = PrefetchReader::with_block_sizes(source, full_block, seek_block);

        let jump_to = (full_block * 2) as u64;
        reader.seek(SeekFrom::Start(jump_to)).unwrap();
        // Le em pedacos pequenos pra pos ficar estritamente dentro do bloco
        // pos-seek, confirmando continuidade sequencial (dispara o proximo
        // prefetch, ja de tamanho cheio).
        let mut out = vec![0u8; seek_block + 4096];
        let mut done = 0;
        while done < out.len() {
            let chunk = 4096.min(out.len() - done);
            reader.read_exact(&mut out[done..done + chunk]).unwrap();
            done += chunk;
        }
        assert_eq!(out, data[jump_to as usize..jump_to as usize + out.len()]);

        // De tempo para o prefetch especulativo (background) completar.
        std::thread::sleep(Duration::from_millis(50));

        let requested = sizes.lock().unwrap();
        assert!(
            requested.contains(&full_block),
            "esperava a rampa voltar ao bloco cheio ({full_block}) apos leitura sequencial, fetches pedidos: {requested:?}"
        );
    }

    #[test]
    fn ramp_grows_in_three_steps_seek_then_medium_then_full() {
        let full_block = 8 * 1024 * 1024;
        let seek_block = 512 * 1024;
        let medium_block = (seek_block * 4).min(full_block);
        let data: Vec<u8> = (0..255u8).cycle().take(full_block * 4).collect();
        let sizes = Arc::new(Mutex::new(Vec::new()));
        let source = SizeTrackingSource { data: data.clone(), requested_sizes: sizes.clone() };
        let mut reader = PrefetchReader::with_block_sizes(source, full_block, seek_block);

        let jump_to = (full_block * 2) as u64;
        reader.seek(SeekFrom::Start(jump_to)).unwrap();
        // Fica dentro do bloco medio (nao cruza pro cheio): consumir o bloco
        // cheio instalado dispararia mais um kick especulativo encadeado,
        // o que tornaria a asserção de 3 passos exatos fragil.
        let mut out = vec![0u8; seek_block + medium_block - 4096];
        let mut done = 0;
        while done < out.len() {
            let chunk = 4096.min(out.len() - done);
            reader.read_exact(&mut out[done..done + chunk]).unwrap();
            done += chunk;
        }
        assert_eq!(out, data[jump_to as usize..jump_to as usize + out.len()]);

        std::thread::sleep(Duration::from_millis(50));

        let requested = sizes.lock().unwrap();
        // `ends_with` (nao `==`): a construcao ja dispara um kick(0, seek_block)
        // proprio antes do seek acontecer, que tambem aparece na lista.
        assert!(
            requested.ends_with(&[seek_block, medium_block, full_block]),
            "esperava a rampa em 3 passos (seek -> medio -> cheio) no final, fetches pedidos: {requested:?}"
        );
    }

    #[test]
    fn thermal_throttle_pauses_speculative_prefetch() {
        let block_size = 64 * 1024;
        let data: Vec<u8> = (0..255u8).cycle().take(block_size * 4).collect();
        let sizes = Arc::new(Mutex::new(Vec::new()));
        let source = SizeTrackingSource { data: data.clone(), requested_sizes: sizes.clone() };
        let mut reader = PrefetchReader::with_block_size(source, block_size);
        reader.set_local_thermal_throttle(true);

        // O bloco inicial (0) é buscado na criação via kick_prefetch_sized
        std::thread::sleep(Duration::from_millis(50));

        // Consome todo o bloco 0 sequencialmente
        let mut out = vec![0u8; block_size];
        reader.read_exact(&mut out).unwrap();
        assert_eq!(out, data[0..block_size]);

        std::thread::sleep(Duration::from_millis(50));

        // Com thermal throttle ativo, kick_prefetch não deve ter disparado prefetch especulativo
        let requested = sizes.lock().unwrap();
        // Apenas o primeiro bloco síncrono/inicial foi pedido
        assert_eq!(requested.len(), 1, "com thermal throttle ativo, nenhum prefetch especulativo deveria ter sido disparado: {requested:?}");
    }
}
