//! Pipeline de streaming e prefetching para HLS (T8.2, T8.3, T8.5).

use super::abr::AdaptiveBitrateManager;
use super::playlist::{HlsMediaPlaylist, HlsPlaylist, HlsVariant, parse_playlist};
use super::segment::{fetch_init_segment, fetch_segment};
use crate::prefetch::RangeSource;
use std::collections::HashMap;
use std::io;
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// Fonte de streaming HLS para alimentar o demuxer FFmpeg.
pub struct HlsStreamSource {
    client: reqwest::blocking::Client,
    master_url: String,
    media_playlist: HlsMediaPlaylist,
    abr: AdaptiveBitrateManager,
    key_cache: HashMap<String, Vec<u8>>,
    init_cache: HashMap<String, Vec<u8>>,
    last_emitted_map_uri: Option<String>,
    init_emitted_for_segment: Option<usize>,
    current_segment_idx: usize,
    buffer: Vec<u8>,
    buffer_offset_in_segment: usize,
    virtual_stream_position: u64,
    estimated_total_bytes: Option<u64>,
}

impl HlsStreamSource {
    pub fn open(url: &str) -> Result<Self, String> {
        let client = crate::http::client_builder()
            .timeout(Duration::from_secs(10))
            .build()
            .map_err(|e| e.to_string())?;

        let resp = client
            .get(url)
            .send()
            .map_err(|e| format!("Falha ao conectar ao servidor HLS ({url}): {e}"))?;

        if !resp.status().is_success() {
            return Err(format!(
                "Servidor HLS retornou HTTP status {}",
                resp.status()
            ));
        }

        let body = resp
            .text()
            .map_err(|e| format!("Falha ao ler playlist M3U8: {e}"))?;
        let parsed = parse_playlist(&body, url)?;

        let (abr, media_playlist) = match parsed {
            HlsPlaylist::Master(master) => {
                let abr = AdaptiveBitrateManager::new(master.variants);
                let active_variant = abr
                    .current_variant()
                    .ok_or_else(|| "Nenhuma variante HLS válida".to_string())?;
                let media_resp = client.get(&active_variant.url).send().map_err(|e| {
                    format!(
                        "Falha ao baixar media playlist HLS ({}): {e}",
                        active_variant.url
                    )
                })?;
                let media_body = media_resp.text().map_err(|e| e.to_string())?;
                let media_parsed = parse_playlist(&media_body, &active_variant.url)?;
                match media_parsed {
                    HlsPlaylist::Media(m) => (abr, m),
                    _ => return Err("Media playlist esperada após master playlist HLS".to_string()),
                }
            }
            HlsPlaylist::Media(media) => {
                let dummy_variant = HlsVariant {
                    bandwidth: 0,
                    resolution: None,
                    codecs: None,
                    frame_rate: None,
                    url: url.to_string(),
                };
                let abr = AdaptiveBitrateManager::new(vec![dummy_variant]);
                (abr, media)
            }
        };

        // Estima tamanho total com base na duração e bandwidth da variante
        let estimated_bytes = if let Some(v) = abr.current_variant() {
            if v.bandwidth > 0 && media_playlist.total_duration > 0.0 {
                Some(((v.bandwidth as f64 * media_playlist.total_duration) / 8.0) as u64)
            } else {
                None
            }
        } else {
            None
        };

        Ok(Self {
            client,
            master_url: url.to_string(),
            media_playlist,
            abr,
            key_cache: HashMap::new(),
            init_cache: HashMap::new(),
            last_emitted_map_uri: None,
            init_emitted_for_segment: None,
            current_segment_idx: 0,
            buffer: Vec::new(),
            buffer_offset_in_segment: 0,
            virtual_stream_position: 0,
            estimated_total_bytes: estimated_bytes,
        })
    }

    pub fn master_url(&self) -> &str {
        &self.master_url
    }

    pub fn variants(&self) -> &[HlsVariant] {
        self.abr.variants()
    }

    pub fn current_variant_index(&self) -> usize {
        self.abr.current_variant_index()
    }

    pub fn set_variant(&mut self, index: Option<usize>) -> Result<(), String> {
        self.abr.set_manual_override(index);
        self.init_emitted_for_segment = None;
        self.last_emitted_map_uri = None;
        self.reload_media_playlist()
    }

    pub fn total_duration(&self) -> f64 {
        self.media_playlist.total_duration
    }

    /// Recarrega a media playlist para a variante ativa
    fn reload_media_playlist(&mut self) -> Result<(), String> {
        let active_variant = match self.abr.current_variant() {
            Some(v) => v.clone(),
            None => return Ok(()),
        };

        let resp = self
            .client
            .get(&active_variant.url)
            .send()
            .map_err(|e| format!("Falha ao recarregar media playlist HLS: {e}"))?;

        let body = resp.text().map_err(|e| e.to_string())?;
        if let HlsPlaylist::Media(m) = parse_playlist(&body, &active_variant.url)? {
            self.media_playlist = m;
            // Força reemissão do init segment para a nova variante ativa (#177)
            self.last_emitted_map_uri = None;
        }

        Ok(())
    }

    /// Salta o streaming para o segmento correspondente ao timestamp em segundos (T8.5).
    pub fn seek_to_timestamp(&mut self, timestamp_sec: f64) -> Result<(), String> {
        if self.media_playlist.segments.is_empty() {
            return Ok(());
        }

        let target = timestamp_sec.max(0.0);
        let mut target_idx = 0;

        for (i, seg) in self.media_playlist.segments.iter().enumerate() {
            if seg.start_time <= target && target < seg.start_time + seg.duration {
                target_idx = i;
                break;
            }
            if target >= seg.start_time + seg.duration {
                target_idx = i;
            }
        }

        self.current_segment_idx = target_idx;
        self.buffer.clear();
        self.buffer_offset_in_segment = 0;
        self.last_emitted_map_uri = None;
        self.init_emitted_for_segment = None;
        log::info!(
            "HLS Seek: jumping to segment {} (timestamp {:.2}s / total {:.2}s)",
            target_idx,
            target,
            self.media_playlist.total_duration
        );

        Ok(())
    }

    /// Fills internal buffer by downloading the next segment when the current one is depleted.
    fn ensure_buffer(&mut self) -> io::Result<bool> {
        if self.buffer_offset_in_segment < self.buffer.len() {
            return Ok(true);
        }

        if self.current_segment_idx >= self.media_playlist.segments.len() {
            return Ok(false); // EOF
        }

        let segment = self.media_playlist.segments[self.current_segment_idx].clone();

        // If the segment specifies an EXT-X-MAP (fMP4/CMAF) and the init section
        // has not yet been emitted for this segment, emit it before media chunks (#177).
        if let Some(ref map) = segment.map {
            let already_emitted_for_this_seg =
                self.init_emitted_for_segment == Some(self.current_segment_idx);
            if !already_emitted_for_this_seg {
                let needs_init =
                    self.last_emitted_map_uri.as_deref() != Some(&map.uri) || segment.discontinuity;
                if needs_init {
                    let init_bytes = if let Some(cached) = self.init_cache.get(&map.uri) {
                        cached.clone()
                    } else {
                        let fetched =
                            fetch_init_segment(&self.client, map).map_err(io::Error::other)?;
                        self.init_cache.insert(map.uri.clone(), fetched.clone());
                        fetched
                    };

                    self.buffer = init_bytes;
                    self.buffer_offset_in_segment = 0;
                    self.init_emitted_for_segment = Some(self.current_segment_idx);
                    self.last_emitted_map_uri = Some(map.uri.clone());
                    return Ok(true);
                }
            }
        }

        let (data, dur) =
            fetch_segment(&self.client, &segment, &mut self.key_cache).map_err(io::Error::other)?;

        // Inform ABR about segment download performance.
        let quality_changed = self
            .abr
            .record_segment_download(data.len(), dur, segment.duration);
        // Note: FFmpeg's mov demuxer ignores duplicated moov atoms mid-stream.
        // Mid-stream ABR switching for fMP4 requires demuxer restart across C ABI (matching DASH).
        // For fMP4, maintain the active variant across the stream (Refs #177).
        let is_fmp4 = segment.map.is_some();
        if quality_changed && !is_fmp4 {
            let _ = self.reload_media_playlist();
        }

        self.buffer = data;
        self.buffer_offset_in_segment = 0;
        self.current_segment_idx += 1;

        Ok(true)
    }
}

impl io::Read for HlsStreamSource {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }

        if !self.ensure_buffer()? {
            return Ok(0); // EOF
        }

        let available = self.buffer.len() - self.buffer_offset_in_segment;
        let to_copy = buf.len().min(available);

        buf[..to_copy].copy_from_slice(
            &self.buffer[self.buffer_offset_in_segment..self.buffer_offset_in_segment + to_copy],
        );
        self.buffer_offset_in_segment += to_copy;
        self.virtual_stream_position += to_copy as u64;

        Ok(to_copy)
    }
}

impl io::Seek for HlsStreamSource {
    fn seek(&mut self, pos: io::SeekFrom) -> io::Result<u64> {
        match pos {
            io::SeekFrom::Start(n) => {
                // Seek aproximado proporcional se conhecermos o tamanho total estimado
                if let Some(total_bytes) = self.estimated_total_bytes
                    && total_bytes > 0
                    && self.media_playlist.total_duration > 0.0
                {
                    let frac = (n as f64) / (total_bytes as f64);
                    let target_sec = frac * self.media_playlist.total_duration;
                    let _ = self.seek_to_timestamp(target_sec);
                }
                self.virtual_stream_position = n;
                Ok(n)
            }
            io::SeekFrom::Current(n) => {
                let new_pos = (self.virtual_stream_position as i64 + n).max(0) as u64;
                self.virtual_stream_position = new_pos;
                Ok(new_pos)
            }
            io::SeekFrom::End(_) => Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "SeekFrom::End não suportado em HLS",
            )),
        }
    }
}

impl RangeSource for HlsStreamSource {
    fn read_range(&mut self, _offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        use io::Read;
        self.read(buf)
    }

    fn len(&self) -> Option<u64> {
        self.estimated_total_bytes
    }
}

/// Wrapper `Arc<Mutex<HlsStreamSource>>` seguro para compartilhamento
pub type SharedHlsStreamSource = Arc<Mutex<HlsStreamSource>>;

#[cfg(test)]
mod tests {
    use super::*;
    use httpmock::Method::GET;
    use httpmock::MockServer;
    use std::io::Read;

    #[test]
    fn test_hls_stream_source_fmp4_init_segment_delivered_first() {
        let server = MockServer::start();

        let playlist = r#"#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:2
#EXT-X-MAP:URI="init.mp4"
#EXTINF:2.0,
segment0.m4s
#EXTINF:2.0,
segment1.m4s
#EXT-X-ENDLIST
"#;

        let playlist_mock = server.mock(|when, then| {
            when.method(GET).path("/stream.m3u8");
            then.status(200)
                .header("Content-Type", "application/vnd.apple.mpegurl")
                .body(playlist);
        });

        let init_mock = server.mock(|when, then| {
            when.method(GET).path("/init.mp4");
            then.status(200).body(b"INIT_MOOV_BYTES_123");
        });

        let seg0_mock = server.mock(|when, then| {
            when.method(GET).path("/segment0.m4s");
            then.status(200).body(b"SEGMENT_0_PAYLOAD");
        });

        let seg1_mock = server.mock(|when, then| {
            when.method(GET).path("/segment1.m4s");
            then.status(200).body(b"SEGMENT_1_PAYLOAD");
        });

        let url = server.url("/stream.m3u8");
        let mut source = HlsStreamSource::open(&url).expect("open deve ter sucesso");

        let mut all_bytes = Vec::new();
        source
            .read_to_end(&mut all_bytes)
            .expect("read deve ter sucesso");

        playlist_mock.assert();
        init_mock.assert_calls(1);
        seg0_mock.assert_calls(1);
        seg1_mock.assert_calls(1);

        let mut expected = Vec::new();
        expected.extend_from_slice(b"INIT_MOOV_BYTES_123");
        expected.extend_from_slice(b"SEGMENT_0_PAYLOAD");
        expected.extend_from_slice(b"SEGMENT_1_PAYLOAD");

        assert_eq!(all_bytes, expected);
    }

    #[test]
    fn test_hls_stream_source_abr_switch_delivers_new_variant_init_section() {
        let server = MockServer::start();

        let master = r#"#EXTM3U
#EXT-X-VERSION:7
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
var1.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
var2.m3u8
"#;

        let var1_playlist = r#"#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:2
#EXT-X-MAP:URI="init1.mp4"
#EXTINF:2.0,
seg1_0.m4s
#EXTINF:2.0,
seg1_1.m4s
#EXT-X-ENDLIST
"#;

        let var2_playlist = r#"#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:2
#EXT-X-MAP:URI="init2.mp4"
#EXTINF:2.0,
seg2_0.m4s
#EXTINF:2.0,
seg2_1.m4s
#EXT-X-ENDLIST
"#;

        let master_mock = server.mock(|when, then| {
            when.method(GET).path("/master.m3u8");
            then.status(200).body(master);
        });

        let _var1_mock = server.mock(|when, then| {
            when.method(GET).path("/var1.m3u8");
            then.status(200).body(var1_playlist);
        });

        let _var2_mock = server.mock(|when, then| {
            when.method(GET).path("/var2.m3u8");
            then.status(200).body(var2_playlist);
        });

        let init1_mock = server.mock(|when, then| {
            when.method(GET).path("/init1.mp4");
            then.status(200).body(b"INIT_VARIANT_1_HEADER");
        });

        let init2_mock = server.mock(|when, then| {
            when.method(GET).path("/init2.mp4");
            then.status(200).body(b"INIT_VARIANT_2_HEADER");
        });

        let _seg1_mock = server.mock(|when, then| {
            when.method(GET).path("/seg1_0.m4s");
            then.status(200).body(b"MEDIA_V1_CHUNK");
        });

        let _seg2_mock = server.mock(|when, then| {
            when.method(GET).path("/seg2_0.m4s");
            then.status(200).body(b"MEDIA_V2_CHUNK");
        });

        let url = server.url("/master.m3u8");
        let mut source = HlsStreamSource::open(&url).expect("open should succeed");

        // 1. Initial read: contains the init segment of the active variant (variant 1 from initial ABR)
        let mut init2_buf = vec![0u8; 21];
        source
            .read_exact(&mut init2_buf)
            .expect("read of initial init segment should succeed");
        assert_eq!(&init2_buf, b"INIT_VARIANT_2_HEADER");
        init2_mock.assert_calls(1);

        // 2. Manual variant switch to variant 0
        source
            .set_variant(Some(0))
            .expect("variant switch should succeed");

        // 3. Next read at boundary: delivered bytes must be the init segment of the new variant
        let mut init1_buf = vec![0u8; 21];
        source
            .read_exact(&mut init1_buf)
            .expect("read of new variant init segment should succeed");
        assert_eq!(&init1_buf, b"INIT_VARIANT_1_HEADER");
        init1_mock.assert_calls(1);

        master_mock.assert();
    }

    #[test]
    fn test_hls_stream_source_discontinuity_emits_init_once_and_reads_all_media_segments() {
        let server = MockServer::start();

        let playlist = r#"#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:2
#EXT-X-MAP:URI="init.mp4"
#EXTINF:2.0,
s0.m4s
#EXT-X-DISCONTINUITY
#EXTINF:2.0,
s1.m4s
#EXT-X-ENDLIST
"#;

        let playlist_mock = server.mock(|when, then| {
            when.method(GET).path("/playlist.m3u8");
            then.status(200).body(playlist);
        });

        let init_mock = server.mock(|when, then| {
            when.method(GET).path("/init.mp4");
            then.status(200).body(b"INIT_HEADER_123");
        });

        let s0_mock = server.mock(|when, then| {
            when.method(GET).path("/s0.m4s");
            then.status(200).body(b"SEGMENT_0");
        });

        let s1_mock = server.mock(|when, then| {
            when.method(GET).path("/s1.m4s");
            then.status(200).body(b"SEGMENT_1");
        });

        let url = server.url("/playlist.m3u8");
        let mut source = HlsStreamSource::open(&url).expect("open should succeed");

        let mut all_bytes = Vec::new();
        source
            .read_to_end(&mut all_bytes)
            .expect("read_to_end should terminate and succeed");

        playlist_mock.assert();
        // Crucial assertions: init must be emitted twice (before s0 and after discontinuity before s1),
        // and both segments must be fetched without getting stuck in an infinite loop.
        init_mock.assert_calls(1); // Cached from first fetch, re-emitted from cache
        s0_mock.assert_calls(1);
        s1_mock.assert_calls(1);

        let mut expected = Vec::new();
        expected.extend_from_slice(b"INIT_HEADER_123");
        expected.extend_from_slice(b"SEGMENT_0");
        expected.extend_from_slice(b"INIT_HEADER_123");
        expected.extend_from_slice(b"SEGMENT_1");

        assert_eq!(all_bytes, expected);
    }
}
