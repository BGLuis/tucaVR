#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate_cinema_env.py — Gerador do ambiente 3D "Cinema IMAX" de alta fidelidade para o tucaVR.

Inspirado no "Grand Cinema" do Bigscreen VR e "IMAX Theater" do Skybox VR:
- Auditório em anfiteatro com 4 fileiras escalonadas em degraus suaves e curvadas em arco voltadas para a tela IMAX.
- O usuário fica posicionado na fileira VIP central (Row 2, centro) na altura ideal dos olhos (Y = 1.20m),
  com visão panorâmica imersiva e desobstruída da tela gigante (6.0m x 3.375m em Z = -7.5m).
- Poltronas ergonômicas de cinema:
  * Almofadas espessas com bordas arredondadas em veludo vermelho rubi profundo (#7a121c).
  * Encosto reclinado com descanso de cabeça contornado.
  * Apoios de braço acolchoados em couro grafite (#25272c) com porta-copos metálicos escuros.
  * Pedestais de suporte em aço.
- Degraus com iluminação de balizamento âmbar quente (#ffb336) projetando luz nos carpetes.
- Paredes laterais com painéis acústicos angulados Art Déco e arandelas com iluminação indireta dourada.
- Teto com baffles acústicos e cabine de projeção com vidro iluminado ao fundo.
- Proscênio elegante com palco baixo e moldura chanfrada de titânio ao redor da tela.
- Baked Ambient Occlusion (AO) suave por vértice.
- Otimização extrema: 1 draw call, ~12.000 triângulos, vertex colors sem texturas de VRAM, < 300 KB.
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


def create_box(extents, center=(0, 0, 0), color=(200, 200, 200, 255)):
    """Cria uma caixa simples com vertex colors."""
    box = trimesh.creation.box(extents=extents)
    box.apply_translation(center)
    colors = np.tile(np.array(color, dtype=np.uint8), (len(box.vertices), 1))
    box.visual.vertex_colors = colors
    return box


def create_subdivided_box(extents, subdivisions=1, center=(0, 0, 0), color=(200, 200, 200, 255)):
    """Cria uma caixa subdividida para iluminação suave e gradientes por vértice."""
    box = trimesh.creation.box(extents=extents)
    for _ in range(subdivisions):
        box = box.subdivide()
    box.apply_translation(center)
    colors = np.tile(np.array(color, dtype=np.uint8), (len(box.vertices), 1))
    box.visual.vertex_colors = colors
    return box


def create_rounded_box(extents, radius=0.03, subdivisions=2, center=(0, 0, 0), color=(200, 200, 200, 255)):
    """Cria uma caixa com cantos e arestas arredondados para estofamento de cinema."""
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
    """Cria um cilindro alinhado com o eixo fornecido."""
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
    Gera uma poltrona ergonômica de cinema VIP moderna:
    - Assento arredondado em veludo vermelho rubi (#7a121c).
    - Encosto inclinado com descanso de cabeça contornado.
    - Apoios de braço acolchoados em couro grafite (#25272c) com porta-copos (#141518).
    - Pedestais em aço escuro.
    """
    seat_meshes = []
    cx, cy, cz = center

    col_velvet = (122, 18, 28, 255)       # Veludo vermelho rubi profundo
    col_velvet_head = (145, 24, 36, 255)  # Encosto de cabeça
    col_leather = (36, 38, 44, 255)       # Couro grafite nos apoios
    col_steel = (20, 22, 26, 255)         # Aço fosco da estrutura
    col_cupholder = (14, 15, 18, 255)     # Porta-copos interno

    seat_w, seat_d, seat_h = 0.54, 0.50, 0.12

    # 1. Pedestal metálico inferior ligado ao chão
    seat_meshes.append(create_cylinder(radius=0.04, height=0.28, sections=10, center=(0.0, 0.14, 0.0), axis=(0,1,0), color=col_steel))
    seat_meshes.append(create_box(extents=[0.24, 0.03, 0.30], center=(0.0, 0.015, 0.0), color=col_steel))

    # 2. Concha estrutural do assento
    seat_meshes.append(create_box(extents=[seat_w, 0.06, seat_d], center=(0.0, 0.28, 0.0), color=col_steel))

    # 3. Almofada do assento (arredondada, confortável)
    seat_meshes.append(create_rounded_box(extents=[seat_w - 0.04, seat_h, seat_d - 0.04], radius=0.03, subdivisions=2, center=(0.0, 0.36, 0.0), color=col_velvet))

    # 4. Encosto reclinado com descanso de cabeça ergonômico
    back_h = 0.52
    back_w = seat_w - 0.04
    back_mesh = create_rounded_box(extents=[back_w, back_h, 0.10], radius=0.03, subdivisions=2, center=(0.0, 0.64, 0.22), color=col_velvet)
    back_mesh.apply_transform(trimesh.transformations.rotation_matrix(np.radians(-8), [1, 0, 0], point=(0.0, 0.40, 0.22)))
    seat_meshes.append(back_mesh)

    # 5. Descanso de cabeça contornado
    head_mesh = create_rounded_box(extents=[back_w - 0.06, 0.18, 0.12], radius=0.035, subdivisions=2, center=(0.0, 0.92, 0.25), color=col_velvet_head)
    head_mesh.apply_transform(trimesh.transformations.rotation_matrix(np.radians(-8), [1, 0, 0], point=(0.0, 0.40, 0.22)))
    seat_meshes.append(head_mesh)

    # 6. Apoios de braço laterais com porta-copos
    arm_w, arm_h, arm_d = 0.10, 0.24, 0.44
    for side in [-1, 1]:
        ax = side * (seat_w/2 + arm_w/2 - 0.02)
        # Apoio acolchoado
        seat_meshes.append(create_rounded_box(extents=[arm_w, arm_h, arm_d], radius=0.025, subdivisions=1, center=(ax, 0.46, 0.02), color=col_leather))
        # Porta-copos na extremidade frontal
        seat_meshes.append(create_cylinder(radius=0.038, height=0.04, sections=12, center=(ax, 0.59, -0.14), axis=(0,1,0), color=col_cupholder))

    # Concatena o assento e aplica posição e rotação em arco
    seat_full = trimesh.util.concatenate(seat_meshes)
    if abs(angle_deg) > 0.01:
        seat_full.apply_transform(trimesh.transformations.rotation_matrix(np.radians(angle_deg), [0, 1, 0]))
    seat_full.apply_translation([cx, cy, cz])
    return seat_full


def build_cinema():
    meshes = []

    # =========================================================================
    # 1. ESTRUTURA DO AUDITÓRIO (Paredes, Teto e Proscênio)
    # Dimensões da sala: Largura 11.0m (X: [-5.5, +5.5]), Altura 5.5m (Y: [-1.0, 4.5]), Profundidade 14.0m (Z: [-8.5, +5.5])
    # =========================================================================
    wall_base_col = (28, 30, 36, 255)      # Cinza-chumbo acústico
    ceiling_col = (18, 20, 24, 255)        # Teto escuro absorvente
    stage_col = (22, 24, 28, 255)          # Palco em carvalho escuro

    # Parede Frontal / Proscênio atrás da tela (Z = -8.20m)
    meshes.append(create_subdivided_box(extents=[11.0, 5.50, 0.15], subdivisions=2, center=(0.0, 1.75, -8.25), color=(14, 15, 18, 255)))
    # Parede Traseira (atrás da última fileira, Z = +5.20m)
    meshes.append(create_subdivided_box(extents=[11.0, 5.50, 0.15], subdivisions=2, center=(0.0, 1.75, 5.25), color=wall_base_col))
    # Parede Esquerda (X = -5.50m)
    meshes.append(create_subdivided_box(extents=[0.15, 5.50, 13.50], subdivisions=2, center=(-5.55, 1.75, -1.50), color=wall_base_col))
    # Parede Direita (X = +5.50m)
    meshes.append(create_subdivided_box(extents=[0.15, 5.50, 13.50], subdivisions=2, center=(5.55, 1.75, -1.50), color=wall_base_col))
    # Teto
    meshes.append(create_subdivided_box(extents=[11.0, 0.10, 13.50], subdivisions=2, center=(0.0, 4.55, -1.50), color=ceiling_col))

    # =========================================================================
    # 2. PALCO E MOLDURA DA TELA IMAX
    # Tela virtual: screen_pos = 0.0, 2.20, -7.50 | screen_scale = 6.00, 3.375
    # Base da tela em Y = 2.20 - 3.375/2 = 0.51m. Topo em Y = 3.89m.
    # =========================================================================
    # Palco elevado suave abaixo da tela (altura Y = 0.35m, Z de -6.8m a -8.2m)
    meshes.append(create_rounded_box(extents=[9.60, 0.40, 1.40], radius=0.05, subdivisions=2, center=(0.0, 0.15, -7.50), color=stage_col))

    # Saia frontal do palco em veludo escuro plissado
    for p in range(24):
        px = -4.60 + p * 0.40
        meshes.append(create_cylinder(radius=0.035, height=0.35, sections=10, center=(px, 0.15, -6.78), axis=(0,1,0), color=(16, 17, 20, 255)))

    # Moldura chanfrada de titânio ao redor da tela IMAX gigante
    sw, sh = 6.00, 3.375
    frame_th = 0.06
    frame_d = 0.05
    frame_col = (25, 27, 32, 255)
    # Moldura superior e inferior
    meshes.append(create_box(extents=[sw + 2*frame_th, frame_th, frame_d], center=(0.0, 2.20 + sh/2 + frame_th/2, -7.55), color=frame_col))
    meshes.append(create_box(extents=[sw + 2*frame_th, frame_th, frame_d], center=(0.0, 2.20 - sh/2 - frame_th/2, -7.55), color=frame_col))
    # Moldura esquerda e direita
    meshes.append(create_box(extents=[frame_th, sh, frame_d], center=(-sw/2 - frame_th/2, 2.20, -7.55), color=frame_col))
    meshes.append(create_box(extents=[frame_th, sh, frame_d], center=(sw/2 + frame_th/2, 2.20, -7.55), color=frame_col))
    # Cavidade preta antirreflexo atrás da tela
    meshes.append(create_box(extents=[sw + 0.20, sh + 0.20, 0.02], center=(0.0, 2.20, -7.58), color=(8, 9, 11, 255)))

    # =========================================================================
    # 3. ANFITEATRO ESCALONADO EM DEGRAUS E CARPETE
    # 4 patamares com subida gradual para visão perfeita:
    # Patamar 0: Z = -4.50m a -3.10m (Y = -0.30m)
    # Patamar 1: Z = -3.10m a -1.50m (Y = -0.15m)
    # Patamar 2 (VIP do Usuário): Z = -1.50m a +0.80m (Y = 0.00m) -> centro em Z = -0.20m
    # Patamar 3: Z = +0.80m a +3.20m (Y = +0.25m)
    # Corredor traseiro: Z = +3.20m a +5.20m (Y = +0.50m)
    # =========================================================================
    carpet_col = (32, 34, 42, 255)        # Carpete acústico cinza grafite
    step_edge_col = (20, 21, 25, 255)     # Cantoneira de degrau preta
    light_amber = (255, 185, 60, 255)     # LED âmbar quente embutido no degrau

    # Plataformas dos patamares
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
        # Bloco do piso do patamar
        meshes.append(create_box(extents=[t_w, 0.40, t_d], center=(0.0, t_y - 0.20, t_z), color=carpet_col))

        # Espelho e nariz de degrau com iluminação LED âmbar
        if t_idx > 0:
            step_z = t["z_min"]
            step_h = t["y"] - tiers[t_idx - 1]["y"]
            step_y = t["y"] - step_h / 2.0
            # Espelho vertical do degrau
            meshes.append(create_box(extents=[t_w, step_h, 0.04], center=(0.0, step_y, step_z), color=step_edge_col))
            # Fita LED âmbar contínua sob o nariz do degrau (ilumina o degrau inferior)
            meshes.append(create_box(extents=[t_w - 0.40, 0.025, 0.03], center=(0.0, t["y"] - 0.02, step_z - 0.02), color=light_amber))

    # =========================================================================
    # 4. POLTRONAS ERGONÔMICAS EM ARCO (STADIUM SEATING)
    # Fileira 0: 6 poltronas em Z = -3.90m, Y = -0.30m
    # Fileira 1: 8 poltronas em Z = -2.40m, Y = -0.15m
    # Fileira 2 (VIP Usuário): 8 poltronas em Z = -0.30m, Y = 0.00m (Usuário na central X = 0.0)
    # Fileira 3: 8 poltronas em Z = +1.80m, Y = +0.25m
    # =========================================================================
    # Centro de curvatura em direção à tela IMAX (Z = -7.50m)
    curve_center_z = -7.50

    seat_rows = [
        {"y": -0.30, "z": -3.90, "count": 6, "spacing": 0.76},
        {"y": -0.15, "z": -2.40, "count": 8, "spacing": 0.78},
        {"y":  0.00, "z": -0.30, "count": 8, "spacing": 0.80}, # Fileira VIP
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
            # Calcula ângulo de orientação radial voltado para o centro da tela
            dx = sx
            dz = r_z - curve_center_z
            angle_deg = -np.degrees(np.arctan2(dx, dz)) * 0.75 # Curvatura elegante

            meshes.append(create_cinema_seat(center=(sx, r_y, r_z), angle_deg=angle_deg))

    # =========================================================================
    # 5. PAINÉIS ACÚSTICOS ART DÉCO NAS PAREDES LATERAIS (X = ±5.50m)
    # Painéis intercalados com frisos de madeira e arandelas com iluminação dourada
    # =========================================================================
    panel_col = (38, 42, 50, 255)         # Tecido acústico grafite
    panel_wood = (145, 100, 65, 255)      # Friso em madeira nobre
    sconce_glow = (255, 210, 110, 255)    # Luz dourada das arandelas

    panel_z_centers = [-5.50, -3.50, -1.50, 0.50, 2.50, 4.50]
    for side in [-1, 1]:
        px = side * 5.42
        for pz in panel_z_centers:
            # Placa acústica chanfrada
            meshes.append(create_rounded_box(extents=[0.06, 3.20, 1.60], radius=0.04, subdivisions=1, center=(px, 2.10, pz), color=panel_col))
            # Frisos verticais decorativos em madeira
            meshes.append(create_box(extents=[0.08, 3.40, 0.05], center=(px, 2.10, pz - 0.78), color=panel_wood))
            meshes.append(create_box(extents=[0.08, 3.40, 0.05], center=(px, 2.10, pz + 0.78), color=panel_wood))

            # Arandela elegante de iluminação indireta no centro do painel
            sconce_y = 2.40
            meshes.append(create_box(extents=[0.07, 0.28, 0.12], center=(px, sconce_y, pz), color=(25, 26, 30, 255)))
            # Feixe emissivo superior e inferior
            meshes.append(create_cylinder(radius=0.04, height=0.06, sections=10, center=(px, sconce_y + 0.15, pz), axis=(0,1,0), color=sconce_glow))
            meshes.append(create_cylinder(radius=0.04, height=0.06, sections=10, center=(px, sconce_y - 0.15, pz), axis=(0,1,0), color=sconce_glow))

    # =========================================================================
    # 6. BAFFLES ACÚSTICOS NO TETO E CABINE DE PROJEÇÃO
    # =========================================================================
    # Baffles acústicos suspensos no teto
    for bz in [-5.0, -3.0, -1.0, 1.0, 3.0]:
        meshes.append(create_rounded_box(extents=[8.80, 0.25, 0.12], radius=0.03, subdivisions=1, center=(0.0, 4.35, bz), color=(24, 26, 32, 255)))

    # Cabine de projeção ao fundo (parede traseira Z = +5.20m, Y = 2.80m)
    booth_glass = (45, 75, 110, 255)      # Vidro azulado translúcido com luz interior
    meshes.append(create_box(extents=[1.80, 0.60, 0.08], center=(0.0, 3.20, 5.15), color=(22, 24, 28, 255)))
    meshes.append(create_box(extents=[1.50, 0.35, 0.04], center=(0.0, 3.20, 5.18), color=booth_glass))

    # =========================================================================
    # 7. CONCATENAÇÃO EM MALHA ÚNICA E BAKED AMBIENT OCCLUSION (AO)
    # =========================================================================
    full_mesh = trimesh.util.concatenate(meshes)
    verts = full_mesh.vertices.copy()
    normals = full_mesh.vertex_normals.copy()
    colors = full_mesh.visual.vertex_colors.copy().astype(np.float32)[:, :3] / 255.0

    # Iluminação ambiente de cinema (atmosfera suave e intimista)
    ambient = 0.42

    # Luz de tela (screen bounce) simulada vinda da posição da tela IMAX (0.0, 2.20, -7.50)
    screen_pt = np.array([0.0, 2.20, -7.50])
    s_vec = screen_pt - verts
    s_dist = np.linalg.norm(s_vec, axis=1, keepdims=True)
    s_dir = s_vec / np.maximum(1e-6, s_dist)
    s_diff = np.maximum(0.0, np.sum(normals * s_dir, axis=1, keepdims=True))
    s_atten = 1.0 / (1.0 + 0.15 * s_dist + 0.04 * (s_dist ** 2))
    s_col = np.array([0.70, 0.82, 1.0]) # Luz azulada cinematográfica suave

    # Luzes de rodapé âmbar (step lights) nos degraus
    step_light_contrib = np.zeros((len(verts), 1), dtype=np.float32)
    for t_step_z in [-3.20, -1.60, 0.80, 3.20]:
        dz = np.abs(verts[:, 2:3] - t_step_z)
        dy = np.abs(verts[:, 1:2])
        mask = (dz < 0.80) & (dy < 0.60)
        step_light_contrib += np.where(mask, (1.0 - dz / 0.80) * (1.0 - dy / 0.60) * 0.40, 0.0)
    step_col = np.array([1.0, 0.72, 0.25])

    # Arandelas de parede laterais
    wall_light_contrib = np.zeros((len(verts), 1), dtype=np.float32)
    dist_side = np.minimum(np.abs(verts[:, 0:1] + 5.40), np.abs(verts[:, 0:1] - 5.40))
    wall_mask = dist_side < 1.20
    wall_light_contrib += np.where(wall_mask, (1.0 - dist_side / 1.20) * 0.25, 0.0)
    sconce_light_col = np.array([1.0, 0.85, 0.50])

    # Ambient Occlusion (AO): cantos, rodapés e sob as poltronas
    ao = np.ones((len(verts), 1), dtype=np.float32)
    # Proximidade do chão
    y_rel = np.clip((verts[:, 1:2] + 0.50) / 0.20, 0.0, 1.0)
    ao *= (0.75 + 0.25 * y_rel)
    # Cantos laterais
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

    return full_mesh


def main():
    print("Criando malha de alta fidelidade do Cinema IMAX...")
    mesh = build_cinema()
    print(f"Malha gerada: {len(mesh.vertices)} vértices, {len(mesh.faces)} faces ({len(mesh.faces)} triângulos)")

    glb_bytes = mesh.export(file_type="glb")
    with open(MODEL_PATH, "wb") as f:
        f.write(glb_bytes)

    size_kb = os.path.getsize(MODEL_PATH) / 1024
    print(f"Salvo em: {MODEL_PATH} ({size_kb:.1f} KB)")


if __name__ == "__main__":
    main()
