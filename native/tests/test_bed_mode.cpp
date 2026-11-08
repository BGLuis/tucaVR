#include "bed_mode.h"
#include "browser_pose.h"
#include "controls_pose.h"
#include "cylinder_hit.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace vrplayer;

static constexpr float kEpsilon = 1e-3f;

static bool FloatNear(float a, float b, float eps = kEpsilon) {
    return std::fabs(a - b) <= eps;
}

static bool Vec3Near(const XrVector3f &a, const XrVector3f &b, float eps = kEpsilon) {
    return FloatNear(a.x, b.x, eps) && FloatNear(a.y, b.y, eps) && FloatNear(a.z, b.z, eps);
}

static bool Mat4Near(const Mat4 &a, const Mat4 &b, float eps = kEpsilon) {
    for (int i = 0; i < 16; ++i) {
        if (!FloatNear(a.m[i], b.m[i], eps)) {
            return false;
        }
    }
    return true;
}

static bool QuatMatchesMatrix(const XrQuaternionf &q, const Mat4 &m, float eps = 1e-3f) {
    Mat4 qMat = Mat4FromXrPose(XrPosef{q, {0.0f, 0.0f, 0.0f}});
    for (int col = 0; col < 3; ++col) {
        for (int row = 0; row < 3; ++row) {
            int idx = col * 4 + row;
            if (!FloatNear(qMat.m[idx], m.m[idx], eps)) {
                return false;
            }
        }
    }
    return true;
}

static bool IsMatrixOrthonormal(const Mat4 &m, float eps = kEpsilon) {
    // Check that columns are unit length and orthogonal
    for (int col1 = 0; col1 < 3; ++col1) {
        float lenSq = 0.0f;
        for (int row = 0; row < 3; ++row) {
            float val = m.m[col1 * 4 + row];
            lenSq += val * val;
        }
        if (!FloatNear(lenSq, 1.0f, eps))
            return false;

        for (int col2 = col1 + 1; col2 < 3; ++col2) {
            float dot = 0.0f;
            for (int row = 0; row < 3; ++row) {
                dot += m.m[col1 * 4 + row] * m.m[col2 * 4 + row];
            }
            if (!FloatNear(dot, 0.0f, eps))
                return false;
        }
    }
    return true;
}

static void TestRecenterYawOnlyInvariants() {
    // Head rotated by 45 degrees yaw, but tilted up by 30 degrees pitch
    // In cinema (yaw-only) mode, pitch must be discarded from the scene orientation.
    const float yaw = 0.785398f;   // ~45 deg
    const float pitch = 0.523598f; // ~30 deg
    Mat4 headRot = Mat4Multiply(Mat4RotationY(yaw), Mat4RotationX(pitch));
    XrQuaternionf headOrientation = QuatFromMat4(headRot);
    XrVector3f headPos = {1.0f, 1.8f, -0.5f};

    RecenterResult res = ComputeRecenter(false /* bedModeEnabled */, headOrientation, headPos, 1.5f);

    assert(FloatNear(res.yawOffset, yaw));
    assert(FloatNear(res.pitchOffset, pitch));
    assert(Mat4Near(res.orientationMatrix, Mat4RotationY(yaw)));
    assert(QuatMatchesMatrix(res.orientation, res.orientationMatrix));
    assert(FloatNear(res.translationOffset.x, 1.0f));
    assert(FloatNear(res.translationOffset.y, 0.3f)); // 1.8 - 1.5
    assert(FloatNear(res.translationOffset.z, -0.5f));

    // Verify BasePointToWorld strictly equals sceneTranslationOffset + Vec3RotateY(basePos, yaw)
    XrVector3f baseScreenPos = {0.0f, 1.5f, -2.4f};
    XrVector3f worldFrom3DoF = BasePointToWorld(baseScreenPos, headPos, res.orientationMatrix);
    XrVector3f worldFromYawOnly = Vec3Add(res.translationOffset, Vec3RotateY(baseScreenPos, res.yawOffset));
    assert(Vec3Near(worldFrom3DoF, worldFromYawOnly));

    // Verify roundtrip
    XrVector3f backToBase = WorldPointToBase(worldFrom3DoF, headPos, res.orientationMatrix);
    assert(Vec3Near(backToBase, baseScreenPos));

    std::cout << "[PASS] TestRecenterYawOnlyInvariants\n";
}

static void TestRecenter3DoFBedModeLookingStraightUpAtCeiling() {
    // User lying flat in bed, looking straight up at the ceiling (+90 deg pitch).
    // Rotation around X axis by +pi/2 (+90 deg).
    const float kHalfPi = 1.5707963f;
    Mat4 headRot = Mat4RotationX(kHalfPi);
    XrQuaternionf headOrientation = QuatFromMat4(headRot);
    XrVector3f headPos = {0.0f, 0.5f, 0.0f}; // ~50cm above floor on bed mattress

    RecenterResult res = ComputeRecenter(true /* bedModeEnabled */, headOrientation, headPos, 1.5f);

    assert(FloatNear(res.pitchOffset, kHalfPi));
    assert(IsMatrixOrthonormal(res.orientationMatrix));
    assert(QuatMatchesMatrix(res.orientation, res.orientationMatrix));

    // Forward direction must point straight up toward the ceiling (0, 1, 0)
    XrVector3f fwd = ExtractForwardVector(res.orientationMatrix);
    assert(FloatNear(fwd.x, 0.0f));
    assert(FloatNear(fwd.y, 1.0f));
    assert(FloatNear(fwd.z, 0.0f));

    // Base screen is at {0, 1.5, -2.4}.
    // In world space, looking at ceiling, it must be placed directly above the user at {0, 0.5 + 2.4, 0} = {0, 2.9, 0}
    XrVector3f baseScreenPos = {0.0f, 1.5f, -2.4f};
    XrVector3f worldScreenPos = BasePointToWorld(baseScreenPos, headPos, res.orientationMatrix);
    assert(FloatNear(worldScreenPos.x, 0.0f));
    assert(FloatNear(worldScreenPos.y, 2.9f));
    assert(FloatNear(worldScreenPos.z, 0.0f));

    // Screen normal (local +Z = {0, 0, 1}) must point downward toward the user's face {0, -1, 0}
    XrVector3f screenNormal = Mat4TransformVector(res.orientationMatrix, {0.0f, 0.0f, 1.0f});
    assert(FloatNear(screenNormal.x, 0.0f));
    assert(FloatNear(screenNormal.y, -1.0f));
    assert(FloatNear(screenNormal.z, 0.0f));

    // Screen up vector (local +Y = {0, 1, 0}) must point along positive Z {0, 0, 1} (top of user's head / backward)
    XrVector3f screenUp = Mat4TransformVector(res.orientationMatrix, {0.0f, 1.0f, 0.0f});
    assert(FloatNear(screenUp.x, 0.0f));
    assert(FloatNear(screenUp.y, 0.0f));
    assert(FloatNear(screenUp.z, 1.0f));

    // Roundtrip verification
    XrVector3f baseRestored = WorldPointToBase(worldScreenPos, headPos, res.orientationMatrix);
    assert(Vec3Near(baseRestored, baseScreenPos));

    std::cout << "[PASS] TestRecenter3DoFBedModeLookingStraightUpAtCeiling\n";
}

static void TestRecenter3DoFArbitraryRecline() {
    // Reclined at 45 deg pitch, 30 deg yaw, 10 deg roll
    const float yaw = 0.523598f;
    const float pitch = 0.785398f;
    const float roll = 0.174533f;

    // Build rotation: yaw * pitch * roll
    Mat4 rot = Mat4Multiply(Mat4Multiply(Mat4RotationY(yaw), Mat4RotationX(pitch)),
                            Mat4RotationY(0.0f)); // roll around Z
    // Construct roll rotation around Z
    Mat4 rotZ = Mat4Identity();
    rotZ.m[0] = cosf(roll);
    rotZ.m[1] = sinf(roll);
    rotZ.m[4] = -sinf(roll);
    rotZ.m[5] = cosf(roll);
    rot = Mat4Multiply(rot, rotZ);

    XrQuaternionf headOrientation = QuatFromMat4(rot);
    XrVector3f headPos = {0.2f, 0.7f, 1.1f};

    RecenterResult res = ComputeRecenter(true, headOrientation, headPos, 1.5f);

    assert(IsMatrixOrthonormal(res.orientationMatrix));
    assert(QuatMatchesMatrix(res.orientation, res.orientationMatrix));

    XrVector3f baseScreenPos = {0.0f, 1.5f, -2.4f};
    XrVector3f worldScreenPos = BasePointToWorld(baseScreenPos, headPos, res.orientationMatrix);

    // Distance between head and screen must remain exactly 2.4 meters
    float dist = Vec3Length(Vec3Sub(worldScreenPos, headPos));
    assert(FloatNear(dist, 2.4f));

    // Screen normal must point directly back to the head
    XrVector3f screenNormal = Mat4TransformVector(res.orientationMatrix, {0.0f, 0.0f, 1.0f});
    XrVector3f toHead = Vec3Sub(headPos, worldScreenPos);
    float toHeadLen = Vec3Length(toHead);
    XrVector3f toHeadDir = {toHead.x / toHeadLen, toHead.y / toHeadLen, toHead.z / toHeadLen};
    assert(Vec3Near(screenNormal, toHeadDir));

    // Roundtrip verification for multiple points
    XrVector3f testPoints[] = {{0.0f, 1.5f, -2.4f}, {-1.2f, 1.0f, -1.8f}, {1.5f, 2.0f, -3.0f}, {0.0f, 1.05f, -1.3f}};
    for (const auto &p : testPoints) {
        XrVector3f w = BasePointToWorld(p, headPos, res.orientationMatrix);
        XrVector3f back = WorldPointToBase(w, headPos, res.orientationMatrix);
        assert(Vec3Near(back, p));
    }

    std::cout << "[PASS] TestRecenter3DoFArbitraryRecline\n";
}

static void TestSphericalOrientationBedMode() {
    // When watching 180° / 360° VR videos in Bed Mode, the sphere must orient
    // its forward projection center directly to where the user is looking.
    const float kPitchUp = 1.0f; // ~57.3 deg reclined
    Mat4 headRot = Mat4RotationX(kPitchUp);
    XrQuaternionf headOrientation = QuatFromMat4(headRot);
    XrVector3f headPos = {0.0f, 0.6f, 0.0f};

    RecenterResult res = ComputeRecenter(true, headOrientation, headPos, 1.5f);

    // Sphere forward vector (local {0, 0, -1})
    XrVector3f sphereForward = Mat4TransformVector(res.orientationMatrix, {0.0f, 0.0f, -1.0f});
    XrVector3f userGaze = ExtractForwardVector(res.orientationMatrix);
    assert(Vec3Near(sphereForward, userGaze));

    // When metadata yaw offset is present (AVSphericalMapping / prhd):
    float videoSphereYawOffset = 0.5f;
    Mat4 combinedRot = Mat4Multiply(res.orientationMatrix, Mat4RotationY(videoSphereYawOffset));
    assert(IsMatrixOrthonormal(combinedRot));

    std::cout << "[PASS] TestSphericalOrientationBedMode\n";
}

static void TestSingularitiesAndGimbalLockResistance() {
    // Test extreme boundary values
    float pitches[] = {0.0f,
                       1.5707963f,  // +90 deg (exact zenith)
                       -1.5707963f, // -90 deg (exact nadir)
                       3.1415926f,  // 180 deg upside-down
                       0.0001f,     -0.0001f};

    for (float p : pitches) {
        Mat4 rot = Mat4RotationX(p);
        XrQuaternionf q = QuatFromMat4(rot);
        RecenterResult res = ComputeRecenter(true, q, {0, 1.5f, 0}, 1.5f);

        assert(std::isfinite(res.pitchOffset));
        assert(std::isfinite(res.yawOffset));
        assert(IsMatrixOrthonormal(res.orientationMatrix));
    }

    // Test unnormalized quaternions and degenerate inputs
    XrQuaternionf degenerateQuats[] = {
        {0.0f, 0.0f, 0.0f, 0.0f},          // zero length
        {100.0f, 200.0f, -300.0f, 400.0f}, // large magnitude
        {1e-9f, 0.0f, 0.0f, 0.0f},         // near-zero magnitude
        {0.0f, 0.0f, 0.0f, -1.0f}          // negative w
    };

    for (const auto &dq : degenerateQuats) {
        XrQuaternionf normQ = QuatNormalize(dq);
        float lenSq = normQ.x * normQ.x + normQ.y * normQ.y + normQ.z * normQ.z + normQ.w * normQ.w;
        assert(FloatNear(lenSq, 1.0f, 1e-3f));

        RecenterResult res = ComputeRecenter(true, dq, {0, 1.5f, 0}, 1.5f);
        assert(IsMatrixOrthonormal(res.orientationMatrix));
    }

    // Test zenith/nadir yaw preservation when pitch is +/-90 deg
    const float testYaws[] = {0.0f, 0.785398f, 1.570796f, 3.141592f, -1.047197f};
    for (float y : testYaws) {
        // Tilted straight up (+90 deg pitch) with body facing yaw y
        Mat4 rotZenith = Mat4Multiply(Mat4RotationY(y), Mat4RotationX(1.5707963f));
        XrQuaternionf qZ = QuatFromMat4(rotZenith);
        RecenterResult resZ = ComputeRecenter(false /* yaw-only */, qZ, {0, 1.5f, 0}, 1.5f);
        // Normalize angle difference to [-pi, pi]
        float diff = std::remainder(resZ.yawOffset - y, 2.0f * 3.14159265f);
        assert(FloatNear(diff, 0.0f, 0.01f));

        // Tilted straight down (-90 deg pitch) with body facing yaw y
        Mat4 rotNadir = Mat4Multiply(Mat4RotationY(y), Mat4RotationX(-1.5707963f));
        XrQuaternionf qN = QuatFromMat4(rotNadir);
        RecenterResult resN = ComputeRecenter(false /* yaw-only */, qN, {0, 1.5f, 0}, 1.5f);
        float diffN = std::remainder(resN.yawOffset - y, 2.0f * 3.14159265f);
        assert(FloatNear(diffN, 0.0f, 0.01f));
    }

    std::cout << "[PASS] TestSingularitiesAndGimbalLockResistance\n";
}

static void TestControlsPoseBedMode() {
    // 1. Verify that in yaw-only mode, 3DoF functions match legacy yaw functions bit-for-bit
    float testYaw = 1.234f;
    XrVector3f sceneOffset = {2.0f, 0.3f, -1.0f};
    XrVector3f headPos = {2.0f, 1.8f, -1.0f}; // headPos.y - 1.5 = sceneOffset.y
    Mat4 yawRot = Mat4RotationY(testYaw);

    XrVector3f ctrlCenterLegacy = ComputeControlsCenter(sceneOffset, testYaw);
    XrVector3f ctrlCenter3DoF = ComputeControlsCenter3DoF(headPos, yawRot);
    assert(Vec3Near(ctrlCenterLegacy, ctrlCenter3DoF));

    Mat4 ctrlRotLegacy = ComputeControlsRotation(testYaw);
    Mat4 ctrlRot3DoF = ComputeControlsRotation3DoF(yawRot);
    assert(Mat4Near(ctrlRotLegacy, ctrlRot3DoF));

    XrVector3f cylCenterLegacy = ComputeControlsCylinderCenter(ctrlCenterLegacy, testYaw);
    XrVector3f cylCenter3DoF = ComputeControlsCylinderCenter3DoF(ctrlCenter3DoF, ctrlRot3DoF);
    assert(Vec3Near(cylCenterLegacy, cylCenter3DoF));

    // 2. In Bed Mode looking at ceiling (+90 deg pitch)
    Mat4 ceilingRot = Mat4RotationX(1.5707963f);
    XrVector3f headBed = {0.0f, 0.5f, 0.0f};
    XrVector3f ctrlCenterCeiling = ComputeControlsCenter3DoF(headBed, ceilingRot);
    Mat4 ctrlRotCeiling = ComputeControlsRotation3DoF(ceilingRot);
    XrVector3f cylCenterCeiling = ComputeControlsCylinderCenter3DoF(ctrlCenterCeiling, ctrlRotCeiling);

    // Controls must be located above user's torso, below the screen elevation
    assert(ctrlCenterCeiling.y > headBed.y);
    assert(ctrlCenterCeiling.y < 2.9f); // screen is at 2.9m
    assert(IsMatrixOrthonormal(ctrlRotCeiling));
    assert(Vec3Length(Vec3Sub(cylCenterCeiling, ctrlCenterCeiling)) > 0.5f);

    std::cout << "[PASS] TestControlsPoseBedMode\n";
}

static void TestRaycastControlsCylinderInBedMode() {
    // User lying flat on bed looking straight up at ceiling (+90 deg pitch)
    Mat4 ceilingRot = Mat4RotationX(1.5707963f);
    XrVector3f headBed = {0.0f, 0.5f, 0.0f};

    XrVector3f ctrlCenter = ComputeControlsCenter3DoF(headBed, ceilingRot);
    Mat4 ctrlRot = ComputeControlsRotation3DoF(ceilingRot);
    Mat4 ctrlModel = Mat4Multiply(Mat4Translation(ctrlCenter.x, ctrlCenter.y, ctrlCenter.z), ctrlRot);

    // Aim controller ray from head towards the center of controls panel
    XrVector3f rayDir = Vec3Sub(ctrlCenter, headBed);
    float dist = Vec3Length(rayDir);
    assert(dist > 0.5f);
    rayDir = Vec3Scale(rayDir, 1.0f / dist);

    float outU = -1.0f;
    float outV = -1.0f;
    const float aspectRatio = 1582.0f / 800.0f;
    float t = rayHitsCylinder(ctrlModel, kControlsCylinderRadius, kControlsCylinderCentralAngle, aspectRatio, outU,
                              outV, headBed, rayDir);

    assert(t > 0.0f);
    assert(FloatNear(t, dist, 0.02f));
    assert(FloatNear(outU, 0.5f, 0.02f));
    assert(FloatNear(outV, 0.5f, 0.02f));

    std::cout << "[PASS] TestRaycastControlsCylinderInBedMode\n";
}

static void TestOrientationQuaternionMatchesMatrix() {
    // 1. Identity orientation
    {
        RecenterResult res = ComputeRecenter(true, {0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 1.5f, 0.0f}, 1.5f);
        assert(QuatMatchesMatrix(res.orientation, res.orientationMatrix));
    }
    // 2. Ceiling gaze (+90 deg pitch)
    {
        XrQuaternionf qCeil = QuatFromMat4(Mat4RotationX(1.5707963f));
        RecenterResult res = ComputeRecenter(true, qCeil, {0.0f, 0.5f, 0.0f}, 1.5f);
        assert(QuatMatchesMatrix(res.orientation, res.orientationMatrix));
    }
    // 3. Floor gaze (-90 deg pitch)
    {
        XrQuaternionf qFloor = QuatFromMat4(Mat4RotationX(-1.5707963f));
        RecenterResult res = ComputeRecenter(true, qFloor, {0.0f, 1.5f, 0.0f}, 1.5f);
        assert(QuatMatchesMatrix(res.orientation, res.orientationMatrix));
    }
    // 4. Multiple pitch/yaw sweep
    for (float yaw = -3.0f; yaw <= 3.0f; yaw += 1.0f) {
        for (float pitch = -1.4f; pitch <= 1.4f; pitch += 0.7f) {
            Mat4 rot = Mat4Multiply(Mat4RotationY(yaw), Mat4RotationX(pitch));
            XrQuaternionf q = QuatFromMat4(rot);
            RecenterResult resBed = ComputeRecenter(true, q, {1.0f, 2.0f, 3.0f}, 1.5f);
            assert(QuatMatchesMatrix(resBed.orientation, resBed.orientationMatrix));
            RecenterResult resCinema = ComputeRecenter(false, q, {1.0f, 2.0f, 3.0f}, 1.5f);
            assert(QuatMatchesMatrix(resCinema.orientation, resCinema.orientationMatrix));
        }
    }
    std::cout << "[PASS] TestOrientationQuaternionMatchesMatrix\n";
}

static void TestApplyRecenterHelper() {
    struct DummyAppState {
        float sceneYawOffset = 0.0f;
        XrQuaternionf sceneOrientation{};
        Mat4 sceneOrientationMatrix{};
        XrVector3f sceneTranslationOffset{};
    } state;

    RecenterResult res{};
    res.yawOffset = 1.234f;
    res.orientation = {0.1f, 0.2f, 0.3f, 0.9f};
    res.orientationMatrix = Mat4RotationY(1.234f);
    res.translationOffset = {1.0f, 2.0f, 3.0f};

    ApplyRecenter(state, res);

    assert(FloatNear(state.sceneYawOffset, res.yawOffset));
    assert(FloatNear(state.sceneOrientation.x, res.orientation.x));
    assert(FloatNear(state.sceneOrientation.y, res.orientation.y));
    assert(FloatNear(state.sceneOrientation.z, res.orientation.z));
    assert(FloatNear(state.sceneOrientation.w, res.orientation.w));
    assert(Mat4Near(state.sceneOrientationMatrix, res.orientationMatrix));
    assert(Vec3Near(state.sceneTranslationOffset, res.translationOffset));

    std::cout << "[PASS] TestApplyRecenterHelper\n";
}

int main() {
    std::cout << "--- Executing unit tests for bed_mode.h ---\n";
    TestRecenterYawOnlyInvariants();
    TestRecenter3DoFBedModeLookingStraightUpAtCeiling();
    TestRecenter3DoFArbitraryRecline();
    TestSphericalOrientationBedMode();
    TestSingularitiesAndGimbalLockResistance();
    TestControlsPoseBedMode();
    TestRaycastControlsCylinderInBedMode();
    TestOrientationQuaternionMatchesMatrix();
    TestApplyRecenterHelper();
    std::cout << "--- All bed_mode.h unit tests passed successfully! ---\n";
    return 0;
}
