//! Decisão compartilhada entre os 5 protocolos (smb/ftp/sftp/webdav/nfs) para
//! "esta pasta tem alguma mídia reproduzível, recursivamente?" — usada pela poda
//! de pastas vazias na listagem de arquivos (ver `NetworkFolderProber.kt` do lado
//! Kotlin, e `docs/phases` para o histórico da decisão).
//!
//! Cada protocolo implementa sua própria `scan_has_media` reusando a MESMA conexão
//! já aberta (uma por chamada, não uma por nível de pasta visitado — diferente de
//! chamar `list_directory` repetidamente do lado Kotlin, que reconectaria a cada
//! subpasta) e faz sua própria varredura iterativa (pilha explícita, não recursão
//! assíncrona — evita ter que nomear o tipo de "tree"/sessão de cada crate cliente
//! numa assinatura de função recursiva). Só a decisão "isto é um arquivo de mídia?",
//! o formato do resultado e o freio de segurança (deadline) moram aqui.
//!
//! Sem limite de profundidade fixo: um cap arbitrário (ex. 2 níveis) erraria para
//! estruturas de pastas organizadas em várias subpastas, escondendo pastas com
//! conteúdo de verdade. O freio contra árvores patologicamente grandes é um
//! deadline de parede (tempo real), não uma contagem de níveis.

use std::time::{Duration, Instant};

/// Extensões de mídia reproduzível — MIRROR de `DirectoryLister.kt`
/// (`VIDEO_EXTENSIONS`/`AUDIO_EXTENSIONS`/`IMAGE_EXTENSIONS`, app/src/main/java/
/// com/tucavr/filebrowser/DirectoryLister.kt). Mesma obrigação de sincronia manual
/// já documentada para o enum `ScreenMode` (ver CLAUDE.md) — uma extensão nova
/// suportada precisa ser adicionada nos dois lugares, ou a poda de pastas de rede
/// passa a esconder pastas que na verdade têm mídia daquele tipo novo.
const MEDIA_EXTENSIONS: &[&str] = &[
    // vídeo
    "mp4", "mkv", "avi", "mov", "webm", "flv", "ts", "m3u8", "mpd", "3gp", "wmv", "mpg", "mpeg",
    // áudio
    "mp3", "flac", "aac", "ogg", "wav", "m4a", "opus", "wma",
    // imagem
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic",
];

/// Deadline de segurança por varredura de subpasta (decisão com o usuário: "tempo
/// máximo, ex. 10s" em vez de um limite de profundidade fixo — ver comentário do
/// módulo). Se estourar, a varredura assume que a pasta PODE ter conteúdo em vez
/// de travar a navegação indefinidamente numa árvore patologicamente grande.
pub const SCAN_DEADLINE: Duration = Duration::from_secs(10);

pub fn deadline_from_now() -> Instant {
    Instant::now() + SCAN_DEADLINE
}

pub fn is_media_filename(name: &str) -> bool {
    match name.rsplit_once('.') {
        Some((_, ext)) if !ext.is_empty() => MEDIA_EXTENSIONS.contains(&ext.to_ascii_lowercase().as_str()),
        _ => false,
    }
}

/// Resultado de uma varredura recursiva de pasta.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ScanResult {
    pub has_media: bool,
    /// false = a varredura bateu no [SCAN_DEADLINE] e ASSUMIU que a pasta tem
    /// conteúdo em vez de concluir — o chamador (Kotlin) usa isso pra dar um TTL de
    /// revalidação mais curto a esse resultado (não é uma resposta definitiva).
    pub completed_fully: bool,
}

impl ScanResult {
    pub fn found() -> Self {
        Self { has_media: true, completed_fully: true }
    }

    pub fn exhausted_empty() -> Self {
        Self { has_media: false, completed_fully: true }
    }

    pub fn timed_out_assume_has_media() -> Self {
        Self { has_media: true, completed_fully: false }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recognizes_video_audio_and_image_extensions_case_insensitively() {
        assert!(is_media_filename("movie.mp4"));
        assert!(is_media_filename("MOVIE.MKV"));
        assert!(is_media_filename("song.flac"));
        assert!(is_media_filename("photo.HEIC"));
    }

    #[test]
    fn rejects_non_media_and_extensionless_names() {
        assert!(!is_media_filename("readme.txt"));
        assert!(!is_media_filename("archive.zip"));
        assert!(!is_media_filename("no_extension"));
        assert!(!is_media_filename(".hidden"));
        assert!(!is_media_filename(""));
    }

    #[test]
    fn found_and_exhausted_and_timed_out_report_the_expected_flags() {
        assert_eq!(ScanResult::found(), ScanResult { has_media: true, completed_fully: true });
        assert_eq!(ScanResult::exhausted_empty(), ScanResult { has_media: false, completed_fully: true });
        assert_eq!(ScanResult::timed_out_assume_has_media(), ScanResult { has_media: true, completed_fully: false });
    }
}
