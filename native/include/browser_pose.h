#pragma once

#include "vk_math.h"

#include <cmath>

namespace vrplayer {

// Posição do navegador em coordenadas da cena (mesmo referencial de screenPosition), para que ele
// acompanhe o recenter como os demais painéis.
constexpr XrVector3f kBrowserSpawnLocalPosition = {0.0f, 1.5f, -2.2f};

inline XrVector3f BrowserLocalToWorld(const XrVector3f &local, const XrVector3f &sceneOffset, float sceneYaw) {
    return Vec3Add(sceneOffset, Vec3RotateY(local, sceneYaw));
}

inline XrVector3f BrowserWorldToLocal(const XrVector3f &world, const XrVector3f &sceneOffset, float sceneYaw) {
    return Vec3RotateY(Vec3Sub(world, sceneOffset), -sceneYaw);
}

inline XrVector3f BrowserLocalToWorld3DoF(const XrVector3f &local, const XrVector3f &headCenter, const Mat4 &rot) {
    const XrVector3f rel = {local.x, local.y - 1.5f, local.z};
    const XrVector3f rotRel = {
        rot.m[0] * rel.x + rot.m[4] * rel.y + rot.m[8] * rel.z,
        rot.m[1] * rel.x + rot.m[5] * rel.y + rot.m[9] * rel.z,
        rot.m[2] * rel.x + rot.m[6] * rel.y + rot.m[10] * rel.z,
    };
    return Vec3Add(headCenter, rotRel);
}

inline XrVector3f BrowserWorldToLocal3DoF(const XrVector3f &world, const XrVector3f &headCenter, const Mat4 &rot) {
    const XrVector3f d = Vec3Sub(world, headCenter);
    const XrVector3f rel = {
        rot.m[0] * d.x + rot.m[1] * d.y + rot.m[2] * d.z,
        rot.m[4] * d.x + rot.m[5] * d.y + rot.m[6] * d.z,
        rot.m[8] * d.x + rot.m[9] * d.y + rot.m[10] * d.z,
    };
    return {rel.x, rel.y + 1.5f, rel.z};
}

// Yaw que vira o painel para a cabeça (billboard horizontal, igual ao painel Home).
inline float BillboardYaw(const XrVector3f &panelWorld, const XrVector3f &head, float fallbackYaw) {
    const float dx = head.x - panelWorld.x;
    const float dz = head.z - panelWorld.z;
    const float len = std::sqrt(dx * dx + dz * dz);
    return (len > 1e-4f) ? std::atan2(dx / len, dz / len) : fallbackYaw;
}

} // namespace vrplayer
