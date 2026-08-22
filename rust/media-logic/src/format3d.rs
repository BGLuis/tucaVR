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
        }
    }

    /// Índice numérico do `enum class ScreenMode` nativo (0-9) — precisa
    /// ficar em sincronia com `native/src/vr_player_app.cpp`,
    /// `native/src/vr_player_app_vulkan.cpp` e `modeLabelResIds` em
    /// `VRControlsPresentation.kt` (ver CLAUDE.md). O `ScreenMode` nativo
    /// tem só 10 valores contra os 12 daqui — `Spherical360SbsHalf`/
    /// `Spherical360OverUnderHalf` colapsam nos mesmos índices de suas
    /// contrapartes "full" (a geometria da esfera não muda entre full/half,
    /// só a resolução efetiva por olho — diferente do caso plano, onde
    /// full/half tem aspect ratio de quad diferente e por isso tem índices
    /// próprios). `Vr180Mono` usa o mesmo índice de `Sphere180` (não existe
    /// um "VR180 mono" separado no nativo). Match exaustivo — adicionar uma
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
        }
    }
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
    if is_sbs { aspect >= 2.4 } else { aspect <= 1.2 }
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
    let aspect = if width == 0 || height == 0 { 0.0 } else { width as f64 / height as f64 };

    match projection {
        Some(VideoProjection::HalfEquirectangular) => {
            Some(if is_stereo { Format3D::Vr180Sbs } else { Format3D::Vr180Mono })
        }
        Some(VideoProjection::Equirectangular) => Some(if !is_stereo {
            Format3D::Spherical360Mono
        } else if is_sbs {
            if is_full_packing(true, aspect) { Format3D::Spherical360SbsFull } else { Format3D::Spherical360SbsHalf }
        } else if is_full_packing(false, aspect) {
            Format3D::Spherical360OverUnderFull
        } else {
            Format3D::Spherical360OverUnderHalf
        }),
        // Cubemap/EAC/fisheye/etc: fora da taxonomia deste app — sem sinal
        // utilizavel, deixa pra filename/resolucao decidirem.
        Some(_) => None,
        None if is_stereo && is_sbs => {
            Some(if is_full_packing(true, aspect) { Format3D::SbsFull } else { Format3D::SbsHalf })
        }
        None if is_stereo => {
            Some(if is_full_packing(false, aspect) { Format3D::OverUnderFull } else { Format3D::OverUnderHalf })
        }
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
    /// Nothing matched anything. Conservative fallback: [`Format3D::Flat2D`]
    /// only, per the module-level CAUTION note — never a stereo/spherical
    /// guess with zero supporting signal.
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
    /// `_vr180`, `_180x180`, `_vr_` — explicitly VR180-branded markers.
    Vr180Specific,
    /// Bare `_180` with no VR180-specific marker alongside it.
    Vr180Generic,
    /// `_360` or `_360x180`.
    Spherical360,
}

/// Detect [`Format3D`] from a filename, per T3.2's pattern table
/// (case-insensitive `contains` matching). Returns `None` when nothing in
/// the name matches any known convention, so callers can fall through to
/// [`detect_from_resolution`].
///
/// ## Priority order (deliberate, not accidental — see tests below)
///
/// Filenames can carry *two* independent signals at once: a stereo layout
/// (SBS/OU, full/half) and a projection/extent (flat/180°/360°). This
/// function extracts both independently, then combines them, instead of
/// returning on the first pattern match in table order like the doc's
/// literal T3.2 sample — the sample's flat placeholder `Format3D` enum
/// (`Format3D::SBS`, `Format3D::Spherical360`, ...) can't represent a
/// filename like `movie_360_sbs.mp4` (stereo 360° SBS) as a single value,
/// but our richer enum can and does (`Spherical360SbsFull`), so we resolve
/// the combination rather than picking whichever pattern happens to appear
/// first in a fixed list.
///
/// Layout extraction order (half before full):
/// `_half_sbs` contains `_sbs` as a literal substring ("...alf**_sbs**"),
/// so checking the generic SBS patterns first would misclassify every
/// half-SBS file as full-SBS. Half markers are therefore always checked
/// first. `_hsbs`/`_hou` don't actually collide with `_sbs`/`_ou` this way
/// (the `h` breaks underscore-adjacency), but are checked first anyway for
/// symmetry and so this ordering keeps holding if patterns are ever added.
///
/// Projection extraction order (VR180-specific before generic `_180`):
/// `_vr180` and `_180x180` both contain `_180` as a substring, so the
/// specific markers are checked first — both this function's current
/// mapping and a plain substring-first check happen to agree here, but
/// checking the specific patterns first keeps that an explicit choice
/// rather than a coincidence.
///
/// Judgment calls (locked in by the ambiguous-case tests below):
/// - `_vr180` / `_180x180` / `_vr_` -> [`Format3D::Vr180Sbs`] unconditionally.
///   VR180 is a de-facto SBS-only format in consumer camera output (Insta360,
///   Vecnos/Kandao rigs, YouTube VR180 uploads); a bare VR180 marker with no
///   further signal is far more likely to be mislabeled-as-mono SBS content
///   than genuine mono 180° footage.
/// - Bare `_180` (no VR180-specific marker) -> [`Format3D::Vr180Mono`] unless
///   an explicit stereo layout marker is *also* present elsewhere in the
///   name, in which case it resolves to [`Format3D::Vr180Sbs`]. Unlike
///   `_vr180`, a bare `_180` alone is not a strong enough stereo signal on
///   its own (per the module-level CAUTION: don't guess stereo without
///   support), but an explicit `_sbs`/`_ou` marker in the same name *is*
///   support.
/// - `_360x180` -> kept as [`Format3D::Spherical360Mono`] (absent an
///   accompanying stereo marker) per the task brief — no more specific
///   real-world convention for that exact token was found.
/// - `_360` + an SBS/OU marker anywhere else in the name -> the matching
///   stereo 360° variant (e.g. `Spherical360SbsHalf`), never flat SBS/OU.
///   A 360° marker is strong, explicit evidence that the SBS/OU marker
///   describes the *sphere's* packing, not a flat video's.
pub fn detect_from_filename(name: &str) -> Option<Format3D> {
    let lower = name.to_lowercase();

    let stereo_layout = if lower.contains("_hsbs") || lower.contains("_half_sbs") {
        Some(FilenameStereoLayout::SbsHalf)
    } else if lower.contains("_hou") {
        Some(FilenameStereoLayout::OuHalf)
    } else if lower.contains("_sbs")
        || lower.contains("-sbs")
        || lower.contains("_3d_sbs")
        || lower.contains("_lr")
    {
        Some(FilenameStereoLayout::SbsFull)
    } else if lower.contains("_ou") || lower.contains("_tab") {
        Some(FilenameStereoLayout::OuFull)
    } else {
        None
    };

    let projection = if lower.contains("_vr180") || lower.contains("_180x180") || lower.contains("_vr_")
    {
        Some(FilenameProjection::Vr180Specific)
    } else if lower.contains("_360x180") || lower.contains("_360") {
        Some(FilenameProjection::Spherical360)
    } else if lower.contains("_180") {
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
        Some(FilenameProjection::Spherical360) => Some(match stereo_layout {
            None => Format3D::Spherical360Mono,
            Some(FilenameStereoLayout::SbsFull) => Format3D::Spherical360SbsFull,
            Some(FilenameStereoLayout::SbsHalf) => Format3D::Spherical360SbsHalf,
            Some(FilenameStereoLayout::OuFull) => Format3D::Spherical360OverUnderFull,
            Some(FilenameStereoLayout::OuHalf) => Format3D::Spherical360OverUnderHalf,
        }),
        // VR180-specific markers collapse to Sbs regardless of any other
        // layout marker found — see judgment-call notes above.
        Some(FilenameProjection::Vr180Specific) => Some(Format3D::Vr180Sbs),
        Some(FilenameProjection::Vr180Generic) => Some(match stereo_layout {
            None => Format3D::Vr180Mono,
            Some(_) => Format3D::Vr180Sbs,
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

    let close_to = |value: f64, target: f64| (value - target).abs() <= target * RESOLUTION_ASPECT_TOLERANCE;

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
        assert_eq!(detect_from_filename("movie_sbs.mp4"), Some(Format3D::SbsFull));
    }

    #[test]
    fn filename_dash_sbs() {
        assert_eq!(detect_from_filename("movie-sbs.mp4"), Some(Format3D::SbsFull));
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
        assert_eq!(detect_from_filename("movie_hsbs.mp4"), Some(Format3D::SbsHalf));
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
    fn filename_360x180() {
        assert_eq!(
            detect_from_filename("trip_360x180.mp4"),
            Some(Format3D::Spherical360Mono)
        );
    }

    #[test]
    fn filename_lr() {
        assert_eq!(detect_from_filename("movie_lr.mp4"), Some(Format3D::SbsFull));
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
            "movie_sbs.mp4",  // filename says full SBS
            3840,
            3840,             // resolution heuristic says 360 OU
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
            3840,             // resolution heuristic would say 360 OU full
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
        assert_eq!(Format3D::Spherical360OverUnderFull.to_screen_mode_index(), 8);
        assert_eq!(Format3D::Spherical360OverUnderHalf.to_screen_mode_index(), 8);
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
            resolve_container_hint(
                None,
                Some(VideoProjection::HalfEquirectangular),
                1920,
                1920
            ),
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
            resolve_container_hint(
                None,
                Some(VideoProjection::Equirectangular),
                3840,
                1920
            ),
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
    fn resolve_container_hint_unsupported_or_empty_returns_none() {
        // Cubemap -> None
        assert_eq!(
            resolve_container_hint(None, Some(VideoProjection::Cubemap), 1920, 1080),
            None
        );
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
}
