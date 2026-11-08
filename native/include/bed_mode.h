#pragma once

#include "vk_math.h"

#include <algorithm>
#include <cmath>

namespace vrplayer {

// Normalizes a quaternion. If length is near zero or non-finite, returns identity quaternion.
inline XrQuaternionf QuatNormalize(const XrQuaternionf &q) {
    const float lenSq = q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w;
    if (lenSq < 1e-8f || !std::isfinite(lenSq)) {
        return {0.0f, 0.0f, 0.0f, 1.0f};
    }
    const float invLen = 1.0f / sqrtf(lenSq);
    return {q.x * invLen, q.y * invLen, q.z * invLen, q.w * invLen};
}

// Transforms a 3D vector by the 3x3 linear rotation part of a column-major Mat4.
inline XrVector3f Mat4TransformVector(const Mat4 &m, const XrVector3f &v) {
    return {
        m.m[0] * v.x + m.m[4] * v.y + m.m[8] * v.z,
        m.m[1] * v.x + m.m[5] * v.y + m.m[9] * v.z,
        m.m[2] * v.x + m.m[6] * v.y + m.m[10] * v.z,
    };
}

// Transforms a 3D vector by the transpose (inverse of orthonormal rotation) of Mat4.
inline XrVector3f Mat4TransformVectorInverse(const Mat4 &m, const XrVector3f &v) {
    return {
        m.m[0] * v.x + m.m[1] * v.y + m.m[2] * v.z,
        m.m[4] * v.x + m.m[5] * v.y + m.m[6] * v.z,
        m.m[8] * v.x + m.m[9] * v.y + m.m[10] * v.z,
    };
}

// Extracts the forward gaze vector from a column-major transformation/rotation matrix.
// In OpenXR coordinate space, column 2 (m[8], m[9], m[10]) points along +Z (backward),
// so the forward direction is along -Z: (-m[8], -m[9], -m[10]).
inline XrVector3f ExtractForwardVector(const Mat4 &rotMatrix) {
    return {-rotMatrix.m[8], -rotMatrix.m[9], -rotMatrix.m[10]};
}

// Extracts head elevation pitch angle in radians from the forward gaze vector.
// Returns angle in [-pi/2, +pi/2], where > 0 means looking upwards toward the ceiling.
inline float ExtractHeadPitch(const XrVector3f &fwd) {
    const float clampedY = std::max(-1.0f, std::min(1.0f, fwd.y));
    return asinf(clampedY);
}

// Result of a recenter operation.
struct RecenterResult {
    float yawOffset;
    float pitchOffset;
    XrQuaternionf orientation;
    Mat4 orientationMatrix;
    XrVector3f translationOffset;
};

// Computes recenter parameters for cinema (yaw-only) or bed (3DoF reclined) mode.
// Prevents gimbal lock and quaternion degeneracy under any head orientation (including +90 deg ceiling gaze).
inline RecenterResult ComputeRecenter(bool bedModeEnabled, const XrQuaternionf &headOrientation,
                                      const XrVector3f &headPosition, float nominalEyeHeight = 1.5f) {
    RecenterResult res{};
    res.translationOffset = headPosition;
    res.translationOffset.y = headPosition.y - nominalEyeHeight;

    const XrQuaternionf qNorm = QuatNormalize(headOrientation);
    const Mat4 headRot = Mat4FromXrPose(XrPosef{qNorm, {0.0f, 0.0f, 0.0f}});
    const XrVector3f fwd = ExtractForwardVector(headRot);

    res.pitchOffset = ExtractHeadPitch(fwd);
    const float hLenSq = fwd.x * fwd.x + fwd.z * fwd.z;
    if (hLenSq >= 1e-4f) {
        res.yawOffset = atan2f(-fwd.x, -fwd.z);
    } else {
        // Singularidade de zenith (+90 deg) ou nadir (-90 deg): a projecao horizontal do olhar eh nula.
        // Usamos o vetor 'up' (coluna Y da matriz) projetado no plano XZ para preservar a orientacao
        // corporal horizontal do usuario sem resetar espuriamente para yaw = 0.
        const XrVector3f up = {headRot.m[4], headRot.m[5], headRot.m[6]};
        if (fwd.y > 0.0f) {
            res.yawOffset = atan2f(up.x, up.z);
        } else {
            res.yawOffset = atan2f(-up.x, -up.z);
        }
    }

    if (bedModeEnabled) {
        // Bed mode (3DoF reclined playback): aligns the scene with the user's full 3DoF head orientation.
        res.orientation = qNorm;
        res.orientationMatrix = headRot;
    } else {
        // Standard cinema mode: horizon remains rigidly locked to world gravity (Y-axis only).
        res.orientation = QuatFromYaw(res.yawOffset);
        res.orientationMatrix = Mat4RotationY(res.yawOffset);
    }

    return res;
}

// Transforms a base point (defined relative to nominal eye height = 1.5m) to world coordinates.
// In yaw-only mode, this strictly matches: sceneTranslationOffset + Vec3RotateY(basePos, yaw).
inline XrVector3f BasePointToWorld(const XrVector3f &basePos, const XrVector3f &headCenter, const Mat4 &rot) {
    const XrVector3f rel = {basePos.x, basePos.y - 1.5f, basePos.z};
    return Vec3Add(headCenter, Mat4TransformVector(rot, rel));
}

// Transforms a world coordinate back to base space (relative to nominal eye height = 1.5m).
// In yaw-only mode, this strictly matches: Vec3RotateY(worldPos - sceneTranslationOffset, -yaw).
inline XrVector3f WorldPointToBase(const XrVector3f &worldPos, const XrVector3f &headCenter, const Mat4 &rot) {
    const XrVector3f d = Vec3Sub(worldPos, headCenter);
    const XrVector3f rel = Mat4TransformVectorInverse(rot, d);
    return {rel.x, rel.y + 1.5f, rel.z};
}

} // namespace vrplayer
