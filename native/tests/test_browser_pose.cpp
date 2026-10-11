#include "browser_pose.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace vrplayer;

static constexpr float kEpsilon = 1e-4f;
static constexpr float kPi = 3.14159265358979f;

static bool Near(float a, float b, float eps = kEpsilon) {
    return std::fabs(a - b) <= eps;
}

// O recenter define sceneYawOffset como atan2(-fwd.x, -fwd.z) (ver RenderFrame).
static float SceneYawLookingAt(float fwdX, float fwdZ) {
    return std::atan2(-fwdX, -fwdZ);
}

static void TestLocalToWorldRoundTrip() {
    const XrVector3f offset = {0.4f, 0.1f, -1.2f};
    const float yaw = 1.2f;
    const XrVector3f local = {-1.5f, 1.8f, -3.0f};

    const XrVector3f world = BrowserLocalToWorld(local, offset, yaw);
    const XrVector3f back = BrowserWorldToLocal(world, offset, yaw);

    assert(Near(back.x, local.x) && Near(back.y, local.y) && Near(back.z, local.z));
    std::cout << "[PASS] TestLocalToWorldRoundTrip\n";
}

// Motivo do bug: apos o recenter o navegador precisa reaparecer a frente de quem recentrou.
static void TestSpawnIsInFrontOfTheHeadAfterRecenter() {
    const float headings[][2] = {{0.0f, -1.0f}, {1.0f, 0.0f}, {0.0f, 1.0f}, {-1.0f, 0.0f}};
    const XrVector3f head = {2.0f, 1.6f, -3.0f};

    for (const auto &fwd : headings) {
        const float yaw = SceneYawLookingAt(fwd[0], fwd[1]);
        const XrVector3f world = BrowserLocalToWorld(kBrowserSpawnLocalPosition, head, yaw);

        const float expectedX = head.x + fwd[0] * 2.2f;
        const float expectedZ = head.z + fwd[1] * 2.2f;
        assert(Near(world.x, expectedX) && Near(world.z, expectedZ));
    }
    std::cout << "[PASS] TestSpawnIsInFrontOfTheHeadAfterRecenter\n";
}

static void TestSamePanelMovesWithTheSceneWhenYawChanges() {
    const XrVector3f offset = {0.0f, 0.0f, 0.0f};
    const XrVector3f local = {0.0f, 1.5f, -2.2f};

    const XrVector3f before = BrowserLocalToWorld(local, offset, 0.0f);
    const XrVector3f after = BrowserLocalToWorld(local, offset, kPi * 0.5f);

    assert(!Near(before.x, after.x) || !Near(before.z, after.z));
    assert(Near(std::hypot(before.x, before.z), std::hypot(after.x, after.z)));
    std::cout << "[PASS] TestSamePanelMovesWithTheSceneWhenYawChanges\n";
}

static void TestBillboardFacesTheHead() {
    const XrVector3f panel = {0.0f, 1.5f, 0.0f};

    assert(Near(BillboardYaw(panel, {0.0f, 1.6f, 5.0f}, 9.0f), 0.0f));
    assert(Near(BillboardYaw(panel, {5.0f, 1.6f, 0.0f}, 9.0f), kPi * 0.5f));
    assert(Near(BillboardYaw(panel, {-5.0f, 1.6f, 0.0f}, 9.0f), -kPi * 0.5f));
    std::cout << "[PASS] TestBillboardFacesTheHead\n";
}

static void TestBillboardKeepsFallbackWhenHeadIsAbovePanel() {
    const XrVector3f panel = {1.0f, 1.5f, 1.0f};

    assert(Near(BillboardYaw(panel, {1.0f, 3.0f, 1.0f}, 0.7f), 0.7f));
    std::cout << "[PASS] TestBillboardKeepsFallbackWhenHeadIsAbovePanel\n";
}

int main() {
    TestLocalToWorldRoundTrip();
    TestSpawnIsInFrontOfTheHeadAfterRecenter();
    TestSamePanelMovesWithTheSceneWhenYawChanges();
    TestBillboardFacesTheHead();
    TestBillboardKeepsFallbackWhenHeadIsAbovePanel();
    return 0;
}
