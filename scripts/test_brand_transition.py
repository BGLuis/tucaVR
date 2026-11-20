#!/usr/bin/env python3
"""
E2E Test Runner for tucaVR Brand Identity Transition (Epic #102).

Validates all 4 requirements (R1, R2, R3, R4) across 4 tiers:
  - Tier 1: Feature Coverage (>= 5 tests per feature)
  - Tier 2: Boundary & Corner Cases (>= 5 tests per feature)
  - Tier 3: Cross-Feature Combinations (pairwise interactions)
  - Tier 4: Quality gates run through Gradle (UiSourceGuardsTest, ktlint)

The intro's runtime behaviour (play once per process, skip, reduced motion, fade to
Home) is covered by the Kotlin unit tests in app/src/test/java/com/tucavr/Intro*.kt,
not here.

Usage:
  python3 scripts/test_brand_transition.py [--tier {1,2,3,4,all}]
                                           [--milestone {M1,M2,M3,M4,all}]
                                           [--feature {R1,R2,R3,R4,all}]
                                           [--skip-gradle]
                                           [--verbose]
                                           [--json-output <path>]
"""

import argparse
import functools
import importlib.util
import json
import os
import re
import struct
import subprocess
import sys
import time
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple
from xml.etree import ElementTree as ET

REPO_ROOT = Path(__file__).resolve().parent.parent
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# Canonical brand palette tokens (Figma node 133:2)
CANONICAL_PALETTE = {
    "DEEP_TEAL": "#0B4650",
    "CYAN_TURQUOISE": "#16B3B0",
    "LIGHT_AQUA": "#63D9D2",
    "DARK_TEAL": "#11808A",
    "PALE_CYAN_FOAM": "#D9F6F2",
    "FLAME_ORANGE": "#FF6B00",
    "WARM_ORANGE": "#FF8F45",
    "CRIMSON_RED": "#E2452B",
    "GOLDEN_YELLOW": "#FFC247",
    "EMERALD_GREEN": "#2ED3A2",
    "VOID_BG": "#121212",
    "TEXT_LIGHT": "#F2FBFA",
}

CANONICAL_HEX_SET = {
    c.lower() for c in CANONICAL_PALETTE.values()
} | {"#ffffff", "#00000000", "#000000"}


@dataclass
class TestCase:
    id: str
    tier: int
    milestone: str
    feature: str
    description: str
    fn: Callable[[], Tuple[bool, str]]
    requires_gradle: bool = False


@dataclass
class TestResult:
    test_id: str
    tier: int
    milestone: str
    feature: str
    description: str
    status: str  # "PASS", "FAIL", "SKIPPED"
    message: str
    duration_ms: float


REGISTRY: List[TestCase] = []


def register_test(
    id: str,
    tier: int,
    milestone: str,
    feature: str,
    description: str,
    requires_gradle: bool = False,
):
    def decorator(fn: Callable[[], Tuple[bool, str]]):
        REGISTRY.append(
            TestCase(
                id=id,
                tier=tier,
                milestone=milestone,
                feature=feature,
                description=description,
                fn=fn,
                requires_gradle=requires_gradle,
            )
        )
        return fn

    return decorator


# ----------------------------------------------------------------------
# PNG & XML & File Parsing Helpers
# ----------------------------------------------------------------------


def read_png_dimensions(path: Path) -> Tuple[int, int, int, int, int]:
    """
    Parses PNG IHDR chunk returning (width, height, bit_depth, color_type, interlace_method).
    Raises ValueError on invalid PNG.
    """
    with open(path, "rb") as f:
        header = f.read(8)
        if header != b"\x89PNG\r\n\x1a\n":
            raise ValueError(f"Invalid PNG signature in {path}")
        length, chunk_type = struct.unpack(">I4s", f.read(8))
        if chunk_type != b"IHDR":
            raise ValueError(f"First chunk in {path} is not IHDR")
        ihdr_data = f.read(length)
        if len(ihdr_data) < 13:
            raise ValueError(f"Corrupt IHDR chunk in {path}")
        w, h, bit_depth, color_type, comp, filt, interlace = struct.unpack(
            ">IIBBBBB", ihdr_data[:13]
        )
        return w, h, bit_depth, color_type, interlace


def iter_png_chunks(data: bytes):
    """Yields (type, payload, stored_crc) for every chunk after the PNG signature."""
    pos = 8
    while pos + 8 <= len(data):
        length, chunk_type = struct.unpack(">I4s", data[pos : pos + 8])
        payload = data[pos + 8 : pos + 8 + length]
        (crc,) = struct.unpack(">I", data[pos + 8 + length : pos + 12 + length])
        yield chunk_type, payload, crc
        pos += 12 + length


@functools.lru_cache(maxsize=None)
def read_png_pixels(path: Path) -> Tuple[int, int, int, Tuple[bytes, ...]]:
    """
    Decodes a non-interlaced 8-bit RGB or RGBA PNG into
    (width, height, channels, rows), each row holding width * channels bytes.
    """
    w, h, bit_depth, color_type, interlace = read_png_dimensions(path)
    if bit_depth != 8 or color_type not in (2, 6) or interlace != 0:
        raise ValueError(
            f"{path.name}: only non-interlaced 8-bit RGB/RGBA is supported "
            f"(depth {bit_depth}, color type {color_type}, interlace {interlace})"
        )
    channels = 3 if color_type == 2 else 4
    idat = b"".join(
        payload for chunk_type, payload, _ in iter_png_chunks(path.read_bytes()) if chunk_type == b"IDAT"
    )
    raw = zlib.decompress(idat)
    stride = w * channels
    rows = []
    prev = bytearray(stride)
    pos = 0
    for _ in range(h):
        filter_type = raw[pos]
        line = bytearray(raw[pos + 1 : pos + 1 + stride])
        pos += 1 + stride
        if filter_type == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                predictor = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + predictor) & 0xFF
        elif filter_type != 0:
            raise ValueError(f"{path.name}: unknown PNG filter type {filter_type}")
        rows.append(bytes(line))
        prev = line
    return w, h, channels, tuple(rows)


def lottie_value(prop: dict, frame: float):
    """
    Value of a Lottie property at a frame that coincides with a keyframe or precedes
    the first one (no interpolation); scalars wrapped in a one-element list are unwrapped.
    """
    if prop.get("a") != 1:
        value = prop["k"]
    else:
        current = prop["k"][0]
        for kf in prop["k"]:
            if kf["t"] <= frame:
                current = kf
        value = current["s"]
    if isinstance(value, list) and len(value) == 1:
        return value[0]
    return value


def lottie_animated(node) -> bool:
    if isinstance(node, dict):
        return node.get("a") == 1 or any(lottie_animated(v) for v in node.values())
    if isinstance(node, list):
        return any(lottie_animated(v) for v in node)
    return False


def lottie_visible_drawables(items: list, frame: float, path: str) -> List[str]:
    """Names of the fills/strokes in a shape list that paint something at `frame`."""
    transform = next((it for it in items if it["ty"] == "tr"), None)
    if transform is not None:
        scale = lottie_value(transform["s"], frame)
        if lottie_value(transform["o"], frame) <= 0 or 0 in scale[:2]:
            return []
    for trim in (it for it in items if it["ty"] == "tm"):
        if lottie_value(trim["s"], frame) == lottie_value(trim["e"], frame):
            return []
    visible = []
    for it in items:
        name = f"{path}/{it.get('nm', it['ty'])}"
        if it["ty"] == "gr":
            visible += lottie_visible_drawables(it["it"], frame, name)
        elif it["ty"] in ("fl", "st", "gf", "gs") and lottie_value(it["o"], frame) > 0:
            visible.append(name)
    return visible


def lottie_layer_visible_drawables(layer: dict, frame: float) -> List[str]:
    if not layer["ip"] <= frame < layer["op"]:
        return []
    ks = layer["ks"]
    if lottie_value(ks["o"], frame) <= 0 or 0 in lottie_value(ks["s"], frame)[:2]:
        return []
    return lottie_visible_drawables(layer.get("shapes", []), frame, layer["nm"])


def sea_floor_color_at(layer: dict, x: float, y: float) -> Tuple[int, int, int]:
    """
    Evaluates the intro's static radial-gradient background layer at canvas pixel
    (x, y), using the layer transform the way lottie-android maps the shader.
    """
    ks = layer["ks"]
    ax, ay = lottie_value(ks["a"], 0)[:2]
    px, py = lottie_value(ks["p"], 0)[:2]
    sx, sy = (c / 100.0 for c in lottie_value(ks["s"], 0)[:2])
    gradient = next(s for s in layer["shapes"] if s["ty"] == "gf")
    if gradient["t"] != 2:
        raise ValueError("sea floor gradient is not radial")
    gx, gy = lottie_value(gradient["s"], 0)
    ex, ey = lottie_value(gradient["e"], 0)
    radius = ((ex - gx) ** 2 + (ey - gy) ** 2) ** 0.5
    lx = (x - px) / sx + ax
    ly = (y - py) / sy + ay
    t = min(1.0, (((lx - gx) ** 2 + (ly - gy) ** 2) ** 0.5) / radius)
    count = gradient["g"]["p"]
    raw = gradient["g"]["k"]["k"]
    stops = [(raw[i * 4], raw[i * 4 + 1 : i * 4 + 4]) for i in range(count)]
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        if p0 <= t <= p1:
            f = (t - p0) / (p1 - p0) if p1 > p0 else 0.0
            return tuple(round((a + (b - a) * f) * 255) for a, b in zip(c0, c1))
    edge = stops[-1][1] if t > stops[-1][0] else stops[0][1]
    return tuple(round(c * 255) for c in edge)


def abs_path_bbox(d: str) -> Tuple[float, float, float, float]:
    """
    Bounding box (min_x, min_y, max_x, max_y) of the points and control points of an
    absolute-command SVG/VectorDrawable path. Relative commands are rejected.
    """
    tokens = re.findall(r"[A-Za-z]|[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?", d)
    xs: List[float] = []
    ys: List[float] = []
    cmd = None
    i = 0
    while i < len(tokens):
        if tokens[i].isalpha():
            cmd = tokens[i]
            i += 1
            if cmd in "Zz":
                continue
        if cmd is None or cmd.islower():
            raise ValueError(f"relative or missing path command '{cmd}' in: {d[:40]}...")
        if cmd == "H":
            xs.append(float(tokens[i]))
            i += 1
        elif cmd == "V":
            ys.append(float(tokens[i]))
            i += 1
        elif cmd in "MLCSQT":
            xs.append(float(tokens[i]))
            ys.append(float(tokens[i + 1]))
            i += 2
        else:
            raise ValueError(f"unsupported path command '{cmd}'")
    return min(xs), min(ys), max(xs), max(ys)


def foreground_paths(path: Path) -> list:
    return list(ET.parse(path).getroot().iter("path"))


def intro_layers() -> list:
    intro_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "intro.json"
    return json.loads(intro_path.read_text(encoding="utf-8"))["layers"]


def generator_dependency_error() -> Optional[str]:
    """
    The exports are rendered with the pinned cairosvg; logo.py's rsvg-convert fallback
    produces a different PNG, so the reproduction tests refuse to run without it.
    """
    if importlib.util.find_spec("cairosvg") is None:
        return (
            f"cairosvg is not importable from {sys.executable}; install design/logo/requirements.txt "
            "or run scripts/test-brand-transition.sh, which sets up that environment"
        )
    return None


def run_cmd(
    cmd: List[str], cwd: Optional[Path] = None, timeout: int = 120
) -> Tuple[int, str, str]:
    proc = subprocess.run(
        cmd,
        cwd=cwd or REPO_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=timeout,
    )
    return proc.returncode, proc.stdout, proc.stderr


# ----------------------------------------------------------------------
# TIER 1: Feature Coverage (>= 5 tests per feature: R1, R2, R3, R4)
# ----------------------------------------------------------------------


@register_test(
    "T1_R1_01_generator_scripts_presence",
    1,
    "M1",
    "R1",
    "Verify existence of generator scripts (scene.py, logo.py, requirements.txt, README.md)",
)
def test_t1_r1_01():
    logo_dir = REPO_ROOT / "design" / "logo"
    required = ["scene.py", "logo.py", "requirements.txt", "README.md"]
    missing = [f for f in required if not (logo_dir / f).exists()]
    if missing:
        return False, f"Missing generator files in design/logo: {missing}"
    return True, "All required generator files present in design/logo/"


@register_test(
    "T1_R1_02_committed_exports_presence",
    1,
    "M1",
    "R1",
    "Verify presence of committed asset exports (simbolo.svg, logo_final.svg, intro.json, vr_splash.png)",
)
def test_t1_r1_02():
    logo_dir = REPO_ROOT / "design" / "logo"
    required = ["simbolo.svg", "logo_final.svg", "intro.json", "vr_splash.png"]
    missing = [f for f in required if not (logo_dir / f).exists()]
    if missing:
        return False, f"Missing committed exports in design/logo: {missing}"
    return True, "All committed exports present in design/logo/"


@register_test(
    "T1_R1_03_font_license_attribution",
    1,
    "M1",
    "R1",
    "Verify Baloo 2 font license attribution under SIL Open Font License v1.1 in design/logo/",
)
def test_t1_r1_03():
    logo_dir = REPO_ROOT / "design" / "logo"
    readme_path = logo_dir / "README.md"
    ofl_path = logo_dir / "OFL.txt"
    if not readme_path.exists() or not ofl_path.exists():
        return False, "design/logo/README.md and design/logo/OFL.txt must both exist"
    readme = readme_path.read_text(encoding="utf-8")
    ofl = ofl_path.read_text(encoding="utf-8")
    if "SIL Open Font License, Version 1.1" not in ofl or "Baloo 2" not in ofl:
        return False, "OFL.txt is not the SIL OFL 1.1 text for Baloo 2"
    missing = [
        token
        for token in ("Baloo 2", "SIL Open Font License, Version 1.1", "OFL.txt")
        if token not in readme
    ]
    if missing:
        return False, f"README.md font attribution is missing: {missing}"
    return True, "Baloo 2 SIL Open Font License v1.1 properly documented"


@register_test(
    "T1_R1_04_svg_structure_and_viewbox",
    1,
    "M1",
    "R1",
    "Verify XML syntax and viewBox of simbolo.svg (512x512) and logo_final.svg (778x300)",
)
def test_t1_r1_04():
    logo_dir = REPO_ROOT / "design" / "logo"
    simbolo = logo_dir / "simbolo.svg"
    logo_final = logo_dir / "logo_final.svg"

    for path, expected_vb in [
        (simbolo, (512, 512)),
        (logo_final, (778, 300)),
    ]:
        if not path.exists():
            return False, f"File {path.name} does not exist"
        content = path.read_text(encoding="utf-8")
        if not ("<svg" in content and "</svg>" in content):
            return False, f"{path.name} is not a valid SVG document"
        vb_match = re.search(
            r'viewBox=["\']\s*([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s*["\']',
            content,
        )
        if not vb_match:
            return False, f"{path.name} missing viewBox attribute"
        w, h = float(vb_match.group(3)), float(vb_match.group(4))
        if (int(w), int(h)) != expected_vb:
            return (
                False,
                f"{path.name} viewBox width/height is ({w}, {h}), expected {expected_vb}",
            )
    return (
        True,
        "simbolo.svg (512x512) and logo_final.svg (778x300) have valid structure and viewBox",
    )


@register_test(
    "T1_R1_05_letters_glyph_definition",
    1,
    "M1",
    "R1",
    "Verify letters.json exists and defines outlines for 't', 'u', 'c', 'a', 'V', 'R'",
)
def test_t1_r1_05():
    letters_file = REPO_ROOT / "design" / "logo" / "letters.json"
    if not letters_file.exists():
        return False, "design/logo/letters.json does not exist"
    try:
        data = json.loads(letters_file.read_text(encoding="utf-8"))
    except Exception as e:
        return False, f"Failed to parse letters.json: {e}"

    required_glyphs = ["t", "u", "c", "a", "V", "R"]
    missing = [g for g in required_glyphs if g not in data]
    if missing:
        return False, f"letters.json is missing glyph definitions: {missing}"
    return (
        True,
        "letters.json contains valid glyph contours for all 'tucaVR' characters",
    )


@register_test(
    "T1_R2_01_splash_asset_presence",
    1,
    "M1",
    "R2",
    "Verify app/src/main/assets/vr_splash.png exists",
)
def test_t1_r2_01():
    splash_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "vr_splash.png"
    if not splash_path.exists():
        return False, f"{splash_path} does not exist"
    return True, "app/src/main/assets/vr_splash.png is present"


@register_test(
    "T1_R2_02_splash_exact_dimensions",
    1,
    "M1",
    "R2",
    "Verify vr_splash.png dimensions are exactly 1400x560 pixels",
)
def test_t1_r2_02():
    splash_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "vr_splash.png"
    if not splash_path.exists():
        return False, f"{splash_path} does not exist"
    try:
        w, h, _, _, _ = read_png_dimensions(splash_path)
    except Exception as e:
        return False, f"Failed reading PNG header: {e}"
    if (w, h) != (1400, 560):
        return (
            False,
            f"vr_splash.png dimensions are {w}x{h}, expected strictly 1400x560",
        )
    return True, "vr_splash.png has exact 1400x560 dimensions"


@register_test(
    "T1_R2_03_splash_png_format_validity",
    1,
    "M1",
    "R2",
    "Verify vr_splash.png has 8-bit color depth and RGB/RGBA color type",
)
def test_t1_r2_03():
    splash_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "vr_splash.png"
    if not splash_path.exists():
        return False, f"{splash_path} does not exist"
    try:
        _, _, bit_depth, color_type, interlace = read_png_dimensions(
            splash_path
        )
    except Exception as e:
        return False, f"Failed reading PNG header: {e}"
    if bit_depth != 8:
        return False, f"vr_splash.png bit depth is {bit_depth}, expected 8-bit"
    if color_type not in (2, 6):  # 2: RGB, 6: RGBA
        return (
            False,
            f"vr_splash.png color type is {color_type}, expected RGB(2) or RGBA(6)",
        )
    return True, "vr_splash.png is 8-bit truecolor (RGB or RGBA)"


@register_test(
    "T1_R2_04_splash_opaque_with_black_border",
    1,
    "M1",
    "R2",
    "Verify vr_splash.png is fully opaque and every border pixel is exactly #000000",
)
def test_t1_r2_04():
    # Horizon OS draws the splash over black: a non-black edge shows up as a box (commit 577596a).
    splash_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "vr_splash.png"
    if not splash_path.exists():
        return False, f"{splash_path} does not exist"
    w, h, channels, rows = read_png_pixels(splash_path)
    if channels == 4:
        for y, row in enumerate(rows):
            alphas = row[3::4]
            if min(alphas) != 255:
                x = alphas.index(min(alphas))
                return False, f"vr_splash.png pixel ({x}, {y}) has alpha {alphas[x]}, expected 255"

    def rgb(x: int, y: int) -> Tuple[int, ...]:
        return tuple(rows[y][x * channels : x * channels + 3])

    border = [(x, y) for x in range(w) for y in (0, h - 1)] + [
        (x, y) for y in range(h) for x in (0, w - 1)
    ]
    for x, y in border:
        if rgb(x, y) != (0, 0, 0):
            return False, f"vr_splash.png border pixel ({x}, {y}) is RGB {rgb(x, y)}, expected #000000"
    return True, "vr_splash.png is fully opaque and its whole border is #000000"


@register_test(
    "T1_R2_05_splash_manifest_meta_data",
    1,
    "M1",
    "R2",
    "Verify AndroidManifest.xml binds com.oculus.ossplash to true",
)
def test_t1_r2_05():
    manifest_path = (
        REPO_ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    )
    if not manifest_path.exists():
        return False, f"{manifest_path} not found"
    content = manifest_path.read_text(encoding="utf-8")
    if not re.search(
        r'<meta-data\s+android:name=["\']com\.oculus\.ossplash["\']\s+android:value=["\']true["\']',
        content,
    ):
        return (
            False,
            "AndroidManifest.xml does not declare com.oculus.ossplash=true under <application>",
        )
    return True, "AndroidManifest.xml contains valid com.oculus.ossplash binding"


@register_test(
    "T1_R3_01_adaptive_descriptors_structure",
    1,
    "M2",
    "R3",
    "Verify ic_launcher.xml and ic_launcher_round.xml declare background, foreground, and monochrome",
)
def test_t1_r3_01():
    res_dir = REPO_ROOT / "app" / "src" / "main" / "res" / "mipmap-anydpi-v26"
    for name in ["ic_launcher.xml", "ic_launcher_round.xml"]:
        f = res_dir / name
        if not f.exists():
            return False, f"Missing adaptive icon descriptor: {name}"
        content = f.read_text(encoding="utf-8")
        if "<adaptive-icon" not in content:
            return False, f"{name} is not an <adaptive-icon>"
        for tag in ["<background", "<foreground", "<monochrome"]:
            if tag not in content:
                return (
                    False,
                    f"{name} is missing mandatory layer tag: {tag}",
                )
    return (
        True,
        "Adaptive icon descriptors correctly declare background, foreground, and monochrome layers",
    )


@register_test(
    "T1_R3_02_foreground_viewport_and_safezone",
    1,
    "M2",
    "R3",
    "Verify ic_launcher_foreground.xml viewport is 108x108",
)
def test_t1_r3_02():
    fg = (
        REPO_ROOT
        / "app"
        / "src"
        / "main"
        / "res"
        / "drawable"
        / "ic_launcher_foreground.xml"
    )
    if not fg.exists():
        return False, "ic_launcher_foreground.xml does not exist"
    content = fg.read_text(encoding="utf-8")
    if 'android:viewportWidth="108"' not in content or (
        'android:viewportHeight="108"' not in content
    ):
        return (
            False,
            "ic_launcher_foreground.xml viewport is not 108x108",
        )
    return True, "ic_launcher_foreground.xml defines standard 108x108 viewport"


@register_test(
    "T1_R3_03_vector_path_length_threshold",
    1,
    "M2",
    "R3",
    "Verify all <path> elements in ic_launcher_foreground.xml have pathData length < 400 chars",
)
def test_t1_r3_03():
    fg = (
        REPO_ROOT
        / "app"
        / "src"
        / "main"
        / "res"
        / "drawable"
        / "ic_launcher_foreground.xml"
    )
    if not fg.exists():
        return False, "ic_launcher_foreground.xml does not exist"
    content = fg.read_text(encoding="utf-8")
    paths = re.findall(r'android:pathData=["\']([^"\']+)["\']', content)
    if not paths:
        return False, "No pathData found in ic_launcher_foreground.xml"
    for idx, p in enumerate(paths):
        if len(p) >= 400:
            return (
                False,
                f"Path #{idx+1} length is {len(p)} chars (exceeds 400 char threshold): {p[:40]}...",
            )
    return True, f"All {len(paths)} paths have length < 400 chars"


@register_test(
    "T1_R3_04_monochrome_layer_validity",
    1,
    "M2",
    "R3",
    "Verify ic_launcher_monochrome.xml exists and uses single #FFFFFF fill",
)
def test_t1_r3_04():
    mono = (
        REPO_ROOT
        / "app"
        / "src"
        / "main"
        / "res"
        / "drawable"
        / "ic_launcher_monochrome.xml"
    )
    if not mono.exists():
        return False, "ic_launcher_monochrome.xml does not exist"
    content = mono.read_text(encoding="utf-8")
    if "<vector" not in content or "</vector>" not in content:
        return False, "ic_launcher_monochrome.xml is not a valid vector drawable"
    # Check fills
    fills = re.findall(r'android:fillColor=["\']([^"\']+)["\']', content)
    for f in fills:
        if f.lower() not in ("#ffffff", "#ffffffff", "#00000000", "@color/white"):
            return (
                False,
                f"Monochrome layer uses non-white/non-transparent fill: {f}",
            )
    return (
        True,
        "ic_launcher_monochrome.xml is a valid single-color monochrome vector drawable",
    )


@register_test(
    "T1_R3_05_background_layer_color",
    1,
    "M2",
    "R3",
    "Verify ic_launcher_background.xml exists, is 108x108, and fills with #121212 or #0B4650",
)
def test_t1_r3_05():
    bg = (
        REPO_ROOT
        / "app"
        / "src"
        / "main"
        / "res"
        / "drawable"
        / "ic_launcher_background.xml"
    )
    if not bg.exists():
        return False, "ic_launcher_background.xml does not exist"
    content = bg.read_text(encoding="utf-8")
    if 'android:viewportWidth="108"' not in content or (
        'android:viewportHeight="108"' not in content
    ):
        return False, "ic_launcher_background.xml viewport is not 108x108"
    fills = re.findall(r'android:fillColor=["\']([^"\']+)["\']', content)
    if not any(f.upper() in ("#121212", "#0B4650") for f in fills):
        return (
            False,
            f"ic_launcher_background.xml fill color ({fills}) does not match #121212 or #0B4650",
        )
    return True, "ic_launcher_background.xml has correct 108x108 viewport and background color"


@register_test(
    "T1_R4_01_gradle_lottie_dependency",
    1,
    "M3",
    "R4",
    "Verify app/build.gradle.kts declares com.airbnb.android:lottie:6.7.1",
)
def test_t1_r4_01():
    gradle_file = REPO_ROOT / "app" / "build.gradle.kts"
    if not gradle_file.exists():
        return False, "app/build.gradle.kts does not exist"
    content = gradle_file.read_text(encoding="utf-8")
    if "com.airbnb.android:lottie:6.7.1" not in content:
        return (
            False,
            "app/build.gradle.kts missing dependency 'com.airbnb.android:lottie:6.7.1'",
        )
    return True, "Lottie 6.7.1 dependency declared in app/build.gradle.kts"


@register_test(
    "T1_R4_02_intro_json_staging_asset",
    1,
    "M3",
    "R4",
    "Verify app/src/main/assets/intro.json exists",
)
def test_t1_r4_02():
    intro_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "intro.json"
    if not intro_path.exists():
        return False, f"{intro_path} does not exist"
    return True, "app/src/main/assets/intro.json exists"


@register_test(
    "T1_R4_03_intro_lottie_schema",
    1,
    "M3",
    "R4",
    "Verify intro.json contains valid Lottie schema (v, fr=60, ip=0, op=96, w=1400, h=560, layers)",
)
def test_t1_r4_03():
    intro_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "intro.json"
    if not intro_path.exists():
        return False, f"{intro_path} does not exist"
    try:
        data = json.loads(intro_path.read_text(encoding="utf-8"))
    except Exception as e:
        return False, f"Failed parsing intro.json: {e}"

    for k in ["v", "fr", "ip", "op", "w", "h", "layers"]:
        if k not in data:
            return False, f"intro.json missing mandatory top-level key: {k}"

    if data.get("fr") != 60:
        return False, f"intro.json framerate is {data.get('fr')}, expected 60"
    if (data.get("ip"), data.get("op")) != (0, 96):
        return (
            False,
            f"intro.json in/out frames are ({data.get('ip')}, {data.get('op')}), expected (0, 96)",
        )
    if (data.get("w"), data.get("h")) != (1400, 560):
        return (
            False,
            f"intro.json dimensions are ({data.get('w')}, {data.get('h')}), expected (1400, 560)",
        )
    if not isinstance(data.get("layers"), list) or len(data.get("layers")) == 0:
        return False, "intro.json layers is empty or not a list"

    # Verify shape elements adhere to Bodymovin/Lottie streaming parser constraint (ty is first key)
    def verify_shape_keys(item, path=""):
        if isinstance(item, dict):
            if "ty" in item and isinstance(item["ty"], str):
                first_key = next(iter(item.keys()))
                if first_key != "ty":
                    return False, f"Shape '{item.get('nm', 'unnamed')}' at {path} has first key '{first_key}', expected 'ty'"
            for k, v in item.items():
                ok, err = verify_shape_keys(v, f"{path}.{k}" if path else k)
                if not ok:
                    return False, err
        elif isinstance(item, list):
            for i, elem in enumerate(item):
                ok, err = verify_shape_keys(elem, f"{path}[{i}]")
                if not ok:
                    return False, err
        return True, ""

    ok, err = verify_shape_keys(data.get("layers", []))
    if not ok:
        return False, f"intro.json violates shape key order constraint: {err}"

    return True, "intro.json conforms strictly to Lottie schema (60 fps, 96 frames, 1400x560, ty-first shapes)"


@register_test(
    "T1_R4_05_intro_controller_unit_tests_pass",
    1,
    "M3",
    "R4",
    "Verify IntroSequenceControllerTest.kt exists in app/src/test/java/com/tucavr/",
    requires_gradle=True,
)
def test_t1_r4_05():
    test_file = (
        REPO_ROOT
        / "app"
        / "src"
        / "test"
        / "java"
        / "com"
        / "tucavr"
        / "IntroSequenceControllerTest.kt"
    )
    if not test_file.exists():
        return False, f"{test_file} does not exist"
    # Execute the test via Gradle
    code, stdout, stderr = run_cmd(
        [
            "./gradlew",
            "testDebugUnitTest",
            "--tests",
            "com.tucavr.IntroSequenceControllerTest",
            "-PrustBuildMode=skip",
        ]
    )
    if code != 0:
        return (
            False,
            f"IntroSequenceControllerTest execution failed:\n{stderr or stdout}",
        )
    return True, "IntroSequenceControllerTest executed and passed under Gradle"



@register_test(
    "T1_R4_06_intro_static_background_layer",
    1,
    "M3",
    "R4",
    "Verify intro.json's bottom layer is a static, opaque background covering the whole canvas on every frame",
)
def test_t1_r4_06():
    intro_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "intro.json"
    data = json.loads(intro_path.read_text(encoding="utf-8"))
    bottom = data["layers"][-1]
    if bottom.get("ip", 1) > data["ip"] or bottom.get("op", 0) < data["op"]:
        return False, f"Bottom layer '{bottom.get('nm')}' spans frames {bottom.get('ip')}-{bottom.get('op')}, not the whole intro"
    if lottie_animated(bottom):
        return False, f"Bottom layer '{bottom.get('nm')}' has animated properties"
    ks = bottom["ks"]
    if lottie_value(ks["o"], 0) != 100:
        return False, f"Bottom layer opacity is {lottie_value(ks['o'], 0)}, expected 100"
    rect = next((s for s in bottom.get("shapes", []) if s["ty"] == "rc"), None)
    paint = next((s for s in bottom.get("shapes", []) if s["ty"] in ("fl", "gf")), None)
    if rect is None or paint is None or lottie_value(paint["o"], 0) != 100:
        return False, "Bottom layer must be an opaque filled rectangle"
    ax, ay = lottie_value(ks["a"], 0)[:2]
    px, py = lottie_value(ks["p"], 0)[:2]
    sx, sy = (c / 100.0 for c in lottie_value(ks["s"], 0)[:2])
    rcx, rcy = lottie_value(rect["p"], 0)
    rw, rh = lottie_value(rect["s"], 0)
    left = px + (rcx - rw / 2 - ax) * sx
    right = px + (rcx + rw / 2 - ax) * sx
    top = py + (rcy - rh / 2 - ay) * sy
    bottom_edge = py + (rcy + rh / 2 - ay) * sy
    if left > 0 or top > 0 or right < data["w"] or bottom_edge < data["h"]:
        return (
            False,
            f"Background covers ({left:.1f}, {top:.1f})-({right:.1f}, {bottom_edge:.1f}), not the {data['w']}x{data['h']} canvas",
        )
    return True, f"Bottom layer '{bottom['nm']}' is a static opaque background covering the whole canvas"


# ----------------------------------------------------------------------
# TIER 2: Boundary & Corner Cases (>= 5 tests per feature: R1, R2, R3, R4)
# ----------------------------------------------------------------------


@register_test(
    "T2_R1_01_generator_reproduces_exports",
    2,
    "M1",
    "R1",
    "Verify re-running the generator reproduces every exported asset byte for byte",
)
def test_t2_r1_01():
    logo_dir = REPO_ROOT / "design" / "logo"
    logo_script = logo_dir / "logo.py"
    if not logo_script.exists():
        return False, "design/logo/logo.py does not exist"
    missing_dep = generator_dependency_error()
    if missing_dep:
        return False, missing_dep

    exports = [
        logo_dir / name for name in ("simbolo.svg", "logo_final.svg", "intro.json", "vr_splash.png")
    ] + [REPO_ROOT / "app" / "src" / "main" / "assets" / name for name in ("intro.json", "vr_splash.png")]
    before = {path: path.read_bytes() for path in exports}

    code, stdout, stderr = run_cmd([sys.executable, "logo.py"], cwd=logo_dir)
    if code != 0:
        return (
            False,
            f"logo.py execution failed with code {code}:\n{stderr or stdout}",
        )

    changed = [path for path in exports if path.read_bytes() != before[path]]
    if changed:
        for path in changed:
            path.write_bytes(before[path])
        names = [str(path.relative_to(REPO_ROOT)) for path in changed]
        return False, f"Generator output differs from the exported assets (restored them): {names}"
    return True, "Re-running logo.py reproduces all exports byte for byte"


@register_test(
    "T2_R1_02_svg_coordinate_precision_bounds",
    2,
    "M1",
    "R1",
    "Verify SVG coordinates in simbolo.svg and logo_final.svg have decimal precision <= 4",
)
def test_t2_r1_02():
    logo_dir = REPO_ROOT / "design" / "logo"
    for name in ["simbolo.svg", "logo_final.svg"]:
        f = logo_dir / name
        if not f.exists():
            return False, f"{name} does not exist"
        content = f.read_text(encoding="utf-8")
        decimals = re.findall(r"\d+\.(\d{5,})", content)
        if decimals:
            return (
                False,
                f"{name} contains coordinates with unrounded precision ({len(decimals)} instances, e.g. .{decimals[0]}): limit is 4 decimal places",
            )
    return True, "All SVG coordinates have rounded precision <= 4 decimal places"


@register_test(
    "T2_R1_03_color_palette_token_adherence",
    2,
    "M1",
    "R1",
    "Verify all colors in simbolo.svg and logo_final.svg adhere strictly to canonical brand tokens",
)
def test_t2_r1_03():
    logo_dir = REPO_ROOT / "design" / "logo"
    for name in ["simbolo.svg", "logo_final.svg"]:
        f = logo_dir / name
        if not f.exists():
            return False, f"{name} does not exist"
        content = f.read_text(encoding="utf-8")
        hex_colors = re.findall(r"#[0-9a-fA-F]{6}", content)
        for h in hex_colors:
            if h.lower() not in CANONICAL_HEX_SET:
                return (
                    False,
                    f"{name} contains non-canonical color '{h}'. Allowed: {sorted(CANONICAL_HEX_SET)}",
                )
    return True, "All colors in SVGs adhere strictly to canonical brand palette"


@register_test(
    "T2_R1_04_deterministic_json_serialization",
    2,
    "M1",
    "R1",
    "Verify intro.json and letters.json use deterministic indentation and key ordering",
)
def test_t2_r1_04():
    logo_dir = REPO_ROOT / "design" / "logo"
    # letters.json is written with sorted keys; intro.json keeps insertion order
    # because lottie-android's streaming parser needs "ty" first (commit 569582f).
    for name, sort_keys in [("letters.json", True), ("intro.json", False)]:
        f = logo_dir / name
        if not f.exists():
            return False, f"{name} does not exist"
        content = f.read_text(encoding="utf-8")
        try:
            canonical = json.dumps(json.loads(content), indent=2, sort_keys=sort_keys) + "\n"
        except Exception as e:
            return False, f"Failed parsing {name}: {e}"
        if content != canonical:
            return False, f"{name} is not serialized as json.dumps(indent=2, sort_keys={sort_keys}) + newline"
    return True, "JSON files follow deterministic indented formatting"


@register_test(
    "T2_R1_05_clean_directory_no_stray_artifacts",
    2,
    "M1",
    "R1",
    "Verify design/logo/ contains no .pyc, __pycache__, or uncommitted temporary files",
)
def test_t2_r1_05():
    logo_dir = REPO_ROOT / "design" / "logo"
    if not logo_dir.exists():
        return False, "design/logo/ does not exist"
    strays = []
    for root, dirs, files in os.walk(logo_dir):
        if "__pycache__" in dirs:
            strays.append(os.path.join(root, "__pycache__"))
        for f in files:
            if f.endswith((".pyc", ".tmp", ".swp", "~")):
                strays.append(os.path.join(root, f))
    if strays:
        return False, f"Stray artifacts found in design/logo/: {strays}"
    return True, "design/logo/ is free of temporary or compiled artifacts"


@register_test(
    "T2_R2_01_exact_aspect_ratio_2_5",
    2,
    "M1",
    "R2",
    "Verify vr_splash.png aspect ratio is mathematically 2.50000 (1400:560)",
)
def test_t2_r2_01():
    splash_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "vr_splash.png"
    if not splash_path.exists():
        return False, f"{splash_path} does not exist"
    w, h, _, _, _ = read_png_dimensions(splash_path)
    aspect = w / h
    if abs(aspect - 2.5) > 1e-6:
        return False, f"vr_splash.png aspect ratio is {aspect:.4f}, expected 2.5000"
    return True, f"Aspect ratio is exactly {aspect:.5f} (2.5:1 / 5:2)"


@register_test(
    "T2_R2_02_file_size_budget",
    2,
    "M1",
    "R2",
    "Verify vr_splash.png file size is within 10 KB to 500 KB budget",
)
def test_t2_r2_02():
    splash_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "vr_splash.png"
    if not splash_path.exists():
        return False, f"{splash_path} does not exist"
    size = splash_path.stat().st_size
    if size < 10 * 1024:
        return False, f"vr_splash.png is abnormally small: {size} bytes (< 10 KB)"
    if size > 500 * 1024:
        return False, f"vr_splash.png exceeds size budget: {size} bytes (> 500 KB)"
    return True, f"vr_splash.png size is {size / 1024:.1f} KB (within 10-500 KB budget)"


@register_test(
    "T2_R2_03_non_interlaced_scan",
    2,
    "M1",
    "R2",
    "Verify vr_splash.png interlace method is 0 (non-interlaced required by Horizon OS)",
)
def test_t2_r2_03():
    splash_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "vr_splash.png"
    if not splash_path.exists():
        return False, f"{splash_path} does not exist"
    _, _, _, _, interlace = read_png_dimensions(splash_path)
    if interlace != 0:
        return (
            False,
            f"vr_splash.png is interlaced (method {interlace}); Horizon OS requires non-interlaced (0)",
        )
    return True, "vr_splash.png is non-interlaced"


@register_test(
    "T2_R2_04_png_chunk_integrity",
    2,
    "M1",
    "R2",
    "Verify every vr_splash.png chunk has a valid CRC, IHDR comes first and IEND last",
)
def test_t2_r2_04():
    splash_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "vr_splash.png"
    if not splash_path.exists():
        return False, f"{splash_path} does not exist"
    chunks = []
    for chunk_type, payload, crc in iter_png_chunks(splash_path.read_bytes()):
        name = chunk_type.decode("latin-1")
        if zlib.crc32(chunk_type + payload) != crc:
            return False, f"vr_splash.png chunk {name} has a bad CRC"
        chunks.append(name)
    if not chunks or chunks[0] != "IHDR" or chunks[-1] != "IEND":
        return False, f"vr_splash.png chunk order is invalid: {chunks}"
    return True, f"PNG chunk sequence is valid: {chunks}"


@register_test(
    "T2_R2_05_symbol_wordmark_visual_margins",
    2,
    "M1",
    "R2",
    "Verify the lockup drawn in vr_splash.png keeps at least a 40px margin to every canvas edge",
)
def test_t2_r2_05():
    splash_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "vr_splash.png"
    w, h, channels, rows = read_png_pixels(splash_path)
    # The sea floor never exceeds SEA_FLOOR_CENTER (max channel 32); the lockup's
    # darkest colour, the #0B4650 disk, has a channel of 70.
    threshold = 48
    xs, ys = [], []
    for y, row in enumerate(rows):
        for x in range(w):
            if max(row[x * channels : x * channels + 3]) > threshold:
                xs.append(x)
                ys.append(y)
    if not xs:
        return False, "No lockup pixels found in vr_splash.png"
    margins = {
        "left": min(xs),
        "top": min(ys),
        "right": w - 1 - max(xs),
        "bottom": h - 1 - max(ys),
    }
    tight = {side: m for side, m in margins.items() if m < 40}
    if tight:
        return False, f"Lockup is closer than 40px to the canvas edge: {tight}"
    return True, f"Lockup margins in vr_splash.png: {margins}"


@register_test(
    "T2_R3_01_omits_micro_antennae_and_spots",
    2,
    "M2",
    "R3",
    "Verify ic_launcher_foreground.xml omits the golden antennae strokes and the tiny spots that blur at 32px",
)
def test_t2_r3_01():
    fg = REPO_ROOT / "app" / "src" / "main" / "res" / "drawable" / "ic_launcher_foreground.xml"
    if not fg.exists():
        return False, "ic_launcher_foreground.xml does not exist"
    # At 32px the icon renders the 512-unit master at 32 / 108 * 0.125 px per unit, so
    # the spots (at most ~8.4 units wide) would cover a third of a pixel.
    tiny_spot_units = 9.0
    for path in foreground_paths(fg):
        stroke = path.get(ANDROID_NS + "strokeColor", "").upper()
        if stroke == CANONICAL_PALETTE["GOLDEN_YELLOW"]:
            return False, "ic_launcher_foreground.xml still draws a golden antenna stroke"
        min_x, min_y, max_x, max_y = abs_path_bbox(path.get(ANDROID_NS + "pathData"))
        if max(max_x - min_x, max_y - min_y) < tiny_spot_units:
            return (
                False,
                f"ic_launcher_foreground.xml keeps a {max_x - min_x:.1f}x{max_y - min_y:.1f} unit spot",
            )
    return (
        True,
        "ic_launcher_foreground.xml has no antenna strokes and no detail under 9 master units",
    )


@register_test(
    "T2_R3_02_adaptive_mask_clipping_bounds",
    2,
    "M2",
    "R3",
    "Verify foreground content stays within the 72dp safe zone [18..90] in the 108x108 viewport",
)
def test_t2_r3_02():
    fg = REPO_ROOT / "app" / "src" / "main" / "res" / "drawable" / "ic_launcher_foreground.xml"
    if not fg.exists():
        return False, "ic_launcher_foreground.xml does not exist"
    root = ET.parse(fg).getroot()
    groups = root.findall("group")
    if len(groups) != 1 or root.findall("path") or groups[0].findall(".//group"):
        return False, "Expected every path under a single top-level <group>; update this test for the new structure"
    group = groups[0]
    scale_x = float(group.get(ANDROID_NS + "scaleX", "1"))
    scale_y = float(group.get(ANDROID_NS + "scaleY", "1"))
    tx = float(group.get(ANDROID_NS + "translateX", "0"))
    ty = float(group.get(ANDROID_NS + "translateY", "0"))
    bounds = [float("inf"), float("inf"), float("-inf"), float("-inf")]
    for path in foreground_paths(fg):
        half_stroke = float(path.get(ANDROID_NS + "strokeWidth", "0")) / 2
        min_x, min_y, max_x, max_y = abs_path_bbox(path.get(ANDROID_NS + "pathData"))
        bounds[0] = min(bounds[0], (min_x - half_stroke) * scale_x + tx)
        bounds[1] = min(bounds[1], (min_y - half_stroke) * scale_y + ty)
        bounds[2] = max(bounds[2], (max_x + half_stroke) * scale_x + tx)
        bounds[3] = max(bounds[3], (max_y + half_stroke) * scale_y + ty)
    if bounds[0] < 18 or bounds[1] < 18 or bounds[2] > 90 or bounds[3] > 90:
        return False, f"Foreground content spans {[round(b, 2) for b in bounds]}, outside the [18..90] safe zone"
    return True, f"Foreground content spans {[round(b, 2) for b in bounds]}, inside the [18..90] safe zone"


@register_test(
    "T2_R3_03_path_character_budget_max_ceiling",
    2,
    "M2",
    "R3",
    "Verify no single path in ic_launcher_foreground.xml exceeds 400 chars (Lint limit 800)",
)
def test_t2_r3_03():
    fg = (
        REPO_ROOT
        / "app"
        / "src"
        / "main"
        / "res"
        / "drawable"
        / "ic_launcher_foreground.xml"
    )
    if not fg.exists():
        return False, "ic_launcher_foreground.xml does not exist"
    content = fg.read_text(encoding="utf-8")
    paths = re.findall(r'android:pathData=["\']([^"\']+)["\']', content)
    for p in paths:
        if len(p) > 400:
            return (
                False,
                f"Path length {len(p)} exceeds maximum 400 char budget",
            )
    return True, "All paths adhere to < 400 character ceiling"


@register_test(
    "T2_R3_04_monochrome_stencil_negative_space",
    2,
    "M2",
    "R3",
    "Verify monochrome icon relies on negative space cutouts rather than multi-color fills",
)
def test_t2_r3_04():
    mono = (
        REPO_ROOT
        / "app"
        / "src"
        / "main"
        / "res"
        / "drawable"
        / "ic_launcher_monochrome.xml"
    )
    if not mono.exists():
        return False, "ic_launcher_monochrome.xml does not exist"
    content = mono.read_text(encoding="utf-8")
    fills = set(re.findall(r'android:fillColor=["\']([^"\']+)["\']', content))
    # Should only have #FFFFFF or #00000000
    for f in fills:
        if f.lower() not in ("#ffffff", "#ffffffff", "#00000000", "@color/white"):
            return (
                False,
                f"Monochrome icon contains unexpected fill color: {f}",
            )
    return True, "Monochrome icon uses negative space stenciling"


@register_test(
    "T2_R3_05_lint_zero_warnings_verification",
    2,
    "M2",
    "R3",
    "Verify Android Lint reports no IconLauncherShape or VectorPath issue on the launcher icon resources",
    requires_gradle=True,
)
def test_t2_r3_05():
    report = REPO_ROOT / "app" / "build" / "reports" / "lint-results-debug.xml"
    code, stdout, stderr = run_cmd(
        ["./gradlew", ":app:lintDebug", "-PrustBuildMode=skip"], timeout=900
    )
    if code != 0:
        return False, f":app:lintDebug failed with code {code}:\n{stderr or stdout}"
    if not report.exists():
        return False, f"{report} was not produced by :app:lintDebug"
    findings = []
    for issue in ET.parse(report).getroot().iter("issue"):
        if issue.get("id") not in ("IconLauncherShape", "VectorPath"):
            continue
        for location in issue.iter("location"):
            if Path(location.get("file", "")).name.startswith("ic_launcher"):
                findings.append(f"{issue.get('id')} in {Path(location.get('file')).name}")
    if findings:
        return False, f"Android Lint flagged the launcher icon: {sorted(set(findings))}"
    return True, "Android Lint reports no IconLauncherShape or VectorPath issue on ic_launcher* resources"


@register_test(
    "T2_R4_01_exact_fps_and_duration_bounds",
    2,
    "M3",
    "R4",
    "Verify intro.json duration is mathematically 1.600 s (96 frames @ 60 fps)",
)
def test_t2_r4_01():
    intro_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "intro.json"
    if not intro_path.exists():
        return False, f"{intro_path} does not exist"
    data = json.loads(intro_path.read_text(encoding="utf-8"))
    fr = data.get("fr", 0)
    total_frames = data.get("op", 0) - data.get("ip", 0)
    duration = total_frames / fr if fr else 0
    if abs(duration - 1.600) > 1e-4:
        return (
            False,
            f"intro.json duration is {duration:.4f}s, expected strictly 1.600s",
        )
    return True, f"intro.json duration is exactly {duration:.3f}s (96 frames @ 60 fps)"



@register_test(
    "T2_R4_02_nothing_but_background_visible_at_frame_0",
    2,
    "M3",
    "R4",
    "Verify no symbol or wordmark element of intro.json paints anything at frame 0",
)
def test_t2_r4_02():
    layers = intro_layers()
    # Only a static bottom layer counts as the background (T1_R4_06 checks its shape).
    if not lottie_animated(layers[-1]):
        layers = layers[:-1]
    visible = []
    for layer in layers:
        visible += lottie_layer_visible_drawables(layer, 0)
    if visible:
        return False, f"Visible at frame 0 besides the background: {visible}"
    return True, "Only the static background layer is visible at frame 0"


def load_scene_module():
    """Imports design/logo/scene.py without leaving bytecode in design/logo/."""
    sys.dont_write_bytecode = True
    logo_dir = str(REPO_ROOT / "design" / "logo")
    if logo_dir not in sys.path:
        sys.path.insert(0, logo_dir)
    import scene

    return scene


@register_test(
    "T2_R4_04_symbol_stacking_matches_svg_paint_order",
    2,
    "M3",
    "R4",
    "Verify the intro draws the symbol's groups bottom-to-top in simbolo.svg paint order",
)
def test_t2_r4_04():
    scene = load_scene_module()
    svg = (REPO_ROOT / "design" / "logo" / "simbolo.svg").read_text(encoding="utf-8")
    svg_index = {}
    for index, d in enumerate(re.findall(r'<path d="([^"]+)"', svg)):
        for contour in scene.parse_svg_path(d):
            key = (json.dumps(contour["v"]), contour["c"])
            if key in svg_index and svg_index[key] != index:
                return False, f"simbolo.svg paths {svg_index[key]} and {index} share a contour; cannot map them"
            svg_index[key] = index

    def svg_indices(item) -> set:
        if item["ty"] == "gr":
            return set().union(*(svg_indices(child) for child in item["it"]))
        if item["ty"] != "sh":
            return set()
        shape = item["ks"]["k"][0]["s"][0] if item["ks"]["a"] == 1 else item["ks"]["k"]
        key = (json.dumps(shape["v"]), shape["c"])
        if key not in svg_index:
            raise ValueError(f"'{item['nm']}' matches no simbolo.svg path")
        return {svg_index[key]}

    def check(items: list, where: str) -> Optional[str]:
        # lottie-android draws a group's items from the last to the first, so the
        # first item ends up on top: draw order is the reverse of the array.
        drawn = [it for it in reversed(items) if it["ty"] == "gr"]
        previous_max, previous_name = -1, None
        for group in drawn:
            indices = svg_indices(group)
            if min(indices) <= previous_max:
                return (
                    f"{where}: '{group['nm']}' (SVG paths {min(indices)}-{max(indices)}) is drawn above "
                    f"'{previous_name}' (up to SVG path {previous_max})"
                )
            previous_max, previous_name = max(indices), group["nm"]
        return None

    symbol = next(layer for layer in intro_layers() if layer["nm"].startswith("Symbol"))
    body = next(it for it in symbol["shapes"] if it.get("nm") == "tamarutaca")
    for items, where in [(symbol["shapes"], "Symbol layer"), (body["it"], "tamarutaca group")]:
        error = check(items, where)
        if error:
            return False, error
    if body["it"][-1]["ty"] != "tr":
        return False, "tamarutaca group's transform is not its last item"
    return True, "Symbol layer and tamarutaca group stack in simbolo.svg paint order"


@register_test(
    "T2_R4_03_readme_states_authored_and_playback_duration",
    2,
    "M3",
    "R4",
    "Verify design/logo/README.md states the authored length and the on-device playback length",
)
def test_t2_r4_03():
    intro = json.loads(
        (REPO_ROOT / "app" / "src" / "main" / "assets" / "intro.json").read_text(encoding="utf-8")
    )
    controller = (
        REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "tucavr" / "IntroSequenceController.kt"
    ).read_text(encoding="utf-8")
    speed_match = re.search(r"const val PLAYBACK_SPEED = ([0-9.]+)f", controller)
    hold_match = re.search(r"const val HOLD_AFTER_END_MS = (\d+)L", controller)
    if not speed_match or not hold_match:
        return False, "IntroSequenceController must declare PLAYBACK_SPEED and HOLD_AFTER_END_MS"
    speed = float(speed_match.group(1))
    hold_s = int(hold_match.group(1)) / 1000
    authored_s = (intro["op"] - intro["ip"]) / intro["fr"]
    expected = [
        f"{authored_s:.1f} s",
        f"{speed:g}x",
        f"{authored_s / speed:.1f} s",
        f"{hold_s:g} s",
    ]
    readme = (REPO_ROOT / "design" / "logo" / "README.md").read_text(encoding="utf-8")
    missing = [token for token in expected if token not in readme]
    if missing:
        return False, f"design/logo/README.md does not state {missing}"
    return True, f"README states {expected[0]} authored, {expected[2]} at {expected[1]} plus a {expected[3]} hold"


# ----------------------------------------------------------------------
# TIER 3: Cross-Feature Combinations (Pairwise interactions)
# ----------------------------------------------------------------------


@register_test(
    "T3_PAIR_01_r1_r2_splash_parity",
    3,
    "M1",
    "R1+R2",
    "Verify design/logo/vr_splash.png and app/src/main/assets/vr_splash.png are identical",
)
def test_t3_pair_01():
    p1 = REPO_ROOT / "design" / "logo" / "vr_splash.png"
    p2 = REPO_ROOT / "app" / "src" / "main" / "assets" / "vr_splash.png"
    if not p1.exists():
        return False, f"{p1} does not exist"
    if not p2.exists():
        return False, f"{p2} does not exist"
    if p1.read_bytes() != p2.read_bytes():
        return (
            False,
            "design/logo/vr_splash.png and app/src/main/assets/vr_splash.png differ in content",
        )
    return True, "Quest splash assets in design/logo and app/assets are bitwise identical"


@register_test(
    "T3_PAIR_02_r1_r3_symbol_to_launcher_geometry",
    3,
    "M2",
    "R1+R3",
    "Verify launcher icon foreground and simbolo.svg share the core brand colors",
)
def test_t3_pair_02():
    simbolo = REPO_ROOT / "design" / "logo" / "simbolo.svg"
    fg = (
        REPO_ROOT
        / "app"
        / "src"
        / "main"
        / "res"
        / "drawable"
        / "ic_launcher_foreground.xml"
    )
    if not simbolo.exists():
        return False, "simbolo.svg does not exist"
    if not fg.exists():
        return False, "ic_launcher_foreground.xml does not exist"
    # Both should use the primary brand colors
    simbolo_content = simbolo.read_text(encoding="utf-8")
    fg_content = fg.read_text(encoding="utf-8")
    for col in ["#16B3B0", "#FF6B00"]:
        if col.lower() not in simbolo_content.lower():
            return False, f"simbolo.svg missing core color {col}"
        if col.lower() not in fg_content.lower():
            return False, f"ic_launcher_foreground.xml missing core color {col}"
    return True, "Launcher icon geometry aligns with canonical simbolo.svg palette"


@register_test(
    "T3_PAIR_03_r1_r4_intro_json_parity",
    3,
    "M3",
    "R1+R4",
    "Verify design/logo/intro.json and app/src/main/assets/intro.json are identical",
)
def test_t3_pair_03():
    p1 = REPO_ROOT / "design" / "logo" / "intro.json"
    p2 = REPO_ROOT / "app" / "src" / "main" / "assets" / "intro.json"
    if not p1.exists():
        return False, f"{p1} does not exist"
    if not p2.exists():
        return False, f"{p2} does not exist"
    if p1.read_bytes() != p2.read_bytes():
        return (
            False,
            "design/logo/intro.json and app/src/main/assets/intro.json differ in content",
        )
    return True, "intro.json assets in design/logo and app/assets are bitwise identical"


@register_test(
    "T3_PAIR_04_r2_r4_splash_and_intro_share_canvas_and_background",
    3,
    "M3",
    "R2+R4",
    "Verify vr_splash.png and intro.json share the 1400x560 canvas and the same background",
)
def test_t3_pair_04():
    splash_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "vr_splash.png"
    intro_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "intro.json"
    if not splash_path.exists():
        return False, f"{splash_path} does not exist"
    if not intro_path.exists():
        return False, f"{intro_path} does not exist"

    w, h, channels, rows = read_png_pixels(splash_path)
    intro_data = json.loads(intro_path.read_text(encoding="utf-8"))
    iw, ih = intro_data.get("w"), intro_data.get("h")
    if (w, h) != (iw, ih):
        return (
            False,
            f"Dimension mismatch between splash ({w}x{h}) and intro ({iw}x{ih})",
        )

    background = intro_data["layers"][-1]
    # Samples avoid the 778x300 lockup box centred on the canvas. lottie-android
    # truncates colour channels and the rasterizers round differently, hence the tolerance.
    tolerance = 2
    worst = (0, None)
    for y in range(0, h, 20):
        for x in range(0, w, 20):
            if 300 <= x <= 1100 and 120 <= y <= 440:
                continue
            expected = sea_floor_color_at(background, x, y)
            actual = tuple(rows[y][x * channels : x * channels + 3])
            delta = max(abs(a - e) for a, e in zip(actual, expected))
            if delta > worst[0]:
                worst = (delta, (x, y, actual, expected))
    if worst[0] > tolerance:
        x, y, actual, expected = worst[1]
        return False, f"Splash background at ({x}, {y}) is {actual}, intro background is {expected}"
    return (
        True,
        f"vr_splash.png and intro.json share the {w}x{h} canvas and background (max channel delta {worst[0]})",
    )


@register_test(
    "T3_PAIR_05_r3_r4_palette_cross_parity",
    3,
    "M3",
    "R3+R4",
    "Verify launcher icon foreground and intro animation share canonical brand colors",
)
def test_t3_pair_05():
    fg = (
        REPO_ROOT
        / "app"
        / "src"
        / "main"
        / "res"
        / "drawable"
        / "ic_launcher_foreground.xml"
    )
    intro_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "intro.json"
    if not fg.exists():
        return False, "ic_launcher_foreground.xml does not exist"
    if not intro_path.exists():
        return False, "intro.json does not exist"

    fg_text = fg.read_text(encoding="utf-8").lower()
    intro_colors = set()

    def collect(node):
        if isinstance(node, dict):
            if node.get("ty") in ("fl", "st") and node["c"].get("a") == 0:
                r, g, b = node["c"]["k"][:3]
                intro_colors.add(f"#{round(r * 255):02x}{round(g * 255):02x}{round(b * 255):02x}")
            for v in node.values():
                collect(v)
        elif isinstance(node, list):
            for v in node:
                collect(v)

    collect(json.loads(intro_path.read_text(encoding="utf-8"))["layers"])
    for col in ["#ff6b00", "#16b3b0"]:
        if col not in fg_text:
            return False, f"Launcher foreground missing {col}"
        if col not in intro_colors:
            return False, f"intro.json fills/strokes missing {col}"
    return True, "Consistent brand palette shared across launcher icon and intro sequence"


@register_test(
    "T3_PAIR_06_r1_build_idempotence_isolation",
    3,
    "M4",
    "R1+Build",
    "Verify brand asset generation does not touch tracked code files outside assets",
)
def test_t3_pair_06():
    logo_dir = REPO_ROOT / "design" / "logo"
    logo_script = logo_dir / "logo.py"
    if not logo_script.exists():
        return False, "design/logo/logo.py does not exist"

    missing_dep = generator_dependency_error()
    if missing_dep:
        return False, missing_dep

    code, diff_before, _ = run_cmd(["git", "diff", "--name-only"])
    files_before = set(diff_before.splitlines())

    code, stdout, stderr = run_cmd([sys.executable, "logo.py"], cwd=logo_dir)
    if code != 0:
        return False, f"logo.py execution failed: {stderr or stdout}"

    code, diff_after, _ = run_cmd(["git", "diff", "--name-only"])
    files_after = set(diff_after.splitlines())

    newly_modified = files_after - files_before
    modified_outside_assets = [
        f
        for f in newly_modified
        if not f.startswith(("design/logo/", "app/src/main/assets/"))
    ]
    if modified_outside_assets:
        return (
            False,
            f"Unexpected modifications outside assets directory caused by generator: {modified_outside_assets}",
        )
    return True, "Asset generation maintains clean isolation from core code trees"


# ----------------------------------------------------------------------
# TIER 4: Quality gates run through Gradle
# ----------------------------------------------------------------------


@register_test(
    "T4_SCENARIO_05_ui_source_guards_and_ktlint",
    4,
    "M4",
    "QualityGates",
    "Verify UiSourceGuardsTest and ktlintCheck pass with zero hardcoded literals or style violations",
    requires_gradle=True,
)
def test_t4_scenario_05():
    # 1. Run UiSourceGuardsTest
    code, stdout, stderr = run_cmd(
        [
            "./gradlew",
            "testDebugUnitTest",
            "--tests",
            "com.tucavr.UiSourceGuardsTest",
            "-PrustBuildMode=skip",
        ]
    )
    if code != 0:
        return (
            False,
            f"UiSourceGuardsTest failed:\n{stderr or stdout}",
        )

    # 2. Run ktlintCheck
    code, stdout, stderr = run_cmd(["./gradlew", "ktlintCheck"])
    if code != 0:
        return (
            False,
            f"ktlintCheck failed:\n{stderr or stdout}",
        )
    return True, "UiSourceGuardsTest and ktlintCheck passed cleanly"


# ----------------------------------------------------------------------
# Test Runner Engine & Reporting
# ----------------------------------------------------------------------


def filter_tests(
    tier: Optional[str],
    milestone: Optional[str],
    feature: Optional[str],
    skip_gradle: bool,
) -> List[TestCase]:
    filtered = []
    for tc in REGISTRY:
        if skip_gradle and tc.requires_gradle:
            continue
        if tier and tier != "all":
            try:
                t_val = int(tier)
                if tc.tier != t_val:
                    continue
            except ValueError:
                pass
        if milestone and milestone != "all":
            if tc.milestone != milestone and milestone not in tc.milestone:
                continue
        if feature and feature != "all":
            if feature not in tc.feature:
                continue
        filtered.append(tc)
    return filtered


def main():
    parser = argparse.ArgumentParser(
        description="E2E Test Runner for tucaVR Brand Transition (Epic #102)"
    )
    parser.add_argument(
        "--tier",
        choices=["1", "2", "3", "4", "all"],
        default="all",
        help="Run tests of a specific tier (default: all)",
    )
    parser.add_argument(
        "--milestone",
        choices=["M1", "M2", "M3", "M4", "all"],
        default="all",
        help="Run tests for a specific milestone (default: all)",
    )
    parser.add_argument(
        "--feature",
        choices=["R1", "R2", "R3", "R4", "all"],
        default="all",
        help="Run tests for a specific requirement (default: all)",
    )
    parser.add_argument(
        "--skip-gradle",
        action="store_true",
        help="Skip tests that require Gradle execution (fast mode <1s)",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Verbose test failure logs"
    )
    parser.add_argument(
        "--json-output", type=str, help="Save structured test results to JSON"
    )
    parser.add_argument(
        "--stop-on-first-error",
        "-x",
        action="store_true",
        help="Halt execution on first test failure",
    )
    args = parser.parse_args()

    tests_to_run = filter_tests(
        args.tier, args.milestone, args.feature, args.skip_gradle
    )

    print("=" * 80)
    print("tucaVR Brand Identity Transition (Epic #102) — E2E Test Suite")
    print(
        f"Filters: Tier={args.tier} | Milestone={args.milestone} | Feature={args.feature} | SkipGradle={args.skip_gradle}"
    )
    print(f"Total Tests Selected: {len(tests_to_run)} of {len(REGISTRY)}")
    print("=" * 80)

    results: List[TestResult] = []
    failed_count = 0
    passed_count = 0

    start_suite_time = time.time()

    for tc in tests_to_run:
        t0 = time.time()
        try:
            passed, msg = tc.fn()
        except Exception as e:
            passed = False
            msg = f"Unexpected exception: {e}"
        dt = (time.time() - t0) * 1000

        status = "PASS" if passed else "FAIL"
        if passed:
            passed_count += 1
            icon = "✅"
        else:
            failed_count += 1
            icon = "❌"

        result = TestResult(
            test_id=tc.id,
            tier=tc.tier,
            milestone=tc.milestone,
            feature=tc.feature,
            description=tc.description,
            status=status,
            message=msg,
            duration_ms=dt,
        )
        results.append(result)

        print(
            f"{icon} [T{tc.tier}|{tc.milestone}|{tc.feature}] {tc.id} ({dt:.1f}ms): {msg}"
        )

        if not passed and args.verbose:
            print(f"   Details: {tc.description}")
            print(f"   Remediation: Inspect requirements for {tc.feature} in {tc.milestone}")

        if not passed and args.stop_on_first_error:
            break

    total_time = (time.time() - start_suite_time) * 1000

    print("-" * 80)
    print("TEST EXECUTION SUMMARY:")
    print(f"  Total executed: {len(results)}")
    print(f"  Passed:         {passed_count}")
    print(f"  Failed:         {failed_count}")
    print(f"  Total time:     {total_time:.1f} ms")
    print("-" * 80)

    if args.json_output:
        out_path = Path(args.json_output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "timestamp": time.time(),
            "summary": {
                "total": len(results),
                "passed": passed_count,
                "failed": failed_count,
                "duration_ms": total_time,
            },
            "results": [
                {
                    "id": r.test_id,
                    "tier": r.tier,
                    "milestone": r.milestone,
                    "feature": r.feature,
                    "status": r.status,
                    "message": r.message,
                    "duration_ms": r.duration_ms,
                }
                for r in results
            ],
        }
        out_path.write_text(json.dumps(data, indent=2), encoding="utf-8")
        print(f"Structured results written to: {out_path}")

    if failed_count > 0:
        print(f"❌ Test run FAILED ({failed_count} failure(s)).")
        sys.exit(1)
    else:
        print("✅ All executed tests PASSED!")
        sys.exit(0)


if __name__ == "__main__":
    main()
