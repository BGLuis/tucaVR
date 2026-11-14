#pragma once

#include "screen_mode.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace vrplayer {

// Default virtual screen dimensions in meters (16:9 reference)
constexpr float kDefaultBaseScreenWidth = 2.8f;
constexpr float kDefaultBaseScreenHeight = 1.575f;
constexpr float kDefaultAspectRatio = 16.0f / 9.0f;

// Curved cylindrical video screen constants & parameters
constexpr float kMaxScreenCurvatureAngle = 120.0f * (3.14159265358979323846f / 180.0f); // ~2.0943951 rad (120 deg)
constexpr float kCurvatureGentle = 0.25f;                                               // 30 deg span
constexpr float kCurvatureModerate = 0.50f;  // 60 deg span (~natural viewer focal distance)
constexpr float kCurvatureDeep = 0.85f;      // ~102 deg span
constexpr uint32_t kCurvedScreenSlices = 64; // 64 vertical slices = 65 columns, 130 vertices

struct ScreenVertex {
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;
    float u = 0.0f;
    float v = 0.0f;
};

struct CurvedScreenParams {
    float curvature = 0.0f;    // Normalized [0.0, 1.0]
    float centralAngle = 0.0f; // Central angle in radians [0, kMaxScreenCurvatureAngle]
    float radius = 0.0f;       // Curvature radius in meters
    float arcLength = 0.0f;    // Total arc length (screen width W) in meters
    float height = 0.0f;       // Screen height H in meters
    float sagitta = 0.0f;      // Sagitta / depth offset at the edges: R * (1 - cos(theta/2))
    bool isCurved = false;     // True if curvature > 1e-4f
};

struct ScreenScale {
    float x = kDefaultBaseScreenWidth;
    float y = kDefaultBaseScreenHeight;
};

struct PanelTargetExtent {
    uint32_t width = 0;
    uint32_t height = 0;
};

struct SampleAspectRatio {
    uint32_t num = 1;
    uint32_t den = 1;
};

// Unpacks the bridge's `numerator << 32 | denominator` word. A zero component (unknown) means 1:1.
inline SampleAspectRatio UnpackSampleAspectRatio(uint64_t packed) {
    const uint32_t num = static_cast<uint32_t>(packed >> 32);
    const uint32_t den = static_cast<uint32_t>(packed & 0xFFFFFFFFu);
    if (num == 0 || den == 0) {
        return {};
    }
    return {num, den};
}

// Calculates the effective content display aspect ratio (W / H) for a video frame,
// accounting for the sample aspect ratio (anamorphic pixels, e.g. 720x576 flagged 16:9)
// and anamorphic stereoscopic packing modes. Crop rectangles are not applied (issue #176).
inline float CalculateContentAspectRatio(uint32_t videoWidth, uint32_t videoHeight, ScreenMode mode,
                                         SampleAspectRatio sar = {}) {
    if (videoWidth == 0 || videoHeight == 0) {
        return kDefaultAspectRatio;
    }
    if (sar.num == 0 || sar.den == 0) {
        sar = {};
    }

    const float rawAspect = (static_cast<float>(videoWidth) / static_cast<float>(videoHeight)) *
                            (static_cast<float>(sar.num) / static_cast<float>(sar.den));
    if (!std::isfinite(rawAspect) || rawAspect <= 0.001f) {
        return kDefaultAspectRatio;
    }

    float aspect = rawAspect;
    switch (mode) {
    case ScreenMode::SBS:
        // Full Side-by-Side: two full-resolution frames side by side.
        // Raw frame width contains both eyes, so each eye sees half the width.
        aspect = rawAspect * 0.5f;
        break;
    case ScreenMode::OU:
        // Full Over-Under: two full-resolution frames stacked vertically.
        // Raw frame height contains both eyes, so each eye sees half the height.
        aspect = rawAspect * 2.0f;
        break;
    case ScreenMode::SBSHalf:
    case ScreenMode::OUHalf:
    case ScreenMode::Flat2D:
    default:
        // Half SBS/OU have anamorphic compression to fit normal aspect ratio into frame.
        // Content aspect ratio matches container aspect ratio.
        aspect = rawAspect;
        break;
    }

    if (!std::isfinite(aspect) || aspect <= 0.001f) {
        return kDefaultAspectRatio;
    }

    // Clamp to reasonable bounds to prevent rendering anomalies on degenerate video metadata
    return std::clamp(aspect, 0.05f, 20.0f);
}

// Computes proportional fit bounds for a given content aspect ratio,
// constrained within maxBoundWidth and maxBoundHeight while preserving exact geometry.
inline ScreenScale ComputeFitBounds(float contentAspect, float maxBoundWidth = kDefaultBaseScreenWidth,
                                    float maxBoundHeight = kDefaultBaseScreenHeight) {
    if (maxBoundWidth <= 0.001f || maxBoundHeight <= 0.001f) {
        maxBoundWidth = kDefaultBaseScreenWidth;
        maxBoundHeight = kDefaultBaseScreenHeight;
    }
    if (!std::isfinite(contentAspect) || contentAspect <= 0.001f) {
        contentAspect = kDefaultAspectRatio;
    }

    const float boundsAspect = maxBoundWidth / maxBoundHeight;
    ScreenScale scale;

    if (contentAspect >= boundsAspect) {
        // Wider than or equal to bounds (e.g. 16:9, 2.39:1): fit to max width
        scale.x = maxBoundWidth;
        scale.y = maxBoundWidth / contentAspect;
    } else {
        // Taller than bounds (e.g. 4:3, 9:16 vertical): fit to max height
        scale.y = maxBoundHeight;
        scale.x = maxBoundHeight * contentAspect;
    }

    return scale;
}

// Fits content inside the user-chosen screen size. The user's size is a stable 16:9 bounding box of the
// given width, so the visible height never exceeds boundWidth * 9/16 whatever the content aspect.
inline ScreenScale ComputeUserBoundFit(float contentAspect, float boundWidth) {
    return ComputeFitBounds(contentAspect, boundWidth, boundWidth * (9.0f / 16.0f));
}

// Convenience helper: directly calculates fit bounds given video dimensions, screen mode, and bounds
inline ScreenScale CalculateVideoFitBounds(uint32_t videoWidth, uint32_t videoHeight, ScreenMode mode,
                                           float maxBoundWidth = kDefaultBaseScreenWidth,
                                           float maxBoundHeight = kDefaultBaseScreenHeight) {
    const float aspect = CalculateContentAspectRatio(videoWidth, videoHeight, mode);
    return ComputeFitBounds(aspect, maxBoundWidth, maxBoundHeight);
}

// Computes the target video resolution (pixel width and height) adapted to the angular resolution
// of the virtual screen on the physical headset display.
//
// Angular subtense formula:
//   theta = 2.0f * std::atan(size / (2.0f * distance)) * (180.0f / pi)
// Target lines = theta * panelPpd * oversampleFactor
//
// Preserves the video's aspect ratio and ensures that content is never upscaled above its
// native dimensions (videoWidth x videoHeight). Dimensions are aligned to even values for
// hardware video/YCbCr swapchains.
inline PanelTargetExtent ComputePanelTargetVideoExtent(uint32_t videoWidth, uint32_t videoHeight, float screenWidthM,
                                                       float screenHeightM, float distanceM, float panelPpd = 25.0f,
                                                       float oversampleFactor = 2.0f) {
    if (videoWidth == 0 || videoHeight == 0) {
        return {0, 0};
    }
    if (!std::isfinite(distanceM) || distanceM <= 0.001f || !std::isfinite(panelPpd) || panelPpd <= 0.001f ||
        !std::isfinite(oversampleFactor) || oversampleFactor <= 0.001f ||
        (!std::isfinite(screenWidthM) && !std::isfinite(screenHeightM)) ||
        (screenWidthM <= 0.001f && screenHeightM <= 0.001f)) {
        return {videoWidth, videoHeight};
    }

    constexpr float kPi = 3.14159265358979323846f;
    const float videoAspect = static_cast<float>(videoWidth) / static_cast<float>(videoHeight);

    float contentW = 0.0f;
    float contentH = 0.0f;
    if (screenWidthM <= 0.001f) {
        contentH = screenHeightM;
        contentW = screenHeightM * videoAspect;
    } else if (screenHeightM <= 0.001f) {
        contentW = screenWidthM;
        contentH = screenWidthM / videoAspect;
    } else {
        const ScreenScale fit = ComputeFitBounds(videoAspect, screenWidthM, screenHeightM);
        contentW = fit.x;
        contentH = fit.y;
    }

    const float radH = 2.0f * std::atan(contentH / (2.0f * distanceM));
    const float degH = radH * (180.0f / kPi);
    const float targetLinesH = degH * panelPpd * oversampleFactor;

    const float radW = 2.0f * std::atan(contentW / (2.0f * distanceM));
    const float degW = radW * (180.0f / kPi);
    const float targetLinesW = degW * panelPpd * oversampleFactor;

    const float scaleH = targetLinesH / static_cast<float>(videoHeight);
    const float scaleW = targetLinesW / static_cast<float>(videoWidth);
    const float scale = std::min(1.0f, std::max(scaleH, scaleW));

    uint32_t targetH = static_cast<uint32_t>(std::lround(static_cast<float>(videoHeight) * scale));
    uint32_t targetW = static_cast<uint32_t>(std::lround(static_cast<float>(videoWidth) * scale));

    if (targetH > 2 && (targetH % 2 != 0)) {
        targetH = (targetH + 1u <= videoHeight) ? (targetH + 1u) : (targetH - 1u);
    }
    if (targetW > 2 && (targetW % 2 != 0)) {
        targetW = (targetW + 1u <= videoWidth) ? (targetW + 1u) : (targetW - 1u);
    }

    targetW = std::clamp(targetW, 2u, videoWidth);
    targetH = std::clamp(targetH, 2u, videoHeight);

    return {targetW, targetH};
}

// Computes geometric curvature parameters for a cylindrical screen given curvature in [0, 1]
// and screen bounds in meters. Preserves the front apex at local Z = 0.
inline CurvedScreenParams ComputeCurvedScreenParams(float curvature, float screenWidth = kDefaultBaseScreenWidth,
                                                    float screenHeight = kDefaultBaseScreenHeight) {
    CurvedScreenParams p;
    if (screenWidth <= 0.001f || screenHeight <= 0.001f) {
        screenWidth = kDefaultBaseScreenWidth;
        screenHeight = kDefaultBaseScreenHeight;
    }
    p.arcLength = screenWidth;
    p.height = screenHeight;

    if (!std::isfinite(curvature) || curvature <= 1e-4f) {
        p.curvature = 0.0f;
        p.isCurved = false;
        p.centralAngle = 0.0f;
        p.radius = 0.0f;
        p.sagitta = 0.0f;
        return p;
    }

    p.curvature = std::clamp(curvature, 0.0f, 1.0f);
    p.isCurved = true;
    p.centralAngle = p.curvature * kMaxScreenCurvatureAngle;
    p.radius = p.arcLength / p.centralAngle;
    p.sagitta = p.radius * (1.0f - std::cos(p.centralAngle * 0.5f));
    return p;
}

// Generates vertices for a cylindrical triangle strip mesh (cols * 2 vertices).
// Vertices are arranged in triangle strip order: bottom0, top0, bottom1, top1, ...
// The apex (center column) is at local (0, 0, 0), curving towards +Z at edges.
inline void GenerateCurvedScreenVertices(const CurvedScreenParams &params, std::vector<ScreenVertex> &outVertices,
                                         uint32_t slices = kCurvedScreenSlices) {
    if (slices < 1) {
        slices = 1;
    }
    const uint32_t cols = slices + 1;
    outVertices.resize(cols * 2);

    const float halfH = params.height * 0.5f;
    const float halfW = params.arcLength * 0.5f;
    const float halfAngle = params.centralAngle * 0.5f;

    for (uint32_t i = 0; i <= slices; ++i) {
        const float alpha = static_cast<float>(i) / static_cast<float>(slices);
        const float u = alpha;
        float x = 0.0f;
        float z = 0.0f;

        if (params.isCurved && params.radius > 1e-4f && params.centralAngle > 1e-4f) {
            const float phi = -halfAngle + alpha * params.centralAngle;
            x = params.radius * std::sin(phi);
            z = params.radius * (1.0f - std::cos(phi));
        } else {
            x = -halfW + alpha * params.arcLength;
            z = 0.0f;
        }

        // Bottom vertex (v = 1.0)
        outVertices[i * 2 + 0] = {x, -halfH, z, u, 1.0f};
        // Top vertex (v = 0.0)
        outVertices[i * 2 + 1] = {x, +halfH, z, u, 0.0f};
    }
}

struct CurvedPointOffset {
    float x = 0.0f;
    float z = 0.0f;
    float yaw = 0.0f; // Tangent rotation in radians around Y axis
};

// Computes the projected local position and tangent yaw rotation on a curved cylindrical screen
// for a given horizontal flat offset X (meters from screen center), adding an optional normal offset.
inline CurvedPointOffset ProjectToCurvedScreen(const CurvedScreenParams &params, float flatOffsetX,
                                               float normalOffsetZ = 0.0f) {
    CurvedPointOffset res;
    if (!params.isCurved || params.radius <= 1e-4f || params.centralAngle <= 1e-4f) {
        res.x = flatOffsetX;
        res.z = normalOffsetZ;
        res.yaw = 0.0f;
        return res;
    }

    const float halfAngle = params.centralAngle * 0.5f;
    const float phi = std::clamp(flatOffsetX / params.radius, -halfAngle, halfAngle);
    res.x = params.radius * std::sin(phi);
    res.z = params.radius * (1.0f - std::cos(phi)) + normalOffsetZ;
    res.yaw = -phi;
    return res;
}

} // namespace vrplayer
