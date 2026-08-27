use ffmpeg_next as ffmpeg;
use ffmpeg_next::codec::decoder::Audio;
use ffmpeg_next::format::context::Input;
use ffmpeg_next::software::resampling::Context as Resampler;
use media_logic::spatial_audio::AudioChannelLayout;

const OUTPUT_SAMPLE_RATE: u32 = 48000;

pub struct AudioDecoder {
    decoder: Audio,
    resampler: Resampler,
    stream_index: usize,
    channels: u32,
    channel_layout: AudioChannelLayout,
    resampler_layout: ffmpeg::util::channel_layout::ChannelLayout,
}

unsafe impl Send for AudioDecoder {}

impl AudioDecoder {
    /// `stream_index` deve vir do `Demuxer` (ver `Demuxer::audio_stream_index` /
    /// `select_audio_track`).
    pub fn new(input_ctx: &Input, stream_index: usize) -> Result<Self, ffmpeg::Error> {
        let stream = input_ctx
            .stream(stream_index)
            .ok_or(ffmpeg::Error::StreamNotFound)?;

        let context = ffmpeg::codec::context::Context::from_parameters(stream.parameters())?;
        let decoder = context.decoder().audio()?;

        let channels = decoder.channels() as u32;

        // Verifica tags de metadados para detectar pistas de Ambisonics em vídeos 360°
        let mut is_ambisonics = false;
        for (k, v) in stream.metadata().iter() {
            let key = k.to_lowercase();
            let val = v.to_lowercase();
            if key.contains("ambisonic") || key.contains("spatial") || val.contains("ambisonic") {
                is_ambisonics = true;
                break;
            }
        }

        let channel_layout = AudioChannelLayout::from_channel_count_and_tags(channels, is_ambisonics);

        let resampler_layout = match channels {
            6 => ffmpeg::util::channel_layout::ChannelLayout::_5POINT1,
            8 => ffmpeg::util::channel_layout::ChannelLayout::_7POINT1,
            4 => ffmpeg::util::channel_layout::ChannelLayout::QUAD,
            1 => ffmpeg::util::channel_layout::ChannelLayout::MONO,
            _ => ffmpeg::util::channel_layout::ChannelLayout::STEREO,
        };

        let resampler = Resampler::get(
            decoder.format(),
            decoder.channel_layout(),
            decoder.rate(),
            ffmpeg::format::Sample::F32(ffmpeg::format::sample::Type::Packed),
            resampler_layout,
            OUTPUT_SAMPLE_RATE,
        )?;

        Ok(Self {
            decoder,
            resampler,
            stream_index,
            channels,
            channel_layout,
            resampler_layout,
        })
    }

    pub fn channel_layout(&self) -> AudioChannelLayout {
        self.channel_layout
    }

    pub fn channels(&self) -> u32 {
        self.channels
    }

    /// Controle de velocidade "estilo fita": reamostra para
    /// `48000/speed` em vez de `48000`, mas continua tocando no stream
    /// de saida fixo em 48kHz.
    pub fn set_speed(&mut self, speed: f32) -> Result<(), ffmpeg::Error> {
        let target_rate = media_logic::audio_resample::target_sample_rate(OUTPUT_SAMPLE_RATE, speed);

        self.resampler = Resampler::get(
            self.decoder.format(),
            self.decoder.channel_layout(),
            self.decoder.rate(),
            ffmpeg::format::Sample::F32(ffmpeg::format::sample::Type::Packed),
            self.resampler_layout,
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
            let available = data.len() / 4;
            let len = media_logic::audio_resample::valid_sample_count(
                resampled.samples(),
                resampled.channels() as usize,
                available,
            );
            let slice = unsafe { std::slice::from_raw_parts(ptr, len) };
            samples.extend_from_slice(slice);
        }

        Ok(samples)
    }
}
