//! Language and region parsing for audio and subtitle tracks.
//!
//! Maps ISO 639 codes, extracts BCP-47 / ISO 3166 region tags, parses title
//! strings, and enforces a deterministic fallback hierarchy. Host-testable with zero
//! Android/hardware dependencies.

/// Parsed language and regional information for a track.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackLanguageInfo {
    /// Normalized primary 2-letter ISO 639-1 code (e.g., "pt", "en", "es", "ja")
    /// or original code if unrecognized. Empty if unknown.
    pub language_code: String,
    /// Human-readable base language name (e.g., "Portuguese", "English", "Spanish").
    pub language_name: String,
    /// 2-letter ISO 3166-1 uppercase country code (e.g., "BR", "PT", "US", "GB", "ES", "MX").
    pub region_code: Option<String>,
    /// Human-readable region name in English (e.g., "Brazil", "Portugal", "United States", "Mexico", "Spain").
    pub region_name: Option<String>,
    /// Extracted or synthesized language/region tag (e.g., "pt-BR", "pt-PT", "en-US", "por").
    pub tag: String,
    /// Formatted display name (e.g., "Portuguese (Brazil)", "Portuguese (Portugal)",
    /// "English (United States)", "Unknown language · track 1").
    pub display_name: String,
}

/// Maps an ISO 639-1 (2-letter) or ISO 639-2 (3-letter) code to its English language name.
pub fn iso_to_language_name(code: &str) -> Option<&'static str> {
    let cleaned = code.trim().to_ascii_lowercase();
    match cleaned.as_str() {
        "pt" | "por" => Some("Portuguese"),
        "en" | "eng" => Some("English"),
        "es" | "spa" => Some("Spanish"),
        "fr" | "fra" | "fre" => Some("French"),
        "de" | "deu" | "ger" => Some("German"),
        "it" | "ita" => Some("Italian"),
        "ja" | "jpn" => Some("Japanese"),
        "ko" | "kor" => Some("Korean"),
        "zh" | "zho" | "chi" => Some("Chinese"),
        "ru" | "rus" => Some("Russian"),
        "ar" | "ara" => Some("Arabic"),
        "nl" | "nld" | "dut" => Some("Dutch"),
        "pl" | "pol" => Some("Polish"),
        "sv" | "swe" => Some("Swedish"),
        "tr" | "tur" => Some("Turkish"),
        "hi" | "hin" => Some("Hindi"),
        "uk" | "ukr" => Some("Ukrainian"),
        "cs" | "ces" | "cze" => Some("Czech"),
        "da" | "dan" => Some("Danish"),
        "fi" | "fin" => Some("Finnish"),
        "el" | "ell" | "gre" => Some("Greek"),
        "he" | "heb" => Some("Hebrew"),
        "hu" | "hun" => Some("Hungarian"),
        "id" | "ind" => Some("Indonesian"),
        "no" | "nor" => Some("Norwegian"),
        "ro" | "ron" | "rum" => Some("Romanian"),
        "th" | "tha" => Some("Thai"),
        "vi" | "vie" => Some("Vietnamese"),
        "ca" | "cat" => Some("Catalan"),
        _ => None,
    }
}

/// Normalizes language code to primary 2-letter ISO 639-1 code if known.
pub fn normalize_language_code(code: &str) -> String {
    let primary = code
        .trim()
        .split(['-', '_'])
        .next()
        .unwrap_or("")
        .to_ascii_lowercase();

    match primary.as_str() {
        "por" => "pt",
        "eng" => "en",
        "spa" => "es",
        "fra" | "fre" => "fr",
        "deu" | "ger" => "de",
        "ita" => "it",
        "jpn" => "ja",
        "kor" => "ko",
        "zho" | "chi" => "zh",
        "rus" => "ru",
        "ara" => "ar",
        "nld" | "dut" => "nl",
        "pol" => "pl",
        "swe" => "sv",
        "tur" => "tr",
        "hin" => "hi",
        "ukr" => "uk",
        "ces" | "cze" => "cs",
        "dan" => "da",
        "fin" => "fi",
        "ell" | "gre" => "el",
        "heb" => "he",
        "hun" => "hu",
        "ind" => "id",
        "nor" => "no",
        "ron" | "rum" => "ro",
        "tha" => "th",
        "vie" => "vi",
        "cat" => "ca",
        other => other,
    }
    .to_string()
}

/// Extracts ISO 3166-1 region code and region name from a language tag like `pt-BR`, `pt_PT`, `es-MX`.
pub fn extract_region_from_tag(tag: &str) -> Option<(&'static str, &'static str)> {
    let trimmed = tag.trim();
    let parts: Vec<&str> = trimmed.split(['-', '_']).collect();
    if parts.len() < 2 {
        return None;
    }

    let subtag = parts[1].to_ascii_uppercase();
    match subtag.as_str() {
        "BR" => Some(("BR", "Brazil")),
        "PT" => Some(("PT", "Portugal")),
        "US" => Some(("US", "United States")),
        "GB" | "UK" => Some(("GB", "Great Britain")),
        "ES" => Some(("ES", "Spain")),
        "MX" | "419" => Some(("MX", "Mexico")),
        "FR" => Some(("FR", "France")),
        "DE" => Some(("DE", "Germany")),
        "JP" => Some(("JP", "Japan")),
        "IT" => Some(("IT", "Italy")),
        "KR" => Some(("KR", "South Korea")),
        "CN" => Some(("CN", "China")),
        "TW" => Some(("TW", "Taiwan")),
        "HK" => Some(("HK", "Hong Kong")),
        "RU" => Some(("RU", "Russia")),
        "CA" => Some(("CA", "Canada")),
        "AU" => Some(("AU", "Australia")),
        "AR" => Some(("AR", "Argentina")),
        "CL" => Some(("CL", "Chile")),
        "CO" => Some(("CO", "Colombia")),
        _ => None,
    }
}

/// Parses a track title for explicit region keywords.
pub fn extract_region_from_title(title: &str) -> Option<(&'static str, &'static str)> {
    let lower = title.to_ascii_lowercase();

    // Check specific Portuguese dialect keywords
    if lower.contains("português (brasil)")
        || lower.contains("portuguese (brazil)")
        || lower.contains("brasileiro")
        || lower.contains("brazilian")
        || lower.contains("pt-br")
        || lower.contains("pt_br")
        || lower.contains("brasil")
        || lower.contains("brazil")
    {
        return Some(("BR", "Brazil"));
    }
    if lower.contains("português (portugal)")
        || lower.contains("portuguese (portugal)")
        || lower.contains("europeu")
        || lower.contains("european")
        || lower.contains("pt-pt")
        || lower.contains("pt_pt")
        || lower.contains("portugal")
    {
        return Some(("PT", "Portugal"));
    }

    // Check Spanish dialect keywords
    if lower.contains("latino")
        || lower.contains("latin")
        || lower.contains("latinoamérica")
        || lower.contains("amércia latina")
        || lower.contains("mexico")
        || lower.contains("méxico")
        || lower.contains("es-mx")
        || lower.contains("es-419")
    {
        return Some(("MX", "Mexico"));
    }
    if lower.contains("castellano")
        || lower.contains("castilian")
        || lower.contains("españa")
        || lower.contains("spain")
        || lower.contains("es-es")
    {
        return Some(("ES", "Spain"));
    }

    // Check English dialect keywords
    if lower.contains("english (us)")
        || lower.contains("en-us")
        || lower.contains("united states")
        || lower.contains("usa")
        || lower.contains("american")
    {
        return Some(("US", "United States"));
    }
    if lower.contains("english (uk)")
        || lower.contains("english (gb)")
        || lower.contains("en-gb")
        || lower.contains("united kingdom")
        || lower.contains("great britain")
        || lower.contains("british")
    {
        return Some(("GB", "Great Britain"));
    }

    // Check French dialect keywords
    if lower.contains("québécois")
        || lower.contains("quebec")
        || lower.contains("canada")
        || lower.contains("fr-ca")
    {
        return Some(("CA", "Canada"));
    }
    if lower.contains("français (france)") || lower.contains("france") || lower.contains("fr-fr") {
        return Some(("FR", "France"));
    }

    // Germany
    if lower.contains("germany") || lower.contains("deutschland") || lower.contains("de-de") {
        return Some(("DE", "Germany"));
    }

    // Japan
    if lower.contains("japan") || lower.contains("nihon") || lower.contains("ja-jp") {
        return Some(("JP", "Japan"));
    }

    None
}

/// Returns the configured default region for a given normalized ISO 639-1 language code.
pub fn default_region_for_language(lang_code: &str) -> Option<(&'static str, &'static str)> {
    match lang_code {
        "pt" => Some(("BR", "Brazil")),
        "es" => Some(("ES", "Spain")),
        "en" => Some(("US", "United States")),
        "fr" => Some(("FR", "France")),
        "de" => Some(("DE", "Germany")),
        "it" => Some(("IT", "Italy")),
        "ja" => Some(("JP", "Japan")),
        "ko" => Some(("KR", "South Korea")),
        "zh" => Some(("CN", "China")),
        "ru" => Some(("RU", "Russia")),
        _ => None,
    }
}

/// Resolves track language and region information following the fallback hierarchy:
/// 1. Region of the tag when present (`pt-BR` -> Brazil, `pt-PT` -> Portugal).
/// 2. Region named in the track title.
/// 3. Per-language default (e.g., `pt` -> Brazil, `es` -> Spain, `en` -> US).
/// 4. Empty/unknown -> "Unknown language · track N".
pub fn resolve_track_language(tag: &str, title: &str, track_index: usize) -> TrackLanguageInfo {
    let tag_trimmed = tag.trim();
    let norm_lang = normalize_language_code(tag_trimmed);

    // If tag was empty, attempt to infer language from title keywords
    let (effective_lang, base_lang_name) = if !norm_lang.is_empty() {
        let name = iso_to_language_name(tag_trimmed)
            .or_else(|| iso_to_language_name(&norm_lang))
            .unwrap_or(tag_trimmed);
        (norm_lang, name.to_string())
    } else {
        let title_lower = title.to_ascii_lowercase();
        if title_lower.contains("português") || title_lower.contains("portuguese") {
            ("pt".to_string(), "Portuguese".to_string())
        } else if title_lower.contains("english") || title_lower.contains("inglês") {
            ("en".to_string(), "English".to_string())
        } else if title_lower.contains("español")
            || title_lower.contains("spanish")
            || title_lower.contains("espanhol")
            || title_lower.contains("latino")
            || title_lower.contains("castellano")
        {
            ("es".to_string(), "Spanish".to_string())
        } else if title_lower.contains("français") || title_lower.contains("french") {
            ("fr".to_string(), "French".to_string())
        } else if title_lower.contains("deutsch") || title_lower.contains("german") {
            ("de".to_string(), "German".to_string())
        } else if title_lower.contains("japanese") || title_lower.contains("japonês") {
            ("ja".to_string(), "Japanese".to_string())
        } else {
            (String::new(), String::new())
        }
    };

    // If language is still unknown, apply fallback 4
    if effective_lang.is_empty() {
        return TrackLanguageInfo {
            language_code: String::new(),
            language_name: String::new(),
            region_code: None,
            region_name: None,
            tag: tag_trimmed.to_string(),
            display_name: format!("Unknown language · track {}", track_index + 1),
        };
    }

    // Step 1: Region of the tag when present
    let region = extract_region_from_tag(tag_trimmed)
        // Step 2: Region named in the track title
        .or_else(|| extract_region_from_title(title))
        // Step 3: Per-language default
        .or_else(|| default_region_for_language(&effective_lang));

    let (region_code, region_name) = match region {
        Some((code, name)) => (Some(code.to_string()), Some(name.to_string())),
        None => (None, None),
    };

    let display_name = if let Some(ref r_name) = region_name {
        format!("{base_lang_name} ({r_name})")
    } else {
        base_lang_name.clone()
    };

    let effective_tag = if tag_trimmed.is_empty() {
        if let Some(ref r_code) = region_code {
            format!("{effective_lang}-{r_code}")
        } else {
            effective_lang.clone()
        }
    } else {
        tag_trimmed.to_string()
    };

    TrackLanguageInfo {
        language_code: effective_lang,
        language_name: base_lang_name,
        region_code,
        region_name,
        tag: effective_tag,
        display_name,
    }
}

/// Helper to parse subtitle file suffix (e.g. `.pt-PT.srt` -> language "pt-PT", display name "Portuguese (Portugal)").
pub fn parse_subtitle_file_suffix(filename_or_path: &str) -> Option<TrackLanguageInfo> {
    let clean_path = filename_or_path.replace('\\', "/");
    let filename = clean_path.rsplit('/').next().unwrap_or(&clean_path);

    let parts: Vec<&str> = filename.split('.').collect();
    if parts.len() < 2 {
        return None;
    }

    // Last part must be a subtitle extension
    let ext = parts.last().unwrap().to_ascii_lowercase();
    if !matches!(ext.as_str(), "srt" | "vtt" | "ass" | "ssa" | "sup") {
        return None;
    }

    // If filename starts with a dot like `.pt-PT.srt`
    if filename.starts_with('.') && parts.len() == 3 && parts[0].is_empty() {
        let tag = parts[1];
        let info = resolve_track_language(tag, "", 0);
        if !info.language_code.is_empty() {
            return Some(info);
        }
    }

    // Traverse segments from right to left before the extension, looking for language tags
    // e.g. "movie.pt-PT.srt", "movie.en.forced.srt"
    let mut candidate_tag = None;
    for &part in parts[1..parts.len() - 1].iter().rev() {
        let lower = part.to_ascii_lowercase();
        if matches!(
            lower.as_str(),
            "forced" | "default" | "sdh" | "cc" | "sub" | "hi" | "commentary"
        ) {
            continue;
        }

        // Check if `part` is a language tag (e.g., "pt-PT", "pt-BR", "en", "por", "latino")
        let primary = lower.split(['-', '_']).next().unwrap_or("");
        if iso_to_language_name(primary).is_some()
            || matches!(lower.as_str(), "latino" | "castellano")
        {
            candidate_tag = Some(part);
            break;
        }
    }

    let tag = candidate_tag?;
    let info = resolve_track_language(tag, filename, 0);
    if !info.language_code.is_empty() {
        Some(info)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_por_to_portuguese() {
        let info = resolve_track_language("por", "", 0);
        assert_eq!(info.language_name, "Portuguese");
        assert_eq!(iso_to_language_name("por"), Some("Portuguese"));
    }

    #[test]
    fn test_pt_pt_to_portugal() {
        let info = resolve_track_language("pt-PT", "", 0);
        assert_eq!(info.region_code.as_deref(), Some("PT"));
        assert_eq!(info.region_name.as_deref(), Some("Portugal"));
        assert_eq!(info.display_name, "Portuguese (Portugal)");
    }

    #[test]
    fn test_pt_to_configured_default() {
        let info = resolve_track_language("pt", "", 0);
        assert_eq!(info.region_code.as_deref(), Some("BR"));
        assert_eq!(info.region_name.as_deref(), Some("Brazil"));
        assert_eq!(info.display_name, "Portuguese (Brazil)");
    }

    #[test]
    fn test_empty_to_unknown() {
        let info = resolve_track_language("", "", 0);
        assert_eq!(info.display_name, "Unknown language · track 1");

        let info2 = resolve_track_language("", "", 3);
        assert_eq!(info2.display_name, "Unknown language · track 4");
    }

    #[test]
    fn test_pt_pt_srt_identified_as_portuguese_portugal() {
        let info = parse_subtitle_file_suffix("movie.pt-PT.srt").expect("should parse");
        assert_eq!(info.tag, "pt-PT");
        assert_eq!(info.display_name, "Portuguese (Portugal)");

        let info_suffix = parse_subtitle_file_suffix(".pt-PT.srt").expect("should parse");
        assert_eq!(info_suffix.tag, "pt-PT");
        assert_eq!(info_suffix.display_name, "Portuguese (Portugal)");
    }

    #[test]
    fn test_title_parsing_fallback() {
        let info_latino = resolve_track_language("es", "Audio Latino", 0);
        assert_eq!(info_latino.region_code.as_deref(), Some("MX"));
        assert_eq!(info_latino.display_name, "Spanish (Mexico)");

        let info_castellano = resolve_track_language("es", "Castellano", 0);
        assert_eq!(info_castellano.region_code.as_deref(), Some("ES"));
        assert_eq!(info_castellano.display_name, "Spanish (Spain)");

        let info_pt_pt_title = resolve_track_language("por", "Português (Portugal)", 0);
        assert_eq!(info_pt_pt_title.region_code.as_deref(), Some("PT"));
        assert_eq!(info_pt_pt_title.display_name, "Portuguese (Portugal)");
    }

    #[test]
    fn test_various_languages() {
        let en = resolve_track_language("eng", "", 0);
        assert_eq!(en.language_name, "English");
        assert_eq!(en.region_code.as_deref(), Some("US"));

        let en_gb = resolve_track_language("en-GB", "", 0);
        assert_eq!(en_gb.language_name, "English");
        assert_eq!(en_gb.region_code.as_deref(), Some("GB"));

        let jp = resolve_track_language("jpn", "", 0);
        assert_eq!(jp.language_name, "Japanese");
        assert_eq!(jp.region_code.as_deref(), Some("JP"));
    }
}
