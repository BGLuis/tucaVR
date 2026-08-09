use ffmpeg_next as ffmpeg;
use ffmpeg::format::context::{Input, StreamIo};
use protocols::prefetch::PrefetchReader;

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
        ffmpeg::init().map_err(|e| e.to_string())?;

        let ictx = if let Some(target) = protocols::smb::SmbTarget::from_internal(path) {
            let source = protocols::smb::SmbFileSource::open(&target)?;
            let reader = PrefetchReader::new(source);
            let stream_io = StreamIo::from_read_seek(reader).map_err(|e| e.to_string())?;
            ffmpeg::format::input_from_stream(stream_io, Some(&target.path), None).map_err(|e| e.to_string())?
        } else if let Some(target) = protocols::ftp::FtpTarget::from_internal(path) {
            let source = protocols::ftp::FtpFileSource::open(&target)?;
            let reader = PrefetchReader::new(source);
            let stream_io = StreamIo::from_read_seek(reader).map_err(|e| e.to_string())?;
            ffmpeg::format::input_from_stream(stream_io, Some(&target.path), None).map_err(|e| e.to_string())?
        } else if let Some(target) = protocols::sftp::SftpTarget::from_internal(path) {
            let source = protocols::sftp::SftpFileSource::open(&target)?;
            let reader = PrefetchReader::new(source);
            let stream_io = StreamIo::from_read_seek(reader).map_err(|e| e.to_string())?;
            ffmpeg::format::input_from_stream(stream_io, Some(&target.path), None).map_err(|e| e.to_string())?
        } else if path.starts_with("https://") {
            let source = protocols::http::HttpsRangeSource::new(path)?;
            let reader = PrefetchReader::new(source);
            let stream_io = StreamIo::from_read_seek(reader).map_err(|e| e.to_string())?;
            ffmpeg::format::input_from_stream(stream_io, Some(path), None).map_err(|e| e.to_string())?
        } else {
            ffmpeg::format::input(&path).map_err(|e| e.to_string())?
        };

        let mut video_streams = Vec::new();
        let mut audio_streams = Vec::new();

        for stream in ictx.streams() {
            let codec = stream.parameters();
            match codec.medium() {
                ffmpeg::media::Type::Video => video_streams.push(stream.index()),
                ffmpeg::media::Type::Audio => audio_streams.push(stream.index()),
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
        })
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

    pub fn read_packet(&mut self) -> Option<(usize, ffmpeg::Packet)> {
        for (stream, packet) in self.input_context.packets() {
            return Some((stream.index(), packet));
        }
        None
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
