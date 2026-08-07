use ffmpeg_next as ffmpeg;
use ffmpeg_next::codec::decoder::Audio;
use ffmpeg_next::format::context::Input;
use ffmpeg_next::software::resampling::Context as Resampler;

const OUTPUT_SAMPLE_RATE: u32 = 48000;

pub struct AudioDecoder {
    decoder: Audio,
    resampler: Resampler,
    stream_index: usize,
}

impl AudioDecoder {
    /// `stream_index` deve vir do `Demuxer` (ver `Demuxer::audio_stream_index` /
    /// `select_audio_track`) — antes este construtor escolhia o "melhor"
    /// stream de audio pela heuristica do FFmpeg de forma independente do
    /// Demuxer, o que podia divergir do stream que o Demuxer de fato
    /// roteava (`read_packet`), fazendo o audio ser descartado
    /// silenciosamente em arquivos com mais de uma trilha.
    pub fn new(input_ctx: &Input, stream_index: usize) -> Result<Self, ffmpeg::Error> {
        let stream = input_ctx
            .stream(stream_index)
            .ok_or(ffmpeg::Error::StreamNotFound)?;

        let context = ffmpeg::codec::context::Context::from_parameters(stream.parameters())?;
        let mut decoder = context.decoder().audio()?;

        let resampler = Resampler::get(
            decoder.format(),
            decoder.channel_layout(),
            decoder.rate(),
            ffmpeg::format::Sample::F32(ffmpeg::format::sample::Type::Packed),
            ffmpeg::util::channel_layout::ChannelLayout::STEREO,
            OUTPUT_SAMPLE_RATE,
        )?;

        Ok(Self {
            decoder,
            resampler,
            stream_index,
        })
    }

    /// Controle de velocidade "estilo fita": reamostra para
    /// `48000/speed` em vez de `48000`, mas continua tocando no stream
    /// de saida fixo em 48kHz. Menos samples por segundo de conteudo
    /// original quando speed>1 significa que o mesmo trecho toca mais
    /// rapido (e mais agudo) — nao preserva o pitch (isso exigiria
    /// time-stretching tipo WSOLA/SoundTouch, fora do escopo do MVP).
    pub fn set_speed(&mut self, speed: f32) -> Result<(), ffmpeg::Error> {
        let target_rate = ((OUTPUT_SAMPLE_RATE as f64) / (speed as f64))
            .round()
            .clamp(8000.0, 192000.0) as u32;

        self.resampler = Resampler::get(
            self.decoder.format(),
            self.decoder.channel_layout(),
            self.decoder.rate(),
            ffmpeg::format::Sample::F32(ffmpeg::format::sample::Type::Packed),
            ffmpeg::util::channel_layout::ChannelLayout::STEREO,
            target_rate,
        )?;
        Ok(())
    }

    pub fn decode(&mut self, packet: &ffmpeg::Packet) -> Result<Vec<f32>, ffmpeg::Error> {
        if packet.stream() != self.stream_index {
            return Ok(Vec::new());
        }

        self.decoder.send_packet(packet)?;
        
        let mut decoded = ffmpeg::frame::Audio::empty();
        let mut samples = Vec::new();
        
        while self.decoder.receive_frame(&mut decoded).is_ok() {
            let mut resampled = ffmpeg::frame::Audio::empty();
            self.resampler.run(&decoded, &mut resampled)?;
            
            let data = resampled.data(0);
            let ptr = data.as_ptr() as *const f32;
            let len = data.len() / 4;
            let slice = unsafe { std::slice::from_raw_parts(ptr, len) };
            samples.extend_from_slice(slice);
        }
        
        Ok(samples)
    }
}
