#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate-living-room.py — High-fidelity Evening Home Theater Lounge generator for tucaVR.

Design goals:
- Cozy evening home theater atmosphere with tactile 512x512 baked-relief materials (Venetian plaster,
  staggered walnut planks, woven linen upholstery, bouclé wool rug, and recessed twilight window).
- Zero geometric collisions certified by CollisionChecker (<= 2mm tolerance across all furniture,
  cushions, pillows, slatted feature wall, media console, window frame, curtains, and arc lamp).
- Physically grounded TV mounting: OLED bezel flush on the front walnut slatted wall (Z = -3.03m),
  virtual screen at Z = -3.00m (2.64m x 1.485m -> 47.5 deg FOV, 16:9).
- Ergonomic modular L-shaped sectional sofa with continuous full-width backrest and clean clearances.
"""

import math
import os
import sys
import numpy as np

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
    make_pleated_curtain,
    make_rounded_box,
    make_tube_along_path,
    tex_fabric,
    tex_night_window_panorama,
    tex_plaster,
    tex_wood,
)

PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
OUTPUT_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "environments", "living_room")
MODEL_PATH = os.path.join(OUTPUT_DIR, "model.glb")

# Eye is modeled at Y = 1.15m (seated on sofa) and shifted +0.35m on export to land at runtime Y = 1.50m.
DESIGN_EYE_Y = 1.15
RUNTIME_EYE_Y = 1.50
EXPORT_Y_OFFSET = RUNTIME_EYE_Y - DESIGN_EYE_Y


def add_potted_plant(
    builder: GlbSceneBuilder,
    checker: CollisionChecker,
    cx: float,
    base_y: float,
    cz: float,
) -> None:
    """Ceramic planter with soil and arched organic foliage blades grouped cleanly on the tabletop."""
    pot = make_cylinder(0.050, 0.084, sections=14, center=(cx, base_y + 0.042, cz), radius_top=0.066)
    soil = make_cylinder(0.058, 0.010, sections=12, center=(cx, base_y + 0.089, cz))
    builder.add_mesh("ceramic_pot", checker.register("coffee_plant", pot))
    builder.add_mesh("matte_dark", checker.register("coffee_plant", soil))

    for i in range(9):
        yaw = (i / 9.0) * 2.0 * math.pi + (0.2 if i % 2 else 0.0)
        tier = 0 if i < 5 else 1
        length = 0.145 if tier == 0 else 0.105
        tilt = 0.62 if tier == 0 else 0.34
        dx, dz = math.sin(yaw), math.cos(yaw)
        p0 = (cx + dx * 0.015, base_y + 0.094, cz + dz * 0.015)
        p1 = (
            cx + dx * (length * 0.45 * math.sin(tilt)),
            base_y + 0.094 + length * 0.55 * math.cos(tilt),
            cz + dz * (length * 0.45 * math.sin(tilt)),
        )
        p2 = (
            cx + dx * (length * 0.95 * math.sin(tilt + 0.35)),
            base_y + 0.094 + length * 0.85 * math.cos(tilt + 0.35),
            cz + dz * (length * 0.95 * math.sin(tilt + 0.35)),
        )
        builder.add_mesh("foliage", checker.register("coffee_plant", make_tube_along_path([p0, p1], radius=0.0045, sections=6)))
        leaf = make_rounded_box(
            (0.042 if tier == 0 else 0.032, 0.006, 0.085 if tier == 0 else 0.065),
            radius=0.0025,
            segments=1,
            center=(0.0, 0.0, 0.0),
        )
        leaf.rotate_axis_angle(math.radians(28.0 if tier == 0 else 16.0), (1.0, 0.0, 0.0))
        leaf.rotate_axis_angle(yaw, (0.0, 1.0, 0.0))
        leaf.translate(p2)
        builder.add_mesh("foliage", checker.register("coffee_plant", leaf))


def build_living_room() -> GlbSceneBuilder:
    builder = GlbSceneBuilder()
    checker = CollisionChecker()

    def add(mat: str, group: str, part: MeshPart) -> None:
        builder.add_mesh(mat, checker.register(group, part))

    # 512x512 Baked-Relief PBR textures (tactile detail baked in for zero extra GPU cost)
    wall_base, wall_orm = tex_plaster((106, 104, 112), size=512, seed=201, roughness=0.90)
    ceil_base, ceil_orm = tex_plaster((78, 80, 88), size=512, seed=202, roughness=0.94)
    floor_base, floor_orm = tex_wood((142, 102, 68), (82, 54, 34), size=512, seed=203, planks=6, roughness=0.38)
    walnut_base, walnut_orm = tex_wood((122, 82, 50), (66, 40, 24), size=512, seed=204, planks=3, roughness=0.36)
    sofa_base, sofa_orm = tex_fabric((86, 94, 108), size=512, seed=205, weave_freq=56, roughness=0.86, contrast=0.95)
    rug_base, rug_orm = tex_fabric((138, 132, 122), size=512, seed=206, weave_freq=48, roughness=0.94, contrast=1.10)
    curtain_base, curtain_orm = tex_fabric((108, 104, 100), size=512, seed=207, weave_freq=56, roughness=0.90, contrast=0.95)
    window_pano = tex_night_window_panorama(2048, 1024, seed=208)

    builder.add_material(MaterialSpec("wall_plaster", base_texture=wall_base, orm_texture=wall_orm, roughness_factor=1.0, metallic_factor=0.0, jpeg_quality=90))
    builder.add_material(MaterialSpec("ceiling_matte", base_texture=ceil_base, orm_texture=ceil_orm, roughness_factor=1.0, metallic_factor=0.0, jpeg_quality=88))
    builder.add_material(MaterialSpec("wood_floor", base_texture=floor_base, orm_texture=floor_orm, roughness_factor=1.0, metallic_factor=0.0, jpeg_quality=90))
    builder.add_material(MaterialSpec("wood_walnut", base_texture=walnut_base, orm_texture=walnut_orm, roughness_factor=1.0, metallic_factor=0.0, jpeg_quality=90))
    builder.add_material(MaterialSpec("sofa_fabric", base_texture=sofa_base, orm_texture=sofa_orm, roughness_factor=1.0, metallic_factor=0.0, jpeg_quality=90))
    builder.add_material(MaterialSpec("rug_wool", base_texture=rug_base, orm_texture=rug_orm, roughness_factor=1.0, metallic_factor=0.0, jpeg_quality=90))
    builder.add_material(MaterialSpec("curtain_linen", base_texture=curtain_base, orm_texture=curtain_orm, roughness_factor=1.0, metallic_factor=0.0, double_sided=True, jpeg_quality=88))
    builder.add_material(MaterialSpec("brass_metal", base_color_factor=(0.76, 0.56, 0.24, 1.0), metallic_factor=0.82, roughness_factor=0.28))
    builder.add_material(MaterialSpec("matte_dark", base_color_factor=(0.032, 0.035, 0.042, 1.0), metallic_factor=0.35, roughness_factor=0.42))
    builder.add_material(MaterialSpec("accent_decor", base_color_factor=(1.0, 1.0, 1.0, 1.0), metallic_factor=0.0, roughness_factor=0.78))
    builder.add_material(MaterialSpec("ceramic_pot", base_color_factor=(0.44, 0.48, 0.46, 1.0), metallic_factor=0.0, roughness_factor=0.34))
    builder.add_material(MaterialSpec("foliage", base_color_factor=(0.065, 0.19, 0.088, 1.0), metallic_factor=0.0, roughness_factor=0.60, double_sided=True))
    builder.add_material(MaterialSpec("emissive_warm", base_color_factor=(0.24, 0.16, 0.06, 1.0), emissive_factor=(1.0, 0.68, 0.32), emissive_strength=1.35, roughness_factor=0.4, bake_lighting=False))
    builder.add_material(MaterialSpec("emissive_shade", base_color_factor=(0.38, 0.30, 0.18, 1.0), emissive_factor=(0.95, 0.64, 0.28), emissive_strength=0.85, roughness_factor=0.75, double_sided=True, bake_lighting=False))
    builder.add_material(MaterialSpec("window_night", base_color_factor=(0.08, 0.10, 0.14, 1.0), emissive_texture=window_pano, emissive_factor=(0.95, 0.95, 1.0), emissive_strength=1.05, roughness_factor=0.95, bake_lighting=False))

    # =========================================================================
    # 1. ROOM SHELL: X [-2.60, +2.60] (5.2m), Y [0.0, 2.90], Z [-3.14, +2.26] (5.4m)
    # =========================================================================
    z_front = -3.14
    z_back = 2.26
    room_d = z_back - z_front
    z_mid = 0.5 * (z_front + z_back)

    add("wall_plaster", "wall_back", make_box((5.20, 2.90, 0.12), center=(0.0, 1.45, z_back + 0.06), uv_scale=0.5, subdiv=(4, 3, 1)))
    add("wall_plaster", "wall_front", make_box((5.20, 2.90, 0.12), center=(0.0, 1.45, z_front - 0.06), uv_scale=0.5, subdiv=(4, 3, 1)))
    add("wall_plaster", "wall_right", make_box((0.12, 2.90, room_d), center=(2.66, 1.45, z_mid), uv_scale=0.5, subdiv=(1, 3, 5)))

    # Left wall with panoramic window opening (Z: -2.40 to +0.80, Y: 0.52 to 2.52)
    win_z0, win_z1 = -2.40, 0.80
    win_y0, win_y1 = 0.52, 2.52
    win_w = win_z1 - win_z0
    win_h = win_y1 - win_y0
    win_zc = 0.5 * (win_z0 + win_z1)
    win_yc = 0.5 * (win_y0 + win_y1)

    add("wall_plaster", "wall_left_below", make_box((0.12, win_y0, win_w), center=(-2.66, win_y0 * 0.5, win_zc), uv_scale=0.5))
    add("wall_plaster", "wall_left_above", make_box((0.12, 2.90 - win_y1, win_w), center=(-2.66, win_y1 + (2.90 - win_y1) * 0.5, win_zc), uv_scale=0.5))
    add("wall_plaster", "wall_left_front", make_box((0.12, 2.90, win_z0 - z_front), center=(-2.66, 1.45, 0.5 * (z_front + win_z0)), uv_scale=0.5))
    add("wall_plaster", "wall_left_back", make_box((0.12, 2.90, z_back - win_z1), center=(-2.66, 1.45, 0.5 * (win_z1 + z_back)), uv_scale=0.5))

    # Ceiling & hardwood floor
    add("ceiling_matte", "ceiling", make_box((5.20, 0.08, room_d), center=(0.0, 2.94, z_mid), uv_scale=0.5, subdiv=(4, 1, 4)))
    add("wood_floor", "floor", make_box((5.20, 0.06, room_d), center=(0.0, -0.03, z_mid), uv_scale=0.55, subdiv=(5, 1, 6)))

    # Trimmed dark architectural baseboards (clean mitered butt-joints, zero corner overlap)
    add("matte_dark", "baseboard_back", make_box((5.16, 0.09, 0.02), center=(0.0, 0.045, z_back - 0.01)))
    add("matte_dark", "baseboard_front", make_box((5.16, 0.09, 0.02), center=(0.0, 0.045, z_front + 0.01)))
    add("matte_dark", "baseboard_right", make_box((0.02, 0.09, room_d - 0.04), center=(2.59, 0.045, z_mid)))
    add("matte_dark", "baseboard_left", make_box((0.02, 0.09, room_d - 0.04), center=(-2.59, 0.045, z_mid)))

    # =========================================================================
    # 2. FRONT WALL: WALNUT SLATTED PANEL, FLUSH OLED TV & CLEAR MEDIA CONSOLE
    # Wall Z = -3.14 -> Backer [-3.14, -3.116] -> Slats [-3.115, -3.083]
    # -> Console [-3.078, -2.748] (100% IN FRONT of slats, zero overlap!)
    # -> TV bezel [-3.032, -3.008] -> Virtual screen at Z = -3.00m.
    # =========================================================================
    panel_w, panel_h = 3.60, 2.58
    panel_yc = 1.45  # Y: [0.16, 2.74]
    add("matte_dark", "slat_backer", make_box((panel_w, panel_h, 0.024), center=(0.0, panel_yc, -3.128)))

    n_slats = 34
    slat_step = panel_w / n_slats
    for s in range(n_slats):
        sx = -panel_w * 0.5 + (s + 0.5) * slat_step
        add("wood_walnut", "slat_wall", make_box((0.042, panel_h, 0.032), center=(sx, panel_yc, -3.099), uv_scale=1.0))

    # Warm 2700K indirect bias glow strips around the slatted feature wall (clean non-overlapping perimeter)
    add("emissive_warm", "bias_top", make_box((panel_w, 0.016, 0.014), center=(0.0, 2.750, -3.128)))
    add("emissive_warm", "bias_bottom", make_box((panel_w, 0.016, 0.014), center=(0.0, 0.150, -3.128)))
    add("emissive_warm", "bias_left", make_box((0.016, panel_h, 0.014), center=(-panel_w * 0.5 - 0.010, panel_yc, -3.128)))
    add("emissive_warm", "bias_right", make_box((0.016, panel_h, 0.014), center=(panel_w * 0.5 + 0.010, panel_yc, -3.128)))

    # Flush-mounted OLED TV chassis & ultra-thin bezel anchored cleanly onto the slatted wall face
    sw, sh = 2.64, 1.485
    sy_tv = 1.37  # Exported Y = 1.72m
    b_th = 0.024
    # VESA mount box connecting slat front (-3.083) to TV backplate (-3.050)
    add("matte_dark", "tv_mount", make_box((1.10, 0.65, 0.032), center=(0.0, sy_tv, -3.066)))
    add("matte_dark", "tv_backplate", make_box((sw, sh, 0.016), center=(0.0, sy_tv, -3.041)))
    # Non-overlapping 4-bar bezel at Z[-3.032, -3.008]
    add("matte_dark", "tv_bezel_top", make_box((sw + 2 * b_th, b_th, 0.024), center=(0.0, sy_tv + sh * 0.5 + b_th * 0.5, -3.020)))
    add("matte_dark", "tv_bezel_bot", make_box((sw + 2 * b_th, b_th, 0.024), center=(0.0, sy_tv - sh * 0.5 - b_th * 0.5, -3.020)))
    add("matte_dark", "tv_bezel_left", make_box((b_th, sh, 0.024), center=(-sw * 0.5 - b_th * 0.5, sy_tv, -3.020)))
    add("matte_dark", "tv_bezel_right", make_box((b_th, sh, 0.024), center=(sw * 0.5 + b_th * 0.5, sy_tv, -3.020)))

    # Floating low-profile media console: sits strictly IN FRONT of slats (Z >= -3.080)
    c_w, c_h, c_d = 2.72, 0.25, 0.33
    c_y = 0.30
    c_z = -2.915  # Z: [-3.080, -2.750] -> 3mm clear of slats at -3.083!
    add("matte_dark", "console_body", make_box((c_w, c_h, c_d), center=(0.0, c_y, c_z)))
    # Walnut top slab (overhangs sides and front cleanly, flush at back Z = -3.080)
    top_y_bot = c_y + c_h * 0.5 + 0.001
    top_h = 0.024
    top_y_top = top_y_bot + top_h  # 0.450
    add("wood_walnut", "console_top", make_box((c_w + 0.04, top_h, c_d + 0.02), center=(0.0, top_y_bot + top_h * 0.5, c_z + 0.01), uv_scale=0.9))
    add("emissive_warm", "console_led", make_box((c_w - 0.30, 0.012, 0.014), center=(0.0, c_y - c_h * 0.5 - 0.008, c_z - 0.08)))

    # Slim soundbar & console decor resting cleanly on top of console_top (Y >= 0.451)
    sb_h = 0.052
    sb_y = top_y_top + 0.001 + sb_h * 0.5
    add("matte_dark", "soundbar", make_rounded_box((1.12, sb_h, 0.09), radius=0.016, segments=2, center=(0.0, sb_y, c_z + 0.04)))
    add("emissive_warm", "soundbar_led", make_box((0.035, 0.006, 0.004), center=(0.0, sb_y, c_z + 0.088)))
    add("accent_decor", "console_book", make_box((0.22, 0.028, 0.16), center=(-1.02, top_y_top + 0.015, c_z + 0.04), color=(166, 76, 58, 255)))
    add("ceramic_pot", "console_vase", make_cylinder(0.046, 0.125, sections=14, center=(1.04, top_y_top + 0.0635, c_z + 0.04), radius_top=0.036))

    # =========================================================================
    # 3. MODULAR L-SHAPED SECTIONAL SOFA (ZERO OVERLAP, CONTINUOUS BACKREST)
    # Main module:  X[-1.30, +0.92], Z[-0.38, +0.52]
    # Chaise right: X[+0.925, +1.78], Z[-1.24, +0.52] (5mm clean architectural seam)
    # Backrest:     X[-1.30, +1.78], Z[+0.36, +0.52], Y[0.192, 0.720]
    # =========================================================================
    add("wood_walnut", "sofa_base", make_box((2.22, 0.11, 0.90), center=(-0.19, 0.135, 0.07), uv_scale=1.0))
    for lx in (-1.20, -0.19, 0.82):
        for lz in (-0.28, 0.42):
            add("matte_dark", "sofa_legs", make_cylinder(0.024, 0.079, sections=10, center=(lx, 0.0395, lz)))

    add("wood_walnut", "chaise_base", make_box((0.855, 0.11, 1.76), center=(1.3525, 0.135, -0.36), uv_scale=1.0))
    for lx in (1.02, 1.68):
        for lz in (-1.14, -0.36, 0.42):
            add("matte_dark", "chaise_legs", make_cylinder(0.024, 0.079, sections=10, center=(lx, 0.0395, lz)))

    # 3 main seat cushions (Y[0.192, 0.352], Z[-0.37, +0.35]) + chaise cushion (Z[-1.23, +0.35])
    seat_centers_x = (-0.93, -0.19, 0.55)
    for idx, cx in enumerate(seat_centers_x):
        add("sofa_fabric", f"seat_cushion_{idx}", make_rounded_box((0.72, 0.16, 0.72), radius=0.038, segments=2, center=(cx, 0.272, -0.01), uv_scale=1.8))

    add("sofa_fabric", "chaise_cushion", make_rounded_box((0.835, 0.16, 1.58), radius=0.038, segments=2, center=(1.3525, 0.272, -0.44), uv_scale=1.8))

    # Full-width continuous rear backrest structure behind both main seats and chaise (zero gap!)
    add("sofa_fabric", "sofa_backrest", make_rounded_box((3.08, 0.528, 0.16), radius=0.032, segments=2, center=(0.24, 0.456, 0.44), uv_scale=1.6))

    # 4 backrest cushions resting cleanly ON TOP of seats (Y >= 0.356) and IN FRONT of backrest (Z <= 0.356)
    back_specs = [
        ("back_cushion_0", -0.93, 0.71),
        ("back_cushion_1", -0.19, 0.71),
        ("back_cushion_2", 0.55, 0.71),
        ("back_cushion_chaise", 1.3525, 0.82),
    ]
    for bname, bx, bw in back_specs:
        bc = make_rounded_box((bw, 0.40, 0.15), radius=0.038, segments=2, center=(bx, 0.562, 0.268), uv_scale=1.8)
        bc.rotate_axis_angle(math.radians(-6.0), (1.0, 0.0, 0.0), pivot=(bx, 0.562, 0.268))
        add("sofa_fabric", bname, bc)

    # Left & right armrests outside the seat/chaise modules
    add("sofa_fabric", "arm_left", make_rounded_box((0.195, 0.48, 0.90), radius=0.03, segments=2, center=(-1.4025, 0.32, 0.07), uv_scale=1.8))
    for lz in (-0.28, 0.42):
        add("matte_dark", "arm_left_legs", make_cylinder(0.022, 0.079, sections=10, center=(-1.4025, 0.0395, lz)))

    add("sofa_fabric", "arm_right", make_rounded_box((0.195, 0.48, 1.76), radius=0.03, segments=2, center=(1.8825, 0.32, -0.36), uv_scale=1.8))
    for lz in (-1.14, 0.42):
        add("matte_dark", "arm_right_legs", make_cylinder(0.022, 0.079, sections=10, center=(1.8825, 0.0395, lz)))

    # Throw pillows resting cleanly ON the seat surface (Y_min >= 0.356) and clear of arms & back cushions!
    p1 = make_rounded_box((0.33, 0.33, 0.105), radius=0.036, segments=2, center=(-1.08, 0.522, 0.00), color=(204, 142, 54, 255))
    p1.rotate_axis_angle(math.radians(24.0), (0.0, 1.0, 0.0), pivot=(-1.08, 0.522, 0.00))
    add("accent_decor", "pillow_left", p1)

    p2 = make_rounded_box((0.33, 0.33, 0.105), radius=0.036, segments=2, center=(1.55, 0.522, -0.02), color=(178, 82, 62, 255))
    p2.rotate_axis_angle(math.radians(-26.0), (0.0, 1.0, 0.0), pivot=(1.55, 0.522, -0.02))
    add("accent_decor", "pillow_right", p2)

    # =========================================================================
    # 4. WOOL RUG, WALNUT COFFEE TABLE & ORGANIC POTTED PLANT
    # Rug positioned cleanly in front of main sofa & left of chaise (zero leg overlap!)
    # =========================================================================
    rug_cx, rug_z = -0.22, -1.55
    add("rug_wool", "rug", make_rounded_box((2.16, 0.012, 1.98), radius=0.005, segments=1, center=(rug_cx, 0.007, rug_z), uv_scale=1.6))

    tw, td, th = 1.04, 0.52, 0.34
    table_cx = -0.18
    # Legs rest cleanly ON TOP of rug (Y = 0.014 -> 0.324)
    leg_h = 0.310
    for tx in (table_cx - tw * 0.5 + 0.09, table_cx + tw * 0.5 - 0.09):
        for tz in (rug_z - td * 0.5 + 0.09, rug_z + td * 0.5 - 0.09):
            add("matte_dark", "table_legs", make_cylinder(0.014, leg_h, sections=10, center=(tx, 0.014 + leg_h * 0.5, tz)))
    # Lower shelf fits strictly between the 4 legs (clear of leg radii!)
    add("matte_dark", "table_shelf", make_box((tw - 0.22, 0.014, td - 0.22), center=(table_cx, 0.115, rug_z)))
    # Walnut tabletop sits cleanly on top of legs (Y[0.325, 0.357])
    add("wood_walnut", "table_top", make_rounded_box((tw, 0.032, td), radius=0.022, segments=2, center=(table_cx, 0.341, rug_z), uv_scale=1.1))

    # Coffee-table art book + ceramic pot with modeled plant leaves resting on tabletop (Y >= 0.358)
    add("accent_decor", "coffee_book", make_box((0.25, 0.022, 0.19), center=(table_cx - 0.24, 0.369, rug_z - 0.04), color=(204, 196, 182, 255)))
    add("accent_decor", "coffee_bookmark", make_box((0.12, 0.007, 0.18), center=(table_cx - 0.30, 0.3845, rug_z - 0.04), color=(178, 72, 54, 255)))
    add_potted_plant(builder, checker, cx=table_cx + 0.25, base_y=0.358, cz=rug_z + 0.04)

    # =========================================================================
    # 5. PANORAMIC WINDOW (NON-OVERLAPPING FRAME) & PLEATED CURTAINS
    # =========================================================================
    bg_x = -4.20
    bg_w = win_w + 3.00  # 6.20m -> ~3.0mm/texel at 2048px (native Quest 3 PPD sharpness + full 6DoF parallax)
    bg_h = win_h + 1.60  # 3.60m
    pane_pos = np.array([
        [bg_x, win_yc - bg_h * 0.5, win_zc + bg_w * 0.5],
        [bg_x, win_yc - bg_h * 0.5, win_zc - bg_w * 0.5],
        [bg_x, win_yc + bg_h * 0.5, win_zc - bg_w * 0.5],
        [bg_x, win_yc + bg_h * 0.5, win_zc + bg_w * 0.5],
    ], dtype=np.float32)
    pane_nrm = np.tile(np.array([[1.0, 0.0, 0.0]], dtype=np.float32), (4, 1))
    pane_uv = np.array([[0.0, 1.0], [1.0, 1.0], [1.0, 0.0], [0.0, 0.0]], dtype=np.float32)
    pane_col = np.ones((4, 4), dtype=np.float32)
    pane_idx = np.array([0, 1, 2, 0, 2, 3], dtype=np.uint32)
    add("window_night", "window_backdrop", MeshPart(pane_pos, pane_nrm, pane_uv, pane_col, pane_idx))

    # Window frame fitted strictly inside wall opening (Z[-2.40, +0.80], Y[0.52, 2.52], X[-2.72, -2.59])
    frame_th = 0.044
    inner_h = win_h - 2.0 * frame_th
    add("matte_dark", "win_sill_bot", make_box((0.13, frame_th, win_w), center=(-2.655, win_y0 + frame_th * 0.5, win_zc)))
    add("matte_dark", "win_sill_top", make_box((0.13, frame_th, win_w), center=(-2.655, win_y1 - frame_th * 0.5, win_zc)))
    add("matte_dark", "win_jamb_front", make_box((0.13, inner_h, frame_th), center=(-2.655, win_yc, win_z0 + frame_th * 0.5)))
    add("matte_dark", "win_jamb_back", make_box((0.13, inner_h, frame_th), center=(-2.655, win_yc, win_z1 - frame_th * 0.5)))
    add("matte_dark", "win_mullion_1", make_box((0.08, inner_h, 0.026), center=(-2.655, win_yc, win_zc - win_w / 6.0)))
    add("matte_dark", "win_mullion_2", make_box((0.08, inner_h, 0.026), center=(-2.655, win_yc, win_zc + win_w / 6.0)))

    # Curtain track rail & sinusoidally pleated linen curtains gathered cleanly outside glass & baseboard
    add("matte_dark", "curtain_rail", make_cylinder(0.012, win_w + 1.24, sections=10, center=(-2.46, 2.76, win_zc), axis=(0.0, 0.0, 1.0)))
    for cname, cz_curt in (("curtain_front", win_z0 - 0.32), ("curtain_back", win_z1 + 0.32)):
        curt = make_pleated_curtain(width=0.58, height=2.70, amplitude=0.040, pleats=4.0, segs_w=20, segs_h=4, uv_scale=1.6)
        curt.rotate_axis_angle(math.pi * 0.5, (0.0, 1.0, 0.0))
        curt.translate((-2.46, 1.37, cz_curt))  # X[-2.50, -2.42], Y[0.02, 2.72] -> clear of wall/baseboard/rail!
        add("curtain_linen", cname, curt)

    # =========================================================================
    # 6. TRUE CURVED BRASS ARC FLOOR LAMP (FREE CORNER, ZERO OVERLAP)
    # Grouped as "arc_lamp" so the curved stem's concave AABB doesn't self-flag its hanging shade.
    # =========================================================================
    base_x, base_z = -2.14, -2.16
    add("matte_dark", "arc_lamp", make_cylinder(0.17, 0.044, sections=18, center=(base_x, 0.022, base_z)))
    arc_pts = []
    p0 = np.array([base_x, 0.045, base_z], dtype=np.float32)
    p1 = np.array([base_x - 0.02, 1.56, base_z - 0.02], dtype=np.float32)
    p2 = np.array([base_x + 0.16, 2.18, base_z + 0.20], dtype=np.float32)
    p3 = np.array([base_x + 0.44, 1.92, base_z + 0.42], dtype=np.float32)
    for step in range(18):
        t = step / 17.0
        mt = 1.0 - t
        pt = (mt ** 3) * p0 + 3 * (mt ** 2) * t * p1 + 3 * mt * (t ** 2) * p2 + (t ** 3) * p3
        arc_pts.append(pt)
    add("brass_metal", "arc_lamp", make_tube_along_path(arc_pts, radius=0.012, sections=10))

    # Socket, open drum shade, and bulb suspended cleanly from p3
    shade_c = (float(p3[0]), float(p3[1]) - 0.165, float(p3[2]))
    add("brass_metal", "arc_lamp", make_cylinder(0.032, 0.040, sections=12, center=(shade_c[0], shade_c[1] + 0.140, shade_c[2])))
    add("emissive_shade", "arc_lamp", make_cylinder(0.185, 0.24, sections=20, center=shade_c, radius_top=0.125, caps=False))
    add("emissive_warm", "arc_lamp", make_cylinder(0.044, 0.070, sections=12, center=(shade_c[0], shade_c[1] + 0.015, shade_c[2])))

    # =========================================================================
    # 7. RIGHT WALL ART GALLERY & RECESSED CEILING SPOTLIGHTS (CLEAN LAYERS)
    # =========================================================================
    art_palette = [(176, 80, 56, 255), (76, 120, 106, 255), (58, 84, 122, 255)]
    for idx, az in enumerate((-1.45, -0.25, 0.95)):
        add("wood_walnut", f"art_frame_{idx}", make_box((0.022, 0.86, 0.66), center=(2.588, 1.52, az)))
        add("accent_decor", f"art_mat_{idx}", make_box((0.008, 0.76, 0.56), center=(2.572, 1.52, az), color=(216, 210, 200, 255)))
        add("accent_decor", f"art_canvas_{idx}", make_box((0.006, 0.52, 0.38), center=(2.564, 1.52, az), color=art_palette[idx]))

    for idx, (sx, sz) in enumerate(((-1.25, -1.60), (1.25, -1.60), (0.0, -2.55), (1.25, 0.35), (-1.25, 0.35))):
        add("matte_dark", f"spot_trim_{idx}", make_cylinder(0.068, 0.010, sections=14, center=(sx, 2.894, sz)))
        add("emissive_warm", f"spot_lens_{idx}", make_cylinder(0.044, 0.008, sections=12, center=(sx, 2.884, sz)))

    checker.assert_no_collisions("living_room", tolerance=0.002)
    return builder


def living_room_linear_bake(verts: np.ndarray, normals: np.ndarray) -> np.ndarray:
    """Linear AO + warm corner lamp & subtle cool window spill for realistic evening lounge depth."""
    n = len(verts)
    ao = np.ones((n, 1), dtype=np.float32)

    # Floor & ceiling contact AO
    ao *= (0.76 + 0.24 * np.clip(verts[:, 1:2] / 0.22, 0.0, 1.0))
    ao *= (0.84 + 0.16 * np.clip((2.90 - verts[:, 1:2]) / 0.25, 0.0, 1.0))

    # Wall corners
    dx_w = np.minimum(np.abs(verts[:, 0:1] + 2.60), np.abs(verts[:, 0:1] - 2.60))
    dz_w = np.minimum(np.abs(verts[:, 2:3] + 3.14), np.abs(verts[:, 2:3] - 2.26))
    ao *= (0.80 + 0.20 * np.clip(np.minimum(dx_w, dz_w) / 0.25, 0.0, 1.0))

    # Contact shadow under sofa, chaise & coffee table
    under_sofa = (verts[:, 0:1] > -1.52) & (verts[:, 0:1] < 1.98) & (verts[:, 2:3] > -0.40) & (verts[:, 2:3] < 0.54) & (verts[:, 1:2] < 0.12)
    under_chaise = (verts[:, 0:1] > 0.90) & (verts[:, 0:1] < 1.98) & (verts[:, 2:3] > -1.26) & (verts[:, 2:3] < -0.36) & (verts[:, 1:2] < 0.12)
    under_table = (verts[:, 0:1] > -0.74) & (verts[:, 0:1] < 0.38) & (verts[:, 2:3] > -1.84) & (verts[:, 2:3] < -1.26) & (verts[:, 1:2] < 0.15)
    ao[under_sofa | under_chaise] *= 0.62
    ao[under_table] *= 0.74

    # Warm corner glow from the arched floor lamp (-1.70, 1.76, -1.74)
    lamp_pt = np.array([[-1.70, 1.76, -1.74]], dtype=np.float32)
    l_vec = lamp_pt - verts
    l_dist = np.linalg.norm(l_vec, axis=1, keepdims=True)
    l_dir = l_vec / np.maximum(l_dist, 1e-5)
    l_diff = np.maximum(0.0, np.sum(normals * l_dir, axis=1, keepdims=True))
    l_atten = 0.42 / (1.0 + 0.50 * l_dist + 0.32 * l_dist * l_dist)

    # Subtle cool twilight spill from the left window
    win_spill = np.clip((2.60 - np.abs(verts[:, 0:1] + 2.60)) / 3.4, 0.0, 1.0) * 0.16

    tint = ao * (
        np.array([[0.82, 0.81, 0.84]], dtype=np.float32)
        + l_diff * l_atten * np.array([[0.58, 0.36, 0.16]], dtype=np.float32)
        + win_spill * np.array([[0.18, 0.26, 0.44]], dtype=np.float32)
    )
    return np.clip(tint, 0.22, 1.0)


def main() -> None:
    print("Building Evening Home Theater Living Room (512x512 Baked-Relief PBR + 0-Collision glTF 2.0)...")
    builder = build_living_room()
    stats = builder.build_and_export(MODEL_PATH, bake_fn=living_room_linear_bake, y_offset=EXPORT_Y_OFFSET)
    print(
        f"Saved {MODEL_PATH}: {stats['triangles']} triangles, {stats['vertices']} vertices, "
        f"{stats['materials']} materials, {stats['textures']} textures, {stats['bytes'] / 1024:.1f} KB"
    )


if __name__ == "__main__":
    main()
