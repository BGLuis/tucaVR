use ffmpeg_next as ffmpeg;
use ffmpeg_next::codec::decoder::Audio;
use ffmpeg_next::format::context::Input;
use ffmpeg_next::software::resampling::Context as Resampler;
use media_logic::spatial_audio::{AudioChannelLayout, ChannelOrdering, Normalization};

const OUTPUT_SAMPLE_RATE: u32 = 48000;

/// Resultado da detecção de metadados Ambisonics do container.
#[derive(Debug, Default)]
struct AmbisonicsHint {
    is_ambisonics: bool,
    ordering: Option<ChannelOrdering>,
    normalization: Option<Normalization>,
}

/// Detecta metadados Ambisonics nas tags de texto do stream (heurística rápida).
fn detect_from_tags(stream: &ffmpeg::format::stream::Stream) -> AmbisonicsHint {
    let mut hint = AmbisonicsHint::default();
    for (k, v) in stream.metadata().iter() {
        let key = k.to_lowercase();
        let val = v.to_lowercase();
        if key.contains("ambisonic") || key.contains("spatial") || val.contains("ambisonic") {
            hint.is_ambisonics = true;
            // Tags do YouTube / GoPro: "SPATIAL-AUDIO-MODE" = "ambiX" (ACN/SN3D)
            //                          "SPATIAL-AUDIO-FORMAT" = "FUMA" (FuMa)
            if val.contains("fuma") || val.contains("foa_fuma") {
                hint.ordering = Some(ChannelOrdering::FuMa);
                hint.normalization = Some(Normalization::FuMa);
            } else if val.contains("acn") || val.contains("ambix") || val.contains("ambi-x") {
                hint.ordering = Some(ChannelOrdering::Acn);
                hint.normalization = Some(Normalization::Sn3d);
            }
        }
    }
    hint
}

/// Detecta formato Ambisonics a partir da box SA3D (MP4/MOV) embutida nos extradata do codec.
/// Formato: https://github.com/google/spatial-media/blob/master/docs/spatial-audio-rfc.md
fn detect_from_sa3d_box(extradata: &[u8]) -> Option<AmbisonicsHint> {
    // Busca a assinatura "SA3D" no bloco de extradata (atom MP4)
    let fourcc = b"SA3D";
    let pos = extradata.windows(4).position(|w| w == fourcc)?;
    // Após a FourCC, o byte de versão (1 byte) e depois ambisonic_type (1 byte).
    // ambisonic_type: 0 = FUMA, 1 = ACN (padrão YouTube/AmbiX)
    let data_start = pos + 4;
    if data_start + 1 >= extradata.len() {
        return None;
    }
    let _version = extradata[data_start];
    let ambisonic_type = extradata.get(data_start + 1).copied().unwrap_or(1);
    Some(AmbisonicsHint {
        is_ambisonics: true,
        ordering: Some(if ambisonic_type == 0 {
            ChannelOrdering::FuMa
        } else {
            ChannelOrdering::Acn
        }),
        normalization: Some(if ambisonic_type == 0 {
            Normalization::FuMa
        } else {
            Normalization::Sn3d
        }),
    })
}

/// Detecta a tag `AMBISONICS` do codec private de streams Matroska (MKV/WebM).
/// O Matroska codifica a tag como dados binários de codec private com a
/// ID de elemento AMBISONICS (0xBB) na codec-private block.
fn detect_from_mkv_ambisonics_tag(extradata: &[u8]) -> Option<AmbisonicsHint> {
    // Busca pelo marcador binário da tag AMBISONICS do Matroska:
    // O bloco tem a signature ASCII "AMBI" nos primeiros bytes de extensões customizadas
    let marker = b"AMBI";
    let pos = extradata.windows(4).position(|w| w == marker)?;
    let data_start = pos + 4;
    // Byte de formato: 0x00 = FuMa, 0x01 = ACN/SN3D
    let fmt_byte = extradata.get(data_start).copied().unwrap_or(1);
    Some(AmbisonicsHint {
        is_ambisonics: true,
        ordering: Some(if fmt_byte == 0x00 {
            ChannelOrdering::FuMa
        } else {
            ChannelOrdering::Acn
        }),
        normalization: Some(if fmt_byte == 0x00 {
            Normalization::FuMa
        } else {
            Normalization::Sn3d
        }),
    })
}

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

        // 1. Heurística de texto (rápida, compatível com todos os containers)
        let mut hint = detect_from_tags(&stream);

        // 2. Box SA3D (MP4/MOV) — decodificação estrutural, desbloqueia FuMa
        if !hint.ordering.is_some() {
            let extradata = decoder.extradata().unwrap_or(&[]);
            if let Some(sa3d_hint) = detect_from_sa3d_box(extradata) {
                hint = sa3d_hint;
            }
        }

        // 3. Tag AMBISONICS do codec private (MKV/WebM)
        if !hint.ordering.is_some() {
            let extradata = decoder.extradata().unwrap_or(&[]);
            if let Some(mkv_hint) = detect_from_mkv_ambisonics_tag(extradata) {
                hint = mkv_hint;
            }
        }

        let channel_layout = AudioChannelLayout::from_channel_count_tags_and_ordering(
            channels,
            hint.is_ambisonics,
            hint.ordering,
            hint.normalization,
        );

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
