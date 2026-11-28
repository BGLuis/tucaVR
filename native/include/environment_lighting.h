#pragma once

#include "bed_mode.h"
#include "vk_math.h"

#include <algorithm>
#include <cmath>
#include <string>

namespace vrplayer {

// Exact IEC 61966-2-1 sRGB EOTF for scalar channels.
inline float SrgbToLinearScalar(float c) {
    c = std::clamp(c, 0.0f, 1.0f);
    return (c <= 0.04045f) ? (c / 12.92f) : std::pow((c + 0.055f) / 1.055f, 2.4f);
}

// Perceptually uniform brightness curve for room/skybox dimming sliders (0..1 -> linear gain).
inline float PerceptualBrightnessToLinear(float brightness) {
    return SrgbToLinearScalar(brightness);
}

// Emissive accents (step LEDs, sconces, lamps, orbital neons) dim gently with room brightness
// so they never blind the viewer in a dark cinema, yet remain subtly legible at 0% room light.
inline float ComputeEmissiveScale(float envBrightness) {
    const float b = std::clamp(envBrightness, 0.0f, 1.0f);
    return 0.22f + 0.78f * PerceptualBrightnessToLinear(b);
}

struct AmbientGlowTargets {
    float voidHaloTarget = 0.0f;
    float roomGlowTarget = 0.0f;
};

// Resolves independent target intensities for the Void bias-light halo and 3D room reactive screen glow.
// Fixes three bugs in the legacy single-scalar gate:
//  1. Setting Screen Glow to Off (0.0) previously fell back to kAmbientMaxIntensity (0.85).
//  2. Room glow was hardcoded to "cinema" and "living_room", excluding "space" and custom environments.
//  3. Room glow was gated by the Void-only Ambient Mode toggle.
inline AmbientGlowTargets ResolveAmbientAndGlowIntensities(bool ambientModeEnabled, float configuredScreenGlow,
                                                           float defaultVoidHaloIntensity, bool isSphereOrCubemap,
                                                           int thermalLevel, bool hasVideoFrame, bool passthroughActive,
                                                           bool isVoidEnvironment, bool has3dEnvironmentMesh) {
    AmbientGlowTargets targets{};
    if (isSphereOrCubemap || thermalLevel >= 2 || !hasVideoFrame || passthroughActive) {
        return targets;
    }

    if (isVoidEnvironment) {
        if (ambientModeEnabled) {
            targets.voidHaloTarget = std::clamp(defaultVoidHaloIntensity, 0.0f, 1.0f);
        }
        return targets;
    }

    if (has3dEnvironmentMesh) {
        targets.roomGlowTarget = std::clamp(configuredScreenGlow, 0.0f, 1.0f);
    }
    return targets;
}

struct ScreenInEnvironmentSpace {
    XrVector3f cameraPosLocal;
    XrVector3f centerLocal;
    XrVector3f halfRightLocal;
    XrVector3f halfUpLocal;
    XrVector3f normalLocal;
};

// Transforms the camera and the virtual screen rectangle from world space into the 3D environment's
// local model space using the analytical inverse of the rigid environment transform.
// Keeps rectangular screen bounce lighting aligned across recentering, 6DoF movement, spawn offsets,
// and 3DoF Bed Mode.
inline ScreenInEnvironmentSpace ComputeScreenInEnvironmentSpace(const Mat4 &envModel, const Mat4 &screenModelNoScale,
                                                                float screenScaleX, float screenScaleY,
                                                                const XrVector3f &cameraPosWorld) {
    const Mat4 invEnv = Mat4RigidInverse(envModel);

    auto transformPoint = [&](const XrVector3f &p) -> XrVector3f {
        return {
            invEnv.m[0] * p.x + invEnv.m[4] * p.y + invEnv.m[8] * p.z + invEnv.m[12],
            invEnv.m[1] * p.x + invEnv.m[5] * p.y + invEnv.m[9] * p.z + invEnv.m[13],
            invEnv.m[2] * p.x + invEnv.m[6] * p.y + invEnv.m[10] * p.z + invEnv.m[14],
        };
    };

    auto transformDir = [&](const XrVector3f &v) -> XrVector3f { return Mat4TransformVector(invEnv, v); };

    const XrVector3f screenCenterWorld = {screenModelNoScale.m[12], screenModelNoScale.m[13], screenModelNoScale.m[14]};
    const XrVector3f rightWorld = {screenModelNoScale.m[0], screenModelNoScale.m[1], screenModelNoScale.m[2]};
    const XrVector3f upWorld = {screenModelNoScale.m[4], screenModelNoScale.m[5], screenModelNoScale.m[6]};
    // Video quad faces +Z in its local space (toward the viewer at origin looking down -Z).
    const XrVector3f normalWorld = {screenModelNoScale.m[8], screenModelNoScale.m[9], screenModelNoScale.m[10]};

    const float halfW = std::max(0.01f, screenScaleX * 0.5f);
    const float halfH = std::max(0.01f, screenScaleY * 0.5f);

    ScreenInEnvironmentSpace out{};
    out.cameraPosLocal = transformPoint(cameraPosWorld);
    out.centerLocal = transformPoint(screenCenterWorld);
    out.halfRightLocal = Vec3Scale(transformDir(rightWorld), halfW);
    out.halfUpLocal = Vec3Scale(transformDir(upWorld), halfH);

    XrVector3f nLocal = transformDir(normalWorld);
    const float nLen = Vec3Length(nLocal);
    if (nLen > 1e-6f) {
        nLocal = Vec3Scale(nLocal, 1.0f / nLen);
    } else {
        nLocal = {0.0f, 0.0f, 1.0f};
    }
    out.normalLocal = nLocal;
    return out;
}

struct RoomLightingPreset {
    float keyDir[3];
    float keyIntensity;
    float keyColor[3];
    float skyAmbientIntensity;
    float groundColor[3];
    float ambientHemiStrength;
};

// Returns physically calibrated linear-space hemisphere and key-light defaults per room archetype.
// Tuned for sRGB hardware decode + Quest 3 panels so baked surface detail stays clearly legible at 100%
// room brightness while dimming smoothly toward pitch black via the user's brightness slider.
inline RoomLightingPreset GetRoomLightingPreset(const std::string &envId) {
    if (envId == "cinema") {
        // Warm overhead architectural fill; legible textured acoustic walls & velvet without washing out IMAX contrast.
        return {
            {0.0f, 0.85f, 0.52f}, 1.15f, {1.0f, 0.88f, 0.72f}, 0.72f, {0.14f, 0.12f, 0.13f}, 0.78f,
        };
    }
    if (envId == "living_room") {
        // Cozy evening lounge: warm 2700K key from the left arc lamp + balanced architectural ambient.
        return {
            {-0.62f, 0.68f, -0.39f}, 1.35f, {1.0f, 0.84f, 0.62f}, 0.85f, {0.20f, 0.16f, 0.13f}, 0.88f,
        };
    }
    if (envId == "space") {
        // Crisp cool-blue starlight key from above-left so titanium/carbon deck reads clearly against deep space.
        return {
            {-0.35f, 0.88f, -0.32f}, 1.45f, {0.82f, 0.91f, 1.0f}, 0.78f, {0.10f, 0.13f, 0.18f}, 0.80f,
        };
    }
    // Neutral studio-balanced lighting for user-imported custom glTF environments.
    return {
        {0.25f, 0.90f, 0.35f}, 1.30f, {1.0f, 0.97f, 0.94f}, 0.80f, {0.16f, 0.16f, 0.17f}, 0.82f,
    };
}

} // namespace vrplayer
