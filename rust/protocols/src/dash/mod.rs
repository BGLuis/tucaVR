//! Módulo MPEG-DASH Streaming (Fase 0.4 · Seção 2).
//!
//! Fornece parser MPD (ISO/IEC 23009-1), download e prefetch de segmentos fMP4,
//! Adaptive Bitrate (ABR) e fonte de streaming bufferizada para o demuxer FFmpeg.

pub mod manifest;
pub mod stream;

pub use manifest::{
    DashAdaptationSet, DashManifest, DashPeriod, DashRepresentation, SegmentBase, SegmentTemplate,
    parse_byte_range, parse_iso8601_duration, parse_mpd, resolve_template_url,
};
pub use stream::{DashStreamSource, fetch_and_probe_representations};
