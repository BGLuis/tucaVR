use ffmpeg_next as ffmpeg;
use ffmpeg::format::Pixel;
use ffmpeg::software::scaling::{context::Context as ScalingContext, flag::Flags};
use std::sync::atomic::{AtomicBool, Ordering};

use crate::decoder::{HwDecoder, RawFrame};
use crate::demuxer::{Demuxer, ReadPacketOutcome};
use ndk::media::media_format::MediaFormat;

// generate_strip()/generate_strip_hw() sao chamadas SINCRONAS e bloqueantes
// que rodam por minutos num arquivo grande (uma decodificacao por posicao da
// trilha inteira) — o cancelamento cooperativo do Kotlin (Job.cancel()) nao
// interrompe uma chamada JNI ja em andamento, entao o cancelamento real
// precisa ser um flag do lado Rust checado a cada posicao. Ver
// `cancel_strip_generation`/`bridge::cancel_thumbnail_strip_generation`.
static STRIP_CANCELLED: AtomicBool = AtomicBool::new(false);

pub fn cancel_strip_generation() {
    STRIP_CANCELLED.store(true, Ordering::Relaxed);
}

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

// T13.3: alvo primario e ~10% da duracao (doc da fase 0.2, secao 13) — melhor
// heuristica que um instante fixo pra pular intros/fade-in em videos longos
// (comum em VR180 8K, ver achado abaixo). Clampado em [1s, 30s]: sem isso um
// clipe de 5s pediria seek pra 0.5s (quase nada) e um filme de 2h pediria
// seek pra 12min (decode desnecessariamente longe do inicio so pra gerar
// uma miniatura). Duracao desconhecida (`duration_us <= 0`, streams sem
// duration confiavel) cai no fallback fixo de 1s de sempre.
const MIN_SEEK_TARGET_US: i64 = 1_000_000;
const MAX_SEEK_TARGET_US: i64 = 30_000_000;

fn primary_seek_target_us(duration_us: i64) -> i64 {
    if duration_us <= 0 {
        return MIN_SEEK_TARGET_US;
    }
    (duration_us / 10).clamp(MIN_SEEK_TARGET_US, MAX_SEEK_TARGET_US)
}

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

    let primary_target_us = primary_seek_target_us(demuxer.input_context.duration());
    let _ = demuxer.input_context.seek(primary_target_us, ..primary_target_us);
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
    STRIP_CANCELLED.store(false, Ordering::Relaxed);

    ffmpeg::init().ok()?;
    let mut demuxer = Demuxer::new(path).ok()?;
    let video_stream_index = demuxer.video_stream_index?;
    let stream = demuxer.input_context.stream(video_stream_index)?;
    let codec_context = ffmpeg::codec::context::Context::from_parameters(stream.parameters()).ok()?;
    let mut decoder = codec_context.decoder().video().ok()?;

    if decoder.width().saturating_mul(decoder.height()) > MAX_STRIP_SOURCE_PIXELS {
        // Acima do limite seguro pra decode por SOFTWARE (ver comentario da
        // constante acima) — tenta decode por HARDWARE em vez de desistir.
        // MediaCodec e um ASIC dedicado, nao compete por RAM/CPU geral do
        // jeito que o software compete com a reproducao real concorrente.
        return generate_strip_hw(path, interval_secs, max_width, max_height);
    }

    let duration_secs = demuxer.input_context.duration() as f64 / 1_000_000.0;
    let count = (duration_secs / interval_secs).floor() as usize;
    if count == 0 {
        return None;
    }

    let frame_len = (max_width * max_height * 4) as usize;
    let mut rgba = vec![0u8; count * frame_len];
    for (i, chunk) in rgba.chunks_exact_mut(frame_len).enumerate() {
        if STRIP_CANCELLED.load(Ordering::Relaxed) {
            break;
        }
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

    // Cancelada antes de terminar (usuario soltou o arrasto) — nao devolve um
    // resultado parcial (quase todo preto): o lado Kotlin cacheia (memoria e
    // disco) qualquer retorno nao-nulo daqui pra sempre, entao um parcial
    // cacheado ficaria preso mostrando o mesmo preview quebrado pro resto da
    // sessao. Devolver None deixa o PROXIMO arrasto tentar de novo do zero.
    if STRIP_CANCELLED.load(Ordering::Relaxed) {
        return None;
    }

    Some(ThumbnailStrip { rgba, count, width: max_width, height: max_height })
}

/// Trilha por decode de HARDWARE (MediaCodec, ver `crate::decoder::HwDecoder`)
/// pra fontes acima de `MAX_STRIP_SOURCE_PIXELS` — mesma logica de
/// `generate_strip` (um seek + decode por posicao), so trocando o decoder e
/// a conversao de pixel. `configure` e chamado com `window: None`: sem
/// Surface, o MediaCodec entrega bytes YUV legiveis por CPU direto (ver
/// `RawFrame`/`decode_one_frame_raw`), evitando precisar de um contexto
/// GL/Vulkan headless soo pra ler os pixels de volta.
///
/// Recria o `HwDecoder` inteiro a cada posicao da trilha em vez de
/// `flush()`: e o UNICO padrao ja validado em hardware neste projeto pra
/// "seek durante decode MediaCodec" (`PlaybackController::seek` tambem
/// recria a sessao inteira, ver playback.rs) — `flush()` nunca foi
/// exercitado aqui, e o custo de recriar (dezenas de ms por posicao, mais
/// caro que flush) e aceitavel pra uma trilha gerada em background.
fn generate_strip_hw(path: &str, interval_secs: f64, max_width: u32, max_height: u32) -> Option<ThumbnailStrip> {
    STRIP_CANCELLED.store(false, Ordering::Relaxed);
    let mut demuxer = Demuxer::new(path).ok()?;
    let video_stream_index = demuxer.video_stream_index?;
    let stream = demuxer.input_context.stream(video_stream_index)?;
    let codec_id = stream.parameters().id();
    let (mime, video_is_nal_based) = crate::decoder::mime_for_codec_id(codec_id).ok()?;

    let codec_context = ffmpeg::codec::context::Context::from_parameters(stream.parameters()).ok()?;
    let video_decoder_ctx = codec_context.decoder().video().ok()?;
    let src_width = video_decoder_ctx.width();
    let src_height = video_decoder_ctx.height();
    if src_width == 0 || src_height == 0 {
        return None;
    }

    let sps_pps = demuxer.get_video_extradata().and_then(|ed| match codec_id {
        ffmpeg::codec::Id::HEVC => crate::hevc::extract_vps_sps_pps(&ed),
        ffmpeg::codec::Id::H264 => crate::h264::extract_sps_pps(&ed),
        ffmpeg::codec::Id::AV1 => crate::av1::extract_config_obus(&ed),
        // VP9 nao tem SPS/PPS/config OBU em banda (mesmo motivo de
        // playback.rs).
        _ => None,
    });

    let duration_secs = demuxer.input_context.duration() as f64 / 1_000_000.0;
    let count = (duration_secs / interval_secs).floor() as usize;
    if count == 0 {
        return None;
    }

    let frame_len = (max_width * max_height * 4) as usize;
    let mut rgba = vec![0u8; count * frame_len];
    let mut ok_count = 0usize;

    for (i, chunk) in rgba.chunks_exact_mut(frame_len).enumerate() {
        if STRIP_CANCELLED.load(Ordering::Relaxed) {
            break;
        }
        let target_secs = (i + 1) as f64 * interval_secs;
        let target_us = (target_secs * 1_000_000.0) as i64;
        if demuxer.input_context.seek(target_us, ..target_us).is_err() {
            continue;
        }

        let mut format = MediaFormat::new();
        format.set_str("mime", mime);
        format.set_i32("width", src_width as i32);
        format.set_i32("height", src_height as i32);
        let mut video_decoder = match HwDecoder::new_configured_and_started(mime, &format, None) {
            Ok(d) => d,
            Err(e) => {
                log_thumb_once(&format!("generate_strip_hw: setup falhou: {e}"));
                continue;
            }
        };
        if let Some(sps) = &sps_pps {
            let _ = video_decoder.decode_packet(sps, 0, 2, |_| true, || {}, || true);
        }

        match decode_and_scale_hw(
            &mut demuxer,
            &mut video_decoder,
            video_stream_index,
            video_is_nal_based,
            max_width,
            max_height,
        ) {
            Some(image) => {
                chunk.copy_from_slice(&image.rgba);
                ok_count += 1;
            }
            None => log_thumb_once("generate_strip_hw: decode_and_scale_hw retornou None"),
        }
        let _ = video_decoder.stop();
    }

    let cancelled = STRIP_CANCELLED.load(Ordering::Relaxed);
    unsafe {
        let tag = std::ffi::CString::new("VRPlayer_Rust").unwrap();
        let msg = std::ffi::CString::new(format!(
            "generate_strip_hw: {ok_count}/{count} posicoes com sucesso{}",
            if cancelled { " (cancelada, nao sera cacheada)" } else { "" }
        )).unwrap();
        ndk_sys::__android_log_print(4, tag.as_ptr(), msg.as_ptr());
    }
    // Ver comentario equivalente em generate_strip acima: nao cachear parcial.
    if cancelled {
        return None;
    }

    Some(ThumbnailStrip { rgba, count, width: max_width, height: max_height })
}

fn log_thumb_once(msg: &str) {
    use std::sync::atomic::{AtomicBool, Ordering};
    static LOGGED: AtomicBool = AtomicBool::new(false);
    if LOGGED.swap(true, Ordering::Relaxed) {
        return;
    }
    unsafe {
        let tag = std::ffi::CString::new("VRPlayer_Rust").unwrap();
        let cmsg = std::ffi::CString::new(msg).unwrap();
        ndk_sys::__android_log_print(5, tag.as_ptr(), cmsg.as_ptr());
    }
}

/// Equivalente hardware de `decode_and_scale`: alimenta pacotes ate um
/// frame de saida ficar pronto (decoders com B-frames tem atraso de
/// reordenacao — um pacote so nao basta) ou `MAX_PACKETS_TRIED` estourar.
fn decode_and_scale_hw(
    demuxer: &mut Demuxer,
    video_decoder: &mut HwDecoder,
    video_stream_index: usize,
    video_is_nal_based: bool,
    max_width: u32,
    max_height: u32,
) -> Option<ThumbnailImage> {
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

        let pts = packet.pts().unwrap_or(0);
        let Some(data) = packet.data() else { continue };
        let frame_data = if video_is_nal_based {
            crate::nal::convert_avcc_to_annexb(data)
        } else {
            data.to_vec()
        };

        match video_decoder.decode_one_frame_raw(&frame_data, pts, 0) {
            Ok(Some(raw)) => return raw_frame_to_thumbnail(&raw, max_width, max_height),
            Ok(None) => continue,
            Err(_) => break,
        }
    }
    None
}

/// Interpreta os bytes crus do MediaCodec (`RawFrame`) conforme o
/// color-format negociado em runtime e converte pra RGBA na resolucao
/// minuscula da trilha — ver `media_logic::yuv_convert` pra por que essa
/// logica vive numa crate pura e testavel em vez de aqui direto.
fn raw_frame_to_thumbnail(raw: &RawFrame, max_width: u32, max_height: u32) -> Option<ThumbnailImage> {
    use media_logic::yuv_convert::{yuv420_to_rgba_scaled, YuvLayout, YuvPlanes};

    let layout = YuvLayout::from_android_color_format(raw.color_format)?;
    let width = raw.width.max(0) as u32;
    let height = raw.height.max(0) as u32;
    if width == 0 || height == 0 {
        return None;
    }
    let stride = if raw.stride > 0 { raw.stride as usize } else { width as usize };
    let slice_height = if raw.slice_height > 0 { raw.slice_height as usize } else { height as usize };

    let y_size = stride.checked_mul(slice_height)?;
    if raw.data.len() < y_size {
        return None;
    }
    let y = &raw.data[..y_size];
    let chroma_h = slice_height / 2;

    let planes = match layout {
        YuvLayout::Planar => {
            let chroma_stride = stride / 2;
            let c_size = chroma_stride.checked_mul(chroma_h)?;
            let u_start = y_size;
            let v_start = u_start.checked_add(c_size)?;
            if raw.data.len() < v_start.checked_add(c_size)? {
                return None;
            }
            YuvPlanes {
                y,
                y_stride: stride,
                u: &raw.data[u_start..u_start + c_size],
                u_stride: chroma_stride,
                v: &raw.data[v_start..v_start + c_size],
                v_stride: chroma_stride,
            }
        }
        YuvLayout::SemiPlanar => {
            // UV intercalado (2 bytes por amostra de croma) — mesmo stride
            // em bytes do plano Y.
            let c_size = stride.checked_mul(chroma_h)?;
            let uv_start = y_size;
            if raw.data.len() < uv_start.checked_add(c_size)? {
                return None;
            }
            YuvPlanes {
                y,
                y_stride: stride,
                u: &raw.data[uv_start..uv_start + c_size],
                u_stride: stride,
                v: &[],
                v_stride: 0,
            }
        }
    };

    let rgba = yuv420_to_rgba_scaled(&planes, layout, width, height, max_width, max_height)?;
    Some(ThumbnailImage { rgba, width: max_width, height: max_height })
}
