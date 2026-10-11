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

// Yaw que vira o painel para a cabeça (billboard horizontal, igual ao painel Home).
inline float BillboardYaw(const XrVector3f &panelWorld, const XrVector3f &head, float fallbackYaw) {
    const float dx = head.x - panelWorld.x;
    const float dz = head.z - panelWorld.z;
    const float len = std::sqrt(dx * dx + dz * dz);
    return (len > 1e-4f) ? std::atan2(dx / len, dz / len) : fallbackYaw;
}

} // namespace vrplayer
