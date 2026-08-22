// subtitle_loader.rs
//
// Carregamento e gerenciamento de legendas externas e embutidas na camada core.
// Integra detecção de encoding (chardetng) e parsers SRT/WebVTT da crate media-logic.

use media_logic::subtitle::{detect_and_decode, parse_srt, parse_vtt, SubtitleEntry};
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

    // Identifica formato por extensão ou conteúdo inicial
    let is_vtt = path.to_lowercase().ends_with(".vtt") || decoded_text.trim_start().starts_with("WEBVTT");

    let entries = if is_vtt {
        parse_vtt(&decoded_text)
    } else {
        parse_srt(&decoded_text)
    };

    Ok(entries)
}

/// Lê o conteúdo bruto de um arquivo local ou remoto.
fn read_file_bytes(path: &str) -> Result<Vec<u8>, String> {
    if path.starts_with("http://") || path.starts_with("https://") {
        let resp = reqwest::blocking::get(path).map_err(|e| format!("HTTP fetch error: {e}"))?;
        let bytes = resp.bytes().map_err(|e| format!("HTTP bytes error: {e}"))?;
        Ok(bytes.to_vec())
    } else if let Some(target) = protocols::smb::SmbTarget::from_internal(path) {
        let mut source = protocols::smb::SmbFileSource::open(&target)?;
        let size = source.size();
        if size > 10 * 1024 * 1024 {
            return Err("Subtitle file too large (>10MB)".into());
        }
        let mut buf = vec![0u8; size as usize];
        use protocols::prefetch::RangeSource;
        source.read_range(0, &mut buf)?;
        Ok(buf)
    } else if let Some(target) = protocols::sftp::SftpTarget::from_internal(path) {
        let mut source = protocols::sftp::SftpFileSource::open(&target)?;
        let size = source.size();
        if size > 10 * 1024 * 1024 {
            return Err("Subtitle file too large (>10MB)".into());
        }
        let mut buf = vec![0u8; size as usize];
        use protocols::prefetch::RangeSource;
        source.read_range(0, &mut buf)?;
        Ok(buf)
    } else if let Some(target) = protocols::nfs::NfsTarget::from_internal(path) {
        let mut source = protocols::nfs::NfsFileSource::open(&target)?;
        let size = source.size();
        if size > 10 * 1024 * 1024 {
            return Err("Subtitle file too large (>10MB)".into());
        }
        let mut buf = vec![0u8; size as usize];
        use protocols::prefetch::RangeSource;
        source.read_range(0, &mut buf)?;
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
        let candidate_extensions = &["srt", "vtt", "pt-BR.srt", "en.srt", "pt.srt", "es.srt"];

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
