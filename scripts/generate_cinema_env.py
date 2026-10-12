#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate_cinema_env.py — Generator of the high-fidelity "IMAX Cinema" 3D environment for tucaVR.

Inspirado no "Grand Cinema" do Bigscreen VR e "IMAX Theater" do Skybox VR:
- An amphitheater auditorium with 4 rows staggered in gentle steps and curved in an arc facing the IMAX screen.
- The user sits in the central seat of the VIP row (Row 2, X = 0) at the ideal eye height (Y = 1.20m),
  with an immersive, unobstructed panoramic view of the giant screen (6.0m x 3.375m at Z = -7.5m).
  The coordinates above are modeling coordinates; the GLB is exported shifted +0.30m in Y so the eye
  lands at the runtime's 1.50m (see EXPORT_Y_OFFSET) — in config.ini the screen sits at Y = 2.50m.
- Ergonomic cinema seats:
  * Thick cushions with rounded edges in deep ruby-red velvet (#7a121c).
  * A reclined backrest with a contoured headrest.
  * Padded armrests in graphite leather (#25272c) with dark metallic cup holders.
  * Steel support pedestals.
- Steps with warm amber marker lighting (#ffb336) casting light on the carpets.
- Side walls with angled Art Deco acoustic panels and sconces with golden indirect lighting.
- A ceiling with acoustic baffles and a projection booth with lit glass at the back.
- An elegant proscenium with a low stage and a chamfered titanium frame around the screen.
- Soft per-vertex baked Ambient Occlusion (AO).
- 1 draw call, ~30,000 triangles, vertex colors with no VRAM textures, ~1.3 MB (vertices split at the hard edges).
"""

import os
import sys
import numpy as np
import trimesh

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
OUTPUT_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "environments", "cinema")
MODEL_PATH = os.path.join(OUTPUT_DIR, "model.glb")

os.makedirs(OUTPUT_DIR, exist_ok=True)

# The runtime anchors the user's eye 1.50m above the model's origin when
# recentralizar (vr_player_app_vulkan.cpp: sceneTranslationOffset.y = headCenter.y - 1.5f).
# The scene is modeled with the eye at DESIGN_EYE_Y and exported shifted in Y so
# that point lands exactly at RUNTIME_EYE_Y. The config.ini screen_pos
# (and the preset in VRActivity.setVirtualEnvironment) is already in exported coordinates.
DESIGN_EYE_Y = 1.20
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
    """Creates a box with rounded corners and edges for cinema upholstery."""
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


def create_cinema_seat(center, angle_deg=0.0):
    """
    Generates a modern ergonomic VIP cinema seat:
    - Assento arredondado em veludo vermelho rubi (#7a121c).
    - A reclined backrest with a contoured headrest.
    - Padded armrests in graphite leather (#25272c) with cup holders (#141518).
    - Dark steel pedestals.
    """
    seat_meshes = []
    cx, cy, cz = center

    col_velvet = (122, 18, 28, 255)       # Deep ruby-red velvet
    col_velvet_head = (145, 24, 36, 255)  # Headrest
    col_leather = (36, 38, 44, 255)       # Graphite leather on the armrests
    col_steel = (20, 22, 26, 255)         # Matte steel frame
    col_cupholder = (14, 15, 18, 255)     # Inner cup holder

    seat_w, seat_d, seat_h = 0.54, 0.50, 0.12

    # 1. Lower metal pedestal attached to the floor
    seat_meshes.append(create_cylinder(radius=0.04, height=0.28, sections=10, center=(0.0, 0.14, 0.0), axis=(0,1,0), color=col_steel))
    seat_meshes.append(create_box(extents=[0.24, 0.03, 0.30], center=(0.0, 0.015, 0.0), color=col_steel))

    # 2. Structural shell of the seat
    seat_meshes.append(create_box(extents=[seat_w, 0.06, seat_d], center=(0.0, 0.28, 0.0), color=col_steel))

    # 3. Seat cushion (rounded, comfortable)
    seat_meshes.append(create_rounded_box(extents=[seat_w - 0.04, seat_h, seat_d - 0.04], radius=0.03, subdivisions=2, center=(0.0, 0.36, 0.0), color=col_velvet))

    # 4. Reclined backrest with an ergonomic headrest
    back_h = 0.52
    back_w = seat_w - 0.04
    back_mesh = create_rounded_box(extents=[back_w, back_h, 0.10], radius=0.03, subdivisions=2, center=(0.0, 0.64, 0.22), color=col_velvet)
    back_mesh.apply_transform(trimesh.transformations.rotation_matrix(np.radians(-8), [1, 0, 0], point=(0.0, 0.40, 0.22)))
    seat_meshes.append(back_mesh)

    # 5. Contoured headrest
    head_mesh = create_rounded_box(extents=[back_w - 0.06, 0.18, 0.12], radius=0.035, subdivisions=2, center=(0.0, 0.92, 0.25), color=col_velvet_head)
    head_mesh.apply_transform(trimesh.transformations.rotation_matrix(np.radians(-8), [1, 0, 0], point=(0.0, 0.40, 0.22)))
    seat_meshes.append(head_mesh)

    # 6. Side armrests with cup holders
    arm_w, arm_h, arm_d = 0.10, 0.24, 0.44
    for side in [-1, 1]:
        ax = side * (seat_w/2 + arm_w/2 - 0.02)
        # Apoio acolchoado
        seat_meshes.append(create_rounded_box(extents=[arm_w, arm_h, arm_d], radius=0.025, subdivisions=1, center=(ax, 0.46, 0.02), color=col_leather))
        # Cup holders at the front end
        seat_meshes.append(create_cylinder(radius=0.038, height=0.04, sections=12, center=(ax, 0.59, -0.14), axis=(0,1,0), color=col_cupholder))

    # Concatenate the seat and apply the arc position and rotation
    seat_full = trimesh.util.concatenate(seat_meshes)
    if abs(angle_deg) > 0.01:
        seat_full.apply_transform(trimesh.transformations.rotation_matrix(np.radians(angle_deg), [0, 1, 0]))
    seat_full.apply_translation([cx, cy, cz])
    return seat_full


def build_cinema():
    meshes = []

    # =========================================================================
    # 1. AUDITORIUM STRUCTURE (Walls, Ceiling and Proscenium)
    # Room dimensions: Width 11.0m (X: [-5.5, +5.5]), Height 5.5m (Y: [-1.0, 4.5]), Depth 14.0m (Z: [-8.5, +5.5])
    # =========================================================================
    wall_base_col = (28, 30, 36, 255)      # Acoustic lead-gray
    ceiling_col = (18, 20, 24, 255)        # Dark absorbent ceiling
    stage_col = (22, 24, 28, 255)          # Dark oak stage

    # Front wall / proscenium behind the screen (Z = -8.20m)
    meshes.append(create_subdivided_box(extents=[11.0, 5.50, 0.15], subdivisions=2, center=(0.0, 1.75, -8.25), color=(14, 15, 18, 255)))
    # Back wall (behind the last row, Z = +5.20m)
    meshes.append(create_subdivided_box(extents=[11.0, 5.50, 0.15], subdivisions=2, center=(0.0, 1.75, 5.25), color=wall_base_col))
    # Left wall (X = -5.50m)
    meshes.append(create_subdivided_box(extents=[0.15, 5.50, 13.50], subdivisions=2, center=(-5.55, 1.75, -1.50), color=wall_base_col))
    # Right wall (X = +5.50m)
    meshes.append(create_subdivided_box(extents=[0.15, 5.50, 13.50], subdivisions=2, center=(5.55, 1.75, -1.50), color=wall_base_col))
    # Ceiling
    meshes.append(create_subdivided_box(extents=[11.0, 0.10, 13.50], subdivisions=2, center=(0.0, 4.55, -1.50), color=ceiling_col))

    # =========================================================================
    # 2. STAGE AND IMAX SCREEN FRAME
    # Virtual screen: screen_pos = 0.0, 2.20, -7.50 | screen_scale = 6.00, 3.375
    # Screen base at Y = 2.20 - 3.375/2 = 0.51m. Top at Y = 3.89m.
    # =========================================================================
    # Gently raised stage below the screen (height Y = 0.35m, Z from -6.8m to -8.2m)
    meshes.append(create_rounded_box(extents=[9.60, 0.40, 1.40], radius=0.05, subdivisions=2, center=(0.0, 0.15, -7.50), color=stage_col))

    # Front skirt of the stage in pleated dark velvet
    for p in range(24):
        px = -4.60 + p * 0.40
        meshes.append(create_cylinder(radius=0.035, height=0.35, sections=10, center=(px, 0.15, -6.78), axis=(0,1,0), color=(16, 17, 20, 255)))

    # Chamfered titanium frame around the giant IMAX screen
    sw, sh = 6.00, 3.375
    frame_th = 0.06
    frame_d = 0.05
    frame_col = (25, 27, 32, 255)
    # Top and bottom frame
    meshes.append(create_box(extents=[sw + 2*frame_th, frame_th, frame_d], center=(0.0, 2.20 + sh/2 + frame_th/2, -7.55), color=frame_col))
    meshes.append(create_box(extents=[sw + 2*frame_th, frame_th, frame_d], center=(0.0, 2.20 - sh/2 - frame_th/2, -7.55), color=frame_col))
    # Left and right frame
    meshes.append(create_box(extents=[frame_th, sh, frame_d], center=(-sw/2 - frame_th/2, 2.20, -7.55), color=frame_col))
    meshes.append(create_box(extents=[frame_th, sh, frame_d], center=(sw/2 + frame_th/2, 2.20, -7.55), color=frame_col))
    # Anti-reflective black cavity behind the screen
    meshes.append(create_box(extents=[sw + 0.20, sh + 0.20, 0.02], center=(0.0, 2.20, -7.58), color=(8, 9, 11, 255)))

    # =========================================================================
    # 3. AMPHITHEATER STAGGERED IN STEPS AND CARPET
    # 4 tiers with a gradual rise for a perfect view:
    # Tier 0: Z = -4.50m to -3.10m (Y = -0.30m)
    # Tier 1: Z = -3.10m to -1.50m (Y = -0.15m)
    # Tier 2 (the user's VIP row): Z = -1.50m to +0.80m (Y = 0.00m) -> center at Z = -0.20m
    # Tier 3: Z = +0.80m to +3.20m (Y = +0.25m)
    # Rear aisle: Z = +3.20m to +5.20m (Y = +0.50m)
    # =========================================================================
    carpet_col = (32, 34, 42, 255)        # Graphite-gray acoustic carpet
    step_edge_col = (20, 21, 25, 255)     # Black step corner trim
    light_amber = (255, 185, 60, 255)     # Warm amber LED embedded in the step

    # Tier platforms
    tiers = [
        {"y": -0.30, "z_min": -4.80, "z_max": -3.20},
        {"y": -0.15, "z_min": -3.20, "z_max": -1.60},
        {"y":  0.00, "z_min": -1.60, "z_max":  0.80},
        {"y":  0.25, "z_min":  0.80, "z_max":  3.20},
        {"y":  0.50, "z_min":  3.20, "z_max":  5.20},
    ]

    for t_idx, t in enumerate(tiers):
        t_w = 9.80
        t_d = t["z_max"] - t["z_min"]
        t_z = (t["z_min"] + t["z_max"]) / 2.0
        t_y = t["y"]
        # Tier floor block
        meshes.append(create_box(extents=[t_w, 0.40, t_d], center=(0.0, t_y - 0.20, t_z), color=carpet_col))

        # Step riser and nosing with amber LED lighting
        if t_idx > 0:
            step_z = t["z_min"]
            step_h = t["y"] - tiers[t_idx - 1]["y"]
            step_y = t["y"] - step_h / 2.0
            # Vertical step riser
            meshes.append(create_box(extents=[t_w, step_h, 0.04], center=(0.0, step_y, step_z), color=step_edge_col))
            # Continuous amber LED strip under the step nosing (lights the lower step)
            meshes.append(create_box(extents=[t_w - 0.40, 0.025, 0.03], center=(0.0, t["y"] - 0.02, step_z - 0.02), color=light_amber))

    # =========================================================================
    # 4. ERGONOMIC SEATS IN AN ARC (STADIUM SEATING)
    # Row 0: 6 seats at Z = -3.90m, Y = -0.30m
    # Row 1: 8 seats at Z = -2.40m, Y = -0.15m
    # Row 2 (the user's VIP row): 9 seats at Z = 0.00m, Y = 0.00m (the user sits in the center, X = 0.0)
    #   An odd count so a seat exists at X = 0; with Z = 0 the eye (0, DESIGN_EYE_Y, 0)
    #   sits 78cm above the seat and ~11cm in front of the headrest.
    # Row 3: 8 seats at Z = +1.80m, Y = +0.25m
    # =========================================================================
    # Center of curvature toward the IMAX screen (Z = -7.50m)
    curve_center_z = -7.50

    seat_rows = [
        {"y": -0.30, "z": -3.90, "count": 6, "spacing": 0.76},
        {"y": -0.15, "z": -2.40, "count": 8, "spacing": 0.78},
        {"y":  0.00, "z":  0.00, "count": 9, "spacing": 0.80}, # VIP row
        {"y":  0.25, "z":  1.80, "count": 8, "spacing": 0.82},
    ]

    for r_idx, row in enumerate(seat_rows):
        r_y = row["y"]
        r_z = row["z"]
        n_seats = row["count"]
        spacing = row["spacing"]
        total_w = (n_seats - 1) * spacing

        for s in range(n_seats):
            sx = -total_w / 2.0 + s * spacing
            # Compute the radial orientation angle facing the center of the screen
            dx = sx
            dz = r_z - curve_center_z
            angle_deg = -np.degrees(np.arctan2(dx, dz)) * 0.75 # Curvatura elegante

            meshes.append(create_cinema_seat(center=(sx, r_y, r_z), angle_deg=angle_deg))

    # =========================================================================
    # 5. ART DECO ACOUSTIC PANELS ON THE SIDE WALLS (X = ±5.50m)
    # Panels interleaved with wooden friezes and sconces with golden lighting
    # =========================================================================
    panel_col = (38, 42, 50, 255)         # Graphite acoustic fabric
    panel_wood = (145, 100, 65, 255)      # Fine-wood friezes
    sconce_glow = (255, 210, 110, 255)    # Golden light of the sconces

    panel_z_centers = [-5.50, -3.50, -1.50, 0.50, 2.50, 4.50]
    for side in [-1, 1]:
        px = side * 5.42
        for pz in panel_z_centers:
            # Chamfered acoustic plate
            meshes.append(create_rounded_box(extents=[0.06, 3.20, 1.60], radius=0.04, subdivisions=1, center=(px, 2.10, pz), color=panel_col))
            # Decorative vertical wooden friezes
            meshes.append(create_box(extents=[0.08, 3.40, 0.05], center=(px, 2.10, pz - 0.78), color=panel_wood))
            meshes.append(create_box(extents=[0.08, 3.40, 0.05], center=(px, 2.10, pz + 0.78), color=panel_wood))

            # Elegant indirect-lighting sconce at the center of the panel
            sconce_y = 2.40
            meshes.append(create_box(extents=[0.07, 0.28, 0.12], center=(px, sconce_y, pz), color=(25, 26, 30, 255)))
            # Upper and lower emissive beam
            meshes.append(create_cylinder(radius=0.04, height=0.06, sections=10, center=(px, sconce_y + 0.15, pz), axis=(0,1,0), color=sconce_glow))
            meshes.append(create_cylinder(radius=0.04, height=0.06, sections=10, center=(px, sconce_y - 0.15, pz), axis=(0,1,0), color=sconce_glow))

    # =========================================================================
    # 6. ACOUSTIC BAFFLES ON THE CEILING AND PROJECTION BOOTH
    # =========================================================================
    # Acoustic baffles hanging from the ceiling
    for bz in [-5.0, -3.0, -1.0, 1.0, 3.0]:
        meshes.append(create_rounded_box(extents=[8.80, 0.25, 0.12], radius=0.03, subdivisions=1, center=(0.0, 4.35, bz), color=(24, 26, 32, 255)))

    # Projection booth at the back (back wall Z = +5.20m, Y = 2.80m)
    booth_glass = (45, 75, 110, 255)      # Translucent bluish glass with interior light
    meshes.append(create_box(extents=[1.80, 0.60, 0.08], center=(0.0, 3.20, 5.15), color=(22, 24, 28, 255)))
    meshes.append(create_box(extents=[1.50, 0.35, 0.04], center=(0.0, 3.20, 5.18), color=booth_glass))

    # =========================================================================
    # 7. CONCATENATION INTO A SINGLE MESH AND BAKED AMBIENT OCCLUSION (AO)
    # =========================================================================
    # smooth_shade splits the vertices at hard edges (> 35°): without it the
    # boxes share 8 vertices, the corner normals come out diagonal and
    # both the bake below and the shader light the flat faces as a gradient.
    full_mesh = trimesh.graph.smooth_shade(trimesh.util.concatenate(meshes), angle=np.radians(35))
    verts = full_mesh.vertices.copy()
    normals = full_mesh.vertex_normals.copy()
    colors = full_mesh.visual.vertex_colors.copy().astype(np.float32)[:, :3] / 255.0

    # Cinema ambient lighting (a soft, intimate atmosphere)
    ambient = 0.42

    # Simulated screen light (screen bounce) coming from the IMAX screen position (0.0, 2.20, -7.50)
    screen_pt = np.array([0.0, 2.20, -7.50])
    s_vec = screen_pt - verts
    s_dist = np.linalg.norm(s_vec, axis=1, keepdims=True)
    s_dir = s_vec / np.maximum(1e-6, s_dist)
    s_diff = np.maximum(0.0, np.sum(normals * s_dir, axis=1, keepdims=True))
    s_atten = 1.0 / (1.0 + 0.15 * s_dist + 0.04 * (s_dist ** 2))
    s_col = np.array([0.70, 0.82, 1.0]) # Soft cinematic bluish light

    # Amber baseboard lights (step lights) on the steps
    step_light_contrib = np.zeros((len(verts), 1), dtype=np.float32)
    for t_step_z in [-3.20, -1.60, 0.80, 3.20]:
        dz = np.abs(verts[:, 2:3] - t_step_z)
        dy = np.abs(verts[:, 1:2])
        mask = (dz < 0.80) & (dy < 0.60)
        step_light_contrib += np.where(mask, (1.0 - dz / 0.80) * (1.0 - dy / 0.60) * 0.40, 0.0)
    step_col = np.array([1.0, 0.72, 0.25])

    # Side wall sconces
    wall_light_contrib = np.zeros((len(verts), 1), dtype=np.float32)
    dist_side = np.minimum(np.abs(verts[:, 0:1] + 5.40), np.abs(verts[:, 0:1] - 5.40))
    wall_mask = dist_side < 1.20
    wall_light_contrib += np.where(wall_mask, (1.0 - dist_side / 1.20) * 0.25, 0.0)
    sconce_light_col = np.array([1.0, 0.85, 0.50])

    # Ambient Occlusion (AO): corners, baseboards and under the seats
    ao = np.ones((len(verts), 1), dtype=np.float32)
    # Proximity to the floor
    y_rel = np.clip((verts[:, 1:2] + 0.50) / 0.20, 0.0, 1.0)
    ao *= (0.75 + 0.25 * y_rel)
    # Side corners
    d_lateral = np.minimum(np.abs(verts[:, 0:1] + 5.50), np.abs(verts[:, 0:1] - 5.50))
    c_factor = np.clip(d_lateral / 0.30, 0.0, 1.0)
    ao *= (0.80 + 0.20 * c_factor)

    total_light = (
        ambient +
        s_diff * s_atten * 0.30 * s_col +
        step_light_contrib * step_col +
        wall_light_contrib * sconce_light_col
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
    print("Criando malha de alta fidelidade do Cinema IMAX...")
    mesh = build_cinema()
    print(f"Mesh generated: {len(mesh.vertices)} vertices, {len(mesh.faces)} faces ({len(mesh.faces)} triangles)")

    glb_bytes = mesh.export(file_type="glb", include_normals=True)
    with open(MODEL_PATH, "wb") as f:
        f.write(glb_bytes)

    size_kb = os.path.getsize(MODEL_PATH) / 1024
    print(f"Salvo em: {MODEL_PATH} ({size_kb:.1f} KB)")


if __name__ == "__main__":
    main()
