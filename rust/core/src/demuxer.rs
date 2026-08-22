use ffmpeg_next as ffmpeg;
use ffmpeg::format::context::{Input, StreamIo};
use protocols::prefetch::{PrefetchReader, PrefetchStats, SharedRangeSource};
use std::sync::{Arc, Mutex};

// 12MB em vez do default 4MB: a 8K/60fps HEVC (~63Mbps / 7.85MB/s), 4MB cobre só ~0.5s por bloco.
const REMOTE_PREFETCH_BLOCK_SIZE: usize = 12 * 1024 * 1024;
// Bloco pequeno so pro PRIMEIRO fetch pos-seek — ver doc de protocols::prefetch sobre a rampa.
const SEEK_PREFETCH_BLOCK_SIZE: usize = 1024 * 1024;

/// `probesize`/`analyzeduration` do libavformat (default 5MB / 5s) controlam
/// quanto o `avformat_find_stream_info` le antes de devolver o controle —
/// custo pago em TODA abertura (arquivo novo, nao so seek), multiplicado
/// pelo RTT de rede em SMB/SFTP. Reduzido pra 1MB/1s: suficiente pros
/// containers usados aqui (MP4/MKV com header bem formado ja declara
/// codec/dimensoes; nao decodifica frame nenhum pra isso), mas nao
/// validado contra arquivo exotico/corrompido — se a deteccao de trilha
/// falhar de forma nova, este e o primeiro lugar a suspeitar.
fn fast_probe_options() -> ffmpeg::Dictionary<'static> {
    let mut opts = ffmpeg::Dictionary::new();
    opts.set("probesize", "1000000");
    opts.set("analyzeduration", "1000000");
    opts
}

/// Resultado de `Demuxer::read_packet` — ver comentario no metodo sobre por
/// que isto nao e so `Option<(usize, Packet)>` como antes.
pub enum ReadPacketOutcome {
    Packet(usize, ffmpeg::Packet),
    Eof,
    Error(String),
}

/// Conexao de rede reaproveitavel entre `Demuxer::open()` sucessivos no
/// MESMO path (ver `PlaybackController::seek`) — SFTP, SMB e HTTPS por
/// enquanto, os protocolos cujo `RangeSource` ja tolera ficar aberto entre
/// sessoes (SFTP reconecta sozinho em erro de leitura, ver
/// `SftpFileSource::read_range`; SMB usa `auto_reconnect` da propria lib
/// cliente; HTTPS usa `reqwest::blocking::Client`, que já faz pooling e
/// reconexao transparente por request). FTP ainda nao tem essa garantia
/// (sem retry/reconnect na leitura), entao continua abrindo conexao nova a
/// cada seek — cachear sem isso arriscaria falhar um seek que hoje sempre
/// funciona. `ffmpeg-next` nao expoe API pra recuperar o stream de dentro
/// de um `Input` ja construido, entao a conexao e compartilhada via
/// `Arc<Mutex<_>>` desde o inicio em vez de "recuperada" depois do Demuxer
/// antigo morrer.
#[derive(Default)]
pub enum ConnectionCache {
    #[default]
    Empty,
    Sftp(String, Arc<Mutex<protocols::sftp::SftpFileSource>>),
    Smb(String, Arc<Mutex<protocols::smb::SmbFileSource>>),
    Https(String, Arc<Mutex<protocols::http::HttpsRangeSource>>),
}

pub struct Demuxer {
    pub input_context: Input,
    pub video_stream_index: Option<usize>,
    pub audio_stream_index: Option<usize>,
    // Todos os streams de cada tipo, na ordem em que aparecem no container.
    // `video_stream_index`/`audio_stream_index` sempre apontam para um dos
    // indices aqui dentro. select_*_track() usa a posicao ordinal nesta
    // lista (0 = primeira trilha de audio, 1 = segunda, etc.), nao o
    // stream index bruto do container.
    pub video_streams: Vec<usize>,
    pub audio_streams: Vec<usize>,
    pub subtitle_streams: Vec<usize>,
    // Instrumentacao do PrefetchReader da fonte remota (docs/DEBUGGING.md) —
    // `None` para arquivo local ou `http://` puro (sem PrefetchReader
    // envolvido, ver roteamento em `new()`). Capturado ANTES de o
    // PrefetchReader ser engolido pelo `StreamIo` opaco do ffmpeg-next.
    pub network_stats: Option<Arc<PrefetchStats>>,
}

impl Demuxer {
    /// `path` pode ser um caminho local (ou `/proc/self/fd/N`, usado pelo
    /// content:// picker), uma URI interna `smb://` (T6.3, ver
    /// `protocols::smb::uri`), ou uma URL `http://`/`https://` (T7).
    ///
    /// Roteamento por esquema (achado verificado nesta sessao, ver
    /// `protocols::http` para os detalhes de como foi confirmado):
    /// - `smb://...`  -> custom I/O via `protocols::smb::SmbFileSource` (o
    ///   libavformat empacotado aqui nao tem `libsmbclient`).
    /// - `ftp://...` (formato interno, ver `protocols::ftp::uri`) -> custom
    ///   I/O via `protocols::ftp::FtpFileSource` (T6.3) — mesmo motivo do
    ///   SMB: nem o libavformat empacotado tem suporte nativo a FTP com o
    ///   tipo de seek que o demuxer precisa aqui.
    /// - `sftp://...` (formato interno, ver `protocols::sftp::uri`) -> custom
    ///   I/O via `protocols::sftp::SftpFileSource` (T6.3), mesmo motivo.
    /// - `https://...` -> custom I/O via `protocols::http::HttpsRangeSource`
    ///   (o libavformat empacotado aqui foi compilado SEM nenhum backend TLS
    ///   — `https://` nativo simplesmente nao funciona neste `.so`).
    /// - Qualquer outra coisa (local, `/proc/self/fd/N`, `http://` puro) ->
    ///   `ffmpeg::format::input` direto, sem custom I/O: o protocolo `http`
    ///   (sem `s`) ESTA habilitado nesta build e ja lida com range requests
    ///   internamente, entao nao ha necessidade de reinventar isso para
    ///   HTTP sem TLS.
    pub fn new(path: &str) -> Result<Self, String> {
        Self::open(path, None)
    }

    /// Como `new()`, mas aceita um `ConnectionCache` opcional (ver o tipo
    /// acima) pra reaproveitar a conexao SFTP de uma sessao anterior no
    /// MESMO path em vez de reabrir do zero — usado por
    /// `PlaybackController::load_at` (seek), nao por chamadores avulsos
    /// como `thumbnail::generate`.
    pub fn open(path: &str, cache: Option<&mut ConnectionCache>) -> Result<Self, String> {
        ffmpeg::init().map_err(|e| e.to_string())?;

        let mut network_stats = None;

        let ictx = if let Some(target) = protocols::smb::SmbTarget::from_internal(path) {
            let shared = Self::smb_source(path, &target, cache)?;
            let reader = PrefetchReader::with_block_sizes(shared, REMOTE_PREFETCH_BLOCK_SIZE, SEEK_PREFETCH_BLOCK_SIZE);
            network_stats = Some(reader.stats());
            let stream_io = StreamIo::from_read_seek(reader).map_err(|e| e.to_string())?;
            ffmpeg::format::input_from_stream(stream_io, Some(&target.path), Some(fast_probe_options())).map_err(|e| e.to_string())?
        } else if let Some(target) = protocols::ftp::FtpTarget::from_internal(path) {
            let source = protocols::ftp::FtpFileSource::open(&target)?;
            let reader = PrefetchReader::with_block_sizes(source, REMOTE_PREFETCH_BLOCK_SIZE, SEEK_PREFETCH_BLOCK_SIZE);
            network_stats = Some(reader.stats());
            let stream_io = StreamIo::from_read_seek(reader).map_err(|e| e.to_string())?;
            ffmpeg::format::input_from_stream(stream_io, Some(&target.path), Some(fast_probe_options())).map_err(|e| e.to_string())?
        } else if let Some(target) = protocols::sftp::SftpTarget::from_internal(path) {
            let shared = Self::sftp_source(path, &target, cache)?;
            let reader = PrefetchReader::with_block_sizes(shared, REMOTE_PREFETCH_BLOCK_SIZE, SEEK_PREFETCH_BLOCK_SIZE);
            network_stats = Some(reader.stats());
            let stream_io = StreamIo::from_read_seek(reader).map_err(|e| e.to_string())?;
            ffmpeg::format::input_from_stream(stream_io, Some(&target.path), Some(fast_probe_options())).map_err(|e| e.to_string())?
        } else if let Some(target) = protocols::nfs::NfsTarget::from_internal(path) {
            let source = protocols::nfs::NfsFileSource::open(&target)?;
            let reader = PrefetchReader::with_block_sizes(source, REMOTE_PREFETCH_BLOCK_SIZE, SEEK_PREFETCH_BLOCK_SIZE);
            network_stats = Some(reader.stats());
            let stream_io = StreamIo::from_read_seek(reader).map_err(|e| e.to_string())?;
            ffmpeg::format::input_from_stream(stream_io, Some(&target.file_path), Some(fast_probe_options())).map_err(|e| e.to_string())?
        } else if path.contains(".m3u8") || path.starts_with("hls://") {
            let source = protocols::hls::HlsStreamSource::open(path)?;
            let stream_io = StreamIo::from_read_seek(source).map_err(|e| e.to_string())?;
            ffmpeg::format::input_from_stream(stream_io, Some(path), Some(fast_probe_options())).map_err(|e| e.to_string())?
        } else if path.starts_with("https://") {
            let shared = Self::https_source(path, cache)?;
            let reader = PrefetchReader::with_block_sizes(shared, REMOTE_PREFETCH_BLOCK_SIZE, SEEK_PREFETCH_BLOCK_SIZE);
            network_stats = Some(reader.stats());
            let stream_io = StreamIo::from_read_seek(reader).map_err(|e| e.to_string())?;
            ffmpeg::format::input_from_stream(stream_io, Some(path), Some(fast_probe_options())).map_err(|e| e.to_string())?
        } else {
            ffmpeg::format::input_with_dictionary(&path, fast_probe_options()).map_err(|e| e.to_string())?
        };

        let mut video_streams = Vec::new();
        let mut audio_streams = Vec::new();
        let mut subtitle_streams = Vec::new();

        for stream in ictx.streams() {
            let codec = stream.parameters();
            match codec.medium() {
                ffmpeg::media::Type::Video => video_streams.push(stream.index()),
                ffmpeg::media::Type::Audio => audio_streams.push(stream.index()),
                ffmpeg::media::Type::Subtitle => subtitle_streams.push(stream.index()),
                _ => {}
            }
        }

        let video_stream_index = video_streams.first().copied();
        let audio_stream_index = audio_streams.first().copied();

        Ok(Self {
            input_context: ictx,
            video_stream_index,
            audio_stream_index,
            video_streams,
            audio_streams,
            subtitle_streams,
            network_stats,
        })
    }

    /// Reaproveita a conexao SFTP do `cache` se `path` bate com a da ultima
    /// sessao; senao abre uma nova e (se `cache` foi passado) a guarda pra
    /// o proximo `open()`. So o `Arc` e clonado aqui — a sessao SSH de
    /// verdade so fecha quando o ultimo clone for dropado (a sessao antiga,
    /// se houver, ja foi finalizada por `stop_and_join()` antes de chegar
    /// aqui — ver `PlaybackController::load_at`).
    fn sftp_source(
        path: &str,
        target: &protocols::sftp::SftpTarget,
        cache: Option<&mut ConnectionCache>,
    ) -> Result<SharedRangeSource<protocols::sftp::SftpFileSource>, String> {
        let Some(cache) = cache else {
            let source = protocols::sftp::SftpFileSource::open(target)?;
            return Ok(SharedRangeSource::new(Arc::new(Mutex::new(source))));
        };
        if let ConnectionCache::Sftp(cached_path, conn) = cache {
            if cached_path.as_str() == path {
                return Ok(SharedRangeSource::new(conn.clone()));
            }
        }
        let conn = Arc::new(Mutex::new(protocols::sftp::SftpFileSource::open(target)?));
        *cache = ConnectionCache::Sftp(path.to_string(), conn.clone());
        Ok(SharedRangeSource::new(conn))
    }

    /// Mesma logica de `sftp_source`, pra SMB (a lib cliente ja tem
    /// `auto_reconnect` proprio, ver `SmbFileSource`).
    fn smb_source(
        path: &str,
        target: &protocols::smb::SmbTarget,
        cache: Option<&mut ConnectionCache>,
    ) -> Result<SharedRangeSource<protocols::smb::SmbFileSource>, String> {
        let Some(cache) = cache else {
            let source = protocols::smb::SmbFileSource::open(target)?;
            return Ok(SharedRangeSource::new(Arc::new(Mutex::new(source))));
        };
        if let ConnectionCache::Smb(cached_path, conn) = cache {
            if cached_path.as_str() == path {
                return Ok(SharedRangeSource::new(conn.clone()));
            }
        }
        let conn = Arc::new(Mutex::new(protocols::smb::SmbFileSource::open(target)?));
        *cache = ConnectionCache::Smb(path.to_string(), conn.clone());
        Ok(SharedRangeSource::new(conn))
    }

    /// Mesma logica de `sftp_source`/`smb_source`, pra HTTPS — sem `target`
    /// separado porque `HttpsRangeSource::new` ja recebe `path` direto.
    fn https_source(
        path: &str,
        cache: Option<&mut ConnectionCache>,
    ) -> Result<SharedRangeSource<protocols::http::HttpsRangeSource>, String> {
        let Some(cache) = cache else {
            let source = protocols::http::HttpsRangeSource::new(path)?;
            return Ok(SharedRangeSource::new(Arc::new(Mutex::new(source))));
        };
        if let ConnectionCache::Https(cached_path, conn) = cache {
            if cached_path.as_str() == path {
                return Ok(SharedRangeSource::new(conn.clone()));
            }
        }
        let conn = Arc::new(Mutex::new(protocols::http::HttpsRangeSource::new(path)?));
        *cache = ConnectionCache::Https(path.to_string(), conn.clone());
        Ok(SharedRangeSource::new(conn))
    }

    /// Seleciona a trilha de audio pela posicao ordinal (0 = primeira
    /// encontrada). Indice fora do range e ignorado silenciosamente,
    /// mantendo a selecao atual.
    pub fn select_audio_track(&mut self, ordinal: usize) {
        if let Some(&idx) = self.audio_streams.get(ordinal) {
            self.audio_stream_index = Some(idx);
        }
    }

    /// Idem para video. Trocar de trilha de video em um arquivo com
    /// multiplos streams de video e incomum, mas a API fica simetrica.
    pub fn select_video_track(&mut self, ordinal: usize) {
        if let Some(&idx) = self.video_streams.get(ordinal) {
            self.video_stream_index = Some(idx);
        }
    }

    /// Le o proximo pacote, dirigindo `Packet::read` diretamente em vez do
    /// iterator de conveniencia `Input::packets()` — achado desta sessao
    /// (docs/NETWORK-IO-PERFORMANCE.md): `packets()` devolve `None` tanto
    /// para EOF de verdade quanto para QUALQUER erro de I/O (rede caindo no
    /// meio de uma leitura, por exemplo), entao o chamador nao tinha como
    /// distinguir "acabou o arquivo" de "a rede falhou" — uma falha de rede
    /// virava um restart silencioso pro inicio do arquivo (ver playback.rs).
    /// A doc do proprio `PacketIter` recomenda isto para quem precisa
    /// observar o erro.
    pub fn read_packet(&mut self) -> ReadPacketOutcome {
        let mut packet = ffmpeg::Packet::empty();
        loop {
            match packet.read(&mut self.input_context) {
                Ok(()) => {
                    let idx = packet.stream();
                    return ReadPacketOutcome::Packet(idx, packet);
                }
                Err(ffmpeg::Error::Eof) => return ReadPacketOutcome::Eof,
                // Pacote corrompido isolado — o demuxer consegue
                // resincronizar (mesmo comportamento do PacketIter interno).
                Err(ffmpeg::Error::InvalidData) => continue,
                Err(e) => return ReadPacketOutcome::Error(e.to_string()),
            }
        }
    }

    pub fn get_video_extradata(&self) -> Option<Vec<u8>> {
        if let Some(idx) = self.video_stream_index {
            if let Some(stream) = self.input_context.stream(idx) {
                unsafe {
                    let ptr = stream.as_ptr();
                    if !ptr.is_null() {
                        let codecpar = (*ptr).codecpar;
                        if !codecpar.is_null() {
                            let ed = (*codecpar).extradata;
                            let sz = (*codecpar).extradata_size;
                            if !ed.is_null() && sz > 0 {
                                let slice = std::slice::from_raw_parts(ed, sz as usize);
                                return Some(slice.to_vec());
                            }
                        }
                    }
                }
            }
        }
        None
    }
}
