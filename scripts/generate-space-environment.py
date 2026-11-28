#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate-space-environment.py — High-fidelity Cosmic Space environment generator for tucaVR.

Produces:
- app/src/main/assets/environments/space/model.glb   (512x512 baked-relief PBR orbital deck, < 8k tris, 0 collisions)
- app/src/main/assets/environments/space/skybox.png  (4096x2048 360 equirectangular deep space + ringed gas giant)
- Optionally regenerates ambient.ogg when invoked with --regen-audio.
"""

import math
import os
import subprocess
import sys
import time
import wave
import numpy as np
from PIL import Image

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from env_glb_builder import (
    CollisionChecker,
    GlbSceneBuilder,
    MaterialSpec,
    MeshPart,
    make_box,
    make_cylinder,
    make_flat_ring,
    make_profile_ring,
    make_rounded_box,
    make_solid_sector,
    tex_brushed_alloy,
    tex_carbon_deck,
)

BASE_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
SPACE_DIR = os.path.join(BASE_DIR, "app", "src", "main", "assets", "environments", "space")
MODEL_OUT = os.path.join(SPACE_DIR, "model.glb")
SKYBOX_OUT = os.path.join(SPACE_DIR, "skybox.png")
AMBIENT_OUT = os.path.join(SPACE_DIR, "ambient.ogg")


# ==============================================================================
# 1. 4K COSMIC SKYBOX GENERATOR (4096x2048 Equirectangular PNG, seamless 360)
# ==============================================================================
def _spherical_fbm(X: np.ndarray, Y: np.ndarray, Z: np.ndarray, seed: int, freq_scale: float, n_waves: int) -> np.ndarray:
    """Seamless 3D directional-wave noise evaluated directly on the unit sphere (zero seam at ±180°)."""
    rng = np.random.default_rng(seed)
    W = rng.normal(0.0, freq_scale, size=(3, n_waves)).astype(np.float32)
    phases = rng.uniform(0.0, 2.0 * math.pi, size=(n_waves,)).astype(np.float32)
    h, w = X.shape
    out = np.zeros((h, w), dtype=np.float32)
    chunk = 128
    for r0 in range(0, h, chunk):
        r1 = min(h, r0 + chunk)
        P = np.stack([X[r0:r1].ravel(), Y[r0:r1].ravel(), Z[r0:r1].ravel()], axis=1)
        vals = np.cos(P @ W + phases).mean(axis=1)
        out[r0:r1] = vals.reshape((r1 - r0, w))
    return out


def generate_skybox(output_path: str, width: int = 4096, height: int = 2048) -> None:
    """
    Generates a crisp 4K 360° equirectangular deep-space panorama calibrated for sRGB hardware decode:
    - Obsidian pitch-black deep space contrast with luminous Milky Way & nebulae.
    - Ringed gas giant planet in the lower-left orbital horizon with terminator, limb glow, and ring shadow.
    - 12,000 anti-aliased sub-pixel stars with natural spectral & magnitude distribution.
    """
    print(f"\n[1/2] Generating 4K Equirectangular Cosmic Skybox ({width}x{height})...")
    t0 = time.time()

    theta = np.linspace(-math.pi, math.pi, width, endpoint=False, dtype=np.float32)
    phi = np.linspace(math.pi * 0.5, -math.pi * 0.5, height, dtype=np.float32)
    theta_g, phi_g = np.meshgrid(theta, phi)
    cos_phi = np.cos(phi_g)
    X = cos_phi * np.cos(theta_g)
    Y = np.sin(phi_g)
    Z = cos_phi * np.sin(theta_g)

    warp = _spherical_fbm(X, Y, Z, seed=301, freq_scale=2.4, n_waves=14) * 0.22
    Xw = X + warp
    Yw = Y - warp * 0.7
    Zw = Z + warp * 0.5
    n_large = np.clip(_spherical_fbm(Xw, Yw, Zw, seed=302, freq_scale=3.2, n_waves=18) * 1.45 + 0.36, 0.0, 1.0)
    n_fine = np.clip(_spherical_fbm(Xw, Yw, Zw, seed=303, freq_scale=7.5, n_waves=20) * 1.60 + 0.30, 0.0, 1.0)
    n_dust = np.clip(_spherical_fbm(Xw, Yw, Zw, seed=304, freq_scale=11.0, n_waves=20) * 2.20 + 0.15, 0.0, 1.0)

    # Tilted Milky Way galactic band + warm core
    mw_n = np.array([0.48, 0.68, -0.55], dtype=np.float32)
    mw_n /= np.linalg.norm(mw_n)
    d_mw = np.abs(X * mw_n[0] + Y * mw_n[1] + Z * mw_n[2])
    mw_band = np.exp(-(d_mw ** 2) / (2.0 * 0.145 ** 2)) * (1.0 - 0.66 * n_dust) * (0.45 + 0.55 * n_fine)

    mw_core_dir = np.cross(mw_n, np.array([0.0, 1.0, 0.0], dtype=np.float32))
    mw_core_dir /= np.linalg.norm(mw_core_dir)
    d_core = np.arccos(np.clip(X * mw_core_dir[0] + Y * mw_core_dir[1] + Z * mw_core_dir[2], -1.0, 1.0))
    mw_core = 1.35 * np.exp(-(d_core ** 2) / (2.0 * 0.32 ** 2)) * (1.0 - 0.52 * n_dust)
    mw_total = mw_band + mw_core

    def nebula_lobe(direction, sigma, noise_field):
        d = np.asarray(direction, dtype=np.float32)
        d /= np.linalg.norm(d)
        ang = np.arccos(np.clip(X * d[0] + Y * d[1] + Z * d[2], -1.0, 1.0))
        return np.exp(-(ang ** 2) / (2.0 * sigma ** 2)) * noise_field

    neb_violet = nebula_lobe((0.46, 0.28, -0.84), 0.35, n_large)
    neb_cyan = nebula_lobe((-0.62, 0.18, -0.76), 0.39, n_fine)
    neb_magenta = nebula_lobe((0.52, -0.32, 0.79), 0.42, 0.6 * n_large + 0.4 * n_fine)

    # Calibrated for VK_FORMAT_R8G8B8A8_SRGB decode (deep obsidian void + clear vibrant nebulae/Milky Way)
    R = np.clip(2.0 + neb_violet * 60.0 + neb_cyan * 16.0 + neb_magenta * 68.0 + mw_total * 74.0, 0.0, 255.0)
    G = np.clip(3.0 + neb_violet * 24.0 + neb_cyan * 58.0 + neb_magenta * 24.0 + mw_total * 64.0, 0.0, 255.0)
    B = np.clip(7.0 + neb_violet * 96.0 + neb_cyan * 112.0 + neb_magenta * 92.0 + mw_total * 88.0, 0.0, 255.0)
    img = np.stack([R, G, B], axis=2).astype(np.float32)

    # Anti-aliased starfield (12,000 stars with Gaussian sub-pixel splatting)
    rng = np.random.default_rng(2026)
    n_stars = 12000
    sz = rng.uniform(-1.0, 1.0, n_stars)
    sth = rng.uniform(-math.pi, math.pi, n_stars)
    sr = np.sqrt(1.0 - sz * sz)
    sx3 = sr * np.cos(sth)
    sy3 = sz
    sz3 = sr * np.sin(sth)

    su = ((np.arctan2(sz3, sx3) / (2.0 * math.pi) + 0.5) * width) % width
    sv = np.clip((0.5 - np.arcsin(np.clip(sy3, -1.0, 1.0)) / math.pi) * (height - 1), 0.0, height - 1.0)
    mag = np.clip(rng.pareto(a=2.6, size=n_stars) / 3.4, 0.22, 2.3)

    rolls = rng.random(n_stars)
    for i in range(n_stars):
        uf = float(su[i])
        vf = float(sv[i])
        u0 = int(round(uf)) % width
        v0 = int(round(vf))
        b = float(mag[i])
        roll = float(rolls[i])
        if roll < 0.36:
            c = np.array([198.0, 224.0, 255.0], dtype=np.float32)
        elif roll < 0.72:
            c = np.array([255.0, 255.0, 255.0], dtype=np.float32)
        elif roll < 0.90:
            c = np.array([255.0, 238.0, 198.0], dtype=np.float32)
        else:
            c = np.array([255.0, 188.0, 138.0], dtype=np.float32)

        rad = 1 if b < 0.85 else (2 if b < 1.5 else 3)
        sigma = 0.65 if rad == 1 else (0.98 if rad == 2 else 1.28)
        inv2s2 = 1.0 / (2.0 * sigma * sigma)
        for dv in range(-rad, rad + 1):
            vv = v0 + dv
            if vv < 0 or vv >= height:
                continue
            dy2 = (vv - vf) ** 2
            for du in range(-rad, rad + 1):
                uu = (u0 + du) % width
                dx2 = (du - (uf - round(uf))) ** 2
                w_g = math.exp(-(dx2 + dy2) * inv2s2) * b
                if w_g > 0.03:
                    img[vv, uu] = np.maximum(img[vv, uu], c * w_g)

    # Distant Ringed Gas Giant Planet (lower-left orbital horizon, outside frontal screen cone)
    p_dir = np.array([-0.74, -0.16, -0.65], dtype=np.float32)
    p_dir /= np.linalg.norm(p_dir)
    p_dist = 7.2
    p_center = p_dir * p_dist
    p_rad = 1.18

    ring_n = np.array([0.28, 0.91, -0.30], dtype=np.float32)
    ring_n /= np.linalg.norm(ring_n)
    sun_dir = np.array([0.62, 0.45, 0.64], dtype=np.float32)
    sun_dir /= np.linalg.norm(sun_dir)

    cos_ang = X * p_dir[0] + Y * p_dir[1] + Z * p_dir[2]
    roi = cos_ang > 0.86
    rx = X[roi]
    ry = Y[roi]
    rz = Z[roi]
    ray = np.stack([rx, ry, rz], axis=1)

    b_dot = ray @ p_center
    c_val = float(np.dot(p_center, p_center) - p_rad * p_rad)
    disc = b_dot * b_dot - c_val
    hit_sphere = disc > 0.0
    t_sphere = np.where(hit_sphere, b_dot - np.sqrt(np.maximum(disc, 0.0)), 1e9)

    denom = ray @ ring_n
    t_ring = np.where(np.abs(denom) > 1e-4, float(np.dot(p_center, ring_n)) / np.where(np.abs(denom) > 1e-4, denom, 1.0), -1.0)
    ring_pt = ray * t_ring[:, None] - p_center[None, :]
    ring_r = np.linalg.norm(ring_pt, axis=1)
    hit_ring = (t_ring > 0.0) & (ring_r > p_rad * 1.32) & (ring_r < p_rad * 2.38)

    p_rgb = np.zeros((len(rx), 3), dtype=np.float32)
    p_alpha = np.zeros((len(rx),), dtype=np.float32)

    if np.any(hit_sphere):
        hs = hit_sphere
        hit_pt = ray[hs] * t_sphere[hs, None]
        nrm = (hit_pt - p_center[None, :]) / p_rad
        lat = np.clip(nrm @ ring_n, -1.0, 1.0)
        bands = (
            0.50
            + 0.24 * np.sin(lat * 18.0)
            + 0.16 * np.cos(lat * 31.0 + 1.2)
            + 0.10 * np.sin(lat * 52.0)
        )
        col_a = np.array([42.0, 72.0, 122.0], dtype=np.float32)
        col_b = np.array([144.0, 168.0, 210.0], dtype=np.float32)
        surf = col_a[None, :] * (1.0 - bands[:, None]) + col_b[None, :] * bands[:, None]

        ndotl = np.clip(nrm @ sun_dir, 0.0, 1.0)
        lit = np.power(ndotl, 0.68) * 0.94 + 0.04
        view_dot = np.clip(-(ray[hs] * nrm).sum(axis=1), 0.0, 1.0)
        rim = np.power(1.0 - view_dot, 3.2) * (0.28 + 0.85 * ndotl)
        atmo = rim[:, None] * np.array([76.0, 196.0, 255.0], dtype=np.float32)

        p_rgb[hs] = np.clip(surf * lit[:, None] + atmo, 0.0, 255.0)
        p_alpha[hs] = 1.0

    if np.any(hit_ring):
        hr = hit_ring
        rr = ring_r[hr] / p_rad
        r_norm = (rr - 1.32) / (2.38 - 1.32)
        edge_fade = np.clip(r_norm * 7.0, 0.0, 1.0) * np.clip((1.0 - r_norm) * 7.0, 0.0, 1.0)
        gap = 1.0 - 0.88 * np.exp(-((rr - 1.88) ** 2) / (2.0 * 0.042 ** 2))
        fine_rings = 0.55 + 0.30 * np.sin(rr * 42.0) + 0.15 * np.cos(rr * 95.0)
        r_alpha = np.clip(edge_fade * gap * fine_rings * 0.74, 0.0, 0.84)

        rp = ring_pt[hr]
        proj_sun = rp @ sun_dir
        perp = rp - proj_sun[:, None] * sun_dir[None, :]
        perp_dist = np.linalg.norm(perp, axis=1)
        in_shadow = (proj_sun < 0.0) & (perp_dist < p_rad * 1.02)
        shadow_mul = np.where(in_shadow, 0.14, 1.0).astype(np.float32)

        ring_col = (
            np.array([170.0, 190.0, 224.0], dtype=np.float32)[None, :]
            * (0.75 + 0.25 * np.sin(rr * 24.0))[:, None]
            * shadow_mul[:, None]
        )

        in_front = t_ring[hr] < t_sphere[hr]
        idx_hr = np.flatnonzero(hr)
        for k, idx_local in enumerate(idx_hr):
            a = float(r_alpha[k])
            if in_front[k]:
                if p_alpha[idx_local] > 0.5:
                    p_rgb[idx_local] = p_rgb[idx_local] * (1.0 - a) + ring_col[k] * a
                else:
                    p_rgb[idx_local] = ring_col[k]
                    p_alpha[idx_local] = a
            elif p_alpha[idx_local] < 0.5:
                p_rgb[idx_local] = ring_col[k]
                p_alpha[idx_local] = a

    roi_img = img[roi]
    img[roi] = roi_img * (1.0 - p_alpha[:, None]) + p_rgb * p_alpha[:, None]

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    final_img = Image.fromarray(np.clip(img, 0.0, 255.0).astype(np.uint8), mode="RGB")
    final_img.save(output_path, format="PNG", optimize=True)

    elapsed = time.time() - t0
    size_kb = os.path.getsize(output_path) / 1024.0
    print(f"  -> Skybox saved: {output_path} ({width}x{height}, {size_kb:.1f} KB, {elapsed:.2f}s)")


# ==============================================================================
# 2. OPTIONAL AMBIENT AUDIO GENERATOR (preserved from existing loop)
# ==============================================================================
def generate_ambient_audio(output_path: str, duration: float = 8.0, sample_rate: int = 48000) -> None:
    print(f"\n[Audio] Regenerating looping space ambient ({duration:.1f}s)...")
    N = int(sample_rate * duration)
    t = np.arange(N) / sample_rate
    left = np.zeros(N, dtype=np.float64)
    right = np.zeros(N, dtype=np.float64)

    for f in (42.0, 55.0, 65.5, 82.5):
        exact_f = round(f * duration) / duration
        left += 0.25 * np.sin(2 * math.pi * exact_f * t) * (0.8 + 0.2 * np.cos(2 * math.pi * t / duration))
        right += 0.25 * np.sin(2 * math.pi * exact_f * t + 0.3) * (0.8 + 0.2 * np.cos(2 * math.pi * t / duration + 0.5))

    for f in (110.0, 165.0, 220.0, 275.0, 330.0):
        exact_f = round(f * duration) / duration
        amp = 0.08 * (100.0 / exact_f)
        left += amp * np.sin(2 * math.pi * exact_f * t) * (0.7 + 0.3 * np.cos(4 * math.pi * t / duration))
        right += amp * np.sin(2 * math.pi * exact_f * t + 0.7) * (0.7 + 0.3 * np.cos(4 * math.pi * t / duration + 1.0))

    rng = np.random.default_rng(1337)
    freqs = np.fft.rfftfreq(N, 1.0 / sample_rate)
    H = 1.0 / (1.0 + (freqs / 80.0) ** 3)
    nl = np.fft.irfft((rng.standard_normal(len(freqs)) + 1j * rng.standard_normal(len(freqs))) * H, n=N)
    nr = np.fft.irfft((rng.standard_normal(len(freqs)) + 1j * rng.standard_normal(len(freqs))) * H, n=N)
    left += nl / np.std(nl) * 0.12
    right += nr / np.std(nr) * 0.12

    peak = max(np.max(np.abs(left)), np.max(np.abs(right)))
    audio_int16 = np.zeros((N, 2), dtype=np.int16)
    audio_int16[:, 0] = (left / peak * 0.20 * 32767).astype(np.int16)
    audio_int16[:, 1] = (right / peak * 0.20 * 32767).astype(np.int16)

    tmp_wav = "/tmp/space_ambient_temp.wav"
    with wave.open(tmp_wav, "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(audio_int16.tobytes())

    subprocess.run(["ffmpeg", "-y", "-i", tmp_wav, "-c:a", "libvorbis", "-q:a", "4", output_path], check=True)
    if os.path.exists(tmp_wav):
        os.remove(tmp_wav)


# ==============================================================================
# 3. ORBITAL OBSERVATION PLATFORM (512x512 BAKED-RELIEF PBR, CALM ACCENTS, 0 COLLISIONS)
# ==============================================================================
def build_space_platform() -> GlbSceneBuilder:
    builder = GlbSceneBuilder()
    checker = CollisionChecker()

    def add(mat: str, group: str, part: MeshPart) -> None:
        builder.add_mesh(mat, checker.register(group, part))

    # 512x512 Baked-Relief aerospace textures (satin titanium metallic=0.58 reads richly in deep space)
    alloy_base, alloy_orm = tex_brushed_alloy((138, 148, 166), size=512, seed=401, roughness=0.36, metallic=0.58)
    carbon_base, carbon_orm = tex_carbon_deck((76, 84, 98), size=512, seed=402)

    builder.add_material(MaterialSpec("deck_alloy", base_texture=alloy_base, orm_texture=alloy_orm, roughness_factor=1.0, metallic_factor=1.0, jpeg_quality=90))
    builder.add_material(MaterialSpec("deck_carbon", base_texture=carbon_base, orm_texture=carbon_orm, roughness_factor=1.0, metallic_factor=1.0, jpeg_quality=90))
    builder.add_material(MaterialSpec("hull_dark", base_color_factor=(0.055, 0.064, 0.080, 1.0), metallic_factor=0.55, roughness_factor=0.46))
    # Restrained, cohesive floor strip glow (no harsh neon blue glare clashing with the deck/stars!)
    builder.add_material(MaterialSpec("floor_accent", base_color_factor=(0.05, 0.14, 0.19, 1.0), emissive_factor=(0.04, 0.24, 0.34), emissive_strength=1.0, roughness_factor=0.32, bake_lighting=False))
    builder.add_material(MaterialSpec("rail_accent", base_color_factor=(0.05, 0.16, 0.22, 1.0), emissive_factor=(0.05, 0.28, 0.40), emissive_strength=1.0, roughness_factor=0.30, bake_lighting=False))
    builder.add_material(MaterialSpec("console_accent", base_color_factor=(0.06, 0.20, 0.28, 1.0), emissive_factor=(0.08, 0.38, 0.52), emissive_strength=1.05, roughness_factor=0.25, bake_lighting=False))

    # A) Central carbon observation medallion (R: 0 -> 0.88m, Y[-0.015, +0.005])
    # Group deck floor rings & concentric sectors cleanly with non-overlapping Y layers
    add("deck_carbon", "deck_medallion", make_cylinder(0.88, 0.020, sections=36, center=(0.0, -0.005, 0.0), uv_scale=0.85))
    # Thin 1.4cm inlaid floor strips (Y = 0.0065, sitting cleanly above/between joints without neon glare)
    add("floor_accent", "floor_ring_inner", make_flat_ring(0.220, 0.234, 0.0065, n_segs=28))
    add("floor_accent", "floor_ring_med", make_flat_ring(0.896, 0.910, 0.0065, n_segs=36))

    # B) 16 radial brushed-alloy deck plates (Y[-0.024, +0.006], cleanly separated from sub-plate & console)
    n_sectors = 16
    d_th = 2.0 * math.pi / n_sectors
    gap = 0.016
    for i in range(n_sectors):
        t0 = i * d_th + gap
        t1 = (i + 1) * d_th - gap
        col_top = (242, 246, 255, 255) if (i % 2 == 0) else (210, 218, 232, 255)
        col_side = (128, 136, 150, 255)
        add("deck_alloy", "deck_plates", make_solid_sector(0.925, 1.565, 0.006, -0.024, t0, t1, 2, col_top, col_side, uv_scale=0.75))
        add("deck_alloy", "deck_plates", make_solid_sector(1.610, 2.230, 0.006, -0.024, t0, t1, 2, col_top, col_side, uv_scale=0.75))

    # Dark sub-plate strictly BELOW deck plates (Y[-0.066, -0.026]) so seams show dark recessed channels
    add("hull_dark", "deck_subplate", make_cylinder(2.265, 0.040, sections=40, center=(0.0, -0.046, 0.0)))

    # Thin 1.4cm outer inlaid floor accent rings (nestled inside radial expansion channels, Y = 0.0065)
    add("floor_accent", "floor_ring_mid", make_flat_ring(1.580, 1.594, 0.0065, n_segs=48))
    add("floor_accent", "floor_ring_out", make_flat_ring(2.242, 2.256, 0.0065, n_segs=48))

    # C) Chamfered titanium outer rim (R: 2.27 -> 2.52m, starts cleanly outside outer ring 2.256)
    rim_profile = [(2.27, 0.00), (2.34, 0.045), (2.46, 0.045), (2.52, -0.06), (2.52, -0.14)]
    add("deck_alloy", "outer_rim", make_profile_ring(rim_profile, 48, (232, 238, 250, 255), uv_scale=0.55))

    # D) Lower levitation hull & soft anti-gravity emitter ring (starts below rim Y = -0.142)
    hull_profile = [(2.52, -0.142), (1.92, -0.36), (1.28, -0.56), (0.80, -0.56)]
    add("hull_dark", "lower_hull", make_profile_ring(hull_profile, 48, (255, 255, 255, 255), uv_scale=0.5))
    add("floor_accent", "emitter_ring", make_flat_ring(0.80, 1.24, -0.565, n_segs=32, up_normal=False))

    # E) Protective panoramic railing (+50.5° to 309.5°, cleanly clear of flanking pillars at ±45°!)
    t_pillar_l = math.pi * 0.25       # +45 deg
    t_pillar_r = math.pi * 1.75       # 315 deg
    t_rail_start = math.radians(50.5)
    t_rail_end = math.radians(309.5)
    t_thresh = math.radians(39.5)

    # Top handrail, subtle top micro-strip, mid-rail accent, and carbon kickplate (stacked in Y, zero overlap)
    add("deck_alloy", "railing_top", make_solid_sector(2.39, 2.45, 1.030, 0.990, t_rail_start, t_rail_end, 28, (238, 242, 252, 255), (190, 200, 215, 255), uv_scale=0.8))
    add("rail_accent", "railing_top_led", make_solid_sector(2.410, 2.430, 1.040, 1.032, t_rail_start, t_rail_end, 28, (255, 255, 255, 255), (255, 255, 255, 255)))
    add("rail_accent", "railing_mid_led", make_solid_sector(2.412, 2.428, 0.550, 0.532, t_rail_start, t_rail_end, 28, (255, 255, 255, 255), (255, 255, 255, 255)))
    add("deck_carbon", "railing_kick", make_solid_sector(2.405, 2.435, 0.160, 0.048, t_rail_start, t_rail_end, 24, (224, 228, 238, 255), (185, 190, 200, 255)))

    # Low front threshold beacon (-41.5° to +41.5°, cleanly clear of pillars at ±45°)
    add("deck_alloy", "front_thresh", make_solid_sector(2.39, 2.45, 0.062, 0.048, -t_thresh, t_thresh, 14, (224, 232, 244, 255), (184, 194, 210, 255)))
    add("rail_accent", "front_thresh_led", make_solid_sector(2.412, 2.428, 0.069, 0.064, -t_thresh, t_thresh, 14, (255, 255, 255, 255), (255, 255, 255, 255)))

    # 9 structural stanchions along the railing (lower & upper segments cleanly separated from kickplate, mid-rail & top rail)
    for idx, phi in enumerate(np.linspace(math.radians(56.0), math.radians(304.0), 9)):
        px = 2.42 * math.sin(phi)
        pz = -2.42 * math.cos(phi)
        # Lower stanchion segment: Y[0.162, 0.530]
        p_lo = make_box((0.052, 0.368, 0.052), center=(0.0, 0.346, 0.0))
        p_lo.rotate_axis_angle(float(phi), (0.0, 1.0, 0.0)).translate((px, 0.0, pz))
        add("deck_alloy", f"stanchion_{idx}", p_lo)
        # Upper stanchion segment: Y[0.552, 0.988]
        p_hi = make_box((0.052, 0.436, 0.052), center=(0.0, 0.770, 0.0))
        p_hi.rotate_axis_angle(float(phi), (0.0, 1.0, 0.0)).translate((px, 0.0, pz))
        add("deck_alloy", f"stanchion_{idx}", p_hi)
        # Subtle inward-facing accent micro-strip (clear of post face)
        strip = make_box((0.012, 0.36, 0.008), center=(0.0, 0.770, 0.032))
        strip.rotate_axis_angle(float(phi), (0.0, 1.0, 0.0)).translate((px, 0.0, pz))
        add("rail_accent", f"stanchion_{idx}", strip)

    # F) Flanking signal pillars at ±45° (sitting cleanly on outer rim Y = 0.048 upward)
    for idx, phi in enumerate((t_pillar_l, t_pillar_r)):
        px = 2.40 * math.sin(phi)
        pz = -2.40 * math.cos(phi)
        col = make_rounded_box((0.16, 0.98, 0.16), radius=0.022, segments=1, center=(0.0, 0.538, 0.0))
        col.rotate_axis_angle(float(phi), (0.0, 1.0, 0.0)).translate((px, 0.0, pz))
        add("deck_alloy", f"pillar_{idx}", col)

        accent = make_box((0.028, 0.82, 0.014), center=(0.0, 0.538, 0.088))
        accent.rotate_axis_angle(float(phi), (0.0, 1.0, 0.0)).translate((px, 0.0, pz))
        add("rail_accent", f"pillar_{idx}", accent)

        add("rail_accent", f"pillar_{idx}", make_cylinder(0.068, 0.028, sections=16, center=(px, 1.044, pz)))
        add("floor_accent", f"pillar_{idx}", make_flat_ring(0.052, 0.082, 1.095, n_segs=18).translate((px, 0.0, pz)))

    # G) Recessed ergonomic front navigation console (base strictly ABOVE deck plates Y = 0.008!)
    add("deck_carbon", "console_base", make_rounded_box((0.58, 0.292, 0.24), radius=0.024, segments=1, center=(0.0, 0.154, -2.02)))
    add("console_accent", "console_strip", make_box((0.46, 0.016, 0.012), center=(0.0, 0.20, -1.892)))
    # Angled display housing & glowing telemetry pad cleanly above console_base (Y_top = 0.300)
    # Grouped as "console_display" because the tilted pad and screen share an axis-aligned box along the 20° slope.
    pad = make_box((0.48, 0.024, 0.19), center=(0.0, 0.352, -2.02))
    pad.rotate_axis_angle(math.radians(20.0), (1.0, 0.0, 0.0), pivot=(0.0, 0.352, -2.02))
    add("deck_alloy", "console_display", pad)
    disp = make_box((0.40, 0.010, 0.135), center=(0.0, 0.371, -2.013))
    disp.rotate_axis_angle(math.radians(20.0), (1.0, 0.0, 0.0), pivot=(0.0, 0.352, -2.02))
    add("console_accent", "console_display", disp)

    # Use radial separating axis in addition to XYZ AABB for concentric annular deck rings
    checker.assert_no_collisions("space", tolerance=0.002, radial_check=True)
    return builder


def space_linear_bake(verts: np.ndarray, normals: np.ndarray) -> np.ndarray:
    """Subtle linear contact AO around deck edges and under-hull."""
    r = np.sqrt(verts[:, 0:1] ** 2 + verts[:, 2:3] ** 2)
    ao = np.ones((len(verts), 1), dtype=np.float32)
    seam = np.exp(-((r - 2.26) ** 2) / (2.0 * 0.10 ** 2))
    ao *= (1.0 - 0.16 * seam)
    under = np.clip((verts[:, 1:2] + 0.15) / 0.20, 0.0, 1.0)
    ao *= (0.68 + 0.32 * under)
    return np.repeat(ao, 3, axis=1)


def main() -> None:
    regen_audio = "--regen-audio" in sys.argv
    skip_skybox = "--skip-skybox" in sys.argv
    if not skip_skybox or not os.path.exists(SKYBOX_OUT):
        generate_skybox(SKYBOX_OUT, width=4096, height=2048)
    else:
        print(f"\n[1/2] Keeping existing 4K skybox: {SKYBOX_OUT}")

    if regen_audio or not os.path.exists(AMBIENT_OUT):
        generate_ambient_audio(AMBIENT_OUT)
    else:
        print(f"  -> Keeping existing ambient audio: {AMBIENT_OUT}")

    print("\n[2/2] Building Multi-Material 512x512 Baked-Relief Orbital Space Platform (model.glb)...")
    builder = build_space_platform()
    stats = builder.build_and_export(MODEL_OUT, bake_fn=space_linear_bake, y_offset=0.0)
    print(
        f"  -> Saved {MODEL_OUT}: {stats['triangles']} triangles (< 8,000), {stats['vertices']} vertices, "
        f"{stats['materials']} materials, {stats['textures']} textures, {stats['bytes'] / 1024:.1f} KB"
    )
    assert stats["triangles"] < 8000, f"Space platform exceeds 8k triangle budget: {stats['triangles']}"


if __name__ == "__main__":
    main()
