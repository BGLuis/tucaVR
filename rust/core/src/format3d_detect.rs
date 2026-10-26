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
    detect as detect_logic, map_av_spherical_projection, map_av_stereo3d, resolve_container_hint,
    DetectionConfidence, Format3D, ProjectionPose, VideoProjection, VideoStereoMode,
};

use crate::demuxer::Demuxer;

/// Analisa os metadados de stream e container do demuxer, filename derivado de `path`
/// e as dimensões `width` e `height`, retornando o `Format3D`, a respectiva `DetectionConfidence`
/// e opcionalmente a `ProjectionPose` inicial de visualização (yaw/pitch/roll).
pub fn detect(
    demuxer: &Demuxer,
    path: &str,
    width: u32,
    height: u32,
) -> (Format3D, DetectionConfidence, Option<ProjectionPose>) {
    let mut stereo: Option<VideoStereoMode> = None;
    let mut projection: Option<VideoProjection> = None;
    let mut initial_pose: Option<ProjectionPose> = None;

    if let Some(video_idx) = demuxer.video_stream_index {
        if let Some(stream) = demuxer.input_context.stream(video_idx) {
            // 1. Tag de container MKV (stereo_mode)
            let meta = stream.metadata();
            if let Some(mode_str) = meta.get("stereo_mode") {
                stereo = match mode_str.to_lowercase().as_str() {
                    "mono" => Some(VideoStereoMode::Mono),
                    "left_right" | "1" | "side_by_side_left_first" => {
                        Some(VideoStereoMode::SideBySideLeft)
                    }
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
                        if data.len() >= std::mem::size_of::<ffmpeg::ffi::AVStereo3D>()
                            && stereo.is_none()
                        {
                            let s: ffmpeg::ffi::AVStereo3D = unsafe {
                                std::ptr::read_unaligned(
                                    data.as_ptr() as *const ffmpeg::ffi::AVStereo3D
                                )
                            };
                            stereo = map_av_stereo3d(s.type_ as i32, s.flags);
                        } else if data.len() >= 8 && stereo.is_none() {
                            let val = i32::from_ne_bytes(data[0..4].try_into().unwrap_or_default());
                            let flags =
                                i32::from_ne_bytes(data[4..8].try_into().unwrap_or_default());
                            stereo = map_av_stereo3d(val, flags);
                        }
                    }
                    ffmpeg::codec::packet::side_data::Type::DataSpherical => {
                        let data = sd.data();
                        if data.len() >= std::mem::size_of::<ffmpeg::ffi::AVSphericalMapping>()
                            && projection.is_none()
                        {
                            let m: ffmpeg::ffi::AVSphericalMapping = unsafe {
                                std::ptr::read_unaligned(
                                    data.as_ptr() as *const ffmpeg::ffi::AVSphericalMapping
                                )
                            };
                            projection = map_av_spherical_projection(
                                m.projection as i32,
                                m.bound_left,
                                m.bound_top,
                                m.bound_right,
                                m.bound_bottom,
                            );
                            if initial_pose.is_none() {
                                let pose =
                                    ProjectionPose::from_ffmpeg_fixed(m.yaw, m.pitch, m.roll);
                                if pose.is_nonzero() {
                                    initial_pose = Some(pose);
                                }
                            }
                        } else if data.len() >= 4 && projection.is_none() {
                            let val = i32::from_ne_bytes(data[0..4].try_into().unwrap_or_default());
                            projection = map_av_spherical_projection(val, 0, 0, 0, 0);
                        }
                    }
                    _ => {}
                }
            }

            // 3. Tags de projeção de container/stream (ex: MKV ProjectionType, YouTube/Google spherical)
            if projection.is_none() {
                let tag_candidate = meta
                    .get("projection")
                    .or_else(|| meta.get("projection_type"))
                    .or_else(|| meta.get("spherical-video"));
                if let Some(proj_str) = tag_candidate {
                    let p_lower = proj_str.to_lowercase();
                    if p_lower.contains("fisheye")
                        || p_lower.contains("rf52")
                        || p_lower.contains("190")
                    {
                        projection = Some(VideoProjection::Fisheye190);
                    } else if p_lower.contains("eac") || p_lower.contains("equiangular") {
                        projection = Some(VideoProjection::EquiangularCubemap);
                    } else if p_lower.contains("cubemap") || p_lower.contains("cube") {
                        projection = Some(VideoProjection::Cubemap);
                    } else if p_lower.contains("equirect") {
                        projection = Some(VideoProjection::Equirectangular);
                    }
                }
            }
        }
    }

    let container_hint = resolve_container_hint(stereo, projection, width, height);
    let filename = path.rsplit(&['/', '\\'][..]).next().unwrap_or(path);
    let (fmt, confidence) = detect_logic(filename, width, height, container_hint);
    (fmt, confidence, initial_pose)
}
