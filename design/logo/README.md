# tucaVR Brand Asset Pipeline (`design/logo/`)

This directory contains the source generators, parametric geometric model, font extraction pipeline, and committed exported assets for the **tucaVR Brand Identity** (Epic #102 / Milestone 1).

The brand identity centers on the **tamarutaca** (peacock mantis shrimp — *Odontodactylus scyllarus*), celebrating its rapid raptorial cavitation strike (~23 m/s) and hyperspectral stereoscopic vision.

---

## 1. Directory Structure

```text
design/logo/
├── requirements.txt         # Pinned Python dependencies (fonttools, cairosvg, pillow)
├── OFL.txt                  # SIL Open Font License v1.1 text for Baloo 2
├── README.md                # Documentation and reproduction guide (this file)
├── extract_letters.py       # Font outline extraction tool for Baloo 2 ExtraBold (weight 800)
├── letters.json             # Committed glyph outlines for 'tucaVR' in Baloo 2 ExtraBold
├── scene.py                 # Single parametric geometric model (SVG & Lottie emitters)
├── logo.py                  # Primary orchestrator CLI generating SVGs, JSON, and splash PNG
├── simbolo.svg              # 512×512 master symbol
├── logo_final.svg           # 778×300 horizontal logo with #121212 background and rx=28
├── intro.json               # 1400×560 Lottie animation (96 frames @ 60 fps / 1.6 s)
└── vr_splash.png            # 1400×560 Meta Horizon OS system splash image, transparent background
```

Associated runtime asset targets:
- `app/src/main/assets/vr_splash.png`: Synced copy of `vr_splash.png` loaded by Meta Quest Horizon OS during cold launch via `<meta-data android:name="com.oculus.ossplash" android:value="true" />` in `AndroidManifest.xml`.

---

## 2. Single Reproduction Command

On a clean checkout, run the single command below to install dependencies in an isolated virtual environment and regenerate all assets:

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt && .venv/bin/python logo.py
```

If dependencies from `requirements.txt` are already available in your environment, run directly:

```bash
python3 logo.py
```

Running this command reproduces `simbolo.svg`, `logo_final.svg`, `intro.json`, and `vr_splash.png` with **zero git diff** against committed files.

---

## 3. Font License Attribution

The wordmark "tucaVR" uses glyphs extracted from **Baloo 2 ExtraBold** (Weight 800), designed by Ek Type and licensed under the **SIL Open Font License, Version 1.1**.

- **Font Family:** Baloo 2
- **Designer / Copyright:** Copyright 2019 The Baloo 2 Project Authors (`https://github.com/EkType/Baloo2`)
- **License:** SIL Open Font License, Version 1.1 (`OFL.txt`)
- **Canonical Upstream Repository:** `https://github.com/google/fonts/tree/main/ofl/baloo2`

The full text of the license is included in [`OFL.txt`](OFL.txt).
