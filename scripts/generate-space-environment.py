#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gerador dos assets do ambiente 3D "Espaço Cósmico" (Fase 0.5 §3):
- app/src/main/assets/environments/space/model.glb   (Plataforma espacial flutuante)
- app/src/main/assets/environments/space/skybox.png  (Panorama cósmico 360 equirretangular)
- app/src/main/assets/environments/space/ambient.ogg (Rumble espacial suave em loop)

Execução recomendada com o interpretador dedicado:
  /tmp/env_3d_tools/bin/python scripts/generate-space-environment.py
"""

import os
import sys
import math
import time
import struct
import wave
import subprocess
import numpy as np
from PIL import Image

try:
    import trimesh
except ImportError:
    print("ERRO: trimesh não encontrado. Execute com /tmp/env_3d_tools/bin/python")
    sys.exit(1)

# Caminhos padrão do projeto
BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SPACE_DIR = os.path.join(BASE_DIR, "app", "src", "main", "assets", "environments", "space")
MODEL_OUT = os.path.join(SPACE_DIR, "model.glb")
SKYBOX_OUT = os.path.join(SPACE_DIR, "skybox.png")
AMBIENT_OUT = os.path.join(SPACE_DIR, "ambient.ogg")


# ==============================================================================
# 1. GERADOR DO SKYBOX CÓSMICO (2048x1024 PNG Equirretangular)
# ==============================================================================
def generate_skybox(output_path, width=2048, height=1024):
    """
    Gera um panorama 360 equirretangular realista do espaço profundo:
    - Fundo negro com milhares de estrelas pontuais (brilhos/tamanhos variados).
    - Gradientes suaves de nebulosas cósmicas (púrpura, azul profundo e magenta).
    - Rastro harmônico da Via Láctea cruzando o céu com dust lanes.
    - Continuidade esférica perfeita sem costuras (360 seamless).
    """
    print(f"\n[1/3] Gerando Skybox Cósmico Equirretangular ({width}x{height})...")
    t0 = time.time()

    # Grade esférica de coordenadas 3D unitárias
    theta = np.linspace(-np.pi, np.pi, width, endpoint=False)
    phi = np.linspace(np.pi / 2, -np.pi / 2, height)
    theta_grid, phi_grid = np.meshgrid(theta, phi)
    cos_phi = np.cos(phi_grid)
    X = cos_phi * np.cos(theta_grid)
    Y = np.sin(phi_grid)
    Z = cos_phi * np.sin(theta_grid)

    P = np.stack([X.ravel(), Y.ravel(), Z.ravel()], axis=1)

    # Ruído Fourier 3D multi-escala para nebulosas e poeira interestelar
    np.random.seed(2026)
    K1 = 16
    W1 = np.random.randn(3, K1) * 3.5
    p1 = np.random.rand(K1) * 2 * np.pi
    noise_large = (np.cos(np.dot(P, W1) + p1).mean(axis=1).reshape((height, width)) + 0.3) * 0.5

    K2 = 24
    W2 = np.random.randn(3, K2) * 8.0
    p2 = np.random.rand(K2) * 2 * np.pi
    noise_fine = (np.cos(np.dot(P, W2) + p2).mean(axis=1).reshape((height, width)) + 0.3) * 0.5

    # Ruído para dust lanes (filamentos escuros da Via Láctea)
    K3 = 20
    W3 = np.random.randn(3, K3) * 12.0
    p3 = np.random.rand(K3) * 2 * np.pi
    noise_dust = np.clip(np.cos(np.dot(P, W3) + p3).mean(axis=1).reshape((height, width)) * 2.0, 0, 1)

    # Plano galáctico da Via Láctea inclinado (~55 graus)
    mw_n = np.array([0.55, 0.65, -0.52])
    mw_n /= np.linalg.norm(mw_n)
    d_mw = np.abs(X * mw_n[0] + Y * mw_n[1] + Z * mw_n[2])
    mw_band = np.exp(-d_mw**2 / (2 * 0.16**2)) * (1.0 - 0.6 * noise_dust)

    # Bulbo/Centro Galáctico
    mw_core_c = np.cross(mw_n, [0, 1, 0])
    mw_core_c /= np.linalg.norm(mw_core_c)
    d_core = np.arccos(np.clip(X * mw_core_c[0] + Y * mw_core_c[1] + Z * mw_core_c[2], -1, 1))
    mw_core = 1.3 * np.exp(-d_core**2 / (2 * 0.35**2)) * (1.0 - 0.5 * noise_dust)
    mw_total = mw_band + mw_core

    # Nebulosa 1: Púrpura / Magenta (superior frontal-direita)
    neb1_c = np.array([0.45, 0.25, -0.85])
    neb1_c /= np.linalg.norm(neb1_c)
    d_neb1 = np.arccos(np.clip(X * neb1_c[0] + Y * neb1_c[1] + Z * neb1_c[2], -1, 1))
    neb1 = np.exp(-d_neb1**2 / (2 * 0.32**2)) * np.clip(noise_large + 0.2, 0, 1)

    # Nebulosa 2: Azul Profundo / Ciano Cósmico (lateral frontal-esquerda)
    neb2_c = np.array([-0.65, -0.20, -0.70])
    neb2_c /= np.linalg.norm(neb2_c)
    d_neb2 = np.arccos(np.clip(X * neb2_c[0] + Y * neb2_c[1] + Z * neb2_c[2], -1, 1))
    neb2 = np.exp(-d_neb2**2 / (2 * 0.38**2)) * np.clip(noise_fine + 0.2, 0, 1)

    # Nebulosa 3: Magenta / Violeta estelar (traseira)
    neb3_c = np.array([0.50, -0.35, 0.78])
    neb3_c /= np.linalg.norm(neb3_c)
    d_neb3 = np.arccos(np.clip(X * neb3_c[0] + Y * neb3_c[1] + Z * neb3_c[2], -1, 1))
    neb3 = np.exp(-d_neb3**2 / (2 * 0.40**2)) * np.clip(noise_large * 0.7 + noise_fine * 0.5, 0, 1)

    # Síntese das cores das nebulosas e Via Láctea (RGB)
    R = (neb1 * 48 + neb2 * 15 + neb3 * 52 + mw_total * 45).clip(0, 255)
    G = (neb1 * 18 + neb2 * 32 + neb3 * 16 + mw_total * 42).clip(0, 255)
    B = (neb1 * 55 + neb2 * 68 + neb3 * 62 + mw_total * 55).clip(0, 255)

    img_arr = np.stack([R, G, B], axis=2).astype(np.float32)

    # Starfield: 8.000 estrelas pontuais com distribuição esférica uniforme e física estelar
    n_stars = 8000
    star_z = np.random.uniform(-1, 1, n_stars)
    star_th = np.random.uniform(-np.pi, np.pi, n_stars)
    star_r = np.sqrt(1 - star_z**2)
    star_x = star_r * np.cos(star_th)
    star_y = star_z
    star_z_3d = star_r * np.sin(star_th)

    # Projeção equirretangular exata
    star_u = ((np.arctan2(star_z_3d, star_x) / (2 * np.pi) + 0.5) * width) % width
    star_v = np.clip((np.arcsin(np.clip(star_y, -1, 1)) / np.pi + 0.5) * height, 0, height - 1)

    # Distribuição de brilho (lei de potências astronômica)
    brightness = np.random.pareto(a=2.5, size=n_stars)
    brightness = np.clip(brightness / 4.0, 0.2, 2.5)

    for i in range(n_stars):
        u0 = int(star_u[i])
        v0 = int(star_v[i])
        b = brightness[i]

        # Paleta de temperatura estelar (O, B, A, F, G, K, M)
        roll = np.random.rand()
        if roll < 0.40:
            c = np.array([215, 230, 255], dtype=np.float32)  # Azul-branco quente
        elif roll < 0.75:
            c = np.array([255, 255, 255], dtype=np.float32)  # Branco puro
        elif roll < 0.90:
            c = np.array([255, 240, 205], dtype=np.float32)  # Amarelo solar
        else:
            c = np.array([255, 190, 140], dtype=np.float32)  # Laranja/vermelho gigante

        if b < 0.8:
            # Estrela padrão: 1 pixel
            img_arr[v0, u0] = np.maximum(img_arr[v0, u0], c * b)
        elif b < 1.4:
            # Estrela média com halo 3x3 suave
            for du in [-1, 0, 1]:
                for dv in [-1, 0, 1]:
                    vv = np.clip(v0 + dv, 0, height - 1)
                    uu = (u0 + du) % width
                    weight = 1.0 / (1.0 + du * du + dv * dv)
                    img_arr[vv, uu] = np.maximum(img_arr[vv, uu], c * (b * weight))
        else:
            # Estrela brilhante destacada com difração sutil em cruz de 4 pontas
            for du in range(-3, 4):
                uu = (u0 + du) % width
                w = 0.8 / (1.0 + abs(du))
                img_arr[v0, uu] = np.maximum(img_arr[v0, uu], c * (b * w))
            for dv in range(-3, 4):
                vv = np.clip(v0 + dv, 0, height - 1)
                w = 0.8 / (1.0 + abs(dv))
                img_arr[vv, u0] = np.maximum(img_arr[vv, u0], c * (b * w))

    # Salva com compressão PNG otimizada
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    final_img = Image.fromarray(np.clip(img_arr, 0, 255).astype(np.uint8))
    final_img.save(output_path, optimize=True)

    elapsed = time.time() - t0
    size_kb = os.path.getsize(output_path) / 1024
    print(f"  -> Skybox salvo em: {output_path}")
    print(f"  -> Dimensões: {width}x{height} | Tempo: {elapsed:.2f}s | Tamanho: {size_kb:.1f} KB ({size_kb/1024:.2f} MB)")


# ==============================================================================
# 2. GERADOR DO ÁUDIO AMBIENTE (8s Loop OGG - Rumble Espacial Suave)
# ==============================================================================
def generate_ambient_audio(output_path, duration=8.0, sample_rate=48000):
    """
    Gera um áudio estéreo em loop perfeito com rumble cósmico e drone suave:
    - Frequências graves profundas sincronizadas exatamente com a duração (sem cliques).
    - Camada harmônica etérea e estéreo espacial com respiração cósmica suave.
    - Ruído de vácuo filtrado periodicamente (FFT circular).
    - Volume contido (-14 dBFS de pico) ideal para ducking durante a reprodução.
    """
    print(f"\n[2/3] Gerando Áudio Ambiente em Loop ({duration:.1f}s, {sample_rate}Hz)...")
    t0 = time.time()

    N = int(sample_rate * duration)
    t = np.arange(N) / sample_rate

    # Frequências sub-graves com ciclos inteiros exatos em 'duration' (continuidade perfeita)
    freqs_sub = [42.0, 55.0, 65.5, 82.5]
    freqs_mid = [110.0, 165.0, 220.0, 275.0, 330.0]

    left = np.zeros(N, dtype=np.float64)
    right = np.zeros(N, dtype=np.float64)

    # 1. Camada sub-grave (rumble da nave / ressonância do vácuo)
    for f in freqs_sub:
        k = round(f * duration)
        exact_f = k / duration
        lfo_l = 0.8 + 0.2 * np.cos(2 * np.pi * 1 * t / duration)
        lfo_r = 0.8 + 0.2 * np.cos(2 * np.pi * 1 * t / duration + 0.5)
        left += 0.25 * np.sin(2 * np.pi * exact_f * t) * lfo_l
        right += 0.25 * np.sin(2 * np.pi * exact_f * t + 0.3) * lfo_r

    # 2. Camada harmônica suave (drone cósmico misterioso e relaxante)
    for f in freqs_mid:
        k = round(f * duration)
        exact_f = k / duration
        lfo_l = 0.7 + 0.3 * np.cos(2 * np.pi * 2 * t / duration)
        lfo_r = 0.7 + 0.3 * np.cos(2 * np.pi * 2 * t / duration + 1.0)
        amp = 0.08 * (100.0 / exact_f)
        left += amp * np.sin(2 * np.pi * exact_f * t) * lfo_l
        right += amp * np.sin(2 * np.pi * exact_f * t + 0.7) * lfo_r

    # 3. Ruído cósmico filtrado via FFT circular (100% contínuo entre o início e o fim)
    np.random.seed(1337)
    freqs = np.fft.rfftfreq(N, 1.0 / sample_rate)
    H = 1.0 / (1.0 + (freqs / 80.0)**3)  # Low-pass acentuado em 80 Hz
    noise_spec_l = (np.random.randn(len(freqs)) + 1j * np.random.randn(len(freqs))) * H
    noise_spec_r = (np.random.randn(len(freqs)) + 1j * np.random.randn(len(freqs))) * H
    noise_l = np.fft.irfft(noise_spec_l, n=N)
    noise_r = np.fft.irfft(noise_spec_r, n=N)

    noise_l = noise_l / np.std(noise_l) * 0.12
    noise_r = noise_r / np.std(noise_r) * 0.12
    left += noise_l
    right += noise_r

    # Micro-fade de suavização de fronteira (50 amostras ~ 1ms) para garantir zero descontinuidade absoluta
    fade_len = 50
    fade_in = 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, fade_len))
    left[:fade_len] = left[:fade_len] * fade_in + left[-fade_len:] * (1.0 - fade_in)
    right[:fade_len] = right[:fade_len] * fade_in + right[-fade_len:] * (1.0 - fade_in)
    left[-fade_len:] = left[:fade_len]
    right[-fade_len:] = right[:fade_len]

    # Normalização para -14 dBFS (amplitude de pico ~0.20)
    peak = max(np.max(np.abs(left)), np.max(np.abs(right)))
    target_peak = 0.20
    left = (left / peak * target_peak).astype(np.float32)
    right = (right / peak * target_peak).astype(np.float32)

    # Escrita intermediária WAV 16-bit PCM
    audio_int16 = np.zeros((N, 2), dtype=np.int16)
    audio_int16[:, 0] = (left * 32767).astype(np.int16)
    audio_int16[:, 1] = (right * 32767).astype(np.int16)

    tmp_wav = "/tmp/space_ambient_temp.wav"
    with wave.open(tmp_wav, "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(audio_int16.tobytes())

    # Codificação para Vorbis OGG com ffmpeg
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    cmd = [
        "ffmpeg", "-y",
        "-i", tmp_wav,
        "-c:a", "libvorbis",
        "-q:a", "4",
        output_path
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if os.path.exists(tmp_wav):
        os.remove(tmp_wav)

    elapsed = time.time() - t0
    size_kb = os.path.getsize(output_path) / 1024
    print(f"  -> Áudio salvo em: {output_path}")
    print(f"  -> Formato: OGG Vorbis Stereo {sample_rate}Hz | Duração: {duration:.1f}s | Tamanho: {size_kb:.1f} KB")


# ==============================================================================
# 3. GERADOR DA PLATAFORMA ESPACIAL 3D (model.glb)
# ==============================================================================
def generate_space_platform(output_path):
    """
    Gera o modelo 3D da plataforma espacial (model.glb):
    - Deck de observação circular futurista flutuante (diâmetro 5m -> raio 2.5m).
    - Piso com placas metálicas radiais, anéis de neon ciano embutidos e aro de borda chanfrado.
    - Casco inferior cônico com núcleo anti-gravidade/levitação luminoso.
    - Guarda-corpo de proteção com corrimão e luzes neon (ciano/azul estelar) nas arestas (vertex colors).
    - Abertura panorâmica frontal (arco de 90° para -Z) deixando a área da tela totalmente desobstruída.
    - Pilares de sinalização com holograma superior e console de navegação frontal rebaixado.
    - Orçamento rígido: < 8.000 triângulos e < 200 KB.
    """
    print("\n[3/3] Gerando Plataforma Espacial Futurista (model.glb)...")
    t0 = time.time()

    meshes = []

    # Cores Sci-Fi (RGB uint8)
    C_CYAN_NEON = [0, 235, 255, 255]       # Neon ciano vibrante
    C_BLUE_ACCENT = [25, 160, 255, 255]    # Azul elétrico
    C_DEEP_BLUE = [15, 110, 220, 255]      # Azul cobalto escuro
    C_ALLOY_LIGHT = [75, 82, 95, 255]      # Liga metálica prata/titânio
    C_ALLOY_MED_1 = [52, 58, 68, 255]      # Painel metálico médio A
    C_ALLOY_MED_2 = [42, 46, 54, 255]      # Painel metálico médio B
    C_DARK_CARBON = [30, 33, 40, 255]      # Fibra de carbono escura
    C_GROOVE_DARK = [20, 22, 26, 255]      # Ranhuras e juntas de expansão
    C_HULL_DARK = [24, 26, 32, 255]        # Casco inferior

    # Funções auxiliares de modelagem paramétrica
    def make_flat_ring(r_in, r_out, y, n_segs, color):
        """Anel plano circular."""
        th = np.linspace(0, 2 * np.pi, n_segs, endpoint=False)
        verts = []
        for t in th:
            verts.append([r_in * np.sin(t), y, -r_in * np.cos(t)])
        for t in th:
            verts.append([r_out * np.sin(t), y, -r_out * np.cos(t)])
        faces = []
        for i in range(n_segs):
            ni = (i + 1) % n_segs
            faces.append([i, ni, n_segs + ni])
            faces.append([n_segs + ni, n_segs + i, i])
        m = trimesh.Trimesh(vertices=verts, faces=faces)
        m.visual.vertex_colors = np.full((len(verts), 4), color, dtype=np.uint8)
        return m

    def make_solid_sector(r_in, r_out, y_top, y_bot, t_start, t_end, num_segs, color_top, color_side):
        """Setor anular prismático volumétrico sólido e manifold."""
        th = np.linspace(t_start, t_end, num_segs + 1)
        sin_t, cos_t = np.sin(th), np.cos(th)
        n_pts = num_segs + 1
        v_top_in = np.column_stack([r_in * sin_t, np.full(n_pts, y_top), -r_in * cos_t])
        v_top_out = np.column_stack([r_out * sin_t, np.full(n_pts, y_top), -r_out * cos_t])
        v_bot_in = np.column_stack([r_in * sin_t, np.full(n_pts, y_bot), -r_in * cos_t])
        v_bot_out = np.column_stack([r_out * sin_t, np.full(n_pts, y_bot), -r_out * cos_t])
        verts = np.vstack([v_top_in, v_top_out, v_bot_in, v_bot_out])
        colors = np.vstack([
            np.full((n_pts, 4), color_top, dtype=np.uint8),
            np.full((n_pts, 4), color_top, dtype=np.uint8),
            np.full((n_pts, 4), color_side, dtype=np.uint8),
            np.full((n_pts, 4), color_side, dtype=np.uint8)
        ])
        faces = []
        # Topo (+Y)
        for i in range(num_segs):
            faces.append([i, i + 1, n_pts + i + 1])
            faces.append([n_pts + i + 1, n_pts + i, i])
        # Fundo (-Y)
        b_in, b_out = 2 * n_pts, 3 * n_pts
        for i in range(num_segs):
            faces.append([b_in + i, b_out + i + 1, b_in + i + 1])
            faces.append([b_out + i + 1, b_in + i, b_out + i])
        # Parede externa (+R)
        for i in range(num_segs):
            faces.append([n_pts + i, n_pts + i + 1, b_out + i + 1])
            faces.append([b_out + i + 1, b_out + i, n_pts + i])
        # Parede interna (-R)
        for i in range(num_segs):
            faces.append([i, b_in + i + 1, i + 1])
            faces.append([b_in + i + 1, i, b_in + i])
        # Laterais radiais
        faces.append([0, b_in, n_pts])
        faces.append([n_pts, b_in, b_out])
        faces.append([num_segs, n_pts + num_segs, b_in + num_segs])
        faces.append([n_pts + num_segs, b_out + num_segs, b_in + num_segs])

        m = trimesh.Trimesh(vertices=verts, faces=faces)
        m.visual.vertex_colors = colors
        return m

    def make_profile_ring(profile, n_segs, color):
        """Superfície de revolução por perfil 2D (r, y)."""
        th = np.linspace(0, 2 * np.pi, n_segs, endpoint=False)
        verts = []
        colors = []
        for r, y in profile:
            for t in th:
                verts.append([r * np.sin(t), y, -r * np.cos(t)])
                colors.append(color)
        faces = []
        for p in range(len(profile) - 1):
            ring0 = p * n_segs
            ring1 = (p + 1) * n_segs
            for i in range(n_segs):
                ni = (i + 1) % n_segs
                faces.append([ring0 + i, ring0 + ni, ring1 + ni])
                faces.append([ring1 + ni, ring1 + i, ring0 + i])
        m = trimesh.Trimesh(vertices=verts, faces=faces)
        m.visual.vertex_colors = np.array(colors, dtype=np.uint8)
        return m

    def make_curved_rail(r, y_bot, y_top, width, th_start, th_end, n_segs, color_top, color_side):
        """Barra ou corrimão curvo seguindo um raio e intervalo angular."""
        r_in = r - width / 2
        r_out = r + width / 2
        th = np.linspace(th_start, th_end, n_segs + 1)
        sin_t, cos_t = np.sin(th), np.cos(th)
        n_pts = n_segs + 1
        v_top_in = np.column_stack([r_in * sin_t, np.full(n_pts, y_top), -r_in * cos_t])
        v_top_out = np.column_stack([r_out * sin_t, np.full(n_pts, y_top), -r_out * cos_t])
        v_bot_in = np.column_stack([r_in * sin_t, np.full(n_pts, y_bot), -r_in * cos_t])
        v_bot_out = np.column_stack([r_out * sin_t, np.full(n_pts, y_bot), -r_out * cos_t])
        verts = np.vstack([v_top_in, v_top_out, v_bot_in, v_bot_out])
        colors = np.vstack([
            np.full((n_pts, 4), color_top, dtype=np.uint8),
            np.full((n_pts, 4), color_top, dtype=np.uint8),
            np.full((n_pts, 4), color_side, dtype=np.uint8),
            np.full((n_pts, 4), color_side, dtype=np.uint8)
        ])
        faces = []
        for i in range(n_segs):
            faces.append([i, i + 1, n_pts + i + 1])
            faces.append([n_pts + i + 1, n_pts + i, i])
        b_in, b_out = 2 * n_pts, 3 * n_pts
        for i in range(n_segs):
            faces.append([b_in + i, b_out + i + 1, b_in + i + 1])
            faces.append([b_out + i + 1, b_in + i, b_out + i])
        for i in range(n_segs):
            faces.append([n_pts + i, n_pts + i + 1, b_out + i + 1])
            faces.append([b_out + i + 1, b_out + i, n_pts + i])
        for i in range(n_segs):
            faces.append([i, b_in + i + 1, i + 1])
            faces.append([b_in + i + 1, i, b_in + i])
        # Tampas das pontas
        faces.append([0, b_in, n_pts])
        faces.append([n_pts, b_in, b_out])
        faces.append([n_segs, n_pts + n_segs, b_in + n_segs])
        faces.append([n_pts + n_segs, b_out + n_segs, b_in + n_segs])
        m = trimesh.Trimesh(vertices=verts, faces=faces)
        m.visual.vertex_colors = colors
        return m

    # --------------------------------------------------------------------------
    # A) DISCO CENTRAL DO PISO (R: 0.0 -> 0.88m, Y = 0.005m)
    # --------------------------------------------------------------------------
    r_center = 0.88
    n_c = 32
    th_c = np.linspace(0, 2 * np.pi, n_c, endpoint=False)
    v_c = [[0.0, 0.005, 0.0]]
    c_c = [C_DARK_CARBON]
    for t in th_c:
        v_c.append([r_center * np.sin(t), 0.005, -r_center * np.cos(t)])
        c_c.append(C_ALLOY_MED_2)
    f_c = []
    for i in range(n_c):
        next_i = (i + 1) % n_c
        f_c.append([0, i + 1, next_i + 1])
    m_center = trimesh.Trimesh(vertices=v_c, faces=f_c)
    m_center.visual.vertex_colors = np.array(c_c, dtype=np.uint8)
    meshes.append(m_center)

    # Medalhão de ancoragem central holográfico (R: 0.20 -> 0.25m)
    meshes.append(make_flat_ring(0.20, 0.25, 0.007, 24, C_CYAN_NEON))

    # Anel de neon interno embutido (R: 0.88 -> 0.92m)
    meshes.append(make_flat_ring(0.88, 0.92, 0.007, 32, C_CYAN_NEON))

    # --------------------------------------------------------------------------
    # B) 16 PLACAS METÁLICAS RADIAIS (R: 0.93 -> 2.24m)
    # --------------------------------------------------------------------------
    n_sectors = 16
    d_th = 2 * np.pi / n_sectors
    gap = 0.015  # Junta de expansão de 1.5cm

    for i in range(n_sectors):
        t0_sec = i * d_th + gap
        t1_sec = (i + 1) * d_th - gap
        color_plate = C_ALLOY_MED_1 if i % 2 == 0 else C_ALLOY_MED_2

        # Placa radial interna (0.93 -> 1.58m)
        m_in = make_solid_sector(0.93, 1.58, 0.005, 0.0, t0_sec, t1_sec, 3, color_plate, C_GROOVE_DARK)
        # Placa radial externa (1.60 -> 2.24m)
        m_out = make_solid_sector(1.60, 2.24, 0.005, 0.0, t0_sec, t1_sec, 3, color_plate, C_GROOVE_DARK)
        meshes.extend([m_in, m_out])

    # Ranhura concêntrica intermediária (R: 1.58 -> 1.60m)
    meshes.append(make_flat_ring(1.58, 1.60, 0.006, 48, C_BLUE_ACCENT))

    # Anel guia de neon externo (R: 2.23 -> 2.26m)
    meshes.append(make_flat_ring(2.23, 2.26, 0.007, 48, C_CYAN_NEON))

    # --------------------------------------------------------------------------
    # C) ARO DE BORDA CHANFRADO (R: 2.26 -> 2.50m)
    # --------------------------------------------------------------------------
    border_profile = [
        (2.26, 0.00),
        (2.34, 0.04),
        (2.45, 0.04),
        (2.50, -0.08),
        (2.50, -0.14)
    ]
    meshes.append(make_profile_ring(border_profile, 48, C_ALLOY_LIGHT))

    # --------------------------------------------------------------------------
    # D) CASCO INFERIOR FLUTUANTE (CHASSIS & NÚCLEO ANTI-GRAVIDADE)
    # --------------------------------------------------------------------------
    hull_profile = [
        (2.50, -0.14),
        (1.90, -0.35),
        (1.25, -0.55),
        (0.80, -0.55)
    ]
    meshes.append(make_profile_ring(hull_profile, 48, C_HULL_DARK))
    # Anel emissor de energia da levitação
    meshes.append(make_flat_ring(0.80, 1.25, -0.55, 32, C_CYAN_NEON))

    # --------------------------------------------------------------------------
    # E) GUARDA-CORPO E BALCÃO DE PROTEÇÃO METÁLICO
    # --------------------------------------------------------------------------
    # Abertura frontal: arco de -45° a +45° totalmente aberto para a tela e o espaço profundo.
    # Corrimão e balcão abrangem os lados e a traseira: arco de +45° (pi/4) a 315° (7pi/4).

    # Corrimão superior com vértice neon ciano na aresta de topo
    meshes.append(make_curved_rail(2.42, 0.99, 1.05, 0.06, np.pi / 4, 7 * np.pi / 4, 32, C_CYAN_NEON, C_ALLOY_LIGHT))
    # Barra intermediária com acento azul estelar
    meshes.append(make_curved_rail(2.42, 0.52, 0.56, 0.03, np.pi / 4, 7 * np.pi / 4, 32, C_BLUE_ACCENT, C_ALLOY_MED_1))
    # Rodapé / guarda inferior
    meshes.append(make_curved_rail(2.42, 0.04, 0.16, 0.02, np.pi / 4, 7 * np.pi / 4, 32, C_DARK_CARBON, C_DARK_CARBON))

    # Soleira frontal rebaixada (linha de beacon neon na borda frontal sem ocluir a tela)
    meshes.append(make_curved_rail(2.42, 0.04, 0.065, 0.04, -np.pi / 4, np.pi / 4, 16, C_CYAN_NEON, C_ALLOY_LIGHT))

    # 11 Pilaretes verticais (stanchions) com faixa luminosa interna
    def make_stanchion(r, phi, y_bot, y_top, w_rad, w_tan):
        h = y_top - y_bot
        y_mid = (y_top + y_bot) / 2
        box = trimesh.creation.box(extents=[w_tan, h, w_rad])
        v = box.vertices.copy()
        c = np.full((len(v), 4), C_ALLOY_MED_2, dtype=np.uint8)
        # Face voltada para o interior ganha luz neon
        c[v[:, 2] > 0.0] = C_CYAN_NEON
        box.visual.vertex_colors = c
        rot = trimesh.transformations.rotation_matrix(phi, [0, 1, 0])
        box.apply_transform(rot)
        pos = [r * np.sin(phi), y_mid, -r * np.cos(phi)]
        box.apply_translation(pos)
        return box

    post_angles = np.linspace(np.pi / 4, 7 * np.pi / 4, 11)
    for phi in post_angles:
        meshes.append(make_stanchion(2.42, phi, 0.04, 1.05, 0.05, 0.05))

    # --------------------------------------------------------------------------
    # F) PILARES DE SINALIZAÇÃO / TERMINAIS HOLOGRÁFICOS FLANQUEADORES
    # --------------------------------------------------------------------------
    def make_signal_pillar(pos, phi_yaw):
        # Coluna estrutural
        col = trimesh.creation.box(extents=[0.20, 0.96, 0.20])
        col.apply_translation([0, 0.48 + 0.04, 0])
        c_col = np.full((len(col.vertices), 4), C_ALLOY_MED_1, dtype=np.uint8)
        c_col[col.vertices[:, 2] > 0.09] = C_CYAN_NEON
        col.visual.vertex_colors = c_col

        # Terminal holográfico superior inclinado
        head = trimesh.creation.cylinder(radius=0.08, height=0.04, sections=16)
        head.apply_translation([0, 1.02, 0])
        head.visual.vertex_colors = np.full((len(head.vertices), 4), C_CYAN_NEON, dtype=np.uint8)

        # Disco holográfico flutuante sutil
        holo = trimesh.creation.cylinder(radius=0.06, height=0.01, sections=12)
        holo.apply_translation([0, 1.06, 0])
        holo.visual.vertex_colors = np.full((len(holo.vertices), 4), [120, 245, 255, 255], dtype=np.uint8)

        pillar = trimesh.util.concatenate([col, head, holo])
        rot = trimesh.transformations.rotation_matrix(phi_yaw, [0, 1, 0])
        pillar.apply_transform(rot)
        pillar.apply_translation(pos)
        return pillar

    p_l_pos = [2.38 * np.sin(7 * np.pi / 4), 0, -2.38 * np.cos(7 * np.pi / 4)]
    p_r_pos = [2.38 * np.sin(np.pi / 4), 0, -2.38 * np.cos(np.pi / 4)]
    meshes.append(make_signal_pillar(p_l_pos, 7 * np.pi / 4))
    meshes.append(make_signal_pillar(p_r_pos, np.pi / 4))

    # --------------------------------------------------------------------------
    # G) CONSOLE DE NAVEGAÇÃO FRONTAL REBAIXADO (Z = -2.05m, Y = 0.0 -> 0.38m)
    # Totalmente abaixo do campo de visão da tela (base da tela em Y = 0.73m a Z = -3.2m)
    # --------------------------------------------------------------------------
    def make_front_console():
        # Pedestal base
        base = trimesh.creation.box(extents=[0.55, 0.32, 0.25])
        base.apply_translation([0, 0.16, -2.05])
        c_base = np.full((len(base.vertices), 4), C_DARK_CARBON, dtype=np.uint8)
        c_base[base.vertices[:, 2] > -2.05 + 0.12] = C_CYAN_NEON
        base.visual.vertex_colors = c_base

        # Painel holográfico de comando
        panel = trimesh.creation.box(extents=[0.45, 0.04, 0.18])
        panel.apply_translation([0, 0.34, -2.05])
        panel.visual.vertex_colors = np.full((len(panel.vertices), 4), C_CYAN_NEON, dtype=np.uint8)

        # Indicadores de status
        led1 = trimesh.creation.box(extents=[0.10, 0.01, 0.03])
        led1.apply_translation([-0.12, 0.37, -2.05])
        led1.visual.vertex_colors = np.full((len(led1.vertices), 4), [80, 210, 255, 255], dtype=np.uint8)

        led2 = trimesh.creation.box(extents=[0.10, 0.01, 0.03])
        led2.apply_translation([0.12, 0.37, -2.05])
        led2.visual.vertex_colors = np.full((len(led2.vertices), 4), [0, 255, 200, 255], dtype=np.uint8)

        return trimesh.util.concatenate([base, panel, led1, led2])

    meshes.append(make_front_console())

    # Combina todas as partes da plataforma em uma malha contínua com cores por vértice
    full_mesh = trimesh.util.concatenate(meshes)
    _ = full_mesh.vertex_normals

    tri_count = len(full_mesh.faces)
    vert_count = len(full_mesh.vertices)

    # Exporta para GLB (glTF 2.0 binário)
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    glb_bytes = trimesh.exchange.gltf.export_glb(full_mesh)
    with open(output_path, "wb") as f:
        f.write(glb_bytes)

    elapsed = time.time() - t0
    size_kb = len(glb_bytes) / 1024
    print(f"  -> Modelo salvo em: {output_path}")
    print(f"  -> Triângulos: {tri_count} (< 8.000) | Vértices: {vert_count} | Tamanho: {size_kb:.1f} KB (< 200 KB)")


# ==============================================================================
# 4. VALIDAÇÃO RIGOROSA DE TODOS OS ARTEFATOS
# ==============================================================================
def validate_assets():
    """Valida tamanhos, dimensões e contagem de polígonos de todos os assets."""
    print("\n" + "=" * 70)
    print("VALIDAÇÃO DOS ASSETS GERADOS PARA O ESPAÇO CÓSMICO")
    print("=" * 70)

    # 1. model.glb
    assert os.path.exists(MODEL_OUT), f"Arquivo não encontrado: {MODEL_OUT}"
    sz_model = os.path.getsize(MODEL_OUT)
    loaded = trimesh.load(MODEL_OUT)
    total_faces = sum(len(g.faces) for g in loaded.geometry.values()) if hasattr(loaded, "geometry") else len(loaded.faces)
    total_verts = sum(len(g.vertices) for g in loaded.geometry.values()) if hasattr(loaded, "geometry") else len(loaded.vertices)
    
    print(f"[OK] {os.path.basename(MODEL_OUT)}:")
    print(f"     - Tamanho do arquivo: {sz_model} bytes ({sz_model / 1024:.2f} KB) [Limite: < 200 KB]")
    print(f"     - Triângulos: {total_faces} [Limite: < 8.000]")
    print(f"     - Vértices: {total_verts}")
    assert total_faces < 8000, f"Triângulos excedem limite: {total_faces} >= 8000"
    assert sz_model < 200 * 1024, f"Tamanho excede limite: {sz_model} >= 200KB"

    # 2. skybox.png
    assert os.path.exists(SKYBOX_OUT), f"Arquivo não encontrado: {SKYBOX_OUT}"
    sz_skybox = os.path.getsize(SKYBOX_OUT)
    with Image.open(SKYBOX_OUT) as im:
        w, h = im.size
        mode = im.mode
    print(f"[OK] {os.path.basename(SKYBOX_OUT)}:")
    print(f"     - Tamanho do arquivo: {sz_skybox} bytes ({sz_skybox / 1024 / 1024:.2f} MB) [Limite: < 1.5 MB]")
    print(f"     - Resolução: {w}x{h} [Esperado: 2048x1024]")
    print(f"     - Modo de cor: {mode}")
    assert (w, h) == (2048, 1024), f"Resolução inválida: {w}x{h}"
    assert sz_skybox < 1.5 * 1024 * 1024, f"Tamanho excede limite: {sz_skybox} >= 1.5MB"

    # 3. ambient.ogg
    assert os.path.exists(AMBIENT_OUT), f"Arquivo não encontrado: {AMBIENT_OUT}"
    sz_ambient = os.path.getsize(AMBIENT_OUT)
    print(f"[OK] {os.path.basename(AMBIENT_OUT)}:")
    print(f"     - Tamanho do arquivo: {sz_ambient} bytes ({sz_ambient / 1024:.2f} KB)")
    assert sz_ambient > 1000, f"Arquivo de áudio muito pequeno ou corrompido: {sz_ambient} bytes"

    print("=" * 70)
    print("TODOS OS ARTEFATOS FORAM VALIDADOS COM SUCESSO!")
    print("=" * 70)


def main():
    print("Iniciando pipeline de geração do ambiente 3D do Espaço Cósmico...")
    t_start = time.time()

    generate_skybox(SKYBOX_OUT)
    generate_ambient_audio(AMBIENT_OUT)
    generate_space_platform(MODEL_OUT)
    validate_assets()

    print(f"\nTempo total de execução: {time.time() - t_start:.2f} segundos.")


if __name__ == "__main__":
    main()
