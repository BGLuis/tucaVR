#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate-living-room.py — Gerador do ambiente 3D "Sala de Estar" de alta fidelidade para o tucaVR.

Inspirado nos ambientes de sala de estar do Bigscreen VR e Skybox VR:
- Proporções arquiteturais residenciais modernas (5.2m x 2.9m x 5.8m).
- Elimina 100% de sobreposição e clipping entre móveis:
  * Tela virtual em Z = -2.80m, Y = 1.50m (base da tela em Y = 0.825m).
  * Painel ripado de madeira contra a parede frontal em Z = -3.38m.
  * Console suspenso montado na parede em Z = -3.15m (topo em Y = 0.55m, com 27cm de folga da tela).
  * Mesa de centro em Z = -1.50m (topo em Y = 0.35m, com mais de 1m de área livre).
  * Câmera do usuário em (0, 1.15, 0).
  * Sofá modular em L: assento em Y = 0.38m (77cm abaixo dos olhos), encosto em Z = +0.65m.
- Iluminação baked em vertex colors com Ambient Occlusion (AO) suave em cantos e rodapés.
- Janela panorâmica com horizonte noturno/crepuscular na parede esquerda.
- 1 draw call, ~6.500 a 8.500 triângulos, formato glTF 2.0 binário (.glb).
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
    """Cria uma caixa com cantos e arestas chanfrados/arredondados para estofados e móveis finos."""
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


def build_living_room():
    meshes = []

    # =========================================================================
    # 1. ESTRUTURA ARQUITETÔNICA DA SALA
    # Dimensões: X: [-2.60, +2.60] (5.2m), Y: [0.0, 2.90] (2.9m), Z: [-3.40, +2.40] (5.8m)
    # =========================================================================
    wall_col = (235, 232, 226, 255)       # Branco quente escandinavo
    ceiling_col = (245, 243, 238, 255)    # Branco suave acetinado
    baseboard_col = (210, 206, 198, 255)  # Rodapé arquitetônico fino

    # Parede Traseira (Z = +2.40)
    meshes.append(create_subdivided_box(extents=[5.20, 2.90, 0.10], subdivisions=2, center=(0.0, 1.45, 2.45), color=wall_col))
    # Parede Frontal (Z = -3.40)
    meshes.append(create_subdivided_box(extents=[5.20, 2.90, 0.10], subdivisions=2, center=(0.0, 1.45, -3.45), color=wall_col))
    # Parede Direita (X = +2.60)
    meshes.append(create_subdivided_box(extents=[0.10, 2.90, 5.80], subdivisions=2, center=(2.65, 1.45, -0.50), color=wall_col))

    # Parede Esquerda com Janela Panorâmica (de Z = -2.60 a +0.80, Y de 0.60 a 2.60)
    # Abaixo da janela:
    meshes.append(create_box(extents=[0.10, 0.60, 3.40], center=(-2.65, 0.30, -0.90), color=wall_col))
    # Acima da janela:
    meshes.append(create_box(extents=[0.10, 0.30, 3.40], center=(-2.65, 2.75, -0.90), color=wall_col))
    # Lateral frontal da janela:
    meshes.append(create_box(extents=[0.10, 2.90, 0.80], center=(-2.65, 1.45, -3.00), color=wall_col))
    # Lateral traseira da janela:
    meshes.append(create_box(extents=[0.10, 2.90, 1.60], center=(-2.65, 1.45, 1.60), color=wall_col))

    # Teto
    meshes.append(create_subdivided_box(extents=[5.20, 0.05, 5.80], subdivisions=2, center=(0.0, 2.925, -0.50), color=ceiling_col))

    # Piso em Tábuas de Carvalho Claro Nobre (Y = 0.0)
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

    # Rodapés modernos finos (altura 10cm)
    meshes.append(create_box(extents=[5.20, 0.10, 0.02], center=(0.0, 0.05, 2.39), color=baseboard_col))
    meshes.append(create_box(extents=[5.20, 0.10, 0.02], center=(0.0, 0.05, -3.39), color=baseboard_col))
    meshes.append(create_box(extents=[0.02, 0.10, 5.80], center=(2.59, 0.05, -0.50), color=baseboard_col))
    meshes.append(create_box(extents=[0.02, 0.10, 0.80], center=(-2.59, 0.05, -3.00), color=baseboard_col))
    meshes.append(create_box(extents=[0.02, 0.10, 1.60], center=(-2.59, 0.05, 1.60), color=baseboard_col))

    # =========================================================================
    # 2. SOFÁ SECTIONAL MODERNO (L-SHAPE)
    # Usuário sentado em (0.0, 1.15, 0.0).
    # Assento do sofá em Y = 0.38m (77cm abaixo dos olhos).
    # Encosto em Z = +0.65m (atrás da cabeça).
    # Chaise lateral à direita em X = +1.20 a +1.80, estendendo-se para frente.
    # =========================================================================
    sofa_main_col = (52, 58, 68, 255)       # Grafite azulado nórdico
    sofa_cushion_col = (62, 70, 82, 255)    # Tecido aveludado macio
    sofa_base_wood = (40, 32, 26, 255)      # Base escura em madeira
    sofa_leg_col = (24, 24, 26, 255)        # Pés metálicos escuros

    # Base principal do sofá (largura 2.6m, profundidade 0.95m, altura 0.15m)
    meshes.append(create_box(extents=[2.60, 0.12, 0.95], center=(0.0, 0.14, 0.40), color=sofa_base_wood))

    # Pés cilíndricos discretos (altura 8cm)
    for lx in [-1.20, 0.0, 1.20]:
        for lz in [0.0, 0.80]:
            meshes.append(create_cylinder(radius=0.025, height=0.08, sections=12, center=(lx, 0.04, lz), axis=(0,1,0), color=sofa_leg_col))

    # Almofadas de assento principais (3 lugares: esquerdo, centro, direito)
    # Centro do usuário em X = 0.0. Assento do centro fica de X = -0.40 a +0.40.
    c_w, c_d, c_h = 0.82, 0.72, 0.16
    for cx in [-0.84, 0.0, 0.84]:
        meshes.append(create_rounded_box(extents=[c_w, c_h, c_d], radius=0.04, subdivisions=2, center=(cx, 0.30, 0.28), color=sofa_cushion_col))

    # Encosto traseiro estrutural (altura até 0.75m)
    meshes.append(create_rounded_box(extents=[2.60, 0.50, 0.20], radius=0.03, subdivisions=2, center=(0.0, 0.53, 0.76), color=sofa_main_col))

    # Almofadas do encosto (ergonômicas, atrás do assento)
    for cx in [-0.84, 0.0, 0.84]:
        back_cushion = create_rounded_box(extents=[c_w, 0.42, 0.18], radius=0.04, subdivisions=2, center=(cx, 0.58, 0.64), color=sofa_cushion_col)
        # Leve inclinação ergonômica para trás
        back_cushion.apply_transform(trimesh.transformations.rotation_matrix(np.radians(-7), [1, 0, 0], point=(cx, 0.58, 0.64)))
        meshes.append(back_cushion)

    # Braço esquerdo do sofá
    meshes.append(create_rounded_box(extents=[0.20, 0.30, 0.95], radius=0.03, subdivisions=2, center=(-1.38, 0.37, 0.40), color=sofa_main_col))

    # Chaise lateral estendida à direita (módulo lounge)
    chaise_w = 0.85
    chaise_d = 1.10
    meshes.append(create_box(extents=[chaise_w, 0.12, chaise_d], center=(1.38, 0.14, -0.45), color=sofa_base_wood))
    for clz in [-0.90, -0.10]:
        meshes.append(create_cylinder(radius=0.025, height=0.08, sections=12, center=(1.65, 0.04, clz), axis=(0,1,0), color=sofa_leg_col))
    meshes.append(create_rounded_box(extents=[chaise_w, c_h, chaise_d], radius=0.04, subdivisions=2, center=(1.38, 0.30, -0.45), color=sofa_cushion_col))
    # Braço direito estendido
    meshes.append(create_rounded_box(extents=[0.20, 0.30, 1.95], radius=0.03, subdivisions=2, center=(1.88, 0.37, -0.10), color=sofa_main_col))

    # Almofadas decorativas nos cantos (mostarda quente e terracota suave)
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
    # 3. TAPETE DE LÃ E MESA DE CENTRO MINIMALISTA
    # Sem cruzamento de geometrias!
    # Tapete: Z de -0.50 a -2.50 (centro Z = -1.50)
    # Mesa: Z = -1.50, Y = 0.35m
    # =========================================================================
    rug_col_base = (230, 226, 218, 255)   # Lã marfim macia
    rug_col_line = (85, 88, 95, 255)      # Linhas geométricas cinza
    rug_w, rug_d = 2.70, 2.00
    rug_z = -1.50
    # Base do tapete (altura fina Y = 0.004)
    meshes.append(create_rounded_box(extents=[rug_w, 0.008, rug_d], radius=0.02, subdivisions=1, center=(0.0, 0.004, rug_z), color=rug_col_base))
    # Frisos geométricos escandinavos
    meshes.append(create_box(extents=[rug_w - 0.20, 0.002, 0.03], center=(0.0, 0.009, rug_z - 0.70), color=rug_col_line))
    meshes.append(create_box(extents=[rug_w - 0.20, 0.002, 0.03], center=(0.0, 0.009, rug_z + 0.70), color=rug_col_line))
    meshes.append(create_box(extents=[0.03, 0.002, rug_d - 0.30], center=(-rug_w/2 + 0.20, 0.009, rug_z), color=rug_col_line))
    meshes.append(create_box(extents=[0.03, 0.002, rug_d - 0.30], center=(rug_w/2 - 0.20, 0.009, rug_z), color=rug_col_line))

    # Mesa de Centro Ovalada / Retangular Arredondada
    table_w, table_d, table_h = 1.05, 0.52, 0.34
    table_wood = (50, 42, 36, 255)        # Nogueira escura
    table_metal = (28, 28, 30, 255)       # Metal preto fosco
    # Tampo
    meshes.append(create_rounded_box(extents=[table_w, 0.03, table_d], radius=0.03, subdivisions=2, center=(0.0, table_h, rug_z), color=table_wood))
    # 4 pernas cônicas
    for tx in [-table_w/2 + 0.08, table_w/2 - 0.08]:
        for tz in [rug_z - table_d/2 + 0.08, rug_z + table_d/2 - 0.08]:
            meshes.append(create_cylinder(radius=0.015, height=table_h, sections=12, center=(tx, table_h/2, tz), axis=(0,1,0), color=table_metal))
    # Prateleira inferior
    meshes.append(create_box(extents=[table_w - 0.12, 0.015, table_d - 0.12], center=(0.0, 0.12, rug_z), color=table_metal))

    # Objetos decorativos sobre a mesa
    # Livro de fotografia/arquitetura
    meshes.append(create_box(extents=[0.24, 0.02, 0.18], center=(-0.22, table_h + 0.025, rug_z - 0.05), color=(220, 215, 205, 255)))
    meshes.append(create_box(extents=[0.11, 0.006, 0.17], center=(-0.28, table_h + 0.038, rug_z - 0.05), color=(195, 75, 55, 255)))
    # Vaso de cerâmica com suculenta
    meshes.append(create_cylinder(radius=0.06, height=0.05, sections=14, center=(0.24, table_h + 0.04, rug_z + 0.04), axis=(0,1,0), color=(120, 140, 130, 255)))
    meshes.append(create_cylinder(radius=0.035, height=0.05, sections=12, center=(0.24, table_h + 0.085, rug_z + 0.04), axis=(0,1,0), color=(55, 110, 70, 255)))

    # =========================================================================
    # 4. PAREDE FRONTAL: PAINEL RIPADO E CONSOLE DA TV
    # Parede em Z = -3.40m.
    # Painel acústico ripado em Z = -3.37m.
    # Console flutuante suspenso em Z = -3.15m (topo Y = 0.55m).
    # Tela virtual em Z = -2.80m, Y = 1.50m (base Y = 0.825m).
    # Folga vertical entre o topo do console e a base da tela: 27.5 cm!
    # Folga de profundidade entre a tela e o console: 35 cm!
    # =========================================================================
    panel_w = 3.60
    panel_h = 2.60
    slat_wood_col = (150, 105, 70, 255)   # Carvalho nobre
    slat_back_col = (26, 24, 22, 255)     # Fundo preto acústico

    # Placa base escura do painel
    meshes.append(create_box(extents=[panel_w, panel_h, 0.02], center=(0.0, 1.45, -3.38), color=slat_back_col))

    # Ripas verticais finas com espaçamento simétrico
    num_slats = 32
    slat_w = 0.04
    slat_step = panel_w / num_slats
    for s in range(num_slats):
        sx = -panel_w / 2.0 + (s + 0.5) * slat_step
        meshes.append(create_box(extents=[slat_w, panel_h, 0.02], center=(sx, 1.45, -3.36), color=slat_wood_col))

    # Iluminação LED indireta (Halo / Bias Glow) atrás do painel ripado
    halo_glow = (255, 230, 180, 255)
    meshes.append(create_box(extents=[panel_w + 0.10, 0.02, 0.015], center=(0.0, 2.76, -3.37), color=halo_glow))
    meshes.append(create_box(extents=[panel_w + 0.10, 0.02, 0.015], center=(0.0, 0.14, -3.37), color=halo_glow))
    meshes.append(create_box(extents=[0.02, panel_h + 0.02, 0.015], center=(-panel_w/2 - 0.05, 1.45, -3.37), color=halo_glow))
    meshes.append(create_box(extents=[0.02, panel_h + 0.02, 0.015], center=(panel_w/2 + 0.05, 1.45, -3.37), color=halo_glow))

    # Console Flutuante Suspenso (Low Profile)
    # Posição: Z = -3.15m, profundidade 0.32m (vai de -3.31 a -2.99m)
    # Altura: Y = 0.35m, extents Y = 0.28m (vai de Y = 0.21m a 0.49m)
    # Tampo superior em Y = 0.50m.
    console_base = (42, 44, 48, 255)      # Grafite acetinado moderno
    console_top = (160, 118, 80, 255)     # Tampo em carvalho nobre
    c_w, c_h, c_d = 2.60, 0.28, 0.32
    c_y, c_z = 0.36, -3.15
    meshes.append(create_box(extents=[c_w, c_h, c_d], center=(0.0, c_y, c_z), color=console_base))
    meshes.append(create_box(extents=[c_w + 0.04, 0.025, c_d + 0.04], center=(0.0, c_y + c_h/2 + 0.0125, c_z), color=console_top))

    # Soundbar slim elegante centralizada sobre o console (topo em Y = 0.56m)
    meshes.append(create_rounded_box(extents=[1.05, 0.045, 0.08], radius=0.015, subdivisions=1, center=(0.0, c_y + c_h/2 + 0.045, c_z), color=(22, 24, 26, 255)))

    # Pequenos elementos decorativos sobre o console
    meshes.append(create_box(extents=[0.20, 0.03, 0.15], center=(-0.95, c_y + c_h/2 + 0.035, c_z), color=(190, 80, 60, 255)))
    meshes.append(create_cylinder(radius=0.05, height=0.12, sections=12, center=(0.95, c_y + c_h/2 + 0.08, c_z), axis=(0,1,0), color=(220, 215, 205, 255)))

    # Moldura de TV ultra-slim discreta na parede em Z = -2.82m (a tela virtual fica em Z = -2.80m)
    # Proporção 16:9 exata (2.40m x 1.35m, centro Y = 1.50m)
    tv_frame_col = (18, 20, 24, 255)
    sw, sh = 2.40, 1.35
    b_th = 0.025
    meshes.append(create_box(extents=[sw + 2*b_th, b_th, 0.02], center=(0.0, 1.50 + sh/2 + b_th/2, -2.82), color=tv_frame_col))
    meshes.append(create_box(extents=[sw + 2*b_th, b_th, 0.02], center=(0.0, 1.50 - sh/2 - b_th/2, -2.82), color=tv_frame_col))
    meshes.append(create_box(extents=[b_th, sh, 0.02], center=(-sw/2 - b_th/2, 1.50, -2.82), color=tv_frame_col))
    meshes.append(create_box(extents=[b_th, sh, 0.02], center=(sw/2 + b_th/2, 1.50, -2.82), color=tv_frame_col))
    # Cavidade traseira antirreflexo OLED
    meshes.append(create_box(extents=[sw, sh, 0.01], center=(0.0, 1.50, -2.83), color=(8, 10, 12, 255)))

    # =========================================================================
    # 5. JANELA PANORÂMICA E CORTINAS MODERNAS (Parede Esquerda, X = -2.60)
    # =========================================================================
    win_w = 3.40
    win_h = 2.00
    win_y_mid = 1.60
    win_z_mid = -0.90
    win_frame = (32, 34, 38, 255)

    # Vista externa de crepúsculo / skyline com gradiente atmosférico
    sky_colors = [
        (35, 55, 95, 255),    # Azul cobalto profundo
        (65, 75, 110, 255),   # Violeta suave
        (120, 95, 110, 255),  # Alaranjado crepuscular no horizonte
    ]
    strip_h = win_h / 3.0
    for st in range(3):
        sy = (win_y_mid - win_h/2) + (2 - st + 0.5) * strip_h
        meshes.append(create_box(extents=[0.02, strip_h, win_w], center=(-2.72, sy, win_z_mid), color=sky_colors[st]))

    # Silhueta de montanhas / cidade no horizonte
    meshes.append(create_box(extents=[0.02, 0.20, win_w], center=(-2.70, win_y_mid - win_h/2 + 0.10, win_z_mid), color=(18, 22, 30, 255)))

    # Caixilho de alumínio anodizado escuro
    meshes.append(create_box(extents=[0.10, 0.04, win_w + 0.08], center=(-2.60, win_y_mid - win_h/2, win_z_mid), color=win_frame))
    meshes.append(create_box(extents=[0.10, 0.04, win_w + 0.08], center=(-2.60, win_y_mid + win_h/2, win_z_mid), color=win_frame))
    meshes.append(create_box(extents=[0.10, win_h, 0.04], center=(-2.60, win_y_mid, win_z_mid - win_w/2), color=win_frame))
    meshes.append(create_box(extents=[0.10, win_h, 0.04], center=(-2.60, win_y_mid, win_z_mid + win_w/2), color=win_frame))
    # 2 montantes verticais delgados dividindo em 3 panos de vidro amplos
    meshes.append(create_box(extents=[0.08, win_h, 0.025], center=(-2.60, win_y_mid, win_z_mid - win_w/6), color=win_frame))
    meshes.append(create_box(extents=[0.08, win_h, 0.025], center=(-2.60, win_y_mid, win_z_mid + win_w/6), color=win_frame))

    # Cortinas de linho franzidas elegantemente esticadas nos cantos (sem cruzar a janela)
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
    # 6. LUMINÁRIA DE CHÃO CURVADA (Luz Indireta Quente 2700K)
    # Posicionada no canto esquerdo recuado: X = -2.10m, Z = -1.90m
    # =========================================================================
    lamp_x, lamp_z = -2.10, -1.90
    lamp_base = (30, 30, 32, 255)
    lamp_metal = (190, 160, 100, 255)     # Latão escovado
    lamp_shade = (255, 240, 210, 255)     # Cúpula translúcida
    lamp_glow = (255, 220, 130, 255)      # Bulbo quente emissivo

    meshes.append(create_cylinder(radius=0.18, height=0.03, sections=16, center=(lamp_x, 0.015, lamp_z), axis=(0,1,0), color=lamp_base))
    meshes.append(create_cylinder(radius=0.015, height=1.75, sections=12, center=(lamp_x, 0.88, lamp_z), axis=(0,1,0), color=lamp_metal))
    meshes.append(create_cylinder(radius=0.20, height=0.30, sections=18, center=(lamp_x, 1.62, lamp_z), axis=(0,1,0), color=lamp_shade))
    meshes.append(create_cylinder(radius=0.06, height=0.10, sections=12, center=(lamp_x, 1.62, lamp_z), axis=(0,1,0), color=lamp_glow))

    # =========================================================================
    # 7. QUADROS DE ARTE ABSTRATA (Parede Direita, X = +2.60)
    # =========================================================================
    art_z = [-1.40, -0.20, 1.00]
    art_palette = [
        (190, 85, 55, 255),   # Terracota
        (85, 125, 110, 255),  # Verde sálvia
        (45, 65, 95, 255),    # Azul ardósia
    ]
    for idx, az in enumerate(art_z):
        # Moldura em carvalho
        meshes.append(create_box(extents=[0.02, 0.85, 0.65], center=(2.58, 1.55, az), color=(150, 110, 75, 255)))
        # Passepartout branco
        meshes.append(create_box(extents=[0.022, 0.77, 0.57], center=(2.578, 1.55, az), color=(245, 242, 235, 255)))
        # Arte abstrata contemporânea
        meshes.append(create_box(extents=[0.024, 0.50, 0.38], center=(2.576, 1.55, az), color=art_palette[idx]))

    # =========================================================================
    # 8. SPOTLIGHTS EMBUTIDOS NO TETO
    # =========================================================================
    spot_glow = (255, 245, 220, 255)
    for sx, sz in [(-1.20, -1.50), (1.20, -1.50), (0.0, -2.60), (1.30, 0.0)]:
        meshes.append(create_cylinder(radius=0.06, height=0.015, sections=12, center=(sx, 2.89, sz), axis=(0,1,0), color=spot_glow))

    # =========================================================================
    # 9. CONCATENAÇÃO EM MALHA ÚNICA E BAKED AMBIENT OCCLUSION (AO)
    # =========================================================================
    full_mesh = trimesh.util.concatenate(meshes)
    verts = full_mesh.vertices.copy()
    normals = full_mesh.vertex_normals.copy()
    colors = full_mesh.visual.vertex_colors.copy().astype(np.float32)[:, :3] / 255.0

    # Iluminação ambiente básica equilibrada
    ambient = 0.55

    # Luz direcional vinda da janela
    win_dir = np.array([0.7, 0.4, -0.3])
    win_dir = win_dir / np.linalg.norm(win_dir)
    win_diff = np.maximum(0.0, np.dot(normals, win_dir))
    win_col = np.array([0.88, 0.92, 1.0])

    # Luz pontual da luminária de chão (-2.10, 1.62, -1.90)
    lamp_pos = np.array([-2.10, 1.62, -1.90])
    l_vec = lamp_pos - verts
    l_dist = np.linalg.norm(l_vec, axis=1, keepdims=True)
    l_dir = l_vec / np.maximum(1e-6, l_dist)
    l_diff = np.maximum(0.0, np.sum(normals * l_dir, axis=1, keepdims=True))
    l_atten = 1.0 / (1.0 + 0.7 * l_dist + 0.4 * (l_dist ** 2))
    lamp_col = np.array([1.0, 0.85, 0.55])

    # Luz suave da fita LED atrás do painel da TV (0.0, 1.5, -3.37)
    tv_pos = np.array([0.0, 1.5, -3.37])
    tv_vec = tv_pos - verts
    tv_dist = np.linalg.norm(tv_vec, axis=1, keepdims=True)
    tv_dir = tv_vec / np.maximum(1e-6, tv_dist)
    tv_diff = np.maximum(0.0, np.sum(normals * tv_dir, axis=1, keepdims=True))
    tv_atten = 1.0 / (1.0 + 1.2 * tv_dist + 0.8 * (tv_dist ** 2))
    tv_glow_col = np.array([0.95, 0.88, 0.75])

    # Oclusão de ambiente (AO) nos cantos, rodapés e sob o sofá
    ao = np.ones((len(verts), 1), dtype=np.float32)
    # Rodapé do piso
    y_floor = np.clip(verts[:, 1:2] / 0.12, 0.0, 1.0)
    ao *= (0.78 + 0.22 * y_floor)
    # Cantos das paredes
    dist_wall_x = np.minimum(np.abs(verts[:, 0:1] + 2.60), np.abs(verts[:, 0:1] - 2.60))
    dist_wall_z = np.minimum(np.abs(verts[:, 2:3] + 3.40), np.abs(verts[:, 2:3] - 2.40))
    corner_factor = np.clip(np.minimum(dist_wall_x, dist_wall_z) / 0.15, 0.0, 1.0)
    ao *= (0.82 + 0.18 * corner_factor)
    # Sob o sofá
    under_sofa = (np.abs(verts[:, 0:1]) < 1.40) & (verts[:, 2:3] > -0.10) & (verts[:, 2:3] < 0.90) & (verts[:, 1:2] < 0.25)
    ao[under_sofa] *= 0.60

    # Composição final de iluminação
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

    return full_mesh


def main():
    print("Criando malha arquitetural refinada da Sala de Estar...")
    mesh = build_living_room()
    print(f"Malha gerada: {len(mesh.vertices)} vértices, {len(mesh.faces)} faces ({len(mesh.faces)} triângulos)")

    # Exporta para GLB
    glb_bytes = mesh.export(file_type="glb")
    with open(MODEL_PATH, "wb") as f:
        f.write(glb_bytes)

    size_kb = os.path.getsize(MODEL_PATH) / 1024
    print(f"Salvo em: {MODEL_PATH} ({size_kb:.1f} KB)")


if __name__ == "__main__":
    main()
