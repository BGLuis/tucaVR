#pragma once

#include "vk_math.h"

#include <cmath>

namespace vrplayer {

// UI (Home / File Browser) cylinder parameters
constexpr float kUiCylinderRadius = 1.6f;
constexpr float kUiCylinderCentralAngle = 80.0f * (3.14159265358979323846f / 180.0f); // ~1.3962634 rad (80 deg)
constexpr XrVector3f kBaseUiPos = {0.0f, 1.36f, -1.6f};

// Controls panel cylinder parameters:
// Base position placed ~19 deg below eye level (nominal eye height ~1.5m) at 1.3m horizontal distance
constexpr float kControlsCylinderRadius = 1.3f;
constexpr float kControlsCylinderCentralAngle = 48.0f * (3.14159265358979323846f / 180.0f); // ~0.837758 rad (48 deg)
constexpr XrVector3f kBaseControlsPos = {0.0f, 1.05f, -1.3f};
constexpr float kControlsPitch = -0.3f; // ~17.18 deg tilt upwards towards the user

// Pure function calculating the world position of the controls center given scene offset and screen yaw
inline XrVector3f ComputeControlsCenter(const XrVector3f &sceneOffset, float screenYaw,
                                        const XrVector3f &basePos = kBaseControlsPos) {
    return Vec3Add(sceneOffset, Vec3RotateY(basePos, screenYaw));
}

// Pure function calculating the rotation matrix for the controls panel (yaw followed by pitch)
inline Mat4 ComputeControlsRotation(float screenYaw, float pitch = kControlsPitch) {
    return Mat4Multiply(Mat4RotationY(screenYaw), Mat4RotationX(pitch));
}

// Pure function calculating the orientation quaternion for the controls panel
inline XrQuaternionf ComputeControlsOrientation(float screenYaw, float pitch = kControlsPitch) {
    return QuatFromMat4(ComputeControlsRotation(screenYaw, pitch));
}

// Pure function calculating the rigid model transform (no scale) for the controls panel
inline Mat4 ComputeControlsModelNoScale(const XrVector3f &controlsCenter, float screenYaw,
                                        float pitch = kControlsPitch) {
    return Mat4Multiply(Mat4Translation(controlsCenter.x, controlsCenter.y, controlsCenter.z),
                        ComputeControlsRotation(screenYaw, pitch));
}

// Pure function calculating the center of curvature for XrCompositionLayerCylinderKHR.
// The OpenXR cylinder pose origin is at the axis/center of curvature, displaced along local +Z.
inline XrVector3f ComputeControlsCylinderCenter(const XrVector3f &controlsCenter, float screenYaw,
                                                float radius = kControlsCylinderRadius, float pitch = kControlsPitch) {
    Mat4 rot = ComputeControlsRotation(screenYaw, pitch);
    // Displace by radius along local Z axis: (rot.m[8], rot.m[9], rot.m[10]) * radius
    XrVector3f disp = {rot.m[8] * radius, rot.m[9] * radius, rot.m[10] * radius};
    return Vec3Add(controlsCenter, disp);
}

// Pure function calculating the downward elevation angle (in degrees) below eye level.
// Positive return value indicates the controls center is below the eyes.
inline float ComputeControlsElevationAngleDeg(const XrVector3f &controlsCenter, const XrVector3f &eyePos) {
    float dy = eyePos.y - controlsCenter.y;
    float dx = controlsCenter.x - eyePos.x;
    float dz = controlsCenter.z - eyePos.z;
    float horizontalDist = std::sqrt(dx * dx + dz * dz);
    if (horizontalDist < 1e-4f) {
        return 0.0f;
    }
    return std::atan2(dy, horizontalDist) * (180.0f / 3.14159265358979323846f);
}

// Pure function calculating horizontal distance (XZ plane) from eyes to controls center
inline float ComputeControlsHorizontalDistance(const XrVector3f &controlsCenter, const XrVector3f &eyePos) {
    float dx = controlsCenter.x - eyePos.x;
    float dz = controlsCenter.z - eyePos.z;
    return std::sqrt(dx * dx + dz * dz);
}

// Pure function calculating 3D Euclidean distance from eyes to controls center
inline float ComputeControlsDistance(const XrVector3f &controlsCenter, const XrVector3f &eyePos) {
    return Vec3Length(Vec3Sub(controlsCenter, eyePos));
}

} // namespace vrplayer

using vrplayer::ComputeControlsCenter;
using vrplayer::ComputeControlsCylinderCenter;
using vrplayer::ComputeControlsDistance;
using vrplayer::ComputeControlsElevationAngleDeg;
using vrplayer::ComputeControlsHorizontalDistance;
using vrplayer::ComputeControlsModelNoScale;
using vrplayer::ComputeControlsOrientation;
using vrplayer::ComputeControlsRotation;
using vrplayer::kBaseControlsPos;
using vrplayer::kBaseUiPos;
using vrplayer::kControlsCylinderCentralAngle;
using vrplayer::kControlsCylinderRadius;
using vrplayer::kControlsPitch;
using vrplayer::kUiCylinderCentralAngle;
using vrplayer::kUiCylinderRadius;
