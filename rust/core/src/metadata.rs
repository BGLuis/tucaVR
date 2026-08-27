use ffmpeg_next as ffmpeg;
use media_logic::metadata_wire::{MediaMetadata, TrackInfo, TrackKind};

use crate::demuxer::Demuxer;

// Tags de container repassadas ao Kotlin — whitelist deliberada. MP4/MKV
// despejam varias chaves internas (major_brand, compatible_brands, encoder)
// que nao tem tradução nem valor pro usuário; a UI so mostra o que reconhece.
const TAG_WHITELIST: &[&str] = &["title", "artist", "album", "date", "genre", "comment"];

/// Abre `path` (qualquer protocolo suportado por `Demuxer::open` — local,
/// smb://, ftp://, sftp://, http(s)://) e extrai metadados de container e
/// trilhas via FFmpeg. Chamada bloqueante (rede + probe) — so decodifica
/// header/index, nenhum frame; mesma ressalva de `thumbnail::generate`
/// sobre rodar sempre numa thread de fundo do lado chamador.
pub fn extract(path: &str) -> Result<MediaMetadata, String> {
    let demuxer = Demuxer::new(path)?;
    let ictx = &demuxer.input_context;

    let format = ictx.format();
    let container = format.name().to_string();
    let container_long = format.description().to_string();
    let duration_us = ictx.duration().max(0);
    let bit_rate = ictx.bit_rate();

    let tags = ictx
        .metadata()
        .iter()
        .filter(|(k, _)| TAG_WHITELIST.contains(&k.to_lowercase().as_str()))
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect();

    let mut tracks = Vec::new();
    for (ordinal, &idx) in demuxer.video_streams.iter().enumerate() {
        tracks.push(track_info(&demuxer, TrackKind::Video, ordinal, idx));
    }
    for (ordinal, &idx) in demuxer.audio_streams.iter().enumerate() {
        tracks.push(track_info(&demuxer, TrackKind::Audio, ordinal, idx));
    }
    for (ordinal, &idx) in demuxer.subtitle_streams.iter().enumerate() {
        tracks.push(track_info(&demuxer, TrackKind::Subtitle, ordinal, idx));
    }
    let (video_w, video_h) = tracks
        .iter()
        .find(|t| t.kind == TrackKind::Video)
        .map(|t| (t.width, t.height))
        .unwrap_or((0, 0));

    let (fmt, confidence) = crate::format3d_detect::detect(&demuxer, path, video_w, video_h);
    let format3d_index = fmt.to_screen_mode_index();
    let detection_confidence = confidence as u8;

    Ok(MediaMetadata {
        container,
        container_long,
        duration_us,
        bit_rate,
        format3d_index,
        detection_confidence,
        tags,
        tracks,
    })
}

// Le width/height/sample_rate/canais direto do AVCodecParameters cru em vez
// de abrir um AVCodecContext (`Context::from_parameters(..).decoder().video()`,
// o padrao usado em thumbnail.rs/playback.rs pra decodificar de verdade):
// abrir decoder chama avcodec_open2, que e caro por trilha e FALHA pra
// codecs sem decoder nesta build enxuta (legenda, audio exotico) — os dois
// tipos de trilha que mais valem a pena listar aqui. codec::Parameters do
// ffmpeg-next 9.0 nao expoe esses campos, entao o acesso e via ponteiro cru
// (mesmo padrao ja usado em Demuxer::get_video_extradata).
fn track_info(demuxer: &Demuxer, kind: TrackKind, ordinal: usize, stream_index: usize) -> TrackInfo {
    let empty = || TrackInfo {
        kind,
        ordinal,
        codec: String::new(),
        language: String::new(),
        title: String::new(),
        width: 0,
        height: 0,
        fps_milli: 0,
        channels: 0,
        sample_rate: 0,
        bit_rate: 0,
        is_default: false,
    };
    let Some(stream) = demuxer.input_context.stream(stream_index) else {
        return empty();
    };

    let params = stream.parameters();
    let codec = params.id().name().to_string();
    let meta = stream.metadata();
    let language = meta.get("language").unwrap_or_default().to_string();
    let title = meta.get("title").unwrap_or_default().to_string();
    let is_default = stream.disposition().contains(ffmpeg::format::stream::Disposition::DEFAULT);

    let fps_milli = {
        let rate = stream.avg_frame_rate();
        if rate.denominator() == 0 {
            0
        } else {
            ((rate.numerator() as f64 / rate.denominator() as f64) * 1000.0).round() as u32
        }
    };

    // AVCodecParameters.width/height/ch_layout.nb_channels/sample_rate/bit_rate —
    // FFmpeg 8.x (versao empacotada, ver ffmpeg-android-maker) so tem
    // ch_layout (AVChannelLayout), o campo legado `channels` foi removido.
    let (width, height, channels, sample_rate, stream_bit_rate) = unsafe {
        let p = params.as_ptr();
        ((*p).width as u32, (*p).height as u32, (*p).ch_layout.nb_channels as u32, (*p).sample_rate as u32, (*p).bit_rate)
    };

    let (width, height) = match kind {
        TrackKind::Video => (width, height),
        _ => (0, 0),
    };
    let (channels, sample_rate) = match kind {
        TrackKind::Audio => (channels, sample_rate),
        _ => (0, 0),
    };
    let fps_milli = match kind {
        TrackKind::Video => fps_milli,
        _ => 0,
    };

    TrackInfo {
        kind,
        ordinal,
        codec,
        language,
        title,
        width,
        height,
        fps_milli,
        channels,
        sample_rate,
        bit_rate: stream_bit_rate,
        is_default,
    }
}
