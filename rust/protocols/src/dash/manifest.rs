//! Parser de manifestos MPD (MPEG-DASH) para VOD e Streaming Adaptativo (T2.1).
//!
//! Utiliza `quick-xml` em modo evento (streaming reader) sem novas dependências externas
//! e com suporte a `SegmentTemplate`, `SegmentBase` e resolução de placeholders.

use crate::dlna::resolve_url;
use crate::hls::abr::BitrateVariant;
use quick_xml::events::Event;
use quick_xml::reader::Reader;

/// Converte durações ISO-8601 (ex.: "PT1H30M15.5S", "PT45S", "PT0H10M0.00S", "P1DT2H") para segundos.
pub fn parse_iso8601_duration(s: &str) -> Option<f64> {
    let s = s.trim();
    if !s.starts_with('P') {
        return None;
    }

    let mut total_secs = 0.0;
    let mut in_time = false;
    let mut current_num = String::new();

    for ch in s[1..].chars() {
        match ch {
            'T' => {
                in_time = true;
            }
            '0'..='9' | '.' => {
                current_num.push(ch);
            }
            'Y' => {
                let val: f64 = current_num.parse().ok()?;
                total_secs += val * 365.0 * 86400.0;
                current_num.clear();
            }
            'M' => {
                let val: f64 = current_num.parse().ok()?;
                if in_time {
                    total_secs += val * 60.0;
                } else {
                    total_secs += val * 30.0 * 86400.0;
                }
                current_num.clear();
            }
            'D' => {
                let val: f64 = current_num.parse().ok()?;
                total_secs += val * 86400.0;
                current_num.clear();
            }
            'H' => {
                let val: f64 = current_num.parse().ok()?;
                total_secs += val * 3600.0;
                current_num.clear();
            }
            'S' => {
                let val: f64 = current_num.parse().ok()?;
                total_secs += val;
                current_num.clear();
            }
            _ => {}
        }
    }

    Some(total_secs)
}

/// Substitui placeholders padrão de templates DASH como `$RepresentationID$`, `$Number$`, `$Number%0[N]d$`, `$Time$`.
pub fn resolve_template_url(template: &str, rep_id: &str, number: u64, time: u64) -> String {
    let mut result = String::with_capacity(template.len() + 16);
    let mut chars = template.chars().peekable();

    while let Some(c) = chars.next() {
        if c == '$' {
            if chars.peek() == Some(&'$') {
                chars.next();
                result.push('$');
                continue;
            }
            let mut tag = String::new();
            let mut closed = false;
            while let Some(&tc) = chars.peek() {
                chars.next();
                if tc == '$' {
                    closed = true;
                    break;
                }
                tag.push(tc);
            }
            if !closed {
                result.push('$');
                result.push_str(&tag);
                continue;
            }

            if tag == "RepresentationID" {
                result.push_str(rep_id);
            } else if tag == "Number" {
                result.push_str(&number.to_string());
            } else if tag == "Time" {
                result.push_str(&time.to_string());
            } else if let Some(rest) = tag.strip_prefix("Number%0") {
                if let Some(width_str) = rest.strip_suffix('d') {
                    if let Ok(width) = width_str.parse::<usize>() {
                        result.push_str(&format!("{:0width$}", number, width = width));
                    } else {
                        result.push_str(&number.to_string());
                    }
                } else {
                    result.push_str(&number.to_string());
                }
            } else if let Some(rest) = tag.strip_prefix("Time%0") {
                if let Some(width_str) = rest.strip_suffix('d') {
                    if let Ok(width) = width_str.parse::<usize>() {
                        result.push_str(&format!("{:0width$}", time, width = width));
                    } else {
                        result.push_str(&time.to_string());
                    }
                } else {
                    result.push_str(&time.to_string());
                }
            } else {
                result.push('$');
                result.push_str(&tag);
                result.push('$');
            }
        } else {
            result.push(c);
        }
    }
    result
}

/// Converte strings de byte range como "835-1500" ou "0-834" em `(offset, length)`.
pub fn parse_byte_range(s: &str) -> Option<(u64, u64)> {
    let parts: Vec<&str> = s.split('-').map(|p| p.trim()).collect();
    if parts.len() == 2 {
        let start: u64 = parts[0].parse().ok()?;
        let end: u64 = parts[1].parse().ok()?;
        if end >= start {
            return Some((start, end - start + 1));
        }
    }
    None
}

#[derive(Debug, Clone, PartialEq)]
pub struct SegmentTemplate {
    pub initialization: Option<String>,
    pub media: Option<String>,
    pub timescale: u64,
    pub duration: Option<u64>,
    pub start_number: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SegmentBase {
    pub index_range: Option<(u64, u64)>, // (offset, length)
    pub initialization_range: Option<(u64, u64)>, // (offset, length)
    pub timescale: u64,
    pub presentation_time_offset: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DashRepresentation {
    pub id: String,
    pub bandwidth: u64,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub codecs: Option<String>,
    pub frame_rate: Option<f32>,
    pub segment_template: Option<SegmentTemplate>,
    pub segment_base: Option<SegmentBase>,
    pub base_url: Option<String>,
}

impl BitrateVariant for DashRepresentation {
    fn bandwidth(&self) -> u64 {
        self.bandwidth
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct DashAdaptationSet {
    pub id: Option<String>,
    pub content_type: Option<String>,
    pub mime_type: Option<String>,
    pub segment_template: Option<SegmentTemplate>,
    pub segment_base: Option<SegmentBase>,
    pub representations: Vec<DashRepresentation>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DashPeriod {
    pub id: Option<String>,
    pub start_sec: Option<f64>,
    pub duration_sec: Option<f64>,
    pub adaptation_sets: Vec<DashAdaptationSet>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DashManifest {
    pub duration_sec: Option<f64>,
    pub min_buffer_time_sec: Option<f64>,
    pub is_dynamic: bool,
    pub base_url: Option<String>,
    pub periods: Vec<DashPeriod>,
}

impl DashManifest {
    /// Extrai e ordena todas as representações de vídeo por bandwidth ascendente.
    /// Propaga SegmentTemplate, SegmentBase e BaseURL herdados do AdaptationSet ou Period.
    pub fn video_representations(&self) -> Vec<DashRepresentation> {
        let mut list = Vec::new();

        for period in &self.periods {
            for adapt in &period.adaptation_sets {
                let is_video = match (&adapt.content_type, &adapt.mime_type) {
                    (Some(ct), _) if ct.eq_ignore_ascii_case("video") => true,
                    (_, Some(mt)) if mt.to_ascii_lowercase().starts_with("video/") => true,
                    _ => adapt.representations.iter().any(|r| r.width.is_some() || r.height.is_some()),
                };

                if !is_video && !adapt.representations.is_empty() {
                    let any_dims = adapt.representations.iter().any(|r| r.width.is_some() || r.height.is_some());
                    if !any_dims {
                        continue;
                    }
                }

                for rep in &adapt.representations {
                    let mut resolved_rep = rep.clone();

                    if resolved_rep.segment_template.is_none() {
                        resolved_rep.segment_template = adapt.segment_template.clone();
                    }
                    if resolved_rep.segment_base.is_none() {
                        resolved_rep.segment_base = adapt.segment_base.clone();
                    }
                    if resolved_rep.base_url.is_none() {
                        resolved_rep.base_url = self.base_url.clone();
                    }

                    list.push(resolved_rep);
                }
            }
        }

        list.sort_by_key(|r| r.bandwidth);
        list
    }
}

/// Faz o parse do manifesto MPD XML.
pub fn parse_mpd(xml: &str, mpd_url: &str) -> Result<DashManifest, String> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);

    let mut duration_sec = None;
    let mut min_buffer_time_sec = None;
    let mut is_dynamic = false;
    let mut mpd_base_url: Option<String> = None;
    let mut periods = Vec::new();

    let mut current_period: Option<DashPeriod> = None;
    let mut current_adapt: Option<DashAdaptationSet> = None;
    let mut current_rep: Option<DashRepresentation> = None;
    let mut current_tag = String::new();

    let mut buf = Vec::new();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let tag_raw = String::from_utf8_lossy(e.name().as_ref()).to_string();
                let local_tag = tag_raw.split(':').next_back().unwrap_or(&tag_raw).to_string();
                current_tag = local_tag.clone();

                match local_tag.as_str() {
                    "MPD" => {
                        for attr in e.attributes().flatten() {
                            let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                            let key_local = key.split(':').next_back().unwrap_or(&key).to_string();
                            let val = String::from_utf8_lossy(&attr.value).to_string();

                            match key_local.as_str() {
                                "mediaPresentationDuration" => {
                                    duration_sec = parse_iso8601_duration(&val);
                                }
                                "minBufferTime" => {
                                    min_buffer_time_sec = parse_iso8601_duration(&val);
                                }
                                "type" => {
                                    is_dynamic = val.eq_ignore_ascii_case("dynamic");
                                }
                                _ => {}
                            }
                        }
                    }
                    "Period" => {
                        let mut p_id = None;
                        let mut p_start = None;
                        let mut p_duration = None;

                        for attr in e.attributes().flatten() {
                            let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                            let key_local = key.split(':').next_back().unwrap_or(&key).to_string();
                            let val = String::from_utf8_lossy(&attr.value).to_string();

                            match key_local.as_str() {
                                "id" => p_id = Some(val),
                                "start" => p_start = parse_iso8601_duration(&val),
                                "duration" => p_duration = parse_iso8601_duration(&val),
                                _ => {}
                            }
                        }

                        current_period = Some(DashPeriod {
                            id: p_id,
                            start_sec: p_start,
                            duration_sec: p_duration,
                            adaptation_sets: Vec::new(),
                        });
                    }
                    "AdaptationSet" => {
                        let mut a_id = None;
                        let mut a_content_type = None;
                        let mut a_mime_type = None;

                        for attr in e.attributes().flatten() {
                            let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                            let key_local = key.split(':').next_back().unwrap_or(&key).to_string();
                            let val = String::from_utf8_lossy(&attr.value).to_string();

                            match key_local.as_str() {
                                "id" => a_id = Some(val),
                                "contentType" => a_content_type = Some(val),
                                "mimeType" => a_mime_type = Some(val),
                                _ => {}
                            }
                        }

                        current_adapt = Some(DashAdaptationSet {
                            id: a_id,
                            content_type: a_content_type,
                            mime_type: a_mime_type,
                            segment_template: None,
                            segment_base: None,
                            representations: Vec::new(),
                        });
                    }
                    "SegmentTemplate" => {
                        let mut st_init = None;
                        let mut st_media = None;
                        let mut st_timescale = 1;
                        let mut st_duration = None;
                        let mut st_start_num = 1;

                        for attr in e.attributes().flatten() {
                            let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                            let key_local = key.split(':').next_back().unwrap_or(&key).to_string();
                            let val = String::from_utf8_lossy(&attr.value).to_string();

                            match key_local.as_str() {
                                "initialization" => st_init = Some(val),
                                "media" => st_media = Some(val),
                                "timescale" => st_timescale = val.parse().unwrap_or(1),
                                "duration" => st_duration = val.parse().ok(),
                                "startNumber" => st_start_num = val.parse().unwrap_or(1),
                                _ => {}
                            }
                        }

                        let template = SegmentTemplate {
                            initialization: st_init,
                            media: st_media,
                            timescale: st_timescale,
                            duration: st_duration,
                            start_number: st_start_num,
                        };

                        if let Some(ref mut rep) = current_rep {
                            rep.segment_template = Some(template);
                        } else if let Some(ref mut adapt) = current_adapt {
                            adapt.segment_template = Some(template);
                        }
                    }
                    "SegmentBase" => {
                        let mut sb_index_range = None;
                        let mut sb_timescale = 1;
                        let mut sb_pto = 0;

                        for attr in e.attributes().flatten() {
                            let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                            let key_local = key.split(':').next_back().unwrap_or(&key).to_string();
                            let val = String::from_utf8_lossy(&attr.value).to_string();

                            match key_local.as_str() {
                                "indexRange" => sb_index_range = parse_byte_range(&val),
                                "timescale" => sb_timescale = val.parse().unwrap_or(1),
                                "presentationTimeOffset" => sb_pto = val.parse().unwrap_or(0),
                                _ => {}
                            }
                        }

                        let base = SegmentBase {
                            index_range: sb_index_range,
                            initialization_range: None,
                            timescale: sb_timescale,
                            presentation_time_offset: sb_pto,
                        };

                        if let Some(ref mut rep) = current_rep {
                            rep.segment_base = Some(base);
                        } else if let Some(ref mut adapt) = current_adapt {
                            adapt.segment_base = Some(base);
                        }
                    }
                    "Representation" => {
                        let mut r_id = String::new();
                        let mut r_bw = 0;
                        let mut r_w = None;
                        let mut r_h = None;
                        let mut r_codecs = None;
                        let mut r_fps = None;

                        for attr in e.attributes().flatten() {
                            let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                            let key_local = key.split(':').next_back().unwrap_or(&key).to_string();
                            let val = String::from_utf8_lossy(&attr.value).to_string();

                            match key_local.as_str() {
                                "id" => r_id = val,
                                "bandwidth" => r_bw = val.parse().unwrap_or(0),
                                "width" => r_w = val.parse().ok(),
                                "height" => r_h = val.parse().ok(),
                                "codecs" => r_codecs = Some(val),
                                "frameRate" => r_fps = val.parse().ok(),
                                _ => {}
                            }
                        }

                        current_rep = Some(DashRepresentation {
                            id: r_id,
                            bandwidth: r_bw,
                            width: r_w,
                            height: r_h,
                            codecs: r_codecs,
                            frame_rate: r_fps,
                            segment_template: None,
                            segment_base: None,
                            base_url: None,
                        });
                    }
                    _ => {}
                }
            }
            Ok(Event::Empty(ref e)) => {
                let tag_raw = String::from_utf8_lossy(e.name().as_ref()).to_string();
                let local_tag = tag_raw.split(':').next_back().unwrap_or(&tag_raw).to_string();

                match local_tag.as_str() {
                    "SegmentTemplate" => {
                        let mut st_init = None;
                        let mut st_media = None;
                        let mut st_timescale = 1;
                        let mut st_duration = None;
                        let mut st_start_num = 1;

                        for attr in e.attributes().flatten() {
                            let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                            let key_local = key.split(':').next_back().unwrap_or(&key).to_string();
                            let val = String::from_utf8_lossy(&attr.value).to_string();

                            match key_local.as_str() {
                                "initialization" => st_init = Some(val),
                                "media" => st_media = Some(val),
                                "timescale" => st_timescale = val.parse().unwrap_or(1),
                                "duration" => st_duration = val.parse().ok(),
                                "startNumber" => st_start_num = val.parse().unwrap_or(1),
                                _ => {}
                            }
                        }

                        let template = SegmentTemplate {
                            initialization: st_init,
                            media: st_media,
                            timescale: st_timescale,
                            duration: st_duration,
                            start_number: st_start_num,
                        };

                        if let Some(ref mut rep) = current_rep {
                            rep.segment_template = Some(template);
                        } else if let Some(ref mut adapt) = current_adapt {
                            adapt.segment_template = Some(template);
                        }
                    }
                    "SegmentBase" => {
                        let mut sb_index_range = None;
                        let mut sb_timescale = 1;
                        let mut sb_pto = 0;

                        for attr in e.attributes().flatten() {
                            let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                            let key_local = key.split(':').next_back().unwrap_or(&key).to_string();
                            let val = String::from_utf8_lossy(&attr.value).to_string();

                            match key_local.as_str() {
                                "indexRange" => sb_index_range = parse_byte_range(&val),
                                "timescale" => sb_timescale = val.parse().unwrap_or(1),
                                "presentationTimeOffset" => sb_pto = val.parse().unwrap_or(0),
                                _ => {}
                            }
                        }

                        let base = SegmentBase {
                            index_range: sb_index_range,
                            initialization_range: None,
                            timescale: sb_timescale,
                            presentation_time_offset: sb_pto,
                        };

                        if let Some(ref mut rep) = current_rep {
                            rep.segment_base = Some(base);
                        } else if let Some(ref mut adapt) = current_adapt {
                            adapt.segment_base = Some(base);
                        }
                    }
                    "Initialization" => {
                        for attr in e.attributes().flatten() {
                            let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                            let key_local = key.split(':').next_back().unwrap_or(&key).to_string();
                            let val = String::from_utf8_lossy(&attr.value).to_string();

                            if key_local == "range" {
                                let init_range = parse_byte_range(&val);
                                if let Some(ref mut rep) = current_rep
                                    && let Some(ref mut sb) = rep.segment_base {
                                    sb.initialization_range = init_range;
                                } else if let Some(ref mut adapt) = current_adapt
                                    && let Some(ref mut sb) = adapt.segment_base {
                                    sb.initialization_range = init_range;
                                }
                            }
                        }
                    }
                    "Representation" => {
                        let mut r_id = String::new();
                        let mut r_bw = 0;
                        let mut r_w = None;
                        let mut r_h = None;
                        let mut r_codecs = None;
                        let mut r_fps = None;

                        for attr in e.attributes().flatten() {
                            let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                            let key_local = key.split(':').next_back().unwrap_or(&key).to_string();
                            let val = String::from_utf8_lossy(&attr.value).to_string();

                            match key_local.as_str() {
                                "id" => r_id = val,
                                "bandwidth" => r_bw = val.parse().unwrap_or(0),
                                "width" => r_w = val.parse().ok(),
                                "height" => r_h = val.parse().ok(),
                                "codecs" => r_codecs = Some(val),
                                "frameRate" => r_fps = val.parse().ok(),
                                _ => {}
                            }
                        }

                        let rep = DashRepresentation {
                            id: r_id,
                            bandwidth: r_bw,
                            width: r_w,
                            height: r_h,
                            codecs: r_codecs,
                            frame_rate: r_fps,
                            segment_template: None,
                            segment_base: None,
                            base_url: None,
                        };

                        if let Some(ref mut adapt) = current_adapt {
                            adapt.representations.push(rep);
                        }
                    }
                    _ => {}
                }
            }
            Ok(Event::Text(ref e)) => {
                let text = e.unescape().unwrap_or_default().to_string();
                if current_tag == "BaseURL" && !text.is_empty() {
                    let resolved = resolve_url(mpd_url, &text);
                    if let Some(ref mut rep) = current_rep {
                        rep.base_url = Some(resolved);
                    } else {
                        mpd_base_url = Some(resolved);
                    }
                }
            }
            Ok(Event::End(ref e)) => {
                let tag_raw = String::from_utf8_lossy(e.name().as_ref()).to_string();
                let local_tag = tag_raw.split(':').next_back().unwrap_or(&tag_raw).to_string();

                match local_tag.as_str() {
                    "Representation" => {
                        if let Some(rep) = current_rep.take()
                            && let Some(ref mut adapt) = current_adapt {
                            adapt.representations.push(rep);
                        }
                    }
                    "AdaptationSet" => {
                        if let Some(adapt) = current_adapt.take()
                            && let Some(ref mut period) = current_period {
                            period.adaptation_sets.push(adapt);
                        }
                    }
                    "Period" => {
                        if let Some(period) = current_period.take() {
                            periods.push(period);
                        }
                    }
                    _ => {}
                }
                current_tag.clear();
            }
            Ok(Event::Eof) => break,
            Err(e) => return Err(format!("Erro de parse no XML MPD: {e}")),
            _ => {}
        }
        buf.clear();
    }

    if let Some(rep) = current_rep.take()
        && let Some(ref mut adapt) = current_adapt {
        adapt.representations.push(rep);
    }
    if let Some(adapt) = current_adapt.take() {
        if let Some(ref mut period) = current_period {
            period.adaptation_sets.push(adapt);
        } else {
            periods.push(DashPeriod {
                id: None,
                start_sec: None,
                duration_sec,
                adaptation_sets: vec![adapt],
            });
        }
    }
    if let Some(period) = current_period.take() {
        periods.push(period);
    }

    Ok(DashManifest {
        duration_sec,
        min_buffer_time_sec,
        is_dynamic,
        base_url: mpd_base_url,
        periods,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hls::abr::AdaptiveBitrateManager;
    use std::time::Duration;

    #[test]
    fn test_parse_iso8601_duration() {
        assert_eq!(parse_iso8601_duration("PT1H30M"), Some(5400.0));
        assert_eq!(parse_iso8601_duration("PT0H10M0.00S"), Some(600.0));
        assert_eq!(parse_iso8601_duration("PT45.5S"), Some(45.5));
        assert_eq!(parse_iso8601_duration("P1DT2H"), Some(93600.0));
        assert_eq!(parse_iso8601_duration("invalid"), None);
    }

    #[test]
    fn test_resolve_template_url() {
        let t1 = "chunk-$RepresentationID$-$Number$.m4s";
        assert_eq!(resolve_template_url(t1, "video_1080p", 42, 0), "chunk-video_1080p-42.m4s");

        let t2 = "segment_$RepresentationID$_$Number%05d$.m4s";
        assert_eq!(resolve_template_url(t2, "v1", 7, 0), "segment_v1_00007.m4s");

        let t3 = "$RepresentationID$/$Time$.m4s";
        assert_eq!(resolve_template_url(t3, "audio", 0, 192000), "audio/192000.m4s");

        let t4 = "test_$$dollar_$Number$.m4s";
        assert_eq!(resolve_template_url(t4, "v", 1, 0), "test_$dollar_1.m4s");
    }

    #[test]
    fn test_parse_mpd_segment_template() {
        let xml = r#"<?xml version="1.0" encoding="utf-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
     mediaPresentationDuration="PT0H10M0.00S"
     minBufferTime="PT1.5S"
     type="static">
  <Period id="1" duration="PT0H10M0.00S">
    <AdaptationSet mimeType="video/mp4" contentType="video">
      <SegmentTemplate timescale="1000"
                       duration="2000"
                       initialization="init-$RepresentationID$.mp4"
                       media="chunk-$RepresentationID$-$Number%05d$.m4s"
                       startNumber="1" />
      <Representation id="v1" bandwidth="1000000" width="1280" height="720" codecs="avc1.4d401f" />
      <Representation id="v2" bandwidth="3000000" width="1920" height="1080" codecs="avc1.640028" />
    </AdaptationSet>
    <AdaptationSet mimeType="audio/mp4" contentType="audio">
      <Representation id="a1" bandwidth="128000" codecs="mp4a.40.2" />
    </AdaptationSet>
  </Period>
</MPD>"#;

        let manifest = parse_mpd(xml, "https://stream.example.com/live/manifest.mpd").expect("parse_mpd deve ter sucesso");
        assert_eq!(manifest.duration_sec, Some(600.0));
        assert_eq!(manifest.min_buffer_time_sec, Some(1.5));
        assert!(!manifest.is_dynamic);

        let video_reps = manifest.video_representations();
        assert_eq!(video_reps.len(), 2);

        assert_eq!(video_reps[0].id, "v1");
        assert_eq!(video_reps[0].bandwidth, 1_000_000);
        assert_eq!(video_reps[0].width, Some(1280));
        assert_eq!(video_reps[0].height, Some(720));
        let template = video_reps[0].segment_template.as_ref().expect("template herdado");
        assert_eq!(template.timescale, 1000);
        assert_eq!(template.duration, Some(2000));
        assert_eq!(template.initialization.as_deref(), Some("init-$RepresentationID$.mp4"));

        assert_eq!(video_reps[1].id, "v2");
        assert_eq!(video_reps[1].bandwidth, 3_000_000);
    }

    #[test]
    fn test_parse_mpd_segment_base() {
        let xml = r#"<?xml version="1.0"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
     mediaPresentationDuration="PT30S"
     type="static">
  <Period id="0">
    <AdaptationSet mimeType="video/mp4" contentType="video">
      <Representation id="rep_base" bandwidth="2500000" width="1920" height="1080">
        <BaseURL>video.mp4</BaseURL>
        <SegmentBase indexRange="835-1500" timescale="90000">
          <Initialization range="0-834" />
        </SegmentBase>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>"#;

        let manifest = parse_mpd(xml, "https://cdn.example.com/dash/manifest.mpd").expect("parse_mpd base deve passar");
        let reps = manifest.video_representations();
        assert_eq!(reps.len(), 1);
        assert_eq!(reps[0].id, "rep_base");
        assert_eq!(reps[0].base_url.as_deref(), Some("https://cdn.example.com/dash/video.mp4"));

        let sb = reps[0].segment_base.as_ref().expect("segment base esperado");
        assert_eq!(sb.index_range, Some((835, 666))); // 1500 - 835 + 1
        assert_eq!(sb.initialization_range, Some((0, 835))); // 834 - 0 + 1
    }

    #[test]
    fn test_abr_with_dash_representations() {
        let reps = vec![
            DashRepresentation {
                id: "360p".into(),
                bandwidth: 500_000,
                width: Some(640),
                height: Some(360),
                codecs: None,
                frame_rate: None,
                segment_template: None,
                segment_base: None,
                base_url: None,
            },
            DashRepresentation {
                id: "720p".into(),
                bandwidth: 2_000_000,
                width: Some(1280),
                height: Some(720),
                codecs: None,
                frame_rate: None,
                segment_template: None,
                segment_base: None,
                base_url: None,
            },
            DashRepresentation {
                id: "1080p".into(),
                bandwidth: 6_000_000,
                width: Some(1920),
                height: Some(1080),
                codecs: None,
                frame_rate: None,
                segment_template: None,
                segment_base: None,
                base_url: None,
            },
        ];

        let mut abr = AdaptiveBitrateManager::new(reps);
        assert_eq!(abr.current_variant_index(), 1); // Inicia na mediana (720p)

        // 3 downloads lentos reduzem para 360p
        assert!(!abr.record_segment_download(1_000_000, Duration::from_secs(9), 10.0));
        assert!(!abr.record_segment_download(1_000_000, Duration::from_secs(9), 10.0));
        assert!(abr.record_segment_download(1_000_000, Duration::from_secs(9), 10.0));
        assert_eq!(abr.current_variant_index(), 0);
        assert_eq!(abr.current_variant().unwrap().id, "360p");
    }
}
