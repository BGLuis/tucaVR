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

use std::io::{self, Read, Seek, SeekFrom};
use std::marker::PhantomData;
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread::JoinHandle;

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

/// 4MB — dentro da faixa 2-8MB que o doc recomenda para o buffer de
/// prefetch (secao 6, "Cuidados e Armadilhas"). O Demuxer usa um valor maior
/// (12MB) para video remoto — ver `core/src/demuxer.rs`.
const DEFAULT_BLOCK_SIZE: usize = 4 * 1024 * 1024;

struct Block {
    start: u64,
    data: Vec<u8>,
    len: usize,
}

enum Command {
    Fetch(u64),
    Shutdown,
}

type FetchResult = io::Result<Block>;

fn worker_gone_err() -> io::Error {
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
    _source: PhantomData<S>,
}

impl<S: RangeSource + 'static> PrefetchReader<S> {
    pub fn new(source: S) -> Self {
        Self::with_block_size(source, DEFAULT_BLOCK_SIZE)
    }

    pub fn with_block_size(mut source: S, block_size: usize) -> Self {
        let block_size = block_size.max(64 * 1024);
        let total_len = source.len();

        let (cmd_tx, cmd_rx) = mpsc::channel::<Command>();
        let (result_tx, result_rx) = mpsc::channel::<FetchResult>();

        let worker = std::thread::Builder::new()
            .name("vrplayer-prefetch-io".to_string())
            .spawn(move || {
                for cmd in cmd_rx {
                    match cmd {
                        Command::Fetch(offset) => {
                            // Vec novo por bloco (nao reaproveita o buffer da
                            // thread principal): os dados precisam atravessar
                            // o canal por valor. Blocos sao grandes mas raros
                            // (segundos de video cada), entao a alocacao nao
                            // e o gargalo aqui.
                            let mut data = vec![0u8; block_size];
                            let result = source.read_range(offset, &mut data).map(|len| Block { start: offset, data, len });
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
            _source: PhantomData,
        };
        // Dispara a busca do primeiro bloco ja na construcao: o primeiro
        // `read()` (quase sempre offset 0) encontra o prefetch em andamento
        // em vez de comecar do zero.
        this.kick_prefetch(0);
        this
    }

    fn cache_hit(&self, pos: u64) -> bool {
        self.cache_len > 0 && pos >= self.cache_start && pos < self.cache_start + self.cache_len as u64
    }

    /// Dispara em background a busca do bloco iniciando em `offset`, se
    /// ainda fizer sentido (dentro do tamanho conhecido do arquivo). Nao
    /// bloqueia — o resultado chega por `result_rx` numa chamada futura.
    fn kick_prefetch(&mut self, offset: u64) {
        if let Some(total) = self.total_len
            && offset >= total
        {
            return;
        }
        if self.cmd_tx.send(Command::Fetch(offset)).is_ok() {
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
            return Ok(());
        }

        if self.pending_start == Some(pos) {
            // Caminho feliz: leitura sequencial alcancou exatamente o bloco
            // que o prefetch anterior ja comecou a buscar.
            let result = self.result_rx.recv().map_err(|_| worker_gone_err())?;
            self.pending_start = None;
            self.install(result?);
        } else {
            // Cache-miss que nao bate com o prefetch em andamento — ou e a
            // primeiríssima leitura, ou um seek de verdade tornou o
            // prefetch em voo obsoleto. O worker processa um Command por
            // vez; se havia um pedido pendente, drena a resposta (descartada)
            // antes de pedir o bloco certo, para nao deixar uma resposta
            // orfa no canal.
            if self.pending_start.take().is_some() {
                let _ = self.result_rx.recv();
            }
            self.cmd_tx.send(Command::Fetch(pos)).map_err(|_| worker_gone_err())?;
            let result = self.result_rx.recv().map_err(|_| worker_gone_err())?;
            self.install(result?);
        }

        if self.cache_len > 0 {
            // Bloco valido instalado — aposta que a leitura continua
            // sequencial e ja dispara a busca do proximo em background.
            self.kick_prefetch(self.cache_start + self.cache_len as u64);
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
}
