#!/usr/bin/env python3
"""
generate-living-room.py - Gerador procedural do ambiente 3D "Sala de Estar" (living_room/model.glb).

Produz um modelo glTF 2.0 binário (.glb) ultraleve e de alta fidelidade para Meta Quest 3:
- Usuário posicionado confortavelmente em um sofá moderno grafite com almofadas.
- Mesinha de centro minimalista com decorações (livro de arquitetura, suculenta).
- Tapete macio geométrico sob a mesa.
- Parede frontal com painel acústico ripado e moldura para ancoragem da tela virtual (screen_pos=0.0,1.5,-2.8, scale=2.4,1.35).
- Console suspenso com soundbar abaixo da TV.
- Luminária de canto com iluminação indireta quente suave (2700K) baked em vertex colors.
- Parede lateral esquerda com janela panorâmica (vista crepúsculo) e cortinas de linho.
- Parede lateral direita com tríptico de arte abstrata minimalista.
- Otimização extrema: 1 draw call, ~6.300 triângulos, vertex colors sem texturas pesadas de VRAM, < 250 KB.
"""

import os
import sys
import json
import numpy as np
import trimesh

def create_box(extents, center=(0, 0, 0), color=(200, 200, 200, 255)):
    """Cria uma caixa simples orientada com 12 triângulos e cores de vértice."""
    box = trimesh.creation.box(extents=extents)
    box.apply_translation(center)
    colors = np.tile(np.array(color, dtype=np.uint8), (len(box.vertices), 1))
    box.visual.vertex_colors = colors
    return box

def create_subdivided_box(extents, subdivisions=1, center=(0, 0, 0), color=(200, 200, 200, 255)):
    """Cria uma caixa subdividida para interpolação suave de iluminação por vértice."""
    box = trimesh.creation.box(extents=extents)
    for _ in range(subdivisions):
        box = box.subdivide()
    box.apply_translation(center)
    colors = np.tile(np.array(color, dtype=np.uint8), (len(box.vertices), 1))
    box.visual.vertex_colors = colors
    return box

def create_rounded_box(extents, radius=0.04, subdivisions=2, center=(0, 0, 0), color=(200, 200, 200, 255)):
    """Cria uma caixa com bordas e cantos arredondados (chanfro contínuo) para estofados e móveis."""
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

def build_living_room_mesh():
    """Gera todas as partes da Sala de Estar e une em uma malha única e otimizada."""
    meshes = []

    # =========================================================================
    # 1. ESTRUTURA ARQUITETÔNICA (Paredes, Teto, Piso em Madeira, Rodapés)
    # Dimensões da sala: X: [-2.80, 2.80], Y: [0.00, 2.80], Z: [-3.10, 2.10]
    # =========================================================================
    wall_color = (230, 228, 222, 255)      # Greige acolhedor
    ceiling_color = (244, 242, 238, 255)   # Branco quente acetinado
    baseboard_col = (212, 210, 202, 255)   # Rodapés arquitetônicos

    # Parede Traseira (atrás do sofá em Z = +2.10)
    meshes.append(create_subdivided_box(extents=[5.60, 2.80, 0.10], subdivisions=2, center=(0.0, 1.40, 2.15), color=wall_color))
    # Parede Frontal (atrás do painel da TV em Z = -3.10)
    meshes.append(create_subdivided_box(extents=[5.60, 2.80, 0.10], subdivisions=2, center=(0.0, 1.40, -3.15), color=wall_color))
    # Parede Direita (em X = +2.80)
    meshes.append(create_subdivided_box(extents=[0.10, 2.80, 5.20], subdivisions=2, center=(2.85, 1.40, -0.50), color=wall_color))

    # Parede Esquerda (com vão livre para janela panorâmica de Z = -2.30 a +0.70, Y = 0.50 a 2.50)
    # Abaixo da janela:
    meshes.append(create_box(extents=[0.10, 0.50, 3.00], center=(-2.85, 0.25, -0.80), color=wall_color))
    # Acima da janela:
    meshes.append(create_box(extents=[0.10, 0.30, 3.00], center=(-2.85, 2.65, -0.80), color=wall_color))
    # Seção frontal-esquerda:
    meshes.append(create_box(extents=[0.10, 2.80, 0.80], center=(-2.85, 1.40, -2.70), color=wall_color))
    # Seção traseira-esquerda:
    meshes.append(create_box(extents=[0.10, 2.80, 1.40], center=(-2.85, 1.40, 1.40), color=wall_color))

    # Teto
    meshes.append(create_subdivided_box(extents=[5.60, 0.05, 5.20], subdivisions=2, center=(0.0, 2.825, -0.50), color=ceiling_color))

    # Piso em Tábuas de Madeira Nobre (Carvalho Escandinavo)
    floor_w = 5.60
    floor_d = 5.20
    n_planks = 14
    plank_d = floor_d / n_planks
    wood_tones = [
        (180, 140, 98, 255),
        (170, 130, 88, 255),
        (190, 150, 108, 255),
        (162, 122, 82, 255),
        (184, 144, 102, 255),
    ]
    for i in range(n_planks):
        z_c = -3.10 + (i + 0.5) * plank_d
        col = wood_tones[i % len(wood_tones)]
        meshes.append(create_box(extents=[floor_w, 0.02, plank_d - 0.005], center=(0.0, -0.01, z_c), color=col))

    # Rodapés ao redor da sala
    meshes.append(create_box(extents=[5.60, 0.10, 0.02], center=(0.0, 0.05, 2.09), color=baseboard_col))
    meshes.append(create_box(extents=[5.60, 0.10, 0.02], center=(0.0, 0.05, -3.09), color=baseboard_col))
    meshes.append(create_box(extents=[0.02, 0.10, 5.20], center=(2.79, 0.05, -0.50), color=baseboard_col))
    meshes.append(create_box(extents=[0.02, 0.10, 0.80], center=(-2.79, 0.05, -2.70), color=baseboard_col))
    meshes.append(create_box(extents=[0.02, 0.10, 1.40], center=(-2.79, 0.05, 1.40), color=baseboard_col))

    # =========================================================================
    # 2. SOFÁ MODERNO E ELEGANTE (Usuário sentado na posição VR de origem)
    # Assento do usuário em (0, 0, 0) com visão livre frontal para a tela virtual
    # =========================================================================
    sofa_upholstery = (42, 48, 58, 255)      # Grafite / azul ardósia profundo
    sofa_cushion_col = (48, 56, 68, 255)     # Estofado macio nos assentos
    sofa_wood_base = (35, 28, 22, 255)       # Base em madeira escura
    sofa_leg_col = (20, 20, 20, 255)         # Pés cônicos em aço escuro

    # Base estrutural do sofá
    meshes.append(create_box(extents=[2.60, 0.12, 1.05], center=(0.0, 0.18, 0.05), color=sofa_wood_base))

    # Pés cônicos do sofá (4 apoios)
    for lx in [-1.20, 1.20]:
        for lz in [-0.40, 0.50]:
            meshes.append(create_cylinder(radius=0.03, height=0.12, sections=12, center=(lx, 0.06, lz), axis=(0,1,0), color=sofa_leg_col))

    # Almofadas do assento (3 assentos: esquerdo, central e direito)
    cushion_w = 0.78
    cushion_d = 0.82
    cushion_h = 0.14
    for cx in [-0.82, 0.0, 0.82]:
        meshes.append(create_rounded_box(extents=[cushion_w, cushion_h, cushion_d], radius=0.035, subdivisions=2, center=(cx, 0.31, -0.05), color=sofa_cushion_col))

    # Estrutura do encosto traseiro
    meshes.append(create_box(extents=[2.60, 0.55, 0.18], center=(0.0, 0.55, 0.48), color=sofa_upholstery))

    # Almofadas traseiras ergonômicas (inclinadas suavemente para trás)
    for cx in [-0.82, 0.0, 0.82]:
        bc = create_rounded_box(extents=[cushion_w, 0.46, 0.18], radius=0.04, subdivisions=2, center=(cx, 0.60, 0.38), color=sofa_cushion_col)
        bc.apply_transform(trimesh.transformations.rotation_matrix(np.radians(-8), [1, 0, 0], point=(cx, 0.60, 0.38)))
        meshes.append(bc)

    # Braços laterais do sofá (arredondados e modernos)
    arm_w, arm_h, arm_d = 0.18, 0.32, 1.05
    for ax in [-1.31, 1.31]:
        meshes.append(create_rounded_box(extents=[arm_w, arm_h, arm_d], radius=0.03, subdivisions=2, center=(ax, 0.40, 0.05), color=sofa_upholstery))

    # Almofadas decorativas nos cantos (paleta mostarda, marfim e azul petróleo)
    ochre_pillow = (205, 145, 52, 255)
    cream_pillow = (228, 224, 215, 255)
    teal_pillow = (38, 88, 98, 255)

    # Almofadas canto esquerdo
    p1 = create_rounded_box(extents=[0.38, 0.38, 0.12], radius=0.04, subdivisions=2, center=(-1.05, 0.52, 0.28), color=ochre_pillow)
    p1.apply_transform(trimesh.transformations.rotation_matrix(np.radians(24), [0, 1, 0], point=(-1.05, 0.52, 0.28)))
    p1.apply_transform(trimesh.transformations.rotation_matrix(np.radians(-12), [1, 0, 0], point=(-1.05, 0.52, 0.28)))
    meshes.append(p1)

    p2 = create_rounded_box(extents=[0.32, 0.32, 0.10], radius=0.035, subdivisions=2, center=(-0.88, 0.46, 0.15), color=cream_pillow)
    p2.apply_transform(trimesh.transformations.rotation_matrix(np.radians(15), [0, 1, 0], point=(-0.88, 0.46, 0.15)))
    meshes.append(p2)

    # Almofadas canto direito
    p3 = create_rounded_box(extents=[0.38, 0.38, 0.12], radius=0.04, subdivisions=2, center=(1.05, 0.52, 0.28), color=ochre_pillow)
    p3.apply_transform(trimesh.transformations.rotation_matrix(np.radians(-24), [0, 1, 0], point=(1.05, 0.52, 0.28)))
    p3.apply_transform(trimesh.transformations.rotation_matrix(np.radians(-12), [1, 0, 0], point=(1.05, 0.52, 0.28)))
    meshes.append(p3)

    p4 = create_rounded_box(extents=[0.32, 0.32, 0.10], radius=0.035, subdivisions=2, center=(0.88, 0.46, 0.15), color=teal_pillow)
    p4.apply_transform(trimesh.transformations.rotation_matrix(np.radians(-15), [0, 1, 0], point=(0.88, 0.46, 0.15)))
    meshes.append(p4)

    # =========================================================================
    # 3. PAREDE FRONTAL: PAINEL ACÚSTICO RIPADO, MOLDURA E CONSOLE SUSPENSO
    # Ancoragem virtual: screen_pos=0.0,1.5,-2.8 | screen_scale=2.4,1.35
    # =========================================================================
    slat_backing_col = (25, 22, 20, 255)
    slat_wood_col = (145, 102, 68, 255)
    panel_w = 3.20
    panel_h = 2.40

    # Placa de fundo escura do painel
    meshes.append(create_box(extents=[panel_w, panel_h, 0.03], center=(0.0, 1.45, -2.93), color=slat_backing_col))

    # Ripas verticais arquitetônicas em madeira carvalho quente
    num_slats = 28
    slat_w = 0.04
    slat_spacing = panel_w / num_slats
    for s in range(num_slats):
        sx = -panel_w / 2.0 + (s + 0.5) * slat_spacing
        meshes.append(create_box(extents=[slat_w, panel_h, 0.02], center=(sx, 1.45, -2.905), color=slat_wood_col))

    # Iluminação indireta de fita LED (Bias Light) ao redor do painel ripado
    led_glow_col = (255, 235, 190, 255)
    meshes.append(create_box(extents=[panel_w + 0.10, 0.02, 0.02], center=(0.0, 2.66, -2.92), color=led_glow_col))
    meshes.append(create_box(extents=[panel_w + 0.10, 0.02, 0.02], center=(0.0, 0.24, -2.92), color=led_glow_col))
    meshes.append(create_box(extents=[0.02, panel_h + 0.02, 0.02], center=(-(panel_w/2 + 0.05), 1.45, -2.92), color=led_glow_col))
    meshes.append(create_box(extents=[0.02, panel_h + 0.02, 0.02], center=((panel_w/2 + 0.05), 1.45, -2.92), color=led_glow_col))

    # Moldura de TV em titânio escuro contornando exatamente a tela virtual
    # A tela fica em Z = -2.80. A moldura é colocada em Z = -2.82.
    frame_col = (22, 24, 28, 255)
    frame_border = 0.03
    sw, sh = 2.40, 1.35
    # Moldura superior
    meshes.append(create_box(extents=[sw + 2*frame_border, frame_border, 0.03], center=(0.0, 1.5 + sh/2 + frame_border/2, -2.82), color=frame_col))
    # Moldura inferior
    meshes.append(create_box(extents=[sw + 2*frame_border, frame_border, 0.03], center=(0.0, 1.5 - sh/2 - frame_border/2, -2.82), color=frame_col))
    # Moldura esquerda
    meshes.append(create_box(extents=[frame_border, sh, 0.03], center=(-sw/2 - frame_border/2, 1.5, -2.82), color=frame_col))
    # Moldura direita
    meshes.append(create_box(extents=[frame_border, sh, 0.03], center=(sw/2 + frame_border/2, 1.5, -2.82), color=frame_col))
    # Placa OLED preta de fundo da TV (recessed cavity)
    meshes.append(create_box(extents=[sw, sh, 0.01], center=(0.0, 1.5, -2.835), color=(12, 14, 16, 255)))

    # Rack / Console suspenso minimalista abaixo da TV
    console_col = (52, 45, 40, 255)       # Grafite acetinado
    console_top_col = (165, 122, 85, 255) # Tampo em madeira carvalho
    c_w, c_h, c_d = 2.80, 0.32, 0.36
    c_y = 0.40
    c_z = -2.68
    # Corpo principal do rack
    meshes.append(create_box(extents=[c_w, c_h, c_d], center=(0.0, c_y, c_z), color=console_col))
    # Tampo superior
    meshes.append(create_box(extents=[c_w + 0.04, 0.025, c_d + 0.04], center=(0.0, c_y + c_h/2 + 0.0125, c_z), color=console_top_col))

    # Soundbar slim sobre o console
    meshes.append(create_box(extents=[1.10, 0.05, 0.09], center=(0.0, c_y + c_h/2 + 0.05, c_z), color=(20, 22, 24, 255)))

    # Decorações minimalistas no console
    meshes.append(create_box(extents=[0.24, 0.03, 0.18], center=(-1.05, c_y + c_h/2 + 0.04, c_z), color=(195, 80, 55, 255)))
    meshes.append(create_box(extents=[0.22, 0.025, 0.16], center=(-1.05, c_y + c_h/2 + 0.068, c_z), color=(235, 230, 220, 255)))
    meshes.append(create_cylinder(radius=0.06, height=0.14, sections=14, center=(1.05, c_y + c_h/2 + 0.095, c_z), axis=(0,1,0), color=(215, 210, 200, 255)))

    # =========================================================================
    # 4. TAPETE MACIO GEOMÉTRICO E MESINHA DE CENTRO
    # =========================================================================
    # Tapete geométrico escandinavo (marfim + linhas carvão)
    rug_base_col = (228, 224, 214, 255)
    rug_pat_col = (72, 75, 82, 255)
    rug_w, rug_d = 2.80, 1.80
    rug_z = -1.35
    # Base chanfrada do tapete
    meshes.append(create_rounded_box(extents=[rug_w, 0.008, rug_d], radius=0.02, subdivisions=1, center=(0.0, 0.004, rug_z), color=rug_base_col))
    # Bordas e padronagem geométrica com linhas finas
    meshes.append(create_box(extents=[rug_w - 0.20, 0.002, 0.04], center=(0.0, 0.009, rug_z - rug_d/2 + 0.15), color=rug_pat_col))
    meshes.append(create_box(extents=[rug_w - 0.20, 0.002, 0.04], center=(0.0, 0.009, rug_z + rug_d/2 - 0.15), color=rug_pat_col))
    meshes.append(create_box(extents=[0.04, 0.002, rug_d - 0.30], center=(-rug_w/2 + 0.15, 0.009, rug_z), color=rug_pat_col))
    meshes.append(create_box(extents=[0.04, 0.002, rug_d - 0.30], center=(rug_w/2 - 0.15, 0.009, rug_z), color=rug_pat_col))
    for sign in [-1, 1]:
        d_line = create_box(extents=[1.40, 0.002, 0.035], center=(0.0, 0.009, rug_z), color=rug_pat_col)
        d_line.apply_transform(trimesh.transformations.rotation_matrix(sign * np.radians(35), [0, 1, 0], point=(0.0, 0.009, rug_z)))
        meshes.append(d_line)

    # Mesinha de centro moderna
    ct_w, ct_d, ct_h, ct_z = 1.10, 0.56, 0.38, -1.45
    table_top_col = (48, 40, 34, 255) # Nogueira escura
    table_leg_col = (24, 24, 26, 255) # Aço preto fosco
    # Tampo arredondado da mesa
    meshes.append(create_rounded_box(extents=[ct_w, 0.035, ct_d], radius=0.03, subdivisions=2, center=(0.0, ct_h, ct_z), color=table_top_col))
    # 4 pernas em haste metálica
    for tx in [-ct_w/2 + 0.08, ct_w/2 - 0.08]:
        for tz in [ct_z - ct_d/2 + 0.08, ct_z + ct_d/2 - 0.08]:
            meshes.append(create_cylinder(radius=0.015, height=ct_h, sections=12, center=(tx, ct_h/2, tz), axis=(0,1,0), color=table_leg_col))
    # Prateleira inferior
    meshes.append(create_box(extents=[ct_w - 0.12, 0.015, ct_d - 0.12], center=(0.0, 0.14, ct_z), color=table_leg_col))

    # Livro de arte/arquitetura e vaso de cerâmica com suculenta
    meshes.append(create_box(extents=[0.26, 0.02, 0.19], center=(-0.24, ct_h + 0.028, ct_z - 0.04), color=(225, 220, 210, 255)))
    meshes.append(create_box(extents=[0.12, 0.005, 0.18], center=(-0.30, ct_h + 0.040, ct_z - 0.04), color=(185, 70, 48, 255)))
    meshes.append(create_cylinder(radius=0.07, height=0.04, sections=14, center=(0.25, ct_h + 0.038, ct_z + 0.02), axis=(0,1,0), color=(110, 135, 120, 255)))
    meshes.append(create_cylinder(radius=0.04, height=0.05, sections=12, center=(0.25, ct_h + 0.083, ct_z + 0.02), axis=(0,1,0), color=(60, 115, 75, 255)))

    # =========================================================================
    # 5. LUMINÁRIA DE CANTO (Abajur de chão com iluminação indireta quente)
    # Posição: X = -2.15, Z = -0.40
    # =========================================================================
    lamp_x, lamp_z = -2.15, -0.40
    lamp_base_col = (28, 28, 30, 255)
    lamp_pole_col = (185, 155, 95, 255) # Latão escovado
    lamp_shade_col = (255, 242, 210, 255) # Cúpula de linho translúcida
    lamp_bulb_col = (255, 225, 140, 255)  # Núcleo emissivo quente

    # Base circular pesada
    meshes.append(create_cylinder(radius=0.18, height=0.03, sections=16, center=(lamp_x, 0.015, lamp_z), axis=(0,1,0), color=lamp_base_col))
    # Haste vertical delgada
    meshes.append(create_cylinder(radius=0.014, height=1.70, sections=12, center=(lamp_x, 0.85, lamp_z), axis=(0,1,0), color=lamp_pole_col))
    # Cúpula cilíndrica elegante
    meshes.append(create_cylinder(radius=0.22, height=0.32, sections=18, center=(lamp_x, 1.58, lamp_z), axis=(0,1,0), color=lamp_shade_col))
    # Bulbo interior com emissão suave
    meshes.append(create_cylinder(radius=0.06, height=0.12, sections=12, center=(lamp_x, 1.58, lamp_z), axis=(0,1,0), color=lamp_bulb_col))

    # =========================================================================
    # 6. JANELA PANORÂMICA E CORTINAS (Parede Esquerda, X = -2.80)
    # =========================================================================
    win_frame_col = (30, 32, 36, 255)
    win_z_min, win_z_max = -2.30, 0.70
    win_y_min, win_y_max = 0.50, 2.50
    win_w = win_z_max - win_z_min
    win_h = win_y_max - win_y_min
    win_z_mid = (win_z_min + win_z_max) / 2.0
    win_y_mid = (win_y_min + win_y_max) / 2.0

    # Cenário exterior com céu crepuscular
    strip_h = win_h / 3.0
    sky_tones = [
        (40, 65, 115, 255),   # Azul escuro do crepúsculo
        (75, 88, 128, 255),   # Transição violeta
        (135, 105, 120, 255), # Brilho quente do horizonte
    ]
    for st in range(3):
        sy = win_y_min + (2 - st + 0.5) * strip_h
        meshes.append(create_box(extents=[0.02, strip_h, win_w], center=(-2.95, sy, win_z_mid), color=sky_tones[st]))

    # Silhueta de vegetação / copas de pinheiros distantes
    meshes.append(create_box(extents=[0.02, 0.25, win_w], center=(-2.93, win_y_min + 0.125, win_z_mid), color=(22, 28, 38, 255)))

    # Caixilho e montantes da janela
    wf_thick = 0.04
    meshes.append(create_box(extents=[0.14, wf_thick, win_w + 0.10], center=(-2.80, win_y_min, win_z_mid), color=win_frame_col))
    meshes.append(create_box(extents=[0.14, wf_thick, win_w + 0.10], center=(-2.80, win_y_max, win_z_mid), color=win_frame_col))
    meshes.append(create_box(extents=[0.14, win_h, wf_thick], center=(-2.80, win_y_mid, win_z_min), color=win_frame_col))
    meshes.append(create_box(extents=[0.14, win_h, wf_thick], center=(-2.80, win_y_mid, win_z_max), color=win_frame_col))
    # 2 montantes dividindo a janela em 3 amplos painéis de vidro
    meshes.append(create_box(extents=[0.10, win_h, 0.03], center=(-2.80, win_y_mid, win_z_min + win_w * 0.333), color=win_frame_col))
    meshes.append(create_box(extents=[0.10, win_h, 0.03], center=(-2.80, win_y_mid, win_z_min + win_w * 0.666), color=win_frame_col))

    # Cortinas de linho franzidas de teto ao chão em ambos os lados
    curtain_col = (235, 230, 222, 255)
    for pleat in range(5):
        pz = -2.55 + pleat * 0.05
        px = -2.72 + (pleat % 2) * 0.03
        meshes.append(create_rounded_box(extents=[0.06, 2.75, 0.05], radius=0.015, subdivisions=1, center=(px, 1.375, pz), color=curtain_col))
    for pleat in range(5):
        pz = 0.70 + pleat * 0.05
        px = -2.72 + (pleat % 2) * 0.03
        meshes.append(create_rounded_box(extents=[0.06, 2.75, 0.05], radius=0.015, subdivisions=1, center=(px, 1.375, pz), color=curtain_col))
    # Varão da cortina no teto
    meshes.append(create_cylinder(radius=0.015, height=win_w + 0.80, sections=12, center=(-2.72, 2.75, win_z_mid), axis=(0,0,1), color=lamp_base_col))

    # =========================================================================
    # 7. TRÍPTICO DE ARTE ABSTRATA MINIMALISTA (Parede Direita, X = +2.80)
    # =========================================================================
    art_frame_col = (165, 125, 85, 255) # Moldura fina em carvalho natural
    art_mat_col = (246, 244, 238, 255)   # Passepartout branco linho
    art_palette = [
        (195, 88, 58, 255),   # Terracota Bauhaus
        (95, 130, 118, 255),  # Verde Sálvia
        (42, 60, 85, 255),    # Azul Nórdico profundo
    ]
    art_z_centers = [-1.20, -0.20, 0.80]
    art_w, art_h, art_y = 0.65, 0.85, 1.60

    for idx, az in enumerate(art_z_centers):
        f_border = 0.03
        meshes.append(create_box(extents=[0.03, f_border, art_w + 2*f_border], center=(2.78, art_y + art_h/2 + f_border/2, az), color=art_frame_col))
        meshes.append(create_box(extents=[0.03, f_border, art_w + 2*f_border], center=(2.78, art_y - art_h/2 - f_border/2, az), color=art_frame_col))
        meshes.append(create_box(extents=[0.03, art_h, f_border], center=(2.78, art_y, az - art_w/2 - f_border/2), color=art_frame_col))
        meshes.append(create_box(extents=[0.03, art_h, f_border], center=(2.78, art_y, az + art_w/2 + f_border/2), color=art_frame_col))
        meshes.append(create_box(extents=[0.015, art_h, art_w], center=(2.79, art_y, az), color=art_mat_col))
        # Elemento artístico central
        meshes.append(create_box(extents=[0.01, art_h * 0.55, art_w * 0.55], center=(2.785, art_y - 0.05, az), color=art_palette[idx]))
        # Elemento secundário de contraste
        sec_c = art_palette[(idx + 1) % 3]
        meshes.append(create_box(extents=[0.012, art_h * 0.25, art_w * 0.35], center=(2.783, art_y + 0.15, az - 0.05), color=sec_c))

    # =========================================================================
    # 8. CONSOLE TRASEIRO E PLANTA ORNAMENTAL (Atrás do sofá, Z = +2.00)
    # =========================================================================
    # Aparador baixo
    meshes.append(create_box(extents=[2.40, 0.70, 0.22], center=(0.0, 0.35, 1.95), color=(50, 44, 38, 255)))
    # Friso arquitetônico na parede traseira
    meshes.append(create_box(extents=[5.60, 0.03, 0.02], center=(0.0, 1.10, 2.08), color=(205, 202, 195, 255)))
    # Vaso cilíndrico de cerâmica com planta (costela-de-adão / ficus)
    meshes.append(create_cylinder(radius=0.15, height=0.35, sections=14, center=(2.15, 0.175, 1.65), axis=(0,1,0), color=(220, 215, 205, 255)))
    for fy, fr, fx_off, fz_off in [(0.42, 0.14, 0, 0), (0.52, 0.18, 0.04, -0.03), (0.64, 0.15, -0.03, 0.02)]:
        meshes.append(create_cylinder(radius=fr, height=0.12, sections=10, center=(2.15 + fx_off, fy, 1.65 + fz_off), axis=(0,1,0), color=(48, 92, 58, 255)))

    # =========================================================================
    # 9. ILUMINAÇÃO EMBUTIDA NO TETO (Spotlights)
    # =========================================================================
    spot_col = (255, 245, 220, 255)
    for sp_x, sp_z in [(-1.20, -1.45), (1.20, -1.45), (0.0, -2.40), (1.50, 0.0)]:
        meshes.append(create_cylinder(radius=0.06, height=0.015, sections=12, center=(sp_x, 2.795, sp_z), axis=(0,1,0), color=spot_col))

    # =========================================================================
    # 10. UNIFICAÇÃO EM MALHA ÚNICA (1 DRAW CALL) E BAKING DE ILUMINAÇÃO
    # =========================================================================
    full_mesh = trimesh.util.concatenate(meshes)
    verts = full_mesh.vertices.copy()
    normals = full_mesh.vertex_normals.copy()
    colors = full_mesh.visual.vertex_colors.copy().astype(np.float32)[:, :3] / 255.0

    # Iluminação ambiente equilibrada
    ambient = 0.50

    # Luz direcional vinda da janela
    win_dir = np.array([0.7, 0.4, -0.3])
    win_dir = win_dir / np.linalg.norm(win_dir)
    win_diff = np.maximum(0.0, np.dot(normals, win_dir))
    win_light_col = np.array([0.85, 0.90, 1.0])

    # Luz pontual quente da luminária de chão em (-2.15, 1.60, -0.40)
    lamp_pt = np.array([-2.15, 1.60, -0.40])
    lamp_vec = lamp_pt - verts
    lamp_dist = np.linalg.norm(lamp_vec, axis=1, keepdims=True)
    lamp_dir = lamp_vec / np.maximum(1e-6, lamp_dist)
    lamp_diff = np.maximum(0.0, np.sum(normals * lamp_dir, axis=1, keepdims=True))
    lamp_atten = 1.0 / (1.0 + 0.9 * lamp_dist + 0.6 * (lamp_dist**2))
    lamp_light_col = np.array([1.0, 0.82, 0.50]) # Luz quente dourada 2700K

    # Iluminação suave de fundo da TV (Bias glow)
    tv_pt = np.array([0.0, 1.5, -2.85])
    tv_vec = tv_pt - verts
    tv_dist = np.linalg.norm(tv_vec, axis=1, keepdims=True)
    tv_dir = tv_vec / np.maximum(1e-6, tv_dist)
    tv_diff = np.maximum(0.0, np.sum(normals * tv_dir, axis=1, keepdims=True))
    tv_atten = 1.0 / (1.0 + 1.2 * tv_dist + 1.0 * (tv_dist**2))
    tv_light_col = np.array([0.90, 0.85, 0.75])

    # Contato de oclusão de ambiente (AO nos cantos e sob o sofá)
    ao = np.ones((len(verts), 1), dtype=np.float32)
    y_floor = np.clip(verts[:, 1:2] / 0.15, 0.0, 1.0)
    ao *= (0.75 + 0.25 * y_floor)
    sofa_mask = (np.abs(verts[:, 0:1]) < 1.35) & (np.abs(verts[:, 2:3] - 0.05) < 0.60) & (verts[:, 1:2] < 0.35)
    ao[sofa_mask] *= 0.70

    # Composição final da iluminação pré-calculada
    total_light = (
        ambient +
        win_diff[:, np.newaxis] * 0.25 * win_light_col +
        lamp_diff * lamp_atten * 0.60 * lamp_light_col +
        tv_diff * tv_atten * 0.30 * tv_light_col
    )
    total_light *= ao

    lit_colors = np.clip(colors * total_light, 0.0, 1.0)
    lit_colors_u8 = (lit_colors * 255.0).astype(np.uint8)
    alpha = np.full((len(verts), 1), 255, dtype=np.uint8)
    final_rgba = np.hstack([lit_colors_u8, alpha])

    full_mesh.visual.vertex_colors = final_rgba
    full_mesh.visual.material = trimesh.visual.material.PBRMaterial(name="LivingRoomPBR", roughnessFactor=0.85, metallicFactor=0.08)
    full_mesh.visual.uv = np.zeros((len(verts), 2), dtype=np.float32)

    return full_mesh

def main():
    output_path = os.path.abspath(
        os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'environments', 'living_room', 'model.glb')
    )
    os.makedirs(os.path.dirname(output_path), exist_ok=True)

    print(f"Gerando ambiente Sala de Estar em: {output_path}")
    mesh = build_living_room_mesh()

    print(f"Malha gerada:")
    print(f"  Vértices: {len(mesh.vertices)}")
    print(f"  Faces (Triângulos): {len(mesh.faces)}")

    glb_bytes = mesh.export(file_type='glb', include_normals=True)
    with open(output_path, 'wb') as f:
        f.write(glb_bytes)

    file_size = len(glb_bytes)
    print(f"Arquivo GLB gravado com sucesso!")
    print(f"  Tamanho: {file_size} bytes ({file_size / 1024.0:.2f} KB)")

    # Validação de integridade com trimesh
    print("\nValidando integridade com trimesh...")
    reloaded_scene = trimesh.load(output_path, file_type='glb')
    geom_count = len(reloaded_scene.geometry)
    print(f"  Geometrias no arquivo (Draw Calls): {geom_count}")
    geom = list(reloaded_scene.geometry.values())[0]
    print(f"  Vértices recarregados: {len(geom.vertices)}")
    print(f"  Faces recarregadas: {len(geom.faces)}")
    print(f"  Normais presentes: {geom.vertex_normals is not None and len(geom.vertex_normals) > 0}")

    # Validação do cabeçalho glTF interno
    json_len = int.from_bytes(glb_bytes[12:16], 'little')
    gltf_hdr = json.loads(glb_bytes[20:20+json_len].decode('utf-8'))
    attrs = gltf_hdr['meshes'][0]['primitives'][0]['attributes']
    print(f"  Atributos glTF 2.0: {attrs}")

    # Verificação de limites das especificações
    assert 3000 <= len(geom.faces) <= 10000, f"Contagem de faces fora do intervalo: {len(geom.faces)}"
    assert file_size < 250 * 1024, f"Tamanho excede 250 KB: {file_size} bytes"
    assert geom_count <= 3, f"Muitas draw calls: {geom_count}"
    assert 'POSITION' in attrs and 'NORMAL' in attrs and 'COLOR_0' in attrs and 'TEXCOORD_0' in attrs, "Atributos glTF incompletos"

    print("\n>>> SUCESSO: Todas as diretrizes e validações foram atendidas perfeitamente! <<<")

if __name__ == '__main__':
    main()
