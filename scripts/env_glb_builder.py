#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
env_glb_builder.py — Self-contained glTF 2.0 (.glb) procedural scene builder for tucaVR.

Uses only numpy + Pillow (no trimesh or Blender required) and guarantees:
- Strict glTF 2.0 compliance (validated by cgltf_validate in native host tests).
- Exact IEC 61966-2-1 sRGB -> linear conversion for COLOR_0 (16-bit normalized linear),
  baseColorFactor, and emissiveFactor so the Vulkan sRGB swapchain never double-encodes.
- Clean per-face world-scale UVs, analytical/smooth normals, multi-material PBR (baseColor,
  packed linear ORM [R=AO, G=Roughness, B=Metallic], and emissive), and procedural textures.
"""

import io
import json
import math
import os
import struct
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import numpy as np
from PIL import Image


# ==============================================================================
# Color Space Helpers (glTF 2.0 requires linear COLOR_0 / factors, sRGB textures)
# ==============================================================================
def srgb_to_linear(arr: np.ndarray) -> np.ndarray:
    """Converts sRGB [0..1] float array to linear [0..1] via exact IEC 61966-2-1 EOTF."""
    c = np.clip(np.asarray(arr, dtype=np.float32), 0.0, 1.0)
    return np.where(c <= 0.04045, c / 12.92, np.power((c + 0.055) / 1.055, 2.4)).astype(np.float32)


def rgb8_to_linear(rgb: Sequence[float]) -> np.ndarray:
    """Converts an 8-bit sRGB color (0..255) to a linear RGB float32 triple."""
    vals = np.array(rgb[:3], dtype=np.float32) / 255.0
    return srgb_to_linear(vals)


def rgba8_to_linear(rgba: Sequence[float]) -> np.ndarray:
    """Converts an 8-bit sRGB RGBA color (0..255) to linear RGBA float32."""
    lin = rgb8_to_linear(rgba)
    a = float(rgba[3]) / 255.0 if len(rgba) >= 4 else 1.0
    return np.array([lin[0], lin[1], lin[2], a], dtype=np.float32)


# ==============================================================================
# Lightweight Mesh Container
# ==============================================================================
@dataclass
class MeshPart:
    positions: np.ndarray  # (N, 3) float32
    normals: np.ndarray    # (N, 3) float32
    uvs: np.ndarray        # (N, 2) float32
    colors: np.ndarray     # (N, 4) float32 LINEAR RGBA
    indices: np.ndarray    # (M,) uint32

    def translate(self, offset: Sequence[float]) -> "MeshPart":
        off = np.asarray(offset, dtype=np.float32).reshape(1, 3)
        self.positions = self.positions + off
        return self

    def rotate_axis_angle(self, angle_rad: float, axis: Sequence[float], pivot: Sequence[float] = (0.0, 0.0, 0.0)) -> "MeshPart":
        ax = np.asarray(axis, dtype=np.float32)
        n = float(np.linalg.norm(ax))
        if n < 1e-8 or abs(angle_rad) < 1e-8:
            return self
        ax = ax / n
        c = math.cos(angle_rad)
        s = math.sin(angle_rad)
        ux, uy, uz = float(ax[0]), float(ax[1]), float(ax[2])
        R = np.array([
            [c + ux * ux * (1 - c), ux * uy * (1 - c) - uz * s, ux * uz * (1 - c) + uy * s],
            [uy * ux * (1 - c) + uz * s, c + uy * uy * (1 - c), uy * uz * (1 - c) - ux * s],
            [uz * ux * (1 - c) - uy * s, uz * uy * (1 - c) + ux * s, c + uz * uz * (1 - c)],
        ], dtype=np.float32)
        piv = np.asarray(pivot, dtype=np.float32).reshape(1, 3)
        self.positions = (self.positions - piv) @ R.T + piv
        self.normals = self.normals @ R.T
        norms = np.linalg.norm(self.normals, axis=1, keepdims=True)
        self.normals = np.divide(self.normals, np.maximum(norms, 1e-6))
        return self

    def scale(self, factors: Sequence[float], pivot: Sequence[float] = (0.0, 0.0, 0.0)) -> "MeshPart":
        sc = np.asarray(factors, dtype=np.float32).reshape(1, 3)
        piv = np.asarray(pivot, dtype=np.float32).reshape(1, 3)
        self.positions = (self.positions - piv) * sc + piv
        self.normals = self.normals / np.where(np.abs(sc) > 1e-6, sc, 1.0)
        norms = np.linalg.norm(self.normals, axis=1, keepdims=True)
        self.normals = np.divide(self.normals, np.maximum(norms, 1e-6))
        return self


def concat_parts(parts: List[MeshPart]) -> MeshPart:
    if not parts:
        return MeshPart(
            positions=np.zeros((0, 3), dtype=np.float32),
            normals=np.zeros((0, 3), dtype=np.float32),
            uvs=np.zeros((0, 2), dtype=np.float32),
            colors=np.zeros((0, 4), dtype=np.float32),
            indices=np.zeros((0,), dtype=np.uint32),
        )
    pos_list, nrm_list, uv_list, col_list, idx_list = [], [], [], [], []
    base = 0
    for p in parts:
        if len(p.positions) == 0 or len(p.indices) == 0:
            continue
        pos_list.append(p.positions.astype(np.float32, copy=False))
        nrm_list.append(p.normals.astype(np.float32, copy=False))
        uv_list.append(p.uvs.astype(np.float32, copy=False))
        col_list.append(p.colors.astype(np.float32, copy=False))
        idx_list.append(p.indices.astype(np.uint32, copy=False) + np.uint32(base))
        base += len(p.positions)
    return MeshPart(
        positions=np.vstack(pos_list),
        normals=np.vstack(nrm_list),
        uvs=np.vstack(uv_list),
        colors=np.vstack(col_list),
        indices=np.concatenate(idx_list),
    )


def _color_array(n: int, color_rgba8: Sequence[float]) -> np.ndarray:
    lin = rgba8_to_linear(color_rgba8)
    return np.tile(lin.reshape(1, 4), (n, 1))


# ==============================================================================
# Procedural Geometry Primitives (clean normals + world-scale UVs)
# ==============================================================================
def make_box(
    extents: Sequence[float],
    center: Sequence[float] = (0.0, 0.0, 0.0),
    color: Sequence[float] = (255, 255, 255, 255),
    uv_scale: float = 1.0,
    subdiv: Tuple[int, int, int] = (1, 1, 1),
) -> MeshPart:
    """Axis-aligned box with sharp face normals, world-scale UVs, and optional face subdivisions."""
    sx, sy, sz = float(extents[0]), float(extents[1]), float(extents[2])
    hx, hy, hz = sx * 0.5, sy * 0.5, sz * 0.5
    nx, ny, nz = max(1, int(subdiv[0])), max(1, int(subdiv[1])), max(1, int(subdiv[2]))

    # Each face: (origin_corner, u_vec, v_vec, normal, u_segs, v_segs, u_len, v_len)
    faces_spec = [
        ((-hx, -hy,  hz), ( sx, 0, 0), (0,  sy, 0), ( 0,  0,  1), nx, ny, sx, sy),  # +Z
        (( hx, -hy, -hz), (-sx, 0, 0), (0,  sy, 0), ( 0,  0, -1), nx, ny, sx, sy),  # -Z
        (( hx, -hy,  hz), (0, 0, -sz), (0,  sy, 0), ( 1,  0,  0), nz, ny, sz, sy),  # +X
        ((-hx, -hy, -hz), (0, 0,  sz), (0,  sy, 0), (-1,  0,  0), nz, ny, sz, sy),  # -X
        ((-hx,  hy,  hz), ( sx, 0, 0), (0, 0, -sz), ( 0,  1,  0), nx, nz, sx, sz),  # +Y
        ((-hx, -hy, -hz), ( sx, 0, 0), (0, 0,  sz), ( 0, -1,  0), nx, nz, sx, sz),  # -Y
    ]

    pos_all, nrm_all, uv_all, idx_all = [], [], [], []
    base = 0
    for orig, uvec, vvec, nrm, us, vs, ulen, vlen in faces_spec:
        orig_a = np.array(orig, dtype=np.float32)
        u_a = np.array(uvec, dtype=np.float32)
        v_a = np.array(vvec, dtype=np.float32)
        n_a = np.array(nrm, dtype=np.float32)

        uu = np.linspace(0.0, 1.0, us + 1, dtype=np.float32)
        vv = np.linspace(0.0, 1.0, vs + 1, dtype=np.float32)
        ug, vg = np.meshgrid(uu, vv, indexing="xy")
        ug_f = ug.ravel()
        vg_f = vg.ravel()

        pts = orig_a[None, :] + ug_f[:, None] * u_a[None, :] + vg_f[:, None] * v_a[None, :]
        nrms = np.tile(n_a[None, :], (len(pts), 1))
        uvs = np.column_stack([ug_f * (ulen * uv_scale), vg_f * (vlen * uv_scale)])

        cols = us + 1
        tris = []
        for j in range(vs):
            row0 = base + j * cols
            row1 = base + (j + 1) * cols
            for i in range(us):
                tris.append([row0 + i, row0 + i + 1, row1 + i + 1])
                tris.append([row0 + i, row1 + i + 1, row1 + i])

        pos_all.append(pts)
        nrm_all.append(nrms)
        uv_all.append(uvs)
        idx_all.append(np.array(tris, dtype=np.uint32).ravel())
        base += len(pts)

    positions = np.vstack(pos_all)
    normals = np.vstack(nrm_all)
    uvs = np.vstack(uv_all)
    indices = np.concatenate(idx_all)
    part = MeshPart(positions, normals, uvs, _color_array(len(positions), color), indices)
    return part.translate(center)


def make_rounded_box(
    extents: Sequence[float],
    radius: float = 0.03,
    segments: int = 3,
    center: Sequence[float] = (0.0, 0.0, 0.0),
    color: Sequence[float] = (255, 255, 255, 255),
    uv_scale: float = 1.0,
) -> MeshPart:
    """Smooth chamfered/rounded box with analytical smooth normals and clean planar-projected UVs."""
    sx, sy, sz = float(extents[0]), float(extents[1]), float(extents[2])
    r = min(float(radius), sx * 0.45, sy * 0.45, sz * 0.45)
    hx, hy, hz = sx * 0.5, sy * 0.5, sz * 0.5
    inner = np.array([max(0.0, hx - r), max(0.0, hy - r), max(0.0, hz - r)], dtype=np.float32)

    s = max(2, int(segments))
    box = make_box(extents, center=(0.0, 0.0, 0.0), color=color, uv_scale=uv_scale, subdiv=(s, s, s))
    v = box.positions
    clamped = np.clip(v, -inner[None, :], inner[None, :])
    diff = v - clamped
    dist = np.linalg.norm(diff, axis=1, keepdims=True)
    mask = dist[:, 0] > 1e-6
    new_v = v.copy()
    new_n = box.normals.copy()
    new_v[mask] = clamped[mask] + (diff[mask] / dist[mask]) * r
    new_n[mask] = diff[mask] / dist[mask]

    box.positions = new_v
    box.normals = new_n
    return box.translate(center)


def make_cylinder(
    radius: float,
    height: float,
    sections: int = 16,
    center: Sequence[float] = (0.0, 0.0, 0.0),
    axis: Sequence[float] = (0.0, 1.0, 0.0),
    color: Sequence[float] = (255, 255, 255, 255),
    uv_scale: float = 1.0,
    radius_top: Optional[float] = None,
    caps: bool = True,
) -> MeshPart:
    """Cylinder or truncated cone aligned with `axis`, with clean radial UVs and sharp cap normals."""
    rb = float(radius)
    rt = rb if radius_top is None else float(radius_top)
    hh = float(height) * 0.5
    n = max(6, int(sections))

    angles = np.linspace(0.0, 2.0 * math.pi, n + 1, dtype=np.float32)
    ca = np.cos(angles)
    sa = np.sin(angles)

    # Slope normal for truncated cone along Y
    slope = (rb - rt) / max(float(height), 1e-5)
    ny = slope
    n_norm = math.sqrt(1.0 + ny * ny)
    nr = 1.0 / n_norm
    ny /= n_norm

    circ = math.pi * (rb + rt)
    pos_list, nrm_list, uv_list, idx_list = [], [], [], []
    base = 0

    # Side strip
    side_pos = np.zeros((2 * (n + 1), 3), dtype=np.float32)
    side_nrm = np.zeros((2 * (n + 1), 3), dtype=np.float32)
    side_uv = np.zeros((2 * (n + 1), 2), dtype=np.float32)
    for i in range(n + 1):
        u = (i / n) * circ * uv_scale
        # Bottom ring
        side_pos[i] = [rb * ca[i], -hh, rb * sa[i]]
        side_nrm[i] = [nr * ca[i], ny, nr * sa[i]]
        side_uv[i] = [u, 0.0]
        # Top ring
        side_pos[n + 1 + i] = [rt * ca[i], hh, rt * sa[i]]
        side_nrm[n + 1 + i] = [nr * ca[i], ny, nr * sa[i]]
        side_uv[n + 1 + i] = [u, float(height) * uv_scale]

    side_tris = []
    for i in range(n):
        b0, b1 = i, i + 1
        t0, t1 = n + 1 + i, n + 1 + i + 1
        # Counter-clockwise viewed from outside (+radial normal)
        side_tris.append([b0, t0, t1])
        side_tris.append([b0, t1, b1])

    pos_list.append(side_pos)
    nrm_list.append(side_nrm)
    uv_list.append(side_uv)
    idx_list.append(np.array(side_tris, dtype=np.uint32).ravel())
    base += len(side_pos)

    if caps:
        # Top cap (+Y)
        if rt > 1e-6:
            c_pos = np.zeros((n + 1, 3), dtype=np.float32)
            c_pos[0] = [0.0, hh, 0.0]
            c_pos[1:, 0] = rt * ca[:-1]
            c_pos[1:, 1] = hh
            c_pos[1:, 2] = rt * sa[:-1]
            c_nrm = np.tile(np.array([[0.0, 1.0, 0.0]], dtype=np.float32), (n + 1, 1))
            c_uv = np.column_stack([c_pos[:, 0] * uv_scale + 0.5, c_pos[:, 2] * uv_scale + 0.5])
            tris = [[base, base + 1 + ((i + 1) % n), base + 1 + i] for i in range(n)]
            pos_list.append(c_pos)
            nrm_list.append(c_nrm)
            uv_list.append(c_uv)
            idx_list.append(np.array(tris, dtype=np.uint32).ravel())
            base += len(c_pos)

        # Bottom cap (-Y)
        if rb > 1e-6:
            c_pos = np.zeros((n + 1, 3), dtype=np.float32)
            c_pos[0] = [0.0, -hh, 0.0]
            c_pos[1:, 0] = rb * ca[:-1]
            c_pos[1:, 1] = -hh
            c_pos[1:, 2] = rb * sa[:-1]
            c_nrm = np.tile(np.array([[0.0, -1.0, 0.0]], dtype=np.float32), (n + 1, 1))
            c_uv = np.column_stack([c_pos[:, 0] * uv_scale + 0.5, c_pos[:, 2] * uv_scale + 0.5])
            tris = [[base, base + 1 + i, base + 1 + ((i + 1) % n)] for i in range(n)]
            pos_list.append(c_pos)
            nrm_list.append(c_nrm)
            uv_list.append(c_uv)
            idx_list.append(np.array(tris, dtype=np.uint32).ravel())
            base += len(c_pos)

    positions = np.vstack(pos_list)
    normals = np.vstack(nrm_list)
    uvs = np.vstack(uv_list)
    indices = np.concatenate(idx_list)
    part = MeshPart(positions, normals, uvs, _color_array(len(positions), color), indices)

    # Align default +Y axis with target axis
    target_ax = np.asarray(axis, dtype=np.float32)
    target_ax = target_ax / max(float(np.linalg.norm(target_ax)), 1e-6)
    y_ax = np.array([0.0, 1.0, 0.0], dtype=np.float32)
    dot = float(np.clip(np.dot(y_ax, target_ax), -1.0, 1.0))
    if dot < -0.9999:
        part.rotate_axis_angle(math.pi, (1.0, 0.0, 0.0))
    elif dot < 0.9999:
        rot_ax = np.cross(y_ax, target_ax)
        part.rotate_axis_angle(math.acos(dot), rot_ax)

    return part.translate(center)


def make_tube_along_path(
    points: Sequence[Sequence[float]],
    radius: float,
    sections: int = 12,
    color: Sequence[float] = (255, 255, 255, 255),
    uv_scale: float = 1.0,
) -> MeshPart:
    """Extrudes a smooth circular tube along a 3D polyline (ideal for arched lamps and handrails)."""
    pts = np.asarray(points, dtype=np.float32)
    m = len(pts)
    n = max(6, int(sections))
    angles = np.linspace(0.0, 2.0 * math.pi, n + 1, dtype=np.float32)
    ca, sa = np.cos(angles), np.sin(angles)

    # Parallel-transport frames along the curve
    tangents = np.zeros_like(pts)
    tangents[0] = pts[1] - pts[0]
    tangents[-1] = pts[-1] - pts[-2]
    for i in range(1, m - 1):
        tangents[i] = pts[i + 1] - pts[i - 1]
    tangents /= np.maximum(np.linalg.norm(tangents, axis=1, keepdims=True), 1e-6)

    up_ref = np.array([0.0, 0.0, 1.0], dtype=np.float32)
    if abs(float(np.dot(tangents[0], up_ref))) > 0.9:
        up_ref = np.array([1.0, 0.0, 0.0], dtype=np.float32)
    side = np.cross(tangents[0], up_ref)
    side /= max(float(np.linalg.norm(side)), 1e-6)

    ring_pos, ring_nrm, ring_uv = [], [], []
    dist_accum = 0.0
    circ = 2.0 * math.pi * radius

    for i in range(m):
        if i > 0:
            dist_accum += float(np.linalg.norm(pts[i] - pts[i - 1]))
            # Project previous side orthogonal to new tangent
            side = side - float(np.dot(side, tangents[i])) * tangents[i]
            s_len = float(np.linalg.norm(side))
            if s_len < 1e-5:
                alt = np.array([1.0, 0.0, 0.0], dtype=np.float32)
                if abs(float(np.dot(tangents[i], alt))) > 0.9:
                    alt = np.array([0.0, 1.0, 0.0], dtype=np.float32)
                side = np.cross(tangents[i], alt)
                s_len = float(np.linalg.norm(side))
            side /= s_len
        binorm = np.cross(tangents[i], side)

        nrms = ca[:, None] * side[None, :] + sa[:, None] * binorm[None, :]
        p_ring = pts[i][None, :] + radius * nrms
        uv_ring = np.column_stack([(angles / (2.0 * math.pi)) * circ * uv_scale, np.full(n + 1, dist_accum * uv_scale, dtype=np.float32)])
        ring_pos.append(p_ring)
        ring_nrm.append(nrms)
        ring_uv.append(uv_ring)

    positions = np.vstack(ring_pos)
    normals = np.vstack(ring_nrm)
    uvs = np.vstack(ring_uv)
    tris = []
    stride = n + 1
    for j in range(m - 1):
        r0 = j * stride
        r1 = (j + 1) * stride
        for i in range(n):
            tris.append([r0 + i, r0 + i + 1, r1 + i + 1])
            tris.append([r0 + i, r1 + i + 1, r1 + i])
    indices = np.array(tris, dtype=np.uint32).ravel()
    return MeshPart(positions, normals, uvs, _color_array(len(positions), color), indices)


def make_pleated_curtain(
    width: float,
    height: float,
    amplitude: float = 0.045,
    pleats: float = 5.0,
    segs_w: int = 32,
    segs_h: int = 6,
    center: Sequence[float] = (0.0, 0.0, 0.0),
    color: Sequence[float] = (255, 255, 255, 255),
    uv_scale: float = 1.0,
) -> MeshPart:
    """Sinusoidally pleated curtain sheet facing +Z (use with doubleSided material or rotate as needed)."""
    uu = np.linspace(0.0, 1.0, segs_w + 1, dtype=np.float32)
    vv = np.linspace(0.0, 1.0, segs_h + 1, dtype=np.float32)
    ug, vg = np.meshgrid(uu, vv, indexing="xy")

    x = (ug - 0.5) * width
    y = (vg - 0.5) * height
    # Slight flare at the bottom for natural drapery weight
    flare = 1.0 + 0.18 * (1.0 - vg)
    phase = ug * pleats * 2.0 * math.pi
    z = np.sin(phase) * amplitude * flare

    dz_dx = (np.cos(phase) * amplitude * flare * pleats * 2.0 * math.pi) / max(width, 1e-4)
    nx = -dz_dx
    ny = np.zeros_like(nx)
    nz = np.ones_like(nx)
    inv_len = 1.0 / np.sqrt(nx * nx + nz * nz)
    nx *= inv_len
    nz *= inv_len

    positions = np.column_stack([x.ravel(), y.ravel(), z.ravel()]).astype(np.float32)
    normals = np.column_stack([nx.ravel(), ny.ravel(), nz.ravel()]).astype(np.float32)
    uvs = np.column_stack([(ug * width * uv_scale).ravel(), (vg * height * uv_scale).ravel()]).astype(np.float32)

    cols = segs_w + 1
    tris = []
    for j in range(segs_h):
        r0 = j * cols
        r1 = (j + 1) * cols
        for i in range(segs_w):
            tris.append([r0 + i, r0 + i + 1, r1 + i + 1])
            tris.append([r0 + i, r1 + i + 1, r1 + i])
    indices = np.array(tris, dtype=np.uint32).ravel()
    return MeshPart(positions, normals, uvs, _color_array(len(positions), color), indices).translate(center)


def make_flat_ring(
    r_in: float,
    r_out: float,
    y: float,
    n_segs: int = 48,
    color: Sequence[float] = (255, 255, 255, 255),
    uv_scale: float = 0.5,
    up_normal: bool = True,
) -> MeshPart:
    """Horizontal circular ring at height y with radial UVs."""
    th = np.linspace(0.0, 2.0 * math.pi, n_segs + 1, dtype=np.float32)
    st, ct = np.sin(th), -np.cos(th)
    p_in = np.column_stack([r_in * st, np.full(n_segs + 1, y, dtype=np.float32), r_in * ct])
    p_out = np.column_stack([r_out * st, np.full(n_segs + 1, y, dtype=np.float32), r_out * ct])
    positions = np.vstack([p_in, p_out])
    ny = 1.0 if up_normal else -1.0
    normals = np.tile(np.array([[0.0, ny, 0.0]], dtype=np.float32), (len(positions), 1))
    uvs = np.column_stack([positions[:, 0] * uv_scale + 0.5, positions[:, 2] * uv_scale + 0.5])

    stride = n_segs + 1
    tris = []
    for i in range(n_segs):
        if up_normal:
            tris.append([i, i + 1, stride + i + 1])
            tris.append([i, stride + i + 1, stride + i])
        else:
            tris.append([i, stride + i + 1, i + 1])
            tris.append([i, stride + i, stride + i + 1])
    return MeshPart(positions, normals, uvs, _color_array(len(positions), color), np.array(tris, dtype=np.uint32).ravel())


def make_solid_sector(
    r_in: float,
    r_out: float,
    y_top: float,
    y_bot: float,
    t_start: float,
    t_end: float,
    num_segs: int,
    color_top: Sequence[float],
    color_side: Sequence[float],
    uv_scale: float = 0.65,
) -> MeshPart:
    """Volumetric prismatic annular sector with sharp per-face normals and clean world-scale UVs."""
    parts = []
    th = np.linspace(t_start, t_end, num_segs + 1, dtype=np.float32)
    st, ct = np.sin(th), -np.cos(th)
    n_pts = num_segs + 1

    # Top (+Y)
    p_ti = np.column_stack([r_in * st, np.full(n_pts, y_top), r_in * ct]).astype(np.float32)
    p_to = np.column_stack([r_out * st, np.full(n_pts, y_top), r_out * ct]).astype(np.float32)
    pos_top = np.vstack([p_ti, p_to])
    nrm_top = np.tile(np.array([[0.0, 1.0, 0.0]], dtype=np.float32), (2 * n_pts, 1))
    uv_top = np.column_stack([pos_top[:, 0] * uv_scale + 0.5, pos_top[:, 2] * uv_scale + 0.5])
    tris_top = []
    for i in range(num_segs):
        tris_top.append([i, i + 1, n_pts + i + 1])
        tris_top.append([i, n_pts + i + 1, n_pts + i])
    parts.append(MeshPart(pos_top, nrm_top, uv_top, _color_array(len(pos_top), color_top), np.array(tris_top, dtype=np.uint32).ravel()))

    # Bottom (-Y)
    p_bi = np.column_stack([r_in * st, np.full(n_pts, y_bot), r_in * ct]).astype(np.float32)
    p_bo = np.column_stack([r_out * st, np.full(n_pts, y_bot), r_out * ct]).astype(np.float32)
    pos_bot = np.vstack([p_bi, p_bo])
    nrm_bot = np.tile(np.array([[0.0, -1.0, 0.0]], dtype=np.float32), (2 * n_pts, 1))
    uv_bot = np.column_stack([pos_bot[:, 0] * uv_scale + 0.5, pos_bot[:, 2] * uv_scale + 0.5])
    tris_bot = []
    for i in range(num_segs):
        tris_bot.append([i, n_pts + i + 1, i + 1])
        tris_bot.append([i, n_pts + i, n_pts + i + 1])
    parts.append(MeshPart(pos_bot, nrm_bot, uv_bot, _color_array(len(pos_bot), color_side), np.array(tris_bot, dtype=np.uint32).ravel()))

    # Outer curved wall (+radial)
    pos_out = np.vstack([p_to, p_bo])
    rad_out = np.column_stack([st, np.zeros(n_pts), ct]).astype(np.float32)
    nrm_out = np.vstack([rad_out, rad_out])
    arc_u = (th - t_start) * r_out * uv_scale
    uv_out = np.vstack([
        np.column_stack([arc_u, np.full(n_pts, y_top * uv_scale)]),
        np.column_stack([arc_u, np.full(n_pts, y_bot * uv_scale)]),
    ]).astype(np.float32)
    tris_out = []
    for i in range(num_segs):
        tris_out.append([i, i + 1, n_pts + i + 1])
        tris_out.append([i, n_pts + i + 1, n_pts + i])
    parts.append(MeshPart(pos_out, nrm_out, uv_out, _color_array(len(pos_out), color_side), np.array(tris_out, dtype=np.uint32).ravel()))

    # Inner curved wall (-radial)
    pos_in = np.vstack([p_ti, p_bi])
    rad_in = -rad_out
    nrm_in = np.vstack([rad_in, rad_in])
    arc_ui = (th - t_start) * r_in * uv_scale
    uv_in = np.vstack([
        np.column_stack([arc_ui, np.full(n_pts, y_top * uv_scale)]),
        np.column_stack([arc_ui, np.full(n_pts, y_bot * uv_scale)]),
    ]).astype(np.float32)
    tris_in = []
    for i in range(num_segs):
        tris_in.append([i, n_pts + i + 1, i + 1])
        tris_in.append([i, n_pts + i, n_pts + i + 1])
    parts.append(MeshPart(pos_in, nrm_in, uv_in, _color_array(len(pos_in), color_side), np.array(tris_in, dtype=np.uint32).ravel()))

    # End caps (2 radial quads)
    def radial_cap(idx: int, sign: float) -> MeshPart:
        t = float(th[idx])
        # Tangent vector along increasing theta is (cos(t), 0, sin(t))
        n_vec = np.array([math.cos(t) * sign, 0.0, math.sin(t) * sign], dtype=np.float32)
        q = np.array([p_ti[idx], p_to[idx], p_bo[idx], p_bi[idx]], dtype=np.float32)
        nrms = np.tile(n_vec[None, :], (4, 1))
        uvs = np.array([[0.0, y_top], [r_out - r_in, y_top], [r_out - r_in, y_bot], [0.0, y_bot]], dtype=np.float32) * uv_scale
        tris = np.array([0, 2, 1, 0, 3, 2], dtype=np.uint32) if sign < 0 else np.array([0, 1, 2, 0, 2, 3], dtype=np.uint32)
        return MeshPart(q, nrms, uvs, _color_array(4, color_side), tris)

    parts.append(radial_cap(0, -1.0))
    parts.append(radial_cap(num_segs, 1.0))
    return concat_parts(parts)


def make_profile_ring(
    profile: Sequence[Tuple[float, float]],
    n_segs: int,
    color: Sequence[float],
    uv_scale: float = 0.5,
) -> MeshPart:
    """Surface of revolution from a 2D (r, y) profile with per-segment sharp normals and clean UVs."""
    th = np.linspace(0.0, 2.0 * math.pi, n_segs + 1, dtype=np.float32)
    st, ct = np.sin(th), -np.cos(th)
    parts = []
    v_accum = 0.0
    for p in range(len(profile) - 1):
        r0, y0 = float(profile[p][0]), float(profile[p][1])
        r1, y1 = float(profile[p + 1][0]), float(profile[p + 1][1])
        dr, dy = r1 - r0, y1 - y0
        seg_len = math.hypot(dr, dy)
        if seg_len < 1e-6:
            continue
        # Outward normal in (r, y) plane perpendicular to (dr, dy)
        nr = -dy / seg_len
        ny = dr / seg_len
        p0 = np.column_stack([r0 * st, np.full(n_segs + 1, y0), r0 * ct]).astype(np.float32)
        p1 = np.column_stack([r1 * st, np.full(n_segs + 1, y1), r1 * ct]).astype(np.float32)
        n_ring = np.column_stack([nr * st, np.full(n_segs + 1, ny), nr * ct]).astype(np.float32)
        r_avg = 0.5 * (r0 + r1)
        u_ring = (th * r_avg) * uv_scale
        uv0 = np.column_stack([u_ring, np.full(n_segs + 1, v_accum * uv_scale)]).astype(np.float32)
        v_accum += seg_len
        uv1 = np.column_stack([u_ring, np.full(n_segs + 1, v_accum * uv_scale)]).astype(np.float32)

        stride = n_segs + 1
        tris = []
        for i in range(n_segs):
            tris.append([i, i + 1, stride + i + 1])
            tris.append([i, stride + i + 1, stride + i])
        parts.append(MeshPart(
            np.vstack([p0, p1]),
            np.vstack([n_ring, n_ring]),
            np.vstack([uv0, uv1]),
            _color_array(2 * stride, color),
            np.array(tris, dtype=np.uint32).ravel(),
        ))
    return concat_parts(parts)


# ==============================================================================
# AABB Geometric Collision Checker (guarantees zero interpenetration)
# ==============================================================================
class CollisionChecker:
    """Tracks world-space AABBs of named logical scene groups and fails if any distinct groups overlap."""

    def __init__(self) -> None:
        # Each entry: (group_name, min_xyz, max_xyz, min_r, max_r)
        self._boxes: List[Tuple[str, np.ndarray, np.ndarray, float, float]] = []
        self._allowed: set = set()

    def allow(self, group_a: str, group_b: str) -> None:
        self._allowed.add(tuple(sorted((group_a, group_b))))

    def register(self, group_name: str, part: MeshPart) -> MeshPart:
        if len(part.positions) > 0:
            bmin = part.positions.min(axis=0).astype(np.float64)
            bmax = part.positions.max(axis=0).astype(np.float64)
            radii = np.hypot(part.positions[:, 0].astype(np.float64), part.positions[:, 2].astype(np.float64))
            # If the box encloses the origin in XZ, true min radial distance is 0.0
            encloses_origin = (bmin[0] <= 0.0 <= bmax[0]) and (bmin[2] <= 0.0 <= bmax[2]) and (len(part.positions) <= 24)
            r_min = 0.0 if encloses_origin else float(radii.min())
            r_max = float(radii.max())
            self._boxes.append((group_name, bmin, bmax, r_min, r_max))
        return part

    def register_box(self, group_name: str, bmin: Sequence[float], bmax: Sequence[float]) -> None:
        mn = np.asarray(bmin, dtype=np.float64)
        mx = np.asarray(bmax, dtype=np.float64)
        corners_r = [math.hypot(x, z) for x in (mn[0], mx[0]) for z in (mn[2], mx[2])]
        r_min = 0.0 if (mn[0] <= 0.0 <= mx[0] and mn[2] <= 0.0 <= mx[2]) else min(corners_r)
        self._boxes.append((group_name, mn, mx, r_min, max(corners_r)))

    def find_collisions(self, tolerance: float = 0.002, radial_check: bool = False) -> List[str]:
        """Returns human-readable collision descriptions for any distinct groups overlapping by > tolerance (m)."""
        collisions: List[str] = []
        n = len(self._boxes)
        for i in range(n):
            name_a, min_a, max_a, rmin_a, rmax_a = self._boxes[i]
            for j in range(i + 1, n):
                name_b, min_b, max_b, rmin_b, rmax_b = self._boxes[j]
                if name_a == name_b:
                    continue
                if tuple(sorted((name_a, name_b))) in self._allowed:
                    continue
                ox = min(max_a[0], max_b[0]) - max(min_a[0], min_b[0])
                if ox <= tolerance:
                    continue
                oy = min(max_a[1], max_b[1]) - max(min_a[1], min_b[1])
                if oy <= tolerance:
                    continue
                oz = min(max_a[2], max_b[2]) - max(min_a[2], min_b[2])
                if oz <= tolerance:
                    continue
                if radial_check:
                    # Exact radial separating axis for concentric circular/annular geometry
                    orad = min(rmax_a, rmax_b) - max(rmin_a, rmin_b)
                    if orad <= tolerance:
                        continue
                collisions.append(
                    f"Collision: '{name_a}' <-> '{name_b}' overlap = "
                    f"({ox * 1000:.1f}mm, {oy * 1000:.1f}mm, {oz * 1000:.1f}mm)"
                )
        return collisions

    def assert_no_collisions(self, scene_name: str, tolerance: float = 0.002, radial_check: bool = False) -> None:
        hits = self.find_collisions(tolerance=tolerance, radial_check=radial_check)
        if hits:
            summary = "\n  ".join(hits[:25])
            extra = f"\n  ... and {len(hits) - 25} more" if len(hits) > 25 else ""
            raise AssertionError(
                f"[{scene_name}] Found {len(hits)} geometric AABB collision(s) (> {tolerance * 1000:.1f}mm):\n"
                f"  {summary}{extra}"
            )
        print(f"  [CollisionChecker] {scene_name}: 0 collisions verified across {len(self._boxes)} tracked volumes (tol={tolerance * 1000:.1f}mm).")


# ==============================================================================
# Procedural PBR Texture Generators (512x512 Baked-Relief BaseColor sRGB + ORM Linear)
# ==============================================================================
def _tileable_fbm(size: int, seed: int, octaves: int = 4, base_freq: int = 4) -> np.ndarray:
    """Fast tileable 2D value-noise fBm via periodic trigonometric/grid interpolation."""
    rng = np.random.default_rng(seed)
    acc = np.zeros((size, size), dtype=np.float32)
    amp = 0.5
    total_amp = 0.0
    coords = np.linspace(0.0, 1.0, size, endpoint=False, dtype=np.float32)
    gx, gy = np.meshgrid(coords, coords, indexing="xy")

    for o in range(octaves):
        freq = base_freq * (2 ** o)
        grid = rng.random((freq, freq), dtype=np.float32)
        fx = gx * freq
        fy = gy * freq
        ix0 = np.floor(fx).astype(np.int32) % freq
        iy0 = np.floor(fy).astype(np.int32) % freq
        ix1 = (ix0 + 1) % freq
        iy1 = (iy0 + 1) % freq
        tx = fx - np.floor(fx)
        ty = fy - np.floor(fy)
        # Smoothstep
        sx = tx * tx * (3.0 - 2.0 * tx)
        sy = ty * ty * (3.0 - 2.0 * ty)
        v00 = grid[iy0, ix0]
        v10 = grid[iy0, ix1]
        v01 = grid[iy1, ix0]
        v11 = grid[iy1, ix1]
        val = (v00 * (1 - sx) + v10 * sx) * (1 - sy) + (v01 * (1 - sx) + v11 * sx) * sy
        acc += val * amp
        total_amp += amp
        amp *= 0.5
    return acc / max(total_amp, 1e-6)


def _tileable_cellular(size: int, cells: int, seed: int) -> np.ndarray:
    """Fast tileable Worley/cellular dome heightmap (1.0 at cell centers, 0.0 at creases) for pebbled leather."""
    rng = np.random.default_rng(seed)
    pts_x = rng.uniform(0.12, 0.88, size=(cells, cells)).astype(np.float32)
    pts_y = rng.uniform(0.12, 0.88, size=(cells, cells)).astype(np.float32)

    u = np.linspace(0.0, float(cells), size, endpoint=False, dtype=np.float32)
    gx, gy = np.meshgrid(u, u, indexing="xy")
    cx = np.floor(gx).astype(np.int32)
    cy = np.floor(gy).astype(np.int32)
    fx = gx - cx
    fy = gy - cy

    d1 = np.full((size, size), 10.0, dtype=np.float32)
    d2 = np.full((size, size), 10.0, dtype=np.float32)
    for dy in (-1, 0, 1):
        ny = (cy + dy) % cells
        for dx in (-1, 0, 1):
            nx = (cx + dx) % cells
            px = dx + pts_x[ny, nx] - fx
            py = dy + pts_y[ny, nx] - fy
            dist = np.sqrt(px * px + py * py)
            closer = dist < d1
            d2 = np.where(closer, d1, np.minimum(d2, dist))
            d1 = np.where(closer, dist, d1)
    # Edge distance (d2 - d1) is 0 right in the crease and positive inside each leather pebble dome
    edge = np.clip((d2 - d1) * 2.4, 0.0, 1.0)
    return np.power(edge, 0.65).astype(np.float32)


def _bake_directional_relief(
    height: np.ndarray,
    strength: float = 0.30,
    light_dir: Tuple[float, float] = (-0.55, -0.75),
    gain: float = 4.0,
) -> np.ndarray:
    """
    Computes periodic 2D Sobel gradients from `height` [0..1] and bakes directional micro-lighting
    into a multiplier around 1.0. Gives crisp tactile relief on stationary Quest 3 rooms at ZERO extra GPU cost.
    """
    h = np.asarray(height, dtype=np.float32)
    # Periodic 3x3 Sobel kernel (seamless across tile boundaries)
    tl = np.roll(np.roll(h, 1, axis=0), 1, axis=1)
    tc = np.roll(h, 1, axis=0)
    tr = np.roll(np.roll(h, 1, axis=0), -1, axis=1)
    ml = np.roll(h, 1, axis=1)
    mr = np.roll(h, -1, axis=1)
    bl = np.roll(np.roll(h, -1, axis=0), 1, axis=1)
    bc = np.roll(h, -1, axis=0)
    br = np.roll(np.roll(h, -1, axis=0), -1, axis=1)

    gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl)
    gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr)

    lx, ly = float(light_dir[0]), float(light_dir[1])
    norm = math.hypot(lx, ly)
    if norm > 1e-6:
        lx /= norm
        ly /= norm

    # Surface normal slope dot light direction
    dot = -(gx * lx + gy * ly)
    relief = np.tanh(dot * gain)
    return (1.0 + strength * relief).astype(np.float32)


def make_orm_image(ao: np.ndarray, roughness: np.ndarray, metallic: np.ndarray) -> Image.Image:
    """Packs linear AO (R), Roughness (G), Metallic (B) into an RGB PIL Image."""
    h, w = ao.shape
    arr = np.zeros((h, w, 3), dtype=np.uint8)
    arr[:, :, 0] = np.clip(ao * 255.0, 0, 255).astype(np.uint8)
    arr[:, :, 1] = np.clip(roughness * 255.0, 0, 255).astype(np.uint8)
    arr[:, :, 2] = np.clip(metallic * 255.0, 0, 255).astype(np.uint8)
    return Image.fromarray(arr, mode="RGB")


def tex_fabric(
    base_rgb: Sequence[int],
    size: int = 512,
    seed: int = 11,
    weave_freq: int = 28,
    roughness: float = 0.86,
    contrast: float = 1.0,
) -> Tuple[Image.Image, Image.Image]:
    """512x512 tactile woven linen / velvet / bouclé upholstery with baked warp-weft thread relief."""
    macro = _tileable_fbm(size, seed=seed, octaves=4, base_freq=4)
    slub = _tileable_fbm(size, seed=seed + 13, octaves=3, base_freq=18)

    u = np.linspace(0.0, float(weave_freq), size, endpoint=False, dtype=np.float32)
    xx, yy = np.meshgrid(u, u, indexing="xy")

    # Authentic over/under basket-twill yarn heightmap at VR-legible scale
    ix = np.floor(xx).astype(np.int32)
    iy = np.floor(yy).astype(np.int32)
    fx = xx - ix
    fy = yy - iy
    warp_arch = np.sin(fx * math.pi)
    weft_arch = np.sin(fy * math.pi)
    # 2x2 twill selector creates realistic diagonal woven fabric structure
    twill_phase = ((ix + iy) % 4) < 2
    yarn_h = np.where(twill_phase, warp_arch * (0.65 + 0.35 * weft_arch), weft_arch * (0.65 + 0.35 * warp_arch))
    # Subtle micro-thread ridges along each yarn
    micro_strand = 0.5 + 0.5 * np.where(twill_phase, np.sin(fy * 4.0 * math.pi), np.sin(fx * 4.0 * math.pi))
    height = np.clip(0.55 * yarn_h + 0.25 * macro + 0.12 * slub + 0.08 * micro_strand, 0.0, 1.0)

    relief = _bake_directional_relief(height, strength=0.20 * contrast, gain=3.0)
    # Soft natural fabric nap + fine thread shading (crisp upholstery, never chunky wicker)
    tone = (0.82 + 0.24 * macro + 0.12 * (yarn_h - 0.5) * contrast) * relief
    base = np.asarray(base_rgb, dtype=np.float32).reshape(1, 1, 3)
    rgb = np.clip(base * tone[:, :, None], 0, 255).astype(np.uint8)

    ao = np.clip(0.76 + 0.24 * yarn_h * (0.86 + 0.14 * macro), 0.64, 1.0)
    rough = np.clip(roughness + (0.5 - yarn_h) * 0.14 + (0.5 - macro) * 0.06, 0.55, 0.98)
    metal = np.zeros((size, size), dtype=np.float32)
    return Image.fromarray(rgb, mode="RGB"), make_orm_image(ao, rough, metal)


def tex_carpet_artdeco(
    base_rgb: Sequence[int],
    accent_rgb: Sequence[int],
    size: int = 512,
    seed: int = 22,
) -> Tuple[Image.Image, Image.Image]:
    """512x512 tufted Art Deco diamond-lattice theater carpet with baked pile texture and pattern relief."""
    macro = _tileable_fbm(size, seed=seed, octaves=4, base_freq=6)
    pile = _tileable_fbm(size, seed=seed + 5, octaves=3, base_freq=32)

    u = np.linspace(0.0, 4.0, size, endpoint=False, dtype=np.float32)
    xx, yy = np.meshgrid(u, u, indexing="xy")

    # Crisp double-line Art Deco diamond lattice + small central medallion dots
    d1 = np.abs(np.mod(xx + yy, 1.0) - 0.5)
    d2 = np.abs(np.mod(xx - yy, 1.0) - 0.5)
    d_min = np.minimum(d1, d2)
    outer_line = np.clip(1.0 - np.abs(d_min - 0.038) * 34.0, 0.0, 1.0)
    inner_line = np.clip(1.0 - d_min * 26.0, 0.0, 1.0)
    # Corner diamond pips
    cx = np.mod(xx + 0.5, 1.0) - 0.5
    cy = np.mod(yy + 0.5, 1.0) - 0.5
    pip = np.clip(1.0 - (np.abs(cx) + np.abs(cy)) * 11.0, 0.0, 1.0)
    pattern = np.clip(inner_line * 0.85 + outer_line * 0.55 + pip * 0.70, 0.0, 1.0)

    # Raised tufted carpet pile heightmap
    height = np.clip(0.45 * pile + 0.25 * macro + 0.30 * pattern, 0.0, 1.0)
    relief = _bake_directional_relief(height, strength=0.34, gain=4.5)

    b = np.asarray(base_rgb, dtype=np.float32).reshape(1, 1, 3)
    a = np.asarray(accent_rgb, dtype=np.float32).reshape(1, 1, 3)
    col = (b * (1.0 - pattern[:, :, None] * 0.78) + a * (pattern[:, :, None] * 0.78))
    col *= (0.76 + 0.34 * macro[:, :, None] + 0.14 * (pile[:, :, None] - 0.5)) * relief[:, :, None]

    ao = np.clip(0.68 + 0.26 * pile + 0.10 * pattern, 0.55, 1.0)
    rough = np.clip(0.92 + (0.5 - pile) * 0.08, 0.82, 0.99)
    metal = np.zeros((size, size), dtype=np.float32)
    return Image.fromarray(np.clip(col, 0, 255).astype(np.uint8), mode="RGB"), make_orm_image(ao, rough, metal)


def tex_wood(
    base_rgb: Sequence[int],
    grain_rgb: Sequence[int],
    size: int = 512,
    seed: int = 33,
    planks: int = 4,
    roughness: float = 0.40,
    staggered_joints: bool = True,
) -> Tuple[Image.Image, Image.Image]:
    """512x512 walnut/oak planks with per-plank grain offsets, V-groove seams, pores, and baked bevel relief."""
    rng = np.random.default_rng(seed + 101)
    fbm_a = _tileable_fbm(size, seed=seed, octaves=4, base_freq=4)
    fbm_fine = _tileable_fbm(size, seed=seed + 17, octaves=3, base_freq=20)
    u = np.linspace(0.0, 1.0, size, endpoint=False, dtype=np.float32)
    v = np.linspace(0.0, 1.0, size, endpoint=False, dtype=np.float32)
    xx, yy = np.meshgrid(u, v, indexing="xy")

    # Horizontal plank rows along Y with independent grain phase & tone per plank
    seam_v = np.zeros((size, size), dtype=np.float32)
    seam_h = np.zeros((size, size), dtype=np.float32)
    plank_shade = np.ones((size, size), dtype=np.float32)
    phase_shift = np.zeros((size, size), dtype=np.float32)

    if planks > 1:
        py = yy * planks
        frac_y = py - np.floor(py)
        plank_idx = np.floor(py).astype(np.int32) % planks
        # Smooth V-groove seam width (~2.4% of plank width)
        edge_y = np.minimum(frac_y, 1.0 - frac_y)
        seam_h = np.clip(1.0 - edge_y / 0.024, 0.0, 1.0)

        shades = rng.uniform(0.84, 1.14, size=planks).astype(np.float32)
        phases = rng.uniform(0.0, 6.28, size=planks).astype(np.float32)
        plank_shade = shades[plank_idx]
        phase_shift = phases[plank_idx]

        if staggered_joints:
            joint_x = (rng.uniform(0.15, 0.85, size=planks) + np.arange(planks) * 0.37) % 1.0
            jx = joint_x[plank_idx]
            dx_j = np.abs(((xx - jx + 0.5) % 1.0) - 0.5)
            seam_v = np.clip(1.0 - dx_j / 0.0055, 0.0, 1.0)
            # Slight tone difference on either side of the butt joint
            after_joint = (xx > jx).astype(np.float32)
            plank_shade = plank_shade * (0.95 + 0.10 * after_joint)

    seam = np.maximum(seam_h, seam_v)

    # Organic flowing wood grain rings + longitudinal fiber streaks
    ring_coord = yy * 26.0 + fbm_a * 5.2 + phase_shift + 0.35 * np.sin(xx * 2.0 * math.pi + phase_shift)
    ring_wave = 0.5 + 0.5 * np.sin(ring_coord * math.pi)
    # Sharpen dark latewood bands for realistic walnut figure
    grain = np.power(ring_wave, 1.85) * 0.62 + fbm_a * 0.24 + fbm_fine * 0.14
    # Fine horizontal pores stretched along X
    pore_row = np.roll(fbm_fine, 3, axis=1) * 0.5 + fbm_fine * 0.5
    pores = np.clip((0.36 - pore_row) * 3.0, 0.0, 1.0) * 0.28

    # Heightmap: raised planks with beveled V-grooves, subtle grain ridges, and recessed pores
    height = np.clip(0.82 * (1.0 - 0.85 * seam) + 0.14 * (1.0 - grain) - 0.12 * pores, 0.0, 1.0)
    relief = _bake_directional_relief(height, strength=0.34, light_dir=(-0.35, -0.92), gain=4.2)

    b = np.asarray(base_rgb, dtype=np.float32).reshape(1, 1, 3)
    g = np.asarray(grain_rgb, dtype=np.float32).reshape(1, 1, 3)
    col = (b * (1.0 - grain[:, :, None]) + g * grain[:, :, None]) * plank_shade[:, :, None]
    col *= (1.0 - 0.22 * pores[:, :, None]) * (1.0 - 0.68 * seam[:, :, None]) * relief[:, :, None]

    ao = np.clip(1.0 - 0.58 * seam - 0.14 * pores - 0.06 * grain, 0.38, 1.0)
    rough = np.clip(roughness + 0.16 * grain + 0.18 * pores + 0.42 * seam, 0.20, 0.94)
    metal = np.zeros((size, size), dtype=np.float32)
    return Image.fromarray(np.clip(col, 0, 255).astype(np.uint8), mode="RGB"), make_orm_image(ao, rough, metal)


def tex_leather(
    base_rgb: Sequence[int],
    size: int = 512,
    seed: int = 44,
    roughness: float = 0.38,
) -> Tuple[Image.Image, Image.Image]:
    """512x512 genuine pebbled leather with cellular domes, creases, and baked micro-highlights."""
    pebbles = _tileable_cellular(size, cells=36, seed=seed)
    creases = _tileable_fbm(size, seed=seed + 7, octaves=4, base_freq=5)
    fine = _tileable_fbm(size, seed=seed + 19, octaves=2, base_freq=24)

    height = np.clip(0.62 * pebbles + 0.26 * creases + 0.12 * fine, 0.0, 1.0)
    relief = _bake_directional_relief(height, strength=0.36, gain=4.5)

    # Subtle sheen on pebble crowns, darker crevices
    mod = (0.76 + 0.28 * pebbles + 0.14 * (creases - 0.5)) * relief
    b = np.asarray(base_rgb, dtype=np.float32).reshape(1, 1, 3)
    col = np.clip(b * mod[:, :, None], 0, 255).astype(np.uint8)
    ao = np.clip(0.66 + 0.34 * pebbles, 0.58, 1.0)
    rough = np.clip(roughness + (1.0 - pebbles) * 0.24 + (0.5 - creases) * 0.08, 0.24, 0.78)
    metal = np.zeros((size, size), dtype=np.float32)
    return Image.fromarray(col, mode="RGB"), make_orm_image(ao, rough, metal)


def tex_plaster(
    base_rgb: Sequence[int],
    size: int = 512,
    seed: int = 55,
    roughness: float = 0.90,
) -> Tuple[Image.Image, Image.Image]:
    """512x512 tactile Venetian architectural plaster with trowel sweeps and mineral grain relief."""
    trowel = _tileable_fbm(size, seed=seed, octaves=4, base_freq=4)
    grain = _tileable_fbm(size, seed=seed + 9, octaves=3, base_freq=18)
    sand = _tileable_fbm(size, seed=seed + 23, octaves=2, base_freq=48)

    height = np.clip(0.52 * trowel + 0.30 * grain + 0.18 * sand, 0.0, 1.0)
    relief = _bake_directional_relief(height, strength=0.28, gain=4.2)

    mod = (0.82 + 0.26 * trowel + 0.12 * (grain - 0.5) + 0.08 * (sand - 0.5)) * relief
    b = np.asarray(base_rgb, dtype=np.float32).reshape(1, 1, 3)
    col = np.clip(b * mod[:, :, None], 0, 255).astype(np.uint8)
    ao = np.clip(0.80 + 0.20 * (0.6 * trowel + 0.4 * grain), 0.70, 1.0)
    rough = np.clip(roughness + (0.5 - trowel) * 0.12, 0.74, 0.98)
    metal = np.zeros((size, size), dtype=np.float32)
    return Image.fromarray(col, mode="RGB"), make_orm_image(ao, rough, metal)


def tex_acoustic_ribbed(
    base_rgb: Sequence[int],
    groove_rgb: Sequence[int],
    size: int = 512,
    seed: int = 58,
    ribs: int = 16,
    roughness: float = 0.88,
) -> Tuple[Image.Image, Image.Image]:
    """
    512x512 vertical fluted/ribbed acoustic fabric wall panel for the Cinema auditorium.
    Bakes crisp 3D vertical ribs + deep sound-absorbing shadow grooves directly into baseColor/ORM
    for dramatic architectural depth at ZERO extra triangles or shader cost.
    """
    u = np.linspace(0.0, float(ribs), size, endpoint=False, dtype=np.float32)
    v = np.linspace(0.0, 1.0, size, endpoint=False, dtype=np.float32)
    xx, yy = np.meshgrid(u, v, indexing="xy")

    fx = xx - np.floor(xx)
    # Flat-crowned beveled vertical rib: 0..0.18 & 0.82..1.0 = recessed groove, 0.22..0.78 = fabric rib face
    dist_c = np.abs(fx - 0.5)
    rib_profile = np.clip((0.36 - dist_c) / 0.10, 0.0, 1.0)
    # Softly crown the rib center for sculpted catch-light
    rib_crown = rib_profile * (0.88 + 0.12 * np.cos((fx - 0.5) * math.pi / 0.36))

    # Fine acoustic fabric weave on top of the ribs
    macro = _tileable_fbm(size, seed=seed, octaves=4, base_freq=5)
    weave_u = np.sin(xx * 6.0 * math.pi) * np.cos(yy * float(ribs) * 6.0 * math.pi)
    fabric_tex = 0.5 + 0.25 * weave_u + 0.25 * macro

    height = np.clip(0.82 * rib_crown + 0.18 * fabric_tex * rib_profile, 0.0, 1.0)
    relief = _bake_directional_relief(height, strength=0.40, light_dir=(-0.82, -0.35), gain=3.4)

    b = np.asarray(base_rgb, dtype=np.float32).reshape(1, 1, 3)
    g = np.asarray(groove_rgb, dtype=np.float32).reshape(1, 1, 3)
    col = g * (1.0 - rib_profile[:, :, None]) + b * rib_profile[:, :, None]
    col *= (0.84 + 0.24 * fabric_tex[:, :, None]) * relief[:, :, None]

    ao = np.clip(0.38 + 0.62 * rib_profile * (0.86 + 0.14 * fabric_tex), 0.35, 1.0)
    rough = np.clip(roughness + (1.0 - rib_profile) * 0.09, 0.78, 0.99)
    metal = np.zeros((size, size), dtype=np.float32)
    return Image.fromarray(np.clip(col, 0, 255).astype(np.uint8), mode="RGB"), make_orm_image(ao, rough, metal)


def tex_brushed_alloy(
    base_rgb: Sequence[int],
    size: int = 512,
    seed: int = 66,
    roughness: float = 0.36,
    metallic: float = 0.58,
) -> Tuple[Image.Image, Image.Image]:
    """512x512 brushed aerospace titanium hull panels with beveled seams, micro-rivets, and baked relief."""
    rng = np.random.default_rng(seed)
    # Multi-scale horizontal brushed grain
    row_fine = rng.uniform(-1.0, 1.0, size=(size, 1)).astype(np.float32)
    row_med = np.repeat(rng.uniform(-1.0, 1.0, size=(size // 4, 1)).astype(np.float32), 4, axis=0)
    fbm = _tileable_fbm(size, seed=seed + 3, octaves=4, base_freq=6)
    streaks = (
        np.repeat(row_fine, size, axis=1) * 0.075
        + np.repeat(row_med, size, axis=1) * 0.065
        + (fbm - 0.5) * 0.14
    )

    # 2x2 beveled titanium panel grid with subtle tonal variation per panel
    u = np.linspace(0.0, 2.0, size, endpoint=False, dtype=np.float32)
    xx, yy = np.meshgrid(u, u, indexing="xy")
    fu = np.mod(xx, 1.0)
    fv = np.mod(yy, 1.0)
    px_i = np.floor(xx).astype(np.int32) % 2
    py_i = np.floor(yy).astype(np.int32) % 2
    panel_shades = np.array([[0.93, 1.05], [1.04, 0.96]], dtype=np.float32)
    panel_tone = panel_shades[py_i, px_i]

    edge_u = np.minimum(fu, 1.0 - fu)
    edge_v = np.minimum(fv, 1.0 - fv)
    edge_d = np.minimum(edge_u, edge_v)
    # Smooth beveled groove (~1.8% of panel width) + subtle machined chamfer highlight near edge
    seam = np.clip(1.0 - edge_d / 0.018, 0.0, 1.0)
    edge_wear = np.exp(-((edge_d - 0.028) ** 2) / (2.0 * 0.010 ** 2)) * 0.14

    # Countersunk aerospace micro-rivets in each panel corner
    rivets = np.zeros((size, size), dtype=np.float32)
    for rx in (0.08, 0.92):
        for ry in (0.08, 0.92):
            d2 = (fu - rx) ** 2 + (fv - ry) ** 2
            # Dark recessed ring around a slightly raised fastener head
            ring = np.exp(-((np.sqrt(d2) - 0.022) ** 2) / (2.0 * 0.005 ** 2))
            rivets = np.maximum(rivets, ring)

    height = np.clip(0.84 * (1.0 - 0.85 * seam) - 0.35 * rivets + 0.12 * streaks, 0.0, 1.0)
    relief = _bake_directional_relief(height, strength=0.38, light_dir=(-0.60, -0.75), gain=4.2)

    b = np.asarray(base_rgb, dtype=np.float32).reshape(1, 1, 3)
    col = (
        b
        * panel_tone[:, :, None]
        * (1.0 + streaks[:, :, None] + edge_wear[:, :, None])
        * (1.0 - 0.62 * seam[:, :, None])
        * (1.0 - 0.38 * rivets[:, :, None])
        * relief[:, :, None]
    )
    ao = np.clip(1.0 - 0.58 * seam - 0.30 * rivets, 0.36, 1.0)
    rough = np.clip(roughness + streaks * 0.45 + 0.36 * seam + 0.15 * rivets - edge_wear * 0.4, 0.18, 0.88)
    metal = np.clip(np.full((size, size), metallic, dtype=np.float32) * (1.0 - 0.55 * seam), 0.15, 0.90)
    return Image.fromarray(np.clip(col, 0, 255).astype(np.uint8), mode="RGB"), make_orm_image(ao, rough, metal)


def tex_carbon_deck(base_rgb: Sequence[int], size: int = 512, seed: int = 77) -> Tuple[Image.Image, Image.Image]:
    """512x512 crisp 2x2 twill carbon-fiber deck plating with baked directional tow sheen."""
    u = np.linspace(0.0, 32.0, size, endpoint=False, dtype=np.float32)
    xx, yy = np.meshgrid(u, u, indexing="xy")
    ix = np.floor(xx).astype(np.int32)
    iy = np.floor(yy).astype(np.int32)
    fx = xx - ix
    fy = yy - iy

    # Authentic 2x2 diagonal twill carbon weave
    is_warp = ((ix + iy) % 4) < 2
    warp_h = np.sin(fx * math.pi) * (0.70 + 0.30 * np.sin(fy * math.pi))
    weft_h = np.sin(fy * math.pi) * (0.70 + 0.30 * np.sin(fx * math.pi))
    tow_h = np.where(is_warp, warp_h, weft_h)
    # Alternating anisotropic luminance between horizontal and vertical carbon tows
    tow_sheen = np.where(is_warp, 1.16, 0.82).astype(np.float32)

    fbm = _tileable_fbm(size, seed=seed, octaves=3, base_freq=6)
    height = np.clip(0.78 * tow_h + 0.22 * fbm, 0.0, 1.0)
    relief = _bake_directional_relief(height, strength=0.34, gain=3.8)

    b = np.asarray(base_rgb, dtype=np.float32).reshape(1, 1, 3)
    col = b * (0.72 + 0.36 * tow_h[:, :, None]) * tow_sheen[:, :, None] * relief[:, :, None]
    ao = np.clip(0.68 + 0.32 * tow_h, 0.58, 1.0)
    rough = np.clip(0.36 + 0.22 * (1.0 - tow_h), 0.24, 0.68)
    metal = np.full((size, size), 0.24, dtype=np.float32)
    return Image.fromarray(np.clip(col, 0, 255).astype(np.uint8), mode="RGB"), make_orm_image(ao, rough, metal)


def tex_night_window_panorama(width: int = 2048, height: int = 1024, seed: int = 88) -> Image.Image:
    """
    High-resolution 2048x1024 twilight-to-midnight architectural skyline & mountain horizon panorama
    for the Living Room window backdrop (~3mm/texel -> Quest 3 native PPD sharpness).
    """
    rng = np.random.default_rng(seed)
    u = np.linspace(0.0, 1.0, width, endpoint=False, dtype=np.float32)
    v = np.linspace(0.0, 1.0, height, dtype=np.float32)  # 0=top, 1=bottom
    uu, vv = np.meshgrid(u, v, indexing="xy")

    # 1. Deep indigo zenith -> cobalt mid -> subtle warm amber-violet twilight horizon
    sky_top = np.array([7.0, 12.0, 28.0], dtype=np.float32)
    sky_mid = np.array([20.0, 32.0, 62.0], dtype=np.float32)
    sky_hor = np.array([104.0, 66.0, 62.0], dtype=np.float32)

    t1 = np.clip(vv / 0.54, 0.0, 1.0)[:, :, None]
    t2 = np.clip((vv - 0.44) / 0.30, 0.0, 1.0)[:, :, None]
    sky = sky_top * (1.0 - t1) + sky_mid * t1
    sky = sky * (1.0 - t2) + sky_hor * t2

    # Subtle urban light pollution glow dome right behind the city skyline
    city_glow = np.exp(-((vv - 0.72) ** 2) / (2.0 * 0.11 ** 2)) * (0.55 + 0.45 * np.exp(-((uu - 0.52) ** 2) / (2.0 * 0.28 ** 2)))
    sky += city_glow[:, :, None] * np.array([46.0, 28.0, 18.0], dtype=np.float32)

    # Smooth high-altitude twilight cirrus clouds
    clouds_raw = _tileable_fbm(512, seed=seed + 1, octaves=5, base_freq=3)
    clouds_img = np.array(
        Image.fromarray(np.clip(clouds_raw * 255.0, 0, 255).astype(np.uint8)).resize((width, height), Image.BICUBIC),
        dtype=np.float32,
    ) / 255.0
    cloud_band = np.exp(-((vv - 0.48) ** 2) / (2.0 * 0.14 ** 2)) * np.clip(clouds_img - 0.38, 0.0, 1.0)
    sky += cloud_band[:, :, None] * np.array([54.0, 40.0, 52.0], dtype=np.float32)
    img = sky.copy()

    # 2. Crisp anti-aliased crescent moon in upper-left sky with soft atmospheric halo
    aspect = float(width) / float(height)
    mx, my = 0.27, 0.21
    dx_m = (uu - mx) * aspect
    dy_m = vv - my
    dist_m = np.sqrt(dx_m * dx_m + dy_m * dy_m)
    moon_halo = np.exp(-(dist_m ** 2) / (2.0 * 0.055 ** 2)) * 0.24 + np.exp(-(dist_m ** 2) / (2.0 * 0.018 ** 2)) * 0.45
    img += moon_halo[:, :, None] * np.array([150.0, 178.0, 228.0], dtype=np.float32)
    # Moon disc minus shadow disc -> sharp anti-aliased crescent
    r_moon = 0.0145
    disc_lit = np.clip((r_moon - dist_m) * (height * 0.75), 0.0, 1.0)
    dist_sh = np.sqrt((dx_m - 0.0062) ** 2 + (dy_m + 0.0022) ** 2)
    disc_sh = np.clip((r_moon * 0.94 - dist_sh) * (height * 0.75), 0.0, 1.0)
    crescent = np.clip(disc_lit - disc_sh, 0.0, 1.0)
    img = img * (1.0 - crescent[:, :, None]) + np.array([242.0, 246.0, 255.0], dtype=np.float32) * crescent[:, :, None]

    # 3. 1,400 anti-aliased sub-pixel stars in upper sky (Gaussian splatted, zero aliasing)
    n_stars = 1400
    su = rng.uniform(2.0, width - 3.0, size=n_stars)
    sv = rng.uniform(4.0, height * 0.56, size=n_stars)
    smag = np.clip(rng.pareto(a=2.7, size=n_stars) / 3.5, 0.22, 1.8) * (1.0 - sv / (height * 0.62))
    sroll = rng.random(n_stars)
    for i in range(n_stars):
        uf, vf = float(su[i]), float(sv[i])
        b = float(smag[i])
        if b < 0.08:
            continue
        u0, v0 = int(round(uf)), int(round(vf))
        r_pick = float(sroll[i])
        if r_pick < 0.40:
            scol = np.array([200.0, 224.0, 255.0], dtype=np.float32)
        elif r_pick < 0.78:
            scol = np.array([255.0, 255.0, 255.0], dtype=np.float32)
        else:
            scol = np.array([255.0, 232.0, 192.0], dtype=np.float32)
        rad = 1 if b < 0.85 else 2
        sigma = 0.65 if rad == 1 else 0.95
        inv2s2 = 1.0 / (2.0 * sigma * sigma)
        for dv in range(-rad, rad + 1):
            yy = v0 + dv
            if yy < 0 or yy >= height:
                continue
            dy2 = (yy - vf) ** 2
            for du in range(-rad, rad + 1):
                xx = u0 + du
                if xx < 0 or xx >= width:
                    continue
                w_g = math.exp(-((xx - uf) ** 2 + dy2) * inv2s2) * b
                if w_g > 0.03:
                    img[yy, xx] = np.maximum(img[yy, xx], scol * w_g)

    # 4. Distant hazy mountain range (far atmospheric depth layer)
    ridge_far = (
        0.64
        + 0.048 * np.sin(u * 2.0 * math.pi * 1.9 + 0.6)
        + 0.022 * np.cos(u * 2.0 * math.pi * 4.8 + 1.4)
        + 0.010 * np.sin(u * 2.0 * math.pi * 11.3)
    )
    mask_far = vv >= ridge_far[None, :]
    haze_t = np.clip((vv - ridge_far[None, :]) / 0.14, 0.0, 1.0)
    far_col = np.array([26.0, 32.0, 50.0], dtype=np.float32) * (1.0 - haze_t[:, :, None]) + np.array([14.0, 18.0, 30.0], dtype=np.float32) * haze_t[:, :, None]
    img = np.where(mask_far[:, :, None], far_col, img)

    # 5. Crisp architectural modern city skyline (mid-ground towers with illuminated window grids)
    base_y_px = int(height * 0.84)
    # Two depth passes of buildings: far mid-tone skyscrapers + closer darker silhouettes
    for layer_idx, (n_bld, h_range, w_range, body_col, win_prob) in enumerate([
        (36, (int(height * 0.08), int(height * 0.24)), (int(width * 0.016), int(width * 0.040)), np.array([14.0, 17.0, 26.0], dtype=np.float32), 0.34),
        (28, (int(height * 0.06), int(height * 0.19)), (int(width * 0.018), int(width * 0.046)), np.array([9.0, 11.0, 18.0], dtype=np.float32), 0.42),
    ]):
        for _ in range(n_bld):
            bw = int(rng.integers(w_range[0], w_range[1]))
            bh = int(rng.integers(h_range[0], h_range[1]))
            # Cluster taller towers toward center-left and center-right of the window view
            cx = int(rng.uniform(0.06, 0.94) * width)
            if abs(cx / width - 0.50) < 0.24:
                bh = int(bh * rng.uniform(1.08, 1.38))
            x0 = max(2, cx - bw // 2)
            x1 = min(width - 2, x0 + bw)
            y0 = max(int(height * 0.42), base_y_px - bh)
            y1 = base_y_px

            # Slight vertical facade gradient (catching subtle twilight sky at roof)
            grad_v = np.linspace(1.22, 0.85, max(1, y1 - y0), dtype=np.float32)[:, None, None]
            img[y0:y1, x0:x1] = body_col[None, None, :] * grad_v

            # Optional stepped penthouse / crown & aviation beacon on tall towers
            if bh > int(height * 0.16) and bw >= 28:
                pw = max(10, bw // 2)
                ph = max(8, bh // 9)
                px0 = (x0 + x1) // 2 - pw // 2
                py0 = max(4, y0 - ph)
                img[py0:y0, px0:px0 + pw] = body_col * 1.15
                # Slim spire + subtle red beacon
                if rng.random() < 0.55:
                    sx = (x0 + x1) // 2
                    sy = max(4, py0 - int(rng.integers(12, 28)))
                    img[sy:py0, sx:sx + 2] = np.array([28.0, 32.0, 44.0], dtype=np.float32)
                    img[max(0, sy - 1):sy + 2, max(0, sx - 1):min(width, sx + 2)] = np.array([255.0, 58.0, 48.0], dtype=np.float32)

            # Crisp architectural window grid (2-3px tall lit panes with dark mullions)
            step_x = int(rng.integers(6, 10))
            step_y = int(rng.integers(7, 11))
            win_w_px = max(2, step_x - 3)
            win_h_px = max(2, step_y - 4)
            margin_x = 4
            margin_y = 6
            for wy in range(y0 + margin_y, y1 - margin_y, step_y):
                # Whole office floors sometimes stay lit together
                floor_lit = rng.random() < (0.28 if layer_idx == 1 else 0.18)
                for wx in range(x0 + margin_x, x1 - margin_x - win_w_px, step_x):
                    if floor_lit or (rng.random() < win_prob):
                        w_roll = rng.random()
                        if w_roll < 0.62:
                            wcol = np.array([255.0, 206.0, 134.0], dtype=np.float32)
                        elif w_roll < 0.86:
                            wcol = np.array([255.0, 236.0, 198.0], dtype=np.float32)
                        else:
                            wcol = np.array([170.0, 214.0, 255.0], dtype=np.float32)
                        inten = float(rng.uniform(0.45, 0.96)) * (0.78 if layer_idx == 0 else 1.0)
                        img[wy:wy + win_h_px, wx:wx + win_w_px] = wcol * inten

    # 6. Foreground dark hillside / coastal treeline silhouette + warm distant highway lights
    ridge_near = (
        0.82
        + 0.024 * np.sin(u * 2.0 * math.pi * 2.4 + 2.2)
        + 0.011 * np.cos(u * 2.0 * math.pi * 7.5)
    )
    mask_near = vv >= ridge_near[None, :]
    img = np.where(mask_near[:, :, None], np.array([6.0, 8.0, 13.0], dtype=np.float32), img)

    # Distant golden boulevard / valley street lights along the base of the skyline
    for _ in range(420):
        lx = int(rng.integers(3, width - 3))
        ly_min = int(ridge_near[lx] * height) + 3
        if ly_min >= height - 3:
            continue
        ly = int(rng.integers(ly_min, min(height - 3, ly_min + int(height * 0.12))))
        lcol = np.array([255.0, 192.0, 114.0], dtype=np.float32) if rng.random() < 0.82 else np.array([255.0, 238.0, 210.0], dtype=np.float32)
        inten = float(rng.uniform(0.40, 0.95))
        img[ly, lx] = np.maximum(img[ly, lx], lcol * inten)
        img[ly, lx - 1] = np.maximum(img[ly, lx - 1], lcol * (inten * 0.42))
        img[ly, lx + 1] = np.maximum(img[ly, lx + 1], lcol * (inten * 0.42))

    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), mode="RGB")


# ==============================================================================
# glTF 2.0 Binary (.glb) Multi-Material Exporter
# ==============================================================================
@dataclass
class MaterialSpec:
    name: str
    base_color_factor: Tuple[float, float, float, float] = (1.0, 1.0, 1.0, 1.0)
    metallic_factor: float = 0.0
    roughness_factor: float = 0.85
    emissive_factor: Tuple[float, float, float] = (0.0, 0.0, 0.0)
    emissive_strength: float = 1.0
    base_texture: Optional[Image.Image] = None
    orm_texture: Optional[Image.Image] = None
    emissive_texture: Optional[Image.Image] = None
    occlusion_strength: float = 1.0
    double_sided: bool = False
    unlit: bool = False
    bake_lighting: bool = True
    jpeg_quality: int = 86


class GlbSceneBuilder:
    """Collects meshes by PBR material, runs linear vertex AO/light bake, and writes valid glTF 2.0 .glb."""

    def __init__(self) -> None:
        self.materials: Dict[str, MaterialSpec] = {}
        self.material_order: List[str] = []
        self.buckets: Dict[str, List[MeshPart]] = {}

    def add_material(self, spec: MaterialSpec) -> None:
        if spec.name not in self.materials:
            self.material_order.append(spec.name)
            self.buckets[spec.name] = []
        self.materials[spec.name] = spec

    def add_mesh(self, material_name: str, part: MeshPart) -> None:
        if material_name not in self.materials:
            raise KeyError(f"Material '{material_name}' not registered")
        self.buckets[material_name].append(part)

    def build_and_export(
        self,
        output_path: str,
        bake_fn: Optional[Callable[[np.ndarray, np.ndarray], np.ndarray]] = None,
        y_offset: float = 0.0,
    ) -> Dict[str, int]:
        """
        Merges parts per material, optionally applies `bake_fn(verts, normals) -> linear_rgb_multiplier`,
        shifts by `y_offset`, and writes a binary glTF 2.0 `.glb`.
        """
        bin_data = bytearray()
        buffer_views = []
        accessors = []
        images = []
        textures = []
        gltf_materials = []
        primitives = []
        extensions_used = set()

        def pad4() -> None:
            while len(bin_data) % 4 != 0:
                bin_data.append(0)

        def append_bytes(raw: bytes, target: Optional[int] = None) -> int:
            pad4()
            offset = len(bin_data)
            bin_data.extend(raw)
            bv = {"buffer": 0, "byteOffset": offset, "byteLength": len(raw)}
            if target is not None:
                bv["target"] = target
            bv_idx = len(buffer_views)
            buffer_views.append(bv)
            return bv_idx

        def add_image(img: Image.Image, name: str, lossless: bool = False, quality: int = 86, subsampling: int = 2) -> int:
            buf = io.BytesIO()
            if lossless:
                img.save(buf, format="PNG", optimize=True)
                mime = "image/png"
            else:
                img.convert("RGB").save(buf, format="JPEG", quality=quality, subsampling=subsampling, optimize=True)
                mime = "image/jpeg"
            bv_idx = append_bytes(buf.getvalue())
            img_idx = len(images)
            images.append({"bufferView": bv_idx, "mimeType": mime, "name": name})
            tex_idx = len(textures)
            textures.append({"sampler": 0, "source": img_idx})
            return tex_idx

        total_tris = 0
        total_verts = 0

        for mat_name in self.material_order:
            parts = self.buckets[mat_name]
            if not parts:
                continue
            merged = concat_parts(parts)
            if len(merged.positions) == 0 or len(merged.indices) == 0:
                continue

            spec = self.materials[mat_name]

            # Apply linear baked AO/ambient multiplier before export Y offset
            if bake_fn is not None and spec.bake_lighting and not spec.unlit:
                mult = bake_fn(merged.positions, merged.normals)
                if mult.ndim == 1:
                    mult = mult[:, None]
                merged.colors[:, :3] = np.clip(merged.colors[:, :3] * mult, 0.0, 1.0)

            if abs(y_offset) > 1e-6:
                merged.translate((0.0, y_offset, 0.0))

            # Build glTF material entry
            mat_idx = len(gltf_materials)
            pbr: Dict[str, object] = {
                "baseColorFactor": [float(x) for x in spec.base_color_factor],
                "metallicFactor": float(spec.metallic_factor),
                "roughnessFactor": float(spec.roughness_factor),
            }
            if spec.base_texture is not None:
                tex_id = add_image(spec.base_texture, f"{mat_name}_base", lossless=False, quality=spec.jpeg_quality)
                pbr["baseColorTexture"] = {"index": tex_id}

            mat_obj: Dict[str, object] = {
                "name": mat_name,
                "pbrMetallicRoughness": pbr,
                "doubleSided": bool(spec.double_sided),
            }

            if spec.orm_texture is not None:
                orm_id = add_image(spec.orm_texture, f"{mat_name}_orm", lossless=True)
                pbr["metallicRoughnessTexture"] = {"index": orm_id}
                mat_obj["occlusionTexture"] = {"index": orm_id, "strength": float(spec.occlusion_strength)}

            if any(c > 1e-5 for c in spec.emissive_factor):
                mat_obj["emissiveFactor"] = [float(c) for c in spec.emissive_factor]
                if spec.emissive_strength > 1.001:
                    extensions_used.add("KHR_materials_emissive_strength")
                    mat_obj.setdefault("extensions", {})["KHR_materials_emissive_strength"] = {
                        "emissiveStrength": float(spec.emissive_strength)
                    }
            if spec.emissive_texture is not None:
                # Use 4:4:4 chroma (subsampling=0) at quality=95 so crisp night city lights & stars never macroblock
                em_id = add_image(spec.emissive_texture, f"{mat_name}_emissive", lossless=False, quality=95, subsampling=0)
                mat_obj["emissiveTexture"] = {"index": em_id}
                if "emissiveFactor" not in mat_obj:
                    mat_obj["emissiveFactor"] = [1.0, 1.0, 1.0]

            if spec.unlit:
                extensions_used.add("KHR_materials_unlit")
                mat_obj.setdefault("extensions", {})["KHR_materials_unlit"] = {}

            gltf_materials.append(mat_obj)

            # Pack vertex attributes
            pos_f32 = np.ascontiguousarray(merged.positions, dtype=np.float32)
            nrm_f32 = np.ascontiguousarray(merged.normals, dtype=np.float32)
            uv_f32 = np.ascontiguousarray(merged.uvs, dtype=np.float32)
            # 16-bit normalized linear RGBA preserves dark theater shadow gradients with half the size of float32.
            col_u16 = np.ascontiguousarray(
                np.round(np.clip(merged.colors, 0.0, 1.0) * 65535.0).astype(np.uint16)
            )

            bv_pos = append_bytes(pos_f32.tobytes(), target=34962)
            bv_nrm = append_bytes(nrm_f32.tobytes(), target=34962)
            bv_uv = append_bytes(uv_f32.tobytes(), target=34962)
            bv_col = append_bytes(col_u16.tobytes(), target=34962)

            acc_pos = len(accessors)
            accessors.append({
                "bufferView": bv_pos,
                "componentType": 5126,
                "count": len(pos_f32),
                "type": "VEC3",
                "min": [float(x) for x in pos_f32.min(axis=0)],
                "max": [float(x) for x in pos_f32.max(axis=0)],
            })
            acc_nrm = len(accessors)
            accessors.append({
                "bufferView": bv_nrm,
                "componentType": 5126,
                "count": len(nrm_f32),
                "type": "VEC3",
            })
            acc_uv = len(accessors)
            accessors.append({
                "bufferView": bv_uv,
                "componentType": 5126,
                "count": len(uv_f32),
                "type": "VEC2",
            })
            acc_col = len(accessors)
            accessors.append({
                "bufferView": bv_col,
                "componentType": 5123,  # UNSIGNED_SHORT
                "normalized": True,
                "count": len(col_u16),
                "type": "VEC4",
            })

            # Indices (16-bit when possible, 32-bit otherwise)
            if len(pos_f32) < 65535:
                idx_arr = np.ascontiguousarray(merged.indices, dtype=np.uint16)
                idx_comp = 5123
            else:
                idx_arr = np.ascontiguousarray(merged.indices, dtype=np.uint32)
                idx_comp = 5125
            bv_idx = append_bytes(idx_arr.tobytes(), target=34963)
            acc_idx = len(accessors)
            accessors.append({
                "bufferView": bv_idx,
                "componentType": idx_comp,
                "count": len(idx_arr),
                "type": "SCALAR",
            })

            primitives.append({
                "attributes": {
                    "POSITION": acc_pos,
                    "NORMAL": acc_nrm,
                    "TEXCOORD_0": acc_uv,
                    "COLOR_0": acc_col,
                },
                "indices": acc_idx,
                "material": mat_idx,
                "mode": 4,  # TRIANGLES
            })

            total_verts += len(pos_f32)
            total_tris += len(idx_arr) // 3

        pad4()
        gltf_json: Dict[str, object] = {
            "asset": {"version": "2.0", "generator": "tucaVR env_glb_builder.py"},
            "scene": 0,
            "scenes": [{"nodes": [0]}],
            "nodes": [{"mesh": 0, "name": "EnvironmentRoot"}],
            "meshes": [{"name": "EnvironmentMesh", "primitives": primitives}],
            "materials": gltf_materials,
            "accessors": accessors,
            "bufferViews": buffer_views,
            "buffers": [{"byteLength": len(bin_data)}],
        }
        if textures:
            gltf_json["samplers"] = [{
                "magFilter": 9729,
                "minFilter": 9987,
                "wrapS": 10497,
                "wrapT": 10497,
            }]
            gltf_json["images"] = images
            gltf_json["textures"] = textures
        if extensions_used:
            gltf_json["extensionsUsed"] = sorted(extensions_used)

        json_bytes = json.dumps(gltf_json, separators=(",", ":")).encode("utf-8")
        while len(json_bytes) % 4 != 0:
            json_bytes += b" "

        total_length = 12 + 8 + len(json_bytes) + 8 + len(bin_data)
        glb = bytearray()
        glb.extend(struct.pack("<III", 0x46546C67, 2, total_length))
        glb.extend(struct.pack("<II", len(json_bytes), 0x4E4F534A))
        glb.extend(json_bytes)
        glb.extend(struct.pack("<II", len(bin_data), 0x004E4942))
        glb.extend(bin_data)

        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        with open(output_path, "wb") as f:
            f.write(glb)

        return {
            "triangles": total_tris,
            "vertices": total_verts,
            "materials": len(gltf_materials),
            "textures": len(images),
            "bytes": len(glb),
        }
