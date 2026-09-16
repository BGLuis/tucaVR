#!/usr/bin/env python3
"""
Gerador de Ambiente 3D - Cinema IMAX (tucaVR)
Gera:
  - app/src/main/assets/environments/cinema/model.glb
  - app/src/main/assets/environments/cinema/ambient.ogg

Requisitos:
  - Fileiras de assentos de cinema estilizados (vermelho escuro com encostos e apoios de braço pretos/cinza)
  - Piso escalonado em degraus suaves descendo em direção à tela
  - Observador no ponto central (origem Y ~ 0 ou 1.2m correspondente aos olhos)
  - Paredes laterais com painéis acústicos estilizados (cinza-chumbo)
  - Moldura elegante ao redor da tela (screen_pos=0.0,2.2,-7.5 e screen_scale=6.0,3.375)
  - Luzes de rodapé nos degraus com cores quentes/âmbar em vertex colors
  - Otimização extrema: 1 draw call, 5.000 a 15.000 triângulos, tamanho < 300 KB
  - Áudio de ambiência sutil em loop contínuo (HVAC / ar condicionado suave)
"""

import os
import sys
import math
import subprocess
import wave
import numpy as np
import trimesh

# Diretórios base
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
OUTPUT_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "environments", "cinema")
MODEL_OUTPUT_PATH = os.path.join(OUTPUT_DIR, "model.glb")
AUDIO_OUTPUT_PATH = os.path.join(OUTPUT_DIR, "ambient.ogg")

os.makedirs(OUTPUT_DIR, exist_ok=True)

# Cores da Paleta (RGBA uint8)
COLOR_BURGUNDY_LIGHT = [165, 24, 36, 255]   # Destaque superior veludo vermelho
COLOR_BURGUNDY_MID   = [135, 18, 28, 255]   # Tom médio veludo
COLOR_BURGUNDY_DARK  = [105, 14, 22, 255]   # Tom escuro/sombra veludo
COLOR_SEAT_SHELL     = [32, 33, 36, 255]    # Concha traseira e acabamento polímero
COLOR_ARMREST        = [40, 42, 46, 255]    # Apoio de braço cinza escuro
COLOR_CUPHOLDER      = [18, 19, 22, 255]    # Porta-copos interno
COLOR_STEEL_LEG      = [24, 25, 28, 255]    # Pedestal de suporte metálico

COLOR_CARPET         = [26, 28, 34, 255]    # Carpete grafite escuro
COLOR_STEP_RISER     = [22, 23, 27, 255]    # Espelho do degrau
COLOR_STEP_LIGHT_ON  = [255, 195, 55, 255]  # Lente emissiva âmbar quente
COLOR_STEP_LIGHT_SPILL = [175, 115, 28, 255]# Brilho no carpete ao redor da luz
COLOR_STEP_FIXTURE   = [28, 28, 30, 255]    # Moldura da luminária de rodapé

COLOR_WALL_BASE      = [30, 32, 38, 255]    # Parede base cinza-chumbo
COLOR_PANEL_FRONT    = [42, 45, 52, 255]    # Painel acústico frontal
COLOR_PANEL_BEVEL    = [34, 37, 43, 255]    # Bisel do painel acústico
COLOR_PANEL_REVEAL   = [22, 24, 28, 255]    # Friso entre painéis
COLOR_REVEAL_GLOW    = [80, 58, 24, 255]    # Brilho sutil arquitetônico âmbar

COLOR_SCREEN_FRAME   = [28, 30, 35, 255]    # Moldura da tela titânio escuro
COLOR_FRAME_HIGHLIGHT= [48, 52, 60, 255]    # Chanfro da moldura da tela
COLOR_SCREEN_BACKDROP= [12, 13, 16, 255]    # Fundo do proscênio (preto absorvente)
COLOR_STAGE_SKIRT    = [22, 24, 28, 255]    # Saia do palco abaixo da tela

COLOR_CEILING        = [18, 20, 24, 255]    # Teto escuro
COLOR_CEILING_BAFFLE = [15, 16, 19, 255]    # Baffles acústicos do teto
COLOR_BOOTH_GLASS    = [20, 24, 30, 255]    # Vidro da cabine de projeção


# ==============================================================================
# Funções Geradoras de Geometria Básica
# ==============================================================================

def make_box(min_pt, max_pt, base_color, top_color=None, bot_color=None):
    """Gera um paralelepípedo com normais externas e vertex colors."""
    x0, y0, z0 = min_pt
    x1, y1, z1 = max_pt

    v = np.array([
        [x0, y0, z0],  # 0
        [x1, y0, z0],  # 1
        [x1, y1, z0],  # 2
        [x0, y1, z0],  # 3
        [x0, y0, z1],  # 4
        [x1, y0, z1],  # 5
        [x1, y1, z1],  # 6
        [x0, y1, z1],  # 7
    ], dtype=np.float32)

    f = np.array([
        [0, 2, 1], [0, 3, 2],  # Front (z0)
        [4, 5, 6], [4, 6, 7],  # Back (z1)
        [0, 1, 5], [0, 5, 4],  # Bottom (y0)
        [3, 6, 2], [3, 7, 6],  # Top (y1)
        [0, 4, 7], [0, 7, 3],  # Left (x0)
        [1, 2, 6], [1, 6, 5],  # Right (x1)
    ], dtype=np.int32)

    colors = np.full((8, 4), base_color, dtype=np.uint8)
    if top_color is not None:
        colors[[2, 3, 6, 7]] = top_color
    if bot_color is not None:
        colors[[0, 1, 4, 5]] = bot_color

    m = trimesh.Trimesh(vertices=v, faces=f)
    m.visual = trimesh.visual.ColorVisuals(mesh=m, vertex_colors=colors)
    return m


def make_quad(p0, p1, p2, p3, c0, c1=None, c2=None, c3=None):
    """Gera um quadrilátero (2 triângulos) com cores por vértice."""
    v = np.array([p0, p1, p2, p3], dtype=np.float32)
    f = np.array([[0, 1, 2], [0, 2, 3]], dtype=np.int32)

    if c1 is None: c1 = c0
    if c2 is None: c2 = c0
    if c3 is None: c3 = c0

    colors = np.array([c0, c1, c2, c3], dtype=np.uint8)
    m = trimesh.Trimesh(vertices=v, faces=f)
    m.visual = trimesh.visual.ColorVisuals(mesh=m, vertex_colors=colors)
    return m


# ==============================================================================
# Modelagem das Poltronas de Cinema
# ==============================================================================

def create_beveled_cushion(cx, cz, floor_y, width=0.52, depth=0.46, y_bot=0.36, y_top=0.45, bevel=0.04):
    """Assento estilizado com chanfro ergonômico frontal e degradê de veludo."""
    hx = width * 0.5
    z_back = cz + depth * 0.5
    z_front = cz - depth * 0.5
    y0 = floor_y + y_bot
    y1 = floor_y + y_top

    # Vértices do assento com chanfro frontal
    v = np.array([
        [-hx + cx, y0, z_front],             # 0: frente inferior esq
        [ hx + cx, y0, z_front],             # 1: frente inferior dir
        [ hx + cx, y1 - bevel, z_front],     # 2: chanfro frente dir
        [-hx + cx, y1 - bevel, z_front],     # 3: chanfro frente esq
        [-hx + cx, y1, z_front + bevel],     # 4: topo frente esq
        [ hx + cx, y1, z_front + bevel],     # 5: topo frente dir
        [ hx + cx, y1, z_back],              # 6: topo trás dir
        [-hx + cx, y1, z_back],              # 7: topo trás esq
        [-hx + cx, y0, z_back],              # 8: trás inferior esq
        [ hx + cx, y0, z_back],              # 9: trás inferior dir
    ], dtype=np.float32)

    f = np.array([
        # Frente vertical inferior
        [0, 2, 1], [0, 3, 2],
        # Chanfro frontal
        [3, 5, 2], [3, 4, 5],
        # Topo
        [4, 6, 5], [4, 7, 6],
        # Traseira
        [8, 9, 6], [8, 6, 7],
        # Fundo
        [0, 1, 9], [0, 9, 8],
        # Lateral esquerda
        [0, 8, 7], [0, 7, 4], [0, 4, 3],
        # Lateral direita
        [1, 2, 5], [1, 5, 6], [1, 6, 9],
    ], dtype=np.int32)

    colors = np.empty((10, 4), dtype=np.uint8)
    colors[[0, 1, 8, 9]] = COLOR_BURGUNDY_DARK   # Fundo escuro
    colors[[2, 3]]       = COLOR_BURGUNDY_MID    # Chanfro médio
    colors[[4, 5, 6, 7]] = COLOR_BURGUNDY_LIGHT  # Topo destacado

    m = trimesh.Trimesh(vertices=v, faces=f)
    m.visual = trimesh.visual.ColorVisuals(mesh=m, vertex_colors=colors)
    return m


def create_backrest(cx, cz, floor_y, width=0.52, height=0.62, thickness=0.08, tilt=0.08):
    """Encosto anatômico com apoio de cabeça, inclinação reclinada e concha plástica traseira."""
    hx = width * 0.5
    y0 = floor_y + 0.43
    y1 = floor_y + 0.43 + height
    y_lumbar = y0 + height * 0.45

    # Almofada dianteira (vermelho escuro)
    z0 = cz + 0.18
    z1 = z0 + tilt

    v_cushion = np.array([
        [-hx + cx, y0, z0],                     # 0
        [ hx + cx, y0, z0],                     # 1
        [ hx + cx, y_lumbar, z0 + tilt * 0.4],  # 2
        [-hx + cx, y_lumbar, z0 + tilt * 0.4],  # 3
        [-hx + cx, y1, z1],                     # 4
        [ hx + cx, y1, z1],                     # 5
        # Verso almofada
        [-hx + cx, y0, z0 + thickness],         # 6
        [ hx + cx, y0, z0 + thickness],         # 7
        [ hx + cx, y1, z1 + thickness],         # 8
        [-hx + cx, y1, z1 + thickness],         # 9
    ], dtype=np.float32)

    f_cushion = np.array([
        # Face frontal inferior
        [0, 2, 1], [0, 3, 2],
        # Face frontal superior (apoio de cabeça)
        [3, 5, 2], [3, 4, 5],
        # Topo
        [4, 8, 5], [4, 9, 8],
        # Fundo
        [0, 1, 7], [0, 7, 6],
        # Lateral esq
        [0, 6, 9], [0, 9, 4], [0, 4, 3],
        # Lateral dir
        [1, 2, 5], [1, 5, 8], [1, 8, 7],
    ], dtype=np.int32)

    colors_c = np.empty((10, 4), dtype=np.uint8)
    colors_c[[0, 1, 6, 7]] = COLOR_BURGUNDY_DARK
    colors_c[[2, 3]]       = COLOR_BURGUNDY_MID
    colors_c[[4, 5, 8, 9]] = COLOR_BURGUNDY_LIGHT

    m_cushion = trimesh.Trimesh(vertices=v_cushion, faces=f_cushion)
    m_cushion.visual = trimesh.visual.ColorVisuals(mesh=m_cushion, vertex_colors=colors_c)

    # Concha traseira plástica protetora (preto/cinza escuro)
    z_shell_0 = z0 + thickness * 0.95
    z_shell_1 = z1 + thickness * 0.95
    shell_t = 0.035

    v_shell = np.array([
        [-hx + cx, y0, z_shell_0],
        [ hx + cx, y0, z_shell_0],
        [ hx + cx, y1, z_shell_1],
        [-hx + cx, y1, z_shell_1],
        [-hx + cx, y0, z_shell_0 + shell_t],
        [ hx + cx, y0, z_shell_0 + shell_t],
        [ hx + cx, y1, z_shell_1 + shell_t],
        [-hx + cx, y1, z_shell_1 + shell_t],
    ], dtype=np.float32)

    f_shell = np.array([
        [4, 5, 6], [4, 6, 7],  # Face traseira
        [3, 6, 2], [3, 7, 6],  # Topo
        [0, 1, 5], [0, 5, 4],  # Fundo
        [0, 4, 7], [0, 7, 3],  # Lateral esq
        [1, 2, 6], [1, 6, 5],  # Lateral dir
    ], dtype=np.int32)

    colors_s = np.full((8, 4), COLOR_SEAT_SHELL, dtype=np.uint8)
    m_shell = trimesh.Trimesh(vertices=v_shell, faces=f_shell)
    m_shell.visual = trimesh.visual.ColorVisuals(mesh=m_shell, vertex_colors=colors_s)

    return trimesh.util.concatenate([m_cushion, m_shell])


def create_armrest(cx, cz, floor_y, width=0.075, length=0.44, arm_y_bot=0.56, arm_y_top=0.64):
    """Apoio de braço em polímero cinza com porta-copos embutido na ponta frontal."""
    hx = width * 0.5
    z0 = cz - length * 0.5
    z1 = cz + length * 0.5
    y0 = floor_y + arm_y_bot
    y1 = floor_y + arm_y_top

    # Corpo principal do braço
    m_body = make_box([cx - hx, y0, z0], [cx + hx, y1, z1], COLOR_ARMREST)

    # Porta-copos na extremidade dianteira
    cup_r = min(hx * 0.85, 0.032)
    cup_cz = z0 + cup_r + 0.015
    cup_y = y1 + 0.001

    # Pequena cavidade hexagonal do porta-copos
    angles = np.linspace(0, 2 * np.pi, 7)[:-1]
    v_cup = [
        [cx, cup_y - 0.02, cup_cz]  # 0: fundo centro
    ]
    for a in angles:
        v_cup.append([cx + cup_r * np.cos(a), cup_y, cup_cz + cup_r * np.sin(a)])

    f_cup = []
    for i in range(1, 7):
        next_i = 1 if i == 6 else i + 1
        f_cup.append([0, next_i, i])

    v_cup = np.array(v_cup, dtype=np.float32)
    f_cup = np.array(f_cup, dtype=np.int32)
    colors_cup = np.full((len(v_cup), 4), COLOR_CUPHOLDER, dtype=np.uint8)
    m_cup = trimesh.Trimesh(vertices=v_cup, faces=f_cup)
    m_cup.visual = trimesh.visual.ColorVisuals(mesh=m_cup, vertex_colors=colors_cup)

    # Suporte vertical do braço até a base
    m_bracket = make_box([cx - 0.025, floor_y + 0.35, cz + 0.05], [cx + 0.025, y0, cz + 0.12], COLOR_STEEL_LEG)

    return trimesh.util.concatenate([m_body, m_cup, m_bracket])


def create_stanchion(cx, cz, floor_y):
    """Coluna central de ancoragem no piso com base flangeada."""
    y0 = floor_y
    y1 = floor_y + 0.36
    m_post = make_box([cx - 0.04, y0, cz - 0.04], [cx + 0.04, y1, cz + 0.04], COLOR_STEEL_LEG)
    m_base = make_box([cx - 0.08, y0, cz - 0.08], [cx + 0.08, y0 + 0.015, cz + 0.08], [18, 19, 21, 255])
    return trimesh.util.concatenate([m_post, m_base])


# ==============================================================================
# Modelagem dos Degraus com Luzes de Rodapé (Aisle Steps & Amber Lights)
# ==============================================================================

def create_step_with_light(x_min, x_max, z_riser, z_tread_end, y_lower, y_higher):
    """
    Cria um degrau de escada no corredor com espelho vertical, piso horizontal e
    luminária de rodapé âmbar quente incrustada no espelho com reflexo nos vértices.
    """
    meshes = []
    cx = (x_min + x_max) * 0.5

    # Espelho vertical do degrau (Riser)
    p0 = [x_max, y_lower, z_riser]
    p1 = [x_min, y_lower, z_riser]
    p2 = [x_min, y_higher, z_riser]
    p3 = [x_max, y_higher, z_riser]
    meshes.append(make_quad(p0, p1, p2, p3, COLOR_STEP_RISER))

    # Piso horizontal do degrau (Tread) com degradê do brilho âmbar
    p_t0 = [x_min, y_higher, z_tread_end]
    p_t1 = [x_max, y_higher, z_tread_end]
    p_t2 = [x_max, y_higher, z_riser]
    p_t3 = [x_min, y_higher, z_riser]

    # Vértices próximos ao espelho recebem reflexo da luz
    meshes.append(make_quad(p_t0, p_t1, p_t2, p_t3, COLOR_CARPET, COLOR_CARPET, COLOR_STEP_LIGHT_SPILL, COLOR_STEP_LIGHT_SPILL))

    # Luminária embutida no centro do espelho
    light_w = 0.26
    light_h = 0.035
    light_y0 = y_lower + (y_higher - y_lower) * 0.4
    light_y1 = light_y0 + light_h

    # Moldura metálica protetora
    meshes.append(make_box(
        [cx - light_w * 0.55, light_y0 - 0.008, z_riser - 0.015],
        [cx + light_w * 0.55, light_y1 + 0.008, z_riser],
        COLOR_STEP_FIXTURE
    ))

    # Lente emissiva âmbar brilhante
    p_l0 = [cx + light_w * 0.5, light_y0, z_riser - 0.016]
    p_l1 = [cx - light_w * 0.5, light_y0, z_riser - 0.016]
    p_l2 = [cx - light_w * 0.5, light_y1, z_riser - 0.016]
    p_l3 = [cx + light_w * 0.5, light_y1, z_riser - 0.016]
    meshes.append(make_quad(p_l0, p_l1, p_l2, p_l3, COLOR_STEP_LIGHT_ON))

    return trimesh.util.concatenate(meshes)


# ==============================================================================
# Modelagem das Paredes Laterais e Painéis Acústicos Estilizados
# ==============================================================================

def create_acoustic_panel(x_pos, cz, y_base, y_top=4.4, width=1.15, thickness=0.08, is_left=True):
    """
    Painel acústico com chanfros geométricos facetados e fenda de luz LED arquitetônica.
    """
    meshes = []
    hz = width * 0.5
    bevel = 0.07

    # Direção de projeção do painel (para dentro da sala)
    x_inner = x_pos + (thickness if is_left else -thickness)
    x_front_bevel = x_pos + (thickness * 0.9 if is_left else -thickness * 0.9)

    # Face frontal facetada do painel
    z0 = cz - hz
    z1 = cz + hz

    v_panel = np.array([
        # Base
        [x_pos, y_base, z0],
        [x_pos, y_base, z1],
        [x_pos, y_top, z1],
        [x_pos, y_top, z0],
        # Face frontal com chanfro
        [x_inner, y_base + bevel, z0 + bevel],
        [x_inner, y_base + bevel, z1 - bevel],
        [x_inner, y_top - bevel, z1 - bevel],
        [x_inner, y_top - bevel, z0 + bevel],
    ], dtype=np.float32)

    if is_left:
        # Parede esquerda (normal apontando para +X)
        f_panel = np.array([
            [4, 5, 6], [4, 6, 7],  # Face frontal
            [0, 4, 7], [0, 7, 3],  # Bisel frontal (z0)
            [1, 2, 6], [1, 6, 5],  # Bisel traseiro (z1)
            [3, 7, 6], [3, 6, 2],  # Bisel superior
            [0, 1, 5], [0, 5, 4],  # Bisel inferior
        ], dtype=np.int32)
    else:
        # Parede direita (normal apontando para -X)
        f_panel = np.array([
            [4, 6, 5], [4, 7, 6],  # Face frontal
            [0, 7, 4], [0, 3, 7],  # Bisel frontal
            [1, 5, 6], [1, 6, 2],  # Bisel traseiro
            [3, 6, 7], [3, 2, 6],  # Bisel superior
            [0, 4, 5], [0, 5, 1],  # Bisel inferior
        ], dtype=np.int32)

    colors = np.empty((8, 4), dtype=np.uint8)
    colors[:4] = COLOR_PANEL_BEVEL
    colors[4:] = COLOR_PANEL_FRONT

    m_p = trimesh.Trimesh(vertices=v_panel, faces=f_panel)
    m_p.visual = trimesh.visual.ColorVisuals(mesh=m_p, vertex_colors=colors)
    meshes.append(m_p)

    # Luz vertical sutil âmbar no canal lateral do painel
    reveal_z = z1 + 0.15
    reveal_w = 0.08
    light_x = x_pos + (0.02 if is_left else -0.02)
    if is_left:
        p0 = [light_x, y_base + 0.2, reveal_z + reveal_w]
        p1 = [light_x, y_base + 0.2, reveal_z - reveal_w]
        p2 = [light_x, y_top - 0.2, reveal_z - reveal_w]
        p3 = [light_x, y_top - 0.2, reveal_z + reveal_w]
    else:
        p0 = [light_x, y_base + 0.2, reveal_z - reveal_w]
        p1 = [light_x, y_base + 0.2, reveal_z + reveal_w]
        p2 = [light_x, y_top - 0.2, reveal_z + reveal_w]
        p3 = [light_x, y_top - 0.2, reveal_z - reveal_w]

    meshes.append(make_quad(p0, p1, p2, p3, COLOR_REVEAL_GLOW))

    return trimesh.util.concatenate(meshes)


# ==============================================================================
# Modelagem da Moldura da Tela e Proscênio
# ==============================================================================

def create_screen_frame_and_stage():
    """
    Gera a moldura elegante ao redor da tela (screen_pos=0.0,2.2,-7.5 e screen_scale=6.0,3.375),
    com cavidade escura de absorção de luz e saia acústica drapeada abaixo da tela.
    """
    meshes = []

    # Dimensões da tela em config.ini
    screen_cx = 0.0
    screen_cy = 2.2
    screen_cz = -7.50
    screen_w = 6.00
    screen_h = 3.375

    x_inner_l = screen_cx - screen_w * 0.5   # -3.00
    x_inner_r = screen_cx + screen_w * 0.5   # +3.00
    y_inner_b = screen_cy - screen_h * 0.5   # 0.5125
    y_inner_t = screen_cy + screen_h * 0.5   # 3.8875

    # Bordas externas da moldura elegante
    frame_bevel = 0.20
    x_outer_l = x_inner_l - frame_bevel      # -3.20
    x_outer_r = x_inner_r + frame_bevel      # +3.20
    y_outer_b = y_inner_b - frame_bevel      # 0.3125
    y_outer_t = y_inner_t + frame_bevel      # 4.0875

    z_front = screen_cz - 0.02
    z_back = screen_cz - 0.06

    # 1. Barra superior da moldura
    meshes.append(make_box([x_outer_l, y_inner_t, z_back], [x_outer_r, y_outer_t, z_front], COLOR_SCREEN_FRAME, COLOR_FRAME_HIGHLIGHT))

    # 2. Barra inferior da moldura
    meshes.append(make_box([x_outer_l, y_outer_b, z_back], [x_outer_r, y_inner_b, z_front], COLOR_SCREEN_FRAME, COLOR_FRAME_HIGHLIGHT))

    # 3. Coluna esquerda da moldura
    meshes.append(make_box([x_outer_l, y_inner_b, z_back], [x_inner_l, y_inner_t, z_front], COLOR_SCREEN_FRAME, COLOR_FRAME_HIGHLIGHT))

    # 4. Coluna direita da moldura
    meshes.append(make_box([x_inner_r, y_inner_b, z_back], [x_outer_r, y_inner_t, z_front], COLOR_SCREEN_FRAME, COLOR_FRAME_HIGHLIGHT))

    # 5. Parede de fundo do proscênio (veludo absorvente preto atrás da tela)
    p0 = [-5.0, -0.99, screen_cz - 0.25]
    p1 = [ 5.0, -0.99, screen_cz - 0.25]
    p2 = [ 5.0,  5.00, screen_cz - 0.25]
    p3 = [-5.0,  5.00, screen_cz - 0.25]
    meshes.append(make_quad(p0, p1, p2, p3, COLOR_SCREEN_BACKDROP))

    # 6. Saia drapeada / cortina acústica abaixo da moldura até o chão
    skirt_y0 = -0.99
    skirt_y1 = y_outer_b
    skirt_z = screen_cz - 0.04

    # Drapeado com 10 ondulações sutis
    x_splits = np.linspace(-4.5, 4.5, 11)
    for i in range(len(x_splits) - 1):
        xl = x_splits[i]
        xr = x_splits[i + 1]
        z_offset = 0.02 if (i % 2 == 1) else 0.0
        p_s0 = [xl, skirt_y0, skirt_z + z_offset]
        p_s1 = [xr, skirt_y0, skirt_z + z_offset]
        p_s2 = [xr, skirt_y1, skirt_z + z_offset]
        p_s3 = [xl, skirt_y1, skirt_z + z_offset]
        col = COLOR_STAGE_SKIRT if (i % 2 == 0) else [28, 30, 35, 255]
        meshes.append(make_quad(p_s0, p_s1, p_s2, p_s3, col))

    return trimesh.util.concatenate(meshes)


# ==============================================================================
# Construção Completa do Cinema
# ==============================================================================

def build_cinema_model():
    """Monta todas as geometrias do Cinema IMAX e combina em 1 único nó otimizado."""
    all_meshes = []

    # Definição dos patamares (6 fileiras)
    # Observer na Row 3 (Z = 0.0, Tier Floor Y = 0.0m, Nível dos olhos = ~1.15m)
    row_z = [-3.20, -1.60, 0.00, 1.60, 3.20, 4.80]
    tier_y = [-0.66, -0.33, 0.00, 0.33, 0.66, 0.99]
    tier_z_bounds = [
        [-4.00, -2.40],  # Tier 1
        [-2.40, -0.80],  # Tier 2
        [-0.80,  0.80],  # Tier 3 (Observador)
        [ 0.80,  2.40],  # Tier 4
        [ 2.40,  4.00],  # Tier 5
        [ 4.00,  5.60],  # Tier 6
    ]

    print(">>> 1/5: Gerando fileiras de poltronas de cinema...")
    # Configuração dos blocos de poltronas
    center_seat_x = [-1.80, -1.20, -0.60, 0.00, 0.60, 1.20, 1.80]
    center_arm_x  = [-2.10, -1.50, -0.90, -0.30, 0.30, 0.90, 1.50, 2.10]

    left_seat_x   = [-3.80, -3.20]
    left_arm_x    = [-4.10, -3.50, -2.90]

    right_seat_x  = [3.20, 3.80]
    right_arm_x   = [2.90, 3.50, 4.10]

    for r_idx in range(6):
        cz = row_z[r_idx]
        fy = tier_y[r_idx]

        # Assentos e suportes do bloco central
        for sx in center_seat_x:
            all_meshes.append(create_beveled_cushion(sx, cz, fy))
            all_meshes.append(create_backrest(sx, cz, fy))
            all_meshes.append(create_stanchion(sx, cz, fy))
        for ax in center_arm_x:
            all_meshes.append(create_armrest(ax, cz, fy))

        # Bloco esquerdo
        for sx in left_seat_x:
            all_meshes.append(create_beveled_cushion(sx, cz, fy))
            all_meshes.append(create_backrest(sx, cz, fy))
            all_meshes.append(create_stanchion(sx, cz, fy))
        for ax in left_arm_x:
            all_meshes.append(create_armrest(ax, cz, fy))

        # Bloco direito
        for sx in right_seat_x:
            all_meshes.append(create_beveled_cushion(sx, cz, fy))
            all_meshes.append(create_backrest(sx, cz, fy))
            all_meshes.append(create_stanchion(sx, cz, fy))
        for ax in right_arm_x:
            all_meshes.append(create_armrest(ax, cz, fy))

    print(f"    Subtotal após 66 poltronas: {sum(len(m.faces) for m in all_meshes)} faces")

    print(">>> 2/5: Gerando piso escalonado, degraus e luzes de rodapé âmbar...")
    # Palco frontal abaixo do telão
    p0 = [-5.0, -0.99, -4.00]
    p1 = [ 5.0, -0.99, -4.00]
    p2 = [ 5.0, -0.99, -7.75]
    p3 = [-5.0, -0.99, -7.75]
    all_meshes.append(make_quad(p0, p1, p2, p3, COLOR_CARPET))

    # Passarela traseira (atrás da última fileira)
    p0 = [-5.0, 0.99, 6.80]
    p1 = [ 5.0, 0.99, 6.80]
    p2 = [ 5.0, 0.99, 5.60]
    p3 = [-5.0, 0.99, 5.60]
    all_meshes.append(make_quad(p0, p1, p2, p3, COLOR_CARPET))

    # Plataformas horizontais dos patamares (áreas de assento)
    aisle_l_min, aisle_l_max = -2.85, -2.15
    aisle_r_min, aisle_r_max =  2.15,  2.85

    for i in range(6):
        z_start, z_end = tier_z_bounds[i]
        fy = tier_y[i]

        # Bloco central
        p0 = [-2.15, fy, z_end]
        p1 = [ 2.15, fy, z_end]
        p2 = [ 2.15, fy, z_start]
        p3 = [-2.15, fy, z_start]
        all_meshes.append(make_quad(p0, p1, p2, p3, COLOR_CARPET))

        # Bloco esquerdo
        p0 = [-5.00, fy, z_end]
        p1 = [aisle_l_min, fy, z_end]
        p2 = [aisle_l_min, fy, z_start]
        p3 = [-5.00, fy, z_start]
        all_meshes.append(make_quad(p0, p1, p2, p3, COLOR_CARPET))

        # Bloco direito
        p0 = [aisle_r_max, fy, z_end]
        p1 = [ 5.00, fy, z_end]
        p2 = [ 5.00, fy, z_start]
        p3 = [aisle_r_max, fy, z_start]
        all_meshes.append(make_quad(p0, p1, p2, p3, COLOR_CARPET))

        # Espelhos das fileiras de assentos (risers verticais)
        prev_y = -0.99 if i == 0 else tier_y[i - 1]
        # Central
        p0 = [ 2.15, prev_y, z_start]
        p1 = [-2.15, prev_y, z_start]
        p2 = [-2.15, fy, z_start]
        p3 = [ 2.15, fy, z_start]
        all_meshes.append(make_quad(p0, p1, p2, p3, COLOR_STEP_RISER))
        # Esquerdo
        p0 = [aisle_l_min, prev_y, z_start]
        p1 = [-5.00, prev_y, z_start]
        p2 = [-5.00, fy, z_start]
        p3 = [aisle_l_min, fy, z_start]
        all_meshes.append(make_quad(p0, p1, p2, p3, COLOR_STEP_RISER))
        # Direito
        p0 = [ 5.00, prev_y, z_start]
        p1 = [aisle_r_max, prev_y, z_start]
        p2 = [aisle_r_max, fy, z_start]
        p3 = [ 5.00, fy, z_start]
        all_meshes.append(make_quad(p0, p1, p2, p3, COLOR_STEP_RISER))

    # Escadas dos corredores com luzes de rodapé âmbar
    for i in range(6):
        z_b = tier_z_bounds[i][0]
        y_prev = -0.99 if i == 0 else tier_y[i - 1]
        y_next = tier_y[i]
        y_mid = (y_prev + y_next) * 0.5
        z_step1 = z_b - 0.20
        z_step2 = z_b + 0.20

        # Corredor Esquerdo
        all_meshes.append(create_step_with_light(aisle_l_min, aisle_l_max, z_step1, z_step2, y_prev, y_mid))
        all_meshes.append(create_step_with_light(aisle_l_min, aisle_l_max, z_step2, tier_z_bounds[i][1], y_mid, y_next))

        # Corredor Direito
        all_meshes.append(create_step_with_light(aisle_r_min, aisle_r_max, z_step1, z_step2, y_prev, y_mid))
        all_meshes.append(create_step_with_light(aisle_r_min, aisle_r_max, z_step2, tier_z_bounds[i][1], y_mid, y_next))

    print(">>> 3/5: Gerando paredes laterais e painéis acústicos...")
    # Parede esquerda base
    p0 = [-5.0, -0.99,  6.80]
    p1 = [-5.0, -0.99, -7.75]
    p2 = [-5.0,  5.00, -7.75]
    p3 = [-5.0,  5.00,  6.80]
    all_meshes.append(make_quad(p0, p1, p2, p3, COLOR_WALL_BASE))

    # Parede direita base
    p0 = [ 5.0, -0.99, -7.75]
    p1 = [ 5.0, -0.99,  6.80]
    p2 = [ 5.0,  5.00,  6.80]
    p3 = [ 5.0,  5.00, -7.75]
    all_meshes.append(make_quad(p0, p1, p2, p3, COLOR_WALL_BASE))

    # 8 Painéis acústicos em cada parede lateral
    panel_z_centers = [-6.20, -4.60, -3.00, -1.40, 0.20, 1.80, 3.40, 5.00]
    for cz in panel_z_centers:
        all_meshes.append(create_acoustic_panel(-5.0, cz, -0.5, 4.4, width=1.10, thickness=0.08, is_left=True))
        all_meshes.append(create_acoustic_panel( 5.0, cz, -0.5, 4.4, width=1.10, thickness=0.08, is_left=False))

    print(">>> 4/5: Gerando moldura elegante da tela, teto acústico e parede traseira...")
    # Moldura da tela e palco
    all_meshes.append(create_screen_frame_and_stage())

    # Teto
    p0 = [-5.0, 5.0, -7.75]
    p1 = [ 5.0, 5.0, -7.75]
    p2 = [ 5.0, 5.0,  6.80]
    p3 = [-5.0, 5.0,  6.80]
    all_meshes.append(make_quad(p0, p1, p2, p3, COLOR_CEILING))

    # Baffles acústicos suspensos no teto
    baffle_z = [-6.0, -4.5, -3.0, -1.5, 0.0, 1.5, 3.0, 4.5]
    for bz in baffle_z:
        all_meshes.append(make_box([-4.8, 4.75, bz - 0.06], [4.8, 5.00, bz + 0.06], COLOR_CEILING_BAFFLE))

    # Parede traseira com cabine de projeção
    p0 = [ 5.0, 0.99, 6.80]
    p1 = [-5.0, 0.99, 6.80]
    p2 = [-5.0, 5.00, 6.80]
    p3 = [ 5.0, 5.00, 6.80]
    all_meshes.append(make_quad(p0, p1, p2, p3, COLOR_WALL_BASE))

    # Moldura e janela da cabine de projeção
    all_meshes.append(make_box([-0.8, 2.7, 6.75], [0.8, 3.5, 6.80], [42, 45, 52, 255]))
    p0 = [ 0.7, 2.8, 6.76]
    p1 = [-0.7, 2.8, 6.76]
    p2 = [-0.7, 3.4, 6.76]
    p3 = [ 0.7, 3.4, 6.76]
    all_meshes.append(make_quad(p0, p1, p2, p3, COLOR_BOOTH_GLASS))

    print(">>> 5/5: Combinando malha e exportando GLB...")
    combined = trimesh.util.concatenate(all_meshes)

    # Garante cálculo de normais por vértice para shading perfeito
    combined.vertex_normals

    print(f"    Total de Vértices: {len(combined.vertices)}")
    print(f"    Total de Triângulos: {len(combined.faces)}")

    glb_bytes = combined.export(file_type="glb", include_normals=True)
    with open(MODEL_OUTPUT_PATH, "wb") as f:
        f.write(glb_bytes)

    print(f"    Arquivo GLB salvo em: {MODEL_OUTPUT_PATH}")
    print(f"    Tamanho do GLB: {len(glb_bytes)} bytes ({len(glb_bytes) / 1024:.1f} KB)")

    return combined, len(glb_bytes)


# ==============================================================================
# Geração do Áudio de Ambiência (HVAC / Ar Condicionado Suave)
# ==============================================================================

def generate_ambient_audio():
    """
    Gera um áudio de ambiência sutil de sala silenciosa com ar condicionado suave.
    Loop contínuo sem transição perceptível (crossfade perfeito).
    """
    print("\n>>> Gerando áudio de ambiência sutil (ambient.ogg)...")
    sample_rate = 44100
    duration = 8.0  # segundos
    n_samples = int(sample_rate * duration)

    np.random.seed(42)
    t = np.linspace(0, duration, n_samples, endpoint=False)

    # 1. Zumbido profundo de baixa frequência do HVAC (45Hz, 90Hz, 120Hz)
    rumble = (
        0.035 * np.sin(2 * np.pi * 48 * t) +
        0.020 * np.sin(2 * np.pi * 92 * t) +
        0.012 * np.sin(2 * np.pi * 125 * t)
    )

    # 2. Ruído de ar filtrado (som de difusor de ar condicionado suave)
    noise = np.random.normal(0, 1, n_samples)
    noise_fft = np.fft.rfft(noise)
    freqs = np.fft.rfftfreq(n_samples, 1.0 / sample_rate)

    # Filtro acústico: passagem entre 40Hz e 1800Hz com roll-off suave
    filt = np.zeros_like(freqs)
    mask_low = (freqs >= 40) & (freqs <= 250)
    mask_mid = (freqs > 250) & (freqs <= 900)
    mask_high = (freqs > 900) & (freqs <= 2500)

    filt[mask_low] = 1.0
    filt[mask_mid] = np.exp(-(freqs[mask_mid] - 250) / 200)
    filt[mask_high] = 0.04 * np.exp(-(freqs[mask_high] - 900) / 600)

    filtered_air = np.fft.irfft(noise_fft * filt, n=n_samples)
    filtered_air = (filtered_air / (np.max(np.abs(filtered_air)) + 1e-7)) * 0.05

    signal = rumble + filtered_air

    # 3. Crossfade perfeito nos limites (0.5s) para loop sem cliques
    fade_samples = int(sample_rate * 0.5)
    fade_in = np.linspace(0.0, 1.0, fade_samples)
    fade_out = 1.0 - fade_in

    signal[:fade_samples] = signal[:fade_samples] * fade_in + signal[-fade_samples:] * fade_out
    signal[-fade_samples:] = signal[:fade_samples]

    # Normalização sutil (-22 dB FS para ambiência discreta)
    signal = signal / (np.max(np.abs(signal)) + 1e-7) * 0.09

    # Estéreo decorrelacionado sutil para expansão espacial
    left = signal
    right = np.roll(signal, int(sample_rate * 0.008))  # atraso de 8ms

    audio_int16 = np.empty((n_samples, 2), dtype=np.int16)
    audio_int16[:, 0] = (left * 32767).astype(np.int16)
    audio_int16[:, 1] = (right * 32767).astype(np.int16)

    temp_wav = "/tmp/tucavr_cinema_ambient.wav"
    with wave.open(temp_wav, "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(audio_int16.tobytes())

    # Conversão para OGG Vorbis via ffmpeg
    cmd = [
        "ffmpeg", "-y",
        "-i", temp_wav,
        "-c:a", "libvorbis",
        "-b:a", "64k",
        AUDIO_OUTPUT_PATH
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"Falha ao executar ffmpeg: {res.stderr}")

    if os.path.exists(temp_wav):
        os.remove(temp_wav)

    ogg_size = os.path.getsize(AUDIO_OUTPUT_PATH)
    print(f"    Áudio OGG salvo em: {AUDIO_OUTPUT_PATH}")
    print(f"    Tamanho do OGG: {ogg_size} bytes ({ogg_size / 1024:.1f} KB)")
    return ogg_size


# ==============================================================================
# Validação de Integridade com Trimesh e FFprobe
# ==============================================================================

def validate_environment():
    """Valida formalmente o arquivo GLB e OGG gerados."""
    print("\n========================================================")
    print(">>> VALIDAÇÃO DE INTEGRIDADE DO AMBIENTE CINEMA IMAX <<<")
    print("========================================================")

    # 1. Validação GLB
    assert os.path.exists(MODEL_OUTPUT_PATH), f"Arquivo não encontrado: {MODEL_OUTPUT_PATH}"
    glb_size = os.path.getsize(MODEL_OUTPUT_PATH)
    loaded = trimesh.load(MODEL_OUTPUT_PATH, file_type="glb")

    if isinstance(loaded, trimesh.Scene):
        geometries = list(loaded.geometry.values())
        draw_calls = len(geometries)
        total_verts = sum(len(g.vertices) for g in geometries)
        total_faces = sum(len(g.faces) for g in geometries)
        bounds = loaded.bounds
        has_vcols = any(hasattr(g.visual, "vertex_colors") and g.visual.vertex_colors is not None for g in geometries)
    else:
        draw_calls = 1
        total_verts = len(loaded.vertices)
        total_faces = len(loaded.faces)
        bounds = loaded.bounds
        has_vcols = hasattr(loaded.visual, "vertex_colors") and loaded.visual.vertex_colors is not None

    print(f"[GLB] Arquivo: {MODEL_OUTPUT_PATH}")
    print(f"[GLB] Tamanho: {glb_size} bytes ({glb_size / 1024:.2f} KB)")
    print(f"[GLB] Draw Calls (Meshes/Nós): {draw_calls}")
    print(f"[GLB] Vértices: {total_verts}")
    print(f"[GLB] Triângulos (Faces): {total_faces}")
    print(f"[GLB] Bounding Box Min: {bounds[0].round(2)}")
    print(f"[GLB] Bounding Box Max: {bounds[1].round(2)}")
    print(f"[GLB] Vertex Colors Presentes: {has_vcols}")

    # Checagens das diretrizes obrigatórias
    assert draw_calls <= 3, f"ERRO: Draw calls ({draw_calls}) excedem o limite de 3!"
    assert 5000 <= total_faces <= 15000, f"ERRO: Total de faces ({total_faces}) fora da faixa de 5.000 a 15.000!"
    assert glb_size < 300 * 1024, f"ERRO: Tamanho do GLB ({glb_size} bytes) excede 300 KB!"
    assert has_vcols, "ERRO: Vertex colors ausentes!"

    # 2. Validação Áudio OGG
    assert os.path.exists(AUDIO_OUTPUT_PATH), f"Arquivo não encontrado: {AUDIO_OUTPUT_PATH}"
    ogg_size = os.path.getsize(AUDIO_OUTPUT_PATH)
    probe_cmd = [
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration,bit_rate:stream=codec_name,channels,sample_rate",
        "-of", "default=noprint_wrappers=1",
        AUDIO_OUTPUT_PATH
    ]
    probe_res = subprocess.run(probe_cmd, capture_output=True, text=True)
    print("\n[OGG] Arquivo:", AUDIO_OUTPUT_PATH)
    print(f"[OGG] Tamanho: {ogg_size} bytes ({ogg_size / 1024:.2f} KB)")
    print("[OGG] Detalhes do Formato:\n" + probe_res.stdout.strip())

    print("\n>>> TODAS AS VALIDAÇÕES PASSARAM COM SUCESSO! <<<")


if __name__ == "__main__":
    build_cinema_model()
    generate_ambient_audio()
    validate_environment()
