//! Pipeline de streaming e prefetching para HLS (T8.2, T8.3, T8.5).

use super::abr::AdaptiveBitrateManager;
use super::playlist::{parse_playlist, HlsMediaPlaylist, HlsPlaylist, HlsVariant};
use super::segment::fetch_segment;
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
    current_segment_idx: usize,
    buffer: Vec<u8>,
    buffer_offset_in_segment: usize,
    virtual_stream_position: u64,
    estimated_total_bytes: Option<u64>,
}

impl HlsStreamSource {
    pub fn open(url: &str) -> Result<Self, String> {
        let client = reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(10))
            .build()
            .map_err(|e| e.to_string())?;

        let resp = client
            .get(url)
            .send()
            .map_err(|e| format!("Falha ao conectar ao servidor HLS ({url}): {e}"))?;

        if !resp.status().is_success() {
            return Err(format!("Servidor HLS retornou HTTP status {}", resp.status()));
        }

        let body = resp.text().map_err(|e| format!("Falha ao ler playlist M3U8: {e}"))?;
        let parsed = parse_playlist(&body, url)?;

        let (abr, media_playlist) = match parsed {
            HlsPlaylist::Master(master) => {
                let abr = AdaptiveBitrateManager::new(master.variants);
                let active_variant = abr.current_variant().ok_or_else(|| "Nenhuma variante HLS válida".to_string())?;
                let media_resp = client
                    .get(&active_variant.url)
                    .send()
                    .map_err(|e| format!("Falha ao baixar media playlist HLS ({}): {e}", active_variant.url))?;
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
        log::info!(
            "HLS Seek: Saltando para segmento {} (timestamp {:.2}s / total {:.2}s)",
            target_idx,
            target,
            self.media_playlist.total_duration
        );

        Ok(())
    }

    /// Preenche o buffer interno baixando o próximo segmento se o atual esgotou.
    fn ensure_buffer(&mut self) -> io::Result<bool> {
        if self.buffer_offset_in_segment < self.buffer.len() {
            return Ok(true);
        }

        if self.current_segment_idx >= self.media_playlist.segments.len() {
            return Ok(false); // EOF
        }

        let segment = self.media_playlist.segments[self.current_segment_idx].clone();
        let (data, dur) = fetch_segment(&self.client, &segment, &mut self.key_cache)
            .map_err(io::Error::other)?;

        // Informa o ABR sobre a velocidade de download do segmento
        let quality_changed = self.abr.record_segment_download(data.len(), dur, segment.duration);
        if quality_changed {
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

        buf[..to_copy].copy_from_slice(&self.buffer[self.buffer_offset_in_segment..self.buffer_offset_in_segment + to_copy]);
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
            io::SeekFrom::End(_) => Err(io::Error::new(io::ErrorKind::Unsupported, "SeekFrom::End não suportado em HLS")),
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
