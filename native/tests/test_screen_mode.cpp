#include "polar_mode.h"
#include "screen_mode.h"

#include <cassert>
#include <cmath>
#include <cstring>
#include <iostream>
#include <vector>

static void TestScreenModeIndices() {
    assert(static_cast<uint32_t>(ScreenMode::Flat2D) == 0);
    assert(static_cast<uint32_t>(ScreenMode::SBS) == 1);
    assert(static_cast<uint32_t>(ScreenMode::SBSHalf) == 2);
    assert(static_cast<uint32_t>(ScreenMode::OU) == 3);
    assert(static_cast<uint32_t>(ScreenMode::OUHalf) == 4);
    assert(static_cast<uint32_t>(ScreenMode::Sphere360) == 5);
    assert(static_cast<uint32_t>(ScreenMode::Sphere180) == 6);
    assert(static_cast<uint32_t>(ScreenMode::Sphere360SBS) == 7);
    assert(static_cast<uint32_t>(ScreenMode::Sphere360OU) == 8);
    assert(static_cast<uint32_t>(ScreenMode::Vr180SBS) == 9);
    assert(static_cast<uint32_t>(ScreenMode::Cubemap3x2) == 10);
    assert(static_cast<uint32_t>(ScreenMode::Cubemap6x1) == 11);
    assert(static_cast<uint32_t>(ScreenMode::EAC3x2) == 12);
    assert(static_cast<uint32_t>(ScreenMode::Cubemap3x2SBS) == 13);
    assert(static_cast<uint32_t>(ScreenMode::EAC3x2SBS) == 14);
    assert(static_cast<uint32_t>(ScreenMode::Fisheye190) == 15);
    assert(static_cast<uint32_t>(ScreenMode::Fisheye190SBS) == 16);
    std::cout << "[PASS] TestScreenModeIndices\n";
}

static void TestScreenModeNames() {
    assert(std::strcmp(ScreenModeName(ScreenMode::Flat2D), "Flat2D") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::SBS), "SBS") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::SBSHalf), "SBSHalf") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::OU), "OU") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::OUHalf), "OUHalf") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Sphere360), "Sphere360") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Sphere180), "Sphere180") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Sphere360SBS), "Sphere360SBS") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Sphere360OU), "Sphere360OU") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Vr180SBS), "Vr180SBS") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Cubemap3x2), "Cubemap3x2") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Cubemap6x1), "Cubemap6x1") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::EAC3x2), "EAC3x2") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Cubemap3x2SBS), "Cubemap3x2SBS") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::EAC3x2SBS), "EAC3x2SBS") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Fisheye190), "Fisheye190") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Fisheye190SBS), "Fisheye190SBS") == 0);
    std::cout << "[PASS] TestScreenModeNames\n";
}

static void TestScreenModePredicates() {
    for (uint32_t i = 0; i <= 16; i++) {
        ScreenMode m = static_cast<ScreenMode>(i);
        if (i < 5) {
            assert(!IsSphereMode(m));
        } else {
            assert(IsSphereMode(m));
        }

        if (i >= 1 && i <= 4) {
            assert(IsFlatStereoMode(m));
        } else {
            assert(!IsFlatStereoMode(m));
        }

        if (i >= 10 && i <= 14) {
            assert(IsCubemapMode(m));
        } else {
            assert(!IsCubemapMode(m));
        }

        if (i == 15 || i == 16) {
            assert(IsFisheyeMode(m));
        } else {
            assert(!IsFisheyeMode(m));
        }
    }
    std::cout << "[PASS] TestScreenModePredicates\n";
}

static void TestPolarModeEncodeDecode() {
    const int angles[] = {180, 190, 200, 220};
    const int polarModes[] = {0, 1, 2};

    for (int angle : angles) {
        for (int mode : polarModes) {
            int packed = EncodePolar180(mode, angle);
            assert(DecodePolarMode(packed) == mode);
            assert(DecodeFisheyeFov(packed) == angle);
        }
    }

    // Default/fallback: angle omitted or upper bits 0
    for (int mode : polarModes) {
        int packedDefault = EncodePolar180(mode);
        assert(DecodePolarMode(packedDefault) == mode);
        assert(DecodeFisheyeFov(packedDefault) == 190);

        // Raw mode integer with 0 in upper bits
        assert(DecodePolarMode(mode) == mode);
        assert(DecodeFisheyeFov(mode) == 190);
    }

    std::cout << "[PASS] TestPolarModeEncodeDecode\n";
}

static void TestGenerateFisheyeHemisphereMesh() {
    const float testFovs[] = {180.0f, 190.0f, 200.0f, 220.0f};
    const int ringCounts[] = {16, 32, 64};
    const int sectorCounts[] = {32, 64, 128};

    constexpr float kPi = 3.14159265358979323846f;
    constexpr float kTexelThreshold = 1.0f / 3840.0f; // Acceptance criterion: < 1 texel error on 3840 frame

    for (float fov : testFovs) {
        for (size_t config = 0; config < 3; ++config) {
            const int rings = ringCounts[config];
            const int sectors = sectorCounts[config];

            std::vector<FisheyeVertex> vertices;
            std::vector<uint32_t> indices;
            GenerateFisheyeHemisphereMesh(fov, rings, sectors, vertices, indices);

            // 1. Verify vertex count
            const size_t expectedVertexCount = 1 + static_cast<size_t>(rings) * static_cast<size_t>(sectors);
            assert(vertices.size() == expectedVertexCount);

            // 2. Verify triangle index count and validity
            const size_t expectedTriangles = static_cast<size_t>(2 * rings - 1) * static_cast<size_t>(sectors);
            assert(indices.size() == expectedTriangles * 3);
            for (size_t i = 0; i < indices.size(); i += 3) {
                uint32_t i0 = indices[i];
                uint32_t i1 = indices[i + 1];
                uint32_t i2 = indices[i + 2];
                assert(i0 < vertices.size());
                assert(i1 < vertices.size());
                assert(i2 < vertices.size());
                assert(i0 != i1 && i1 != i2 && i0 != i2);
            }

            // 3. Verify smooth pole handling (apex vertex at index 0)
            const FisheyeVertex &apex = vertices[0];
            assert(std::abs(apex.x) < 1e-6f);
            assert(std::abs(apex.y) < 1e-6f);
            assert(std::abs(apex.z - (-1.0f)) < 1e-6f);
            assert(std::abs(apex.u - 0.5f) < 1e-6f);
            assert(std::abs(apex.v - 0.5f) < 1e-6f);

            // Verify pole fan connectivity
            for (int s = 0; s < sectors; ++s) {
                assert(indices[s * 3] == 0);
            }

            // 4. Verify normalized vertex positions and radial UV accuracy against analytical equidistant formula
            const float kMaxHalfFov = (fov * kPi / 180.0f) * 0.5f;

            for (size_t idx = 0; idx < vertices.size(); ++idx) {
                const FisheyeVertex &v = vertices[idx];

                // Normalized position on unit sphere (length == 1.0)
                float len = std::sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
                assert(std::abs(len - 1.0f) < 1e-5f);

                // Analytical equidistant projection matching stereo.frag
                float cosPsi = std::clamp(-v.z, -1.0f, 1.0f);
                float psi = std::acos(cosPsi);
                float rNorm = (kMaxHalfFov > 1e-6f) ? (psi / kMaxHalfFov) : 0.0f;
                float rho = std::sqrt(v.x * v.x + v.y * v.y);
                float planeDirX = (rho > 1e-6f) ? (v.x / rho) : 0.0f;
                float planeDirY = (rho > 1e-6f) ? (v.y / rho) : 0.0f;
                float expectedU = 0.5f + 0.5f * rNorm * planeDirX;
                float expectedV = 0.5f - 0.5f * rNorm * planeDirY;

                assert(std::abs(v.u - expectedU) < kTexelThreshold);
                assert(std::abs(v.v - expectedV) < kTexelThreshold);

                // Check outermost ring boundary
                if (idx > vertices.size() - static_cast<size_t>(sectors)) {
                    float distUv = std::sqrt((v.u - 0.5f) * (v.u - 0.5f) + (v.v - 0.5f) * (v.v - 0.5f));
                    assert(std::abs(distUv - 0.5f) < 1e-5f);
                    assert(std::abs(psi - kMaxHalfFov) < 1e-5f);
                }
            }
        }
    }

    // Edge cases and parameter clamping
    {
        std::vector<FisheyeVertex> vertices;
        std::vector<uint32_t> indices;
        GenerateFisheyeHemisphereMesh(-50.0f, 0, 1, vertices, indices);
        assert(vertices.size() == 1 + 1 * 3); // clamped rings=1, sectors=3
        assert(indices.size() == (2 * 1 - 1) * 3 * 3);
    }

    std::cout << "[PASS] TestGenerateFisheyeHemisphereMesh (180, 190, 200, 220 deg)\n";
}

int main() {
    std::cout << "--- Executando testes unitarios de screen_mode.h ---\n";
    TestScreenModeIndices();
    TestScreenModeNames();
    TestScreenModePredicates();
    TestPolarModeEncodeDecode();
    TestGenerateFisheyeHemisphereMesh();
    std::cout << "--- Todos os testes de screen_mode.h passaram com sucesso! ---\n";
    return 0;
}
