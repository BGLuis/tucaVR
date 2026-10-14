#include "controls_pose.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace vrplayer;

static constexpr float kPi = 3.14159265358979323846f;
static constexpr float kEpsilon = 1e-4f;

static bool Near(float a, float b, float eps = kEpsilon) {
    return std::fabs(a - b) <= eps;
}

static void TestNominalControlsPose() {
    const XrVector3f sceneOffset = {0.0f, 0.0f, 0.0f};
    const float screenYaw = 0.0f;
    const XrVector3f nominalEyePos = {0.0f, 1.5f, 0.0f};

    const XrVector3f center = ComputeControlsCenter(sceneOffset, screenYaw);

    assert(Near(center.x, 0.0f));
    assert(Near(center.y, 1.05f));
    assert(Near(center.z, -1.3f));

    const float elevationAngle = ComputeControlsElevationAngleDeg(center, nominalEyePos);
    // Explicit requirement: center between 15 deg and 22 deg below the eyes (~19 deg)
    assert(elevationAngle >= 15.0f && elevationAngle <= 22.0f);
    assert(Near(elevationAngle, 19.086f, 0.1f));

    const float hDist = ComputeControlsHorizontalDistance(center, nominalEyePos);
    const float dist = ComputeControlsDistance(center, nominalEyePos);

    // Explicit requirement: distance between 0.8 and 3.0 m (1.3m nominal)
    assert(hDist >= 0.8f && hDist <= 3.0f);
    assert(dist >= 0.8f && dist <= 3.0f);
    assert(Near(hDist, 1.3f));
    assert(Near(dist, std::sqrt(1.3f * 1.3f + 0.45f * 0.45f), 0.01f));

    std::cout << "[PASS] TestNominalControlsPose (Angle=" << elevationAngle << " deg, Dist=" << dist << " m)\n";
}

static void TestControlsPoseFollowsScreenYaw() {
    const XrVector3f sceneOffset = {0.0f, 0.0f, 0.0f};
    const XrVector3f nominalEyePos = {0.0f, 1.5f, 0.0f};

    const float testYawAnglesDeg[] = {0.0f, 30.0f, 45.0f, 90.0f, 135.0f, 180.0f, -45.0f, -90.0f};

    for (float yawDeg : testYawAnglesDeg) {
        float yawRad = yawDeg * (kPi / 180.0f);
        XrVector3f center = ComputeControlsCenter(sceneOffset, yawRad);

        // Center must be rotated by yaw around Y: x = s * (-1.3) = -1.3*sin(yaw), z = c * (-1.3) = -1.3*cos(yaw)
        float expectedX = -1.3f * std::sin(yawRad);
        float expectedZ = -1.3f * std::cos(yawRad);
        assert(Near(center.x, expectedX, 0.01f));
        assert(Near(center.y, 1.05f));
        assert(Near(center.z, expectedZ, 0.01f));

        // Ergonomics invariants must be preserved at any yaw angle
        float elevationAngle = ComputeControlsElevationAngleDeg(center, nominalEyePos);
        assert(elevationAngle >= 15.0f && elevationAngle <= 22.0f);

        float dist = ComputeControlsDistance(center, nominalEyePos);
        assert(dist >= 0.8f && dist <= 3.0f);
    }

    std::cout << "[PASS] TestControlsPoseFollowsScreenYaw\n";
}

static void TestSceneOffsetTranslationInvariance() {
    const XrVector3f offsets[] = {
        {1.2f, 0.3f, -2.5f},
        {-3.0f, -0.2f, 1.0f},
        {0.0f, 1.0f, 0.0f},
    };

    for (const auto &offset : offsets) {
        XrVector3f eyePos = Vec3Add(offset, {0.0f, 1.5f, 0.0f});
        float yawRad = 0.5f;

        XrVector3f center = ComputeControlsCenter(offset, yawRad);

        float elevationAngle = ComputeControlsElevationAngleDeg(center, eyePos);
        assert(elevationAngle >= 15.0f && elevationAngle <= 22.0f);

        float dist = ComputeControlsDistance(center, eyePos);
        assert(dist >= 0.8f && dist <= 3.0f);
    }

    std::cout << "[PASS] TestSceneOffsetTranslationInvariance\n";
}

static void TestControlsCylinderCurvatureCenter() {
    const XrVector3f center = {0.0f, 1.05f, -1.3f};
    const float yaw = 0.0f;
    const float pitch = kControlsPitch;

    XrVector3f cylCenter = ComputeControlsCylinderCenter(center, yaw, kControlsCylinderRadius, pitch);

    // Distance between panel center and cylinder curvature center must equal radius
    float dist = Vec3Length(Vec3Sub(cylCenter, center));
    assert(Near(dist, kControlsCylinderRadius, 1e-4f));

    // When yaw is 0 and pitch is tilted upwards towards the user,
    // normal vector points towards +Z and slightly downwards.
    // Displacing along normal displaces +Z and downwards.
    assert(cylCenter.z > center.z);

    std::cout << "[PASS] TestControlsCylinderCurvatureCenter\n";
}

static void TestControlsTransform() {
    const XrVector3f center = {1.0f, 1.05f, -2.0f};
    const float yaw = 0.3f;
    const float pitch = kControlsPitch;

    Mat4 model = ComputeControlsModelNoScale(center, yaw, pitch);
    Mat4 inv = Mat4RigidInverse(model);
    Mat4 prod = Mat4Multiply(model, inv);

    // Check product is approximately identity
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            float expected = (col == row) ? 1.0f : 0.0f;
            assert(Near(prod.m[col * 4 + row], expected, 1e-3f));
        }
    }

    std::cout << "[PASS] TestControlsTransform\n";
}

int main() {
    std::cout << "--- Executando testes unitarios de controls_pose.h ---\n";
    TestNominalControlsPose();
    TestControlsPoseFollowsScreenYaw();
    TestSceneOffsetTranslationInvariance();
    TestControlsCylinderCurvatureCenter();
    TestControlsTransform();
    std::cout << "--- Todos os testes de controls_pose.h passaram com sucesso! ---\n";
    return 0;
}
