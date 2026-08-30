// subtitle.rs
//
// Módulo de legendas da camada media-logic (100% livre de dependências NDK/Android,
// executável diretamente no host com `cargo test -p media-logic`).
//
// Implementa:
// 1. Parser SRT (SubRip) — T9.1
// 2. Parser WebVTT (Web Video Text Tracks) — T9.2
// 3. Sincronização por PTS com ajuste de offset temporal — T9.4
// 4. Detecção e conversão automática de encoding (UTF-8, Latin1, Windows-1252, etc.) — T9.5

/// Representa uma entrada/cue de legenda individual no fluxo de reprodução.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SubtitleEntry {
    /// Índice sequencial da entrada (1-indexed por padrão em SRT).
    pub index: u32,
    /// Instante inicial de exibição em milissegundos.
    pub start_ms: u64,
    /// Instante final de exibição em milissegundos.
    pub end_ms: u64,
    /// Texto limpo a ser exibido na tela (quebras de linha preservadas).
    pub text: String,
}

/// Remove tags HTML/estilo comuns em legendas (`<b>`, `<i>`, `<u>`, `<font...>`, `<c...>`, etc.)
/// e entidades HTML básicas (`&nbsp;`, `&amp;`, `&lt;`, `&gt;`, `&quot;`).
pub fn sanitize_subtitle_text(input: &str) -> String {
    let mut result = String::with_capacity(input.len());
    let mut in_tag = false;

    for ch in input.chars() {
        if ch == '<' {
            in_tag = true;
        } else if ch == '>' {
            in_tag = false;
        } else if !in_tag {
            result.push(ch);
        }
    }

    // Decodificar entidades HTML comuns
    result
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .trim()
        .to_string()
}

/// Converte uma string de timestamp SRT/VTT (`00:01:23,456`, `00:01:23.456` ou `01:23.456`)
/// para milissegundos absolutos (`u64`).
pub fn parse_timestamp_ms(s: &str) -> Option<u64> {
    let s = s.trim();
    if s.is_empty() {
        return None;
    }

    // Normalizar separador de milissegundos (, ou .)
    let (time_part, millis_part) = if let Some(idx) = s.find([',', '.']) {
        let (t, m) = s.split_at(idx);
        let m_clean = &m[1..]; // descarta o separador
        (t, m_clean)
    } else {
        (s, "0")
    };

    let millis: u64 = match millis_part.len() {
        0 => 0,
        1 => millis_part.parse::<u64>().ok()? * 100,
        2 => millis_part.parse::<u64>().ok()? * 10,
        3 => millis_part.parse::<u64>().ok()?,
        _ => millis_part[..3].parse::<u64>().ok()?,
    };

    let parts: Vec<&str> = time_part.split(':').collect();
    match parts.len() {
        3 => {
            let hours: u64 = parts[0].trim().parse().ok()?;
            let minutes: u64 = parts[1].trim().parse().ok()?;
            let seconds: u64 = parts[2].trim().parse().ok()?;
            Some(hours * 3_600_000 + minutes * 60_000 + seconds * 1_000 + millis)
        }
        2 => {
            let minutes: u64 = parts[0].trim().parse().ok()?;
            let seconds: u64 = parts[1].trim().parse().ok()?;
            Some(minutes * 60_000 + seconds * 1_000 + millis)
        }
        1 => {
            let seconds: u64 = parts[0].trim().parse().ok()?;
            Some(seconds * 1_000 + millis)
        }
        _ => None,
    }
}

/// Analisa a linha de tempo no formato `start --> end [settings]`.
fn parse_timing_line(line: &str) -> Option<(u64, u64)> {
    let mut parts = line.split("-->");
    let start_str = parts.next()?.trim();
    let rest = parts.next()?.trim();

    // A parte final pode conter opções/settings (ex: `00:00:05.000 align:start position:20%`)
    let end_str = rest.split_whitespace().next()?;

    let start_ms = parse_timestamp_ms(start_str)?;
    let end_ms = parse_timestamp_ms(end_str)?;

    Some((start_ms, end_ms))
}

/// Parser para o formato SubRip (.srt) — T9.1.
///
/// Tolera variações de formato: índices faltantes, separadores CRLF/LF,
/// tags HTML no texto e múltiplos blocos sem linha em branco entre eles.
pub fn parse_srt(content: &str) -> Vec<SubtitleEntry> {
    let mut entries = Vec::new();
    let normalized = content.replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<&str> = normalized.lines().collect();

    let mut i = 0;
    let mut auto_index = 1u32;

    while i < lines.len() {
        let line = lines[i].trim();
        if line.is_empty() {
            i += 1;
            continue;
        }

        // Tenta ler índice numérico opcional
        let mut cue_index = auto_index;
        let mut timing_line = line;

        if let Ok(num) = line.parse::<u32>() {
            cue_index = num;
            i += 1;
            if i >= lines.len() {
                break;
            }
            timing_line = lines[i].trim();
        }

        // Verifica se é a linha de timestamp `-->`
        if let Some((start_ms, end_ms)) = parse_timing_line(timing_line) {
            i += 1;
            let mut text_lines = Vec::new();

            // Acumula linhas de texto até encontrar linha em branco ou próximo bloco
            while i < lines.len() {
                let text_line = lines[i];
                if text_line.trim().is_empty() {
                    i += 1;
                    break;
                }
                // Se a linha contiver `-->`, é o início de um próximo bloco sem separador
                if text_line.contains("-->") {
                    break;
                }
                text_lines.push(text_line);
                i += 1;
            }

            let raw_text = text_lines.join("\n");
            let clean_text = sanitize_subtitle_text(&raw_text);

            if !clean_text.is_empty() && end_ms >= start_ms {
                entries.push(SubtitleEntry {
                    index: cue_index,
                    start_ms,
                    end_ms,
                    text: clean_text,
                });
                auto_index = cue_index + 1;
            }
        } else {
            i += 1;
        }
    }

    // Garante ordenação cronológica por tempo inicial
    entries.sort_by_key(|e| e.start_ms);
    entries
}

/// Parser para o formato WebVTT (.vtt) — T9.2.
///
/// Suporta header `WEBVTT`, blocos de comentário `NOTE`, identificadores
/// opcionais de cue e cue settings (`position:`, `align:`).
pub fn parse_vtt(content: &str) -> Vec<SubtitleEntry> {
    let mut entries = Vec::new();
    let normalized = content.replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<&str> = normalized.lines().collect();

    let mut i = 0;
    let mut auto_index = 1u32;

    // Ignora header WEBVTT e metadados iniciais até a primeira linha vazia
    if !lines.is_empty() && lines[0].trim_start().starts_with("WEBVTT") {
        i += 1;
        while i < lines.len() && !lines[i].trim().is_empty() {
            i += 1;
        }
    }

    while i < lines.len() {
        let line = lines[i].trim();
        if line.is_empty() {
            i += 1;
            continue;
        }

        // Pular blocos de comentário NOTE
        if line.starts_with("NOTE") {
            i += 1;
            while i < lines.len() && !lines[i].trim().is_empty() {
                i += 1;
            }
            continue;
        }

        // Pular blocos STYLE e REGION
        if line.starts_with("STYLE") || line.starts_with("REGION") {
            i += 1;
            while i < lines.len() && !lines[i].trim().is_empty() {
                i += 1;
            }
            continue;
        }

        let mut timing_line = line;
        let mut cue_index = auto_index;

        // Se a linha atual não tiver `-->`, pode ser um identificador de cue
        if !line.contains("-->") {
            if let Ok(num) = line.parse::<u32>() {
                cue_index = num;
            }
            i += 1;
            if i >= lines.len() {
                break;
            }
            timing_line = lines[i].trim();
        }

        if let Some((start_ms, end_ms)) = parse_timing_line(timing_line) {
            i += 1;
            let mut text_lines = Vec::new();

            while i < lines.len() {
                let text_line = lines[i];
                if text_line.trim().is_empty() {
                    i += 1;
                    break;
                }
                if text_line.contains("-->") {
                    break;
                }
                text_lines.push(text_line);
                i += 1;
            }

            let raw_text = text_lines.join("\n");
            let clean_text = sanitize_subtitle_text(&raw_text);

            if !clean_text.is_empty() && end_ms >= start_ms {
                entries.push(SubtitleEntry {
                    index: cue_index,
                    start_ms,
                    end_ms,
                    text: clean_text,
                });
                auto_index = cue_index + 1;
            }
        } else {
            i += 1;
        }
    }

    entries.sort_by_key(|e| e.start_ms);
    entries
}

/// Detecta a codificação de caracteres dos bytes brutos de um arquivo de legenda
/// e decodifica para `String` UTF-8 válida — T9.5.
///
/// Suporta UTF-8 com/sem BOM, UTF-16LE, UTF-16BE, Latin-1 (ISO-8859-1) e Windows-1252.
pub fn detect_and_decode(bytes: &[u8]) -> String {
    if bytes.is_empty() {
        return String::new();
    }

    // 1. Verificação de Byte Order Mark (BOM)
    if bytes.starts_with(&[0xEF, 0xBB, 0xBF]) {
        // UTF-8 BOM
        return String::from_utf8_lossy(&bytes[3..]).into_owned();
    }
    if bytes.starts_with(&[0xFF, 0xFE]) {
        // UTF-16LE BOM
        let (cow, _, _) = encoding_rs::UTF_16LE.decode(&bytes[2..]);
        return cow.into_owned();
    }
    if bytes.starts_with(&[0xFE, 0xFF]) {
        // UTF-16BE BOM
        let (cow, _, _) = encoding_rs::UTF_16BE.decode(&bytes[2..]);
        return cow.into_owned();
    }

    // 2. Tentar UTF-8 estrito primeiro (formato padrão mais comum)
    if let Ok(utf8_str) = std::str::from_utf8(bytes) {
        return utf8_str.to_string();
    }

    // 3. Auto-detecção de encoding com chardetng (Mozilla)
    let mut detector = chardetng::EncodingDetector::new();
    detector.feed(bytes, true);
    let encoding = detector.guess(None, true);

    let (cow, _, _) = encoding.decode(bytes);
    cow.into_owned()
}

/// Realiza busca rápida da entrada de legenda ativa para o instante `pts_ms`
/// com aplicação do offset temporal `offset_ms` — T9.4.
///
/// Complexidade: $O(\log N)$ via busca binária na lista ordenada de `SubtitleEntry`.
pub fn find_active_cue(
    entries: &[SubtitleEntry],
    pts_ms: i64,
    offset_ms: i64,
) -> Option<&SubtitleEntry> {
    if entries.is_empty() {
        return None;
    }

    let effective_pts = pts_ms + offset_ms;
    if effective_pts < 0 {
        return None;
    }
    let target = effective_pts as u64;

    // Encontra a primeira entrada cujo start_ms é maior que o target
    let partition_idx = entries.partition_point(|e| e.start_ms <= target);

    // O cue ativo (se houver) deve estar entre os itens que iniciaram antes ou em target
    if partition_idx == 0 {
        return None;
    }

    // Percorrer de trás para frente a partir de partition_idx - 1 para encontrar o cue ativo
    for entry in entries[..partition_idx].iter().rev() {
        if target >= entry.start_ms && target < entry.end_ms {
            return Some(entry);
        }
        // Se já retrocedeu além de onde cues normais costumam durar (ex.: 30s), pode parar
        if target.saturating_sub(entry.start_ms) > 30_000 {
            break;
        }
    }

    None
}

/// Normaliza um código de idioma para o subtag primário em minúsculas,
/// convertendo ISO 639-2 (3 letras) para ISO 639-1 (2 letras) nos idiomas mais
/// comuns. `"pt-BR"` -> `"pt"`, `"por"` -> `"pt"`, `"eng"` -> `"en"`,
/// `"jpn"` -> `"ja"`. Códigos desconhecidos são devolvidos como o primeiro
/// subtag em minúsculas, sem tradução.
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
        other => other,
    }
    .to_string()
}

/// T7.6 — auto-seleção de faixa de legenda pelo idioma do sistema.
///
/// `track_languages` são os códigos de idioma das faixas disponíveis, na ordem
/// em que aparecem (como vêm dos metadados do container: `"por"`, `"eng"`,
/// `"jpn"`, ...). `system_language` costuma ser `Locale.getDefault()` do Android
/// (`"pt-BR"`, `"en"`, ...).
///
/// Retorna o índice da primeira faixa cujo idioma coincide após normalização, ou
/// `None` se nenhuma coincidir — nesse caso o chamador aplica a regra anterior
/// da cadeia de prioridade ("primeira faixa disponível").
pub fn match_subtitle_language(track_languages: &[String], system_language: &str) -> Option<usize> {
    let target = normalize_language_code(system_language);
    if target.is_empty() {
        return None;
    }
    track_languages
        .iter()
        .position(|lang| normalize_language_code(lang) == target)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_timestamp_ms_standard_formats() {
        assert_eq!(parse_timestamp_ms("00:01:23,456"), Some(83456));
        assert_eq!(parse_timestamp_ms("00:01:23.456"), Some(83456));
        assert_eq!(parse_timestamp_ms("01:23.456"), Some(83456));
        assert_eq!(parse_timestamp_ms("00:00:05,000"), Some(5000));
        assert_eq!(parse_timestamp_ms("00:00:00,100"), Some(100));
        assert_eq!(parse_timestamp_ms("01:00:00,000"), Some(3_600_000));
    }

    #[test]
    fn test_sanitize_subtitle_text_strips_html_and_unescapes() {
        let input = "<b>Olá</b>, <i>mundo</i>! &amp; Bem-vindo &lt;VR&gt;";
        assert_eq!(sanitize_subtitle_text(input), "Olá, mundo! & Bem-vindo <VR>");

        let font_tag = "<font color=\"#ff0000\">Texto Colorido</font>";
        assert_eq!(sanitize_subtitle_text(font_tag), "Texto Colorido");
    }

    #[test]
    fn test_parse_srt_simple_and_multiline() {
        let srt = r#"1
00:00:01,000 --> 00:00:04,000
Primeira fala do vídeo

2
00:00:05,500 --> 00:00:08,200
Segunda fala
com duas linhas!
"#;
        let cues = parse_srt(srt);
        assert_eq!(cues.len(), 2);

        assert_eq!(cues[0].index, 1);
        assert_eq!(cues[0].start_ms, 1000);
        assert_eq!(cues[0].end_ms, 4000);
        assert_eq!(cues[0].text, "Primeira fala do vídeo");

        assert_eq!(cues[1].index, 2);
        assert_eq!(cues[1].start_ms, 5500);
        assert_eq!(cues[1].end_ms, 8200);
        assert_eq!(cues[1].text, "Segunda fala\ncom duas linhas!");
    }

    #[test]
    fn test_parse_srt_with_crlf_and_tags() {
        let srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\n<i>Texto em itálico</i>\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\n<b>Negrito</b>";
        let cues = parse_srt(srt);
        assert_eq!(cues.len(), 2);
        assert_eq!(cues[0].text, "Texto em itálico");
        assert_eq!(cues[1].text, "Negrito");
    }

    #[test]
    fn test_parse_vtt_with_header_notes_and_settings() {
        let vtt = r#"WEBVTT - Comentário do arquivo

NOTE
Este é um bloco de anotações ignorado pelo parser

00:01.000 --> 00:04.000 position:50% align:middle
Primeira linha WebVTT

identificador-2
00:05.500 --> 00:08.000
Segunda linha WebVTT
"#;
        let cues = parse_vtt(vtt);
        assert_eq!(cues.len(), 2);
        assert_eq!(cues[0].start_ms, 1000);
        assert_eq!(cues[0].end_ms, 4000);
        assert_eq!(cues[0].text, "Primeira linha WebVTT");

        assert_eq!(cues[1].start_ms, 5500);
        assert_eq!(cues[1].end_ms, 8000);
        assert_eq!(cues[1].text, "Segunda linha WebVTT");
    }

    #[test]
    fn test_find_active_cue_with_offset() {
        let cues = vec![
            SubtitleEntry { index: 1, start_ms: 1000, end_ms: 3000, text: "Um".into() },
            SubtitleEntry { index: 2, start_ms: 5000, end_ms: 8000, text: "Dois".into() },
            SubtitleEntry { index: 3, start_ms: 10000, end_ms: 12000, text: "Três".into() },
        ];

        // Antes do início
        assert_eq!(find_active_cue(&cues, 500, 0), None);

        // Durante o primeiro cue
        assert_eq!(find_active_cue(&cues, 1500, 0).map(|c| c.text.as_str()), Some("Um"));
        assert_eq!(find_active_cue(&cues, 3000, 0), None); // limite final é exclusivo

        // Durante intervalo entre cues
        assert_eq!(find_active_cue(&cues, 4000, 0), None);

        // Segundo cue
        assert_eq!(find_active_cue(&cues, 6000, 0).map(|c| c.text.as_str()), Some("Dois"));

        // Com offset positivo (+1000ms): PTS de 4500 vira 5500 -> acha "Dois"
        assert_eq!(find_active_cue(&cues, 4500, 1000).map(|c| c.text.as_str()), Some("Dois"));

        // Com offset negativo (-1000ms): PTS de 6000 vira 5000 -> acha "Dois"
        assert_eq!(find_active_cue(&cues, 6000, -1000).map(|c| c.text.as_str()), Some("Dois"));
    }

    #[test]
    fn test_encoding_detection_utf8_and_latin1() {
        // UTF-8 normal com acentos em PT-BR
        let utf8_bytes = "Ação, coração, você, não!".as_bytes();
        let decoded = detect_and_decode(utf8_bytes);
        assert_eq!(decoded, "Ação, coração, você, não!");

        // UTF-8 com BOM
        let mut utf8_bom = vec![0xEF, 0xBB, 0xBF];
        utf8_bom.extend_from_slice("Olá Mundo".as_bytes());
        assert_eq!(detect_and_decode(&utf8_bom), "Olá Mundo");

        // Windows-1252 / Latin-1 bytes para "Ação"
        // 'A' = 0x41, 'ç' = 0xE7, 'ã' = 0xE3, 'o' = 0x6F
        let latin1_bytes = vec![0x41, 0xE7, 0xE3, 0x6F];
        let decoded_latin1 = detect_and_decode(&latin1_bytes);
        assert_eq!(decoded_latin1, "Ação");
    }

    #[test]
    fn test_normalize_language_code() {
        assert_eq!(normalize_language_code("pt-BR"), "pt");
        assert_eq!(normalize_language_code("pt_BR"), "pt");
        assert_eq!(normalize_language_code("por"), "pt");
        assert_eq!(normalize_language_code("POR"), "pt");
        assert_eq!(normalize_language_code("eng"), "en");
        assert_eq!(normalize_language_code("jpn"), "ja");
        assert_eq!(normalize_language_code("en"), "en");
        assert_eq!(normalize_language_code("  fre  "), "fr");
        assert_eq!(normalize_language_code("xyz"), "xyz");
        assert_eq!(normalize_language_code(""), "");
    }

    #[test]
    fn test_match_subtitle_language_t76() {
        let tracks = vec![
            "por".to_string(),
            "eng".to_string(),
            "jpn".to_string(),
        ];
        assert_eq!(match_subtitle_language(&tracks, "pt-BR"), Some(0));
        assert_eq!(match_subtitle_language(&tracks, "en"), Some(1));
        assert_eq!(match_subtitle_language(&tracks, "ja"), Some(2));
        assert_eq!(match_subtitle_language(&tracks, "de"), None);
        assert_eq!(match_subtitle_language(&tracks, ""), None);

        // Primeira faixa que coincide vence quando há duplicatas de idioma.
        let dup = vec!["eng".to_string(), "en".to_string(), "pt".to_string()];
        assert_eq!(match_subtitle_language(&dup, "en-US"), Some(0));

        // Lista vazia.
        assert_eq!(match_subtitle_language(&[], "pt"), None);
    }
}
