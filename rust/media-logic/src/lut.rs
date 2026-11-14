//! 3D .cube Color LUT parser and identity generator.
//!
//! Follows the Adobe / DaVinci Resolve `.cube` specification for 3D LUTs:
//! - Optional `TITLE "name"`
//! - Optional `DOMAIN_MIN r g b` (default `0.0 0.0 0.0`)
//! - Optional `DOMAIN_MAX r g b` (default `1.0 1.0 1.0`)
//! - Required `LUT_3D_SIZE N` where `2 <= N <= 65`
//! - Exactly `N * N * N` RGB data lines
//! - Data triplets ordered with R changing fastest, then G, then B (matches 3D texture layout)
//! - Normalized to `[0, 1]` based on `DOMAIN_MIN` and `DOMAIN_MAX`, mapped to RGBA8 (`A = 255`)
//! - Rejects 1D LUTs (`LUT_1D_SIZE`), invalid sizes, malformed lines, non-finite values,
//!   invalid domain ranges, or incorrect entry counts.

use std::fmt;

/// Minimum supported 3D LUT cube edge size.
pub const MIN_LUT_SIZE: u32 = 2;

/// Maximum supported 3D LUT cube edge size.
pub const MAX_LUT_SIZE: u32 = 65;

/// Parsed 3D LUT representation with RGBA8 raw texture data.
#[derive(Debug, Clone, PartialEq)]
pub struct CubeLut3D {
    pub title: Option<String>,
    pub size: u32,
    pub domain_min: [f32; 3],
    pub domain_max: [f32; 3],
    /// `size * size * size * 4` bytes in RGBA8 format.
    pub data: Vec<u8>,
}

/// Errors occurring during `.cube` file parsing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ParseLutError {
    EmptyInput,
    Unsupported1D,
    MissingSize,
    DuplicateSize,
    InvalidSize(u32),
    InvalidDomain,
    MalformedLine(String),
    InvalidEntryCount { expected: usize, found: usize },
}

impl fmt::Display for ParseLutError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ParseLutError::EmptyInput => write!(f, "empty .cube input"),
            ParseLutError::Unsupported1D => write!(f, "1D LUT is not supported, 3D LUT required"),
            ParseLutError::MissingSize => write!(f, "missing LUT_3D_SIZE header"),
            ParseLutError::DuplicateSize => write!(f, "duplicate LUT_3D_SIZE header"),
            ParseLutError::InvalidSize(s) => write!(
                f,
                "invalid LUT_3D_SIZE: {s} (supported range: {MIN_LUT_SIZE}..={MAX_LUT_SIZE})"
            ),
            ParseLutError::InvalidDomain => write!(f, "invalid DOMAIN_MIN / DOMAIN_MAX"),
            ParseLutError::MalformedLine(line) => write!(f, "malformed line: '{line}'"),
            ParseLutError::InvalidEntryCount { expected, found } => {
                write!(f, "expected {expected} table entries, found {found}")
            }
        }
    }
}

impl std::error::Error for ParseLutError {}

/// Generates an identity 3D LUT with edge length `size` in RGBA8 format.
///
/// For `size = 2`, returns a 2x2x2 cube (32 bytes) mapping inputs linearly to outputs.
pub fn identity_lut_rgba8(size: u32) -> Result<Vec<u8>, ParseLutError> {
    if !(MIN_LUT_SIZE..=MAX_LUT_SIZE).contains(&size) {
        return Err(ParseLutError::InvalidSize(size));
    }

    let total_entries = (size as usize) * (size as usize) * (size as usize);
    let mut data = Vec::with_capacity(total_entries * 4);
    let div = (size - 1) as f32;

    for b in 0..size {
        let b_byte = ((b as f32 / div) * 255.0).round() as u8;
        for g in 0..size {
            let g_byte = ((g as f32 / div) * 255.0).round() as u8;
            for r in 0..size {
                let r_byte = ((r as f32 / div) * 255.0).round() as u8;
                data.push(r_byte);
                data.push(g_byte);
                data.push(b_byte);
                data.push(255);
            }
        }
    }

    Ok(data)
}

/// Parses a `.cube` file content from UTF-8 string.
pub fn parse_cube_lut(input: &str) -> Result<CubeLut3D, ParseLutError> {
    let clean_input = input.strip_prefix('\u{feff}').unwrap_or(input).trim();
    if clean_input.is_empty() {
        return Err(ParseLutError::EmptyInput);
    }

    let mut title: Option<String> = None;
    let mut lut_size: Option<u32> = None;
    let mut domain_min = [0.0f32, 0.0f32, 0.0f32];
    let mut domain_max = [1.0f32, 1.0f32, 1.0f32];
    let mut raw_entries: Vec<[f32; 3]> = Vec::new();
    let mut non_comment_lines = 0;

    for line in clean_input.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        non_comment_lines += 1;

        // Keywords check
        if trimmed.starts_with("LUT_1D_SIZE") || trimmed.starts_with("LUT_1D_INPUT_RANGE") {
            return Err(ParseLutError::Unsupported1D);
        }

        if let Some(rest) = trimmed.strip_prefix("TITLE") {
            let raw_title = rest.trim();
            let parsed_title =
                if raw_title.starts_with('"') && raw_title.ends_with('"') && raw_title.len() >= 2 {
                    raw_title[1..raw_title.len() - 1].to_string()
                } else {
                    raw_title.to_string()
                };
            title = Some(parsed_title);
            continue;
        }

        if let Some(rest) = trimmed.strip_prefix("LUT_3D_SIZE") {
            if lut_size.is_some() {
                return Err(ParseLutError::DuplicateSize);
            }
            let s: u32 = rest
                .trim()
                .parse()
                .map_err(|_| ParseLutError::MalformedLine(trimmed.to_string()))?;
            if !(MIN_LUT_SIZE..=MAX_LUT_SIZE).contains(&s) {
                return Err(ParseLutError::InvalidSize(s));
            }
            lut_size = Some(s);
            continue;
        }

        if let Some(rest) = trimmed.strip_prefix("DOMAIN_MIN") {
            domain_min = parse_triplet(rest)?;
            continue;
        }

        if let Some(rest) = trimmed.strip_prefix("DOMAIN_MAX") {
            domain_max = parse_triplet(rest)?;
            continue;
        }

        // Ignore unknown metadata keywords that start with an uppercase letter
        if trimmed
            .chars()
            .next()
            .is_some_and(|c| c.is_ascii_uppercase())
        {
            let first_token = trimmed.split_whitespace().next().unwrap_or("");
            if first_token == "LUT_3D_INPUT_RANGE" {
                continue;
            }
        }

        // Must be a data entry triplet
        let triplet = parse_triplet(trimmed)?;
        raw_entries.push(triplet);
    }

    if non_comment_lines == 0 {
        return Err(ParseLutError::EmptyInput);
    }

    let size = lut_size.ok_or(ParseLutError::MissingSize)?;

    for i in 0..3 {
        if domain_min[i] >= domain_max[i]
            || !domain_min[i].is_finite()
            || !domain_max[i].is_finite()
        {
            return Err(ParseLutError::InvalidDomain);
        }
    }

    let expected_entries = (size as usize) * (size as usize) * (size as usize);
    if raw_entries.len() != expected_entries {
        return Err(ParseLutError::InvalidEntryCount {
            expected: expected_entries,
            found: raw_entries.len(),
        });
    }

    let mut data = Vec::with_capacity(expected_entries * 4);
    let span_r = domain_max[0] - domain_min[0];
    let span_g = domain_max[1] - domain_min[1];
    let span_b = domain_max[2] - domain_min[2];

    for [r, g, b] in raw_entries {
        let norm_r = ((r - domain_min[0]) / span_r).clamp(0.0, 1.0);
        let norm_g = ((g - domain_min[1]) / span_g).clamp(0.0, 1.0);
        let norm_b = ((b - domain_min[2]) / span_b).clamp(0.0, 1.0);

        data.push((norm_r * 255.0).round() as u8);
        data.push((norm_g * 255.0).round() as u8);
        data.push((norm_b * 255.0).round() as u8);
        data.push(255);
    }

    Ok(CubeLut3D {
        title,
        size,
        domain_min,
        domain_max,
        data,
    })
}

/// Parses `.cube` file data from raw bytes (UTF-8 / ASCII).
pub fn parse_cube_lut_bytes(bytes: &[u8]) -> Result<CubeLut3D, ParseLutError> {
    let s = std::str::from_utf8(bytes)
        .map_err(|_| ParseLutError::MalformedLine("invalid UTF-8".to_string()))?;
    parse_cube_lut(s)
}

fn parse_triplet(s: &str) -> Result<[f32; 3], ParseLutError> {
    let mut parts = s.split_whitespace();
    let r_str = parts
        .next()
        .ok_or_else(|| ParseLutError::MalformedLine(s.to_string()))?;
    let g_str = parts
        .next()
        .ok_or_else(|| ParseLutError::MalformedLine(s.to_string()))?;
    let b_str = parts
        .next()
        .ok_or_else(|| ParseLutError::MalformedLine(s.to_string()))?;

    if parts.next().is_some() {
        return Err(ParseLutError::MalformedLine(s.to_string()));
    }

    let r: f32 = r_str
        .parse()
        .map_err(|_| ParseLutError::MalformedLine(s.to_string()))?;
    let g: f32 = g_str
        .parse()
        .map_err(|_| ParseLutError::MalformedLine(s.to_string()))?;
    let b: f32 = b_str
        .parse()
        .map_err(|_| ParseLutError::MalformedLine(s.to_string()))?;

    if !r.is_finite() || !g.is_finite() || !b.is_finite() {
        return Err(ParseLutError::MalformedLine(s.to_string()));
    }

    Ok([r, g, b])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_identity_lut_rgba8_2x2x2() {
        let lut = identity_lut_rgba8(2).expect("identity lut 2x2x2 should succeed");
        assert_eq!(lut.len(), 2 * 2 * 2 * 4); // 32 bytes

        // (r=0, g=0, b=0) -> (0, 0, 0, 255)
        assert_eq!(&lut[0..4], &[0, 0, 0, 255]);
        // (r=1, g=0, b=0) -> (255, 0, 0, 255)
        assert_eq!(&lut[4..8], &[255, 0, 0, 255]);
        // (r=0, g=1, b=0) -> (0, 255, 0, 255)
        assert_eq!(&lut[8..12], &[0, 255, 0, 255]);
        // (r=1, g=1, b=0) -> (255, 255, 0, 255)
        assert_eq!(&lut[12..16], &[255, 255, 0, 255]);
        // (r=0, g=0, b=1) -> (0, 0, 255, 255)
        assert_eq!(&lut[16..20], &[0, 0, 255, 255]);
        // (r=1, g=1, b=1) -> (255, 255, 255, 255)
        assert_eq!(&lut[28..32], &[255, 255, 255, 255]);
    }

    #[test]
    fn test_parse_valid_cube_with_comments_and_title() {
        let cube_content = r#"
# Simple 2x2x2 identity test cube
TITLE "Teal & Orange"
LUT_3D_SIZE 2
DOMAIN_MIN 0.0 0.0 0.0
DOMAIN_MAX 1.0 1.0 1.0

0.0 0.0 0.0
1.0 0.0 0.0
0.0 1.0 0.0
1.0 1.0 0.0
0.0 0.0 1.0
1.0 0.0 1.0
0.0 1.0 1.0
1.0 1.0 1.0
"#;
        let parsed = parse_cube_lut(cube_content).expect("valid cube should parse");
        assert_eq!(parsed.title.as_deref(), Some("Teal & Orange"));
        assert_eq!(parsed.size, 2);
        assert_eq!(parsed.data.len(), 32);
        assert_eq!(&parsed.data[0..4], &[0, 0, 0, 255]);
        assert_eq!(&parsed.data[28..32], &[255, 255, 255, 255]);
    }

    #[test]
    fn test_parse_non_unit_domain() {
        let cube_content = r#"
LUT_3D_SIZE 2
DOMAIN_MIN -1.0 -1.0 -1.0
DOMAIN_MAX 1.0 1.0 1.0

-1.0 -1.0 -1.0
 1.0 -1.0 -1.0
-1.0  1.0 -1.0
 1.0  1.0 -1.0
-1.0 -1.0  1.0
 1.0 -1.0  1.0
-1.0  1.0  1.0
 1.0  1.0  1.0
"#;
        let parsed = parse_cube_lut(cube_content).expect("cube with custom domain should parse");
        assert_eq!(parsed.size, 2);
        assert_eq!(&parsed.data[0..4], &[0, 0, 0, 255]);
        assert_eq!(&parsed.data[4..8], &[255, 0, 0, 255]);
        assert_eq!(&parsed.data[28..32], &[255, 255, 255, 255]);
    }

    #[test]
    fn test_reject_1d_lut() {
        let cube_content = "LUT_1D_SIZE 256\n0.0 0.0 0.0";
        assert_eq!(
            parse_cube_lut(cube_content),
            Err(ParseLutError::Unsupported1D)
        );
    }

    #[test]
    fn test_reject_invalid_size() {
        let cube_content_1 = "LUT_3D_SIZE 1\n0.0 0.0 0.0";
        assert_eq!(
            parse_cube_lut(cube_content_1),
            Err(ParseLutError::InvalidSize(1))
        );

        let cube_content_66 = "LUT_3D_SIZE 66\n0.0 0.0 0.0";
        assert_eq!(
            parse_cube_lut(cube_content_66),
            Err(ParseLutError::InvalidSize(66))
        );
    }

    #[test]
    fn test_reject_invalid_domain() {
        let cube_content = r#"
LUT_3D_SIZE 2
DOMAIN_MIN 1.0 0.0 0.0
DOMAIN_MAX 0.5 1.0 1.0
0.0 0.0 0.0
"#;
        assert_eq!(
            parse_cube_lut(cube_content),
            Err(ParseLutError::InvalidDomain)
        );
    }

    #[test]
    fn test_reject_wrong_entry_count() {
        let cube_content = r#"
LUT_3D_SIZE 2
0.0 0.0 0.0
1.0 0.0 0.0
"#;
        assert_eq!(
            parse_cube_lut(cube_content),
            Err(ParseLutError::InvalidEntryCount {
                expected: 8,
                found: 2
            })
        );
    }

    #[test]
    fn test_reject_malformed_lines() {
        let cube_content = r#"
LUT_3D_SIZE 2
0.0 0.0
"#;
        assert!(matches!(
            parse_cube_lut(cube_content),
            Err(ParseLutError::MalformedLine(_))
        ));

        let cube_nan = r#"
LUT_3D_SIZE 2
0.0 NaN 0.0
"#;
        assert!(matches!(
            parse_cube_lut(cube_nan),
            Err(ParseLutError::MalformedLine(_))
        ));
    }

    #[test]
    fn test_reject_empty_input() {
        assert_eq!(parse_cube_lut(""), Err(ParseLutError::EmptyInput));
        assert_eq!(
            parse_cube_lut("   \n# comment\n"),
            Err(ParseLutError::EmptyInput)
        );
    }
}
