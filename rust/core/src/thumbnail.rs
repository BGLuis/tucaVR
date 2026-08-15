use ffmpeg_next as ffmpeg;
use ffmpeg::format::Pixel;
use ffmpeg::software::scaling::{context::Context as ScalingContext, flag::Flags};

use crate::demuxer::{Demuxer, ReadPacketOutcome};

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

// Achado real em producao (nao teorico): a maioria dos videos VR 180
// 8K testados tem fade-in/intro preto de MAIS de 1s (comum pra nao ofuscar
// quem esta de headset) — o seek acima nao bastava e ~73% dos thumbnails
// de rede saiam totalmente pretos. `generate()` agora verifica o resultado
// (ver `is_effectively_black`) e tenta estes pontos mais adiante antes de
// aceitar — no maximo 2 tentativas extras, sem loop indefinido.
const FALLBACK_SEEK_TARGETS_US: [i64; 2] = [5_000_000, 15_000_000];

/// Detector barato de frame "inutil" (preto ou quase): amostra 1 a cada 8
/// pixels (nao o buffer inteiro) e compara a luminancia media contra um
/// limiar bem escuro. So usado por `generate()` (o thumbnail "de vitrine",
/// que deve representar o conteudo) — `generate_strip()` NAO usa isto, um
/// frame preto no MEIO do preview de arrasto e uma resposta correta (pode
/// ser uma transicao de cena de verdade), diferente do frame inicial.
fn is_effectively_black(rgba: &[u8]) -> bool {
    const BRIGHTNESS_THRESHOLD: u64 = 12; // 0-255, bem escuro mesmo
    let mut sum: u64 = 0;
    let mut count: u64 = 0;
    for px in rgba.chunks_exact(4).step_by(8) {
        sum += px[0] as u64 + px[1] as u64 + px[2] as u64;
        count += 1;
    }
    count == 0 || (sum / (count * 3)) < BRIGHTNESS_THRESHOLD
}

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
    let mut image = decode_and_scale(&mut demuxer, &mut decoder, video_stream_index, max_width, max_height);

    for &fallback_us in FALLBACK_SEEK_TARGETS_US.iter() {
        let is_useless = image.as_ref().map(|img| is_effectively_black(&img.rgba)).unwrap_or(true);
        if !is_useless {
            break;
        }
        if demuxer.input_context.seek(fallback_us, ..fallback_us).is_err() {
            continue;
        }
        // Flush obrigatorio apos o seek (mesmo motivo documentado em
        // generate_strip): sem isto o decoder tenta usar frames de
        // referencia de antes do seek, produzindo lixo em vez de so falhar.
        decoder.flush();
        image = decode_and_scale(&mut demuxer, &mut decoder, video_stream_index, max_width, max_height);
    }

    image
}

/// Nucleo compartilhado por `generate()` (1 frame) e `generate_strip()`
/// (N frames): decodifica o proximo frame decodificavel a partir da
/// posicao ATUAL do `Demuxer` (o chamador ja fez o seek) e escala pra RGBA.
fn decode_and_scale(
    demuxer: &mut Demuxer,
    decoder: &mut ffmpeg::decoder::Video,
    video_stream_index: usize,
    max_width: u32,
    max_height: u32,
) -> Option<ThumbnailImage> {
    let mut decoded = ffmpeg::frame::Video::empty();
    let mut got_frame = false;
    let mut tried = 0u32;

    while tried < MAX_PACKETS_TRIED {
        let (stream_index, packet) = match demuxer.read_packet() {
            ReadPacketOutcome::Packet(idx, packet) => (idx, packet),
            ReadPacketOutcome::Eof | ReadPacketOutcome::Error(_) => break,
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

/// Trilha esparsa de thumbnails (um a cada `interval_secs`) pra preview de
/// arrasto no seekbar (T-seek-ux). `rgba` concatena `count` frames de
/// `width * height * 4` bytes cada, na ordem do arquivo — a posicao do
/// frame `i` e sempre `(i + 1) * interval_secs`, entao o chamador nao
/// precisa de um array de timestamps a parte.
pub struct ThumbnailStrip {
    pub rgba: Vec<u8>,
    pub count: usize,
    pub width: u32,
    pub height: u32,
}

/// Gera `generate()` repetidas vezes reaproveitando o MESMO `Demuxer` (e
/// por tabela, a MESMA conexao de rede) entre os seeks internos, em vez de
/// reabrir a fonte a cada miniatura — mesmo raciocinio do `ConnectionCache`
/// em `demuxer.rs`, so que aqui a reuso e trivial porque tudo acontece
/// dentro de uma unica chamada sincrona (um `Demuxer::new()` so). Posicoes
/// onde o decode falha (keyframe inalcancavel, etc.) ficam com o frame
/// zerado (preto) em vez de encolher o array — mantem a aritmetica de
/// posicao `(i+1)*interval_secs` valida pro chamador mesmo com falhas
/// pontuais no meio, e casa com o contrato de silencio ja usado em
/// `generate()` (falha pontual não vira popup de erro).
// Decode de thumbnail e por SOFTWARE (sem MediaCodec, ver comentario em
// `generate()`) e SEMPRE na resolucao NATIVA do frame — o downscale so
// acontece DEPOIS, no sws_scale. Pra 1 chamada (generate()) isso e caro mas
// tolneravel; `generate_strip()` repete esse decode caro dezenas/centenas
// de vezes (uma por posicao da trilha). Em 8K (7680x4320) cada frame
// decodificado por software fica na casa de dezenas de MB, e isso roda
// CONCORRENTE com a reproducao de verdade (decode de HARDWARE) do mesmo
// arquivo — bug real encontrado em hardware: app abortava (provavel OOM,
// sem mensagem de panic limpa no logcat — condizente com falha de alocacao,
// que aborta direto sem passar pelo panic hook) ao segurar/arrastar o
// tracker num video SFTP 8K60. Corte defensivo: acima do limite, nao gera
// trilha nenhuma (mesmo contrato de silencio do resto deste modulo) em vez
// de arriscar o crash. 4K (3840x2160) cobre a esmagadora maioria dos casos
// reais de uso deste player.
const MAX_STRIP_SOURCE_PIXELS: u32 = 3840 * 2160;

pub fn generate_strip(path: &str, interval_secs: f64, max_width: u32, max_height: u32) -> Option<ThumbnailStrip> {
    if max_width == 0 || max_height == 0 || interval_secs <= 0.0 {
        return None;
    }

    ffmpeg::init().ok()?;
    let mut demuxer = Demuxer::new(path).ok()?;
    let video_stream_index = demuxer.video_stream_index?;
    let stream = demuxer.input_context.stream(video_stream_index)?;
    let codec_context = ffmpeg::codec::context::Context::from_parameters(stream.parameters()).ok()?;
    let mut decoder = codec_context.decoder().video().ok()?;

    if decoder.width().saturating_mul(decoder.height()) > MAX_STRIP_SOURCE_PIXELS {
        return None;
    }

    let duration_secs = demuxer.input_context.duration() as f64 / 1_000_000.0;
    let count = (duration_secs / interval_secs).floor() as usize;
    if count == 0 {
        return None;
    }

    let frame_len = (max_width * max_height * 4) as usize;
    let mut rgba = vec![0u8; count * frame_len];
    for (i, chunk) in rgba.chunks_exact_mut(frame_len).enumerate() {
        let target_secs = (i + 1) as f64 * interval_secs;
        let target_us = (target_secs * 1_000_000.0) as i64;
        if demuxer.input_context.seek(target_us, ..target_us).is_err() {
            continue;
        }
        // Flush obrigatorio apos o seek: sem isto, o decoder tenta usar
        // frames de referencia do GOP anterior (de antes do seek) pra
        // decodificar o novo, produzindo lixo/artefatos em vez de so
        // falhar — `generate()` nunca precisou disto porque so faz UM seek
        // e decoder comeca zerado.
        decoder.flush();
        if let Some(image) = decode_and_scale(&mut demuxer, &mut decoder, video_stream_index, max_width, max_height) {
            chunk.copy_from_slice(&image.rgba);
        }
        // Mitigacao de pico de memoria (nao tuning de performance): da espaco pra
        // reproducao de verdade antes do proximo decode caro de software.
        std::thread::sleep(std::time::Duration::from_millis(100));
    }

    Some(ThumbnailStrip { rgba, count, width: max_width, height: max_height })
}
