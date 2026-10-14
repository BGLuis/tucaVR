#include "controls_pose.h"
#include "cylinder_hit.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace vrplayer;

static constexpr float kPi = 3.14159265358979323846f;

static bool Near(float a, float b, float eps = 0.005f) {
    return std::fabs(a - b) <= eps;
}

static void TestUiCylinderAnglesAndUv() {
    const float radius = kUiCylinderRadius;             // 1.6m
    const float centralAngle = kUiCylinderCentralAngle; // 80 deg in radians
    const float aspectRatio = 1920.0f / 960.0f;         // 2.0
    const Mat4 transform = Mat4Translation(kBaseUiPos.x, kBaseUiPos.y, kBaseUiPos.z);

    // Center of curvature of the UI arc is at (0, 1.36, 0)
    const XrVector3f rayOrigin = {0.0f, 1.36f, 0.0f};

    const float testAnglesDeg[] = {0.0f, 20.0f, -20.0f, 39.0f, -39.0f};

    for (float thetaDeg : testAnglesDeg) {
        float thetaRad = thetaDeg * (kPi / 180.0f);
        XrVector3f rayDir = {std::sin(thetaRad), 0.0f, -std::cos(thetaRad)};

        float outU = -1.0f;
        float outV = -1.0f;
        float t = rayHitsCylinder(transform, radius, centralAngle, aspectRatio, outU, outV, rayOrigin, rayDir);

        assert(t > 0.0f);
        assert(Near(t, radius, 0.02f));

        float expectedU = 0.5f + (thetaDeg / 80.0f);
        assert(Near(outU, expectedU, 0.005f));
        assert(Near(outV, 0.5f, 0.005f));
    }

    std::cout << "[PASS] TestUiCylinderAnglesAndUv\n";
}

static void TestUiCylinderMissOutsideArc() {
    const float radius = kUiCylinderRadius;
    const float centralAngle = kUiCylinderCentralAngle;
    const float aspectRatio = 1920.0f / 960.0f;
    const Mat4 transform = Mat4Translation(kBaseUiPos.x, kBaseUiPos.y, kBaseUiPos.z);
    const XrVector3f rayOrigin = {0.0f, 1.36f, 0.0f};

    const float missAnglesDeg[] = {40.5f, -40.5f, 41.0f, -41.0f, 45.0f, -45.0f, 60.0f, -60.0f, 90.0f, -90.0f};

    for (float thetaDeg : missAnglesDeg) {
        float thetaRad = thetaDeg * (kPi / 180.0f);
        XrVector3f rayDir = {std::sin(thetaRad), 0.0f, -std::cos(thetaRad)};

        float outU = -1.0f;
        float outV = -1.0f;
        float t = rayHitsCylinder(transform, radius, centralAngle, aspectRatio, outU, outV, rayOrigin, rayDir);

        assert(t < 0.0f);
    }

    std::cout << "[PASS] TestUiCylinderMissOutsideArc\n";
}

static void TestUiCylinderVerticalBounds() {
    const float radius = kUiCylinderRadius;
    const float centralAngle = kUiCylinderCentralAngle;
    const float aspectRatio = 1920.0f / 960.0f;
    const Mat4 transform = Mat4Translation(kBaseUiPos.x, kBaseUiPos.y, kBaseUiPos.z);
    const XrVector3f rayOrigin = {0.0f, 1.36f, 0.0f};

    // Height of the UI cylinder: arcLength / aspectRatio = (1.6 * 80 * pi/180) / 2.0 ~= 1.117m
    // Half height ~= 0.5585m. At distance 1.6m, elevation of 30 deg gives dy = 1.6 * sin(30) = 0.8m (miss).
    float upAngleRad = 35.0f * (kPi / 180.0f);
    XrVector3f rayDirUp = {0.0f, std::sin(upAngleRad), -std::cos(upAngleRad)};

    float outU = -1.0f;
    float outV = -1.0f;
    float tUp = rayHitsCylinder(transform, radius, centralAngle, aspectRatio, outU, outV, rayOrigin, rayDirUp);
    assert(tUp < 0.0f);

    float downAngleRad = -35.0f * (kPi / 180.0f);
    XrVector3f rayDirDown = {0.0f, std::sin(downAngleRad), -std::cos(downAngleRad)};
    float tDown = rayHitsCylinder(transform, radius, centralAngle, aspectRatio, outU, outV, rayOrigin, rayDirDown);
    assert(tDown < 0.0f);

    std::cout << "[PASS] TestUiCylinderVerticalBounds\n";
}

static void TestRayPointingBackwardsAndParallel() {
    const float radius = kUiCylinderRadius;
    const float centralAngle = kUiCylinderCentralAngle;
    const float aspectRatio = 2.0f;
    const Mat4 transform = Mat4Translation(kBaseUiPos.x, kBaseUiPos.y, kBaseUiPos.z);
    const XrVector3f rayOrigin = {0.0f, 1.36f, 0.0f};

    float outU = 0.0f;
    float outV = 0.0f;

    // Ray pointing backwards (+Z)
    XrVector3f rayBack = {0.0f, 0.0f, 1.0f};
    assert(rayHitsCylinder(transform, radius, centralAngle, aspectRatio, outU, outV, rayOrigin, rayBack) < 0.0f);

    // Ray pointing straight up (parallel to cylinder axis)
    XrVector3f rayUp = {0.0f, 1.0f, 0.0f};
    assert(rayHitsCylinder(transform, radius, centralAngle, aspectRatio, outU, outV, rayOrigin, rayUp) < 0.0f);

    std::cout << "[PASS] TestRayPointingBackwardsAndParallel\n";
}

static void TestControlsCylinderHitAndMiss() {
    const float radius = kControlsCylinderRadius;             // 1.3m
    const float centralAngle = kControlsCylinderCentralAngle; // 48 deg
    const float aspectRatio = 1582.0f / 800.0f;
    const Mat4 transform = ComputeControlsModelNoScale(kBaseControlsPos, 0.0f, 0.0f);

    // Observer at center of curvature (0.0f, 1.05f, 0.0f)
    const XrVector3f rayOrigin = {0.0f, 1.05f, 0.0f};

    // Hits inside 48 deg span (+-24 deg)
    const float hitAnglesDeg[] = {0.0f, 15.0f, -15.0f, 23.0f, -23.0f};
    for (float thetaDeg : hitAnglesDeg) {
        float thetaRad = thetaDeg * (kPi / 180.0f);
        XrVector3f rayDir = {std::sin(thetaRad), 0.0f, -std::cos(thetaRad)};

        float outU = -1.0f;
        float outV = -1.0f;
        float t = rayHitsCylinder(transform, radius, centralAngle, aspectRatio, outU, outV, rayOrigin, rayDir);
        assert(t > 0.0f);

        float expectedU = 0.5f + (thetaDeg / 48.0f);
        assert(Near(outU, expectedU, 0.005f));
    }

    // Misses outside 48 deg span
    const float missAnglesDeg[] = {25.0f, -25.0f, 35.0f, -35.0f};
    for (float thetaDeg : missAnglesDeg) {
        float thetaRad = thetaDeg * (kPi / 180.0f);
        XrVector3f rayDir = {std::sin(thetaRad), 0.0f, -std::cos(thetaRad)};

        float outU = -1.0f;
        float outV = -1.0f;
        float t = rayHitsCylinder(transform, radius, centralAngle, aspectRatio, outU, outV, rayOrigin, rayDir);
        assert(t < 0.0f);
    }

    std::cout << "[PASS] TestControlsCylinderHitAndMiss\n";
}

static void TestDegenerateParameters() {
    float outU = 0.0f;
    float outV = 0.0f;
    XrVector3f origin = {0.0f, 0.0f, 0.0f};
    XrVector3f dir = {0.0f, 0.0f, -1.0f};
    Mat4 identity = Mat4Identity();

    assert(rayHitsCylinder(identity, 0.0f, 1.0f, 1.0f, outU, outV, origin, dir) < 0.0f);
    assert(rayHitsCylinder(identity, 1.0f, 0.0f, 1.0f, outU, outV, origin, dir) < 0.0f);
    assert(rayHitsCylinder(identity, 1.0f, 1.0f, 0.0f, outU, outV, origin, dir) < 0.0f);

    std::cout << "[PASS] TestDegenerateParameters\n";
}

static void TestObserverOutsideCylinderDiameter() {
    const float radius = kControlsCylinderRadius;             // 1.3m (diameter = 2.6m)
    const float centralAngle = kControlsCylinderCentralAngle; // 48 deg
    const float aspectRatio = 1582.0f / 800.0f;
    const Mat4 transform = ComputeControlsModelNoScale(kBaseControlsPos, 0.0f, 0.0f);

    // Observer at distance 2.8m (> 2 * radius = 2.6m): ray hits back cylinder first then front arc
    const XrVector3f rayOrigin = {0.0f, 1.05f, 1.5f}; // 1.5 - (-1.3) = 2.8m
    const XrVector3f rayDir = {0.0f, 0.0f, -1.0f};

    float outU = -1.0f;
    float outV = -1.0f;
    float t = rayHitsCylinder(transform, radius, centralAngle, aspectRatio, outU, outV, rayOrigin, rayDir);

    assert(t > 0.0f);
    assert(Near(t, 2.8f, 0.01f));
    assert(Near(outU, 0.5f, 0.005f));
    assert(Near(outV, 0.5f, 0.005f));

    // Miss from outside diameter when aiming outside the central arc
    float thetaRad = 26.0f * (kPi / 180.0f);
    XrVector3f rayDirMiss = {std::sin(thetaRad), 0.0f, -std::cos(thetaRad)};
    float tMiss = rayHitsCylinder(transform, radius, centralAngle, aspectRatio, outU, outV, rayOrigin, rayDirMiss);
    assert(tMiss < 0.0f);

    std::cout << "[PASS] TestObserverOutsideCylinderDiameter\n";
}

static void TestControlsCylinderWithPitchAndYaw() {
    const float radius = kControlsCylinderRadius;
    const float centralAngle = kControlsCylinderCentralAngle;
    const float aspectRatio = 1582.0f / 800.0f;
    const float yawRad = 45.0f * (kPi / 180.0f);
    const float pitch = kControlsPitch;

    const XrVector3f center = ComputeControlsCenter({0.0f, 0.0f, 0.0f}, yawRad);
    const Mat4 transform = ComputeControlsModelNoScale(center, yawRad, pitch);

    const XrVector3f eyePos = {0.0f, 1.5f, 0.0f};
    XrVector3f rayDir = Vec3Sub(center, eyePos);
    float len = Vec3Length(rayDir);
    assert(len > 1e-4f);
    rayDir = Vec3Scale(rayDir, 1.0f / len);

    float outU = -1.0f;
    float outV = -1.0f;
    float t = rayHitsCylinder(transform, radius, centralAngle, aspectRatio, outU, outV, eyePos, rayDir);

    assert(t > 0.0f);
    assert(Near(t, len, 0.01f));
    assert(Near(outU, 0.5f, 0.01f));
    assert(Near(outV, 0.5f, 0.01f));

    std::cout << "[PASS] TestControlsCylinderWithPitchAndYaw\n";
}

int main() {
    std::cout << "--- Executando testes unitarios de cylinder_hit.h ---\n";
    TestUiCylinderAnglesAndUv();
    TestUiCylinderMissOutsideArc();
    TestUiCylinderVerticalBounds();
    TestRayPointingBackwardsAndParallel();
    TestControlsCylinderHitAndMiss();
    TestDegenerateParameters();
    TestObserverOutsideCylinderDiameter();
    TestControlsCylinderWithPitchAndYaw();
    std::cout << "--- Todos os testes de cylinder_hit.h passaram com sucesso! ---\n";
    return 0;
}
