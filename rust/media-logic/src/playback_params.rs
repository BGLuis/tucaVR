//! Trivial-but-worth-locking-down clamps used by
//! `rust/core/src/playback.rs` (`PlaybackController::set_speed`/
//! `set_volume`). These are one-liners, but they encode product
//! requirements straight out of PHASE-0.1-MVP.md ("Controle de velocidade
//! (0.5x - 2.0x)", volume 0..1) — a regression here (e.g. someone widening
//! the speed range while wiring up a new UI slider) would silently let
//! invalid values reach the audio resampler/output. Cheap to test, cheap to
//! keep correct.

/// Playback speed is clamped to 0.5x-2.0x (PHASE-0.1-MVP.md, T2.6).
pub fn clamp_speed(speed: f32) -> f32 {
    speed.clamp(0.5, 2.0)
}

/// Volume is clamped to 0.0 (muted) - 1.0 (100%).
pub fn clamp_volume(volume: f32) -> f32 {
    volume.clamp(0.0, 1.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn speed_within_range_is_unchanged() {
        assert_eq!(clamp_speed(1.5), 1.5);
    }

    #[test]
    fn speed_below_minimum_is_clamped_to_0_5() {
        assert_eq!(clamp_speed(0.1), 0.5);
    }

    #[test]
    fn speed_above_maximum_is_clamped_to_2_0() {
        assert_eq!(clamp_speed(5.0), 2.0);
    }

    #[test]
    fn speed_boundary_values_are_kept_as_is() {
        assert_eq!(clamp_speed(0.5), 0.5);
        assert_eq!(clamp_speed(2.0), 2.0);
    }

    #[test]
    fn volume_within_range_is_unchanged() {
        assert_eq!(clamp_volume(0.42), 0.42);
    }

    #[test]
    fn volume_below_zero_is_clamped_to_zero() {
        assert_eq!(clamp_volume(-1.0), 0.0);
    }

    #[test]
    fn volume_above_one_is_clamped_to_one() {
        assert_eq!(clamp_volume(3.0), 1.0);
    }
}
