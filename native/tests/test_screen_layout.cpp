#include "screen_layout.h"

#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>

static bool Near(float a, float b, float eps = 0.001f) {
    return std::fabs(a - b) <= eps;
}

static void TestCalculateContentAspectRatio() {
    using vrplayer::CalculateContentAspectRatio;
    using vrplayer::kDefaultAspectRatio;

    // Zero / invalid inputs fall back to 16:9 default
    assert(Near(CalculateContentAspectRatio(0, 0, ScreenMode::Flat2D), kDefaultAspectRatio));
    assert(Near(CalculateContentAspectRatio(1920, 0, ScreenMode::Flat2D), kDefaultAspectRatio));
    assert(Near(CalculateContentAspectRatio(0, 1080, ScreenMode::Flat2D), kDefaultAspectRatio));

    // Standard 16:9 2D
    assert(Near(CalculateContentAspectRatio(1920, 1080, ScreenMode::Flat2D), 16.0f / 9.0f));
    assert(Near(CalculateContentAspectRatio(3840, 2160, ScreenMode::Flat2D), 16.0f / 9.0f));
    assert(Near(CalculateContentAspectRatio(1280, 720, ScreenMode::Flat2D), 16.0f / 9.0f));

    // 2.39:1 Cinemascope 2D
    assert(Near(CalculateContentAspectRatio(1920, 803, ScreenMode::Flat2D), 1920.0f / 803.0f));
    assert(Near(CalculateContentAspectRatio(3840, 1606, ScreenMode::Flat2D), 3840.0f / 1606.0f));

    // 4:3 Classic 2D
    assert(Near(CalculateContentAspectRatio(1440, 1080, ScreenMode::Flat2D), 4.0f / 3.0f));
    assert(Near(CalculateContentAspectRatio(640, 480, ScreenMode::Flat2D), 4.0f / 3.0f));

    // 1:1 Square 2D
    assert(Near(CalculateContentAspectRatio(1080, 1080, ScreenMode::Flat2D), 1.0f));

    // 9:16 Vertical 2D
    assert(Near(CalculateContentAspectRatio(1080, 1920, ScreenMode::Flat2D), 9.0f / 16.0f));

    // Full SBS: two full frames side-by-side -> raw aspect divided by 2
    // 3840x1080 SBS -> each eye is 1920x1080 (16:9)
    assert(Near(CalculateContentAspectRatio(3840, 1080, ScreenMode::SBS), 16.0f / 9.0f));
    // 3840x803 SBS (Cinemascope) -> each eye is 1920x803 (2.39:1)
    assert(Near(CalculateContentAspectRatio(3840, 803, ScreenMode::SBS), 1920.0f / 803.0f));

    // Half SBS: already squeezed anamorphic 16:9 inside 1920x1080 container
    assert(Near(CalculateContentAspectRatio(1920, 1080, ScreenMode::SBSHalf), 16.0f / 9.0f));

    // Full OU: two full frames stacked vertically -> raw aspect multiplied by 2
    // 1920x2160 OU -> each eye is 1920x1080 (16:9)
    assert(Near(CalculateContentAspectRatio(1920, 2160, ScreenMode::OU), 16.0f / 9.0f));
    // 1920x1606 OU (Cinemascope) -> each eye is 1920x803 (2.39:1)
    assert(Near(CalculateContentAspectRatio(1920, 1606, ScreenMode::OU), 1920.0f / 803.0f));

    // Half OU: already squeezed anamorphic 16:9 inside 1920x1080 container
    assert(Near(CalculateContentAspectRatio(1920, 1080, ScreenMode::OUHalf), 16.0f / 9.0f));

    // Spherical / 360 / VR180 formats are mapped to sphere mesh, content aspect matches raw frame aspect
    assert(Near(CalculateContentAspectRatio(5760, 2880, ScreenMode::Vr180SBS), 5760.0f / 2880.0f));
    assert(Near(CalculateContentAspectRatio(3840, 1920, ScreenMode::Sphere360), 2.0f));

    // Extreme aspect ratios clamped to safe bounds
    float extremeWide = CalculateContentAspectRatio(100000, 1, ScreenMode::Flat2D);
    assert(extremeWide <= 20.0f);
    float extremeTall = CalculateContentAspectRatio(1, 100000, ScreenMode::Flat2D);
    assert(extremeTall >= 0.05f);

    std::cout << "[PASS] TestCalculateContentAspectRatio\n";
}

static void TestComputeFitBounds() {
    using vrplayer::ComputeFitBounds;
    using vrplayer::kDefaultBaseScreenHeight;
    using vrplayer::kDefaultBaseScreenWidth;

    const float baseW = 2.8f;
    const float baseH = 1.575f;

    // 16:9 exactly matches reference bounds
    {
        auto scale = ComputeFitBounds(16.0f / 9.0f, baseW, baseH);
        assert(Near(scale.x, baseW));
        assert(Near(scale.y, baseH));
        assert(Near(scale.x / scale.y, 16.0f / 9.0f));
    }

    // 2.39:1 (Cinemascope) is wider than 16:9 -> fits to max width (2.8m), height decreases
    {
        const float aspect = 2.391f;
        auto scale = ComputeFitBounds(aspect, baseW, baseH);
        assert(Near(scale.x, baseW));
        assert(Near(scale.y, baseW / aspect));
        assert(scale.y < baseH);
        assert(Near(scale.x / scale.y, aspect));
    }

    // 4:3 is narrower than 16:9 -> fits to max height (1.575m), width decreases
    {
        const float aspect = 4.0f / 3.0f;
        auto scale = ComputeFitBounds(aspect, baseW, baseH);
        assert(Near(scale.y, baseH));
        assert(Near(scale.x, baseH * aspect));
        assert(scale.x < baseW);
        assert(Near(scale.x / scale.y, aspect));
    }

    // 9:16 vertical video -> fits to max height (1.575m), width is narrow (~0.886m)
    {
        const float aspect = 9.0f / 16.0f;
        auto scale = ComputeFitBounds(aspect, baseW, baseH);
        assert(Near(scale.y, baseH));
        assert(Near(scale.x, baseH * aspect));
        assert(scale.x < baseW);
        assert(Near(scale.x / scale.y, aspect));
    }

    // Custom environment base bounds (e.g. Cinema room: 4.0m x 2.25m)
    {
        const float cinemaW = 4.0f;
        const float cinemaH = 2.25f;
        const float aspect = 2.391f;
        auto scale = ComputeFitBounds(aspect, cinemaW, cinemaH);
        assert(Near(scale.x, cinemaW));
        assert(Near(scale.y, cinemaW / aspect));
        assert(scale.y < cinemaH);
        assert(Near(scale.x / scale.y, aspect));
    }

    // Degenerate bounds fall back to defaults
    {
        auto scale = ComputeFitBounds(16.0f / 9.0f, 0.0f, 0.0f);
        assert(Near(scale.x, kDefaultBaseScreenWidth));
        assert(Near(scale.y, kDefaultBaseScreenHeight));
    }

    // Invalid aspect ratio falls back to 16:9
    {
        auto scale = ComputeFitBounds(-1.0f, baseW, baseH);
        assert(Near(scale.x, baseW));
        assert(Near(scale.y, baseH));

        auto scaleNan = ComputeFitBounds(std::numeric_limits<float>::quiet_NaN(), baseW, baseH);
        assert(Near(scaleNan.x, baseW));
        assert(Near(scaleNan.y, baseH));

        auto scaleInf = ComputeFitBounds(std::numeric_limits<float>::infinity(), baseW, baseH);
        assert(Near(scaleInf.x, baseW));
        assert(Near(scaleInf.y, baseH));
    }

    std::cout << "[PASS] TestComputeFitBounds\n";
}

static void TestCalculateVideoFitBounds() {
    using vrplayer::CalculateVideoFitBounds;

    // End-to-end: Full SBS 3840x1080 -> 16:9 on 2.8m x 1.575m
    {
        auto scale = CalculateVideoFitBounds(3840, 1080, ScreenMode::SBS, 2.8f, 1.575f);
        assert(Near(scale.x, 2.8f));
        assert(Near(scale.y, 1.575f));
    }

    // End-to-end: Full SBS 3840x803 (Cinemascope) -> 2.39:1
    {
        auto scale = CalculateVideoFitBounds(3840, 803, ScreenMode::SBS, 2.8f, 1.575f);
        assert(Near(scale.x, 2.8f));
        assert(Near(scale.y, 2.8f / (1920.0f / 803.0f)));
        assert(Near(scale.x / scale.y, 1920.0f / 803.0f));
    }

    // End-to-end: Full OU 1920x2160 -> 16:9 on 2.8m x 1.575m
    {
        auto scale = CalculateVideoFitBounds(1920, 2160, ScreenMode::OU, 2.8f, 1.575f);
        assert(Near(scale.x, 2.8f));
        assert(Near(scale.y, 1.575f));
    }

    // End-to-end: 4:3 1440x1080 Flat2D -> width 2.1m, height 1.575m
    {
        auto scale = CalculateVideoFitBounds(1440, 1080, ScreenMode::Flat2D, 2.8f, 1.575f);
        assert(Near(scale.y, 1.575f));
        assert(Near(scale.x, 2.1f));
        assert(Near(scale.x / scale.y, 4.0f / 3.0f));
    }

    // End-to-end: 9:16 vertical 1080x1920 Flat2D -> width 0.8859m, height 1.575m
    {
        auto scale = CalculateVideoFitBounds(1080, 1920, ScreenMode::Flat2D, 2.8f, 1.575f);
        assert(Near(scale.y, 1.575f));
        assert(Near(scale.x, 1.575f * (9.0f / 16.0f)));
        assert(Near(scale.x / scale.y, 9.0f / 16.0f));
    }

    std::cout << "[PASS] TestCalculateVideoFitBounds\n";
}

static void TestAspectRatioPreservationDuringResizing() {
    using vrplayer::CalculateContentAspectRatio;
    using vrplayer::CalculateVideoFitBounds;

    // Simulate resizing a 2.39:1 video from fit bounds (width 2.8m) to custom scale (width 4.0m)
    {
        const uint32_t w = 3840, h = 1606;
        const float aspect = CalculateContentAspectRatio(w, h, ScreenMode::Flat2D);
        auto fit = CalculateVideoFitBounds(w, h, ScreenMode::Flat2D, 2.8f, 1.575f);

        float customWidth = 4.0f;
        float customHeight = customWidth / aspect;

        assert(Near(fit.x / fit.y, aspect));
        assert(Near(customWidth / customHeight, aspect));
        assert(Near(customWidth / customHeight, fit.x / fit.y));
    }

    // Simulate resizing a 4:3 video from fit bounds to custom scale
    {
        const uint32_t w = 1440, h = 1080;
        const float aspect = CalculateContentAspectRatio(w, h, ScreenMode::Flat2D);
        auto fit = CalculateVideoFitBounds(w, h, ScreenMode::Flat2D, 2.8f, 1.575f);

        float customWidth = 3.2f;
        float customHeight = customWidth / aspect;

        assert(Near(fit.x / fit.y, aspect));
        assert(Near(customWidth / customHeight, aspect));
    }

    // Full SBS: resizing preserves un-stretched per-eye aspect ratio
    {
        const uint32_t w = 3840, h = 1080; // Full SBS 16:9
        const float aspect = CalculateContentAspectRatio(w, h, ScreenMode::SBS);
        assert(Near(aspect, 16.0f / 9.0f));

        float customWidth = 5.0f;
        float customHeight = customWidth / aspect;
        assert(Near(customWidth / customHeight, 16.0f / 9.0f));
    }

    // Full OU: resizing preserves un-stretched per-eye aspect ratio
    {
        const uint32_t w = 1920, h = 2160; // Full OU 16:9
        const float aspect = CalculateContentAspectRatio(w, h, ScreenMode::OU);
        assert(Near(aspect, 16.0f / 9.0f));

        float customWidth = 2.0f;
        float customHeight = customWidth / aspect;
        assert(Near(customWidth / customHeight, 16.0f / 9.0f));
    }

    std::cout << "[PASS] TestAspectRatioPreservationDuringResizing\n";
}

static void TestCurvedScreenParamsCalculation() {
    using vrplayer::ComputeCurvedScreenParams;
    using vrplayer::CurvedScreenParams;
    using vrplayer::kCurvatureDeep;
    using vrplayer::kCurvatureGentle;
    using vrplayer::kCurvatureModerate;
    using vrplayer::kMaxScreenCurvatureAngle;

    const float w = 2.8f;
    const float h = 1.575f;

    // Curvature constants verification
    assert(Near(kCurvatureGentle, 0.25f));
    assert(Near(kCurvatureModerate, 0.50f));
    assert(Near(kCurvatureDeep, 0.85f));

    // Curvature = 0 (Flat)
    {
        auto p = ComputeCurvedScreenParams(0.0f, w, h);
        assert(!p.isCurved);
        assert(Near(p.curvature, 0.0f));
        assert(Near(p.centralAngle, 0.0f));
        assert(Near(p.radius, 0.0f));
        assert(Near(p.sagitta, 0.0f));
        assert(Near(p.arcLength, w));
        assert(Near(p.height, h));
    }

    // Curvature = 0.25 (Gentle, ~30 deg span)
    {
        auto p = ComputeCurvedScreenParams(0.25f, w, h);
        assert(p.isCurved);
        assert(Near(p.curvature, 0.25f));
        const float expectedAngle = 0.25f * kMaxScreenCurvatureAngle; // ~0.523598 rad
        assert(Near(p.centralAngle, expectedAngle, 0.002f));
        const float expectedRadius = w / expectedAngle; // ~5.3476m
        assert(Near(p.radius, expectedRadius, 0.01f));
        const float expectedSagitta = expectedRadius * (1.0f - std::cos(expectedAngle * 0.5f)); // ~0.174m
        assert(Near(p.sagitta, expectedSagitta, 0.005f));
        assert(Near(p.arcLength, w));
        assert(Near(p.height, h));
    }

    // Curvature = 0.50 (Moderate, ~60 deg span, radius ~ distance)
    {
        auto p = ComputeCurvedScreenParams(0.50f, w, h);
        assert(p.isCurved);
        assert(Near(p.curvature, 0.50f));
        const float expectedAngle = 0.50f * kMaxScreenCurvatureAngle; // ~1.047197 rad
        assert(Near(p.centralAngle, expectedAngle, 0.002f));
        const float expectedRadius = w / expectedAngle; // ~2.6738m
        assert(Near(p.radius, expectedRadius, 0.01f));
        const float expectedSagitta = expectedRadius * (1.0f - std::cos(expectedAngle * 0.5f));
        assert(Near(p.sagitta, expectedSagitta, 0.005f));
        assert(p.sagitta > 0.3f && p.sagitta < 0.4f);
    }

    // Curvature = 1.0 (Deep, 120 deg span)
    {
        auto p = ComputeCurvedScreenParams(1.0f, w, h);
        assert(p.isCurved);
        assert(Near(p.curvature, 1.0f));
        assert(Near(p.centralAngle, kMaxScreenCurvatureAngle));
        const float expectedRadius = w / kMaxScreenCurvatureAngle; // ~1.3369m
        assert(Near(p.radius, expectedRadius, 0.01f));
        // At 120 deg, half angle is 60 deg, cos(60) = 0.5 -> sagitta = R * 0.5
        assert(Near(p.sagitta, expectedRadius * 0.5f, 0.005f));
    }

    // Edge cases: negative, NaN, Inf, clamp above 1.0
    {
        auto pNeg = ComputeCurvedScreenParams(-0.5f, w, h);
        assert(!pNeg.isCurved);
        assert(Near(pNeg.curvature, 0.0f));

        auto pOver = ComputeCurvedScreenParams(1.5f, w, h);
        assert(pOver.isCurved);
        assert(Near(pOver.curvature, 1.0f));
        assert(Near(pOver.centralAngle, kMaxScreenCurvatureAngle));

        auto pNan = ComputeCurvedScreenParams(std::numeric_limits<float>::quiet_NaN(), w, h);
        assert(!pNan.isCurved);

        auto pInf = ComputeCurvedScreenParams(std::numeric_limits<float>::infinity(), w, h);
        assert(!pInf.isCurved);
    }

    std::cout << "[PASS] TestCurvedScreenParamsCalculation\n";
}

static void TestGenerateCurvedScreenVertices() {
    using vrplayer::ComputeCurvedScreenParams;
    using vrplayer::GenerateCurvedScreenVertices;
    using vrplayer::kCurvedScreenSlices;
    using vrplayer::ScreenVertex;

    const float w = 2.8f;
    const float h = 1.575f;

    // 1. Flat screen mesh (curvature = 0.0f)
    {
        auto params = ComputeCurvedScreenParams(0.0f, w, h);
        std::vector<ScreenVertex> vertices;
        GenerateCurvedScreenVertices(params, vertices, kCurvedScreenSlices);

        assert(vertices.size() == (kCurvedScreenSlices + 1) * 2);

        // Every vertex must have Z = 0.0 (flat plane)
        for (const auto &v : vertices) {
            assert(Near(v.z, 0.0f));
        }

        // Leftmost vertices (i = 0)
        assert(Near(vertices[0].x, -w * 0.5f));
        assert(Near(vertices[0].y, -h * 0.5f));
        assert(Near(vertices[0].u, 0.0f));
        assert(Near(vertices[0].v, 1.0f));

        assert(Near(vertices[1].x, -w * 0.5f));
        assert(Near(vertices[1].y, +h * 0.5f));
        assert(Near(vertices[1].u, 0.0f));
        assert(Near(vertices[1].v, 0.0f));

        // Center vertices (i = 32)
        const uint32_t midIdx = (kCurvedScreenSlices / 2) * 2;
        assert(Near(vertices[midIdx].x, 0.0f));
        assert(Near(vertices[midIdx].u, 0.5f));
        assert(Near(vertices[midIdx + 1].x, 0.0f));
        assert(Near(vertices[midIdx + 1].u, 0.5f));

        // Rightmost vertices (i = 64)
        const uint32_t lastIdx = kCurvedScreenSlices * 2;
        assert(Near(vertices[lastIdx].x, +w * 0.5f));
        assert(Near(vertices[lastIdx].y, -h * 0.5f));
        assert(Near(vertices[lastIdx].u, 1.0f));
        assert(Near(vertices[lastIdx].v, 1.0f));

        assert(Near(vertices[lastIdx + 1].x, +w * 0.5f));
        assert(Near(vertices[lastIdx + 1].y, +h * 0.5f));
        assert(Near(vertices[lastIdx + 1].u, 1.0f));
        assert(Near(vertices[lastIdx + 1].v, 0.0f));
    }

    // 2. Curved screen mesh (curvature = 0.50f Moderate)
    {
        auto params = ComputeCurvedScreenParams(0.50f, w, h);
        std::vector<ScreenVertex> vertices;
        GenerateCurvedScreenVertices(params, vertices, kCurvedScreenSlices);

        assert(vertices.size() == (kCurvedScreenSlices + 1) * 2);

        // Invariant: Apex Preservation (center column must be exactly at Z = 0)
        const uint32_t midIdx = (kCurvedScreenSlices / 2) * 2;
        assert(Near(vertices[midIdx].x, 0.0f));
        assert(Near(vertices[midIdx].z, 0.0f)); // Z_apex = 0
        assert(Near(vertices[midIdx].u, 0.5f));
        assert(Near(vertices[midIdx + 1].x, 0.0f));
        assert(Near(vertices[midIdx + 1].z, 0.0f)); // Z_apex = 0
        assert(Near(vertices[midIdx + 1].u, 0.5f));

        // Invariant: Edges curve towards +Z (closer to viewer) by exactly sagitta
        assert(Near(vertices[0].z, params.sagitta, 0.005f));
        assert(Near(vertices[1].z, params.sagitta, 0.005f));
        const uint32_t lastIdx = kCurvedScreenSlices * 2;
        assert(Near(vertices[lastIdx].z, params.sagitta, 0.005f));
        assert(Near(vertices[lastIdx + 1].z, params.sagitta, 0.005f));

        // Invariant: Left/Right horizontal symmetry
        assert(Near(vertices[0].x, -vertices[lastIdx].x));
        assert(Near(vertices[1].x, -vertices[lastIdx + 1].x));

        // Invariant: Monotonic UV and Z curvature away from center
        float prevU = -0.01f;
        for (uint32_t i = 0; i <= kCurvedScreenSlices; ++i) {
            float curU = vertices[i * 2].u;
            assert(curU >= prevU);
            prevU = curU;

            // Height is uniform
            assert(Near(vertices[i * 2].y, -h * 0.5f));
            assert(Near(vertices[i * 2 + 1].y, +h * 0.5f));
            assert(Near(vertices[i * 2].v, 1.0f));
            assert(Near(vertices[i * 2 + 1].v, 0.0f));

            // Z >= 0 everywhere on the cylinder arc
            assert(vertices[i * 2].z >= -1e-5f);
            assert(vertices[i * 2 + 1].z >= -1e-5f);
        }
    }

    std::cout << "[PASS] TestGenerateCurvedScreenVertices\n";
}

static void TestProjectToCurvedScreen() {
    using vrplayer::ComputeCurvedScreenParams;
    using vrplayer::ProjectToCurvedScreen;

    const float w = 2.8f;
    const float h = 1.575f;

    // 1. Flat screen
    {
        auto params = ComputeCurvedScreenParams(0.0f, w, h);
        auto pCenter = ProjectToCurvedScreen(params, 0.0f, 0.04f);
        assert(Near(pCenter.x, 0.0f));
        assert(Near(pCenter.z, 0.04f));
        assert(Near(pCenter.yaw, 0.0f));

        auto pRight = ProjectToCurvedScreen(params, 1.2f, 0.04f);
        assert(Near(pRight.x, 1.2f));
        assert(Near(pRight.z, 0.04f));
        assert(Near(pRight.yaw, 0.0f));
    }

    // 2. Curved screen (Moderate 0.50f)
    {
        auto params = ComputeCurvedScreenParams(0.50f, w, h);
        auto pCenter = ProjectToCurvedScreen(params, 0.0f, 0.04f);
        assert(Near(pCenter.x, 0.0f));
        assert(Near(pCenter.z, 0.04f));
        assert(Near(pCenter.yaw, 0.0f));

        const float halfW = w * 0.5f;
        auto pEdgeRight = ProjectToCurvedScreen(params, halfW, 0.04f);
        assert(pEdgeRight.x > 0.0f);
        assert(Near(pEdgeRight.z, params.sagitta + 0.04f, 0.005f));
        assert(Near(pEdgeRight.yaw, -params.centralAngle * 0.5f, 0.005f));

        auto pEdgeLeft = ProjectToCurvedScreen(params, -halfW, 0.04f);
        assert(Near(pEdgeLeft.x, -pEdgeRight.x));
        assert(Near(pEdgeLeft.z, pEdgeRight.z));
        assert(Near(pEdgeLeft.yaw, -pEdgeRight.yaw));

        // Subtitle beyond edge clamps to arc angle
        auto pBeyond = ProjectToCurvedScreen(params, halfW + 1.0f, 0.04f);
        assert(Near(pBeyond.x, pEdgeRight.x));
        assert(Near(pBeyond.z, pEdgeRight.z));
        assert(Near(pBeyond.yaw, pEdgeRight.yaw));
    }

    std::cout << "[PASS] TestProjectToCurvedScreen\n";
}

static void TestUserBoundFitKeepsStable16x9Box() {
    using vrplayer::ComputeUserBoundFit;
    const float portrait = 9.0f / 16.0f;
    const float landscape = 16.0f / 9.0f;
    const float widths[] = {0.8f, 2.8f, 4.0f, 8.0f};

    // "Large" (4.0) with a vertical video is limited by the box height, not by the width.
    auto fit = ComputeUserBoundFit(portrait, 4.0f);
    assert(Near(fit.x, 1.266f, 0.002f));
    assert(Near(fit.y, 2.25f));

    // The next 16:9 video goes back to the full chosen width: the vertical fit must not stick.
    fit = ComputeUserBoundFit(landscape, 4.0f);
    assert(Near(fit.x, 4.0f));
    assert(Near(fit.y, 2.25f));

    const float aspects[] = {0.05f, 0.5f, portrait, 1.0f, 4.0f / 3.0f, landscape, 2.39f, 20.0f};
    for (float width : widths) {
        for (float aspect : aspects) {
            const auto f = ComputeUserBoundFit(aspect, width);
            assert(f.y <= width * 9.0f / 16.0f + 1e-4f);
            assert(f.x <= width + 1e-4f);
            assert(Near(f.x / f.y, aspect, aspect * 1e-3f));
        }
    }
    std::cout << "[PASS] TestUserBoundFitKeepsStable16x9Box\n";
}

int main() {
    std::cout << "--- Executing unit tests for screen_layout.h ---\n";
    TestCalculateContentAspectRatio();
    TestComputeFitBounds();
    TestCalculateVideoFitBounds();
    TestAspectRatioPreservationDuringResizing();
    TestUserBoundFitKeepsStable16x9Box();
    TestCurvedScreenParamsCalculation();
    TestGenerateCurvedScreenVertices();
    TestProjectToCurvedScreen();
    std::cout << "--- All screen_layout.h unit tests passed successfully! ---\n";
    return 0;
}
