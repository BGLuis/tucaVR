//! Pure display refresh rate selection and video cadence matching.
//!
//! Matches display refresh rates to whole multiples of video frame rates to
//! eliminate 3:2 pull-down judder without depending on Android or OpenXR.
//! Enables 100% host testing via `cargo test -p media-logic refresh`.

/// Relative tolerance for cadence matching and NTSC fractional rates (~0.1% for 24000/1001 ≈ 0.0999%).
pub const CADENCE_TOLERANCE: f32 = 0.0011;

/// Normalizes standard video frame rates (e.g. NTSC 23.976, 29.97, 59.94)
/// to their nominal integer cadences if within 0.1% tolerance.
pub fn normalize_fps(fps: f32) -> f32 {
    if fps <= 0.0 {
        return 0.0;
    }
    // 24000 / 1001 ≈ 23.976024
    if (fps - 24.0 / 1.001).abs() / 24.0 <= CADENCE_TOLERANCE
        || (fps - 24.0).abs() / 24.0 <= CADENCE_TOLERANCE
    {
        24.0
    } else if (fps - 30.0 / 1.001).abs() / 30.0 <= CADENCE_TOLERANCE
        || (fps - 30.0).abs() / 30.0 <= CADENCE_TOLERANCE
    {
        30.0
    } else if (fps - 48.0 / 1.001).abs() / 48.0 <= CADENCE_TOLERANCE
        || (fps - 48.0).abs() / 48.0 <= CADENCE_TOLERANCE
    {
        48.0
    } else if (fps - 60.0 / 1.001).abs() / 60.0 <= CADENCE_TOLERANCE
        || (fps - 60.0).abs() / 60.0 <= CADENCE_TOLERANCE
    {
        60.0
    } else {
        fps
    }
}

/// Checks whether a display refresh rate is a whole multiple of the content fps
/// (within 0.1% tolerance for NTSC fractional rates or floating point jitter).
pub fn is_cadence_multiple(rate: f32, fps: f32) -> bool {
    if fps <= 0.0 || rate <= 0.0 {
        return false;
    }
    let norm_fps = normalize_fps(fps);
    let ratio = rate / norm_fps;
    let round_ratio = ratio.round();
    if round_ratio < 1.0 {
        return false;
    }
    let diff = (ratio - round_ratio).abs();
    diff / round_ratio <= CADENCE_TOLERANCE
}

/// Returns all rates from `supported_rates` that are whole multiples of `fps`,
/// sorted in ascending order.
pub fn allowed_cadence_rates(fps: f32, supported_rates: &[f32]) -> Vec<f32> {
    if fps <= 0.0 {
        return Vec::new();
    }
    let mut allowed: Vec<f32> = supported_rates
        .iter()
        .copied()
        .filter(|&r| is_cadence_multiple(r, fps))
        .collect();
    allowed.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    allowed.dedup();
    allowed
}

/// Finds the highest supported refresh rate that is a whole multiple of `fps`
/// and does not exceed `max_rate`.
pub fn find_cadence_refresh_rate(fps: f32, supported_rates: &[f32], max_rate: f32) -> Option<f32> {
    if fps <= 0.0 {
        return None;
    }
    supported_rates
        .iter()
        .copied()
        .filter(|&r| r <= max_rate + 0.01 && is_cadence_multiple(r, fps))
        .max_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
}

/// Chooses the highest supported refresh rate that matches the content frame rate cadence
/// and does not exceed `max_rate`. When none qualifies, keeps `current_rate`.
pub fn choose_refresh_rate(
    fps: f32,
    supported_rates: &[f32],
    max_rate: f32,
    current_rate: f32,
) -> f32 {
    find_cadence_refresh_rate(fps, supported_rates, max_rate).unwrap_or(current_rate)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_choose_refresh_rate_standard_cadences() {
        let quest3_rates = [72.0, 90.0, 120.0];

        // 24.0 fps with max 120 -> 120
        assert_eq!(choose_refresh_rate(24.0, &quest3_rates, 120.0, 90.0), 120.0);

        // 24.0 fps with max 90 -> 72
        assert_eq!(choose_refresh_rate(24.0, &quest3_rates, 90.0, 90.0), 72.0);

        // 30.0 fps with max 90 -> 90
        assert_eq!(choose_refresh_rate(30.0, &quest3_rates, 90.0, 72.0), 90.0);

        // 25.0 fps with max 120 -> none qualifies, keeps current rate
        assert_eq!(choose_refresh_rate(25.0, &quest3_rates, 120.0, 90.0), 90.0);
        assert_eq!(choose_refresh_rate(25.0, &quest3_rates, 120.0, 72.0), 72.0);
    }

    #[test]
    fn test_fractional_fps_treated_as_nominal() {
        let quest3_rates = [72.0, 90.0, 120.0];

        // 23.976 fps is treated as 24
        assert_eq!(
            choose_refresh_rate(23.976, &quest3_rates, 120.0, 90.0),
            120.0
        );
        assert_eq!(choose_refresh_rate(23.976, &quest3_rates, 90.0, 90.0), 72.0);

        // Exact 24000/1001
        let ntsc_24 = 24000.0 / 1001.0;
        assert_eq!(
            choose_refresh_rate(ntsc_24, &quest3_rates, 120.0, 90.0),
            120.0
        );
        assert_eq!(
            choose_refresh_rate(ntsc_24, &quest3_rates, 90.0, 90.0),
            72.0
        );

        // 29.97 fps is treated as 30
        assert_eq!(choose_refresh_rate(29.97, &quest3_rates, 90.0, 72.0), 90.0);

        // 59.94 fps is treated as 60
        assert_eq!(
            choose_refresh_rate(59.94, &quest3_rates, 120.0, 90.0),
            120.0
        );

        // 24.05 fps exceeds 0.1% tolerance and is not normalized to 24
        assert_eq!(normalize_fps(24.05), 24.05);
        assert_eq!(choose_refresh_rate(24.05, &quest3_rates, 120.0, 90.0), 90.0);
    }

    #[test]
    fn test_allowed_cadence_rates() {
        let rates = [72.0, 80.0, 90.0, 120.0];

        // 24 fps divides 72 (3x) and 120 (5x)
        assert_eq!(allowed_cadence_rates(24.0, &rates), vec![72.0, 120.0]);

        // 30 fps divides 90 (3x) and 120 (4x)
        assert_eq!(allowed_cadence_rates(30.0, &rates), vec![90.0, 120.0]);

        // 60 fps divides 120 (2x)
        assert_eq!(allowed_cadence_rates(60.0, &rates), vec![120.0]);

        // 25 fps divides none
        assert_eq!(allowed_cadence_rates(25.0, &rates), Vec::<f32>::new());
    }

    #[test]
    fn test_preference_off_matches_target_fps_for_all_levels() {
        use crate::quality::{QualityController, QualityLevel};

        let mut qc = QualityController::new();
        qc.set_match_content_frame_rate(false);
        qc.set_content_fps(24.0);
        qc.set_supported_refresh_rates(vec![72.0, 90.0, 120.0]);

        let levels = [
            QualityLevel::Ultra,
            QualityLevel::High,
            QualityLevel::Medium,
            QualityLevel::Low,
            QualityLevel::Emergency,
        ];

        for level in levels {
            qc.set_manual_level(level);
            let resolved =
                qc.resolve_params(0, crate::upscaling::UpscalingMode::Off, 0, 1920, 1080);
            assert_eq!(resolved.target_fps, level.target_fps());
        }
    }
}
