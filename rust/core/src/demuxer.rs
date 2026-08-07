use ffmpeg_next as ffmpeg;
use ffmpeg::{format::context::Input, Error};

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
    pub fn new(path: &str) -> Result<Self, Error> {
        ffmpeg::init()?;

        let ictx = ffmpeg::format::input(&path)?;

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
