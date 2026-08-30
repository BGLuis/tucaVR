// subtitle_ass.rs
//
// Parser ASS/SSA (Advanced SubStation Alpha / SubStation Alpha) da camada
// media-logic — T7.1 da Fase 0.3, Seção 7.
//
// 100% livre de dependências NDK/Android: roda no host com
// `cargo test -p media-logic subtitle_ass`.
//
// Escopo v0.3 (ver o `[!CAUTION]` em docs/phases/PHASE-0.3-POLISH-AUDIO.md §7 —
// "ASS rendering é extremamente complexo"):
//   SUPORTADO — seções [Script Info] / [V4+ Styles] / [V4 Styles] / [Events];
//   estilos (fonte, tamanho, cores &HAABBGGRR, bold/italic/underline, outline,
//   shadow, alignment 1–9, margens); override tags `\pos`, `\an`/`\a`, `\c`/`\1c`,
//   `\fs`, `\fn`, `\b`, `\i`, `\u`, `\fad`, `\N`/`\n`/`\h`.
//   FORA DE ESCOPO — `\t` (animação), `\move` (usa só o ponto de origem),
//   `\clip`/`\iclip`, `\p` (desenho vetorial — o texto em modo desenho é
//   descartado), karaokê (`\k`), rotação/shear (`\fr*`, `\fsc*`, `\fax`).
//   Essas tags são REMOVIDAS do texto, nunca renderizadas como literais.
//
// A saída tem dois níveis:
//   - `AssSubtitle` completo (script info + estilos + eventos com spans e
//     posicionamento resolvidos) para o renderizador ASS dedicado (T7.2, C++).
//   - `to_subtitle_entries()` / `parse_ass_to_entries()` colapsa tudo em
//     `Vec<SubtitleEntry>` (texto puro), permitindo que o caminho de render de
//     texto simples já existente (font atlas + `find_active_cue`) exiba legendas
//     `.ass` hoje, ignorando estilo/posição.

use crate::subtitle::{parse_timestamp_ms, SubtitleEntry};

// ============================================================================
// Tipos públicos
// ============================================================================

/// Cor ASS já convertida para semântica "de exibição": `a = 255` é opaco,
/// `a = 0` é transparente. O formato de arquivo ASS armazena o inverso no byte
/// `AA` de `&HAABBGGRR` (`00` = opaco), e a conversão é feita no parse.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AssColor {
    pub r: u8,
    pub g: u8,
    pub b: u8,
    pub a: u8,
}

impl AssColor {
    pub const WHITE: AssColor = AssColor { r: 255, g: 255, b: 255, a: 255 };
    pub const BLACK: AssColor = AssColor { r: 0, g: 0, b: 0, a: 255 };
    pub const RED: AssColor = AssColor { r: 255, g: 0, b: 0, a: 255 };
}

/// Converte um valor de cor ASS para `AssColor`.
///
/// Aceita `&HAABBGGRR`, `&HBBGGRR`, `&Hbbggrr&` (com `&` de fechamento das
/// override tags) e o inteiro decimal usado por arquivos SSA antigos.
pub fn parse_ass_color(raw: &str) -> Option<AssColor> {
    let s = raw.trim();
    let value: u32 = if let Some(rest) = s.strip_prefix("&H").or_else(|| s.strip_prefix("&h")) {
        let digits = rest.trim_end_matches('&').trim();
        u32::from_str_radix(digits, 16).ok()?
    } else {
        // SSA antigo: inteiro decimal (às vezes negativo em arquivos ruins).
        s.parse::<i64>().ok()? as u32
    };

    let aa = ((value >> 24) & 0xFF) as u8;
    let bb = ((value >> 16) & 0xFF) as u8;
    let gg = ((value >> 8) & 0xFF) as u8;
    let rr = (value & 0xFF) as u8;
    Some(AssColor {
        r: rr,
        g: gg,
        b: bb,
        a: 255u8.wrapping_sub(aa),
    })
}

/// Estilo nomeado da seção `[V4+ Styles]` (ou `[V4 Styles]` no SSA legado).
#[derive(Debug, Clone, PartialEq)]
pub struct AssStyle {
    pub name: String,
    pub font_name: String,
    pub font_size: f32,
    pub primary_colour: AssColor,
    pub secondary_colour: AssColor,
    pub outline_colour: AssColor,
    pub back_colour: AssColor,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub strike_out: bool,
    pub scale_x: f32,
    pub scale_y: f32,
    pub spacing: f32,
    pub angle: f32,
    /// 1 = outline + shadow, 3 = caixa opaca.
    pub border_style: u8,
    pub outline: f32,
    pub shadow: f32,
    /// Posição numpad 1–9 (canto inferior-esquerdo = 1, centro = 5, topo-direita = 9).
    pub alignment: u8,
    pub margin_l: i32,
    pub margin_r: i32,
    pub margin_v: i32,
}

impl Default for AssStyle {
    fn default() -> Self {
        Self {
            name: "Default".to_string(),
            font_name: "Sans".to_string(),
            font_size: 20.0,
            primary_colour: AssColor::WHITE,
            secondary_colour: AssColor::RED,
            outline_colour: AssColor::BLACK,
            back_colour: AssColor::BLACK,
            bold: false,
            italic: false,
            underline: false,
            strike_out: false,
            scale_x: 100.0,
            scale_y: 100.0,
            spacing: 0.0,
            angle: 0.0,
            border_style: 1,
            outline: 2.0,
            shadow: 0.0,
            alignment: 2,
            margin_l: 10,
            margin_r: 10,
            margin_v: 10,
        }
    }
}

/// Cabeçalho `[Script Info]`. `play_res_*` são a referência de coordenadas para
/// `\pos` e margens — o renderizador escala isso para a tela virtual.
#[derive(Debug, Clone, PartialEq)]
pub struct AssScriptInfo {
    pub play_res_x: u32,
    pub play_res_y: u32,
    pub wrap_style: u8,
    pub scaled_border_and_shadow: bool,
    pub title: String,
}

impl Default for AssScriptInfo {
    fn default() -> Self {
        Self {
            play_res_x: 384,
            play_res_y: 288,
            wrap_style: 2,
            scaled_border_and_shadow: false,
            title: String::new(),
        }
    }
}

/// `\fad(in, out)` em milissegundos.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AssFade {
    pub fade_in_ms: u32,
    pub fade_out_ms: u32,
}

/// Trecho contíguo de texto com propriedades resolvidas depois de aplicar os
/// override tags inline. O renderizador desenha um span de cada vez.
#[derive(Debug, Clone, PartialEq)]
pub struct AssSpan {
    pub text: String,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub colour: AssColor,
    pub font_name: String,
    pub font_size: f32,
}

/// Uma linha `Dialogue:` da seção `[Events]`.
#[derive(Debug, Clone, PartialEq)]
pub struct AssEvent {
    pub layer: i32,
    pub start_ms: u64,
    pub end_ms: u64,
    pub style_name: String,
    /// Campo `Name`/`Actor` (nome de personagem).
    pub actor: String,
    pub effect: String,
    /// Estilo resolvido: cópia do estilo nomeado (ou `Default`), já com as
    /// margens da própria linha sobrepostas quando `!= 0`.
    pub style: AssStyle,
    /// Texto limpo: `\N`/`\n` viram `\n`, `\h` vira espaço, todo bloco `{...}`
    /// removido, texto em modo desenho (`\p1`) descartado.
    pub text: String,
    /// Texto quebrado em trechos por override tags de estilo inline.
    pub spans: Vec<AssSpan>,
    /// `\pos(x, y)` (ou o ponto de origem de `\move`) em coordenadas PlayRes.
    pub pos: Option<(f32, f32)>,
    /// Alignment efetivo: override `\an`/`\a`, senão o do estilo.
    pub alignment: u8,
    pub fade: Option<AssFade>,
}

/// Documento ASS/SSA completo.
#[derive(Debug, Clone, PartialEq)]
pub struct AssSubtitle {
    pub script_info: AssScriptInfo,
    pub styles: Vec<AssStyle>,
    pub events: Vec<AssEvent>,
}

impl AssSubtitle {
    /// Colapsa os eventos em `SubtitleEntry`s de texto puro, ordenados por tempo,
    /// descartando eventos vazios ou com `end < start`. É o que permite a
    /// legenda `.ass` aparecer no caminho de render de texto simples atual.
    pub fn to_subtitle_entries(&self) -> Vec<SubtitleEntry> {
        let mut entries: Vec<SubtitleEntry> = self
            .events
            .iter()
            .filter(|e| e.end_ms >= e.start_ms && !e.text.trim().is_empty())
            .map(|e| SubtitleEntry {
                index: 0,
                start_ms: e.start_ms,
                end_ms: e.end_ms,
                text: e.text.clone(),
            })
            .collect();
        entries.sort_by_key(|e| e.start_ms);
        for (i, e) in entries.iter_mut().enumerate() {
            e.index = (i + 1) as u32;
        }
        entries
    }
}

// ============================================================================
// Parser principal
// ============================================================================

#[derive(Clone, Copy)]
enum Section {
    None,
    ScriptInfo,
    Styles { legacy: bool },
    Events,
}

impl Section {
    fn from_name(name: &str) -> Section {
        match name.trim().to_ascii_lowercase().as_str() {
            "script info" => Section::ScriptInfo,
            "v4 styles" | "v4styles" => Section::Styles { legacy: true },
            "v4+ styles" | "v4++ styles" | "v4+styles" => Section::Styles { legacy: false },
            "events" => Section::Events,
            _ => Section::None,
        }
    }
}

/// Faz o parse de um documento ASS/SSA completo.
pub fn parse_ass(content: &str) -> AssSubtitle {
    let normalized = content.replace("\r\n", "\n").replace('\r', "\n");

    let mut script_info = AssScriptInfo::default();
    let mut styles: Vec<AssStyle> = Vec::new();
    let mut events: Vec<AssEvent> = Vec::new();

    let mut section = Section::None;
    let mut style_format: Vec<String> = Vec::new();
    let mut event_format: Vec<String> = Vec::new();

    for raw_line in normalized.lines() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with(';') {
            continue;
        }
        if let Some(inner) = line.strip_prefix('[').and_then(|s| s.strip_suffix(']')) {
            section = Section::from_name(inner);
            continue;
        }

        match section {
            Section::ScriptInfo => apply_script_info(line, &mut script_info),
            Section::Styles { legacy } => {
                if let Some(fmt) = line.strip_prefix("Format:") {
                    style_format = split_fields(fmt);
                } else if let Some(rest) = line.strip_prefix("Style:") {
                    if style_format.is_empty() {
                        style_format = default_style_format();
                    }
                    if let Some(st) = parse_style_line(rest, &style_format, legacy) {
                        styles.push(st);
                    }
                }
            }
            Section::Events => {
                if let Some(fmt) = line.strip_prefix("Format:") {
                    event_format = split_fields(fmt);
                } else if let Some(rest) = line.strip_prefix("Dialogue:") {
                    if event_format.is_empty() {
                        event_format = default_event_format();
                    }
                    if let Some(ev) = parse_dialogue_line(rest, &event_format, &styles) {
                        events.push(ev);
                    }
                }
                // "Comment:", "Picture:", "Sound:" etc. são ignorados.
            }
            Section::None => {}
        }
    }

    if styles.is_empty() {
        styles.push(AssStyle::default());
    }
    events.sort_by_key(|e| e.start_ms);

    AssSubtitle {
        script_info,
        styles,
        events,
    }
}

/// Atalho: `parse_ass` + `to_subtitle_entries`.
pub fn parse_ass_to_entries(content: &str) -> Vec<SubtitleEntry> {
    parse_ass(content).to_subtitle_entries()
}

/// Remove todos os blocos de override `{...}` e resolve `\N`/`\n`/`\h`,
/// devolvendo apenas o texto exibível. Útil para o caminho rápido de pacotes
/// de legenda embutida que só precisam do texto.
pub fn strip_ass_override_tags(text: &str) -> String {
    parse_event_text(text, &AssStyle::default()).plain
}

// ============================================================================
// Reconstrução a partir de pacotes de container (MKV / S_TEXT/ASS)
// ============================================================================

/// Um pacote de legenda ASS entregue pelo demuxer. Em Matroska o corpo tem o
/// formato `ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`
/// (sem `Start`/`End` — o tempo vem do PTS/duração do pacote).
#[derive(Debug, Clone)]
pub struct MkvAssPacket {
    pub start_ms: u64,
    pub end_ms: u64,
    pub body: String,
}

/// Reconstrói um documento ASS textual a partir do cabeçalho do stream
/// (`extradata`, que traz `[Script Info]` + `[V4+ Styles]` + a linha `Format:`
/// de `[Events]`) e da lista de pacotes de diálogo. O resultado pode ser passado
/// direto para [`parse_ass`].
pub fn ass_document_from_mkv_packets(header: &str, packets: &[MkvAssPacket]) -> String {
    let trimmed = header.trim_end();
    let lower = trimmed.to_ascii_lowercase();

    let mut doc = String::with_capacity(trimmed.len() + packets.len() * 64 + 128);
    doc.push_str(trimmed);
    doc.push('\n');

    if !lower.contains("[events]") {
        doc.push_str("\n[Events]\n");
        doc.push_str(EVENT_FORMAT_LINE);
        doc.push('\n');
    } else if !lower.contains("format:") {
        doc.push_str(EVENT_FORMAT_LINE);
        doc.push('\n');
    }

    for p in packets {
        // splitn(9) preserva vírgulas dentro do campo Text (o último).
        let mut it = p.body.splitn(9, ',');
        let mut f = [""; 9];
        for slot in f.iter_mut() {
            if let Some(v) = it.next() {
                *slot = v;
            }
        }
        // f = [ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text]
        let layer = if f[1].trim().is_empty() { "0" } else { f[1].trim() };
        doc.push_str("Dialogue: ");
        doc.push_str(layer);
        doc.push(',');
        doc.push_str(&format_ass_timestamp(p.start_ms));
        doc.push(',');
        doc.push_str(&format_ass_timestamp(p.end_ms));
        doc.push(',');
        doc.push_str(f[2].trim());
        doc.push(',');
        doc.push_str(f[3].trim());
        doc.push(',');
        doc.push_str(f[4].trim());
        doc.push(',');
        doc.push_str(f[5].trim());
        doc.push(',');
        doc.push_str(f[6].trim());
        doc.push(',');
        doc.push_str(f[7].trim());
        doc.push(',');
        doc.push_str(f[8]);
        doc.push('\n');
    }

    doc
}

const EVENT_FORMAT_LINE: &str =
    "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text";

fn format_ass_timestamp(ms: u64) -> String {
    let cs = (ms % 1000) / 10;
    let total_s = ms / 1000;
    let s = total_s % 60;
    let m = (total_s / 60) % 60;
    let h = total_s / 3600;
    format!("{h}:{m:02}:{s:02}.{cs:02}")
}

// ============================================================================
// Helpers de parse — [Script Info] / [V4+ Styles]
// ============================================================================

fn split_fields(s: &str) -> Vec<String> {
    s.split(',').map(|f| f.trim().to_string()).collect()
}

fn default_style_format() -> Vec<String> {
    split_fields(
        "Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, \
         Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, \
         Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
    )
}

fn default_event_format() -> Vec<String> {
    split_fields("Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
}

fn apply_script_info(line: &str, info: &mut AssScriptInfo) {
    let Some((key, value)) = line.split_once(':') else {
        return;
    };
    let value = value.trim();
    match key.trim().to_ascii_lowercase().as_str() {
        "playresx" => {
            if let Ok(v) = value.parse() {
                info.play_res_x = v;
            }
        }
        "playresy" => {
            if let Ok(v) = value.parse() {
                info.play_res_y = v;
            }
        }
        "wrapstyle" => {
            if let Ok(v) = value.parse() {
                info.wrap_style = v;
            }
        }
        "scaledborderandshadow" => {
            info.scaled_border_and_shadow = value.eq_ignore_ascii_case("yes") || value == "1";
        }
        "title" => info.title = value.to_string(),
        _ => {}
    }
}

/// SSA legado (`\a` e `[V4 Styles]`) usa outra numeração de alignment.
/// 1–3 = base, 5–7 = topo, 9–11 = meio. Converte para numpad 1–9.
fn legacy_alignment_to_numpad(a: u8) -> u8 {
    match a {
        1..=3 => a,
        5..=7 => a + 2,   // 5->7, 6->8, 7->9
        9..=11 => a - 5,  // 9->4, 10->5, 11->6
        _ => 2,
    }
}

fn parse_bool_ass(v: &str) -> bool {
    // ASS usa -1 para verdadeiro e 0 para falso; alguns arquivos usam 1.
    v.trim().parse::<i32>().map(|n| n != 0).unwrap_or(false)
}

fn split_n(s: &str, n: usize) -> Vec<&str> {
    s.splitn(n.max(1), ',').collect()
}

fn parse_style_line(rest: &str, fmt: &[String], legacy: bool) -> Option<AssStyle> {
    let values = split_n(rest, fmt.len());
    let get = |name: &str| -> Option<&str> {
        fmt.iter()
            .position(|f| f.eq_ignore_ascii_case(name))
            .and_then(|i| values.get(i).map(|s| s.trim()))
    };

    let mut st = AssStyle::default();
    if let Some(v) = get("Name") {
        if !v.is_empty() {
            st.name = v.to_string();
        }
    }
    if let Some(v) = get("Fontname") {
        if !v.is_empty() {
            st.font_name = v.to_string();
        }
    }
    if let Some(v) = get("Fontsize").and_then(|v| v.parse().ok()) {
        st.font_size = v;
    }
    if let Some(c) = get("PrimaryColour").and_then(parse_ass_color) {
        st.primary_colour = c;
    }
    if let Some(c) = get("SecondaryColour").and_then(parse_ass_color) {
        st.secondary_colour = c;
    }
    if let Some(c) = get("OutlineColour").or_else(|| get("TertiaryColour")).and_then(parse_ass_color) {
        st.outline_colour = c;
    }
    if let Some(c) = get("BackColour").and_then(parse_ass_color) {
        st.back_colour = c;
    }
    if let Some(v) = get("Bold") {
        st.bold = parse_bool_ass(v);
    }
    if let Some(v) = get("Italic") {
        st.italic = parse_bool_ass(v);
    }
    if let Some(v) = get("Underline") {
        st.underline = parse_bool_ass(v);
    }
    if let Some(v) = get("StrikeOut") {
        st.strike_out = parse_bool_ass(v);
    }
    if let Some(v) = get("ScaleX").and_then(|v| v.parse().ok()) {
        st.scale_x = v;
    }
    if let Some(v) = get("ScaleY").and_then(|v| v.parse().ok()) {
        st.scale_y = v;
    }
    if let Some(v) = get("Spacing").and_then(|v| v.parse().ok()) {
        st.spacing = v;
    }
    if let Some(v) = get("Angle").and_then(|v| v.parse().ok()) {
        st.angle = v;
    }
    if let Some(v) = get("BorderStyle").and_then(|v| v.parse().ok()) {
        st.border_style = v;
    }
    if let Some(v) = get("Outline").and_then(|v| v.parse().ok()) {
        st.outline = v;
    }
    if let Some(v) = get("Shadow").and_then(|v| v.parse().ok()) {
        st.shadow = v;
    }
    if let Some(v) = get("Alignment").and_then(|v| v.parse::<u8>().ok()) {
        st.alignment = if legacy {
            legacy_alignment_to_numpad(v)
        } else {
            v.clamp(1, 9)
        };
    }
    if let Some(v) = get("MarginL").and_then(|v| v.parse().ok()) {
        st.margin_l = v;
    }
    if let Some(v) = get("MarginR").and_then(|v| v.parse().ok()) {
        st.margin_r = v;
    }
    if let Some(v) = get("MarginV").and_then(|v| v.parse().ok()) {
        st.margin_v = v;
    }
    Some(st)
}

// ============================================================================
// Helpers de parse — [Events] / Dialogue
// ============================================================================

fn parse_dialogue_line(rest: &str, fmt: &[String], styles: &[AssStyle]) -> Option<AssEvent> {
    // O campo Text é sempre o último e pode conter vírgulas: fatiar manualmente
    // consumindo `fmt.len() - 1` vírgulas e deixando o resto como Text.
    let mut raw_values: Vec<&str> = Vec::with_capacity(fmt.len());
    let mut cursor = rest;
    for _ in 0..fmt.len().saturating_sub(1) {
        match cursor.find(',') {
            Some(idx) => {
                raw_values.push(&cursor[..idx]);
                cursor = &cursor[idx + 1..];
            }
            None => {
                raw_values.push(cursor);
                cursor = "";
            }
        }
    }
    raw_values.push(cursor);

    let field = |name: &str| -> Option<&str> {
        fmt.iter()
            .position(|f| f.eq_ignore_ascii_case(name))
            .and_then(|i| raw_values.get(i).copied())
    };

    let start_ms = parse_timestamp_ms(field("Start")?.trim())?;
    let end_ms = parse_timestamp_ms(field("End")?.trim())?;

    let layer = field("Layer")
        .or_else(|| field("Marked"))
        .map(|v| v.trim().trim_start_matches("Marked=").trim())
        .and_then(|v| v.parse::<i32>().ok())
        .unwrap_or(0);

    let style_name = field("Style").map(|s| s.trim().to_string()).unwrap_or_default();
    let actor = field("Name").map(|s| s.trim().to_string()).unwrap_or_default();
    let effect = field("Effect").map(|s| s.trim().to_string()).unwrap_or_default();
    let text_raw = field("Text").unwrap_or("");

    let mut style = styles
        .iter()
        .find(|s| s.name.eq_ignore_ascii_case(&style_name))
        .cloned()
        .unwrap_or_default();

    // Margens da própria linha sobrepõem as do estilo quando != 0.
    if let Some(v) = field("MarginL").and_then(|v| v.trim().parse::<i32>().ok()) {
        if v != 0 {
            style.margin_l = v;
        }
    }
    if let Some(v) = field("MarginR").and_then(|v| v.trim().parse::<i32>().ok()) {
        if v != 0 {
            style.margin_r = v;
        }
    }
    if let Some(v) = field("MarginV").and_then(|v| v.trim().parse::<i32>().ok()) {
        if v != 0 {
            style.margin_v = v;
        }
    }

    let parsed = parse_event_text(text_raw, &style);
    let alignment = parsed.align.unwrap_or(style.alignment);

    Some(AssEvent {
        layer,
        start_ms,
        end_ms,
        style_name,
        actor,
        effect,
        style,
        text: parsed.plain,
        spans: parsed.spans,
        pos: parsed.pos,
        alignment,
        fade: parsed.fade,
    })
}

// ============================================================================
// Override tags
// ============================================================================

struct OverrideState {
    pos: Option<(f32, f32)>,
    align: Option<u8>,
    fade: Option<AssFade>,
    /// `\p1`+: texto subsequente são comandos de desenho vetorial — descartar.
    drawing: bool,
}

fn base_span(style: &AssStyle) -> AssSpan {
    AssSpan {
        text: String::new(),
        bold: style.bold,
        italic: style.italic,
        underline: style.underline,
        colour: style.primary_colour,
        font_name: style.font_name.clone(),
        font_size: style.font_size,
    }
}

fn span_props_eq(a: &AssSpan, b: &AssSpan) -> bool {
    a.bold == b.bold
        && a.italic == b.italic
        && a.underline == b.underline
        && a.colour == b.colour
        && a.font_name == b.font_name
        && (a.font_size - b.font_size).abs() < f32::EPSILON
}

fn parse_paren_args(tag: &str) -> Vec<&str> {
    let inner = tag
        .trim()
        .trim_start_matches(|c: char| c != '(')
        .trim_start_matches('(')
        .trim_end_matches(')');
    inner.split(',').map(|s| s.trim()).collect()
}

/// Processa um token de override (sem a barra inicial). Só mexe no que está no
/// escopo v0.3; qualquer outra coisa é ignorada silenciosamente (= removida).
fn apply_tag(tag: &str, span: &mut AssSpan, ov: &mut OverrideState, style: &AssStyle) {
    let t = tag.trim();
    if t.is_empty() {
        return;
    }

    if let Some(rest) = t.strip_prefix("pos") {
        let args = parse_paren_args(rest);
        if let (Some(x), Some(y)) = (
            args.first().and_then(|v| v.parse::<f32>().ok()),
            args.get(1).and_then(|v| v.parse::<f32>().ok()),
        ) {
            ov.pos = Some((x, y));
        }
    } else if let Some(rest) = t.strip_prefix("move") {
        // Fora de escopo (animação): usa só o ponto de origem como posição fixa.
        let args = parse_paren_args(rest);
        if let (Some(x), Some(y)) = (
            args.first().and_then(|v| v.parse::<f32>().ok()),
            args.get(1).and_then(|v| v.parse::<f32>().ok()),
        ) {
            ov.pos = ov.pos.or(Some((x, y)));
        }
    } else if let Some(rest) = t.strip_prefix("an") {
        if let Ok(n) = rest.trim().parse::<u8>() {
            if (1..=9).contains(&n) {
                ov.align = Some(n);
            }
        }
    } else if let Some(rest) = t.strip_prefix("fad") {
        // \fad(in,out) — aceito. \fade(...) de 7 args — fora de escopo.
        if !rest.starts_with('e') {
            let args = parse_paren_args(rest);
            if let (Some(fin), Some(fout)) = (
                args.first().and_then(|v| v.parse::<f32>().ok()),
                args.get(1).and_then(|v| v.parse::<f32>().ok()),
            ) {
                ov.fade = Some(AssFade {
                    fade_in_ms: fin.max(0.0) as u32,
                    fade_out_ms: fout.max(0.0) as u32,
                });
            }
        }
    } else if let Some(rest) = t.strip_prefix("1c").or_else(|| {
        // `\c` mas não `\clip`
        if t.starts_with("clip") {
            None
        } else {
            t.strip_prefix('c')
        }
    }) {
        if let Some(col) = parse_ass_color(rest.trim_end_matches('&')) {
            span.colour = col;
        }
    } else if let Some(rest) = t.strip_prefix("fs") {
        // `\fs<n>` mas não `\fscx`/`\fscy`/`\fsp`
        if !rest.starts_with('c') && !rest.starts_with('p') {
            if let Ok(v) = rest.trim().parse::<f32>() {
                if v > 0.0 {
                    span.font_size = v;
                }
            }
        }
    } else if let Some(rest) = t.strip_prefix("fn") {
        let name = rest.trim();
        if !name.is_empty() {
            span.font_name = name.to_string();
        }
    } else if let Some(rest) = t.strip_prefix('b') {
        // `\b0`/`\b1`/`\b700` — mas não `\be` (blur edges) nem `\blur`/`\bord`.
        if !rest.starts_with('e') && !rest.starts_with("lur") && !rest.starts_with("ord") {
            if let Ok(n) = rest.trim().parse::<i32>() {
                span.bold = n != 0;
            }
        }
    } else if let Some(rest) = t.strip_prefix('i') {
        // `\i0`/`\i1` — mas não `\iclip`.
        if !rest.starts_with("clip") {
            if let Ok(n) = rest.trim().parse::<i32>() {
                span.italic = n != 0;
            }
        }
    } else if let Some(rest) = t.strip_prefix('u') {
        if let Ok(n) = rest.trim().parse::<i32>() {
            span.underline = n != 0;
        }
    } else if let Some(rest) = t.strip_prefix('p') {
        // `\p0` sai do modo desenho; `\p1`+ entra. (`\pos` já tratado acima,
        // `\pbo` cai no parse falho e é ignorado.)
        if let Ok(n) = rest.trim().parse::<i32>() {
            ov.drawing = n > 0;
        }
    } else if t == "r" || (t.starts_with('r') && !t.starts_with("rnd")) {
        // `\r` / `\r<Style>` — reset. Escopo v0.3: volta ao estilo base da linha.
        *span = base_span(style);
    }
    // Tudo o mais (`\t`, `\clip`, `\iclip`, `\org`, `\fr*`, `\fsc*`, `\fax`,
    // `\k*`, `\q`, `\be`, `\blur`, `\shad`, `\bord`, `\xbord`, ...) é ignorado.
}

/// Resultado de [`parse_event_text`].
struct ParsedEventText {
    plain: String,
    spans: Vec<AssSpan>,
    pos: Option<(f32, f32)>,
    align: Option<u8>,
    fade: Option<AssFade>,
}

/// Percorre o texto de um evento resolvendo override tags e escapes.
fn parse_event_text(raw: &str, style: &AssStyle) -> ParsedEventText {
    let mut plain = String::with_capacity(raw.len());
    let mut spans: Vec<AssSpan> = Vec::new();
    let mut cur = base_span(style);
    let mut ov = OverrideState {
        pos: None,
        align: None,
        fade: None,
        drawing: false,
    };

    let chars: Vec<char> = raw.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];

        if c == '{' {
            let close = chars[i + 1..]
                .iter()
                .position(|&x| x == '}')
                .map(|p| i + 1 + p);
            let Some(close) = close else {
                // '{' sem fechamento: trata como literal.
                if !ov.drawing {
                    plain.push(c);
                    cur.text.push(c);
                }
                i += 1;
                continue;
            };
            let block: String = chars[i + 1..close].iter().collect();
            if !cur.text.is_empty() {
                spans.push(cur.clone());
                cur.text.clear();
            }
            for tag in block.split('\\').skip(1) {
                apply_tag(tag, &mut cur, &mut ov, style);
            }
            i = close + 1;
            continue;
        }

        if c == '\\' && i + 1 < chars.len() {
            match chars[i + 1] {
                'N' | 'n' => {
                    if !ov.drawing {
                        plain.push('\n');
                        cur.text.push('\n');
                    }
                    i += 2;
                    continue;
                }
                'h' => {
                    if !ov.drawing {
                        plain.push(' ');
                        cur.text.push(' ');
                    }
                    i += 2;
                    continue;
                }
                _ => {}
            }
        }

        if !ov.drawing {
            plain.push(c);
            cur.text.push(c);
        }
        i += 1;
    }
    if !cur.text.is_empty() {
        spans.push(cur);
    }

    // Mescla spans adjacentes com as mesmas propriedades (um `\pos` sozinho, por
    // exemplo, faz um flush sem mudar estilo).
    let mut merged: Vec<AssSpan> = Vec::with_capacity(spans.len());
    for s in spans {
        match merged.last_mut() {
            Some(last) if span_props_eq(last, &s) => last.text.push_str(&s.text),
            _ => merged.push(s),
        }
    }

    ParsedEventText {
        plain: plain.trim().to_string(),
        spans: merged,
        pos: ov.pos,
        align: ov.align,
        fade: ov.fade,
    }
}

// ============================================================================
// Testes
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"[Script Info]
Title: Amostra
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 0
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,72,&H00FFFFFF,&H000000FF,&H00202020,&H80000000,0,0,0,0,100,100,0,0,1,3,1,2,20,20,40,1
Style: Titulo,Times New Roman,90,&H0000FFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,2,0,8,10,10,10,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,Primeira linha\Nsegunda linha
Dialogue: 0,0:00:04.00,0:00:06.00,Default,,0,0,0,,{\pos(960,540)\c&H0000FF&\b1}Vermelho em negrito{\r} normal
Dialogue: 0,0:00:07.00,0:00:09.00,Titulo,,0,0,0,,{\an5}{\t(0,500,\fscx120)}{\move(0,0,100,100)}Com tags fora de escopo
Comment: 0,0:00:07.00,0:00:09.00,Default,,0,0,0,,isto deve ser ignorado
"#;

    #[test]
    fn test_parse_ass_color_aabbggrr() {
        assert_eq!(
            parse_ass_color("&H00FFFFFF"),
            Some(AssColor { r: 255, g: 255, b: 255, a: 255 })
        );
        // AA=0x80 => transparência 128 => alfa de exibição 127; BB=FF => azul.
        assert_eq!(
            parse_ass_color("&H80FF0000"),
            Some(AssColor { r: 0, g: 0, b: 255, a: 127 })
        );
        // Totalmente transparente.
        assert_eq!(
            parse_ass_color("&HFF000000"),
            Some(AssColor { r: 0, g: 0, b: 0, a: 0 })
        );
        // Com `&` de fechamento de override tag e forma curta BBGGRR.
        assert_eq!(
            parse_ass_color("&H0000FF&"),
            Some(AssColor { r: 255, g: 0, b: 0, a: 255 })
        );
        // SSA antigo: inteiro decimal (16711680 = 0x00FF0000 => azul).
        assert_eq!(
            parse_ass_color("16711680"),
            Some(AssColor { r: 0, g: 0, b: 255, a: 255 })
        );
        assert_eq!(parse_ass_color("lixo"), None);
    }

    #[test]
    fn test_parse_ass_script_info_and_styles() {
        let ass = parse_ass(SAMPLE);
        assert_eq!(ass.script_info.play_res_x, 1920);
        assert_eq!(ass.script_info.play_res_y, 1080);
        assert_eq!(ass.script_info.wrap_style, 0);
        assert!(ass.script_info.scaled_border_and_shadow);
        assert_eq!(ass.script_info.title, "Amostra");

        assert_eq!(ass.styles.len(), 2);
        let def = &ass.styles[0];
        assert_eq!(def.name, "Default");
        assert_eq!(def.font_name, "Arial");
        assert_eq!(def.font_size, 72.0);
        assert_eq!(def.primary_colour, AssColor::WHITE);
        assert_eq!(def.outline_colour, AssColor { r: 0x20, g: 0x20, b: 0x20, a: 255 });
        assert_eq!(def.border_style, 1);
        assert_eq!(def.outline, 3.0);
        assert_eq!(def.shadow, 1.0);
        assert_eq!(def.alignment, 2);
        assert_eq!(def.margin_v, 40);
        assert!(!def.bold);

        let titulo = &ass.styles[1];
        assert_eq!(titulo.font_name, "Times New Roman");
        assert!(titulo.bold);
        assert_eq!(titulo.alignment, 8);
        // &H0000FFFF => AA=00, BB=00, GG=FF, RR=FF => amarelo opaco.
        assert_eq!(titulo.primary_colour, AssColor { r: 255, g: 255, b: 0, a: 255 });
    }

    #[test]
    fn test_parse_ass_events_timing_and_linebreaks() {
        let ass = parse_ass(SAMPLE);
        assert_eq!(ass.events.len(), 3); // "Comment:" não conta

        assert_eq!(ass.events[0].start_ms, 1000);
        assert_eq!(ass.events[0].end_ms, 3500);
        assert_eq!(ass.events[0].text, "Primeira linha\nsegunda linha");
        assert_eq!(ass.events[0].alignment, 2); // herdado do estilo Default
    }

    #[test]
    fn test_parse_ass_override_pos_color_bold_reset() {
        let ass = parse_ass(SAMPLE);
        let ev = &ass.events[1];
        assert_eq!(ev.pos, Some((960.0, 540.0)));
        assert_eq!(ev.text, "Vermelho em negrito normal");
        assert!(!ev.text.contains('{'));
        assert!(!ev.text.contains('\\'));

        // Primeiro span: vermelho + negrito. Depois do \r: volta ao estilo base.
        assert!(ev.spans.len() >= 2);
        let red = &ev.spans[0];
        assert_eq!(red.colour, AssColor { r: 255, g: 0, b: 0, a: 255 });
        assert!(red.bold);
        assert_eq!(red.text, "Vermelho em negrito");

        let normal = ev.spans.last().unwrap();
        assert!(!normal.bold);
        assert_eq!(normal.colour, AssColor::WHITE);
        assert_eq!(normal.text, " normal");
    }

    #[test]
    fn test_parse_ass_out_of_scope_tags_are_stripped_not_leaked() {
        let ass = parse_ass(SAMPLE);
        let ev = &ass.events[2];
        // \an5 aplicado; \t / \move / \fscx removidos sem vazar literal.
        assert_eq!(ev.alignment, 5);
        assert_eq!(ev.text, "Com tags fora de escopo");
        assert!(!ev.text.contains('\\'));
        assert!(!ev.text.to_lowercase().contains("fscx"));
        assert!(!ev.text.to_lowercase().contains("move"));
        assert!(!ev.text.contains('{'));
    }

    #[test]
    fn test_drawing_mode_text_is_discarded() {
        let doc = format!(
            "{}\nDialogue: 0,0:00:10.00,0:00:12.00,Default,,0,0,0,,antes {{\\p1}}m 0 0 l 10 0 10 10{{\\p0}} depois\n",
            SAMPLE.trim_end()
        );
        let ass = parse_ass(&doc);
        let ev = ass.events.last().unwrap();
        assert_eq!(ev.text, "antes  depois");
    }

    #[test]
    fn test_to_subtitle_entries() {
        let entries = parse_ass_to_entries(SAMPLE);
        assert_eq!(entries.len(), 3);
        assert_eq!(entries[0].index, 1);
        assert_eq!(entries[0].start_ms, 1000);
        assert_eq!(entries[0].text, "Primeira linha\nsegunda linha");
        assert_eq!(entries[2].index, 3);
    }

    #[test]
    fn test_strip_ass_override_tags() {
        assert_eq!(
            strip_ass_override_tags("{\\pos(10,10)\\b1}Olá{\\b0} mundo"),
            "Olá mundo"
        );
        assert_eq!(strip_ass_override_tags("linha 1\\Nlinha 2"), "linha 1\nlinha 2");
    }

    #[test]
    fn test_legacy_ssa_v4_styles_alignment() {
        let ssa = r#"[Script Info]
ScriptType: v4.00

[V4 Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding
Style: Default,Arial,24,16777215,255,0,0,0,0,1,1,0,6,10,10,10,0,0

[Events]
Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: Marked=0,0:00:01.00,0:00:02.00,Default,,0,0,0,,legenda ssa
"#;
        let ass = parse_ass(ssa);
        // Legacy 6 (top-center) => numpad 8.
        assert_eq!(ass.styles[0].alignment, 8);
        assert_eq!(ass.events.len(), 1);
        assert_eq!(ass.events[0].text, "legenda ssa");
        assert_eq!(ass.events[0].start_ms, 1000);
    }

    #[test]
    fn test_ass_document_from_mkv_packets_roundtrip() {
        let header = "[Script Info]\nPlayResX: 1280\nPlayResY: 720\n\n\
                      [V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n\
                      Style: Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,20,1\n\n\
                      [Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text";
        let packets = vec![
            MkvAssPacket {
                start_ms: 2000,
                end_ms: 4000,
                body: "0,0,Default,,0,0,0,,Olá mundo".to_string(),
            },
            MkvAssPacket {
                start_ms: 4500,
                end_ms: 6250,
                body: "1,0,Default,Alice,0,0,0,,Frase, com vírgula".to_string(),
            },
        ];
        let doc = ass_document_from_mkv_packets(header, &packets);
        let ass = parse_ass(&doc);

        assert_eq!(ass.script_info.play_res_x, 1280);
        assert_eq!(ass.events.len(), 2);
        assert_eq!(ass.events[0].start_ms, 2000);
        assert_eq!(ass.events[0].end_ms, 4000);
        assert_eq!(ass.events[0].text, "Olá mundo");
        assert_eq!(ass.events[1].start_ms, 4500);
        assert_eq!(ass.events[1].text, "Frase, com vírgula");
        assert_eq!(ass.events[1].actor, "Alice");
    }

    #[test]
    fn test_missing_format_lines_use_defaults() {
        let ass = parse_ass(
            "[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,sem format line\n",
        );
        assert_eq!(ass.events.len(), 1);
        assert_eq!(ass.events[0].text, "sem format line");
        // Sem [V4+ Styles] => um estilo Default sintético.
        assert_eq!(ass.styles.len(), 1);
        assert_eq!(ass.styles[0].name, "Default");
    }
}
