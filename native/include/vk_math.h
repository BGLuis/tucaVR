#pragma once

// Matrizes 4x4 column-major (mesma convencao do GLSL/Vulkan: m[col*4+row]).
// Escopo minimo necessario para posicionar a geometria dos Estagios 2+ do
// plano de migracao Vulkan (docs/VULKAN-MIGRATION-PLAN.md) — nao e uma lib
// de matematica geral, e usada apenas por vr_player_app_vulkan.cpp.

#include <openxr/openxr.h>

#include <cmath>

struct Mat4 {
    float m[16];
};

inline XrVector3f Vec3Add(const XrVector3f& a, const XrVector3f& b) {
    return {a.x + b.x, a.y + b.y, a.z + b.z};
}

inline XrVector3f Vec3Sub(const XrVector3f& a, const XrVector3f& b) {
    return {a.x - b.x, a.y - b.y, a.z - b.z};
}

inline XrVector3f Vec3Scale(const XrVector3f& v, float s) {
    return {v.x * s, v.y * s, v.z * s};
}

// Rotaciona em torno do eixo Y — mesma convencao de sinal de Mat4RotationY
// abaixo (consistencia interna e o que importa aqui: nao precisa bater bit a
// bit com OVR::Matrix4f::RotationY do caminho GLES).
inline XrVector3f Vec3RotateY(const XrVector3f& v, float yaw) {
    const float c = cosf(yaw);
    const float s = sinf(yaw);
    return {c * v.x + s * v.z, v.y, -s * v.x + c * v.z};
}

inline Mat4 Mat4Identity() {
    Mat4 r{};
    r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1.0f;
    return r;
}

inline Mat4 Mat4Multiply(const Mat4& a, const Mat4& b) {
    Mat4 r{};
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            float sum = 0.0f;
            for (int k = 0; k < 4; k++) {
                sum += a.m[k * 4 + row] * b.m[col * 4 + k];
            }
            r.m[col * 4 + row] = sum;
        }
    }
    return r;
}

inline Mat4 Mat4Translation(float x, float y, float z) {
    Mat4 r = Mat4Identity();
    r.m[12] = x;
    r.m[13] = y;
    r.m[14] = z;
    return r;
}

inline Mat4 Mat4Scale(float sx, float sy, float sz) {
    Mat4 r = Mat4Identity();
    r.m[0] = sx;
    r.m[5] = sy;
    r.m[10] = sz;
    return r;
}

inline Mat4 Mat4RotationX(float angle) {
    Mat4 r = Mat4Identity();
    const float c = cosf(angle);
    const float s = sinf(angle);
    r.m[5]  = c;
    r.m[6]  = s;
    r.m[9]  = -s;
    r.m[10] = c;
    return r;
}

inline Mat4 Mat4RotationY(float angle) {
    Mat4 r = Mat4Identity();
    const float c = cosf(angle);
    const float s = sinf(angle);
    r.m[0]  = c;
    r.m[2]  = -s;
    r.m[8]  = s;
    r.m[10] = c;
    return r;
}

// Pose XR (posicao + quaternion) -> matriz de transformacao rigida.
inline Mat4 Mat4FromXrPose(const XrPosef& pose) {
    const XrQuaternionf& q = pose.orientation;
    const float xx = q.x * q.x, yy = q.y * q.y, zz = q.z * q.z;
    const float xy = q.x * q.y, xz = q.x * q.z, yz = q.y * q.z;
    const float wx = q.w * q.x, wy = q.w * q.y, wz = q.w * q.z;

    Mat4 r = Mat4Identity();
    r.m[0] = 1.0f - 2.0f * (yy + zz);
    r.m[1] = 2.0f * (xy + wz);
    r.m[2] = 2.0f * (xz - wy);

    r.m[4] = 2.0f * (xy - wz);
    r.m[5] = 1.0f - 2.0f * (xx + zz);
    r.m[6] = 2.0f * (yz + wx);

    r.m[8] = 2.0f * (xz + wy);
    r.m[9] = 2.0f * (yz - wx);
    r.m[10] = 1.0f - 2.0f * (xx + yy);

    r.m[12] = pose.position.x;
    r.m[13] = pose.position.y;
    r.m[14] = pose.position.z;
    return r;
}

// Inversa de uma transformacao rigida (rotacao ortonormal + translacao):
// R^-1 = R^T, T^-1 = -R^T * T. Mais barato e mais estavel que uma inversa
// 4x4 generica, e e tudo que uma pose de camera precisa.
inline Mat4 Mat4RigidInverse(const Mat4& in) {
    Mat4 r = Mat4Identity();
    r.m[0] = in.m[0];
    r.m[1] = in.m[4];
    r.m[2] = in.m[8];
    r.m[4] = in.m[1];
    r.m[5] = in.m[5];
    r.m[6] = in.m[9];
    r.m[8] = in.m[2];
    r.m[9] = in.m[6];
    r.m[10] = in.m[10];

    const float tx = in.m[12], ty = in.m[13], tz = in.m[14];
    r.m[12] = -(r.m[0] * tx + r.m[4] * ty + r.m[8] * tz);
    r.m[13] = -(r.m[1] * tx + r.m[5] * ty + r.m[9] * tz);
    r.m[14] = -(r.m[2] * tx + r.m[6] * ty + r.m[10] * tz);
    return r;
}

// Projecao assimetrica a partir do FOV do OpenXR, para o clip space do
// Vulkan (Y positivo para baixo, profundidade em [0,1]) — mesma formula da
// implementacao de referencia do OpenXR-SDK (xr_linear.h,
// XrMatrix4x4f_CreateProjectionFov), especializada para Vulkan (sem os
// ramos de OpenGL que este projeto nao usa neste arquivo).
inline Mat4 Mat4ProjectionFromFov(const XrFovf& fov, float nearZ, float farZ) {
    const float tanLeft = tanf(fov.angleLeft);
    const float tanRight = tanf(fov.angleRight);
    const float tanDown = tanf(fov.angleDown);
    const float tanUp = tanf(fov.angleUp);

    const float tanWidth = tanRight - tanLeft;
    const float tanHeight = tanDown - tanUp; // Y para baixo no Vulkan.

    Mat4 r{};
    r.m[0] = 2.0f / tanWidth;
    r.m[8] = (tanRight + tanLeft) / tanWidth;

    r.m[5] = 2.0f / tanHeight;
    r.m[9] = (tanUp + tanDown) / tanHeight;

    r.m[10] = farZ / (nearZ - farZ);
    r.m[14] = (farZ * nearZ) / (nearZ - farZ);

    r.m[11] = -1.0f;
    return r;
}

inline XrQuaternionf QuatFromYaw(float yaw) {
    return {0.0f, sinf(yaw * 0.5f), 0.0f, cosf(yaw * 0.5f)};
}

inline XrQuaternionf QuatFromMat4(const Mat4& m) {
    XrQuaternionf q{};
    float trace = m.m[0] + m.m[5] + m.m[10];
    if (trace > 0.0f) {
        float s = 0.5f / sqrtf(trace + 1.0f);
        q.w = 0.25f / s;
        q.x = (m.m[6] - m.m[9]) * s;
        q.y = (m.m[8] - m.m[2]) * s;
        q.z = (m.m[1] - m.m[4]) * s;
    } else {
        if (m.m[0] > m.m[5] && m.m[0] > m.m[10]) {
            float s = 2.0f * sqrtf(1.0f + m.m[0] - m.m[5] - m.m[10]);
            q.w = (m.m[6] - m.m[9]) / s;
            q.x = 0.25f * s;
            q.y = (m.m[4] + m.m[1]) / s;
            q.z = (m.m[8] + m.m[2]) / s;
        } else if (m.m[5] > m.m[10]) {
            float s = 2.0f * sqrtf(1.0f + m.m[5] - m.m[0] - m.m[10]);
            q.w = (m.m[8] - m.m[2]) / s;
            q.x = (m.m[4] + m.m[1]) / s;
            q.y = 0.25f * s;
            q.z = (m.m[9] + m.m[6]) / s;
        } else {
            float s = 2.0f * sqrtf(1.0f + m.m[10] - m.m[0] - m.m[5]);
            q.w = (m.m[1] - m.m[4]) / s;
            q.x = (m.m[8] + m.m[2]) / s;
            q.y = (m.m[9] + m.m[6]) / s;
            q.z = 0.25f * s;
        }
    }
    return q;
}

