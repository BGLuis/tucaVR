//! Sample aspect ratio (SAR) handling for anamorphic video.
//!
//! A 720x576 stream flagged as 16:9 stores non-square pixels (SAR 64:45), so the
//! display aspect ratio is `width / height * SAR`, not the raw buffer aspect.

/// Normalizes a container/codec SAR. Unknown or invalid values (0/0, zero or
/// negative components) mean square pixels, 1:1.
pub fn normalize_sar(num: i32, den: i32) -> (u32, u32) {
    if num > 0 && den > 0 {
        (num as u32, den as u32)
    } else {
        (1, 1)
    }
}

/// Packs a SAR into one word (`num << 32 | den`) so it can cross the C ABI and
/// live in a single atomic.
pub fn pack_sar(num: u32, den: u32) -> u64 {
    (u64::from(num) << 32) | u64::from(den)
}

/// Picks the SAR to display: the stream's when valid, else the codec's, the same
/// priority as `av_guess_sample_aspect_ratio`. MKV display dimensions and MP4 `pasp`
/// boxes only reach the stream-level field.
pub fn preferred_sar(stream: (i32, i32), codec: (i32, i32)) -> (u32, u32) {
    if stream.0 > 0 && stream.1 > 0 {
        normalize_sar(stream.0, stream.1)
    } else {
        normalize_sar(codec.0, codec.1)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unknown_or_invalid_sar_means_square_pixels() {
        assert_eq!(normalize_sar(0, 0), (1, 1));
        assert_eq!(normalize_sar(0, 1), (1, 1));
        assert_eq!(normalize_sar(1, 0), (1, 1));
        assert_eq!(normalize_sar(-16, 15), (1, 1));
        assert_eq!(normalize_sar(16, -15), (1, 1));
        assert_eq!(normalize_sar(1, 1), (1, 1));
    }

    #[test]
    fn valid_sar_is_kept() {
        assert_eq!(normalize_sar(16, 15), (16, 15));
        assert_eq!(normalize_sar(64, 45), (64, 45));
    }

    #[test]
    fn pack_puts_numerator_high_and_denominator_low() {
        let packed = pack_sar(64, 45);
        assert_eq!((packed >> 32) as u32, 64);
        assert_eq!((packed & 0xFFFF_FFFF) as u32, 45);
    }

    #[test]
    fn stream_sar_wins_over_codec_sar() {
        assert_eq!(preferred_sar((64, 45), (16, 15)), (64, 45));
    }

    #[test]
    fn codec_sar_is_used_when_the_stream_has_none() {
        assert_eq!(preferred_sar((0, 1), (64, 45)), (64, 45));
        assert_eq!(preferred_sar((0, 0), (16, 15)), (16, 15));
    }

    #[test]
    fn no_valid_sar_anywhere_means_square_pixels() {
        assert_eq!(preferred_sar((0, 1), (0, 1)), (1, 1));
    }
}
