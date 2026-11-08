#pragma once

#include "bed_mode.h"
#include "vk_math.h"

#include <cmath>

namespace vrplayer {

// Browser panel position in scene coordinates (same reference space as screenPosition),
// allowing it to follow recenter alongside other panels.
constexpr XrVector3f kBrowserSpawnLocalPosition = {0.0f, 1.5f, -2.2f};

inline XrVector3f BrowserLocalToWorld(const XrVector3f &local, const XrVector3f &sceneOffset, float sceneYaw) {
    return Vec3Add(sceneOffset, Vec3RotateY(local, sceneYaw));
}

inline XrVector3f BrowserWorldToLocal(const XrVector3f &world, const XrVector3f &sceneOffset, float sceneYaw) {
    return Vec3RotateY(Vec3Sub(world, sceneOffset), -sceneYaw);
}

inline XrVector3f BrowserLocalToWorld3DoF(const XrVector3f &local, const XrVector3f &headCenter, const Mat4 &rot) {
    return BasePointToWorld(local, headCenter, rot);
}

inline XrVector3f BrowserWorldToLocal3DoF(const XrVector3f &world, const XrVector3f &headCenter, const Mat4 &rot) {
    return WorldPointToBase(world, headCenter, rot);
}

// Yaw that turns the panel towards the head (horizontal billboard, same as Home panel).
inline float BillboardYaw(const XrVector3f &panelWorld, const XrVector3f &head, float fallbackYaw) {
    const float dx = head.x - panelWorld.x;
    const float dz = head.z - panelWorld.z;
    const float len = std::sqrt(dx * dx + dz * dz);
    return (len > 1e-4f) ? std::atan2(dx / len, dz / len) : fallbackYaw;
}

} // namespace vrplayer
