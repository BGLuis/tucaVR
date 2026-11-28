#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate_cinema_env.py — Architectural Grand IMAX / THX Stadium Cinema generator for tucaVR.

Design goals (SMPTE EG 18 / THX / IMAX stadium sightline blueprint):
- Monumental elevated 16:9 screen: 8.00m x 4.50m at Z = -7.80m, Y = 3.05m (exported; modeling Y = 2.75m).
  - Horizontal FOV = 54.3 deg (THX/IMAX sweet spot).
  - Screen bottom at Y_design = +0.50m (+1.75m above front pit floor, +1.05m above stage).
  - Screen top at Y_design = +5.00m (+26.0 deg elevation — strictly inside SMPTE 30 deg comfort limit).
- Steep stadium bowl rake (40-44 cm risers, 4.30m clear proscenium distance from front row to screen):
  - Row 0 (Z=-3.50, Y=-0.84) & Row 1 (Z=-1.80, Y=-0.44) sit dramatically below the VIP eye line.
  - Sightline clearance (C-value) over Row 1 headrests = +498 mm (> 3x SMPTE 150mm standard),
    verified automatically by `assert_cinema_sightlines()`.
- Zero geometric collisions certified by `CollisionChecker` (<= 2mm tolerance):
  - All 12 wall sconces & cylindrical lamps mounted completely OUTSIDE the ribbed acoustic panels.
  - All surround speakers on dedicated brackets outside the panels.
  - Wainscot chair rails cleanly segmented between walnut pilasters.
  - 2-step building-code side aisle stairs separated from central step LEDs.
- 512x512 baked-relief PBR textures at zero extra GPU shader cost.
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
    tex_acoustic_ribbed,
    tex_carpet_artdeco,
    tex_fabric,
    tex_leather,
    tex_wood,
)

PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
OUTPUT_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "environments", "cinema")
MODEL_PATH = os.path.join(OUTPUT_DIR, "model.glb")

# Runtime anchors eye at Y = 1.50m on recenter. Scene is modeled with eye at Y = 1.20m and shifted +0.30m on export.
DESIGN_EYE_Y = 1.20
RUNTIME_EYE_Y = 1.50
EXPORT_Y_OFFSET = RUNTIME_EYE_Y - DESIGN_EYE_Y

# Canonical SMPTE / THX / IMAX screen dimensions in design coordinates
SCREEN_W = 8.00
SCREEN_H = 4.50
SCREEN_Y_CENTER = 2.75   # Exported Y = 3.05m (Bottom = +0.50m, Top = +5.00m)
SCREEN_Z = -7.80


def add_cinema_seat(
    builder: GlbSceneBuilder,
    checker: CollisionChecker,
    group_name: str,
    center,
    angle_deg: float = 0.0,
    high_detail: bool = True,
) -> float:
    """Ergonomic VIP cinema seat with LOD control. Returns world-space top-of-headrest Y for sightline verification."""
    cx, cy, cz = center
    ang = math.radians(angle_deg)
    segs = 2 if high_detail else 1
    cyl_segs = 10 if high_detail else 6

    col_velvet = (255, 255, 255, 255)
    col_head = (248, 236, 238, 255)
    col_leather = (255, 255, 255, 255)
    col_steel = (225, 230, 240, 255)
    col_cup = (150, 156, 168, 255)

    seat_w, seat_d, seat_h = 0.54, 0.50, 0.12
    max_y = cy

    def place(mat_name: str, part: MeshPart) -> None:
        nonlocal max_y
        if abs(ang) > 1e-4:
            part.rotate_axis_angle(ang, (0.0, 1.0, 0.0))
        part.translate((cx, cy + 0.002, cz))
        max_y = max(max_y, float(part.positions[:, 1].max()))
        builder.add_mesh(mat_name, checker.register(group_name, part))

    # Pedestal & structural shell
    place("metal_dark", make_box((0.26, 0.026, 0.30), center=(0.0, 0.013, 0.0), color=col_steel))
    place("metal_dark", make_cylinder(0.042, 0.226, sections=cyl_segs, center=(0.0, 0.140, 0.0), color=col_steel))
    place("metal_dark", make_box((seat_w, 0.052, seat_d), center=(0.0, 0.280, 0.0), color=col_steel))

    # Cushions & reclined backrest (ruby velvet)
    place("velvet_ruby", make_rounded_box((seat_w - 0.03, seat_h, seat_d - 0.03), radius=0.03, segments=segs, center=(0.0, 0.368, -0.01), color=col_velvet, uv_scale=2.2))

    back = make_rounded_box((seat_w - 0.04, 0.50, 0.10), radius=0.03, segments=segs, center=(0.0, 0.635, 0.21), color=col_velvet, uv_scale=2.2)
    back.rotate_axis_angle(math.radians(-9.0), (1.0, 0.0, 0.0), pivot=(0.0, 0.38, 0.21))
    place("velvet_ruby", back)

    head = make_rounded_box((seat_w - 0.08, 0.17, 0.115), radius=0.032, segments=segs, center=(0.0, 0.915, 0.245), color=col_head, uv_scale=2.2)
    head.rotate_axis_angle(math.radians(-9.0), (1.0, 0.0, 0.0), pivot=(0.0, 0.38, 0.21))
    place("velvet_ruby", head)

    # Pebbled leather armrests + metallic cup holders
    arm_w, arm_h, arm_d = 0.095, 0.23, 0.45
    for side in (-1.0, 1.0):
        ax = side * (seat_w * 0.5 + arm_w * 0.5 - 0.010)
        place("leather_arm", make_rounded_box((arm_w, arm_h, arm_d), radius=0.022, segments=1, center=(ax, 0.46, 0.02), color=col_leather, uv_scale=3.0))
        place("metal_dark", make_cylinder(0.038, 0.034, sections=cyl_segs, center=(ax, 0.592, -0.14), color=col_cup))

    return max_y


def assert_cinema_sightlines(front_rows_top_y) -> None:
    """
    Verifies architectural SMPTE/THX sightline clearance from the VIP viewer's eye (0, 1.20, 0)
    to the bottom edge of the screen (Y = +0.50, Z = -7.80) over every row in front.
    """
    screen_bottom_y = SCREEN_Y_CENTER - SCREEN_H * 0.5
    screen_top_y = SCREEN_Y_CENTER + SCREEN_H * 0.5
    dist_screen = abs(SCREEN_Z)

    hfov_deg = 2.0 * math.degrees(math.atan((SCREEN_W * 0.5) / dist_screen))
    top_angle_deg = math.degrees(math.atan((screen_top_y - DESIGN_EYE_Y) / dist_screen))
    center_angle_deg = math.degrees(math.atan((SCREEN_Y_CENTER - DESIGN_EYE_Y) / dist_screen))
    assert top_angle_deg <= 30.0, f"Top-of-screen angle {top_angle_deg:.1f} deg exceeds SMPTE 30 deg comfort limit!"

    for row_name, row_z, head_top_y in front_rows_top_y:
        d_row = abs(row_z)
        # Height of the eye-to-screen-bottom ray as it passes over this row
        ray_y_at_row = DESIGN_EYE_Y + (screen_bottom_y - DESIGN_EYE_Y) * (d_row / dist_screen)
        c_clearance_m = ray_y_at_row - head_top_y
        # Where the grazing ray over the headrest hits the screen plane
        grazing_y_at_screen = DESIGN_EYE_Y + (head_top_y - DESIGN_EYE_Y) * (dist_screen / d_row)
        margin_below_screen = screen_bottom_y - grazing_y_at_screen
        assert c_clearance_m >= 0.35, (
            f"Sightline obstructed by {row_name}: clearance={c_clearance_m * 1000:.1f}mm < 350mm"
        )
        print(
            f"  [Sightline] {row_name} (Z={row_z:.2f}m, head_top={head_top_y:.2f}m): "
            f"C-clearance=+{c_clearance_m * 1000:.0f}mm, screen_margin=+{margin_below_screen:.2f}m"
        )
    print(
        f"  [Sightline] Screen {SCREEN_W:.2f}x{SCREEN_H:.2f}m @ Z={SCREEN_Z:.2f}m: "
        f"HFOV={hfov_deg:.1f} deg, Center=+{center_angle_deg:.1f} deg, Top=+{top_angle_deg:.1f} deg (<= 30 deg OK)"
    )


def build_cinema() -> GlbSceneBuilder:
    builder = GlbSceneBuilder()
    checker = CollisionChecker()

    def add(mat: str, group: str, part: MeshPart) -> None:
        builder.add_mesh(mat, checker.register(group, part))

    # 512x512 Baked-Relief PBR textures
    velvet_base, velvet_orm = tex_fabric((158, 28, 42), size=512, seed=101, weave_freq=60, roughness=0.88, contrast=0.85)
    curtain_base, curtain_orm = tex_fabric((56, 22, 30), size=512, seed=102, weave_freq=56, roughness=0.90, contrast=0.95)
    carpet_base, carpet_orm = tex_carpet_artdeco((48, 50, 62), (108, 46, 56), size=512, seed=103)
    wall_base, wall_orm = tex_acoustic_ribbed((74, 78, 92), (26, 28, 36), size=512, seed=104, ribs=16, roughness=0.88)
    wood_base, wood_orm = tex_wood((142, 92, 56), (80, 48, 28), size=512, seed=105, planks=3, roughness=0.40)
    leather_base, leather_orm = tex_leather((60, 62, 72), size=512, seed=106, roughness=0.38)

    builder.add_material(MaterialSpec("wall_acoustic", base_texture=wall_base, orm_texture=wall_orm, roughness_factor=1.0, metallic_factor=0.0, jpeg_quality=90))
    builder.add_material(MaterialSpec("carpet", base_texture=carpet_base, orm_texture=carpet_orm, roughness_factor=1.0, metallic_factor=0.0, jpeg_quality=90))
    builder.add_material(MaterialSpec("velvet_ruby", base_texture=velvet_base, orm_texture=velvet_orm, roughness_factor=1.0, metallic_factor=0.0, jpeg_quality=90))
    builder.add_material(MaterialSpec("curtain_dark", base_texture=curtain_base, orm_texture=curtain_orm, roughness_factor=1.0, metallic_factor=0.0, double_sided=True, jpeg_quality=88))
    builder.add_material(MaterialSpec("leather_arm", base_texture=leather_base, orm_texture=leather_orm, roughness_factor=1.0, metallic_factor=0.0, jpeg_quality=90))
    builder.add_material(MaterialSpec("wood_walnut", base_texture=wood_base, orm_texture=wood_orm, roughness_factor=1.0, metallic_factor=0.0, jpeg_quality=90))
    builder.add_material(MaterialSpec("metal_dark", base_color_factor=(0.065, 0.072, 0.088, 1.0), metallic_factor=0.72, roughness_factor=0.32))
    builder.add_material(MaterialSpec("matte_black", base_color_factor=(0.018, 0.020, 0.025, 1.0), metallic_factor=0.05, roughness_factor=0.92))
    builder.add_material(MaterialSpec("emissive_amber", base_color_factor=(0.15, 0.08, 0.02, 1.0), emissive_factor=(1.0, 0.56, 0.14), emissive_strength=1.35, roughness_factor=0.4, bake_lighting=False))
    builder.add_material(MaterialSpec("emissive_sconce", base_color_factor=(0.24, 0.16, 0.06, 1.0), emissive_factor=(1.0, 0.74, 0.34), emissive_strength=1.55, roughness_factor=0.35, bake_lighting=False))
    builder.add_material(MaterialSpec("emissive_booth", base_color_factor=(0.04, 0.07, 0.12, 1.0), emissive_factor=(0.24, 0.46, 0.80), emissive_strength=1.10, roughness_factor=0.25, bake_lighting=False))
    builder.add_material(MaterialSpec("emissive_exit", base_color_factor=(0.02, 0.10, 0.05, 1.0), emissive_factor=(0.10, 0.78, 0.34), emissive_strength=1.05, roughness_factor=0.35, bake_lighting=False))

    # =========================================================================
    # 1. GRAND AUDITORIUM ENVELOPE (Width 13.6m, Height 7.45m, Depth 15.2m)
    # Interior bounds: X[-6.80, +6.80], Y[-1.25, +6.20], Z[-8.80, +6.40]
    # =========================================================================
    room_w = 13.60
    room_h = 7.45
    room_d = 15.20
    y_floor_pit = -1.25
    y_ceil = 6.20
    z_front = -8.80
    z_back = 6.40
    room_yc = 0.5 * (y_floor_pit + y_ceil)
    room_zc = 0.5 * (z_front + z_back)

    add("matte_black", "wall_front", make_box((room_w, room_h, 0.16), center=(0.0, room_yc, z_front - 0.08), subdiv=(4, 3, 1)))
    add("wall_acoustic", "wall_back", make_box((room_w, room_h, 0.16), center=(0.0, room_yc, z_back + 0.08), uv_scale=0.45, subdiv=(6, 4, 1)))
    add("wall_acoustic", "wall_left", make_box((0.16, room_h, room_d), center=(-6.88, room_yc, room_zc), uv_scale=0.45, subdiv=(1, 5, 9)))
    add("wall_acoustic", "wall_right", make_box((0.16, room_h, room_d), center=(6.88, room_yc, room_zc), uv_scale=0.45, subdiv=(1, 5, 9)))
    add("matte_black", "ceiling", make_box((room_w, 0.14, room_d), center=(0.0, y_ceil + 0.07, room_zc), subdiv=(4, 1, 6)))
    add("carpet", "subfloor", make_box((room_w, 0.12, room_d), center=(0.0, y_floor_pit - 0.06, room_zc), uv_scale=0.65, subdiv=(6, 1, 8)))

    # =========================================================================
    # 2. PROSCENIUM STAGE, ELEVATED IMAX SCREEN FRAME & PLEATED CURTAINS
    # Screen: 8.00m x 4.50m @ Z = -7.80m, Y_center = +2.75m (Bottom=+0.50m, Top=+5.00m)
    # =========================================================================
    sw, sh = SCREEN_W, SCREEN_H
    sy_c = SCREEN_Y_CENTER
    sz_screen = SCREEN_Z

    # Theatrical low proscenium stage (Y[-1.248, -0.550], Z[-8.78, -6.90]) — +1.05m below screen bottom!
    add("wood_walnut", "stage", make_rounded_box((12.40, 0.698, 1.88), radius=0.04, segments=2, center=(0.0, -0.899, -7.84), uv_scale=0.60))
    add("emissive_amber", "stage_led", make_box((11.40, 0.020, 0.022), center=(0.0, -0.585, -6.885)))

    # Chamfered brushed titanium screen bezel (non-overlapping 4-bar frame behind video plane)
    f_th = 0.080
    f_d = 0.052
    fz = sz_screen - 0.055  # Z[-7.881, -7.829]
    add("metal_dark", "bezel_top", make_box((sw + 2 * f_th, f_th, f_d), center=(0.0, sy_c + sh * 0.5 + f_th * 0.5, fz)))
    add("metal_dark", "bezel_bot", make_box((sw + 2 * f_th, f_th, f_d), center=(0.0, sy_c - sh * 0.5 - f_th * 0.5, fz)))
    add("metal_dark", "bezel_left", make_box((f_th, sh, f_d), center=(-sw * 0.5 - f_th * 0.5, sy_c, fz)))
    add("metal_dark", "bezel_right", make_box((f_th, sh, f_d), center=(sw * 0.5 + f_th * 0.5, sy_c, fz)))
    add("matte_black", "screen_mask", make_box((sw + 2 * f_th, sh + 2 * f_th, 0.018), center=(0.0, sy_c, fz - 0.038)))

    # Pleated proscenium curtains framing left & right of the 8.0m screen (X[±4.20..±6.68], above stage -0.55)
    for side, cname in ((-1.0, "curtain_left"), (1.0, "curtain_right")):
        curtain = make_pleated_curtain(
            width=2.48,
            height=6.60,
            amplitude=0.065,
            pleats=6.0,
            segs_w=28,
            segs_h=4,
            center=(side * 5.44, 2.78, -7.90),
            uv_scale=1.15,
        )
        add("curtain_dark", cname, curtain)

    # =========================================================================
    # 3. STEEP STADIUM TIERS (40-44cm RAKE) & 2-STEP SIDE AISLE STAIRCASES
    # =========================================================================
    tiers = [
        {"y": -0.84, "z_min": -4.35, "z_max": -2.65},  # Row 0 (4.30m clear from screen!)
        {"y": -0.44, "z_min": -2.65, "z_max": -0.95},  # Row 1
        {"y":  0.00, "z_min": -0.95, "z_max":  1.05},  # Row 2 (VIP user row, eye at 0, 1.20, 0)
        {"y":  0.44, "z_min":  1.05, "z_max":  3.15},  # Row 3
        {"y":  0.88, "z_min":  3.15, "z_max":  6.40},  # Rear walkway & booth
    ]

    for idx, t in enumerate(tiers):
        t_d = t["z_max"] - t["z_min"]
        t_z = 0.5 * (t["z_min"] + t["z_max"])
        t_y = t["y"]
        block_h = max(0.20, t_y - (y_floor_pit + 0.001))
        add("carpet", f"tier_{idx}", make_box((room_w, block_h, t_d - 0.002), center=(0.0, y_floor_pit + 0.001 + block_h * 0.5, t_z), uv_scale=0.65, subdiv=(4, 1, 2)))

        if idx > 0:
            prev_y = tiers[idx - 1]["y"]
            step_h = t_y - prev_y
            step_z = t["z_min"]
            # Full-width dark aluminum riser strictly in front of tier face
            add("metal_dark", f"riser_{idx}", make_box((room_w, step_h - 0.004, 0.032), center=(0.0, prev_y + step_h * 0.5, step_z - 0.019)))
            # Central amber step LED ends cleanly at X = ±5.05m (15cm clear of side stairs!)
            add("emissive_amber", f"step_led_{idx}", make_box((10.10, 0.020, 0.022), center=(0.0, t_y - 0.022, step_z - 0.048)))

            # 2 architectural intermediate steps (~14.5cm rise each) in both side aisles (|X| = 5.20..6.76)
            for side, sname in ((-1.0, "L"), (1.0, "R")):
                ax = side * 5.98
                h_upper = step_h * (2.0 / 3.0)
                h_lower = step_h * (1.0 / 3.0)
                # Upper intermediate step: Z[step_z - 0.28, step_z - 0.04]
                add("carpet", f"aisle_step_hi_{idx}_{sname}", make_box((1.56, h_upper - 0.003, 0.24), center=(ax, prev_y + 0.002 + (h_upper - 0.003) * 0.5, step_z - 0.16), uv_scale=0.65))
                add("emissive_amber", f"aisle_led_hi_{idx}_{sname}", make_box((1.34, 0.016, 0.018), center=(ax, prev_y + h_upper - 0.014, step_z - 0.292)))
                # Lower intermediate step: Z[step_z - 0.55, step_z - 0.31] (clear of upper LED & step!)
                add("carpet", f"aisle_step_lo_{idx}_{sname}", make_box((1.56, h_lower - 0.003, 0.24), center=(ax, prev_y + 0.002 + (h_lower - 0.003) * 0.5, step_z - 0.43), uv_scale=0.65))
                add("emissive_amber", f"aisle_led_lo_{idx}_{sname}", make_box((1.34, 0.016, 0.018), center=(ax, prev_y + h_lower - 0.014, step_z - 0.562)))

    # =========================================================================
    # 4. CURVED STADIUM SEATING (35 VIP SEATS WITH DISTANCE LOD + SIGHTLINE CHECK)
    # =========================================================================
    curve_center_z = SCREEN_Z
    seat_rows = [
        {"name": "Row 0 (Front)", "y": -0.84, "z": -3.50, "count": 8, "spacing": 0.90, "hi": False},
        {"name": "Row 1 (Mid)",   "y": -0.44, "z": -1.80, "count": 9, "spacing": 0.88, "hi": True},
        {"name": "Row 2 (VIP)",   "y":  0.00, "z":  0.00, "count": 9, "spacing": 0.88, "hi": True},
        {"name": "Row 3 (Rear)",  "y":  0.44, "z":  1.95, "count": 9, "spacing": 0.88, "hi": False},
    ]

    front_rows_top_y = []
    for r_idx, row in enumerate(seat_rows):
        n_seats = row["count"]
        spacing = row["spacing"]
        total_w = (n_seats - 1) * spacing
        row_max_y = row["y"]
        for s in range(n_seats):
            sx = -total_w * 0.5 + s * spacing
            dz = row["z"] - curve_center_z
            angle_deg = -math.degrees(math.atan2(sx, dz)) * 0.55
            seat_top_y = add_cinema_seat(
                builder, checker, f"seat_r{r_idx}_s{s}", (sx, row["y"], row["z"]), angle_deg=angle_deg, high_detail=row["hi"]
            )
            row_max_y = max(row_max_y, seat_top_y)
        if row["z"] < -0.1:
            front_rows_top_y.append((row["name"], row["z"], row_max_y))

    assert_cinema_sightlines(front_rows_top_y)

    # =========================================================================
    # 5. ART DECO SIDE WALLS: RIBBED ACOUSTIC BAYS, PILASTERS, EXTERIOR SCONCES & SURROUNDS
    # Wall inner face:        |X| = 6.800
    # Acoustic bay panels:    |X| = 6.795 .. 6.735  (inner face strictly at 6.735!)
    # Sconce backplate:       |X| = 6.732 .. 6.702  (3mm clean gap in front of bay)
    # Sconce metal housing:   |X| = 6.700 .. 6.630
    # Sconce emissive lamps:  |X| = 6.697 .. 6.633  (center 6.665, r=0.032 -> 3.8cm OUTSIDE panel!)
    # Surround speaker box:   |X| <= 6.675          (6.0cm OUTSIDE panel on dedicated bracket!)
    # =========================================================================
    panel_z_centers = [-5.8, -3.6, -1.4, 0.8, 3.0, 5.1]
    pilaster_z = [-6.82, -4.70, -2.50, -0.30, 1.90, 4.05, 6.12]

    for side, sname in ((-1.0, "L"), (1.0, "R")):
        # 7 Vertical walnut pilasters strictly between the acoustic bays
        for p_idx, plz in enumerate(pilaster_z):
            add("wood_walnut", f"pilaster_{sname}_{p_idx}", make_box((0.090, 4.50, 0.072), center=(side * 6.750, 3.40, plz), uv_scale=0.75))

        # Horizontal walnut wainscot chair rail segmented cleanly between pilasters (Y[1.22, 1.31], below bays Y>=1.35)
        for seg_idx in range(len(pilaster_z) - 1):
            z0 = pilaster_z[seg_idx] + 0.038
            z1 = pilaster_z[seg_idx + 1] - 0.038
            add("wood_walnut", f"chair_rail_{sname}_{seg_idx}", make_box((0.060, 0.090, z1 - z0), center=(side * 6.765, 1.265, 0.5 * (z0 + z1)), uv_scale=0.7))

        for idx, pz in enumerate(panel_z_centers):
            # Ribbed acoustic fabric bay (|X| = 6.795 .. 6.735, Y = [1.35, 5.45])
            add("wall_acoustic", f"acoustic_bay_{sname}_{idx}", make_box((0.060, 4.10, 1.76), center=(side * 6.765, 3.40, pz), uv_scale=0.60))

            # Art Deco wall sconce mounted 100% OUTSIDE the acoustic panel!
            sy = 3.15
            add("metal_dark", f"sconce_mount_{sname}_{idx}", make_box((0.030, 0.38, 0.16), center=(side * 6.717, sy, pz)))
            add("metal_dark", f"sconce_body_{sname}_{idx}", make_box((0.070, 0.30, 0.12), center=(side * 6.665, sy, pz)))
            add("emissive_sconce", f"sconce_lamp_top_{sname}_{idx}", make_cylinder(0.032, 0.062, sections=12, center=(side * 6.665, sy + 0.184, pz)))
            add("emissive_sconce", f"sconce_lamp_bot_{sname}_{idx}", make_cylinder(0.032, 0.062, sections=12, center=(side * 6.665, sy - 0.184, pz)))

            # Surround speakers mounted cleanly on brackets outside alternate bays
            if idx in (1, 3, 4):
                add("metal_dark", f"speaker_bracket_{sname}_{idx}", make_box((0.052, 0.14, 0.12), center=(side * 6.706, 4.65, pz)))
                spk = make_box((0.18, 0.44, 0.30), center=(side * 6.53, 4.65, pz))
                spk.rotate_axis_angle(math.radians(side * 15.0), (0.0, 0.0, 1.0), pivot=(side * 6.53, 4.65, pz))
                add("matte_black", f"speaker_{sname}_{idx}", spk)

        # Discreet green emergency exit sign in front of first pilaster (Z = -7.15)
        add("metal_dark", f"exit_box_{sname}", make_box((0.048, 0.18, 0.36), center=(side * 6.770, 1.68, -7.15)))
        add("emissive_exit", f"exit_face_{sname}", make_box((0.018, 0.11, 0.28), center=(side * 6.735, 1.68, -7.15)))

    # =========================================================================
    # 6. CEILING ACOUSTIC BAFFLES & REAR PROJECTION BOOTH
    # =========================================================================
    for b_idx, bz in enumerate((-6.0, -3.8, -1.6, 0.6, 2.8, 4.9)):
        add("wall_acoustic", f"ceiling_baffle_{b_idx}", make_box((11.8, 0.28, 0.15), center=(0.0, 6.05, bz), uv_scale=0.50))

    # Rear projection booth (Z = 6.40m, Y = 4.25m, 4 non-overlapping metal frame bars, recessed glass, forward lens)
    bw, bh, f_b = 2.00, 0.44, 0.14
    by = 4.25
    add("metal_dark", "booth_frame_top", make_box((bw + 2 * f_b, f_b, 0.075), center=(0.0, by + bh * 0.5 + f_b * 0.5, 6.358)))
    add("metal_dark", "booth_frame_bot", make_box((bw + 2 * f_b, f_b, 0.075), center=(0.0, by - bh * 0.5 - f_b * 0.5, 6.358)))
    add("metal_dark", "booth_frame_left", make_box((f_b, bh, 0.075), center=(-bw * 0.5 - f_b * 0.5, by, 6.358)))
    add("metal_dark", "booth_frame_right", make_box((f_b, bh, 0.075), center=(bw * 0.5 + f_b * 0.5, by, 6.358)))
    add("emissive_booth", "booth_glass", make_box((bw - 0.004, bh - 0.004, 0.036), center=(0.0, by, 6.376)))
    add("emissive_sconce", "booth_lens", make_cylinder(0.060, 0.052, sections=12, center=(0.0, by, 6.320), axis=(0.0, 0.0, 1.0)))

    checker.assert_no_collisions("cinema", tolerance=0.002)
    return builder


def cinema_linear_bake(verts: np.ndarray, normals: np.ndarray) -> np.ndarray:
    """Computes clean linear AO + localized warm sconce/step bounce pools."""
    n = len(verts)
    ao = np.ones((n, 1), dtype=np.float32)

    y_floor = np.clip((verts[:, 1:2] + 1.25) / 0.55, 0.0, 1.0)
    ao *= (0.72 + 0.28 * y_floor)

    d_side = np.minimum(np.abs(verts[:, 0:1] + 6.80), np.abs(verts[:, 0:1] - 6.80))
    d_ceil = np.abs(6.20 - verts[:, 1:2])
    ao *= (0.78 + 0.22 * np.clip(d_side / 0.50, 0.0, 1.0))
    ao *= (0.82 + 0.18 * np.clip(d_ceil / 0.45, 0.0, 1.0))

    # Localized warm up/down sconce light pools on the ribbed acoustic panels
    sconce_contrib = np.zeros((n, 1), dtype=np.float32)
    for side in (-1.0, 1.0):
        sx = side * 6.66
        for pz in (-5.8, -3.6, -1.4, 0.8, 3.0, 5.1):
            dx = verts[:, 0:1] - sx
            dy = (verts[:, 1:2] - 3.15) * 0.65
            dz = verts[:, 2:3] - pz
            d2 = dx * dx + dy * dy + dz * dz
            sconce_contrib += 0.38 / (1.0 + d2 * 1.15)

    # Warm floor wash along step LED risers
    step_contrib = np.zeros((n, 1), dtype=np.float32)
    for sz, sy in ((-2.65, -0.44), (-0.95, 0.00), (1.05, 0.44), (3.15, 0.88)):
        dz = np.abs(verts[:, 2:3] - sz)
        dy = np.abs(verts[:, 1:2] - sy)
        mask = (dz < 0.85) & (dy < 0.55)
        step_contrib += np.where(mask, (1.0 - dz / 0.85) * (1.0 - dy / 0.55) * 0.24, 0.0)

    tint = ao * (
        np.array([[0.86, 0.86, 0.90]], dtype=np.float32)
        + sconce_contrib * np.array([[0.46, 0.28, 0.10]], dtype=np.float32)
        + step_contrib * np.array([[0.38, 0.20, 0.06]], dtype=np.float32)
    )
    return np.clip(tint, 0.22, 1.0)


def main() -> None:
    print("Building Grand IMAX/THX Stadium Cinema (512x512 Baked-Relief PBR + Sightlines + 0-Collision glTF 2.0)...")
    builder = build_cinema()
    stats = builder.build_and_export(MODEL_PATH, bake_fn=cinema_linear_bake, y_offset=EXPORT_Y_OFFSET)
    print(
        f"Saved {MODEL_PATH}: {stats['triangles']} triangles, {stats['vertices']} vertices, "
        f"{stats['materials']} materials, {stats['textures']} textures, {stats['bytes'] / 1024:.1f} KB"
    )


if __name__ == "__main__":
    main()
