//! 3D format detection via FFmpeg — PHASE-0.2-3D-NETWORK.md, Section 3.
//!
//! Extracts stereoscopic format and projection cues from:
//! 1. Container / stream tags (MKV `stereo_mode`, `projection_type`, `spherical-video`).
//! 2. FFmpeg stream side data (`Type::Stereo3d`, `Type::DataSpherical`).
//!
//! Combines the result with video dimensions through
//! `media_logic::format3d::resolve_container_hint` and delegates final prioritization
//! to `media_logic::format3d::detect`.

use ffmpeg_next as ffmpeg;
use media_logic::format3d::{
    detect as detect_logic, map_av_spherical_projection, map_av_stereo3d, resolve_container_hint,
    DetectionConfidence, Format3D, ProjectionPose, VideoProjection, VideoStereoMode,
};

use crate::demuxer::Demuxer;

/// Parse stereo mode strings from stream or container metadata.
fn extract_stereo_from_metadata(dict: &ffmpeg::DictionaryRef) -> Option<VideoStereoMode> {
    let mode_str = dict.get("stereo_mode").or_else(|| dict.get("StereoMode"))?;
    match mode_str.trim().to_lowercase().as_str() {
        "mono" | "0" => Some(VideoStereoMode::Mono),
        "left_right" | "1" | "side_by_side_left_first" | "sbs" | "side_by_side" => {
            Some(VideoStereoMode::SideBySideLeft)
        }
        "right_left" | "11" | "right_first" | "side_by_side_right_first" => {
            Some(VideoStereoMode::SideBySideRight)
        }
        "top_bottom" | "3" | "top_bottom_left_first" | "ou" | "over_under" | "tab" => {
            Some(VideoStereoMode::TopBottomLeft)
        }
        "bottom_top" | "2" | "bottom_top_left_first" | "top_bottom_right_first" => {
            Some(VideoStereoMode::TopBottomRight)
        }
        _ => None,
    }
}

/// Parse projection strings or spherical tags from stream or container metadata.
fn extract_projection_from_metadata(dict: &ffmpeg::DictionaryRef) -> Option<VideoProjection> {
    if let Some(proj_str) = dict
        .get("projection")
        .or_else(|| dict.get("projection_type"))
        .or_else(|| dict.get("ProjectionType"))
    {
        let p = proj_str.trim().to_lowercase();
        if p.contains("fisheye") || p.contains("rf52") || p.contains("190") {
            return Some(VideoProjection::Fisheye190);
        } else if p.contains("eac") || p.contains("equiangular") {
            return Some(VideoProjection::EquiangularCubemap);
        } else if p.contains("cubemap") || p.contains("cube") {
            return Some(VideoProjection::Cubemap);
        } else if p.contains("equirect") {
            return Some(VideoProjection::Equirectangular);
        }
    }
    if let Some(spherical) = dict
        .get("spherical-video")
        .or_else(|| dict.get("spherical"))
        .or_else(|| dict.get("SPHERICAL-VIDEO"))
    {
        let s = spherical.trim().to_lowercase();
        if s == "true" || s == "1" || s.contains("equirect") {
            return Some(VideoProjection::Equirectangular);
        } else if s.contains("eac") || s.contains("equiangular") {
            return Some(VideoProjection::EquiangularCubemap);
        } else if s.contains("cubemap") || s.contains("cube") {
            return Some(VideoProjection::Cubemap);
        } else if s.contains("fisheye") {
            return Some(VideoProjection::Fisheye190);
        }
    }
    None
}

/// Inspects stream and container metadata from the demuxer, filename derived from `path`,
/// and video dimensions `width` and `height`, returning the `Format3D`, the respective
/// `DetectionConfidence`, and optionally initial viewing `ProjectionPose` (yaw/pitch/roll).
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
            // 1. Container / stream tag (stereo_mode)
            let meta = stream.metadata();
            stereo = extract_stereo_from_metadata(&meta);

            // 2. Stream side data (MP4 / MKV: AVStereo3D and AVSphericalMapping)
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

            // 3. Container / stream projection tags (e.g. MKV ProjectionType, YouTube/Google spherical)
            if projection.is_none() {
                projection = extract_projection_from_metadata(&meta);
            }
        }
    }

    // 4. Fallback to container-level metadata when tags are not attached to video stream
    let ctx_meta = demuxer.input_context.metadata();
    if stereo.is_none() {
        stereo = extract_stereo_from_metadata(&ctx_meta);
    }
    if projection.is_none() {
        projection = extract_projection_from_metadata(&ctx_meta);
    }

    let container_hint = resolve_container_hint(stereo, projection, width, height);
    let filename = path.rsplit(&['/', '\\'][..]).next().unwrap_or(path);
    let (fmt, confidence) = detect_logic(filename, width, height, container_hint);
    (fmt, confidence, initial_pose)
}

/// Attempts visual stereo inspection on the first decoded video frame from `demuxer`
/// when initial detection yielded `Default` confidence (Flat2D) and the stream is seekable.
/// Restores demuxer seek position back to the beginning upon completion.
pub fn detect_visual_sample(demuxer: &mut Demuxer) -> Option<Format3D> {
    if demuxer.input_context.duration() <= 0 {
        return None;
    }
    let video_idx = demuxer.video_stream_index?;
    let stream = demuxer.input_context.stream(video_idx)?;
    let mut codec_context =
        ffmpeg::codec::context::Context::from_parameters(stream.parameters()).ok()?;
    codec_context.set_threading(ffmpeg::threading::Config {
        kind: ffmpeg::threading::Type::None,
        count: 1,
    });
    let mut decoder = codec_context.decoder().video().ok()?;
    let mut decoded = ffmpeg::frame::Video::empty();
    let mut got_frame = false;
    let mut packets_tried = 0;
    let mut total_packets = 0;

    while packets_tried < 30 && total_packets < 60 {
        match demuxer.read_packet() {
            crate::demuxer::ReadPacketOutcome::Packet(idx, packet) => {
                total_packets += 1;
                if idx == video_idx {
                    packets_tried += 1;
                    if decoder.send_packet(&packet).is_ok()
                        && decoder.receive_frame(&mut decoded).is_ok()
                    {
                        got_frame = true;
                        break;
                    }
                }
            }
            _ => break,
        }
    }

    // Always seek back to the beginning so subsequent playback demuxing starts cleanly
    if demuxer.input_context.seek(0, ..0).is_err() {
        let _ = demuxer.input_context.seek(0, ..);
    }

    if !got_frame {
        return None;
    }

    let w = decoded.width() as usize;
    let h = decoded.height() as usize;
    if w == 0 || h == 0 {
        return None;
    }

    detect_decoded_frame_stereo(&decoded, w, h)
}

/// Evaluates visual stereoscopic detection on a decoded FFmpeg video frame,
/// routing to the appropriate pixel decoder based on pixel format (YUV planar 8-bit,
/// YUV planar 10-bit LE, RGB24, RGBA).
pub fn detect_decoded_frame_stereo(
    decoded: &ffmpeg::frame::Video,
    w: usize,
    h: usize,
) -> Option<Format3D> {
    match decoded.format() {
        ffmpeg::format::Pixel::YUV420P
        | ffmpeg::format::Pixel::YUVJ420P
        | ffmpeg::format::Pixel::YUV422P
        | ffmpeg::format::Pixel::YUVJ422P
        | ffmpeg::format::Pixel::YUV444P
        | ffmpeg::format::Pixel::YUVJ444P
        | ffmpeg::format::Pixel::NV12
        | ffmpeg::format::Pixel::NV21 => {
            let luma = decoded.data(0);
            let stride = decoded.stride(0);
            media_logic::pixel_detect::detect_stereo_from_luma(luma, w, h, stride)
        }
        ffmpeg::format::Pixel::YUV420P10LE
        | ffmpeg::format::Pixel::YUV422P10LE
        | ffmpeg::format::Pixel::YUV444P10LE => {
            let luma = decoded.data(0);
            let stride = decoded.stride(0);
            media_logic::pixel_detect::detect_stereo_from_luma10le(luma, w, h, stride)
        }
        ffmpeg::format::Pixel::RGB24 => {
            let data = decoded.data(0);
            let stride = decoded.stride(0);
            media_logic::pixel_detect::detect_stereo_from_rgb(data, w, h, stride)
        }
        ffmpeg::format::Pixel::RGBA => {
            let data = decoded.data(0);
            let stride = decoded.stride(0);
            media_logic::pixel_detect::detect_stereo_from_rgba(data, w, h, stride)
        }
        _ => {
            let luma = decoded.data(0);
            let stride = decoded.stride(0);
            media_logic::pixel_detect::detect_stereo_from_luma(luma, w, h, stride)
        }
    }
}
