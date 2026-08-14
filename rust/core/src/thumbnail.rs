use ffmpeg_next as ffmpeg;
use ffmpeg::format::Pixel;
use ffmpeg::software::scaling::{context::Context as ScalingContext, flag::Flags};

use crate::demuxer::Demuxer;

pub struct ThumbnailImage {
    /// Pixels RGBA8 empacotados, `width * height * 4` bytes, sem padding de
    /// linha (o padding de linesize do frame decodificado ja foi descartado
    /// linha a linha — ver `generate` abaixo).
    pub rgba: Vec<u8>,
    pub width: u32,
    pub height: u32,
}

// Limite de pacotes de video tentados antes de desistir: um arquivo
// corrompido ou sem keyframe alcancavel nao pode travar isto indefinidamente
// — esta e uma chamada SINCRONA, uma por item de video visivel na listagem
// de rede (ver VideoMetadataReader/NetworkThumbnailGenerator do lado
// Kotlin).
const MAX_PACKETS_TRIED: u32 = 500;

// Seek de 1s pra dentro do arquivo antes de decodificar: muitos videos tem
// frame(s) preto(s)/fade-in logo no inicio, e um thumbnail totalmente preto
// nao ajuda o usuario a reconhecer o arquivo. Best-effort: se o seek falhar
// (formato/protocolo que nao suporta bem), decodifica a partir do que o
// demuxer conseguir entregar mesmo assim.
const SEEK_TARGET_US: i64 = 1_000_000;

/// Gera um thumbnail RGBA decodificando UM frame de video, por software
/// (ffmpeg puro via `sws_scale`, SEM MediaCodec) — decode de hardware exige
/// Surface/AHardwareBuffer/GL, peso desnecessario para um frame estatico e
/// pouco frequente (o lado Kotlin cacheia em disco, ver
/// `NetworkThumbnailGenerator.kt`). Reaproveita o mesmo `Demuxer` (e por
/// tabela, o mesmo I/O customizado SMB/FTP/SFTP/HTTPS) usado pela reproducao
/// de verdade — ver demuxer.rs.
///
/// `path` e a URI interna ja resolvida
/// (`protocols::{smb,ftp,sftp}::Target::to_internal()`). Retorna `None` em
/// qualquer falha (sem faixa de video, decode falhou, arquivo inacessivel,
/// etc.) — mesmo contrato de silencio do `ThumbnailGenerator` local: a UI so
/// deixa de mostrar a miniatura, sem popup de erro.
pub fn generate(path: &str, max_width: u32, max_height: u32) -> Option<ThumbnailImage> {
    if max_width == 0 || max_height == 0 {
        return None;
    }

    ffmpeg::init().ok()?;
    let mut demuxer = Demuxer::new(path).ok()?;
    let video_stream_index = demuxer.video_stream_index?;

    let stream = demuxer.input_context.stream(video_stream_index)?;
    let codec_context = ffmpeg::codec::context::Context::from_parameters(stream.parameters()).ok()?;
    let mut decoder = codec_context.decoder().video().ok()?;

    let _ = demuxer.input_context.seek(SEEK_TARGET_US, ..SEEK_TARGET_US);

    let mut decoded = ffmpeg::frame::Video::empty();
    let mut got_frame = false;
    let mut tried = 0u32;

    while tried < MAX_PACKETS_TRIED {
        let (stream_index, packet) = match demuxer.read_packet() {
            Some(p) => p,
            None => break,
        };
        if stream_index != video_stream_index {
            continue;
        }
        tried += 1;
        if decoder.send_packet(&packet).is_err() {
            continue;
        }
        if decoder.receive_frame(&mut decoded).is_ok() {
            got_frame = true;
            break;
        }
    }
    if !got_frame {
        return None;
    }

    let src_w = decoded.width();
    let src_h = decoded.height();
    if src_w == 0 || src_h == 0 {
        return None;
    }

    let mut scaler = ScalingContext::get(
        decoded.format(),
        src_w,
        src_h,
        Pixel::RGBA,
        max_width,
        max_height,
        Flags::BILINEAR,
    ).ok()?;

    let mut scaled = ffmpeg::frame::Video::empty();
    scaler.run(&decoded, &mut scaled).ok()?;

    let width = max_width as usize;
    let height = max_height as usize;
    let stride = scaled.stride(0);
    let data = scaled.data(0);

    // `data(0)` vem com o linesize/stride que o FFmpeg pode alinhar/arredondar
    // pra cima — mesmo cuidado documentado em audio_decoder.rs pro buffer de
    // audio. Copia linha a linha descartando o padding do fim de cada linha,
    // em vez de usar o buffer inteiro cru.
    let mut rgba = Vec::with_capacity(width * height * 4);
    for row in 0..height {
        let start = row * stride;
        let end = start + width * 4;
        if end > data.len() {
            return None;
        }
        rgba.extend_from_slice(&data[start..end]);
    }

    Some(ThumbnailImage { rgba, width: max_width, height: max_height })
}
