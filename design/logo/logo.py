#!/usr/bin/env python3
import sys

sys.dont_write_bytecode = True
"""
Brand Asset Orchestrator CLI for tucaVR (Epic #102 / Milestone 1).
Generates static vector assets (simbolo.svg, logo_final.svg),
animated Lottie intro (intro.json), and system splash (vr_splash.png)
from the unified parametric geometric model in scene.py.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys

# Ensure local imports work regardless of cwd
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

import scene  # noqa: E402


def load_letters_data(letters_path: str = None) -> dict:
    if not letters_path:
        letters_path = os.path.join(SCRIPT_DIR, "letters.json")

    if not os.path.exists(letters_path):
        print(f"[logo.py] letters.json missing at {letters_path}, extracting...", file=sys.stderr)
        import extract_letters
        data = extract_letters.extract_letters()
        with open(letters_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, sort_keys=True)
            f.write("\n")
        return data

    with open(letters_path, "r", encoding="utf-8") as f:
        return json.load(f)


def render_svg_to_png(svg_content: str, output_path: str, width: int = 1400, height: int = 560):
    """
    Renders SVG content to PNG at exact dimensions.
    Prefers cairosvg (installed via requirements.txt).
    Falls back to rsvg-convert if cairosvg is not present.
    """
    svg_bytes = svg_content.encode("utf-8")
    png_bytes = None

    try:
        import cairosvg
        png_bytes = cairosvg.svg2png(
            bytestring=svg_bytes,
            output_width=width,
            output_height=height,
        )
    except ImportError:
        pass

    if png_bytes is None:
        # Fallback to system rsvg-convert
        try:
            p = subprocess.run(
                ["rsvg-convert", "-w", str(width), "-h", str(height)],
                input=svg_bytes,
                capture_output=True,
                check=True,
            )
            png_bytes = p.stdout
        except (subprocess.SubprocessError, FileNotFoundError) as e:
            raise RuntimeError(
                f"Failed to render PNG: neither cairosvg nor rsvg-convert is available. ({e})"
            )

    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(png_bytes)


def main():
    parser = argparse.ArgumentParser(description="Generate tucaVR brand assets from parametric geometry.")
    parser.add_argument(
        "--output-dir",
        default=SCRIPT_DIR,
        help="Directory to write generated assets (default: design/logo/)",
    )
    parser.add_argument(
        "--assets-dir",
        default=os.path.join(PROJECT_ROOT, "app", "src", "main", "assets"),
        help="Target app assets directory (default: app/src/main/assets/)",
    )
    args = parser.parse_args()

    output_dir = os.path.abspath(args.output_dir)
    assets_dir = os.path.abspath(args.assets_dir)
    os.makedirs(output_dir, exist_ok=True)

    letters_data = load_letters_data()

    print("[1/4] Generating 512x512 master simbolo.svg...")
    simbolo_svg = scene.build_simbolo_svg(pose="rest")
    simbolo_path = os.path.join(output_dir, "simbolo.svg")
    with open(simbolo_path, "w", encoding="utf-8") as f:
        f.write(simbolo_svg)

    print("[2/4] Generating 778x300 horizontal logo_final.svg...")
    logo_svg = scene.build_logo_final_svg(letters_data)
    logo_path = os.path.join(output_dir, "logo_final.svg")
    with open(logo_path, "w", encoding="utf-8") as f:
        f.write(logo_svg)

    print("[3/4] Generating 1400x560 Lottie intro.json (96 frames @ 60fps)...")
    lottie_data = scene.build_lottie_animation(letters_data)
    intro_path = os.path.join(output_dir, "intro.json")
    with open(intro_path, "w", encoding="utf-8") as f:
        json.dump(lottie_data, f, indent=2)
        f.write("\n")

    print("[4/4] Rendering 1400x560 Horizon OS system splash vr_splash.png...")
    splash_svg = scene.build_splash_svg(letters_data)
    splash_png_path = os.path.join(output_dir, "vr_splash.png")
    render_svg_to_png(splash_svg, splash_png_path, width=1400, height=560)

    # Sync assets to app/src/main/assets/
    if os.path.exists(assets_dir):
        app_splash_path = os.path.join(assets_dir, "vr_splash.png")
        shutil.copyfile(splash_png_path, app_splash_path)
        print(f"      Copied vr_splash.png to {app_splash_path}")
        app_intro_path = os.path.join(assets_dir, "intro.json")
        shutil.copyfile(intro_path, app_intro_path)
        print(f"      Copied intro.json to {app_intro_path}")

    print("\n✓ Brand assets successfully generated:")
    print(f"  - {simbolo_path}")
    print(f"  - {logo_path}")
    print(f"  - {intro_path}")
    print(f"  - {splash_png_path}")
    if os.path.exists(assets_dir):
        print(f"  - {os.path.join(assets_dir, 'vr_splash.png')}")
        print(f"  - {os.path.join(assets_dir, 'intro.json')}")


if __name__ == "__main__":
    main()
