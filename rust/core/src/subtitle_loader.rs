// subtitle_loader.rs
//
// Carregamento e gerenciamento de legendas externas e embutidas na camada core.
// Integra detecção de encoding (chardetng) e parsers SRT/WebVTT da crate media-logic.

use crate::demuxer::{Demuxer, ReadPacketOutcome};
use ffmpeg_next as ffmpeg;
use media_logic::subtitle::{detect_and_decode, parse_srt, parse_vtt, sanitize_subtitle_text, SubtitleEntry};
use media_logic::subtitle_ass::{
    ass_document_from_mkv_packets, parse_ass_to_entries, MkvAssPacket,
};
use std::path::Path;

#[derive(Debug, Clone)]
pub struct SubtitleTrackInfo {
    pub title: String,
    pub language: String,
    pub is_external: bool,
    pub source_path: Option<String>,
    pub stream_index: Option<usize>,
}

/// Carrega e analisa um arquivo de legenda externo (.srt ou .vtt) a partir de um caminho
/// local ou remoto suportado.
pub fn load_subtitle_from_path(path: &str) -> Result<Vec<SubtitleEntry>, String> {
    let bytes = read_file_bytes(path)?;
    if bytes.is_empty() {
        return Ok(Vec::new());
    }

    let decoded_text = detect_and_decode(&bytes);
    let lower = path.to_lowercase();

    // Identifica formato por extensão ou conteúdo inicial.
    let entries = if lower.ends_with(".ass") || lower.ends_with(".ssa") {
        parse_ass_to_entries(&decoded_text)
    } else if lower.ends_with(".vtt") || decoded_text.trim_start().starts_with("WEBVTT") {
        parse_vtt(&decoded_text)
    } else if decoded_text.trim_start().starts_with("[Script Info]") {
        // `.ass` sem a extensão correta.
        parse_ass_to_entries(&decoded_text)
    } else {
        parse_srt(&decoded_text)
    };

    Ok(entries)
}

/// Carrega uma faixa de legenda **embutida** no container (T7.5 da Fase 0.3 §7).
///
/// Abre um `Demuxer` dedicado (o do playback já foi movido para dentro das
/// threads de sessão), varre todos os pacotes do stream `stream_index` e os
/// converte em `SubtitleEntry`s de texto. Suporta os codecs de texto (SubRip,
/// `AV_CODEC_ID_TEXT`, WebVTT, MOV_TEXT/tx3g, ASS/SSA). Legendas bitmap
/// (`HDMV_PGS_SUBTITLE`, `DVD_SUBTITLE`) ainda não têm caminho de render de
/// textura no C++, então retornam **erro explícito** — em vez de a faixa ficar
/// vazia e silenciosa, que era o bug descrito no relatório
/// (`docs/reports/PHASE-0.3-07-LEGENDAS-ASS-PGS.md` §1).
///
/// Custo: uma leitura completa do container só para as legendas. Aceitável para
/// arquivos locais na v0.3; para rede é caro e pode virar leitura incremental
/// mais tarde.
pub fn load_embedded_subtitle(
    path: &str,
    stream_index: usize,
) -> Result<Vec<SubtitleEntry>, String> {
    let mut demuxer = Demuxer::new(path).map_err(|e| format!("abrir demuxer: {e}"))?;

    let (codec_id, tb_num, tb_den, extradata) = {
        let stream = demuxer
            .input_context
            .stream(stream_index)
            .ok_or_else(|| "stream de legenda inexistente".to_string())?;
        let params = stream.parameters();
        let tb = stream.time_base();
        let extradata = unsafe {
            let p = params.as_ptr();
            if p.is_null() || (*p).extradata.is_null() || (*p).extradata_size <= 0 {
                String::new()
            } else {
                let slice =
                    std::slice::from_raw_parts((*p).extradata, (*p).extradata_size as usize);
                String::from_utf8_lossy(slice).into_owned()
            }
        };
        (
            params.id(),
            tb.numerator() as f64,
            tb.denominator() as f64,
            extradata,
        )
    };

    let to_ms = |ts: i64| -> u64 {
        if tb_den <= 0.0 {
            return 0;
        }
        ((ts as f64) * tb_num / tb_den * 1000.0).max(0.0) as u64
    };

    #[derive(Clone, Copy, PartialEq)]
    enum Kind {
        Text,
        MovText,
        Ass,
    }
    let kind = match codec_id {
        ffmpeg::codec::Id::SUBRIP | ffmpeg::codec::Id::TEXT | ffmpeg::codec::Id::WEBVTT => {
            Kind::Text
        }
        ffmpeg::codec::Id::MOV_TEXT => Kind::MovText,
        ffmpeg::codec::Id::ASS | ffmpeg::codec::Id::SSA => Kind::Ass,
        other => {
            return Err(format!(
                "legenda embutida com codec '{}' ainda não suportada (bitmap PGS/DVD não tem render de textura)",
                other.name()
            ));
        }
    };

    let mut ass_packets: Vec<MkvAssPacket> = Vec::new();
    let mut text_entries: Vec<SubtitleEntry> = Vec::new();
    let mut next_index: u32 = 1;

    loop {
        let (sidx, packet) = match demuxer.read_packet() {
            ReadPacketOutcome::Packet(i, p) => (i, p),
            ReadPacketOutcome::Eof => break,
            ReadPacketOutcome::Error(e) => return Err(format!("erro de leitura: {e}")),
        };
        if sidx != stream_index {
            continue;
        }
        let Some(data) = packet.data() else { continue };
        let start_ms = to_ms(packet.pts().unwrap_or(0));
        let dur = packet.duration();
        let end_ms = if dur > 0 {
            start_ms + to_ms(dur)
        } else {
            start_ms + 4000
        };

        match kind {
            Kind::Ass => {
                ass_packets.push(MkvAssPacket {
                    start_ms,
                    end_ms,
                    body: String::from_utf8_lossy(data)
                        .trim_end_matches('\0')
                        .to_string(),
                });
            }
            Kind::Text | Kind::MovText => {
                // MOV_TEXT (tx3g) tem um prefixo u16 be de comprimento seguido do
                // UTF-8 e caixas de estilo; ficamos só com o texto.
                let text_bytes: &[u8] = if kind == Kind::MovText {
                    if data.len() >= 2 {
                        let n = u16::from_be_bytes([data[0], data[1]]) as usize;
                        &data[2..(2 + n).min(data.len())]
                    } else {
                        &[]
                    }
                } else {
                    data
                };
                let raw = String::from_utf8_lossy(text_bytes);
                let text = sanitize_subtitle_text(
                    raw.trim_matches(|c| c == '\0' || c == '\n' || c == '\r'),
                );
                if !text.is_empty() && end_ms >= start_ms {
                    text_entries.push(SubtitleEntry {
                        index: next_index,
                        start_ms,
                        end_ms,
                        text,
                    });
                    next_index += 1;
                }
            }
        }
    }

    let mut entries = if kind == Kind::Ass {
        let doc = ass_document_from_mkv_packets(&extradata, &ass_packets);
        parse_ass_to_entries(&doc)
    } else {
        text_entries
    };
    entries.sort_by_key(|e| e.start_ms);
    Ok(entries)
}

/// Lê o conteúdo bruto de um arquivo local ou remoto.
fn read_file_bytes(path: &str) -> Result<Vec<u8>, String> {
    use protocols::prefetch::RangeSource;

    if path.starts_with("http://") || path.starts_with("https://") {
        let mut source = protocols::http::HttpsRangeSource::new(path)
            .map_err(|e| format!("HTTP fetch error: {e}"))?;
        let size = source.len().unwrap_or(0);
        if size > 10 * 1024 * 1024 {
            return Err("Subtitle file too large (>10MB)".into());
        }
        let mut buf = vec![0u8; size as usize];
        source.read_range(0, &mut buf).map_err(|e| e.to_string())?;
        Ok(buf)
    } else if let Some(target) = protocols::smb::SmbTarget::from_internal(path) {
        let mut source = protocols::smb::SmbFileSource::open(&target)
            .map_err(|e| format!("SMB open error: {e}"))?;
        let size = source.len().unwrap_or(0);
        if size > 10 * 1024 * 1024 {
            return Err("Subtitle file too large (>10MB)".into());
        }
        let mut buf = vec![0u8; size as usize];
        source.read_range(0, &mut buf).map_err(|e| e.to_string())?;
        Ok(buf)
    } else if let Some(target) = protocols::sftp::SftpTarget::from_internal(path) {
        let mut source = protocols::sftp::SftpFileSource::open(&target)
            .map_err(|e| format!("SFTP open error: {e}"))?;
        let size = source.len().unwrap_or(0);
        if size > 10 * 1024 * 1024 {
            return Err("Subtitle file too large (>10MB)".into());
        }
        let mut buf = vec![0u8; size as usize];
        source.read_range(0, &mut buf).map_err(|e| e.to_string())?;
        Ok(buf)
    } else if let Some(target) = protocols::nfs::NfsTarget::from_internal(path) {
        let mut source = protocols::nfs::NfsFileSource::open(&target)
            .map_err(|e| format!("NFS open error: {e}"))?;
        let size = source.len().unwrap_or(0);
        if size > 10 * 1024 * 1024 {
            return Err("Subtitle file too large (>10MB)".into());
        }
        let mut buf = vec![0u8; size as usize];
        source.read_range(0, &mut buf).map_err(|e| e.to_string())?;
        Ok(buf)
    } else {
        // Arquivo local no sistema de arquivos
        std::fs::read(path).map_err(|e| format!("Erro ao ler arquivo local {path}: {e}"))
    }
}

/// Tenta encontrar arquivos de legendas sidecar associados a um arquivo de vídeo.
/// Ex: para `/path/to/movie.mp4`, busca `/path/to/movie.srt`, `/path/to/movie.vtt`, etc.
pub fn probe_sidecar_subtitles(video_path: &str) -> Vec<SubtitleTrackInfo> {
    let mut results = Vec::new();

    // Se for arquivo local
    let p = Path::new(video_path);
    if let (Some(parent), Some(stem)) = (p.parent(), p.file_stem()) {
        let stem_str = stem.to_string_lossy();
        let candidate_extensions = &[
            "srt", "vtt", "ass", "ssa", "pt-BR.srt", "en.srt", "pt.srt", "es.srt", "pt-BR.ass",
            "en.ass",
        ];

        for ext in candidate_extensions {
            let candidate_name = format!("{stem_str}.{ext}");
            let candidate_path = parent.join(&candidate_name);
            if candidate_path.exists() && candidate_path.is_file() {
                let candidate_str = candidate_path.to_string_lossy().to_string();
                let lang = if ext.contains("pt-BR") || ext.contains("pt") {
                    "pt-BR".to_string()
                } else if ext.contains("en") {
                    "en".to_string()
                } else if ext.contains("es") {
                    "es".to_string()
                } else {
                    String::new()
                };

                results.push(SubtitleTrackInfo {
                    title: candidate_name,
                    language: lang,
                    is_external: true,
                    source_path: Some(candidate_str),
                    stream_index: None,
                });
            }
        }
    }

    results
}
