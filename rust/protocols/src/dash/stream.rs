//! Pipeline de streaming, download de segmentos e ABR para DASH (T2.2 - T2.5).

use super::manifest::{
    parse_mpd, resolve_template_url, DashManifest, DashRepresentation,
};
use crate::dlna::resolve_url;
use crate::hls::abr::AdaptiveBitrateManager;
use crate::prefetch::RangeSource;
use std::collections::HashMap;
use std::io;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Normaliza URLs com esquemas DASH como `dash://` para `https://` (ou `http://`).
pub fn normalize_dash_url(url: &str) -> String {
    let trimmed = url.trim();
    if let Some(rest) = trimmed.strip_prefix("dash://") {
        format!("https://{rest}")
    } else {
        trimmed.to_string()
    }
}

/// Faz o download do manifesto MPD e extrai as representações de vídeo ordenadas por bandwidth.
pub fn fetch_and_probe_representations(url: &str) -> Result<Vec<DashRepresentation>, String> {
    let normalized = normalize_dash_url(url);
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .map_err(|e| format!("HTTP client error: {e}"))?;

    let resp = client
        .get(&normalized)
        .send()
        .map_err(|e| format!("HTTP request error ao buscar MPD ({normalized}): {e}"))?;

    if !resp.status().is_success() {
        return Err(format!("Servidor DASH retornou HTTP {}", resp.status()));
    }

    let xml = resp.text().map_err(|e| format!("Erro ao ler corpo do MPD: {e}"))?;
    let manifest = parse_mpd(&xml, &normalized)?;
    let reps = manifest.video_representations();
    if reps.is_empty() {
        return Err("Nenhuma representação de vídeo encontrada no manifesto DASH".to_string());
    }

    Ok(reps)
}

/// Fonte de streaming MPEG-DASH para alimentar o demuxer FFmpeg via `StreamIo`.
pub struct DashStreamSource {
    client: reqwest::blocking::Client,
    mpd_url: String,
    manifest: DashManifest,
    abr: AdaptiveBitrateManager<DashRepresentation>,
    init_segments: HashMap<String, Vec<u8>>,
    active_rep_id: String,
    current_segment_num: u64,
    start_number: u64,
    total_segments: Option<u64>,
    segment_duration_sec: f64,
    buffer: Vec<u8>,
    buffer_offset: usize,
    virtual_stream_position: u64,
    estimated_total_bytes: Option<u64>,
    has_delivered_init_for_active_rep: bool,
}

impl DashStreamSource {
    pub fn open(url: &str) -> Result<Self, String> {
        let normalized = normalize_dash_url(url);
        let client = reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(10))
            .build()
            .map_err(|e| e.to_string())?;

        let resp = client
            .get(&normalized)
            .send()
            .map_err(|e| format!("Falha ao conectar ao servidor DASH ({normalized}): {e}"))?;

        if !resp.status().is_success() {
            return Err(format!("Servidor DASH retornou status HTTP {}", resp.status()));
        }

        let body = resp.text().map_err(|e| format!("Falha ao ler XML do MPD: {e}"))?;
        let manifest = parse_mpd(&body, &normalized)?;

        let video_reps = manifest.video_representations();
        if video_reps.is_empty() {
            return Err("Nenhuma representação de vídeo encontrada no MPD".to_string());
        }

        let abr = AdaptiveBitrateManager::new(video_reps);
        let active_rep = abr.current_variant().cloned().ok_or_else(|| "Nenhuma variante ativa".to_string())?;

        let (start_number, seg_duration, total_segs) = Self::calculate_segment_params(&active_rep, &manifest);

        let estimated_bytes = if active_rep.bandwidth > 0 && manifest.duration_sec.unwrap_or(0.0) > 0.0 {
            Some(((active_rep.bandwidth as f64 * manifest.duration_sec.unwrap()) / 8.0) as u64)
        } else {
            None
        };

        let mut source = Self {
            client,
            mpd_url: normalized,
            manifest,
            abr,
            init_segments: HashMap::new(),
            active_rep_id: active_rep.id.clone(),
            current_segment_num: start_number,
            start_number,
            total_segments: total_segs,
            segment_duration_sec: seg_duration,
            buffer: Vec::new(),
            buffer_offset: 0,
            virtual_stream_position: 0,
            estimated_total_bytes: estimated_bytes,
            has_delivered_init_for_active_rep: false,
        };

        // Baixa o segmento de inicialização da representação ativa imediatamente
        source.load_init_segment_if_needed(&active_rep)?;

        Ok(source)
    }

    fn calculate_segment_params(rep: &DashRepresentation, manifest: &DashManifest) -> (u64, f64, Option<u64>) {
        if let Some(ref template) = rep.segment_template {
            let timescale = template.timescale.max(1) as f64;
            let duration_units = template.duration.unwrap_or(template.timescale) as f64;
            let seg_dur = (duration_units / timescale).max(0.1);
            let total_segs = manifest.duration_sec.map(|d| (d / seg_dur).ceil() as u64);
            (template.start_number, seg_dur, total_segs)
        } else {
            (1, 2.0, None)
        }
    }

    pub fn mpd_url(&self) -> &str {
        &self.mpd_url
    }

    pub fn representations(&self) -> &[DashRepresentation] {
        self.abr.variants()
    }

    pub fn current_representation_index(&self) -> usize {
        self.abr.current_variant_index()
    }

    pub fn current_representation(&self) -> Option<&DashRepresentation> {
        self.abr.current_variant()
    }

    pub fn set_representation(&mut self, index: Option<usize>) -> Result<(), String> {
        self.abr.set_manual_override(index);
        self.switch_to_active_representation()
    }

    pub fn total_duration(&self) -> f64 {
        self.manifest.duration_sec.unwrap_or(0.0)
    }

    fn switch_to_active_representation(&mut self) -> Result<(), String> {
        let active_rep = match self.abr.current_variant() {
            Some(r) => r.clone(),
            None => return Ok(()),
        };

        if active_rep.id != self.active_rep_id {
            log::info!("DASH: Trocando representação ativa para id={} ({} bps)", active_rep.id, active_rep.bandwidth);
            self.active_rep_id = active_rep.id.clone();
            self.has_delivered_init_for_active_rep = false;
            let (start_number, seg_duration, total_segs) = Self::calculate_segment_params(&active_rep, &self.manifest);
            self.start_number = start_number;
            self.segment_duration_sec = seg_duration;
            self.total_segments = total_segs;
            self.load_init_segment_if_needed(&active_rep)?;
        }
        Ok(())
    }

    /// Baixa o segmento de inicialização para a representação especificada caso ainda não esteja em cache.
    fn load_init_segment_if_needed(&mut self, rep: &DashRepresentation) -> Result<(), String> {
        if self.init_segments.contains_key(&rep.id) {
            return Ok(());
        }

        let base = rep.base_url.as_deref().unwrap_or(&self.mpd_url);

        if let Some(ref template) = rep.segment_template
            && let Some(ref init_rel) = template.initialization {
            let resolved_rel = resolve_template_url(init_rel, &rep.id, 0, 0);
            let full_url = resolve_url(base, &resolved_rel);

            log::info!("DASH: Baixando init segment de {full_url}");
            let resp = self
                .client
                .get(&full_url)
                .send()
                .map_err(|e| format!("Falha ao baixar init segment ({full_url}): {e}"))?;

            if !resp.status().is_success() {
                return Err(format!("Servidor retornou HTTP {} ao baixar init segment", resp.status()));
            }

            let data = resp.bytes().map_err(|e| e.to_string())?.to_vec();
            self.init_segments.insert(rep.id.clone(), data);
            return Ok(());
        }

        if let Some(ref sb) = rep.segment_base
            && let Some((offset, len)) = sb.initialization_range {
            log::info!("DASH: Baixando init segment via Range {offset}-{} de {base}", offset + len - 1);
            let resp = self
                .client
                .get(base)
                .header("Range", format!("bytes={}-{}", offset, offset + len - 1))
                .send()
                .map_err(|e| format!("Falha no range request de init segment: {e}"))?;

            if !resp.status().is_success() {
                return Err(format!("Range HTTP {} ao buscar init segment", resp.status()));
            }

            let data = resp.bytes().map_err(|e| e.to_string())?.to_vec();
            self.init_segments.insert(rep.id.clone(), data);
            return Ok(());
        }

        Ok(())
    }

    /// Salta o streaming para o segmento correspondente ao timestamp em segundos (T2.5).
    pub fn seek_to_timestamp(&mut self, timestamp_sec: f64) -> Result<(), String> {
        let target_sec = timestamp_sec.max(0.0);

        let active_rep = match self.abr.current_variant() {
            Some(r) => r.clone(),
            None => return Ok(()),
        };

        if let Some(ref template) = active_rep.segment_template {
            let timescale = template.timescale.max(1) as f64;
            let duration_units = template.duration.unwrap_or(template.timescale) as f64;
            let seg_dur = (duration_units / timescale).max(0.001);

            let seg_offset = (target_sec / seg_dur).floor() as u64;
            let mut target_num = template.start_number + seg_offset;

            if let Some(total) = self.total_segments {
                target_num = target_num.min(template.start_number + total.saturating_sub(1));
            }

            log::info!(
                "DASH Seek: Saltando para segmento {} (timestamp {:.2}s / total {:.2}s)",
                target_num,
                target_sec,
                self.manifest.duration_sec.unwrap_or(0.0)
            );

            self.current_segment_num = target_num;
            self.buffer.clear();
            self.buffer_offset = 0;
            self.has_delivered_init_for_active_rep = false;
        }

        Ok(())
    }

    /// Garante que haja dados no buffer interno para leitura pelo demuxer.
    fn ensure_buffer(&mut self) -> io::Result<bool> {
        if self.buffer_offset < self.buffer.len() {
            return Ok(true);
        }

        let active_rep = match self.abr.current_variant() {
            Some(r) => r.clone(),
            None => return Ok(false),
        };

        // Se ainda não entregou o init segment para a representação ativa, entrega primeiro
        if !self.has_delivered_init_for_active_rep
            && let Some(init_data) = self.init_segments.get(&active_rep.id) {
            self.buffer = init_data.clone();
            self.buffer_offset = 0;
            self.has_delivered_init_for_active_rep = true;
            return Ok(true);
        }

        // Verifica término de VOD
        if let Some(total) = self.total_segments
            && self.current_segment_num >= self.start_number + total {
            return Ok(false); // EOF
        }

        // Baixa o segmento de mídia atual
        let base = active_rep.base_url.as_deref().unwrap_or(&self.mpd_url);

        if let Some(ref template) = active_rep.segment_template
            && let Some(ref media_rel) = template.media {
            let _timescale = template.timescale.max(1);
            let duration_units = template.duration.unwrap_or(template.timescale);
            let time_units = (self.current_segment_num.saturating_sub(template.start_number)) * duration_units;

            let resolved_rel = resolve_template_url(media_rel, &active_rep.id, self.current_segment_num, time_units);
            let full_url = resolve_url(base, &resolved_rel);

                let start_time = Instant::now();
                let resp = self
                    .client
                    .get(&full_url)
                    .send()
                    .map_err(|e| io::Error::other(format!("Falha ao baixar segmento DASH ({full_url}): {e}")))?;

                if !resp.status().is_success() {
                    return Err(io::Error::other(format!("HTTP {} ao buscar segmento {}", resp.status(), self.current_segment_num)));
                }

                let data = resp.bytes().map_err(io::Error::other)?.to_vec();
                let dur = start_time.elapsed();

                // Notifica ABR para adaptação de qualidade
                let quality_changed = self.abr.record_segment_download(data.len(), dur, self.segment_duration_sec);
                if quality_changed {
                    let _ = self.switch_to_active_representation();
                }

                self.buffer = data;
                self.buffer_offset = 0;
                self.current_segment_num += 1;
                return Ok(true);
        }

        Ok(false)
    }
}

impl io::Read for DashStreamSource {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }

        if !self.ensure_buffer()? {
            return Ok(0); // EOF
        }

        let available = self.buffer.len() - self.buffer_offset;
        let to_copy = buf.len().min(available);

        buf[..to_copy].copy_from_slice(&self.buffer[self.buffer_offset..self.buffer_offset + to_copy]);
        self.buffer_offset += to_copy;
        self.virtual_stream_position += to_copy as u64;

        Ok(to_copy)
    }
}

impl io::Seek for DashStreamSource {
    fn seek(&mut self, pos: io::SeekFrom) -> io::Result<u64> {
        match pos {
            io::SeekFrom::Start(n) => {
                if let Some(total_bytes) = self.estimated_total_bytes
                    && total_bytes > 0
                    && self.manifest.duration_sec.unwrap_or(0.0) > 0.0
                {
                    let frac = (n as f64) / (total_bytes as f64);
                    let target_sec = frac * self.manifest.duration_sec.unwrap();
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
                "SeekFrom::End não suportado em DASH",
            )),
        }
    }
}

impl RangeSource for DashStreamSource {
    fn read_range(&mut self, _offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        use io::Read;
        self.read(buf)
    }

    fn len(&self) -> Option<u64> {
        self.estimated_total_bytes
    }
}

pub type SharedDashStreamSource = Arc<Mutex<DashStreamSource>>;

#[cfg(test)]
mod tests {
    use super::*;
    use httpmock::Method::GET;
    use httpmock::MockServer;
    use std::io::Read;

    #[test]
    fn test_normalize_dash_url() {
        assert_eq!(normalize_dash_url("dash://example.com/live.mpd"), "https://example.com/live.mpd");
        assert_eq!(normalize_dash_url("https://example.com/live.mpd"), "https://example.com/live.mpd");
        assert_eq!(normalize_dash_url("http://example.com/live.mpd"), "http://example.com/live.mpd");
    }

    #[test]
    fn test_dash_stream_source_read_and_seek_with_mock_server() {
        let server = MockServer::start();

        let mpd_xml = format!(r#"<?xml version="1.0" encoding="utf-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
     mediaPresentationDuration="PT6S"
     type="static">
  <Period id="1">
    <AdaptationSet mimeType="video/mp4" contentType="video">
      <SegmentTemplate timescale="1000"
                       duration="2000"
                       initialization="init.m4s"
                       media="segment_$Number$.m4s"
                       startNumber="1" />
      <Representation id="v1" bandwidth="1000000" width="1280" height="720" />
    </AdaptationSet>
  </Period>
</MPD>"#);

        let mpd_mock = server.mock(|when, then| {
            when.method(GET).path("/manifest.mpd");
            then.status(200)
                .header("Content-Type", "application/dash+xml")
                .body(&mpd_xml);
        });

        let init_mock = server.mock(|when, then| {
            when.method(GET).path("/init.m4s");
            then.status(200).body(b"INIT_HEADER_MOOV");
        });

        let seg1_mock = server.mock(|when, then| {
            when.method(GET).path("/segment_1.m4s");
            then.status(200).body(b"SEGMENT_1_DATA");
        });

        let seg2_mock = server.mock(|when, then| {
            when.method(GET).path("/segment_2.m4s");
            then.status(200).body(b"SEGMENT_2_DATA");
        });

        let mpd_url = server.url("/manifest.mpd");
        let mut source = DashStreamSource::open(&mpd_url).expect("open deve ter sucesso");

        assert_eq!(source.representations().len(), 1);
        assert_eq!(source.total_duration(), 6.0);

        // A primeira leitura deve conter o init segment
        let mut buf = vec![0u8; 16];
        let bytes_read = source.read(&mut buf).expect("read init segment");
        assert_eq!(&buf[..bytes_read], b"INIT_HEADER_MOOV");

        // A próxima leitura deve conter o segmento 1
        let mut seg_buf = vec![0u8; 14];
        let bytes_read_seg1 = source.read(&mut seg_buf).expect("read seg 1");
        assert_eq!(&seg_buf[..bytes_read_seg1], b"SEGMENT_1_DATA");

        // Realiza seek para 3.5 segundos (deve pular direto para o segmento 2)
        source.seek_to_timestamp(3.5).expect("seek deve funcionar");
        assert_eq!(source.current_segment_num, 2);

        // Após seek, recebe o init segment de novo se ainda não foi entregue na nova posição
        let mut seek_init_buf = vec![0u8; 16];
        let bytes_init2 = source.read(&mut seek_init_buf).expect("read init after seek");
        assert_eq!(&seek_init_buf[..bytes_init2], b"INIT_HEADER_MOOV");

        // E em seguida recebe os dados do segmento 2
        let mut seg2_buf = vec![0u8; 14];
        let bytes_seg2 = source.read(&mut seg2_buf).expect("read seg 2");
        assert_eq!(&seg2_buf[..bytes_seg2], b"SEGMENT_2_DATA");

        mpd_mock.assert();
        init_mock.assert();
        seg1_mock.assert();
        seg2_mock.assert();
    }
}
