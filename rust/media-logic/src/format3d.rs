//! Auto-detecção de formato 3D — PHASE-0.2-3D-NETWORK.md, Section 3
//! (T3.1-T3.4).
//!
//! Three independent detection signals, in decreasing order of trust:
//!
//! 1. **Container metadata** (T3.1) — MP4 `st3d`/`sv3d` boxes, MKV
//!    `StereoMode`/`Projection` elements. Parsing the actual boxes needs
//!    `core`'s FFmpeg bindings, which (per the crate-level docs in `lib.rs`)
//!    cannot be compiled on a normal host. That parsing therefore lives in
//!    `core`, not here — this module only defines the metadata *shapes*
//!    (`VideoStereoMode`, `VideoProjection`) so `core` and this crate agree
//!    on vocabulary, and accepts the already-decoded result as a plain
//!    `Option<Format3D>` in [`detect`].
//! 2. **Filename pattern** (T3.2) — [`detect_from_filename`].
//! 3. **Resolution heuristic** (T3.3) — [`detect_from_resolution`], last
//!    resort, lowest confidence.
//!
//! [`detect`] composes all three plus a safe fallback (T3.4's confirmation
//! UI needs to know how confident we are, hence [`DetectionConfidence`]).
//!
//! > [!CAUTION] (verbatim concern from the phase doc's Section 3 callout)
//! > Auto-detection is never 100% reliable. Many SBS/OU videos carry no
//! > metadata at all and filenames vary wildly. The worst acceptable
//! > failure mode is playing a 360°/SBS video as flat 2D — confusing, but
//! > harmless. Guessing a *stereo or spherical* mode with zero signal is
//! > the failure we must never make (near-instant motion sickness if wrong
//! > eye order and disorientation if a flat quad is force-wrapped onto a
//! > sphere the user didn't ask for). That's why [`detect`] only ever falls
//! > back to [`Format3D::Flat2D`] + [`DetectionConfidence::Default`], never
//! > a stereo/spherical guess, once metadata/filename/resolution all come
//! > up empty.

/// Stereoscopic packing as reported by container metadata (MP4 `st3d`, MKV
/// `StereoMode`). Mirrors the T3.1 code sample in
/// PHASE-0.2-3D-NETWORK.md verbatim — kept as its own enum (rather than
/// folded straight into [`Format3D`]) because this is the vocabulary the
/// raw box/element parser in `core` will produce, before it's known
/// whether the projection is flat, 180°, or 360°.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum VideoStereoMode {
    /// 2D — no stereo packing.
    Mono,
    /// SBS, left eye first (left half of the frame).
    SideBySideLeft,
    /// SBS, right eye first (left half of the frame is the right eye).
    SideBySideRight,
    /// OU/Top-Bottom, left eye on top.
    TopBottomLeft,
    /// OU/Top-Bottom, right eye on top.
    TopBottomRight,
}

/// Frame projection as reported by container metadata (MP4 `sv3d`, MKV/WebM
/// `Projection`). Mirrors the T3.1 code sample in
/// PHASE-0.2-3D-NETWORK.md verbatim.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum VideoProjection {
    /// 2D flat / standard rectilinear video.
    Rectangular,
    /// Full 360° equirectangular (2:1 aspect sphere mapping).
    Equirectangular,
    /// Cubemap projection.
    Cubemap,
    /// Equiangular cubemap (EAC) — Google's cubemap variant, common in
    /// YouTube 360° uploads.
    EquiangularCubemap,
    /// Half-equirectangular — 180° VR (front hemisphere only).
    HalfEquirectangular,
    /// Fisheye 190° — lentes circulares olho de peixe com FOV de 190°.
    Fisheye190,
    /// Tiled equirectangular com coordenadas de delimitação em ponto fixo 0.32
    /// (FFmpeg `AV_SPHERICAL_EQUIRECTANGULAR_TILE`, comum em arquivos Google VR180 e Matroska).
    EquirectangularTile {
        bound_left: u32,
        bound_top: u32,
        bound_right: u32,
        bound_bottom: u32,
    },
}

/// Pose inicial da câmera/projeção esférica (yaw, pitch, roll em radianos).
///
/// Extraída de metadados de container (`AVSphericalMapping` / MP4 `prhd`).
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct ProjectionPose {
    /// Rotação em torno do eixo Y (up) em radianos.
    pub yaw: f32,
    /// Rotação em torno do eixo X (right) em radianos.
    pub pitch: f32,
    /// Rotação em torno do eixo Z (forward) em radianos.
    pub roll: f32,
}

impl ProjectionPose {
    /// Converte valores de graus em ponto fixo 16.16 (padrão exportado pelo FFmpeg) para radianos.
    pub fn from_ffmpeg_fixed(yaw_fixed: i32, pitch_fixed: i32, roll_fixed: i32) -> Self {
        const SCALE: f32 = 65536.0;
        let yaw_deg = yaw_fixed as f32 / SCALE;
        let pitch_deg = pitch_fixed as f32 / SCALE;
        let roll_deg = roll_fixed as f32 / SCALE;
        Self {
            yaw: yaw_deg.to_radians(),
            pitch: pitch_deg.to_radians(),
            roll: roll_deg.to_radians(),
        }
    }

    /// Retorna `true` se ao menos um dos ângulos for significativamente diferente de zero.
    pub fn is_nonzero(&self) -> bool {
        self.yaw.abs() > 1e-5 || self.pitch.abs() > 1e-5 || self.roll.abs() > 1e-5
    }
}

/// Mapeia o valor de enum `AVStereo3DType` e flags (`AV_STEREO3D_FLAG_INVERT = 1`) do FFmpeg
/// para [`VideoStereoMode`].
pub fn map_av_stereo3d(type_val: i32, flags: i32) -> Option<VideoStereoMode> {
    const AV_STEREO3D_FLAG_INVERT: i32 = 1 << 0;
    let invert = (flags & AV_STEREO3D_FLAG_INVERT) != 0;
    match type_val {
        0 => Some(VideoStereoMode::Mono),
        1 => Some(if invert {
            VideoStereoMode::SideBySideRight
        } else {
            VideoStereoMode::SideBySideLeft
        }),
        2 => Some(if invert {
            VideoStereoMode::TopBottomRight
        } else {
            VideoStereoMode::TopBottomLeft
        }),
        _ => None,
    }
}

/// Mapeia o valor de enum `AVSphericalProjection` e bounds do FFmpeg para [`VideoProjection`].
pub fn map_av_spherical_projection(
    projection_val: i32,
    bound_left: u32,
    bound_top: u32,
    bound_right: u32,
    bound_bottom: u32,
) -> Option<VideoProjection> {
    match projection_val {
        0 => Some(VideoProjection::Equirectangular),
        1 => Some(VideoProjection::Cubemap),
        2 => Some(VideoProjection::EquirectangularTile {
            bound_left,
            bound_top,
            bound_right,
            bound_bottom,
        }),
        3 => Some(VideoProjection::HalfEquirectangular),
        4 => Some(VideoProjection::Rectangular),
        5 => Some(VideoProjection::Fisheye190),
        _ => None,
    }
}

/// The format this crate/app actually drives UI and rendering off of. This
/// is deliberately richer than the doc's flat T3.2 placeholder enum
/// (`Format3D::SBS`/`HalfSBS`/`OverUnder`/...) because the native renderer
/// needs to pick geometry (flat quad vs. UV sphere vs. hemisphere) *and*
/// eye-separation shader (none vs. SBS split vs. OU split) from a single
/// value — see [`is_stereo`](Format3D::is_stereo) and
/// [`is_spherical`](Format3D::is_spherical).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Format3D {
    /// Standard flat 2D video. The only format with no stereo separation
    /// and no spherical geometry — also the universal safe fallback (see
    /// the module-level CAUTION note).
    Flat2D,
    /// Flat side-by-side, each eye at full source resolution (frame is 2x
    /// the width of a single eye).
    SbsFull,
    /// Flat side-by-side, each eye at half horizontal resolution (frame
    /// width == single-eye display width).
    SbsHalf,
    /// Flat over/under (top-bottom), each eye at full source resolution.
    OverUnderFull,
    /// Flat over/under (top-bottom), each eye at half vertical resolution.
    OverUnderHalf,
    /// 360° equirectangular, mono (same image for both eyes).
    Spherical360Mono,
    /// 360° equirectangular, stereo SBS, full resolution per eye.
    Spherical360SbsFull,
    /// 360° equirectangular, stereo SBS, half resolution per eye.
    Spherical360SbsHalf,
    /// 360° equirectangular, stereo OU, full resolution per eye.
    Spherical360OverUnderFull,
    /// 360° equirectangular, stereo OU, half resolution per eye.
    Spherical360OverUnderHalf,
    /// 180° VR (half-equirectangular / front hemisphere), mono.
    Vr180Mono,
    /// 180° VR (half-equirectangular / front hemisphere), stereo SBS. VR180
    /// is a de-facto SBS-only format in the wild (see the judgment call
    /// documented on [`detect_from_filename`]) — there is deliberately no
    /// `Vr180OverUnder*` variant.
    Vr180Sbs,
    /// Cubemap 3x2, mono (6 faces: Right, Left, Top, Bottom, Front, Back).
    Cubemap3x2Mono,
    /// Cubemap 6x1, mono (horizontal strip de 6 faces).
    Cubemap6x1Mono,
    /// Equi-Angular Cubemap (EAC) 3x2, mono (distribuição angular uniforme, YouTube VR).
    Eac3x2Mono,
    /// Cubemap 3x2, stereo SBS (12 faces, 3x2 por olho).
    Cubemap3x2Sbs,
    /// Equi-Angular Cubemap (EAC) 3x2, stereo SBS.
    Eac3x2Sbs,
    /// Fisheye 190° monoscópico (projeção circular f-theta).
    Fisheye190Mono,
    /// Fisheye 190° stereo SBS (lentes circulares lado a lado, Canon RF 5.2mm / VR190).
    Fisheye190Sbs,
}

impl Format3D {
    /// Whether the native renderer needs to run an eye-separation shader
    /// (SBS/OU UV split) for this format. Matched exhaustively — no `_ =>`
    /// wildcard — so that adding a new `Format3D` variant is a compile
    /// error here until someone decides which bucket it belongs in,
    /// instead of silently defaulting to mono rendering.
    pub fn is_stereo(&self) -> bool {
        match self {
            Format3D::Flat2D => false,
            Format3D::SbsFull => true,
            Format3D::SbsHalf => true,
            Format3D::OverUnderFull => true,
            Format3D::OverUnderHalf => true,
            Format3D::Spherical360Mono => false,
            Format3D::Spherical360SbsFull => true,
            Format3D::Spherical360SbsHalf => true,
            Format3D::Spherical360OverUnderFull => true,
            Format3D::Spherical360OverUnderHalf => true,
            Format3D::Vr180Mono => false,
            Format3D::Vr180Sbs => true,
            Format3D::Cubemap3x2Mono | Format3D::Cubemap6x1Mono | Format3D::Eac3x2Mono => false,
            Format3D::Cubemap3x2Sbs | Format3D::Eac3x2Sbs => true,
            Format3D::Fisheye190Mono => false,
            Format3D::Fisheye190Sbs => true,
        }
    }

    /// Whether the native renderer needs spherical/hemispherical geometry
    /// (UV sphere or half-sphere per T2.1/T2.3) instead of a flat quad.
    /// `Vr180Mono`/`Vr180Sbs` are spherical (hemisphere) even though
    /// `is_stereo()` is `false` for the mono case — projection geometry and
    /// stereo separation are independent axes. Matched exhaustively for the
    /// same reason as [`is_stereo`](Format3D::is_stereo).
    pub fn is_spherical(&self) -> bool {
        match self {
            Format3D::Flat2D => false,
            Format3D::SbsFull => false,
            Format3D::SbsHalf => false,
            Format3D::OverUnderFull => false,
            Format3D::OverUnderHalf => false,
            Format3D::Spherical360Mono => true,
            Format3D::Spherical360SbsFull => true,
            Format3D::Spherical360SbsHalf => true,
            Format3D::Spherical360OverUnderFull => true,
            Format3D::Spherical360OverUnderHalf => true,
            Format3D::Vr180Mono => true,
            Format3D::Vr180Sbs => true,
            Format3D::Cubemap3x2Mono
            | Format3D::Cubemap6x1Mono
            | Format3D::Eac3x2Mono
            | Format3D::Cubemap3x2Sbs
            | Format3D::Eac3x2Sbs
            | Format3D::Fisheye190Mono
            | Format3D::Fisheye190Sbs => true,
        }
    }

    /// Índice numérico do `enum class ScreenMode` nativo (0-16) — precisa
    /// ficar em sincronia com `native/include/screen_mode.h`,
    /// `rust/bridge/src/lib.rs` e `ScreenFormatCatalog.kt`
    /// (ver CLAUDE.md / GEMINI.md). Match exaustivo — adicionar uma
    /// variante nova em `Format3D` é erro de compilação aqui até decidir
    /// pra qual índice ela mapeia.
    pub fn to_screen_mode_index(self) -> u32 {
        match self {
            Format3D::Flat2D => 0,
            Format3D::SbsFull => 1,
            Format3D::SbsHalf => 2,
            Format3D::OverUnderFull => 3,
            Format3D::OverUnderHalf => 4,
            Format3D::Spherical360Mono => 5,
            Format3D::Vr180Mono => 6,
            Format3D::Spherical360SbsFull => 7,
            Format3D::Spherical360SbsHalf => 7,
            Format3D::Spherical360OverUnderFull => 8,
            Format3D::Spherical360OverUnderHalf => 8,
            Format3D::Vr180Sbs => 9,
            Format3D::Cubemap3x2Mono => 10,
            Format3D::Cubemap6x1Mono => 11,
            Format3D::Eac3x2Mono => 12,
            Format3D::Cubemap3x2Sbs => 13,
            Format3D::Eac3x2Sbs => 14,
            Format3D::Fisheye190Mono => 15,
            Format3D::Fisheye190Sbs => 16,
        }
    }

    /// Determina a região de corte do thumbnail para este formato estereoscópico.
    pub fn thumbnail_crop_region(&self) -> ThumbnailCropRegion {
        match self {
            Format3D::Flat2D
            | Format3D::Spherical360Mono
            | Format3D::Vr180Mono
            | Format3D::Cubemap3x2Mono
            | Format3D::Cubemap6x1Mono
            | Format3D::Eac3x2Mono
            | Format3D::Fisheye190Mono => ThumbnailCropRegion::None,
            Format3D::SbsFull
            | Format3D::SbsHalf
            | Format3D::Spherical360SbsFull
            | Format3D::Spherical360SbsHalf
            | Format3D::Vr180Sbs
            | Format3D::Cubemap3x2Sbs
            | Format3D::Eac3x2Sbs
            | Format3D::Fisheye190Sbs => ThumbnailCropRegion::LeftHalf,
            Format3D::OverUnderFull
            | Format3D::OverUnderHalf
            | Format3D::Spherical360OverUnderFull
            | Format3D::Spherical360OverUnderHalf => ThumbnailCropRegion::TopHalf,
        }
    }

    /// Calcula a largura e altura recortadas para o thumbnail a partir das dimensões do frame.
    /// Garante alinhamento par para manter a integridade de croma YUV subsampleado (ex: YUV420).
    pub fn thumbnail_dimensions(&self, src_w: u32, src_h: u32) -> ThumbnailDimensions {
        match self.thumbnail_crop_region() {
            ThumbnailCropRegion::None => ThumbnailDimensions {
                width: src_w,
                height: src_h,
            },
            ThumbnailCropRegion::LeftHalf => ThumbnailDimensions {
                width: (src_w / 2) & !1,
                height: src_h,
            },
            ThumbnailCropRegion::TopHalf => ThumbnailDimensions {
                width: src_w,
                height: (src_h / 2) & !1,
            },
        }
    }
}

/// Região de corte para thumbnail de um único olho em vídeos estereoscópicos.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ThumbnailCropRegion {
    None,
    LeftHalf,
    TopHalf,
}

/// Dimensões pós-recorte para geração de thumbnail.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ThumbnailDimensions {
    pub width: u32,
    pub height: u32,
}

// SBS: aspecto de frame full (dois olhos completos lado a lado) e cerca do
// dobro do de um frame normal; half (olhos espremidos pra caber num frame
// normal) fica proximo do aspecto normal. OU: o inverso (full ~metade,
// half ~normal, pela pilha vertical dobrar a altura). Limiares generosos
// (nao os valores exatos 32:9/4:1 de detect_from_resolution) porque aqui a
// decisao "e 3D" ja veio confirmada pelos metadados do container — s
// escolhendo entre full/half, nunca inventando stereo do nada, entao um
// limiar largo e seguro. Documentado como aproximacao, nao garantia.
fn is_full_packing(is_sbs: bool, aspect: f64) -> bool {
    if is_sbs {
        aspect >= 2.4
    } else {
        aspect <= 1.2
    }
}

/// T3.1: resolve o `Format3D` final a partir do que os metadados do
/// container JÁ CONFIRMARAM (`stereo`/`projection`, produzidos por
/// `core` a partir de tags MKV/side data MP4 — ver doc-comment de
/// [`detect`] sobre por que essa combinação não pode viver só aqui).
/// Nunca inventa um sinal de stereo/esfera que os metadados não deram —
/// só refina full-vs-half via aspect ratio quando o metadado confirma o
/// layout (SBS/OU) sem dizer a resolução por olho. 180° é tratado como
/// SBS-only nesta app (mesmo judgment call de [`detect_from_filename`]) —
/// qualquer stereo não-mono + `HalfEquirectangular` vira [`Format3D::Vr180Sbs`].
pub fn resolve_container_hint(
    stereo: Option<VideoStereoMode>,
    projection: Option<VideoProjection>,
    width: u32,
    height: u32,
) -> Option<Format3D> {
    let is_stereo = matches!(
        stereo,
        Some(VideoStereoMode::SideBySideLeft)
            | Some(VideoStereoMode::SideBySideRight)
            | Some(VideoStereoMode::TopBottomLeft)
            | Some(VideoStereoMode::TopBottomRight)
    );
    let is_sbs = matches!(
        stereo,
        Some(VideoStereoMode::SideBySideLeft) | Some(VideoStereoMode::SideBySideRight)
    );
    let aspect = if width == 0 || height == 0 {
        0.0
    } else {
        width as f64 / height as f64
    };

    match projection {
        Some(VideoProjection::HalfEquirectangular) => Some(if is_stereo {
            Format3D::Vr180Sbs
        } else {
            Format3D::Vr180Mono
        }),
        Some(VideoProjection::EquirectangularTile {
            bound_left,
            bound_right,
            ..
        }) => {
            let total = bound_left as u64 + bound_right as u64;
            if total > u32::MAX as u64 {
                None
            } else {
                let remaining = (u32::MAX as u64 - total) as f64 / u32::MAX as f64;
                if (0.40..=0.60).contains(&remaining) {
                    Some(if is_stereo {
                        Format3D::Vr180Sbs
                    } else {
                        Format3D::Vr180Mono
                    })
                } else if remaining >= 0.90 {
                    Some(if !is_stereo {
                        Format3D::Spherical360Mono
                    } else if is_sbs {
                        if is_full_packing(true, aspect) {
                            Format3D::Spherical360SbsFull
                        } else {
                            Format3D::Spherical360SbsHalf
                        }
                    } else if is_full_packing(false, aspect) {
                        Format3D::Spherical360OverUnderFull
                    } else {
                        Format3D::Spherical360OverUnderHalf
                    })
                } else {
                    None
                }
            }
        }
        Some(VideoProjection::Equirectangular) => Some(if !is_stereo {
            Format3D::Spherical360Mono
        } else if is_sbs {
            if is_full_packing(true, aspect) {
                Format3D::Spherical360SbsFull
            } else {
                Format3D::Spherical360SbsHalf
            }
        } else if is_full_packing(false, aspect) {
            Format3D::Spherical360OverUnderFull
        } else {
            Format3D::Spherical360OverUnderHalf
        }),
        Some(VideoProjection::Cubemap) => Some(if is_stereo {
            Format3D::Cubemap3x2Sbs
        } else if aspect >= 5.0 {
            Format3D::Cubemap6x1Mono
        } else {
            Format3D::Cubemap3x2Mono
        }),
        Some(VideoProjection::EquiangularCubemap) => Some(if is_stereo {
            Format3D::Eac3x2Sbs
        } else {
            Format3D::Eac3x2Mono
        }),
        Some(VideoProjection::Fisheye190) => Some(if is_stereo {
            Format3D::Fisheye190Sbs
        } else {
            Format3D::Fisheye190Mono
        }),
        Some(_) => None,
        None if is_stereo && is_sbs => Some(if is_full_packing(true, aspect) {
            Format3D::SbsFull
        } else {
            Format3D::SbsHalf
        }),
        None if is_stereo => Some(if is_full_packing(false, aspect) {
            Format3D::OverUnderFull
        } else {
            Format3D::OverUnderHalf
        }),
        None => None,
    }
}

/// How [`detect`] arrived at its answer — lets the T3.4 confirmation UI
/// decide whether to show a "we're not fully sure" affordance next to the
/// detected format. Ordered roughly by trustworthiness, most to least.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum DetectionConfidence {
    /// Read straight out of container metadata (`st3d`/`sv3d`/`StereoMode`/
    /// `Projection`). The content itself declares its format — as
    /// trustworthy as detection gets.
    FromMetadata,
    /// Matched a known filename convention (e.g. `_sbs`, `_360`). Common
    /// and usually reliable, but filenames are free text — a false
    /// positive/negative is always possible.
    FromFilename,
    /// No metadata, no filename pattern — inferred purely from aspect
    /// ratio + resolution. The T3.3 "último recurso" tier; easy to fool
    /// (e.g. an unusually-cropped flat 21:9 video near 32:9).
    FromHeuristic,
    /// No signal whatsoever — defaulted to flat 2D.
    Default,
}

/// Internal: stereo packing extracted from a filename, independent of
/// whatever projection (flat/180°/360°) the name also implies. Kept
/// private — callers only ever see the combined [`Format3D`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum FilenameStereoLayout {
    SbsFull,
    SbsHalf,
    OuFull,
    OuHalf,
}

/// Internal: projection/extent extracted from a filename.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum FilenameProjection {
    /// Fisheye 190° specific SBS identifiers (e.g., `rf52`, `vr190`).
    Fisheye190Specific,
    /// Fisheye 190° generic markers (e.g., `fisheye190`, `190fisheye`, `eyefish190`).
    Fisheye190Generic,
    /// `vr180`, `180vr`, `180x180`, `180`+`sbs`, `180`+`vr`, `vr`+`180` — explicitly VR180-branded markers.
    Vr180Specific,
    /// Bare `180` token with no VR180-specific marker alongside it.
    Vr180Generic,
    /// `360` or `360x180`.
    Spherical360,
    /// Cubemap 3x2 / generic cubemap marker (`cubemap`, `cube`).
    Cubemap,
    /// Cubemap 6x1 (`6x1` combined with `cube`, `cubemap`, or `360`).
    Cubemap6x1,
    /// EAC / Equi-Angular Cubemap marker (`eac`).
    Eac,
}

/// Detect [`Format3D`] from a filename using whole-token matching on non-alphanumeric separators.
/// Returns `None` when nothing in the name matches any known convention, so callers can fall through to
/// [`detect_from_resolution`].
///
/// Projection extraction order:
/// Specific projections (Fisheye, EAC, Cubemap) are checked before `360`, which is checked
/// before VR180-specific markers (`vr180`, `180vr`, `180x180`), which are checked before bare `180`.
pub fn detect_from_filename(name: &str) -> Option<Format3D> {
    let stem = match name.rsplit_once('.') {
        Some((base, _ext)) if !base.is_empty() => base,
        _ => name,
    };
    let lower = stem.to_lowercase();
    let tokens: Vec<&str> = lower
        .split(|c: char| !c.is_alphanumeric())
        .filter(|s| !s.is_empty())
        .collect();

    let has_token = |t: &str| tokens.contains(&t);
    let has_pair = |t1: &str, t2: &str| tokens.windows(2).any(|w| w[0] == t1 && w[1] == t2);

    let is_sbs_half = has_token("hsbs") || has_pair("half", "sbs");
    let is_ou_half = has_token("hou") || has_pair("half", "ou") || has_pair("half", "tab");

    let is_sbs_full = has_token("sbs")
        || has_token("fsbs")
        || has_token("lr")
        || has_pair("3d", "sbs")
        || has_pair("side", "by")
        || has_pair("left", "right");

    let is_ou_full = has_token("ou")
        || has_token("fou")
        || has_token("tb")
        || has_token("tab")
        || has_pair("over", "under")
        || has_pair("top", "bottom");

    let stereo_layout = if is_sbs_half {
        Some(FilenameStereoLayout::SbsHalf)
    } else if is_ou_half {
        Some(FilenameStereoLayout::OuHalf)
    } else if is_sbs_full {
        Some(FilenameStereoLayout::SbsFull)
    } else if is_ou_full {
        Some(FilenameStereoLayout::OuFull)
    } else {
        None
    };

    let is_explicit_mono = has_token("mono") || has_token("2d");

    let is_fisheye_specific = has_token("vr190") || has_token("rf52");
    let is_fisheye_generic = has_token("fisheye190")
        || has_token("190fisheye")
        || has_token("eyefish190")
        || has_pair("fisheye", "190")
        || has_pair("190", "fisheye")
        || has_pair("eyefish", "190");

    let is_eac = has_token("eac");
    let is_cubemap_6x1 =
        has_token("6x1") && (has_token("cube") || has_token("cubemap") || has_token("360"));
    let is_cubemap = has_token("cubemap") || has_token("cube");

    let is_spherical_360 = has_token("360x180")
        || has_token("360")
        || has_token("360vr")
        || has_token("vr360")
        || has_pair("360", "vr")
        || has_pair("vr", "360");

    // The old rule was `_vr_` (flanked by separators on both sides, e.g. `trip_vr_footage.mp4`).
    // Replicate this by requiring `vr` to be an interior token so that boundary words like
    // `family_vr.mp4` or `Movie.VR.mkv` do not trigger false positives.
    let is_interior_vr = tokens.len() >= 3 && tokens[1..tokens.len() - 1].contains(&"vr");

    let is_vr180_specific = has_token("vr180")
        || has_token("180vr")
        || has_token("180x180")
        || has_pair("180", "sbs")
        || has_pair("180", "vr")
        || has_pair("vr", "180")
        || is_interior_vr;

    let is_vr180_generic = has_token("180");

    let projection = if is_fisheye_specific {
        Some(FilenameProjection::Fisheye190Specific)
    } else if is_fisheye_generic {
        Some(FilenameProjection::Fisheye190Generic)
    } else if is_eac {
        Some(FilenameProjection::Eac)
    } else if is_cubemap_6x1 {
        Some(FilenameProjection::Cubemap6x1)
    } else if is_cubemap {
        Some(FilenameProjection::Cubemap)
    } else if is_spherical_360 {
        Some(FilenameProjection::Spherical360)
    } else if is_vr180_specific {
        Some(FilenameProjection::Vr180Specific)
    } else if is_vr180_generic {
        Some(FilenameProjection::Vr180Generic)
    } else {
        None
    };

    match projection {
        None => stereo_layout.map(|layout| match layout {
            FilenameStereoLayout::SbsFull => Format3D::SbsFull,
            FilenameStereoLayout::SbsHalf => Format3D::SbsHalf,
            FilenameStereoLayout::OuFull => Format3D::OverUnderFull,
            FilenameStereoLayout::OuHalf => Format3D::OverUnderHalf,
        }),
        Some(FilenameProjection::Fisheye190Specific) => Some(if is_explicit_mono {
            Format3D::Fisheye190Mono
        } else {
            Format3D::Fisheye190Sbs
        }),
        Some(FilenameProjection::Fisheye190Generic) => Some(match stereo_layout {
            Some(_) => Format3D::Fisheye190Sbs,
            None if is_explicit_mono => Format3D::Fisheye190Mono,
            None => Format3D::Fisheye190Sbs,
        }),
        Some(FilenameProjection::Spherical360) => Some(match stereo_layout {
            None => Format3D::Spherical360Mono,
            Some(FilenameStereoLayout::SbsFull) => Format3D::Spherical360SbsFull,
            Some(FilenameStereoLayout::SbsHalf) => Format3D::Spherical360SbsHalf,
            Some(FilenameStereoLayout::OuFull) => Format3D::Spherical360OverUnderFull,
            Some(FilenameStereoLayout::OuHalf) => Format3D::Spherical360OverUnderHalf,
        }),
        // VR180-specific markers collapse to Sbs a menos que haja marcador explícito mono.
        Some(FilenameProjection::Vr180Specific) => Some(if is_explicit_mono {
            Format3D::Vr180Mono
        } else {
            Format3D::Vr180Sbs
        }),
        Some(FilenameProjection::Vr180Generic) => Some(match stereo_layout {
            None => Format3D::Vr180Mono,
            Some(_) => Format3D::Vr180Sbs,
        }),
        Some(FilenameProjection::Eac) => Some(match stereo_layout {
            Some(FilenameStereoLayout::SbsFull) | Some(FilenameStereoLayout::SbsHalf) => {
                Format3D::Eac3x2Sbs
            }
            _ => Format3D::Eac3x2Mono,
        }),
        Some(FilenameProjection::Cubemap6x1) => Some(Format3D::Cubemap6x1Mono),
        Some(FilenameProjection::Cubemap) => Some(match stereo_layout {
            Some(FilenameStereoLayout::SbsFull) | Some(FilenameStereoLayout::SbsHalf) => {
                Format3D::Cubemap3x2Sbs
            }
            _ => Format3D::Cubemap3x2Mono,
        }),
    }
}

/// Tolerance band applied around every target aspect ratio in
/// [`detect_from_resolution`]. ±5% comfortably absorbs real-world encodes
/// that are a handful of pixels off an exact ratio (cropping, padding,
/// non-square pixels) without being so wide that unrelated aspect ratios
/// (e.g. 21:9 flat cinema content) start falling into a stereo/spherical
/// bucket by accident.
const RESOLUTION_ASPECT_TOLERANCE: f64 = 0.05;

/// Detect [`Format3D`] from raw pixel dimensions alone, per T3.3. This is
/// the least reliable signal in the whole module (see the module-level
/// CAUTION) — only ever consulted when neither container metadata nor the
/// filename gave an answer. It can only ever return the formats the doc's
/// heuristic table actually describes signal for (flat full-SBS, mono
/// 360°, stereo-OU 360°); everything else — half framings, OU flat,
/// VR180 — has no distinguishing aspect ratio and is intentionally left to
/// metadata/filename detection instead of guessed here.
pub fn detect_from_resolution(width: u32, height: u32) -> Option<Format3D> {
    if width == 0 || height == 0 {
        return None;
    }

    let aspect = width as f64 / height as f64;
    // The doc's "resolução >= 3840" bar is about horizontal (4K UHD)
    // resolution, which for a 2:1 or 1:1 frame is always the larger of the
    // two dimensions — using `max` rather than `width` directly keeps this
    // correct even if a caller passes portrait-oriented dimensions.
    let max_dim = width.max(height);

    let close_to =
        |value: f64, target: f64| (value - target).abs() <= target * RESOLUTION_ASPECT_TOLERANCE;

    // ~32:9 or ~4:1 -> almost certainly full SBS (two full-width 16:9-ish
    // eyes side by side).
    if close_to(aspect, 32.0 / 9.0) || close_to(aspect, 4.0) {
        return Some(Format3D::SbsFull);
    }

    // ~2:1 at >= 4K -> classic 360° equirectangular mono (a stereo 360°
    // SBS/OU frame at this resolution would need to be *twice* as large in
    // one axis to give each eye a full 2:1 sphere).
    if close_to(aspect, 2.0) && max_dim >= 3840 {
        return Some(Format3D::Spherical360Mono);
    }

    // ~1:1 at >= 4K -> per T3.3, probably 360° stereo OU (two stacked 2:1
    // equirect eyes collapse to a roughly square frame).
    if close_to(aspect, 1.0) && max_dim >= 3840 {
        return Some(Format3D::Spherical360OverUnderFull);
    }

    // ~6:1 at >= 3K -> faixa horizontal de 6 faces cubemap mono (6x1)
    if close_to(aspect, 6.0) && max_dim >= 3000 {
        return Some(Format3D::Cubemap6x1Mono);
    }

    // ~3:1 at >= 3840 -> cubemap stereo SBS (duas metades 3x2 lado a lado)
    if close_to(aspect, 3.0) && max_dim >= 3840 {
        return Some(Format3D::Cubemap3x2Sbs);
    }

    None
}

/// Compose all three detection sources in priority order — container
/// metadata > filename > resolution heuristic > conservative default — and
/// report how confident the result is (T3.4 UI affordance).
///
/// `container_hint` is `Option<Format3D>` rather than
/// `Option<(VideoStereoMode, VideoProjection)>` deliberately: turning raw
/// box/element values into a `Format3D` needs the actual frame dimensions
/// too (container metadata alone doesn't distinguish half- from
/// full-resolution SBS/OU packing), and doing that combination correctly
/// requires FFmpeg's parsed stream info, which only exists inside `core`.
/// So `core` is expected to do that final `Format3D` resolution itself and
/// hand the answer in here — this function only orchestrates priority.
pub fn detect(
    filename: &str,
    width: u32,
    height: u32,
    container_hint: Option<Format3D>,
) -> (Format3D, DetectionConfidence) {
    if let Some(format) = container_hint {
        return (format, DetectionConfidence::FromMetadata);
    }

    if let Some(format) = detect_from_filename(filename) {
        return (format, DetectionConfidence::FromFilename);
    }

    if let Some(format) = detect_from_resolution(width, height) {
        return (format, DetectionConfidence::FromHeuristic);
    }

    // No signal whatsoever: per the module-level CAUTION, never guess a
    // stereo/spherical mode with nothing backing it up.
    (Format3D::Flat2D, DetectionConfidence::Default)
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- is_stereo / is_spherical ----

    #[test]
    fn flat_2d_is_neither_stereo_nor_spherical() {
        assert!(!Format3D::Flat2D.is_stereo());
        assert!(!Format3D::Flat2D.is_spherical());
    }

    #[test]
    fn flat_sbs_and_ou_are_stereo_but_not_spherical() {
        for f in [
            Format3D::SbsFull,
            Format3D::SbsHalf,
            Format3D::OverUnderFull,
            Format3D::OverUnderHalf,
        ] {
            assert!(f.is_stereo(), "{f:?} should be stereo");
            assert!(!f.is_spherical(), "{f:?} should not be spherical");
        }
    }

    #[test]
    fn spherical_360_mono_is_spherical_but_not_stereo() {
        assert!(!Format3D::Spherical360Mono.is_stereo());
        assert!(Format3D::Spherical360Mono.is_spherical());
    }

    #[test]
    fn spherical_360_stereo_variants_are_both() {
        for f in [
            Format3D::Spherical360SbsFull,
            Format3D::Spherical360SbsHalf,
            Format3D::Spherical360OverUnderFull,
            Format3D::Spherical360OverUnderHalf,
        ] {
            assert!(f.is_stereo(), "{f:?} should be stereo");
            assert!(f.is_spherical(), "{f:?} should be spherical");
        }
    }

    #[test]
    fn vr180_mono_is_spherical_but_not_stereo() {
        assert!(!Format3D::Vr180Mono.is_stereo());
        assert!(Format3D::Vr180Mono.is_spherical());
    }

    #[test]
    fn vr180_sbs_is_both() {
        assert!(Format3D::Vr180Sbs.is_stereo());
        assert!(Format3D::Vr180Sbs.is_spherical());
    }

    // ---- detect_from_filename: one test per T3.2 pattern-table row ----

    #[test]
    fn filename_underscore_sbs() {
        assert_eq!(
            detect_from_filename("movie_sbs.mp4"),
            Some(Format3D::SbsFull)
        );
    }

    #[test]
    fn filename_dash_sbs() {
        assert_eq!(
            detect_from_filename("movie-sbs.mp4"),
            Some(Format3D::SbsFull)
        );
    }

    #[test]
    fn filename_3d_sbs() {
        assert_eq!(
            detect_from_filename("movie_3d_sbs.mp4"),
            Some(Format3D::SbsFull)
        );
    }

    #[test]
    fn filename_hsbs() {
        assert_eq!(
            detect_from_filename("movie_hsbs.mp4"),
            Some(Format3D::SbsHalf)
        );
    }

    #[test]
    fn filename_half_sbs() {
        assert_eq!(
            detect_from_filename("movie_half_sbs.mp4"),
            Some(Format3D::SbsHalf)
        );
    }

    #[test]
    fn filename_ou() {
        assert_eq!(
            detect_from_filename("movie_ou.mkv"),
            Some(Format3D::OverUnderFull)
        );
    }

    #[test]
    fn filename_tab() {
        assert_eq!(
            detect_from_filename("movie_tab.mkv"),
            Some(Format3D::OverUnderFull)
        );
    }

    #[test]
    fn filename_hou() {
        assert_eq!(
            detect_from_filename("movie_hou.mkv"),
            Some(Format3D::OverUnderHalf)
        );
    }

    #[test]
    fn filename_360_alone() {
        assert_eq!(
            detect_from_filename("sunset_360.mp4"),
            Some(Format3D::Spherical360Mono)
        );
    }

    #[test]
    fn filename_180_alone() {
        assert_eq!(
            detect_from_filename("beach_180.mp4"),
            Some(Format3D::Vr180Mono)
        );
    }

    #[test]
    fn filename_vr180() {
        assert_eq!(
            detect_from_filename("trip_vr180.mp4"),
            Some(Format3D::Vr180Sbs)
        );
    }

    #[test]
    fn filename_vr_() {
        assert_eq!(
            detect_from_filename("trip_vr_footage.mp4"),
            Some(Format3D::Vr180Sbs)
        );
    }

    #[test]
    fn filename_180x180() {
        assert_eq!(
            detect_from_filename("trip_180x180.mp4"),
            Some(Format3D::Vr180Sbs)
        );
    }

    #[test]
    fn filename_vr180_variations() {
        assert_eq!(
            detect_from_filename("clip-vr180.mp4"),
            Some(Format3D::Vr180Sbs)
        );
        assert_eq!(
            detect_from_filename("clip.vr180.mp4"),
            Some(Format3D::Vr180Sbs)
        );
        assert_eq!(
            detect_from_filename("clip_180vr.mp4"),
            Some(Format3D::Vr180Sbs)
        );
        assert_eq!(
            detect_from_filename("clip_180_vr.mp4"),
            Some(Format3D::Vr180Sbs)
        );
        assert_eq!(
            detect_from_filename("clip_vr180_mono.mp4"),
            Some(Format3D::Vr180Mono)
        );
    }

    #[test]
    fn filename_360x180() {
        assert_eq!(
            detect_from_filename("trip_360x180.mp4"),
            Some(Format3D::Spherical360Mono)
        );
    }

    #[test]
    fn filename_lr() {
        assert_eq!(
            detect_from_filename("movie_lr.mp4"),
            Some(Format3D::SbsFull)
        );
    }

    #[test]
    fn filename_no_known_pattern_returns_none() {
        assert_eq!(detect_from_filename("family_vacation_2023.mp4"), None);
    }

    // ---- ambiguous / combined cases (priority order locked in) ----

    #[test]
    fn filename_360_combined_with_sbs_resolves_to_stereo_360_full() {
        assert_eq!(
            detect_from_filename("space_360_sbs.mp4"),
            Some(Format3D::Spherical360SbsFull)
        );
    }

    #[test]
    fn filename_360_combined_with_half_sbs_resolves_to_stereo_360_half() {
        assert_eq!(
            detect_from_filename("space_360_hsbs.mp4"),
            Some(Format3D::Spherical360SbsHalf)
        );
    }

    #[test]
    fn filename_360_combined_with_ou_resolves_to_stereo_360_over_under() {
        assert_eq!(
            detect_from_filename("space_360_ou.mp4"),
            Some(Format3D::Spherical360OverUnderFull)
        );
    }

    #[test]
    fn filename_360_combined_with_hou_resolves_to_stereo_360_over_under_half() {
        assert_eq!(
            detect_from_filename("space_360_hou.mp4"),
            Some(Format3D::Spherical360OverUnderHalf)
        );
    }

    #[test]
    fn filename_half_sbs_is_never_misread_as_full_sbs() {
        // Regression guard for the substring trap documented on
        // detect_from_filename: "_half_sbs" contains "_sbs" literally.
        assert_eq!(
            detect_from_filename("clip_half_sbs.mp4"),
            Some(Format3D::SbsHalf)
        );
        assert_ne!(
            detect_from_filename("clip_half_sbs.mp4"),
            Some(Format3D::SbsFull)
        );
    }

    #[test]
    fn filename_bare_180_with_explicit_sbs_marker_resolves_to_vr180_sbs() {
        // "_180" alone is mono (no stereo signal), but an explicit "_sbs"
        // marker elsewhere in the name is enough support to call it
        // Vr180Sbs instead of Vr180Mono.
        assert_eq!(
            detect_from_filename("frontyard_180_sbs.mp4"),
            Some(Format3D::Vr180Sbs)
        );
    }

    #[test]
    fn filename_vr180_specific_marker_wins_over_bare_180_substring() {
        // "_vr180" contains "_180" as a substring; the specific marker must
        // still drive classification, not the generic one.
        assert_eq!(
            detect_from_filename("scene_vr180_take2.mp4"),
            Some(Format3D::Vr180Sbs)
        );
    }

    #[test]
    fn filename_fisheye190_patterns() {
        assert_eq!(
            detect_from_filename("concert_fisheye190.mp4"),
            Some(Format3D::Fisheye190Sbs)
        );
        assert_eq!(
            detect_from_filename("nature_fisheye_190.mkv"),
            Some(Format3D::Fisheye190Sbs)
        );
        assert_eq!(
            detect_from_filename("canon_shot_rf52.mp4"),
            Some(Format3D::Fisheye190Sbs)
        );
        assert_eq!(
            detect_from_filename("tour_vr190.mp4"),
            Some(Format3D::Fisheye190Sbs)
        );
        assert_eq!(
            detect_from_filename("clip_190_fisheye.mp4"),
            Some(Format3D::Fisheye190Sbs)
        );
        assert_eq!(
            detect_from_filename("user_clip_eyefish190.mp4"),
            Some(Format3D::Fisheye190Sbs)
        );
        // Explicit mono
        assert_eq!(
            detect_from_filename("clip_fisheye190_mono.mp4"),
            Some(Format3D::Fisheye190Mono)
        );
        assert_eq!(
            detect_from_filename("camera_2d_190_fisheye.mp4"),
            Some(Format3D::Fisheye190Mono)
        );
    }

    #[test]
    fn filename_is_case_insensitive() {
        assert_eq!(
            detect_from_filename("MOVIE_SBS.MP4"),
            Some(Format3D::SbsFull)
        );
        assert_eq!(
            detect_from_filename("Space_360_SBS.mp4"),
            Some(Format3D::Spherical360SbsFull)
        );
    }

    #[test]
    fn filename_whole_token_matching_acceptance_criteria() {
        // Positive cases from acceptance criteria
        assert_eq!(
            detect_from_filename("Film.SBS.mkv"),
            Some(Format3D::SbsFull)
        );
        assert_eq!(
            detect_from_filename("Film.fsbs.mkv"),
            Some(Format3D::SbsFull)
        );
        assert_eq!(
            detect_from_filename("Film.hsbs.mkv"),
            Some(Format3D::SbsHalf)
        );
        assert_eq!(
            detect_from_filename("Film.fou.mkv"),
            Some(Format3D::OverUnderFull)
        );
        assert_eq!(
            detect_from_filename("Film.TB.mkv"),
            Some(Format3D::OverUnderFull)
        );
        assert_eq!(
            detect_from_filename("Film.360.sbs.mkv"),
            Some(Format3D::Spherical360SbsFull)
        );

        // Negative cases from acceptance criteria
        assert_eq!(detect_from_filename("Film_360p.mp4"), None);
        assert_eq!(detect_from_filename("Film_180p.mp4"), None);
        assert_eq!(detect_from_filename("Film_out.mkv"), None);
        assert_eq!(detect_from_filename("Film_ou2.mkv"), None);
        assert_eq!(detect_from_filename("Film_lrx.mkv"), None);
        assert_eq!(detect_from_filename("Film_table.mkv"), None);

        // Edge cases: 360 combined with vr, vr360, 360vr, and fisheye variations
        assert_eq!(
            detect_from_filename("Film_360_vr.mp4"),
            Some(Format3D::Spherical360Mono)
        );
        assert_eq!(
            detect_from_filename("Film.360.vr.sbs.mkv"),
            Some(Format3D::Spherical360SbsFull)
        );
        assert_eq!(
            detect_from_filename("space_vr360.mp4"),
            Some(Format3D::Spherical360Mono)
        );
        assert_eq!(
            detect_from_filename("rollercoaster_360vr.mp4"),
            Some(Format3D::Spherical360Mono)
        );
        // Bare 'vr' without 180 or 360 does not match VR180 SBS
        assert_eq!(detect_from_filename("family_vr.mp4"), None);
        assert_eq!(detect_from_filename("Movie.VR.mkv"), None);

        // Unrequested fisheye variants are not matched without explicit 190 indicator
        assert_eq!(detect_from_filename("clip_fisheye_220.mp4"), None);
        assert_eq!(detect_from_filename("clip_fisheye_200.mp4"), None);
        assert_eq!(detect_from_filename("film_mkx200.mp4"), None);

        // Cubemap 6x1 with '360' token takes precedence over spherical 360
        assert_eq!(
            detect_from_filename("tour.360.6x1.mp4"),
            Some(Format3D::Cubemap6x1Mono)
        );
        assert_eq!(
            detect_from_filename("tour-6x1-360.mp4"),
            Some(Format3D::Cubemap6x1Mono)
        );
        assert_eq!(
            detect_from_filename("Tour.360.Cube.6x1.mp4"),
            Some(Format3D::Cubemap6x1Mono)
        );

        // EAC and Cubemap with '360' token take precedence over spherical 360
        assert_eq!(
            detect_from_filename("flight.360.eac.mp4"),
            Some(Format3D::Eac3x2Mono)
        );
        assert_eq!(
            detect_from_filename("space.360.cubemap.mp4"),
            Some(Format3D::Cubemap3x2Mono)
        );
    }

    // ---- detect_from_resolution ----

    #[test]
    fn resolution_32_by_9_is_full_sbs() {
        // 3840x1080 == 32:9 exactly.
        assert_eq!(detect_from_resolution(3840, 1080), Some(Format3D::SbsFull));
    }

    #[test]
    fn resolution_4_to_1_is_full_sbs() {
        // 7680x1920 == 4:1 exactly (two 3840x1920-ish eyes side by side).
        assert_eq!(detect_from_resolution(7680, 1920), Some(Format3D::SbsFull));
    }

    #[test]
    fn resolution_2_to_1_at_4k_is_spherical_360_mono() {
        assert_eq!(
            detect_from_resolution(7680, 3840),
            Some(Format3D::Spherical360Mono)
        );
        // Also the canonical 4K equirect: 3840x1920.
        assert_eq!(
            detect_from_resolution(3840, 1920),
            Some(Format3D::Spherical360Mono)
        );
    }

    #[test]
    fn resolution_2_to_1_below_4k_does_not_match() {
        // Same 2:1 aspect ratio, but under the >= 3840 resolution bar.
        assert_eq!(detect_from_resolution(1920, 960), None);
    }

    #[test]
    fn resolution_1_to_1_at_4k_is_spherical_360_over_under() {
        assert_eq!(
            detect_from_resolution(3840, 3840),
            Some(Format3D::Spherical360OverUnderFull)
        );
    }

    #[test]
    fn resolution_1_to_1_below_4k_does_not_match() {
        assert_eq!(detect_from_resolution(1024, 1024), None);
    }

    #[test]
    fn resolution_within_tolerance_band_still_matches() {
        // 3900x1080 is aspect ~3.611, within 5% of 32:9 (~3.556).
        assert_eq!(detect_from_resolution(3900, 1080), Some(Format3D::SbsFull));
    }

    #[test]
    fn resolution_standard_16_9_matches_no_band() {
        assert_eq!(detect_from_resolution(1920, 1080), None);
    }

    #[test]
    fn resolution_zero_dimension_is_handled_without_panicking() {
        assert_eq!(detect_from_resolution(0, 1080), None);
        assert_eq!(detect_from_resolution(1920, 0), None);
    }

    // ---- detect: composition priority ----

    #[test]
    fn detect_prefers_container_metadata_over_everything_else() {
        let (format, confidence) = detect(
            "movie_sbs.mp4", // filename says full SBS
            3840,
            3840,                      // resolution heuristic says 360 OU
            Some(Format3D::Vr180Mono), // metadata says VR180 mono
        );
        assert_eq!(format, Format3D::Vr180Mono);
        assert_eq!(confidence, DetectionConfidence::FromMetadata);
    }

    #[test]
    fn detect_prefers_filename_over_resolution_heuristic() {
        let (format, confidence) = detect(
            "movie_hou.mp4", // filename says half OU
            3840,
            3840, // resolution heuristic would say 360 OU full
            None,
        );
        assert_eq!(format, Format3D::OverUnderHalf);
        assert_eq!(confidence, DetectionConfidence::FromFilename);
    }

    #[test]
    fn detect_falls_back_to_resolution_heuristic_when_no_metadata_or_filename_match() {
        let (format, confidence) = detect("family_video.mp4", 3840, 1920, None);
        assert_eq!(format, Format3D::Spherical360Mono);
        assert_eq!(confidence, DetectionConfidence::FromHeuristic);
    }

    #[test]
    fn detect_falls_back_to_flat_2d_default_when_nothing_matches() {
        let (format, confidence) = detect("family_video.mp4", 1920, 1080, None);
        assert_eq!(format, Format3D::Flat2D);
        assert_eq!(confidence, DetectionConfidence::Default);
    }

    // ---- to_screen_mode_index: mapeamento exaustivo pros 10 indices nativos ----

    #[test]
    fn to_screen_mode_index_maps_all_variants_correctly() {
        assert_eq!(Format3D::Flat2D.to_screen_mode_index(), 0);
        assert_eq!(Format3D::SbsFull.to_screen_mode_index(), 1);
        assert_eq!(Format3D::SbsHalf.to_screen_mode_index(), 2);
        assert_eq!(Format3D::OverUnderFull.to_screen_mode_index(), 3);
        assert_eq!(Format3D::OverUnderHalf.to_screen_mode_index(), 4);
        assert_eq!(Format3D::Spherical360Mono.to_screen_mode_index(), 5);
        assert_eq!(Format3D::Vr180Mono.to_screen_mode_index(), 6);
        assert_eq!(Format3D::Spherical360SbsFull.to_screen_mode_index(), 7);
        assert_eq!(Format3D::Spherical360SbsHalf.to_screen_mode_index(), 7);
        assert_eq!(
            Format3D::Spherical360OverUnderFull.to_screen_mode_index(),
            8
        );
        assert_eq!(
            Format3D::Spherical360OverUnderHalf.to_screen_mode_index(),
            8
        );
        assert_eq!(Format3D::Vr180Sbs.to_screen_mode_index(), 9);
    }

    // ---- resolve_container_hint ----

    #[test]
    fn resolve_container_hint_vr180_cases() {
        // VR180 mono
        assert_eq!(
            resolve_container_hint(
                Some(VideoStereoMode::Mono),
                Some(VideoProjection::HalfEquirectangular),
                1920,
                1920
            ),
            Some(Format3D::Vr180Mono)
        );
        assert_eq!(
            resolve_container_hint(None, Some(VideoProjection::HalfEquirectangular), 1920, 1920),
            Some(Format3D::Vr180Mono)
        );

        // VR180 stereo (qualquer layout estereo vira Vr180Sbs)
        assert_eq!(
            resolve_container_hint(
                Some(VideoStereoMode::SideBySideLeft),
                Some(VideoProjection::HalfEquirectangular),
                3840,
                1920
            ),
            Some(Format3D::Vr180Sbs)
        );
        assert_eq!(
            resolve_container_hint(
                Some(VideoStereoMode::TopBottomLeft),
                Some(VideoProjection::HalfEquirectangular),
                1920,
                3840
            ),
            Some(Format3D::Vr180Sbs)
        );
    }

    #[test]
    fn resolve_container_hint_tile_vr180() {
        // Tile com bound_left e bound_right de 90° cada (2^30), deixando 180° frontais (~0.5 da esfera)
        // Arquivos Google VR180 reais usam bound_left = 1073741824, bound_right = 1073741824
        let vr180_tile = VideoProjection::EquirectangularTile {
            bound_left: 1073741824,
            bound_top: 0,
            bound_right: 1073741824,
            bound_bottom: 0,
        };

        // VR180 tile mono
        assert_eq!(
            resolve_container_hint(Some(VideoStereoMode::Mono), Some(vr180_tile), 1920, 1920),
            Some(Format3D::Vr180Mono)
        );
        assert_eq!(
            resolve_container_hint(None, Some(vr180_tile), 1920, 1920),
            Some(Format3D::Vr180Mono)
        );

        // VR180 tile stereo SBS
        assert_eq!(
            resolve_container_hint(
                Some(VideoStereoMode::SideBySideLeft),
                Some(vr180_tile),
                3840,
                1920
            ),
            Some(Format3D::Vr180Sbs)
        );
        // VR180 tile stereo SBS right-first
        assert_eq!(
            resolve_container_hint(
                Some(VideoStereoMode::SideBySideRight),
                Some(vr180_tile),
                3840,
                1920
            ),
            Some(Format3D::Vr180Sbs)
        );

        // Tile de largura total (bounds = 0) vira 360° mono
        let full_tile = VideoProjection::EquirectangularTile {
            bound_left: 0,
            bound_top: 0,
            bound_right: 0,
            bound_bottom: 0,
        };
        assert_eq!(
            resolve_container_hint(None, Some(full_tile), 3840, 1920),
            Some(Format3D::Spherical360Mono)
        );

        // Tile recortado fora dos padrões conhecidos (ex: 25% da esfera) retorna None (fallback seguro)
        let narrow_tile = VideoProjection::EquirectangularTile {
            bound_left: 3221225472, // 75% da esfera à esquerda
            bound_top: 0,
            bound_right: 0,
            bound_bottom: 0,
        };
        assert_eq!(
            resolve_container_hint(None, Some(narrow_tile), 1920, 1080),
            None
        );
    }

    #[test]
    fn test_map_av_spherical_projection_and_stereo3d() {
        // AV_SPHERICAL_FISHEYE (5) -> Fisheye190
        assert_eq!(
            map_av_spherical_projection(5, 0, 0, 0, 0),
            Some(VideoProjection::Fisheye190)
        );

        // AV_STEREO3D_FLAG_INVERT (1) com SBS (1) -> SideBySideRight
        assert_eq!(
            map_av_stereo3d(1, 1),
            Some(VideoStereoMode::SideBySideRight)
        );

        // SBS normal sem flag invert -> SideBySideLeft
        assert_eq!(map_av_stereo3d(1, 0), Some(VideoStereoMode::SideBySideLeft));

        // Top-bottom com flag invert -> TopBottomRight
        assert_eq!(map_av_stereo3d(2, 1), Some(VideoStereoMode::TopBottomRight));

        // AV_SPHERICAL_EQUIRECTANGULAR_TILE (2) -> EquirectangularTile preservando bounds
        assert_eq!(
            map_av_spherical_projection(2, 1073741824, 0, 1073741824, 0),
            Some(VideoProjection::EquirectangularTile {
                bound_left: 1073741824,
                bound_top: 0,
                bound_right: 1073741824,
                bound_bottom: 0,
            })
        );
    }

    #[test]
    fn test_projection_pose_from_ffmpeg_fixed() {
        // 90 graus em 16.16 fixed point: 90 * 65536 = 5898240
        let pose = ProjectionPose::from_ffmpeg_fixed(5898240, 0, 0);
        assert!(pose.is_nonzero());
        let expected_yaw = 90.0f32.to_radians();
        assert!((pose.yaw - expected_yaw).abs() < 1e-4);
        assert_eq!(pose.pitch, 0.0);
        assert_eq!(pose.roll, 0.0);

        // Pose zerada
        let zero_pose = ProjectionPose::from_ffmpeg_fixed(0, 0, 0);
        assert!(!zero_pose.is_nonzero());
    }

    #[test]
    fn resolve_container_hint_360_cases() {
        // 360 Mono
        assert_eq!(
            resolve_container_hint(
                Some(VideoStereoMode::Mono),
                Some(VideoProjection::Equirectangular),
                3840,
                1920
            ),
            Some(Format3D::Spherical360Mono)
        );
        assert_eq!(
            resolve_container_hint(None, Some(VideoProjection::Equirectangular), 3840, 1920),
            Some(Format3D::Spherical360Mono)
        );

        // 360 SBS Full (aspect >= 2.4, ex: 3840x1080 -> 3.55)
        assert_eq!(
            resolve_container_hint(
                Some(VideoStereoMode::SideBySideLeft),
                Some(VideoProjection::Equirectangular),
                3840,
                1080
            ),
            Some(Format3D::Spherical360SbsFull)
        );

        // 360 SBS Half (aspect < 2.4, ex: 3840x1920 -> 2.0)
        assert_eq!(
            resolve_container_hint(
                Some(VideoStereoMode::SideBySideLeft),
                Some(VideoProjection::Equirectangular),
                3840,
                1920
            ),
            Some(Format3D::Spherical360SbsHalf)
        );

        // 360 OU Full (aspect <= 1.2, ex: 1920x1920 -> 1.0 ou 1920x2160 -> 0.88)
        assert_eq!(
            resolve_container_hint(
                Some(VideoStereoMode::TopBottomLeft),
                Some(VideoProjection::Equirectangular),
                1920,
                1920
            ),
            Some(Format3D::Spherical360OverUnderFull)
        );

        // 360 OU Half (aspect > 1.2, ex: 3840x1920 -> 2.0)
        assert_eq!(
            resolve_container_hint(
                Some(VideoStereoMode::TopBottomLeft),
                Some(VideoProjection::Equirectangular),
                3840,
                1920
            ),
            Some(Format3D::Spherical360OverUnderHalf)
        );
    }

    #[test]
    fn resolve_container_hint_flat_stereo_cases() {
        // Flat SBS Full (aspect >= 2.4, ex: 3840x1080 -> ~3.55)
        assert_eq!(
            resolve_container_hint(Some(VideoStereoMode::SideBySideLeft), None, 3840, 1080),
            Some(Format3D::SbsFull)
        );

        // Flat SBS Half (aspect < 2.4, ex: 1920x1080 -> ~1.77)
        assert_eq!(
            resolve_container_hint(Some(VideoStereoMode::SideBySideRight), None, 1920, 1080),
            Some(Format3D::SbsHalf)
        );

        // Flat OU Full (aspect <= 1.2, ex: 1920x2160 -> ~0.88)
        assert_eq!(
            resolve_container_hint(Some(VideoStereoMode::TopBottomLeft), None, 1920, 2160),
            Some(Format3D::OverUnderFull)
        );

        // Flat OU Half (aspect > 1.2, ex: 1920x1080 -> ~1.77)
        assert_eq!(
            resolve_container_hint(Some(VideoStereoMode::TopBottomRight), None, 1920, 1080),
            Some(Format3D::OverUnderHalf)
        );
    }

    #[test]
    fn resolve_container_hint_cubemap_and_eac() {
        // Cubemap mono
        assert_eq!(
            resolve_container_hint(None, Some(VideoProjection::Cubemap), 1920, 1080),
            Some(Format3D::Cubemap3x2Mono)
        );
        // Cubemap 6x1 (aspect >= 5.0)
        assert_eq!(
            resolve_container_hint(None, Some(VideoProjection::Cubemap), 6000, 1000),
            Some(Format3D::Cubemap6x1Mono)
        );
        // Cubemap stereo SBS
        assert_eq!(
            resolve_container_hint(
                Some(VideoStereoMode::SideBySideLeft),
                Some(VideoProjection::Cubemap),
                3840,
                1280
            ),
            Some(Format3D::Cubemap3x2Sbs)
        );
        // EAC mono
        assert_eq!(
            resolve_container_hint(None, Some(VideoProjection::EquiangularCubemap), 1920, 1080),
            Some(Format3D::Eac3x2Mono)
        );
        // EAC stereo SBS
        assert_eq!(
            resolve_container_hint(
                Some(VideoStereoMode::SideBySideLeft),
                Some(VideoProjection::EquiangularCubemap),
                3840,
                1280
            ),
            Some(Format3D::Eac3x2Sbs)
        );
    }

    #[test]
    fn resolve_container_hint_unsupported_or_empty_returns_none() {
        // Mono sem projeção -> None (fica pra filename/resolução decidirem)
        assert_eq!(
            resolve_container_hint(Some(VideoStereoMode::Mono), None, 1920, 1080),
            None
        );
        // Sem metadados
        assert_eq!(resolve_container_hint(None, None, 1920, 1080), None);
        // Dimensões zeradas
        assert_eq!(
            resolve_container_hint(Some(VideoStereoMode::SideBySideLeft), None, 0, 0),
            Some(Format3D::SbsHalf) // aspect 0.0 < 2.4 -> Half
        );
    }

    #[test]
    fn test_thumbnail_crop_region_and_dimensions() {
        // Flat 2D e Mono não recortam
        assert_eq!(
            Format3D::Flat2D.thumbnail_crop_region(),
            ThumbnailCropRegion::None
        );
        assert_eq!(
            Format3D::Flat2D.thumbnail_dimensions(1920, 1080),
            ThumbnailDimensions {
                width: 1920,
                height: 1080
            }
        );
        assert_eq!(
            Format3D::Spherical360Mono.thumbnail_crop_region(),
            ThumbnailCropRegion::None
        );
        assert_eq!(
            Format3D::Vr180Mono.thumbnail_crop_region(),
            ThumbnailCropRegion::None
        );
        assert_eq!(
            Format3D::Cubemap3x2Mono.thumbnail_crop_region(),
            ThumbnailCropRegion::None
        );
        assert_eq!(
            Format3D::Cubemap6x1Mono.thumbnail_crop_region(),
            ThumbnailCropRegion::None
        );
        assert_eq!(
            Format3D::Eac3x2Mono.thumbnail_crop_region(),
            ThumbnailCropRegion::None
        );

        // SBS corta metade esquerda (com alinhamento par)
        assert_eq!(
            Format3D::SbsFull.thumbnail_crop_region(),
            ThumbnailCropRegion::LeftHalf
        );
        assert_eq!(
            Format3D::SbsFull.thumbnail_dimensions(3840, 1080),
            ThumbnailDimensions {
                width: 1920,
                height: 1080
            }
        );
        assert_eq!(
            Format3D::SbsHalf.thumbnail_crop_region(),
            ThumbnailCropRegion::LeftHalf
        );
        assert_eq!(
            Format3D::SbsHalf.thumbnail_dimensions(1920, 1080),
            ThumbnailDimensions {
                width: 960,
                height: 1080
            }
        );
        assert_eq!(
            Format3D::Vr180Sbs.thumbnail_crop_region(),
            ThumbnailCropRegion::LeftHalf
        );
        assert_eq!(
            Format3D::Vr180Sbs.thumbnail_dimensions(7680, 3840),
            ThumbnailDimensions {
                width: 3840,
                height: 3840
            }
        );
        assert_eq!(
            Format3D::Cubemap3x2Sbs.thumbnail_crop_region(),
            ThumbnailCropRegion::LeftHalf
        );
        assert_eq!(
            Format3D::Eac3x2Sbs.thumbnail_crop_region(),
            ThumbnailCropRegion::LeftHalf
        );

        // Over/Under corta metade superior
        assert_eq!(
            Format3D::OverUnderFull.thumbnail_crop_region(),
            ThumbnailCropRegion::TopHalf
        );
        assert_eq!(
            Format3D::OverUnderFull.thumbnail_dimensions(1920, 2160),
            ThumbnailDimensions {
                width: 1920,
                height: 1080
            }
        );
        assert_eq!(
            Format3D::OverUnderHalf.thumbnail_crop_region(),
            ThumbnailCropRegion::TopHalf
        );
        assert_eq!(
            Format3D::OverUnderHalf.thumbnail_dimensions(1920, 1080),
            ThumbnailDimensions {
                width: 1920,
                height: 540
            }
        );

        // Teste de alinhamento ímpar -> par
        assert_eq!(
            Format3D::SbsFull.thumbnail_dimensions(1921, 1080),
            ThumbnailDimensions {
                width: 960,
                height: 1080
            }
        );
        assert_eq!(
            Format3D::OverUnderFull.thumbnail_dimensions(1920, 1081),
            ThumbnailDimensions {
                width: 1920,
                height: 540
            }
        );
    }

    #[test]
    fn test_screen_mode_contract_indices() {
        // Invariante de contrato: to_screen_mode_index deve mapear estritamente para 0..=16,
        // casando com screen_mode.h no C++ e ScreenFormatCatalog.kt no Kotlin.
        assert_eq!(Format3D::Flat2D.to_screen_mode_index(), 0);
        assert_eq!(Format3D::SbsFull.to_screen_mode_index(), 1);
        assert_eq!(Format3D::SbsHalf.to_screen_mode_index(), 2);
        assert_eq!(Format3D::OverUnderFull.to_screen_mode_index(), 3);
        assert_eq!(Format3D::OverUnderHalf.to_screen_mode_index(), 4);
        assert_eq!(Format3D::Spherical360Mono.to_screen_mode_index(), 5);
        assert_eq!(Format3D::Vr180Mono.to_screen_mode_index(), 6);
        assert_eq!(Format3D::Spherical360SbsFull.to_screen_mode_index(), 7);
        assert_eq!(Format3D::Spherical360SbsHalf.to_screen_mode_index(), 7);
        assert_eq!(
            Format3D::Spherical360OverUnderFull.to_screen_mode_index(),
            8
        );
        assert_eq!(
            Format3D::Spherical360OverUnderHalf.to_screen_mode_index(),
            8
        );
        assert_eq!(Format3D::Vr180Sbs.to_screen_mode_index(), 9);
        assert_eq!(Format3D::Cubemap3x2Mono.to_screen_mode_index(), 10);
        assert_eq!(Format3D::Cubemap6x1Mono.to_screen_mode_index(), 11);
        assert_eq!(Format3D::Eac3x2Mono.to_screen_mode_index(), 12);
        assert_eq!(Format3D::Cubemap3x2Sbs.to_screen_mode_index(), 13);
        assert_eq!(Format3D::Eac3x2Sbs.to_screen_mode_index(), 14);
        assert_eq!(Format3D::Fisheye190Mono.to_screen_mode_index(), 15);
        assert_eq!(Format3D::Fisheye190Sbs.to_screen_mode_index(), 16);

        for v in ALL_VARIANTS {
            assert!(v.to_screen_mode_index() <= 16);
        }
    }

    // Todas as variantes de Format3D. Uma variante nova que ficar de fora
    // desta lista faz `screen_mode_encoding_matches_cpp_kotlin_and_bridge`
    // falhar assim que ela introduzir um índice novo.
    const ALL_VARIANTS: [Format3D; 19] = [
        Format3D::Flat2D,
        Format3D::SbsFull,
        Format3D::SbsHalf,
        Format3D::OverUnderFull,
        Format3D::OverUnderHalf,
        Format3D::Spherical360Mono,
        Format3D::Vr180Mono,
        Format3D::Spherical360SbsFull,
        Format3D::Spherical360SbsHalf,
        Format3D::Spherical360OverUnderFull,
        Format3D::Spherical360OverUnderHalf,
        Format3D::Vr180Sbs,
        Format3D::Cubemap3x2Mono,
        Format3D::Cubemap6x1Mono,
        Format3D::Eac3x2Mono,
        Format3D::Cubemap3x2Sbs,
        Format3D::Eac3x2Sbs,
        Format3D::Fisheye190Mono,
        Format3D::Fisheye190Sbs,
    ];

    /// Extrai os valores de `Nome = N,` do corpo de `enum class ScreenMode`.
    fn cpp_screen_mode_values(header: &str) -> Vec<u32> {
        let body = header
            .split("enum class ScreenMode")
            .nth(1)
            .and_then(|rest| rest.split("};").next())
            .expect("enum class ScreenMode não encontrado em screen_mode.h");
        body.lines()
            .filter_map(|line| line.split('=').nth(1))
            .map(|v| {
                v.trim()
                    .trim_end_matches(',')
                    .parse()
                    .expect("valor não numérico em ScreenMode")
            })
            .collect()
    }

    /// Extrai o primeiro argumento de cada `ScreenFormatEntry(N, ...)`.
    fn kotlin_catalog_indices(catalog: &str) -> Vec<u32> {
        catalog
            .split("ScreenFormatEntry(")
            .skip(2) // pula o texto antes da primeira ocorrência e a declaração da data class
            .map(|rest| {
                rest.split(',')
                    .next()
                    .unwrap()
                    .trim()
                    .parse()
                    .expect("índice não numérico no catálogo")
            })
            .collect()
    }

    #[test]
    fn screen_mode_encoding_matches_cpp_kotlin_and_bridge() {
        // A mesma codificação numérica vive em quatro lugares (ver CLAUDE.md).
        // Só este crate roda no CI sem NDK, então a checagem cruzada mora aqui.
        let header = include_str!("../../../native/include/screen_mode.h");
        let catalog =
            include_str!("../../../app/src/main/java/com/tucavr/screens/ScreenFormatCatalog.kt");
        let bridge = include_str!("../../bridge/src/lib.rs");

        let cpp = cpp_screen_mode_values(header);
        let count = cpp.len() as u32;
        let expected: Vec<u32> = (0..count).collect();
        assert_eq!(
            cpp, expected,
            "ScreenMode (C++) deve ser contíguo a partir de 0"
        );

        assert_eq!(
            kotlin_catalog_indices(catalog),
            expected,
            "ScreenFormatCatalog.kt deve listar os mesmos índices, em ordem"
        );

        let bridge_count: u32 = bridge
            .lines()
            .find_map(|l| l.trim().strip_prefix("const SCREEN_MODE_COUNT: u32 = "))
            .and_then(|v| v.trim_end_matches(';').parse().ok())
            .expect("SCREEN_MODE_COUNT não encontrado em rust/bridge/src/lib.rs");
        assert_eq!(
            bridge_count, count,
            "SCREEN_MODE_COUNT do bridge diverge do C++"
        );

        let mut produced: Vec<u32> = ALL_VARIANTS
            .iter()
            .map(|f| f.to_screen_mode_index())
            .collect();
        produced.sort_unstable();
        produced.dedup();
        assert_eq!(
            produced, expected,
            "Format3D deve cobrir exatamente os modos do C++"
        );
    }
}
