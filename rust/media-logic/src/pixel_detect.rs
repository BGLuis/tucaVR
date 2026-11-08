//! Visual stereoscopic format detection via frame pixel analysis.
//!
//! Inspired by 4XVR's `TestVideoType`, this module evaluates Left-vs-Right (SBS)
//! and Top-vs-Bottom (Over-Under) visual correlation on decoded planar luma or
//! RGB/RGBA frames when container metadata, filename patterns, and resolution heuristics
//! return no signal (falling back to Flat 2D).
//!
//! # Architecture & Performance Constraints
//! - Must execute in < 5ms without stalling the pipeline.
//! - Uses subsampled 2D grid sampling (48x32 per eye/half) to achieve sub-millisecond execution.
//! - Protects against false positives with:
//!   1. Dark frame / low-contrast guard: rejects frames with low luminance variance (std dev < 12.0)
//!      or low mean brightness.
//!   2. Exposure parity check: ensures Left/Right or Top/Bottom halves have balanced luminance.
//!   3. Normalized cross-correlation (Pearson r) with horizontal/vertical disparity search.
//!   4. Mean Absolute Error (MAE) check between corresponding eye regions.
//!   5. Relative correlation margin: SBS must significantly exceed OU (and vice versa) to prevent
//!      ambiguous triggers on symmetric 2D scenes.

use crate::format3d::Format3D;

pub const GRID_COLS: usize = 48;
pub const GRID_ROWS: usize = 32;

pub const MIN_STD_DEV: f32 = 12.0;
pub const MIN_MEAN_BRIGHTNESS: f32 = 10.0;
pub const MAX_EXPOSURE_DIFF: f32 = 30.0;
pub const MIN_CORRELATION: f32 = 0.78;
pub const MAX_MAE: f32 = 35.0;
pub const CORRELATION_MARGIN: f32 = 0.15;

/// Generic source of pixel luminance.
pub trait PixelSource {
    fn width(&self) -> usize;
    fn height(&self) -> usize;
    fn get_luma(&self, x: usize, y: usize) -> f32;
}

/// Planar luma view (such as the Y plane of decoded YUV420p video).
pub struct LumaPlane<'a> {
    pub data: &'a [u8],
    pub width: usize,
    pub height: usize,
    pub stride: usize,
}

impl<'a> PixelSource for LumaPlane<'a> {
    #[inline]
    fn width(&self) -> usize {
        self.width
    }

    #[inline]
    fn height(&self) -> usize {
        self.height
    }

    #[inline]
    fn get_luma(&self, x: usize, y: usize) -> f32 {
        let x = x.min(self.width.saturating_sub(1));
        let y = y.min(self.height.saturating_sub(1));
        let offset = y * self.stride + x;
        if offset < self.data.len() {
            self.data[offset] as f32
        } else {
            0.0
        }
    }
}

/// Planar 10-bit luma view (little-endian 16-bit words per pixel, such as YUV420p10le).
pub struct LumaPlane10Le<'a> {
    pub data: &'a [u8],
    pub width: usize,
    pub height: usize,
    pub stride: usize,
}

impl<'a> PixelSource for LumaPlane10Le<'a> {
    #[inline]
    fn width(&self) -> usize {
        self.width
    }

    #[inline]
    fn height(&self) -> usize {
        self.height
    }

    #[inline]
    fn get_luma(&self, x: usize, y: usize) -> f32 {
        let x = x.min(self.width.saturating_sub(1));
        let y = y.min(self.height.saturating_sub(1));
        let offset = y * self.stride + x * 2;
        if offset + 1 < self.data.len() {
            let sample = u16::from_le_bytes([self.data[offset], self.data[offset + 1]]);
            // Map 10-bit range [0, 1023] to standard [0.0, 255.0]
            (sample.min(1023) as f32) * (255.0 / 1023.0)
        } else {
            0.0
        }
    }
}

/// Packed 32-bit RGBA view.
pub struct RgbaPlane<'a> {
    pub data: &'a [u8],
    pub width: usize,
    pub height: usize,
    pub stride: usize,
}

impl<'a> PixelSource for RgbaPlane<'a> {
    #[inline]
    fn width(&self) -> usize {
        self.width
    }

    #[inline]
    fn height(&self) -> usize {
        self.height
    }

    #[inline]
    fn get_luma(&self, x: usize, y: usize) -> f32 {
        let x = x.min(self.width.saturating_sub(1));
        let y = y.min(self.height.saturating_sub(1));
        let offset = y * self.stride + x * 4;
        if offset + 2 < self.data.len() {
            let r = self.data[offset] as u32;
            let g = self.data[offset + 1] as u32;
            let b = self.data[offset + 2] as u32;
            ((77 * r + 150 * g + 29 * b) >> 8) as f32
        } else {
            0.0
        }
    }
}

/// Packed 24-bit RGB view.
pub struct RgbPlane<'a> {
    pub data: &'a [u8],
    pub width: usize,
    pub height: usize,
    pub stride: usize,
}

impl<'a> PixelSource for RgbPlane<'a> {
    #[inline]
    fn width(&self) -> usize {
        self.width
    }

    #[inline]
    fn height(&self) -> usize {
        self.height
    }

    #[inline]
    fn get_luma(&self, x: usize, y: usize) -> f32 {
        let x = x.min(self.width.saturating_sub(1));
        let y = y.min(self.height.saturating_sub(1));
        let offset = y * self.stride + x * 3;
        if offset + 2 < self.data.len() {
            let r = self.data[offset] as u32;
            let g = self.data[offset + 1] as u32;
            let b = self.data[offset + 2] as u32;
            ((77 * r + 150 * g + 29 * b) >> 8) as f32
        } else {
            0.0
        }
    }
}

/// Detailed metrics from visual stereo inspection.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct StereoAnalysisResult {
    pub detected_format: Option<Format3D>,
    pub sbs_correlation: f32,
    pub sbs_mae: f32,
    pub ou_correlation: f32,
    pub ou_mae: f32,
    pub left_std_dev: f32,
    pub right_std_dev: f32,
    pub top_std_dev: f32,
    pub bottom_std_dev: f32,
}

/// Evaluates stereoscopic layout on planar luma data.
pub fn detect_stereo_from_luma(
    data: &[u8],
    width: usize,
    height: usize,
    stride: usize,
) -> Option<Format3D> {
    let plane = LumaPlane {
        data,
        width,
        height,
        stride,
    };
    detect_stereo_frame(&plane)
}

/// Evaluates stereoscopic layout on planar 10-bit luma data (little-endian u16 words).
pub fn detect_stereo_from_luma10le(
    data: &[u8],
    width: usize,
    height: usize,
    stride: usize,
) -> Option<Format3D> {
    let plane = LumaPlane10Le {
        data,
        width,
        height,
        stride,
    };
    detect_stereo_frame(&plane)
}

/// Evaluates stereoscopic layout on packed RGBA data.
pub fn detect_stereo_from_rgba(
    data: &[u8],
    width: usize,
    height: usize,
    stride: usize,
) -> Option<Format3D> {
    let plane = RgbaPlane {
        data,
        width,
        height,
        stride,
    };
    detect_stereo_frame(&plane)
}

/// Evaluates stereoscopic layout on packed RGB data.
pub fn detect_stereo_from_rgb(
    data: &[u8],
    width: usize,
    height: usize,
    stride: usize,
) -> Option<Format3D> {
    let plane = RgbPlane {
        data,
        width,
        height,
        stride,
    };
    detect_stereo_frame(&plane)
}

/// Evaluates stereoscopic layout from any generic pixel source.
pub fn detect_stereo_frame<P: PixelSource>(frame: &P) -> Option<Format3D> {
    analyze_stereo_frame(frame).detected_format
}

/// Performs full visual analysis on a pixel source and returns the detailed metrics.
pub fn analyze_stereo_frame<P: PixelSource>(frame: &P) -> StereoAnalysisResult {
    let w = frame.width();
    let h = frame.height();

    if w < 64 || h < 32 {
        return StereoAnalysisResult {
            detected_format: None,
            sbs_correlation: -1.0,
            sbs_mae: 255.0,
            ou_correlation: -1.0,
            ou_mae: 255.0,
            left_std_dev: 0.0,
            right_std_dev: 0.0,
            top_std_dev: 0.0,
            bottom_std_dev: 0.0,
        };
    }

    // 1. Sample Left and Right eye grids for SBS evaluation
    let half_w = w / 2;
    let mut left_grid = [[0.0f32; GRID_COLS]; GRID_ROWS];
    let mut right_grid = [[0.0f32; GRID_COLS]; GRID_ROWS];
    sample_sub_region(frame, 0, 0, half_w, h, &mut left_grid);
    sample_sub_region(frame, half_w, 0, w - half_w, h, &mut right_grid);

    let (left_mean, left_std) = compute_grid_stats(&left_grid);
    let (right_mean, right_std) = compute_grid_stats(&right_grid);

    let sbs_valid = left_std >= MIN_STD_DEV
        && right_std >= MIN_STD_DEV
        && (left_mean - right_mean).abs() <= MAX_EXPOSURE_DIFF
        && left_mean >= MIN_MEAN_BRIGHTNESS
        && right_mean >= MIN_MEAN_BRIGHTNESS;

    let (best_sbs_r, best_sbs_mae) = if sbs_valid {
        let mut best_r = -1.0f32;
        let mut best_mae = 255.0f32;
        for dx in -2..=2 {
            let (r, mae) = correlate_2d_shift(&left_grid, &right_grid, dx, 0);
            if r > best_r {
                best_r = r;
                best_mae = mae;
            }
        }
        (best_r, best_mae)
    } else {
        (-1.0, 255.0)
    };

    // 2. Sample Top and Bottom eye grids for OU evaluation
    let half_h = h / 2;
    let mut top_grid = [[0.0f32; GRID_COLS]; GRID_ROWS];
    let mut bottom_grid = [[0.0f32; GRID_COLS]; GRID_ROWS];
    sample_sub_region(frame, 0, 0, w, half_h, &mut top_grid);
    sample_sub_region(frame, 0, half_h, w, h - half_h, &mut bottom_grid);

    let (top_mean, top_std) = compute_grid_stats(&top_grid);
    let (bottom_mean, bottom_std) = compute_grid_stats(&bottom_grid);

    let ou_valid = top_std >= MIN_STD_DEV
        && bottom_std >= MIN_STD_DEV
        && (top_mean - bottom_mean).abs() <= MAX_EXPOSURE_DIFF
        && top_mean >= MIN_MEAN_BRIGHTNESS
        && bottom_mean >= MIN_MEAN_BRIGHTNESS;

    let (best_ou_r, best_ou_mae) = if ou_valid {
        let mut best_r = -1.0f32;
        let mut best_mae = 255.0f32;
        for dy in -1..=1 {
            for dx in -1..=1 {
                let (r, mae) = correlate_2d_shift(&top_grid, &bottom_grid, dx, dy);
                if r > best_r {
                    best_r = r;
                    best_mae = mae;
                }
            }
        }
        (best_r, best_mae)
    } else {
        (-1.0, 255.0)
    };

    // 3. Promote format based on correlation and disparity margin
    let aspect = w as f64 / h as f64;
    let detected_format = if best_sbs_r >= MIN_CORRELATION
        && best_sbs_mae <= MAX_MAE
        && best_sbs_r > best_ou_r + CORRELATION_MARGIN
    {
        if aspect >= 2.4 {
            Some(Format3D::SbsFull)
        } else if aspect >= 1.15 {
            Some(Format3D::SbsHalf)
        } else {
            None
        }
    } else if best_ou_r >= MIN_CORRELATION
        && best_ou_mae <= MAX_MAE
        && best_ou_r > best_sbs_r + CORRELATION_MARGIN
    {
        if (0.65..=1.25).contains(&aspect) {
            Some(Format3D::OverUnderFull)
        } else if aspect >= 1.25 {
            Some(Format3D::OverUnderHalf)
        } else {
            None
        }
    } else {
        None
    };

    StereoAnalysisResult {
        detected_format,
        sbs_correlation: best_sbs_r,
        sbs_mae: best_sbs_mae,
        ou_correlation: best_ou_r,
        ou_mae: best_ou_mae,
        left_std_dev: left_std,
        right_std_dev: right_std,
        top_std_dev: top_std,
        bottom_std_dev: bottom_std,
    }
}

/// Samples a bounding box from the frame into a normalized `GRID_ROWS` x `GRID_COLS` array
/// using a 2x2 box filter. Clamps sampling strictly within region boundaries to prevent
/// eye-boundary cross-contamination.
fn sample_sub_region<P: PixelSource>(
    frame: &P,
    reg_x: usize,
    reg_y: usize,
    reg_w: usize,
    reg_h: usize,
    out: &mut [[f32; GRID_COLS]; GRID_ROWS],
) {
    let max_x = reg_x + reg_w.saturating_sub(1);
    let max_y = reg_y + reg_h.saturating_sub(1);
    for (r, out_row) in out.iter_mut().enumerate() {
        let y = (reg_y + (r * reg_h + reg_h / 2) / GRID_ROWS).min(max_y);
        let y1 = (y + 1).min(max_y);
        for (c, out_pixel) in out_row.iter_mut().enumerate() {
            let x = (reg_x + (c * reg_w + reg_w / 2) / GRID_COLS).min(max_x);
            let x1 = (x + 1).min(max_x);
            let p00 = frame.get_luma(x, y);
            let p10 = frame.get_luma(x1, y);
            let p01 = frame.get_luma(x, y1);
            let p11 = frame.get_luma(x1, y1);
            *out_pixel = (p00 + p10 + p01 + p11) * 0.25;
        }
    }
}

/// Computes the mean and standard deviation of pixel values in a grid.
fn compute_grid_stats(grid: &[[f32; GRID_COLS]; GRID_ROWS]) -> (f32, f32) {
    let n = (GRID_ROWS * GRID_COLS) as f32;
    let mut sum = 0.0f32;
    for row in grid {
        for &val in row {
            sum += val;
        }
    }
    let mean = sum / n;
    let mut var_sum = 0.0f32;
    for row in grid {
        for &val in row {
            let diff = val - mean;
            var_sum += diff * diff;
        }
    }
    let std_dev = (var_sum / n).sqrt();
    (mean, std_dev)
}

/// Computes Pearson correlation r and MAE between two grids with integer horizontal (dx)
/// and vertical (dy) shifts.
fn correlate_2d_shift(
    a: &[[f32; GRID_COLS]; GRID_ROWS],
    b: &[[f32; GRID_COLS]; GRID_ROWS],
    dx: i32,
    dy: i32,
) -> (f32, f32) {
    let (c_start_a, c_start_b, col_count) = if dx >= 0 {
        let udx = dx as usize;
        (0, udx, GRID_COLS.saturating_sub(udx))
    } else {
        let udx = (-dx) as usize;
        (udx, 0, GRID_COLS.saturating_sub(udx))
    };

    let (r_start_a, r_start_b, row_count) = if dy >= 0 {
        let udy = dy as usize;
        (0, udy, GRID_ROWS.saturating_sub(udy))
    } else {
        let udy = (-dy) as usize;
        (udy, 0, GRID_ROWS.saturating_sub(udy))
    };

    let n = (row_count * col_count) as f32;
    if n <= 1.0 {
        return (-1.0, 255.0);
    }

    let mut sum_a = 0.0f32;
    let mut sum_b = 0.0f32;
    let mut mae_sum = 0.0f32;

    for r in 0..row_count {
        for c in 0..col_count {
            let val_a = a[r_start_a + r][c_start_a + c];
            let val_b = b[r_start_b + r][c_start_b + c];
            sum_a += val_a;
            sum_b += val_b;
            mae_sum += (val_a - val_b).abs();
        }
    }

    let mean_a = sum_a / n;
    let mean_b = sum_b / n;
    let mae = mae_sum / n;

    let mut cov = 0.0f32;
    let mut var_a = 0.0f32;
    let mut var_b = 0.0f32;

    for r in 0..row_count {
        for c in 0..col_count {
            let diff_a = a[r_start_a + r][c_start_a + c] - mean_a;
            let diff_b = b[r_start_b + r][c_start_b + c] - mean_b;
            cov += diff_a * diff_b;
            var_a += diff_a * diff_a;
            var_b += diff_b * diff_b;
        }
    }

    let denom = (var_a * var_b).sqrt();
    let r_coeff = if denom > 1e-4 { cov / denom } else { -1.0 };

    (r_coeff, mae)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn generate_synthetic_pattern(width: usize, height: usize) -> Vec<u8> {
        let mut buf = vec![0u8; width * height];
        for y in 0..height {
            let y_norm = y as f32 / height as f32;
            for x in 0..width {
                let x_norm = x as f32 / width as f32;
                // Spatial gradients and macroscopic shapes typical of real camera frames
                let grad = x_norm * 110.0 + y_norm * 75.0;
                let wave = ((x_norm * std::f32::consts::TAU * 3.0).sin() * 35.0)
                    + ((y_norm * std::f32::consts::TAU * 2.0).cos() * 25.0);
                let val = (grad + wave + 25.0).clamp(0.0, 255.0) as u8;
                buf[y * width + x] = val;
            }
        }
        buf
    }

    #[test]
    fn synthetic_sbs_half_detected() {
        let eye_w = 960;
        let eye_h = 1080;
        let single_eye = generate_synthetic_pattern(eye_w, eye_h);

        let frame_w = eye_w * 2;
        let frame_h = eye_h;
        let mut frame = vec![0u8; frame_w * frame_h];

        // Fill Left and Right eyes with identical content (with small 5px disparity)
        for y in 0..eye_h {
            for x in 0..eye_w {
                frame[y * frame_w + x] = single_eye[y * eye_w + x];
                let right_x = (x + 5).min(eye_w - 1);
                frame[y * frame_w + eye_w + x] = single_eye[y * eye_w + right_x];
            }
        }

        let detected = detect_stereo_from_luma(&frame, frame_w, frame_h, frame_w);
        assert_eq!(detected, Some(Format3D::SbsHalf));
    }

    #[test]
    fn synthetic_over_under_half_detected() {
        let eye_w = 1920;
        let eye_h = 540;
        let single_eye = generate_synthetic_pattern(eye_w, eye_h);

        let frame_w = eye_w;
        let frame_h = eye_h * 2;
        let mut frame = vec![0u8; frame_w * frame_h];

        // Fill Top and Bottom eyes with identical content (with small disparity)
        for y in 0..eye_h {
            for x in 0..eye_w {
                frame[y * frame_w + x] = single_eye[y * eye_w + x];
                let disp_x = (x + 4).min(eye_w - 1);
                frame[(y + eye_h) * frame_w + x] = single_eye[y * eye_w + disp_x];
            }
        }

        let detected = detect_stereo_from_luma(&frame, frame_w, frame_h, frame_w);
        assert_eq!(detected, Some(Format3D::OverUnderHalf));
    }

    #[test]
    fn synthetic_asymmetric_2d_returns_none() {
        let frame_w = 1920;
        let frame_h = 1080;
        let mut frame = vec![0u8; frame_w * frame_h];

        // Left half: pattern 1; Right half: completely different pattern 2
        for y in 0..frame_h {
            for x in 0..frame_w {
                if x < frame_w / 2 {
                    frame[y * frame_w + x] = ((x * 11 + y * 7) % 200 + 30) as u8;
                } else {
                    frame[y * frame_w + x] = (((frame_w - x) * 19 + y * 31) % 180 + 40) as u8;
                }
            }
        }

        let detected = detect_stereo_from_luma(&frame, frame_w, frame_h, frame_w);
        assert_eq!(detected, None);
    }

    #[test]
    fn solid_black_frame_returns_none() {
        let frame_w = 1920;
        let frame_h = 1080;
        let frame = vec![0u8; frame_w * frame_h];

        let detected = detect_stereo_from_luma(&frame, frame_w, frame_h, frame_w);
        assert_eq!(detected, None);
    }

    #[test]
    fn low_contrast_dark_intro_returns_none() {
        let frame_w = 1920;
        let frame_h = 1080;
        let mut frame = vec![2u8; frame_w * frame_h];
        // Add subtle sensor noise (+-1)
        for (i, p) in frame.iter_mut().enumerate() {
            *p = 2 + (i % 3) as u8;
        }

        let detected = detect_stereo_from_luma(&frame, frame_w, frame_h, frame_w);
        assert_eq!(detected, None);
    }

    #[test]
    fn centered_logo_on_black_returns_none() {
        let frame_w = 1920;
        let frame_h = 1080;
        let mut frame = vec![0u8; frame_w * frame_h];

        // Draw a small bright centered square
        let center_x = frame_w / 2;
        let center_y = frame_h / 2;
        let half_size = 60;
        for y in (center_y - half_size)..(center_y + half_size) {
            for x in (center_x - half_size)..(center_x + half_size) {
                frame[y * frame_w + x] = 240;
            }
        }

        let detected = detect_stereo_from_luma(&frame, frame_w, frame_h, frame_w);
        assert_eq!(detected, None);
    }

    #[test]
    fn horizontal_mirror_reflection_returns_none() {
        let eye_w = 960;
        let eye_h = 1080;
        let pattern = generate_synthetic_pattern(eye_w, eye_h);

        let frame_w = eye_w * 2;
        let frame_h = eye_h;
        let mut frame = vec![0u8; frame_w * frame_h];

        // Left half is pattern; Right half is horizontal reflection (mirrored)
        for y in 0..eye_h {
            for x in 0..eye_w {
                frame[y * frame_w + x] = pattern[y * eye_w + x];
                frame[y * frame_w + eye_w + x] = pattern[y * eye_w + (eye_w - 1 - x)];
            }
        }

        let detected = detect_stereo_from_luma(&frame, frame_w, frame_h, frame_w);
        assert_eq!(
            detected, None,
            "Mirror reflection must not trigger stereo translation detection"
        );
    }

    #[test]
    fn rgba_and_rgb_sources_match_luma() {
        let eye_w = 480;
        let eye_h = 540;
        let single_eye = generate_synthetic_pattern(eye_w, eye_h);
        let frame_w = eye_w * 2;
        let frame_h = eye_h;

        let mut luma = vec![0u8; frame_w * frame_h];
        let mut rgba = vec![0u8; frame_w * frame_h * 4];
        let mut rgb = vec![0u8; frame_w * frame_h * 3];

        for y in 0..eye_h {
            for x in 0..eye_w {
                let v = single_eye[y * eye_w + x];
                // Left
                luma[y * frame_w + x] = v;
                rgba[(y * frame_w + x) * 4] = v;
                rgba[(y * frame_w + x) * 4 + 1] = v;
                rgba[(y * frame_w + x) * 4 + 2] = v;
                rgba[(y * frame_w + x) * 4 + 3] = 255;
                rgb[(y * frame_w + x) * 3] = v;
                rgb[(y * frame_w + x) * 3 + 1] = v;
                rgb[(y * frame_w + x) * 3 + 2] = v;

                // Right
                luma[y * frame_w + eye_w + x] = v;
                rgba[(y * frame_w + eye_w + x) * 4] = v;
                rgba[(y * frame_w + eye_w + x) * 4 + 1] = v;
                rgba[(y * frame_w + eye_w + x) * 4 + 2] = v;
                rgba[(y * frame_w + eye_w + x) * 4 + 3] = 255;
                rgb[(y * frame_w + eye_w + x) * 3] = v;
                rgb[(y * frame_w + eye_w + x) * 3 + 1] = v;
                rgb[(y * frame_w + eye_w + x) * 3 + 2] = v;
            }
        }

        assert_eq!(
            detect_stereo_from_luma(&luma, frame_w, frame_h, frame_w),
            Some(Format3D::SbsHalf)
        );
        assert_eq!(
            detect_stereo_from_rgba(&rgba, frame_w, frame_h, frame_w * 4),
            Some(Format3D::SbsHalf)
        );
        assert_eq!(
            detect_stereo_from_rgb(&rgb, frame_w, frame_h, frame_w * 3),
            Some(Format3D::SbsHalf)
        );
    }

    #[test]
    fn performance_under_5ms_on_1080p_frame() {
        let eye_w = 960;
        let eye_h = 1080;
        let single_eye = generate_synthetic_pattern(eye_w, eye_h);
        let frame_w = eye_w * 2;
        let frame_h = eye_h;
        let mut frame = vec![0u8; frame_w * frame_h];
        for y in 0..eye_h {
            for x in 0..eye_w {
                frame[y * frame_w + x] = single_eye[y * eye_w + x];
                frame[y * frame_w + eye_w + x] = single_eye[y * eye_w + x];
            }
        }

        let start = std::time::Instant::now();
        let detected = detect_stereo_from_luma(&frame, frame_w, frame_h, frame_w);
        let elapsed = start.elapsed();

        assert_eq!(detected, Some(Format3D::SbsHalf));
        assert!(
            elapsed.as_millis() < 5,
            "Execution took {:?}, expected < 5ms",
            elapsed
        );
    }

    #[test]
    fn luma10le_detected_parity_with_8bit() {
        let eye_w = 480;
        let eye_h = 540;
        let single_eye = generate_synthetic_pattern(eye_w, eye_h);
        let frame_w = eye_w * 2;
        let frame_h = eye_h;

        // 10-bit planar YUV buffer: 2 bytes per pixel (u16 little endian)
        let stride_bytes = frame_w * 2;
        let mut luma10 = vec![0u8; stride_bytes * frame_h];

        for y in 0..eye_h {
            for x in 0..eye_w {
                let v = single_eye[y * eye_w + x] as u16;
                // Scale 8-bit to 10-bit: 0..255 -> 0..1020
                let v10 = (v << 2) | (v >> 6);

                // Left eye
                let left_idx = y * stride_bytes + x * 2;
                let b = v10.to_le_bytes();
                luma10[left_idx] = b[0];
                luma10[left_idx + 1] = b[1];

                // Right eye with small disparity
                let right_x = (x + 3).min(eye_w - 1);
                let v_right = single_eye[y * eye_w + right_x] as u16;
                let v10_right = (v_right << 2) | (v_right >> 6);
                let right_idx = y * stride_bytes + (eye_w + x) * 2;
                let b_r = v10_right.to_le_bytes();
                luma10[right_idx] = b_r[0];
                luma10[right_idx + 1] = b_r[1];
            }
        }

        let detected = detect_stereo_from_luma10le(&luma10, frame_w, frame_h, stride_bytes);
        assert_eq!(detected, Some(Format3D::SbsHalf));
    }

    #[test]
    fn asymmetric_dark_eye_returns_none() {
        let eye_w = 480;
        let eye_h = 540;
        let frame_w = eye_w * 2;
        let frame_h = eye_h;
        let mut frame = vec![0u8; frame_w * frame_h];

        // Left eye has bright non-repeating content (gradient from top to bottom);
        // Right eye is pitch black (mean ~2)
        for y in 0..eye_h {
            let y_val = (y as f32 / eye_h as f32 * 180.0 + 40.0) as u8;
            for x in 0..eye_w {
                let x_val = (x as f32 / eye_w as f32 * 30.0) as u8;
                frame[y * frame_w + x] = y_val.saturating_add(x_val);
                frame[y * frame_w + eye_w + x] = 2;
            }
        }

        let detected = detect_stereo_from_luma(&frame, frame_w, frame_h, frame_w);
        assert_eq!(
            detected, None,
            "Frame with one pitch black eye must be rejected as invalid stereo"
        );
    }

    #[test]
    fn portrait_frame_returns_none() {
        // Vertical mobile video (9:16 aspect ratio: 1080x1920)
        let frame_w = 1080;
        let frame_h = 1920;
        let single_eye = generate_synthetic_pattern(frame_w, frame_h / 2);
        let mut frame = vec![0u8; frame_w * frame_h];

        // Even if top and bottom are similar, aspect ratio 0.5625 is not legal OU
        for y in 0..(frame_h / 2) {
            for x in 0..frame_w {
                let v = single_eye[y * frame_w + x];
                frame[y * frame_w + x] = v;
                frame[(y + frame_h / 2) * frame_w + x] = v;
            }
        }

        let detected = detect_stereo_from_luma(&frame, frame_w, frame_h, frame_w);
        assert_eq!(
            detected, None,
            "Portrait frame (aspect < 0.65) must not be auto-promoted to OverUnder"
        );
    }
}
