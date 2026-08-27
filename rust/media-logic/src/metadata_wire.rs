//! Shape + serializacao dos metadados de midia (T13.1) que cruzam a
//! fronteira C ABI como uma unica string. Extracao via FFmpeg fica em
//! `core::metadata` (nao compila no host); so o encode mora aqui porque e a
//! parte pura o suficiente pra testar sem NDK — e e exatamente onde um bug
//! de formatacao (tab/newline embutido num titulo) apareceria.
//!
//! Gramatica (linhas separadas por '\n', campos por '\t', discriminador de
//! tipo de registro no primeiro campo pra poder crescer sem quebrar o
//! parser do lado Kotlin):
//!   F <container> <container_long> <duration_us> <bit_rate>
//!   M <tag_key> <tag_value>              (0..N)
//!   T <kind> <ordinal> <codec> <language> <title> <width> <height>
//!     <fps_milli> <channels> <sample_rate> <bit_rate> <is_default 0|1>
//!
//! So inteiros e strings sanitizadas na wire — nenhum float (fps vira
//! fps_milli = fps*1000), pra nao depender de formatacao/locale de f32/f64
//! entre Rust e Kotlin.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum TrackKind {
    Video,
    Audio,
    Subtitle,
}

impl TrackKind {
    fn wire_name(self) -> &'static str {
        match self {
            TrackKind::Video => "video",
            TrackKind::Audio => "audio",
            TrackKind::Subtitle => "subtitle",
        }
    }
}

pub struct TrackInfo {
    pub kind: TrackKind,
    pub ordinal: usize,
    pub codec: String,
    pub language: String,
    pub title: String,
    pub width: u32,
    pub height: u32,
    pub fps_milli: u32,
    pub channels: u32,
    pub sample_rate: u32,
    pub bit_rate: i64,
    pub is_default: bool,
}

pub struct MediaMetadata {
    pub container: String,
    pub container_long: String,
    pub duration_us: i64,
    pub bit_rate: i64,
    pub format3d_index: u32,
    pub detection_confidence: u8,
    pub tags: Vec<(String, String)>,
    pub tracks: Vec<TrackInfo>,
}

// '\t'/'\n'/'\r' quebrariam a gramatica se vazassem de um titulo/tag de
// usuario — trocados por espaco em vez de escapados, simplicidade > fidelidade
// exata de um caractere de controle que nao tem por que aparecer num titulo.
fn sanitize_field(s: &str) -> String {
    s.chars()
        .map(|c| if c == '\t' || c == '\n' || c == '\r' { ' ' } else { c })
        .collect()
}

pub fn encode(meta: &MediaMetadata) -> String {
    let mut lines = Vec::with_capacity(1 + meta.tags.len() + meta.tracks.len());

    lines.push(format!(
        "F\t{}\t{}\t{}\t{}\t{}\t{}",
        sanitize_field(&meta.container),
        sanitize_field(&meta.container_long),
        meta.duration_us,
        meta.bit_rate,
        meta.format3d_index,
        meta.detection_confidence,
    ));

    for (key, value) in &meta.tags {
        lines.push(format!("M\t{}\t{}", sanitize_field(key), sanitize_field(value)));
    }

    for track in &meta.tracks {
        lines.push(format!(
            "T\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            track.kind.wire_name(),
            track.ordinal,
            sanitize_field(&track.codec),
            sanitize_field(&track.language),
            sanitize_field(&track.title),
            track.width,
            track.height,
            track.fps_milli,
            track.channels,
            track.sample_rate,
            track.bit_rate,
            if track.is_default { 1 } else { 0 },
        ));
    }

    lines.join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> MediaMetadata {
        MediaMetadata {
            container: "matroska".to_string(),
            container_long: "Matroska / WebM".to_string(),
            duration_us: 7_384_000_000,
            bit_rate: 25_000_000,
            format3d_index: 0,
            detection_confidence: 0,
            tags: vec![("title".to_string(), "Big Buck Bunny".to_string())],
            tracks: vec![
                TrackInfo {
                    kind: TrackKind::Video,
                    ordinal: 0,
                    codec: "hevc".to_string(),
                    language: String::new(),
                    title: String::new(),
                    width: 3840,
                    height: 2160,
                    fps_milli: 60_000,
                    channels: 0,
                    sample_rate: 0,
                    bit_rate: 22_000_000,
                    is_default: true,
                },
                TrackInfo {
                    kind: TrackKind::Audio,
                    ordinal: 1,
                    codec: "eac3".to_string(),
                    language: "por".to_string(),
                    title: String::new(),
                    width: 0,
                    height: 0,
                    fps_milli: 0,
                    channels: 6,
                    sample_rate: 48_000,
                    bit_rate: 448_000,
                    is_default: false,
                },
            ],
        }
    }

    #[test]
    fn encode_full_media_info_matches_expected_wire() {
        let expected = "F\tmatroska\tMatroska / WebM\t7384000000\t25000000\t0\t0\n\
                         M\ttitle\tBig Buck Bunny\n\
                         T\tvideo\t0\thevc\t\t\t3840\t2160\t60000\t0\t0\t22000000\t1\n\
                         T\taudio\t1\teac3\tpor\t\t0\t0\t0\t6\t48000\t448000\t0";
        assert_eq!(encode(&sample()), expected);
    }

    #[test]
    fn encode_with_no_tags_or_tracks_is_just_the_format_line() {
        let meta = MediaMetadata {
            container: "mp4".to_string(),
            container_long: "MP4".to_string(),
            duration_us: 0,
            bit_rate: 0,
            format3d_index: 1,
            detection_confidence: 1,
            tags: vec![],
            tracks: vec![],
        };
        assert_eq!(encode(&meta), "F\tmp4\tMP4\t0\t0\t1\t1");
    }

    #[test]
    fn sanitize_strips_tabs_and_newlines_from_titles_and_tags() {
        let mut meta = sample();
        meta.tags = vec![("title".to_string(), "Line1\nLine2\tTabbed\r".to_string())];
        meta.tracks.truncate(1);
        meta.tracks[0].title = "Weird\tTitle".to_string();

        let wire = encode(&meta);
        assert!(!wire.contains("Line1\nLine2"));
        assert!(wire.contains("M\ttitle\tLine1 Line2 Tabbed "));
        assert!(wire.contains("Weird Title"));
    }

    #[test]
    fn sanitize_preserves_unicode() {
        assert_eq!(sanitize_field("Português — 日本語"), "Português — 日本語");
    }
}
