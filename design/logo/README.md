# tucaVR Brand Asset Pipeline (`design/logo/`)

This directory contains the source generators, parametric geometric model, font extraction pipeline, and committed exported assets for the **tucaVR Brand Identity** (Epic #102 / Milestone 1).

The brand identity centers on the **tamarutaca** (peacock mantis shrimp — *Odontodactylus scyllarus*), celebrating its rapid raptorial cavitation strike (~23 m/s) and hyperspectral stereoscopic vision.

---

## 1. Directory Structure

```text
design/logo/
├── requirements.txt         # Exact Python dependency versions (cairosvg and its deps, fonttools, pillow)
├── OFL.txt                  # SIL Open Font License v1.1 text for Baloo 2
├── README.md                # Documentation and reproduction guide (this file)
├── extract_letters.py       # Font outline extraction tool for Baloo 2 ExtraBold (weight 800)
├── letters.json             # Committed glyph outlines for 'tucaVR' in Baloo 2 ExtraBold
├── scene.py                 # Single parametric geometric model (SVG & Lottie emitters)
├── logo.py                  # Primary orchestrator CLI generating SVGs, JSON, and splash PNG
├── simbolo.svg              # 512×512 master symbol
├── logo_final.svg           # 778×300 horizontal logo with #121212 background and rx=28
├── intro.json               # 1400×560 Lottie animation, sea floor background on every frame
└── vr_splash.png            # 1400×560 Meta Horizon OS system splash image, opaque sea floor background
```

Associated runtime asset targets:
- `app/src/main/assets/vr_splash.png`: Synced copy of `vr_splash.png` loaded by Meta Quest Horizon OS during cold launch via `<meta-data android:name="com.oculus.ossplash" android:value="true" />` in `AndroidManifest.xml`.
- `app/src/main/assets/intro.json`: Synced copy of `intro.json` played by `VRPresentation` once per process, after the OpenXR session gains focus.

Both assets share one opaque "sea floor" background: an elliptical radial gradient from a dark teal centre (`DEEP_TEAL` at 40 %) to pure `#000000`. It reaches black before the canvas edges, so every border pixel matches the black Horizon OS draws around the splash, and the intro panel fills its letterbox bands with the same black.

Intro timing: `intro.json` is authored as 96 frames @ 60 fps = 1.6 s. On the headset `VRPresentation` plays it at 0.35x (`IntroSequenceController.PLAYBACK_SPEED`), about 4.6 s, then holds the final frame for 2 s (`HOLD_AFTER_END_MS`) and fades to Home. With reduced motion it shows the final frame for 0.5 s and cuts to Home.

---

## 2. Single Reproduction Command

On a clean checkout, run the single command below from the repository root to install dependencies in an isolated virtual environment and regenerate all assets. The environment lives in the gitignored `build/brand-venv/`, the one `scripts/test-brand-transition.sh` uses; a venv inside `design/logo/` would trip the suite's stray-artifact check.

```bash
python3 -m venv build/brand-venv && build/brand-venv/bin/pip install -r design/logo/requirements.txt && build/brand-venv/bin/python design/logo/logo.py
```

If dependencies from `requirements.txt` are already available in your environment, run directly:

```bash
python3 design/logo/logo.py
```

Running this command reproduces `simbolo.svg`, `logo_final.svg`, `intro.json`, and `vr_splash.png` with **zero git diff** against committed files. `vr_splash.png` is rasterized by the system libcairo through `cairosvg`; the committed file was rendered with cairo 1.18.6 (Arch Linux) and reproduces byte for byte with cairo 1.18.0 (Ubuntu 24.04, as in CI). Without `cairosvg`, `logo.py` falls back to `rsvg-convert`, which produces a different PNG.

`scripts/test-brand-transition.sh` (also run by `make test` and CI) checks this reproduction, among other brand asset rules.

---

## 3. Font License Attribution

The wordmark "tucaVR" uses glyphs extracted from **Baloo 2 ExtraBold** (Weight 800), designed by Ek Type and licensed under the **SIL Open Font License, Version 1.1**.

- **Font Family:** Baloo 2
- **Designer / Copyright:** Copyright 2019 The Baloo 2 Project Authors (`https://github.com/EkType/Baloo2`)
- **License:** SIL Open Font License, Version 1.1 (`OFL.txt`)
- **Canonical Upstream Repository:** `https://github.com/google/fonts/tree/main/ofl/baloo2`

The full text of the license is included in [`OFL.txt`](OFL.txt).
