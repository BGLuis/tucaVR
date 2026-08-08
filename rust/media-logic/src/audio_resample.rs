//! Pure arithmetic extracted out of `rust/core/src/audio_decoder.rs`
//! (`AudioDecoder::set_speed`/`decode`, T2.6 in PHASE-0.1-MVP.md).
//!
//! Both bugs fixed during the speed-control work were pure math mistakes,
//! not anything hardware-specific — which is exactly why they're worth
//! covering with fast host tests instead of only trusting manual headset
//! playback:
//!
//! 1. `target_sample_rate`: the "sped-up tape" trick resamples audio to
//!    `48000 / speed` instead of a fixed `48000`, clamped to a sane range so
//!    a pathological speed value can't ask the resampler for something like
//!    0Hz or a few GHz.
//! 2. `valid_sample_count`: FFmpeg's `Audio::data(0)` returns a slice sized
//!    to the *allocated* buffer (`linesize`, rounded up for alignment), not
//!    the number of *valid* samples (`nb_samples`). Copying `data.len()`
//!    bytes verbatim included trailing padding/garbage reinterpreted as
//!    `f32`, which is exactly the crackling/static bug documented in
//!    PHASE-0.1-MVP.md's T2.6 notes (worse at higher speeds, where each
//!    resampled chunk has fewer valid samples relative to the allocated
//!    buffer size).

/// Resample target rate for the "sped-up/slowed-down tape" playback-speed
/// effect: play `base_rate` Hz of content in `1/speed` the time by asking
/// the resampler for `base_rate / speed` Hz instead. Clamped to
/// `[8_000, 192_000]` so an extreme `speed` (e.g. very close to 0, or a bug
/// upstream that lets an out-of-range value through) can't produce a
/// degenerate or absurd resampler target rate.
pub fn target_sample_rate(base_rate: u32, speed: f32) -> u32 {
    ((base_rate as f64) / (speed as f64))
        .round()
        .clamp(8_000.0, 192_000.0) as u32
}

/// How many `f32` samples (interleaved across `channels` channels) are
/// actually valid at the front of a resampled buffer that reports
/// `reported_samples` samples per channel, given only `available_floats`
/// `f32`s physically fit in the buffer we read from.
///
/// `reported_samples * channels` is what FFmpeg says is valid; clamping to
/// `available_floats` is a defensive bound in case the buffer turns out
/// smaller than expected (should not happen in practice, but a bug here is
/// exactly what caused the T2.6 crackling: trusting the buffer's allocated
/// size instead of what's actually valid).
pub fn valid_sample_count(reported_samples: usize, channels: usize, available_floats: usize) -> usize {
    (reported_samples * channels).min(available_floats)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normal_speed_keeps_base_rate() {
        assert_eq!(target_sample_rate(48_000, 1.0), 48_000);
    }

    #[test]
    fn double_speed_halves_target_rate() {
        assert_eq!(target_sample_rate(48_000, 2.0), 24_000);
    }

    #[test]
    fn half_speed_doubles_target_rate() {
        assert_eq!(target_sample_rate(48_000, 0.5), 96_000);
    }

    #[test]
    fn extreme_low_speed_clamps_to_max_rate() {
        // Without the clamp this would ask for an absurdly high resample
        // rate as speed approaches 0.
        assert_eq!(target_sample_rate(48_000, 0.001), 192_000);
    }

    #[test]
    fn extreme_high_speed_clamps_to_min_rate() {
        assert_eq!(target_sample_rate(48_000, 1000.0), 8_000);
    }

    #[test]
    fn valid_sample_count_uses_reported_times_channels_when_buffer_is_large_enough() {
        // Stereo, 10 valid samples per channel reported, plenty of room in
        // the (over-allocated) buffer.
        assert_eq!(valid_sample_count(10, 2, 4096), 20);
    }

    #[test]
    fn valid_sample_count_excludes_linesize_padding_past_reported_samples() {
        // This is the T2.6 regression scenario: the allocated buffer
        // (available_floats) is bigger than what's actually valid
        // (reported_samples * channels) because of linesize alignment
        // padding. The padding must never leak into the result.
        let reported_samples = 5;
        let channels = 2;
        let available_floats = 64; // linesize-padded, much bigger than 5*2=10
        assert_eq!(valid_sample_count(reported_samples, channels, available_floats), 10);
    }

    #[test]
    fn valid_sample_count_clamps_to_available_floats_if_smaller() {
        // Defensive direction: never read past what's actually there, even
        // if FFmpeg's reported sample count is (incorrectly) larger.
        assert_eq!(valid_sample_count(100, 2, 50), 50);
    }
}
