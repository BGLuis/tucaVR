// subtitle_pgs.rs
//
// Parser PGS / HDMV Presentation Graphic Stream — T7.3 da Fase 0.3, Seção 7.
//
// PGS são legendas *bitmap* de Blu-ray. Chegam de duas formas:
//   - arquivo sidecar `.sup`: cada segmento é precedido do magic "PG" +
//     PTS/DTS de 90 kHz  → `parse_pgs_sup`;
//   - stream `AV_CODEC_ID_HDMV_PGS_SUBTITLE` dentro de um container: o demuxer
//     entrega os segmentos crus (a partir do byte de tipo), com o tempo vindo
//     do PTS do pacote → `parse_pgs_packets`.
//
// 100% host-testável: `cargo test -p media-logic subtitle_pgs`.
//
// O que é feito aqui:
//   - split dos segmentos PCS / WDS / PDS / ODS / END;
//   - remontagem de objetos ODS fragmentados;
//   - descompressão RLE do bitmap indexado;
//   - conversão paleta YCrCb + alfa  →  RGBA de alfa direto (BT.601);
//   - composição dos objetos de um display set num único bitmap recortado
//     (bounding box) com posição em coordenadas de tela (1920×1080 no Blu-ray);
//   - resolução de timing: um display set com objetos abre uma legenda; o
//     display set vazio seguinte a fecha.
//
// O renderizador C++ (T7.4) recebe `PgsSubtitle` e só precisa: subir `rgba`
// como textura, posicionar o quad em `(x, y)` proporcional a `(screen_width,
// screen_height)` e escalar. Nada de fonte/atlas — é o segundo caminho de
// textura citado no relatório.

use std::collections::HashMap;

// ============================================================================
// Tipos públicos
// ============================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PgsSegmentKind {
    /// PDS — Palette Definition Segment (0x14).
    Palette,
    /// ODS — Object Definition Segment (0x15).
    Object,
    /// PCS — Presentation Composition Segment (0x16).
    Presentation,
    /// WDS — Window Definition Segment (0x17).
    Window,
    /// END — End of Display Set (0x80).
    End,
}

impl PgsSegmentKind {
    fn from_byte(b: u8) -> Option<Self> {
        match b {
            0x14 => Some(Self::Palette),
            0x15 => Some(Self::Object),
            0x16 => Some(Self::Presentation),
            0x17 => Some(Self::Window),
            0x80 => Some(Self::End),
            _ => None,
        }
    }
}

/// Um segmento cru já enquadrado, com o instante do display set a que pertence.
#[derive(Debug, Clone)]
pub struct PgsSegment {
    pub pts_ms: u64,
    pub kind: PgsSegmentKind,
    pub payload: Vec<u8>,
}

/// Pacote de legenda PGS entregue por um demuxer de container: `data` são um ou
/// mais segmentos crus (começando no byte de tipo, sem magic "PG" nem PTS).
#[derive(Debug, Clone)]
pub struct PgsPacket {
    pub pts_ms: u64,
    pub data: Vec<u8>,
}

/// Legenda PGS pronta para virar textura.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PgsSubtitle {
    pub start_ms: u64,
    /// `u64::MAX` significa "até o próximo display set" quando não houve um
    /// display set vazio para fechá-la (arquivo truncado, por ex.).
    pub end_ms: u64,
    /// Resolução de referência declarada no PCS (1920×1080 no Blu-ray).
    pub screen_width: u16,
    pub screen_height: u16,
    /// Bounding box da composição, em coordenadas da resolução de referência.
    pub x: u16,
    pub y: u16,
    pub width: u16,
    pub height: u16,
    /// `width * height * 4`, RGBA de alfa direto (não pré-multiplicado).
    pub rgba: Vec<u8>,
}

// ============================================================================
// Enquadramento de segmentos
// ============================================================================

/// Faz o split de um arquivo `.sup` (framing com magic "PG" + PTS/DTS de
/// 90 kHz). Segmentos com tipo desconhecido são descartados; lixo entre
/// segmentos é resincronizado byte a byte.
pub fn parse_pgs_sup_segments(data: &[u8]) -> Vec<PgsSegment> {
    const HEADER: usize = 13; // "PG"(2) + PTS(4) + DTS(4) + type(1) + size(2)
    let mut out = Vec::new();
    let mut i = 0usize;

    while i + HEADER <= data.len() {
        if &data[i..i + 2] != b"PG" {
            i += 1;
            continue;
        }
        let pts_90k = u32::from_be_bytes([data[i + 2], data[i + 3], data[i + 4], data[i + 5]]);
        let type_byte = data[i + 10];
        let size = u16::from_be_bytes([data[i + 11], data[i + 12]]) as usize;
        let start = i + HEADER;
        if start + size > data.len() {
            break;
        }
        if let Some(kind) = PgsSegmentKind::from_byte(type_byte) {
            out.push(PgsSegment {
                pts_ms: pts_90k as u64 / 90,
                kind,
                payload: data[start..start + size].to_vec(),
            });
        }
        i = start + size;
    }
    out
}

/// Faz o split de segmentos crus (sem magic): `type(1) + size(2) + payload`,
/// repetido. Usado para pacotes de container, onde o tempo vem de fora.
fn split_raw_segments(data: &[u8], pts_ms: u64, out: &mut Vec<PgsSegment>) {
    let mut i = 0usize;
    while i + 3 <= data.len() {
        let type_byte = data[i];
        let size = u16::from_be_bytes([data[i + 1], data[i + 2]]) as usize;
        let start = i + 3;
        if start + size > data.len() {
            break;
        }
        if let Some(kind) = PgsSegmentKind::from_byte(type_byte) {
            out.push(PgsSegment {
                pts_ms,
                kind,
                payload: data[start..start + size].to_vec(),
            });
        }
        i = start + size;
    }
}

// ============================================================================
// Entrypoints
// ============================================================================

/// Parseia um arquivo `.sup` completo em legendas prontas.
pub fn parse_pgs_sup(data: &[u8]) -> Vec<PgsSubtitle> {
    compose(&parse_pgs_sup_segments(data))
}

/// Parseia uma sequência de pacotes PGS de container em legendas prontas.
pub fn parse_pgs_packets(packets: &[PgsPacket]) -> Vec<PgsSubtitle> {
    let mut segments = Vec::new();
    for p in packets {
        split_raw_segments(&p.data, p.pts_ms, &mut segments);
    }
    compose(&segments)
}

/// Busca a legenda PGS ativa no instante `pts_ms` (com `offset_ms` aplicado).
/// Espelha o contrato de `subtitle::find_active_cue`.
pub fn find_active_pgs(subs: &[PgsSubtitle], pts_ms: i64, offset_ms: i64) -> Option<&PgsSubtitle> {
    let effective = pts_ms.checked_add(offset_ms)?;
    if effective < 0 {
        return None;
    }
    let target = effective as u64;
    subs.iter()
        .rev()
        .find(|s| target >= s.start_ms && target < s.end_ms)
}

// ============================================================================
// Descompressão RLE  (formato PGS/HDMV, por linha)
// ============================================================================

/// Descomprime o RLE de um objeto PGS num buffer `width * height` de índices de
/// paleta. Tolerante a truncamento: pixels faltantes ficam 0 (transparente).
///
/// Regras (b0 = byte lido):
///   b0 != 0                      → 1 pixel de cor b0
///   b0 == 0, b1 == 0             → fim de linha
///   b0 == 0, b1 & 0xC0 == 0x00   → b1 & 0x3F pixels de cor 0
///   b0 == 0, b1 & 0xC0 == 0x40   → ((b1 & 0x3F) << 8 | b2) pixels de cor 0
///   b0 == 0, b1 & 0xC0 == 0x80   → b1 & 0x3F pixels de cor b2
///   b0 == 0, b1 & 0xC0 == 0xC0   → ((b1 & 0x3F) << 8 | b2) pixels de cor b3
pub fn rle_decode(data: &[u8], width: u16, height: u16) -> Vec<u8> {
    let w = width as usize;
    let h = height as usize;
    let mut out = vec![0u8; w.saturating_mul(h)];
    if w == 0 || h == 0 {
        return out;
    }

    let mut x = 0usize;
    let mut y = 0usize;
    let mut i = 0usize;

    let put = |out: &mut [u8], x: usize, y: usize, run: usize, colour: u8| {
        if y >= h {
            return;
        }
        let end = (x + run).min(w);
        if x < end {
            out[y * w + x..y * w + end].fill(colour);
        }
    };

    while i < data.len() && y < h {
        let b0 = data[i];
        i += 1;

        if b0 != 0 {
            put(&mut out, x, y, 1, b0);
            x += 1;
            continue;
        }

        let Some(&b1) = data.get(i) else { break };
        i += 1;

        if b1 == 0 {
            x = 0;
            y += 1;
            continue;
        }

        let (run, colour) = match b1 & 0xC0 {
            0x00 => ((b1 & 0x3F) as usize, 0u8),
            0x40 => {
                let Some(&b2) = data.get(i) else { break };
                i += 1;
                ((((b1 & 0x3F) as usize) << 8) | b2 as usize, 0u8)
            }
            0x80 => {
                let Some(&b2) = data.get(i) else { break };
                i += 1;
                ((b1 & 0x3F) as usize, b2)
            }
            _ => {
                let (Some(&b2), Some(&b3)) = (data.get(i), data.get(i + 1)) else {
                    break;
                };
                i += 2;
                ((((b1 & 0x3F) as usize) << 8) | b2 as usize, b3)
            }
        };

        put(&mut out, x, y, run, colour);
        x += run;
    }

    out
}

/// Conversão YCrCb → RGB no espaço BT.601 full-range (a convenção usada pelos
/// muxers de PGS na prática).
pub fn ycrcb_to_rgb(y: u8, cr: u8, cb: u8) -> (u8, u8, u8) {
    let yf = y as f32;
    let crf = cr as f32 - 128.0;
    let cbf = cb as f32 - 128.0;
    let r = yf + 1.402 * crf;
    let g = yf - 0.344_136 * cbf - 0.714_136 * crf;
    let b = yf + 1.772 * cbf;
    (clamp8(r), clamp8(g), clamp8(b))
}

fn clamp8(v: f32) -> u8 {
    v.round().clamp(0.0, 255.0) as u8
}

// ============================================================================
// Composição
// ============================================================================

#[derive(Clone)]
struct DecodedObject {
    width: u16,
    height: u16,
    indices: Vec<u8>, // width * height
}

struct ObjAccum {
    width: u16,
    height: u16,
    expected: usize, // bytes de RLE esperados
    data: Vec<u8>,
}

#[derive(Clone)]
struct CompObject {
    object_id: u16,
    x: u16,
    y: u16,
}

struct PendingPcs {
    pts_ms: u64,
    width: u16,
    height: u16,
    palette_id: u8,
    objects: Vec<CompObject>,
}

fn compose(segments: &[PgsSegment]) -> Vec<PgsSubtitle> {
    let mut palettes: HashMap<u8, Vec<[u8; 4]>> = HashMap::new();
    let mut objects: HashMap<u16, DecodedObject> = HashMap::new();
    let mut accum: HashMap<u16, ObjAccum> = HashMap::new();
    let mut pending: Option<PendingPcs> = None;
    let mut open_idx: Option<usize> = None;
    let mut out: Vec<PgsSubtitle> = Vec::new();

    for seg in segments {
        match seg.kind {
            PgsSegmentKind::Presentation => {
                pending = parse_pcs(&seg.payload, seg.pts_ms);
            }
            PgsSegmentKind::Palette => {
                if let Some((id, table)) = parse_pds(&seg.payload) {
                    palettes.insert(id, table);
                }
            }
            PgsSegmentKind::Window => { /* geometria de janela: não usada para o recorte final */ }
            PgsSegmentKind::Object => {
                parse_ods(&seg.payload, &mut accum, &mut objects);
            }
            PgsSegmentKind::End => {
                let Some(pcs) = pending.take() else { continue };

                // Qualquer legenda ainda aberta termina aqui.
                if let Some(idx) = open_idx.take() {
                    if out[idx].end_ms == u64::MAX {
                        out[idx].end_ms = pcs.pts_ms.max(out[idx].start_ms);
                    }
                }

                if pcs.objects.is_empty() {
                    continue; // display set vazio: só fecha o anterior
                }

                let palette = palettes.get(&pcs.palette_id);
                if let Some(sub) = composite(&pcs, &objects, palette) {
                    out.push(sub);
                    open_idx = Some(out.len() - 1);
                }
            }
        }
    }

    // Fecha legendas que ficaram abertas: fim = início da próxima.
    let starts: Vec<u64> = out.iter().map(|s| s.start_ms).collect();
    for (i, s) in out.iter_mut().enumerate() {
        if s.end_ms == u64::MAX {
            if let Some(&next) = starts.get(i + 1) {
                s.end_ms = next.max(s.start_ms);
            }
        }
    }

    out
}

fn composite(
    pcs: &PendingPcs,
    objects: &HashMap<u16, DecodedObject>,
    palette: Option<&Vec<[u8; 4]>>,
) -> Option<PgsSubtitle> {
    // Bounding box de todos os objetos compostos.
    let mut min_x = u32::MAX;
    let mut min_y = u32::MAX;
    let mut max_x = 0u32;
    let mut max_y = 0u32;
    let mut any = false;

    for co in &pcs.objects {
        let Some(obj) = objects.get(&co.object_id) else {
            continue;
        };
        any = true;
        min_x = min_x.min(co.x as u32);
        min_y = min_y.min(co.y as u32);
        max_x = max_x.max(co.x as u32 + obj.width as u32);
        max_y = max_y.max(co.y as u32 + obj.height as u32);
    }
    if !any {
        return None;
    }

    let bbox_w = (max_x - min_x) as usize;
    let bbox_h = (max_y - min_y) as usize;
    if bbox_w == 0 || bbox_h == 0 {
        return None;
    }

    let mut rgba = vec![0u8; bbox_w * bbox_h * 4];

    for co in &pcs.objects {
        let Some(obj) = objects.get(&co.object_id) else {
            continue;
        };
        let ox = co.x as usize - min_x as usize;
        let oy = co.y as usize - min_y as usize;
        let ow = obj.width as usize;
        let oh = obj.height as usize;

        for row in 0..oh {
            for col in 0..ow {
                let idx = obj.indices[row * ow + col];
                let [r, g, b, a] = palette
                    .and_then(|p| p.get(idx as usize).copied())
                    .unwrap_or([0, 0, 0, 0]);
                if a == 0 {
                    continue;
                }
                let dx = ox + col;
                let dy = oy + row;
                if dx >= bbox_w || dy >= bbox_h {
                    continue;
                }
                let o = (dy * bbox_w + dx) * 4;
                rgba[o] = r;
                rgba[o + 1] = g;
                rgba[o + 2] = b;
                rgba[o + 3] = a;
            }
        }
    }

    Some(PgsSubtitle {
        start_ms: pcs.pts_ms,
        end_ms: u64::MAX,
        screen_width: pcs.width,
        screen_height: pcs.height,
        x: min_x as u16,
        y: min_y as u16,
        width: bbox_w as u16,
        height: bbox_h as u16,
        rgba,
    })
}

// ============================================================================
// Parse dos payloads individuais
// ============================================================================

fn be16(b: &[u8], i: usize) -> Option<u16> {
    Some(u16::from_be_bytes([*b.get(i)?, *b.get(i + 1)?]))
}

fn parse_pcs(p: &[u8], pts_ms: u64) -> Option<PendingPcs> {
    if p.len() < 11 {
        return None;
    }
    let width = be16(p, 0)?;
    let height = be16(p, 2)?;
    // p[4] frame_rate, p[5..7] composition_number, p[7] composition_state,
    // p[8] palette_update_flag
    let palette_id = p[9];
    let n_objects = p[10] as usize;

    let mut objects = Vec::with_capacity(n_objects);
    let mut i = 11usize;
    for _ in 0..n_objects {
        if i + 8 > p.len() {
            break;
        }
        let object_id = be16(p, i)?;
        // p[i+2] window_id
        let flags = p[i + 3];
        let x = be16(p, i + 4)?;
        let y = be16(p, i + 6)?;
        i += 8;
        let cropped = flags & 0x80 != 0;
        if cropped {
            i += 8; // crop_x, crop_y, crop_w, crop_h — recorte não aplicado na v0.3
        }
        objects.push(CompObject { object_id, x, y });
    }

    Some(PendingPcs {
        pts_ms,
        width,
        height,
        palette_id,
        objects,
    })
}

fn parse_pds(p: &[u8]) -> Option<(u8, Vec<[u8; 4]>)> {
    if p.len() < 2 {
        return None;
    }
    let palette_id = p[0];
    // p[1] palette_version
    let mut table = vec![[0u8, 0, 0, 0]; 256];
    let mut i = 2usize;
    while i + 5 <= p.len() {
        let entry = p[i] as usize;
        let y = p[i + 1];
        let cr = p[i + 2];
        let cb = p[i + 3];
        let alpha = p[i + 4];
        let (r, g, b) = ycrcb_to_rgb(y, cr, cb);
        table[entry] = [r, g, b, alpha];
        i += 5;
    }
    Some((palette_id, table))
}

fn parse_ods(
    p: &[u8],
    accum: &mut HashMap<u16, ObjAccum>,
    objects: &mut HashMap<u16, DecodedObject>,
) {
    if p.len() < 4 {
        return;
    }
    let object_id = match be16(p, 0) {
        Some(v) => v,
        None => return,
    };
    // p[2] object_version
    let seq_flag = p[3];
    let first = seq_flag & 0x80 != 0;
    let last = seq_flag & 0x40 != 0;

    if first {
        if p.len() < 11 {
            return;
        }
        // object_data_length: u24 be em p[4..7], inclui os 4 bytes de width+height.
        let data_len =
            ((p[4] as usize) << 16) | ((p[5] as usize) << 8) | (p[6] as usize);
        let width = match be16(p, 7) {
            Some(v) => v,
            None => return,
        };
        let height = match be16(p, 9) {
            Some(v) => v,
            None => return,
        };
        let expected = data_len.saturating_sub(4);
        let mut data = Vec::with_capacity(expected);
        data.extend_from_slice(&p[11..]);
        accum.insert(
            object_id,
            ObjAccum {
                width,
                height,
                expected,
                data,
            },
        );
    } else if let Some(acc) = accum.get_mut(&object_id) {
        acc.data.extend_from_slice(&p[4..]);
    }

    if last {
        if let Some(acc) = accum.remove(&object_id) {
            let indices = rle_decode(&acc.data, acc.width, acc.height);
            objects.insert(
                object_id,
                DecodedObject {
                    width: acc.width,
                    height: acc.height,
                    indices,
                },
            );
            let _ = acc.expected;
        }
    }
}

// ============================================================================
// Testes
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn sup_seg(pts_ms: u64, type_byte: u8, payload: &[u8]) -> Vec<u8> {
        let pts_90k = (pts_ms * 90) as u32;
        let mut v = Vec::new();
        v.extend_from_slice(b"PG");
        v.extend_from_slice(&pts_90k.to_be_bytes());
        v.extend_from_slice(&0u32.to_be_bytes()); // DTS
        v.push(type_byte);
        v.extend_from_slice(&(payload.len() as u16).to_be_bytes());
        v.extend_from_slice(payload);
        v
    }

    #[test]
    fn test_rle_decode_basic_runs() {
        // 2×2:  linha 0 = [cor1, cor1],  linha 1 = [cor0, cor0]
        let rle = [
            0x01, 0x01, 0x00, 0x00, // px1, px1, fim de linha
            0x00, 0x02, 0x00, 0x00, // run de 2 de cor 0, fim de linha
        ];
        assert_eq!(rle_decode(&rle, 2, 2), vec![1, 1, 0, 0]);
    }

    #[test]
    fn test_rle_decode_long_run_and_colour_run() {
        // 300×1: run longo (>63) de cor 7 usando o caso 0xC0.
        // 300 = 0x12C  → hi = 0x01, lo = 0x2C
        let rle = [0x00, 0xC0 | 0x01, 0x2C, 0x07, 0x00, 0x00];
        let decoded = rle_decode(&rle, 300, 1);
        assert_eq!(decoded.len(), 300);
        assert!(decoded.iter().all(|&c| c == 7));
    }

    #[test]
    fn test_ycrcb_to_rgb_neutral_and_white() {
        assert_eq!(ycrcb_to_rgb(255, 128, 128), (255, 255, 255));
        assert_eq!(ycrcb_to_rgb(0, 128, 128), (0, 0, 0));
        // Vermelho puro em BT.601: Y≈81, Cb≈90, Cr≈240.
        let (r, g, b) = ycrcb_to_rgb(81, 240, 90);
        assert!(r > 230 && g < 20 && b < 20, "got ({r},{g},{b})");
    }

    #[test]
    fn test_parse_pgs_sup_full_display_set() {
        // ---- Display set 1 @ 1000 ms: PCS + WDS + PDS + ODS + END ----
        #[rustfmt::skip]
        let pcs = [
            0x00, 0x02, 0x00, 0x02, // width=2, height=2
            0x10,                   // frame_rate
            0x00, 0x00,             // composition_number
            0x80,                   // composition_state = epoch start
            0x00,                   // palette_update_flag
            0x00,                   // palette_id
            0x01,                   // number_of_composition_objects
            // objeto:
            0x00, 0x00,             // object_id
            0x00,                   // window_id
            0x00,                   // flags (não recortado)
            0x00, 0x00,             // x
            0x00, 0x00,             // y
        ];
        #[rustfmt::skip]
        let wds = [
            0x01,                   // number_of_windows
            0x00,                   // window_id
            0x00, 0x00, 0x00, 0x00, // x, y
            0x00, 0x02, 0x00, 0x02, // width, height
        ];
        #[rustfmt::skip]
        let pds = [
            0x00, 0x00,             // palette_id, version
            0x00, 0x00, 0x80, 0x80, 0x00, // entrada 0: Y=0  Cr=128 Cb=128 alpha=0
            0x01, 0xFF, 0x80, 0x80, 0xFF, // entrada 1: Y=255 Cr=128 Cb=128 alpha=255
        ];
        let rle = [
            0x01, 0x01, 0x00, 0x00, // linha 0: cor1, cor1
            0x00, 0x02, 0x00, 0x00, // linha 1: run de 2 de cor 0
        ];
        let mut ods = vec![
            0x00, 0x00, // object_id
            0x00,       // version
            0xC0,       // seq_flag = first | last
        ];
        let data_len = (4 + rle.len()) as u32;
        ods.push((data_len >> 16) as u8);
        ods.push((data_len >> 8) as u8);
        ods.push(data_len as u8);
        ods.extend_from_slice(&[0x00, 0x02, 0x00, 0x02]); // width, height
        ods.extend_from_slice(&rle);

        // ---- Display set 2 @ 5000 ms: PCS vazio + END (fecha a legenda) ----
        #[rustfmt::skip]
        let pcs_empty = [
            0x00, 0x02, 0x00, 0x02,
            0x10,
            0x00, 0x01,
            0x00,
            0x00,
            0x00,
            0x00, // number_of_composition_objects = 0
        ];

        let mut file = Vec::new();
        file.extend(sup_seg(1000, 0x16, &pcs));
        file.extend(sup_seg(1000, 0x17, &wds));
        file.extend(sup_seg(1000, 0x14, &pds));
        file.extend(sup_seg(1000, 0x15, &ods));
        file.extend(sup_seg(1000, 0x80, &[]));
        file.extend(sup_seg(5000, 0x16, &pcs_empty));
        file.extend(sup_seg(5000, 0x80, &[]));

        let segs = parse_pgs_sup_segments(&file);
        assert_eq!(segs.len(), 7);

        let subs = parse_pgs_sup(&file);
        assert_eq!(subs.len(), 1);
        let s = &subs[0];
        assert_eq!(s.start_ms, 1000);
        assert_eq!(s.end_ms, 5000);
        assert_eq!((s.screen_width, s.screen_height), (2, 2));
        assert_eq!((s.x, s.y, s.width, s.height), (0, 0, 2, 2));
        assert_eq!(
            s.rgba,
            vec![
                255, 255, 255, 255, // (0,0)
                255, 255, 255, 255, // (1,0)
                0, 0, 0, 0, // (0,1)
                0, 0, 0, 0, // (1,1)
            ]
        );
    }

    #[test]
    fn test_find_active_pgs() {
        let subs = vec![
            PgsSubtitle {
                start_ms: 1000,
                end_ms: 3000,
                screen_width: 1920,
                screen_height: 1080,
                x: 0,
                y: 900,
                width: 4,
                height: 4,
                rgba: vec![0; 64],
            },
            PgsSubtitle {
                start_ms: 5000,
                end_ms: 8000,
                screen_width: 1920,
                screen_height: 1080,
                x: 0,
                y: 900,
                width: 4,
                height: 4,
                rgba: vec![0; 64],
            },
        ];
        assert!(find_active_pgs(&subs, 500, 0).is_none());
        assert_eq!(find_active_pgs(&subs, 1500, 0).map(|s| s.start_ms), Some(1000));
        assert!(find_active_pgs(&subs, 3000, 0).is_none()); // fim exclusivo
        assert!(find_active_pgs(&subs, 4000, 0).is_none());
        assert_eq!(find_active_pgs(&subs, 6000, 0).map(|s| s.start_ms), Some(5000));
        // offset -1000: pts 6000 -> 5000 cai na segunda
        assert_eq!(find_active_pgs(&subs, 6000, -1000).map(|s| s.start_ms), Some(5000));
    }

    #[test]
    fn test_parse_pgs_packets_raw_framing() {
        // Mesmo conteúdo, mas sem magic "PG": só type + size + payload.
        fn raw_seg(type_byte: u8, payload: &[u8]) -> Vec<u8> {
            let mut v = vec![type_byte];
            v.extend_from_slice(&(payload.len() as u16).to_be_bytes());
            v.extend_from_slice(payload);
            v
        }
        #[rustfmt::skip]
        let pcs = [
            0x00, 0x01, 0x00, 0x01, 0x10, 0x00, 0x00, 0x80, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        ];
        let pds = [0x00, 0x00, 0x01, 0xFF, 0x80, 0x80, 0xFF];
        let rle = [0x01, 0x00, 0x00]; // 1×1: um pixel de cor 1
        let mut ods = vec![0x00, 0x00, 0x00, 0xC0];
        let dl = (4 + rle.len()) as u32;
        ods.extend_from_slice(&[(dl >> 16) as u8, (dl >> 8) as u8, dl as u8]);
        ods.extend_from_slice(&[0x00, 0x01, 0x00, 0x01]);
        ods.extend_from_slice(&rle);
        let pcs_empty = [
            0x00u8, 0x01, 0x00, 0x01, 0x10, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
        ];

        let mut d1 = Vec::new();
        d1.extend(raw_seg(0x16, &pcs));
        d1.extend(raw_seg(0x14, &pds));
        d1.extend(raw_seg(0x15, &ods));
        d1.extend(raw_seg(0x80, &[]));

        let mut d2 = Vec::new();
        d2.extend(raw_seg(0x16, &pcs_empty));
        d2.extend(raw_seg(0x80, &[]));

        let subs = parse_pgs_packets(&[
            PgsPacket { pts_ms: 2000, data: d1 },
            PgsPacket { pts_ms: 9000, data: d2 },
        ]);
        assert_eq!(subs.len(), 1);
        assert_eq!(subs[0].start_ms, 2000);
        assert_eq!(subs[0].end_ms, 9000);
        assert_eq!((subs[0].width, subs[0].height), (1, 1));
        assert_eq!(subs[0].rgba, vec![255, 255, 255, 255]);
    }

    #[test]
    fn test_truncated_sup_does_not_panic() {
        let subs = parse_pgs_sup(&[0x50, 0x47, 0x00, 0x01]);
        assert!(subs.is_empty());
        let subs = parse_pgs_sup(b"not a pgs file at all");
        assert!(subs.is_empty());
    }
}
