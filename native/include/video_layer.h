#pragma once

#include "screen_mode.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

// Pure decisions for presenting flat cinema video as its own OpenXR composition layer (quad or cylinder) instead of
// rasterizing it into the eye buffer. The renderer in vr_player_app_vulkan.cpp executes these decisions; nothing here
// touches Vulkan or OpenXR so the rules can run under the host tests.

namespace vrplayer {

// Per-axis cap for the video layer swapchains, applied on top of the runtime's maxSwapchainImageWidth/Height.
constexpr uint32_t kVideoLayerMaxExtent = 4096;

// Worst case of layers submitted in one xrEndFrame. The frame builder owns an array of exactly this size.
constexpr uint32_t kPassthroughLayerCount = 1;
constexpr uint32_t kVideoLayerMaxCount = 2; // one per eye
constexpr uint32_t kProjectionLayerCount = 1;
constexpr uint32_t kPanelLayerCount = 4;   // home, browser, controls, modal
constexpr uint32_t kPointerLayerCount = 2; // beam, cursor
constexpr uint32_t kFrameLayerCapacity =
    kPassthroughLayerCount + kVideoLayerMaxCount + kProjectionLayerCount + kPanelLayerCount + kPointerLayerCount;

enum class VideoLayerLayout { Mono, SideBySide, OverUnder };

// Spherical modes the compositor can project by itself. Cubemap, EAC and fisheye stay in the eye buffer.
inline bool IsEquirectVideoLayerMode(ScreenMode mode) {
    switch (mode) {
    case ScreenMode::Sphere360:
    case ScreenMode::Sphere180:
    case ScreenMode::Sphere360SBS:
    case ScreenMode::Sphere360OU:
    case ScreenMode::Vr180SBS:
        return true;
    default:
        return false;
    }
}

inline bool IsDirectVideoLayerMode(ScreenMode mode) {
    return mode == ScreenMode::Flat2D || IsFlatStereoMode(mode) || IsEquirectVideoLayerMode(mode);
}

inline VideoLayerLayout VideoLayerLayoutForMode(ScreenMode mode) {
    switch (mode) {
    case ScreenMode::SBS:
    case ScreenMode::SBSHalf:
    case ScreenMode::Sphere360SBS:
    case ScreenMode::Vr180SBS:
        return VideoLayerLayout::SideBySide;
    case ScreenMode::OU:
    case ScreenMode::OUHalf:
    case ScreenMode::Sphere360OU:
        return VideoLayerLayout::OverUnder;
    default:
        return VideoLayerLayout::Mono;
    }
}

// A stereo layout needs one swapchain per eye unless the user forces the mono view, which shows the left eye to both.
inline bool UsesPerEyeVideoLayers(VideoLayerLayout layout, bool monoView) {
    return layout != VideoLayerLayout::Mono && !monoView;
}

struct DirectVideoLayerInputs {
    bool featureEnabled = false;
    ScreenMode screenMode = ScreenMode::Flat2D;
    bool hasVideoFrame = false;
    bool photoActive = false;
    // Effective modes: the renderer only applies them while passthrough is active.
    bool chromaKeyActive = false;
    bool packedAlphaActive = false;
    bool curvedScreen = false;
    bool cylinderSupported = false;
    bool equirectSupported = false;
    // Whether the per-eye content fits a swapchain at full size (see FitsVideoLayerWithoutDownscale).
    bool contentFitsLayer = false;
    bool swapchainsReady = false;
};

// Everything except the swapchains existing, so the renderer only creates them for content that can use them.
inline bool IsVideoLayerCandidate(const DirectVideoLayerInputs &in) {
    if (!in.featureEnabled || !in.hasVideoFrame || in.photoActive || !IsDirectVideoLayerMode(in.screenMode)) {
        return false;
    }
    // Both modes need a per-pixel alpha that the layer path does not produce.
    if (in.chromaKeyActive || in.packedAlphaActive) {
        return false;
    }
    // A sphere shows its pixels close to 1:1, so clamping one would throw detail away; it keeps the eye buffer instead.
    if (IsEquirectVideoLayerMode(in.screenMode)) {
        return in.equirectSupported && in.contentFitsLayer;
    }
    // A curved screen is a cylinder layer; without the extension it stays in the eye buffer.
    return !in.curvedScreen || in.cylinderSupported;
}

inline bool ShouldUseDirectVideoLayer(const DirectVideoLayerInputs &in) {
    return in.swapchainsReady && IsVideoLayerCandidate(in);
}

// Portion of the sphere an equirect layer covers: the center of the image faces the layer pose's forward axis.
struct EquirectLayerAngles {
    float centralHorizontal = 0.0f;
    float upperVertical = 0.0f;
    float lowerVertical = 0.0f;
};

inline EquirectLayerAngles ComputeEquirectLayerAngles(ScreenMode mode) {
    constexpr float kPi = 3.14159265358979323846f;
    const bool hemisphere = mode == ScreenMode::Sphere180 || mode == ScreenMode::Vr180SBS;
    return {hemisphere ? kPi : 2.0f * kPi, kPi * 0.5f, -kPi * 0.5f};
}

struct VideoLayerExtent {
    uint32_t width = 0;
    uint32_t height = 0;
};

// Per-eye content size from the decoded buffer. It ignores the display aspect: Half SBS/OU frames are squeezed on
// purpose and the compositor stretches them to the screen. Clamped to the runtime limits and to kVideoLayerMaxExtent
// on each axis, preserving the aspect ratio.
inline VideoLayerExtent ComputeVideoLayerExtent(uint32_t bufferWidth, uint32_t bufferHeight, VideoLayerLayout layout,
                                                uint32_t runtimeMaxWidth, uint32_t runtimeMaxHeight) {
    if (bufferWidth == 0 || bufferHeight == 0) {
        return {};
    }
    uint32_t width = bufferWidth;
    uint32_t height = bufferHeight;
    if (layout == VideoLayerLayout::SideBySide) {
        width = std::max(1u, width / 2);
    } else if (layout == VideoLayerLayout::OverUnder) {
        height = std::max(1u, height / 2);
    }

    const uint32_t limitWidth =
        std::min(runtimeMaxWidth == 0 ? kVideoLayerMaxExtent : runtimeMaxWidth, kVideoLayerMaxExtent);
    const uint32_t limitHeight =
        std::min(runtimeMaxHeight == 0 ? kVideoLayerMaxExtent : runtimeMaxHeight, kVideoLayerMaxExtent);
    const double scale =
        std::min({1.0, static_cast<double>(limitWidth) / width, static_cast<double>(limitHeight) / height});
    if (scale >= 1.0) {
        return {width, height};
    }
    const auto scaled = [](uint32_t value, double factor, uint32_t limit) {
        return std::clamp(static_cast<uint32_t>(std::lround(value * factor)), 1u, limit);
    };
    return {scaled(width, scale, limitWidth), scaled(height, scale, limitHeight)};
}

inline bool FitsVideoLayerWithoutDownscale(uint32_t bufferWidth, uint32_t bufferHeight, VideoLayerLayout layout,
                                           uint32_t runtimeMaxWidth, uint32_t runtimeMaxHeight) {
    if (bufferWidth == 0 || bufferHeight == 0) {
        return false;
    }
    const uint32_t perEyeWidth = layout == VideoLayerLayout::SideBySide ? std::max(1u, bufferWidth / 2) : bufferWidth;
    const uint32_t perEyeHeight = layout == VideoLayerLayout::OverUnder ? std::max(1u, bufferHeight / 2) : bufferHeight;
    const VideoLayerExtent fitted =
        ComputeVideoLayerExtent(bufferWidth, bufferHeight, layout, runtimeMaxWidth, runtimeMaxHeight);
    return fitted.width == perEyeWidth && fitted.height == perEyeHeight;
}

// Sub-rectangle of the decoded frame an eye's layer shows: uv = frameUv * scale + offset.
struct VideoLayerCrop {
    float offsetU = 0.0f;
    float offsetV = 0.0f;
    float scaleU = 1.0f;
    float scaleV = 1.0f;
};

// `eyeIndex` 0 is the left eye. `swapEyes` exchanges which half each eye receives.
inline VideoLayerCrop ComputeEyeCrop(VideoLayerLayout layout, int eyeIndex, bool swapEyes) {
    const int half = (swapEyes ? 1 - eyeIndex : eyeIndex) != 0 ? 1 : 0;
    VideoLayerCrop crop;
    if (layout == VideoLayerLayout::SideBySide) {
        crop.scaleU = 0.5f;
        crop.offsetU = 0.5f * static_cast<float>(half);
    } else if (layout == VideoLayerLayout::OverUnder) {
        crop.scaleV = 0.5f;
        crop.offsetV = 0.5f * static_cast<float>(half);
    }
    return crop;
}

} // namespace vrplayer
