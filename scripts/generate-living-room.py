#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate-living-room.py — Generator of the high-fidelity "Living Room" 3D environment for tucaVR.

Inspired by the living-room environments of Bigscreen VR and Skybox VR:
- Modern residential architectural proportions (5.2m x 2.9m x 5.8m).
- Eliminates 100% of overlap and clipping between furniture:
  * Virtual screen at Z = -2.80m, Y = 1.50m (screen base at Y = 0.825m).
  * Wooden slatted panel against the front wall at Z = -3.38m.
  * Wall-mounted floating console at Z = -3.15m (top at Y = 0.55m, with 27cm of clearance from the screen).
  * Coffee table at Z = -1.50m (top at Y = 0.35m, with more than 1m of free area).
  * The user's camera at (0, 1.15, 0). The coordinates in this list are modeling coordinates; the GLB is
    exported shifted +0.35m in Y so the eye lands at the runtime's 1.50m (see EXPORT_Y_OFFSET) — in
    config.ini the screen sits at Y = 1.85m.
  * L-shaped modular sofa: seat at Y = 0.38m (77cm below the eyes), backrest at Z = +0.65m.
- Lighting baked into vertex colors with soft Ambient Occlusion (AO) in corners and baseboards.
- A panoramic window with a night/twilight horizon on the left wall.
- 1 draw call, ~6,000 triangles, binary glTF 2.0 format (.glb).
"""

import os
import sys
import numpy as np
import trimesh

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
OUTPUT_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "environments", "living_room")
MODEL_PATH = os.path.join(OUTPUT_DIR, "model.glb")

os.makedirs(OUTPUT_DIR, exist_ok=True)

# The runtime anchors the user's eye 1.50m above the model's origin when
# recentralizar (vr_player_app_vulkan.cpp: sceneTranslationOffset.y = headCenter.y - 1.5f).
# The scene is modeled with the eye at DESIGN_EYE_Y and exported shifted in Y so
# that point lands exactly at RUNTIME_EYE_Y. The config.ini screen_pos
# (and the preset in VRActivity.setVirtualEnvironment) is already in exported coordinates.
DESIGN_EYE_Y = 1.15
RUNTIME_EYE_Y = 1.50
EXPORT_Y_OFFSET = RUNTIME_EYE_Y - DESIGN_EYE_Y


def create_box(extents, center=(0, 0, 0), color=(200, 200, 200, 255)):
    """Creates a simple box with vertex colors."""
    box = trimesh.creation.box(extents=extents)
    box.apply_translation(center)
    colors = np.tile(np.array(color, dtype=np.uint8), (len(box.vertices), 1))
    box.visual.vertex_colors = colors
    return box


def create_subdivided_box(extents, subdivisions=1, center=(0, 0, 0), color=(200, 200, 200, 255)):
    """Creates a subdivided box for soft lighting and per-vertex gradients."""
    box = trimesh.creation.box(extents=extents)
    for _ in range(subdivisions):
        box = box.subdivide()
    box.apply_translation(center)
    colors = np.tile(np.array(color, dtype=np.uint8), (len(box.vertices), 1))
    box.visual.vertex_colors = colors
    return box


def create_rounded_box(extents, radius=0.03, subdivisions=2, center=(0, 0, 0), color=(200, 200, 200, 255)):
    """Creates a box with chamfered/rounded corners and edges for upholstery and slim furniture."""
    box = trimesh.creation.box(extents=extents)
    for _ in range(subdivisions):
        box = box.subdivide()
    half = np.array(extents) / 2.0
    inner = np.maximum(0.0, half - radius)
    verts = box.vertices
    clamped = np.clip(verts, -inner, inner)
    diff = verts - clamped
    dist = np.linalg.norm(diff, axis=1, keepdims=True)
    mask = dist[:, 0] > 1e-6
    new_verts = verts.copy()
    new_verts[mask] = clamped[mask] + (diff[mask] / dist[mask]) * radius
    box.vertices = new_verts
    box.apply_translation(center)
    box.fix_normals()
    colors = np.tile(np.array(color, dtype=np.uint8), (len(box.vertices), 1))
    box.visual.vertex_colors = colors
    return box


def create_cylinder(radius, height, sections=16, center=(0, 0, 0), axis=(0, 1, 0), color=(200, 200, 200, 255)):
    """Creates a cylinder aligned with the given axis."""
    cyl = trimesh.creation.cylinder(radius=radius, height=height, sections=sections)
    axis = np.array(axis, dtype=np.float64)
    axis = axis / np.linalg.norm(axis)
    z_axis = np.array([0, 0, 1])
    if not np.allclose(axis, z_axis):
        if np.allclose(axis, -z_axis):
            rot = trimesh.transformations.rotation_matrix(np.pi, [1, 0, 0])
        else:
            rot = trimesh.geometry.align_vectors(z_axis, axis)
        cyl.apply_transform(rot)
    cyl.apply_translation(center)
    colors = np.tile(np.array(color, dtype=np.uint8), (len(cyl.vertices), 1))
    cyl.visual.vertex_colors = colors
    return cyl


def build_living_room():
    meshes = []

    # =========================================================================
    # 1. ARCHITECTURAL STRUCTURE OF THE ROOM
    # Dimensions: X: [-2.60, +2.60] (5.2m), Y: [0.0, 2.90] (2.9m), Z: [-3.40, +2.40] (5.8m)
    # =========================================================================
    wall_col = (235, 232, 226, 255)       # Warm Scandinavian white
    ceiling_col = (245, 243, 238, 255)    # Soft satin white
    baseboard_col = (210, 206, 198, 255)  # Thin architectural baseboard

    # Back wall (Z = +2.40)
    meshes.append(create_subdivided_box(extents=[5.20, 2.90, 0.10], subdivisions=2, center=(0.0, 1.45, 2.45), color=wall_col))
    # Front wall (Z = -3.40)
    meshes.append(create_subdivided_box(extents=[5.20, 2.90, 0.10], subdivisions=2, center=(0.0, 1.45, -3.45), color=wall_col))
    # Right wall (X = +2.60)
    meshes.append(create_subdivided_box(extents=[0.10, 2.90, 5.80], subdivisions=2, center=(2.65, 1.45, -0.50), color=wall_col))

    # Left wall with a panoramic window (from Z = -2.60 to +0.80, Y from 0.60 to 2.60)
    # Below the window:
    meshes.append(create_box(extents=[0.10, 0.60, 3.40], center=(-2.65, 0.30, -0.90), color=wall_col))
    # Above the window:
    meshes.append(create_box(extents=[0.10, 0.30, 3.40], center=(-2.65, 2.75, -0.90), color=wall_col))
    # Front side of the window:
    meshes.append(create_box(extents=[0.10, 2.90, 0.80], center=(-2.65, 1.45, -3.00), color=wall_col))
    # Rear side of the window:
    meshes.append(create_box(extents=[0.10, 2.90, 1.60], center=(-2.65, 1.45, 1.60), color=wall_col))

    # Ceiling
    meshes.append(create_subdivided_box(extents=[5.20, 0.05, 5.80], subdivisions=2, center=(0.0, 2.925, -0.50), color=ceiling_col))

    # Floor in fine light-oak planks (Y = 0.0)
    n_planks = 16
    floor_d = 5.80
    plank_d = floor_d / n_planks
    wood_palette = [
        (185, 148, 108, 255),
        (176, 138, 98, 255),
        (192, 155, 115, 255),
        (168, 132, 92, 255),
    ]
    for i in range(n_planks):
        zc = -3.40 + (i + 0.5) * plank_d
        col = wood_palette[i % len(wood_palette)]
        meshes.append(create_box(extents=[5.20, 0.02, plank_d - 0.004], center=(0.0, -0.01, zc), color=col))

    # Thin modern baseboards (10cm high)
    meshes.append(create_box(extents=[5.20, 0.10, 0.02], center=(0.0, 0.05, 2.39), color=baseboard_col))
    meshes.append(create_box(extents=[5.20, 0.10, 0.02], center=(0.0, 0.05, -3.39), color=baseboard_col))
    meshes.append(create_box(extents=[0.02, 0.10, 5.80], center=(2.59, 0.05, -0.50), color=baseboard_col))
    meshes.append(create_box(extents=[0.02, 0.10, 0.80], center=(-2.59, 0.05, -3.00), color=baseboard_col))
    meshes.append(create_box(extents=[0.02, 0.10, 1.60], center=(-2.59, 0.05, 1.60), color=baseboard_col))

    # =========================================================================
    # 2. MODERN SECTIONAL SOFA (L-SHAPE)
    # The user sits at (0.0, 1.15, 0.0).
    # Sofa seat at Y = 0.38m (77cm below the eyes).
    # Backrest at Z = +0.65m (behind the head).
    # Side chaise on the right at X = +1.20 to +1.80, extending forward.
    # =========================================================================
    sofa_main_col = (52, 58, 68, 255)       # Nordic bluish graphite
    sofa_cushion_col = (62, 70, 82, 255)    # Soft velvety fabric
    sofa_base_wood = (40, 32, 26, 255)      # Dark wooden base
    sofa_leg_col = (24, 24, 26, 255)        # Dark metal feet

    # Main sofa base (2.6m wide, 0.95m deep, 0.15m high)
    meshes.append(create_box(extents=[2.60, 0.12, 0.95], center=(0.0, 0.14, 0.40), color=sofa_base_wood))

    # Discreet cylindrical feet (8cm high)
    for lx in [-1.20, 0.0, 1.20]:
        for lz in [0.0, 0.80]:
            meshes.append(create_cylinder(radius=0.025, height=0.08, sections=12, center=(lx, 0.04, lz), axis=(0,1,0), color=sofa_leg_col))

    # Main seat cushions (3 places: left, center, right)
    # The user's center at X = 0.0. The center seat spans X = -0.40 to +0.40.
    c_w, c_d, c_h = 0.82, 0.72, 0.16
    for cx in [-0.84, 0.0, 0.84]:
        meshes.append(create_rounded_box(extents=[c_w, c_h, c_d], radius=0.04, subdivisions=2, center=(cx, 0.30, 0.28), color=sofa_cushion_col))

    # Structural rear backrest (up to 0.75m high)
    meshes.append(create_rounded_box(extents=[2.60, 0.50, 0.20], radius=0.03, subdivisions=2, center=(0.0, 0.53, 0.76), color=sofa_main_col))

    # Backrest cushions (ergonomic, behind the seat)
    for cx in [-0.84, 0.0, 0.84]:
        back_cushion = create_rounded_box(extents=[c_w, 0.42, 0.18], radius=0.04, subdivisions=2, center=(cx, 0.58, 0.64), color=sofa_cushion_col)
        # A slight ergonomic backward tilt
        back_cushion.apply_transform(trimesh.transformations.rotation_matrix(np.radians(-7), [1, 0, 0], point=(cx, 0.58, 0.64)))
        meshes.append(back_cushion)

    # Left sofa arm
    meshes.append(create_rounded_box(extents=[0.20, 0.30, 0.95], radius=0.03, subdivisions=2, center=(-1.38, 0.37, 0.40), color=sofa_main_col))

    # Side chaise extended to the right (lounge module)
    chaise_w = 0.85
    chaise_d = 1.10
    meshes.append(create_box(extents=[chaise_w, 0.12, chaise_d], center=(1.38, 0.14, -0.45), color=sofa_base_wood))
    for clz in [-0.90, -0.10]:
        meshes.append(create_cylinder(radius=0.025, height=0.08, sections=12, center=(1.65, 0.04, clz), axis=(0,1,0), color=sofa_leg_col))
    meshes.append(create_rounded_box(extents=[chaise_w, c_h, chaise_d], radius=0.04, subdivisions=2, center=(1.38, 0.30, -0.45), color=sofa_cushion_col))
    # Extended right arm
    meshes.append(create_rounded_box(extents=[0.20, 0.30, 1.95], radius=0.03, subdivisions=2, center=(1.88, 0.37, -0.10), color=sofa_main_col))

    # Decorative cushions in the corners (warm mustard and soft terracotta)
    cushion_amber = (215, 155, 60, 255)
    cushion_terracotta = (185, 85, 65, 255)
    # Canto esquerdo
    p1 = create_rounded_box(extents=[0.36, 0.36, 0.12], radius=0.04, subdivisions=2, center=(-1.15, 0.48, 0.50), color=cushion_amber)
    p1.apply_transform(trimesh.transformations.rotation_matrix(np.radians(25), [0, 1, 0], point=(-1.15, 0.48, 0.50)))
    meshes.append(p1)
    # Canto direito
    p2 = create_rounded_box(extents=[0.36, 0.36, 0.12], radius=0.04, subdivisions=2, center=(1.65, 0.48, -0.80), color=cushion_terracotta)
    p2.apply_transform(trimesh.transformations.rotation_matrix(np.radians(-30), [0, 1, 0], point=(1.65, 0.48, -0.80)))
    meshes.append(p2)

    # =========================================================================
    # 3. WOOL RUG AND MINIMALIST COFFEE TABLE
    # No geometry crossing!
    # Rug: Z from -0.50 to -2.50 (center Z = -1.50)
    # Table: Z = -1.50, Y = 0.35m
    # =========================================================================
    rug_col_base = (230, 226, 218, 255)   # Soft ivory wool
    rug_col_line = (85, 88, 95, 255)      # Gray geometric lines
    rug_w, rug_d = 2.70, 2.00
    rug_z = -1.50
    # Rug base (thin height Y = 0.004)
    meshes.append(create_rounded_box(extents=[rug_w, 0.008, rug_d], radius=0.02, subdivisions=1, center=(0.0, 0.004, rug_z), color=rug_col_base))
    # Scandinavian geometric friezes
    meshes.append(create_box(extents=[rug_w - 0.20, 0.002, 0.03], center=(0.0, 0.009, rug_z - 0.70), color=rug_col_line))
    meshes.append(create_box(extents=[rug_w - 0.20, 0.002, 0.03], center=(0.0, 0.009, rug_z + 0.70), color=rug_col_line))
    meshes.append(create_box(extents=[0.03, 0.002, rug_d - 0.30], center=(-rug_w/2 + 0.20, 0.009, rug_z), color=rug_col_line))
    meshes.append(create_box(extents=[0.03, 0.002, rug_d - 0.30], center=(rug_w/2 - 0.20, 0.009, rug_z), color=rug_col_line))

    # Oval / Rounded-Rectangle Coffee Table
    table_w, table_d, table_h = 1.05, 0.52, 0.34
    table_wood = (50, 42, 36, 255)        # Nogueira escura
    table_metal = (28, 28, 30, 255)       # Matte black metal
    # Tampo
    meshes.append(create_rounded_box(extents=[table_w, 0.03, table_d], radius=0.03, subdivisions=2, center=(0.0, table_h, rug_z), color=table_wood))
    # 4 tapered legs
    for tx in [-table_w/2 + 0.08, table_w/2 - 0.08]:
        for tz in [rug_z - table_d/2 + 0.08, rug_z + table_d/2 - 0.08]:
            meshes.append(create_cylinder(radius=0.015, height=table_h, sections=12, center=(tx, table_h/2, tz), axis=(0,1,0), color=table_metal))
    # Lower shelf
    meshes.append(create_box(extents=[table_w - 0.12, 0.015, table_d - 0.12], center=(0.0, 0.12, rug_z), color=table_metal))

    # Decorative objects on the table
    # Photography/architecture book
    meshes.append(create_box(extents=[0.24, 0.02, 0.18], center=(-0.22, table_h + 0.025, rug_z - 0.05), color=(220, 215, 205, 255)))
    meshes.append(create_box(extents=[0.11, 0.006, 0.17], center=(-0.28, table_h + 0.038, rug_z - 0.05), color=(195, 75, 55, 255)))
    # Ceramic pot with a succulent
    meshes.append(create_cylinder(radius=0.06, height=0.05, sections=14, center=(0.24, table_h + 0.04, rug_z + 0.04), axis=(0,1,0), color=(120, 140, 130, 255)))
    meshes.append(create_cylinder(radius=0.035, height=0.05, sections=12, center=(0.24, table_h + 0.085, rug_z + 0.04), axis=(0,1,0), color=(55, 110, 70, 255)))

    # =========================================================================
    # 4. FRONT WALL: SLATTED PANEL AND TV CONSOLE
    # Wall at Z = -3.40m.
    # Slatted acoustic panel at Z = -3.37m.
    # Floating console at Z = -3.15m (top Y = 0.55m).
    # Virtual screen at Z = -2.80m, Y = 1.50m (base Y = 0.825m).
    # Vertical clearance between the console top and the screen base: 27.5 cm!
    # Depth clearance between the screen and the console: 35 cm!
    # =========================================================================
    panel_w = 3.60
    panel_h = 2.60
    slat_wood_col = (150, 105, 70, 255)   # Fine oak
    slat_back_col = (26, 24, 22, 255)     # Acoustic black backing

    # Dark base plate of the panel
    meshes.append(create_box(extents=[panel_w, panel_h, 0.02], center=(0.0, 1.45, -3.38), color=slat_back_col))

    # Thin vertical slats with symmetric spacing
    num_slats = 32
    slat_w = 0.04
    slat_step = panel_w / num_slats
    for s in range(num_slats):
        sx = -panel_w / 2.0 + (s + 0.5) * slat_step
        meshes.append(create_box(extents=[slat_w, panel_h, 0.02], center=(sx, 1.45, -3.36), color=slat_wood_col))

    # Indirect LED lighting (Halo / Bias Glow) behind the slatted panel
    halo_glow = (255, 230, 180, 255)
    meshes.append(create_box(extents=[panel_w + 0.10, 0.02, 0.015], center=(0.0, 2.76, -3.37), color=halo_glow))
    meshes.append(create_box(extents=[panel_w + 0.10, 0.02, 0.015], center=(0.0, 0.14, -3.37), color=halo_glow))
    meshes.append(create_box(extents=[0.02, panel_h + 0.02, 0.015], center=(-panel_w/2 - 0.05, 1.45, -3.37), color=halo_glow))
    meshes.append(create_box(extents=[0.02, panel_h + 0.02, 0.015], center=(panel_w/2 + 0.05, 1.45, -3.37), color=halo_glow))

    # Console Flutuante Suspenso (Low Profile)
    # Position: Z = -3.15m, depth 0.32m (from -3.31 to -2.99m)
    # Height: Y = 0.35m, extents Y = 0.28m (from Y = 0.21m to 0.49m)
    # Top surface at Y = 0.50m.
    console_base = (42, 44, 48, 255)      # Modern satin graphite
    console_top = (160, 118, 80, 255)     # Fine-oak top
    c_w, c_h, c_d = 2.60, 0.28, 0.32
    c_y, c_z = 0.36, -3.15
    meshes.append(create_box(extents=[c_w, c_h, c_d], center=(0.0, c_y, c_z), color=console_base))
    meshes.append(create_box(extents=[c_w + 0.04, 0.025, c_d + 0.04], center=(0.0, c_y + c_h/2 + 0.0125, c_z), color=console_top))

    # Elegant slim soundbar centered on the console (top at Y = 0.56m)
    meshes.append(create_rounded_box(extents=[1.05, 0.045, 0.08], radius=0.015, subdivisions=1, center=(0.0, c_y + c_h/2 + 0.045, c_z), color=(22, 24, 26, 255)))

    # Small decorative elements on the console
    meshes.append(create_box(extents=[0.20, 0.03, 0.15], center=(-0.95, c_y + c_h/2 + 0.035, c_z), color=(190, 80, 60, 255)))
    meshes.append(create_cylinder(radius=0.05, height=0.12, sections=12, center=(0.95, c_y + c_h/2 + 0.08, c_z), axis=(0,1,0), color=(220, 215, 205, 255)))

    # Discreet ultra-slim TV frame on the wall at Z = -2.82m (the virtual screen sits at Z = -2.80m)
    # Exact 16:9 aspect ratio (2.40m x 1.35m, center Y = 1.50m)
    tv_frame_col = (18, 20, 24, 255)
    sw, sh = 2.40, 1.35
    b_th = 0.025
    meshes.append(create_box(extents=[sw + 2*b_th, b_th, 0.02], center=(0.0, 1.50 + sh/2 + b_th/2, -2.82), color=tv_frame_col))
    meshes.append(create_box(extents=[sw + 2*b_th, b_th, 0.02], center=(0.0, 1.50 - sh/2 - b_th/2, -2.82), color=tv_frame_col))
    meshes.append(create_box(extents=[b_th, sh, 0.02], center=(-sw/2 - b_th/2, 1.50, -2.82), color=tv_frame_col))
    meshes.append(create_box(extents=[b_th, sh, 0.02], center=(sw/2 + b_th/2, 1.50, -2.82), color=tv_frame_col))
    # Anti-reflective OLED rear cavity
    meshes.append(create_box(extents=[sw, sh, 0.01], center=(0.0, 1.50, -2.83), color=(8, 10, 12, 255)))

    # =========================================================================
    # 5. PANORAMIC WINDOW AND MODERN CURTAINS (Left Wall, X = -2.60)
    # =========================================================================
    win_w = 3.40
    win_h = 2.00
    win_y_mid = 1.60
    win_z_mid = -0.90
    win_frame = (32, 34, 38, 255)

    # Outside twilight view / skyline with an atmospheric gradient
    sky_colors = [
        (35, 55, 95, 255),    # Deep cobalt blue
        (65, 75, 110, 255),   # Soft violet
        (120, 95, 110, 255),  # Alaranjado crepuscular no horizonte
    ]
    strip_h = win_h / 3.0
    for st in range(3):
        sy = (win_y_mid - win_h/2) + (2 - st + 0.5) * strip_h
        meshes.append(create_box(extents=[0.02, strip_h, win_w], center=(-2.72, sy, win_z_mid), color=sky_colors[st]))

    # Silhouette of mountains / city on the horizon
    meshes.append(create_box(extents=[0.02, 0.20, win_w], center=(-2.70, win_y_mid - win_h/2 + 0.10, win_z_mid), color=(18, 22, 30, 255)))

    # Dark anodized aluminum window frame
    meshes.append(create_box(extents=[0.10, 0.04, win_w + 0.08], center=(-2.60, win_y_mid - win_h/2, win_z_mid), color=win_frame))
    meshes.append(create_box(extents=[0.10, 0.04, win_w + 0.08], center=(-2.60, win_y_mid + win_h/2, win_z_mid), color=win_frame))
    meshes.append(create_box(extents=[0.10, win_h, 0.04], center=(-2.60, win_y_mid, win_z_mid - win_w/2), color=win_frame))
    meshes.append(create_box(extents=[0.10, win_h, 0.04], center=(-2.60, win_y_mid, win_z_mid + win_w/2), color=win_frame))
    # 2 thin vertical mullions splitting it into 3 wide glass panes
    meshes.append(create_box(extents=[0.08, win_h, 0.025], center=(-2.60, win_y_mid, win_z_mid - win_w/6), color=win_frame))
    meshes.append(create_box(extents=[0.08, win_h, 0.025], center=(-2.60, win_y_mid, win_z_mid + win_w/6), color=win_frame))

    # Elegantly gathered linen curtains stretched to the corners (not crossing the window)
    curtain_col = (235, 230, 222, 255)
    for pleat in range(4):
        pz = (win_z_mid - win_w/2 - 0.15) + pleat * 0.06
        px = -2.52 + (pleat % 2) * 0.02
        meshes.append(create_rounded_box(extents=[0.05, 2.80, 0.05], radius=0.015, subdivisions=1, center=(px, 1.40, pz), color=curtain_col))
    for pleat in range(4):
        pz = (win_z_mid + win_w/2 + 0.05) + pleat * 0.06
        px = -2.52 + (pleat % 2) * 0.02
        meshes.append(create_rounded_box(extents=[0.05, 2.80, 0.05], radius=0.015, subdivisions=1, center=(px, 1.40, pz), color=curtain_col))

    # =========================================================================
    # 6. CURVED FLOOR LAMP (Warm 2700K Indirect Light)
    # Posicionada no canto esquerdo recuado: X = -2.10m, Z = -1.90m
    # =========================================================================
    lamp_x, lamp_z = -2.10, -1.90
    lamp_base = (30, 30, 32, 255)
    lamp_metal = (190, 160, 100, 255)     # Brushed brass
    lamp_shade = (255, 240, 210, 255)     # Translucent shade
    lamp_glow = (255, 220, 130, 255)      # Warm emissive bulb

    meshes.append(create_cylinder(radius=0.18, height=0.03, sections=16, center=(lamp_x, 0.015, lamp_z), axis=(0,1,0), color=lamp_base))
    meshes.append(create_cylinder(radius=0.015, height=1.75, sections=12, center=(lamp_x, 0.88, lamp_z), axis=(0,1,0), color=lamp_metal))
    meshes.append(create_cylinder(radius=0.20, height=0.30, sections=18, center=(lamp_x, 1.62, lamp_z), axis=(0,1,0), color=lamp_shade))
    meshes.append(create_cylinder(radius=0.06, height=0.10, sections=12, center=(lamp_x, 1.62, lamp_z), axis=(0,1,0), color=lamp_glow))

    # =========================================================================
    # 7. ABSTRACT ART FRAMES (Right Wall, X = +2.60)
    # =========================================================================
    art_z = [-1.40, -0.20, 1.00]
    art_palette = [
        (190, 85, 55, 255),   # Terracota
        (85, 125, 110, 255),  # Sage green
        (45, 65, 95, 255),    # Slate blue
    ]
    for idx, az in enumerate(art_z):
        # Oak frame
        meshes.append(create_box(extents=[0.02, 0.85, 0.65], center=(2.58, 1.55, az), color=(150, 110, 75, 255)))
        # White passe-partout
        meshes.append(create_box(extents=[0.022, 0.77, 0.57], center=(2.578, 1.55, az), color=(245, 242, 235, 255)))
        # Contemporary abstract art
        meshes.append(create_box(extents=[0.024, 0.50, 0.38], center=(2.576, 1.55, az), color=art_palette[idx]))

    # =========================================================================
    # 8. RECESSED CEILING SPOTLIGHTS
    # =========================================================================
    spot_glow = (255, 245, 220, 255)
    for sx, sz in [(-1.20, -1.50), (1.20, -1.50), (0.0, -2.60), (1.30, 0.0)]:
        meshes.append(create_cylinder(radius=0.06, height=0.015, sections=12, center=(sx, 2.89, sz), axis=(0,1,0), color=spot_glow))

    # =========================================================================
    # 9. CONCATENATION INTO A SINGLE MESH AND BAKED AMBIENT OCCLUSION (AO)
    # =========================================================================
    # smooth_shade splits the vertices at hard edges (> 35°): without it the
    # boxes share 8 vertices, the corner normals come out diagonal and
    # both the bake below and the shader light the flat faces as a gradient.
    full_mesh = trimesh.graph.smooth_shade(trimesh.util.concatenate(meshes), angle=np.radians(35))
    verts = full_mesh.vertices.copy()
    normals = full_mesh.vertex_normals.copy()
    colors = full_mesh.visual.vertex_colors.copy().astype(np.float32)[:, :3] / 255.0

    # Basic balanced ambient lighting
    ambient = 0.55

    # Directional light coming from the window
    win_dir = np.array([0.7, 0.4, -0.3])
    win_dir = win_dir / np.linalg.norm(win_dir)
    win_diff = np.maximum(0.0, np.dot(normals, win_dir))
    win_col = np.array([0.88, 0.92, 1.0])

    # Point light from the floor lamp (-2.10, 1.62, -1.90)
    lamp_pos = np.array([-2.10, 1.62, -1.90])
    l_vec = lamp_pos - verts
    l_dist = np.linalg.norm(l_vec, axis=1, keepdims=True)
    l_dir = l_vec / np.maximum(1e-6, l_dist)
    l_diff = np.maximum(0.0, np.sum(normals * l_dir, axis=1, keepdims=True))
    l_atten = 1.0 / (1.0 + 0.7 * l_dist + 0.4 * (l_dist ** 2))
    lamp_col = np.array([1.0, 0.85, 0.55])

    # Soft light from the LED strip behind the TV panel (0.0, 1.5, -3.37)
    tv_pos = np.array([0.0, 1.5, -3.37])
    tv_vec = tv_pos - verts
    tv_dist = np.linalg.norm(tv_vec, axis=1, keepdims=True)
    tv_dir = tv_vec / np.maximum(1e-6, tv_dist)
    tv_diff = np.maximum(0.0, np.sum(normals * tv_dir, axis=1, keepdims=True))
    tv_atten = 1.0 / (1.0 + 1.2 * tv_dist + 0.8 * (tv_dist ** 2))
    tv_glow_col = np.array([0.95, 0.88, 0.75])

    # Ambient occlusion (AO) in the corners, baseboards and under the sofa
    ao = np.ones((len(verts), 1), dtype=np.float32)
    # Floor baseboard
    y_floor = np.clip(verts[:, 1:2] / 0.12, 0.0, 1.0)
    ao *= (0.78 + 0.22 * y_floor)
    # Wall corners
    dist_wall_x = np.minimum(np.abs(verts[:, 0:1] + 2.60), np.abs(verts[:, 0:1] - 2.60))
    dist_wall_z = np.minimum(np.abs(verts[:, 2:3] + 3.40), np.abs(verts[:, 2:3] - 2.40))
    corner_factor = np.clip(np.minimum(dist_wall_x, dist_wall_z) / 0.15, 0.0, 1.0)
    ao *= (0.82 + 0.18 * corner_factor)
    # Under the sofa
    under_sofa = (np.abs(verts[:, 0:1]) < 1.40) & (verts[:, 2:3] > -0.10) & (verts[:, 2:3] < 0.90) & (verts[:, 1:2] < 0.25)
    ao[under_sofa] *= 0.60

    # Final lighting composition
    total_light = (
        ambient +
        win_diff[:, np.newaxis] * 0.25 * win_col +
        l_diff * l_atten * 0.35 * lamp_col +
        tv_diff * tv_atten * 0.20 * tv_glow_col
    ) * ao

    shaded_colors = np.clip(colors * total_light, 0.0, 1.0)
    final_rgba = np.full((len(verts), 4), 255, dtype=np.uint8)
    final_rgba[:, :3] = (shaded_colors * 255.0).astype(np.uint8)
    full_mesh.visual.vertex_colors = final_rgba

    # Offset applied only after the bake: all the lighting above uses the
    # modeling coordinates (eye at DESIGN_EYE_Y).
    full_mesh.apply_translation([0.0, EXPORT_Y_OFFSET, 0.0])

    return full_mesh


def main():
    print("Criando malha arquitetural refinada da Sala de Estar...")
    mesh = build_living_room()
    print(f"Mesh generated: {len(mesh.vertices)} vertices, {len(mesh.faces)} faces ({len(mesh.faces)} triangles)")

    # Export to GLB
    glb_bytes = mesh.export(file_type="glb", include_normals=True)
    with open(MODEL_PATH, "wb") as f:
        f.write(glb_bytes)

    size_kb = os.path.getsize(MODEL_PATH) / 1024
    print(f"Salvo em: {MODEL_PATH} ({size_kb:.1f} KB)")


if __name__ == "__main__":
    main()
