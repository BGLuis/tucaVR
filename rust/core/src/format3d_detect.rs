//! Detecção de formato 3D via FFmpeg — PHASE-0.2-3D-NETWORK.md, Seção 3.
//!
//! Extrai pistas de formato stereoscópico e projeção a partir de:
//! 1. Tags de container MKV (`stereo_mode`).
//! 2. Stream side data FFmpeg (`Type::Stereo3d`, `Type::DataSpherical`).
//!
//! Combina o resultado com as dimensões de vídeo através de
//! `media_logic::format3d::resolve_container_hint` e delega a priorização final
//! para `media_logic::format3d::detect`.

use ffmpeg_next as ffmpeg;
use media_logic::format3d::{
    detect as detect_logic, resolve_container_hint, DetectionConfidence, Format3D,
    VideoProjection, VideoStereoMode,
};

use crate::demuxer::Demuxer;

/// Analisa os metadados de stream e container do demuxer, filename derivado de `path`
/// e as dimensões `width` e `height`, retornando o `Format3D` e a respectiva `DetectionConfidence`.
pub fn detect(
    demuxer: &Demuxer,
    path: &str,
    width: u32,
    height: u32,
) -> (Format3D, DetectionConfidence) {
    let mut stereo: Option<VideoStereoMode> = None;
    let mut projection: Option<VideoProjection> = None;

    if let Some(video_idx) = demuxer.video_stream_index {
        if let Some(stream) = demuxer.input_context.stream(video_idx) {
            // 1. Tag de container MKV (stereo_mode)
            let meta = stream.metadata();
            if let Some(mode_str) = meta.get("stereo_mode") {
                stereo = match mode_str.to_lowercase().as_str() {
                    "mono" => Some(VideoStereoMode::Mono),
                    "left_right" | "1" | "side_by_side_left_first" => Some(VideoStereoMode::SideBySideLeft),
                    "right_left" | "right_first" => Some(VideoStereoMode::SideBySideRight),
                    "top_bottom" | "top_bottom_left_first" => Some(VideoStereoMode::TopBottomLeft),
                    "bottom_top" | "bottom_top_left_first" => Some(VideoStereoMode::TopBottomRight),
                    _ => None,
                };
            }

            // 2. Stream side data (MP4 / MKV: AVStereo3D e AVSphericalMapping)
            for sd in stream.side_data() {
                match sd.kind() {
                    ffmpeg::codec::packet::side_data::Type::Stereo3d => {
                        let data = sd.data();
                        if data.len() >= 4 && stereo.is_none() {
                            let val = i32::from_ne_bytes(data[0..4].try_into().unwrap_or_default());
                            // AVStereo3DType enum: 0=2D, 1=SBS, 2=TB
                            stereo = match val {
                                0 => Some(VideoStereoMode::Mono),
                                1 => Some(VideoStereoMode::SideBySideLeft),
                                2 => Some(VideoStereoMode::TopBottomLeft),
                                _ => None,
                            };
                        }
                    }
                    ffmpeg::codec::packet::side_data::Type::DataSpherical => {
                        let data = sd.data();
                        if data.len() >= 4 && projection.is_none() {
                            let val = i32::from_ne_bytes(data[0..4].try_into().unwrap_or_default());
                            // AVSphericalProjection enum: 0=Equirect, 1=Cube, 3=HalfEquirect, 4=Rectilinear
                            projection = match val {
                                0 => Some(VideoProjection::Equirectangular),
                                1 => Some(VideoProjection::Cubemap),
                                3 => Some(VideoProjection::HalfEquirectangular),
                                4 => Some(VideoProjection::Rectangular),
                                _ => None,
                            };
                        }
                    }
                    _ => {}
                }
            }
        }
    }

    let container_hint = resolve_container_hint(stereo, projection, width, height);
    let filename = path.rsplit(&['/', '\\'][..]).next().unwrap_or(path);
    detect_logic(filename, width, height, container_hint)
}
