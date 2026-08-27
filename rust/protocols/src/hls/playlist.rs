//! Parser de playlists M3U8 para HLS (RFC 8216) — T8.1.

use crate::dlna::resolve_url;

#[derive(Debug, Clone, PartialEq)]
pub struct HlsVariant {
    pub bandwidth: u64,
    pub resolution: Option<(u32, u32)>,
    pub codecs: Option<String>,
    pub frame_rate: Option<f32>,
    pub url: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct HlsMasterPlaylist {
    pub variants: Vec<HlsVariant>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HlsKey {
    pub method: String,
    pub uri: Option<String>,
    pub iv: Option<[u8; 16]>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct HlsSegment {
    pub index: usize,
    pub duration: f64,
    pub start_time: f64,
    pub url: String,
    pub byte_range: Option<(u64, u64)>, // (offset, length)
    pub key: Option<HlsKey>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct HlsMediaPlaylist {
    pub target_duration: f64,
    pub total_duration: f64,
    pub is_vod: bool,
    pub segments: Vec<HlsSegment>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum HlsPlaylist {
    Master(HlsMasterPlaylist),
    Media(HlsMediaPlaylist),
}

/// Faz o download da playlist Master HLS e extrai as variantes.
pub fn fetch_and_probe_variants(url: &str) -> Result<Vec<HlsVariant>, String> {
    let client = reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(5))
        .build()
        .map_err(|e| format!("HTTP client error: {e}"))?;

    let resp = client
        .get(url)
        .send()
        .map_err(|e| format!("HTTP request error: {e}"))?;

    if !resp.status().is_success() {
        return Err(format!("HTTP status {}", resp.status()));
    }

    let body = resp.text().map_err(|e| format!("HTTP read error: {e}"))?;

    match parse_playlist(&body, url)? {
        HlsPlaylist::Master(master) => Ok(master.variants),
        HlsPlaylist::Media(_) => Err("A URL fornecida é uma Media Playlist, não Master Playlist".to_string()),
    }
}

/// Faz o parse de uma playlist M3U8 (Master ou Media) a partir do texto e resolve URLs relativas.
pub fn parse_playlist(content: &str, base_url: &str) -> Result<HlsPlaylist, String> {
    let lines: Vec<&str> = content.lines().map(|l| l.trim()).filter(|l| !l.is_empty()).collect();

    if lines.is_empty() || lines[0] != "#EXTM3U" {
        return Err("Arquivo não é uma playlist M3U8 válida (falta cabeçalho #EXTM3U)".to_string());
    }

    let is_master = lines.iter().any(|l| l.starts_with("#EXT-X-STREAM-INF"));

    if is_master {
        parse_master_playlist(&lines, base_url).map(HlsPlaylist::Master)
    } else {
        parse_media_playlist(&lines, base_url).map(HlsPlaylist::Media)
    }
}

fn parse_master_playlist(lines: &[&str], base_url: &str) -> Result<HlsMasterPlaylist, String> {
    let mut variants = Vec::new();
    let mut i = 0;

    while i < lines.len() {
        let line = lines[i];
        if let Some(attr_str) = line.strip_prefix("#EXT-X-STREAM-INF:") {
            let mut bandwidth = 0u64;
            let mut resolution = None;
            let mut codecs = None;
            let mut frame_rate = None;

            for attr in split_hls_attributes(attr_str) {
                if let Some((k, v)) = attr.split_once('=') {
                    let k = k.trim().to_ascii_uppercase();
                    let v = v.trim().trim_matches('"');
                    if k == "BANDWIDTH" {
                        bandwidth = v.parse().unwrap_or(0);
                    } else if k == "RESOLUTION" {
                        if let Some((w_str, h_str)) = v.split_once('x').or_else(|| v.split_once('X'))
                            && let (Ok(w), Ok(h)) = (w_str.parse::<u32>(), h_str.parse::<u32>())
                        {
                            resolution = Some((w, h));
                        }
                    } else if k == "CODECS" {
                        codecs = Some(v.to_string());
                    } else if k == "FRAME-RATE" {
                        frame_rate = v.parse::<f32>().ok();
                    }
                }
            }

            // A próxima linha não-comentário é a URI da variante
            i += 1;
            while i < lines.len() && lines[i].starts_with('#') {
                i += 1;
            }

            if i < lines.len() {
                let variant_url = resolve_url(base_url, lines[i]);
                variants.push(HlsVariant {
                    bandwidth,
                    resolution,
                    codecs,
                    frame_rate,
                    url: variant_url,
                });
            }
        }
        i += 1;
    }

    if variants.is_empty() {
        return Err("Nenhuma variante encontrada na master playlist HLS".to_string());
    }

    // Ordena por bandwidth crescente
    variants.sort_by_key(|v| v.bandwidth);

    Ok(HlsMasterPlaylist { variants })
}

fn parse_media_playlist(lines: &[&str], base_url: &str) -> Result<HlsMediaPlaylist, String> {
    let mut target_duration = 0.0f64;
    let mut total_duration = 0.0f64;
    let mut is_vod = false;
    let mut segments = Vec::new();

    let mut current_key: Option<HlsKey> = None;
    let mut next_duration: Option<f64> = None;
    let mut next_byte_range: Option<(u64, u64)> = None;
    let mut last_byte_range_end = 0u64;

    for &line in lines {
        if let Some(dur_str) = line.strip_prefix("#EXT-X-TARGETDURATION:") {
            target_duration = dur_str.trim().parse().unwrap_or(0.0);
        } else if line.starts_with("#EXT-X-ENDLIST") {
            is_vod = true;
        } else if let Some(attr_str) = line.strip_prefix("#EXT-X-KEY:") {
            let mut method = "NONE".to_string();
            let mut uri = None;
            let mut iv = None;

            for attr in split_hls_attributes(attr_str) {
                if let Some((k, v)) = attr.split_once('=') {
                    let k = k.trim().to_ascii_uppercase();
                    let v = v.trim().trim_matches('"');
                    if k == "METHOD" {
                        method = v.to_string();
                    } else if k == "URI" {
                        uri = Some(resolve_url(base_url, v));
                    } else if k == "IV" {
                        iv = parse_hex_iv(v);
                    }
                }
            }

            current_key = if method == "NONE" {
                None
            } else {
                Some(HlsKey { method, uri, iv })
            };
        } else if let Some(dur_part) = line.strip_prefix("#EXTINF:") {
            let dur_str = dur_part.split(',').next().unwrap_or("0");
            next_duration = dur_str.trim().parse().ok();
        } else if let Some(range_str) = line.strip_prefix("#EXT-X-BYTERANGE:") {
            if let Some((len_str, off_str)) = range_str.split_once('@') {
                if let (Ok(len), Ok(off)) = (len_str.trim().parse::<u64>(), off_str.trim().parse::<u64>()) {
                    next_byte_range = Some((off, len));
                    last_byte_range_end = off + len;
                }
            } else if let Ok(len) = range_str.trim().parse::<u64>() {
                let off = last_byte_range_end;
                next_byte_range = Some((off, len));
                last_byte_range_end = off + len;
            }
        } else if !line.starts_with('#') {
            // Linha de URI do segmento
            if let Some(dur) = next_duration.take() {
                let segment_url = resolve_url(base_url, line);
                let segment = HlsSegment {
                    index: segments.len(),
                    duration: dur,
                    start_time: total_duration,
                    url: segment_url,
                    byte_range: next_byte_range.take(),
                    key: current_key.clone(),
                };
                total_duration += dur;
                segments.push(segment);
            }
        }
    }

    Ok(HlsMediaPlaylist {
        target_duration,
        total_duration,
        is_vod,
        segments,
    })
}

fn split_hls_attributes(attr_str: &str) -> Vec<String> {
    let mut result = Vec::new();
    let mut current = String::new();
    let mut in_quotes = false;

    for ch in attr_str.chars() {
        if ch == '"' {
            in_quotes = !in_quotes;
            current.push(ch);
        } else if ch == ',' && !in_quotes {
            let trimmed = current.trim();
            if !trimmed.is_empty() {
                result.push(trimmed.to_string());
            }
            current.clear();
        } else {
            current.push(ch);
        }
    }

    let trimmed = current.trim();
    if !trimmed.is_empty() {
        result.push(trimmed.to_string());
    }

    result
}

fn parse_hex_iv(hex_str: &str) -> Option<[u8; 16]> {
    let clean = hex_str.strip_prefix("0x").or_else(|| hex_str.strip_prefix("0X")).unwrap_or(hex_str);
    if clean.len() != 32 {
        return None;
    }
    let mut out = [0u8; 16];
    for i in 0..16 {
        out[i] = u8::from_str_radix(&clean[i * 2..i * 2 + 2], 16).ok()?;
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE_MASTER: &str = r#"#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
360p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,FRAME-RATE=29.970,CODECS="avc1.4d401f,mp4a.40.2"
720p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,FRAME-RATE=59.940,CODECS="avc1.640028,mp4a.40.2"
1080p/index.m3u8
"#;

    const SAMPLE_MEDIA: &str = r#"#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:9.009,
segment0.ts
#EXTINF:9.009,
segment1.ts
#EXTINF:8.500,
segment2.ts
#EXT-X-ENDLIST
"#;

    #[test]
    fn parse_master_playlist_variants() {
        let parsed = parse_playlist(SAMPLE_MASTER, "https://example.com/hls/master.m3u8").unwrap();
        match parsed {
            HlsPlaylist::Master(master) => {
                assert_eq!(master.variants.len(), 3);
                assert_eq!(master.variants[0].bandwidth, 800000);
                assert_eq!(master.variants[0].resolution, Some((640, 360)));
                assert_eq!(master.variants[0].url, "https://example.com/hls/360p/index.m3u8");

                assert_eq!(master.variants[1].bandwidth, 2500000);
                assert_eq!(master.variants[1].resolution, Some((1280, 720)));

                assert_eq!(master.variants[2].bandwidth, 5000000);
                assert_eq!(master.variants[2].resolution, Some((1920, 1080)));
            }
            _ => panic!("Expected master playlist"),
        }
    }

    #[test]
    fn parse_media_playlist_segments() {
        let parsed = parse_playlist(SAMPLE_MEDIA, "https://example.com/hls/720p/index.m3u8").unwrap();
        match parsed {
            HlsPlaylist::Media(media) => {
                assert_eq!(media.target_duration, 10.0);
                assert!(media.is_vod);
                assert_eq!(media.segments.len(), 3);
                assert_eq!(media.segments[0].url, "https://example.com/hls/720p/segment0.ts");
                assert_eq!(media.segments[0].duration, 9.009);
                assert_eq!(media.segments[0].start_time, 0.0);

                assert_eq!(media.segments[1].start_time, 9.009);
                assert_eq!(media.total_duration, 9.009 + 9.009 + 8.500);
            }
            _ => panic!("Expected media playlist"),
        }
    }
}
