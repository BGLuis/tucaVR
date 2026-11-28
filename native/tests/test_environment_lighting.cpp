#include "environment_lighting.h"

#include <cassert>
#include <cmath>
#include <iostream>

static bool Near(float a, float b, float eps = 1e-4f) {
    return std::fabs(a - b) <= eps;
}

static bool Vec3Near(const XrVector3f &a, const XrVector3f &b, float eps = 1e-3f) {
    return Near(a.x, b.x, eps) && Near(a.y, b.y, eps) && Near(a.z, b.z, eps);
}

static void TestSrgbAndBrightnessCurves() {
    assert(Near(vrplayer::SrgbToLinearScalar(0.0f), 0.0f));
    assert(Near(vrplayer::SrgbToLinearScalar(1.0f), 1.0f));
    // Mid-gray 0.5 sRGB is ~0.2140 linear
    assert(Near(vrplayer::SrgbToLinearScalar(0.5f), 0.21404f, 1e-3f));

    // Emissives never vanish at 0% room brightness and reach 1.0 at 100%
    const float emDark = vrplayer::ComputeEmissiveScale(0.0f);
    const float emFull = vrplayer::ComputeEmissiveScale(1.0f);
    assert(emDark > 0.15f && emDark < 0.35f);
    assert(Near(emFull, 1.0f));
    assert(vrplayer::ComputeEmissiveScale(0.5f) > emDark && vrplayer::ComputeEmissiveScale(0.5f) < emFull);
    std::cout << "[PASS] TestSrgbAndBrightnessCurves\n";
}

static void TestAmbientAndGlowGating() {
    // 1. Regression test: Screen Glow OFF (0.0) in a 3D room must remain 0.0 (never fall back to 0.85).
    auto offRoom = vrplayer::ResolveAmbientAndGlowIntensities(true, 0.0f, 0.85f, false, 0, true, false, false, true);
    assert(Near(offRoom.voidHaloTarget, 0.0f));
    assert(Near(offRoom.roomGlowTarget, 0.0f));

    // 2. 3D room glow (including Space and custom environments) works independently of the Void Ambient Mode toggle.
    auto spaceStrong =
        vrplayer::ResolveAmbientAndGlowIntensities(false, 0.85f, 0.85f, false, 0, true, false, false, true);
    assert(Near(spaceStrong.voidHaloTarget, 0.0f));
    assert(Near(spaceStrong.roomGlowTarget, 0.85f));

    auto subtleRoom =
        vrplayer::ResolveAmbientAndGlowIntensities(false, 0.45f, 0.85f, false, 1, true, false, false, true);
    assert(Near(subtleRoom.roomGlowTarget, 0.45f));

    // 3. Void environment activates only the halo when Ambient Mode is enabled.
    auto voidOn = vrplayer::ResolveAmbientAndGlowIntensities(true, 0.0f, 0.85f, false, 0, true, false, true, false);
    assert(Near(voidOn.voidHaloTarget, 0.85f));
    assert(Near(voidOn.roomGlowTarget, 0.0f));

    auto voidOff = vrplayer::ResolveAmbientAndGlowIntensities(false, 0.85f, 0.85f, false, 0, true, false, true, false);
    assert(Near(voidOff.voidHaloTarget, 0.0f));
    assert(Near(voidOff.roomGlowTarget, 0.0f));

    // 4. Safety gates: thermal >= 2, passthrough, sphere/cubemap, or missing video frame disable both.
    auto thermal = vrplayer::ResolveAmbientAndGlowIntensities(true, 0.85f, 0.85f, false, 2, true, false, false, true);
    assert(Near(thermal.voidHaloTarget, 0.0f) && Near(thermal.roomGlowTarget, 0.0f));

    auto pt = vrplayer::ResolveAmbientAndGlowIntensities(true, 0.85f, 0.85f, false, 0, true, true, false, true);
    assert(Near(pt.voidHaloTarget, 0.0f) && Near(pt.roomGlowTarget, 0.0f));

    auto sphere = vrplayer::ResolveAmbientAndGlowIntensities(true, 0.85f, 0.85f, true, 0, true, false, false, true);
    assert(Near(sphere.voidHaloTarget, 0.0f) && Near(sphere.roomGlowTarget, 0.0f));

    auto noVideo = vrplayer::ResolveAmbientAndGlowIntensities(true, 0.85f, 0.85f, false, 0, false, false, false, true);
    assert(Near(noVideo.voidHaloTarget, 0.0f) && Near(noVideo.roomGlowTarget, 0.0f));

    std::cout << "[PASS] TestAmbientAndGlowGating\n";
}

static void TestScreenInEnvironmentSpaceInvariance() {
    // Verify that recentering (arbitrary yaw + head translation), spawn offsets, and 3DoF Bed Mode
    // map the world-space screen back to the EXACT local model-space screen coordinates of the room.
    const XrVector3f baseScreenPos = {0.0f, 2.40f, -6.20f};
    const float scaleX = 7.60f;
    const float scaleY = 4.275f;
    const XrVector3f spawnOffset = {0.3f, 0.1f, -0.4f};

    // Case A: Standard yaw-only recenter at arbitrary head position & rotation
    const XrQuaternionf qYaw = QuatFromYaw(1.15f);
    const XrVector3f headA = {1.4f, 1.65f, -0.8f};
    const auto recA = vrplayer::ComputeRecenter(false, qYaw, headA, 1.5f);
    const Mat4 envModelA =
        vrplayer::ComputeEnvironmentModel(false, headA, recA.translationOffset, recA.orientationMatrix, spawnOffset);
    const XrVector3f screenWorldA = vrplayer::BasePointToWorld(
        baseScreenPos, Vec3Add(recA.translationOffset, {0.0f, 1.5f, 0.0f}), recA.orientationMatrix);
    const Mat4 screenModelNoScaleA =
        Mat4Multiply(Mat4Translation(screenWorldA.x, screenWorldA.y, screenWorldA.z), recA.orientationMatrix);

    const auto localA =
        vrplayer::ComputeScreenInEnvironmentSpace(envModelA, screenModelNoScaleA, scaleX, scaleY, headA);
    const XrVector3f expectedCenter = Vec3Add(baseScreenPos, spawnOffset);
    const XrVector3f expectedEye = Vec3Add({0.0f, 1.5f, 0.0f}, spawnOffset);
    assert(Vec3Near(localA.centerLocal, expectedCenter));
    assert(Vec3Near(localA.cameraPosLocal, expectedEye));
    assert(Vec3Near(localA.halfRightLocal, {scaleX * 0.5f, 0.0f, 0.0f}));
    assert(Vec3Near(localA.halfUpLocal, {0.0f, scaleY * 0.5f, 0.0f}));
    assert(Vec3Near(localA.normalLocal, {0.0f, 0.0f, 1.0f}));

    // Case B: Bed Mode looking straight up at the ceiling (+90 deg pitch)
    const float halfPi = 1.5707963f;
    const XrQuaternionf qBed = {sinf(halfPi * 0.5f), 0.0f, 0.0f, cosf(halfPi * 0.5f)};
    const XrVector3f headB = {-0.5f, 0.7f, 0.9f};
    const auto recB = vrplayer::ComputeRecenter(true, qBed, headB, 1.5f);
    const Mat4 envModelB =
        vrplayer::ComputeEnvironmentModel(true, headB, recB.translationOffset, recB.orientationMatrix, spawnOffset);
    const XrVector3f screenWorldB = vrplayer::BasePointToWorld(baseScreenPos, headB, recB.orientationMatrix);
    const Mat4 screenModelNoScaleB =
        Mat4Multiply(Mat4Translation(screenWorldB.x, screenWorldB.y, screenWorldB.z), recB.orientationMatrix);

    const auto localB =
        vrplayer::ComputeScreenInEnvironmentSpace(envModelB, screenModelNoScaleB, scaleX, scaleY, headB);
    assert(Vec3Near(localB.centerLocal, expectedCenter));
    assert(Vec3Near(localB.cameraPosLocal, expectedEye));
    assert(Vec3Near(localB.halfRightLocal, {scaleX * 0.5f, 0.0f, 0.0f}));
    assert(Vec3Near(localB.halfUpLocal, {0.0f, scaleY * 0.5f, 0.0f}));
    assert(Vec3Near(localB.normalLocal, {0.0f, 0.0f, 1.0f}));

    std::cout << "[PASS] TestScreenInEnvironmentSpaceInvariance\n";
}

static void TestRoomLightingPresets() {
    for (const char *id : {"cinema", "living_room", "space", "custom_room_uuid"}) {
        const auto p = vrplayer::GetRoomLightingPreset(id);
        const float len = sqrtf(p.keyDir[0] * p.keyDir[0] + p.keyDir[1] * p.keyDir[1] + p.keyDir[2] * p.keyDir[2]);
        assert(len > 0.5f);
        assert(p.keyIntensity > 0.5f && p.keyIntensity <= 2.5f);
        assert(p.skyAmbientIntensity > 0.4f && p.skyAmbientIntensity <= 1.5f);
        assert(p.ambientHemiStrength > 0.5f && p.ambientHemiStrength <= 1.2f);
    }
    std::cout << "[PASS] TestRoomLightingPresets\n";
}

int main() {
    std::cout << "--- Running environment_lighting.h unit tests ---\n";
    TestSrgbAndBrightnessCurves();
    TestAmbientAndGlowGating();
    TestScreenInEnvironmentSpaceInvariance();
    TestRoomLightingPresets();
    std::cout << "--- All environment_lighting.h unit tests passed! ---\n";
    return 0;
}
