#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

// Cinematic Color Grading & Tone Mapping (Hable Filmic on max(R, G, B) + Cubic Contrast S-Curve).
// Provides the exact C++ reference for functions executed in Vulkan shaders (video.frag, stereo.frag,
// stereo_cubemap.frag, video_common.glsl) for host unit testing under ASan/UBSan and native processing.

namespace color_grading {
// Hable Filmic constants
constexpr float kHableA = 0.15f;
constexpr float kHableB = 0.50f;
constexpr float kHableC = 0.10f;
constexpr float kHableD = 0.20f;
constexpr float kHableE = 0.02f;
constexpr float kHableF = 0.30f;

// ST 2084 (PQ) EOTF constants
constexpr float kPqM1 = 0.1593017578125f;
constexpr float kPqM2 = 78.84375f;
constexpr float kPqC1 = 0.8359375f;
constexpr float kPqC2 = 18.8515625f;
constexpr float kPqC3 = 18.6875f;

// Cinematic HDR tonemapping constants
constexpr float kW = 40.0f;
constexpr float kExposure = 3.0f;
constexpr float kSCurveStrength = 0.18f;

// Viewer brightness multiplier on the exposure. Mirrored by HdrLuminanceStore in Kotlin.
constexpr float kHdrLuminanceMin = 0.5f;
constexpr float kHdrLuminanceDefault = 1.0f;
constexpr float kHdrLuminanceMax = 2.0f;
} // namespace color_grading

struct ColorRgb {
    float r = 0.0f;
    float g = 0.0f;
    float b = 0.0f;

    bool operator==(const ColorRgb &o) const { return r == o.r && g == o.g && b == o.b; }
};

// RGB to YCbCr conversion (ITU-R BT.601) for color distance and chroma key measurement
inline ColorRgb RgbToYcbcr(ColorRgb rgb) {
    float y = 0.299f * rgb.r + 0.587f * rgb.g + 0.114f * rgb.b;
    float cb = -0.168736f * rgb.r - 0.331264f * rgb.g + 0.5f * rgb.b;
    float cr = 0.5f * rgb.r - 0.418688f * rgb.g - 0.081312f * rgb.b;
    return {y, cb, cr};
}

// Hable Filmic curve (Uncharted 2 / John Hable)
// Constants:
//   A = 0.15 (Shoulder Strength)
//   B = 0.50 (Linear Strength)
//   C = 0.10 (Linear Angle)
//   D = 0.20 (Toe Strength)
//   E = 0.02 (Toe Numerator)
//   F = 0.30 (Toe Denominator)
inline float HableFilmicCurve(float x) {
    using namespace color_grading;
    float num = x * (kHableA * x + kHableC * kHableB) + kHableD * kHableE;
    float den = x * (kHableA * x + kHableB) + kHableD * kHableF;
    if (std::abs(den) < 1e-6f) {
        return 0.0f;
    }
    return (num / den) - (kHableE / kHableF);
}

// Exact piecewise sRGB EOTF (IEC 61966-2-1), mirroring SrgbEotf() in video.frag, stereo.frag and
// stereo_cubemap.frag. The eye swapchain is *_SRGB, so shaders linearize the gamma-coded video value
// and the hardware sRGB OETF restores it on write.
inline float SrgbEotf(float c) {
    c = std::clamp(c, 0.0f, 1.0f);
    return (c <= 0.04045f) ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f);
}

inline ColorRgb SrgbEotf(ColorRgb c) {
    return {SrgbEotf(c.r), SrgbEotf(c.g), SrgbEotf(c.b)};
}

// Exact piecewise sRGB OETF: what the GPU applies when writing to an *_SRGB attachment.
inline float SrgbOetf(float l) {
    l = std::clamp(l, 0.0f, 1.0f);
    return (l <= 0.0031308f) ? l * 12.92f : 1.055f * std::pow(l, 1.0f / 2.4f) - 0.055f;
}

// Adaptive Polynomial Cubic S-Curve for contrast
// f(x) = x * x * (3.0 - 2.0 * x)
// Fixed anchors at black (0.0), mid-tone (0.5), and white (1.0).
// Eliminates mid-tone gamma drift while preserving smooth toe and shoulder rolloff.
inline float ApplyCubicSCurve(float x, float strength) {
    if (strength <= 0.0f) {
        return x;
    }
    float c = std::clamp(x, 0.0f, 1.0f);
    float s = c * c * (3.0f - 2.0f * c);
    return c + strength * (s - c);
}

// ST 2084 (PQ EOTF): converts PQ signal [0.0, 1.0] to relative linear luminance [0.0, 1.0],
// where 1.0 corresponds to the theoretical peak of 10,000 nits.
inline ColorRgb PqEotf(ColorRgb pq) {
    using namespace color_grading;

    auto evalChannel = [&](float v) -> float {
        float p = std::pow(std::clamp(v, 0.0f, 1.0f), 1.0f / kPqM2);
        float num = std::max(p - kPqC1, 0.0f);
        float den = std::max(kPqC2 - kPqC3 * p, 1e-6f);
        return std::pow(num / den, 1.0f / kPqM1);
    };

    return {evalChannel(pq.r), evalChannel(pq.g), evalChannel(pq.b)};
}

// BT.2100 HLG: inverse OETF -> scene light -> system OOTF (gamma 1.2,
// 1000 nits nominal peak) -> display nits.
inline ColorRgb HlgToDisplayNits(ColorRgb hlg) {
    constexpr float a = 0.17883277f;
    constexpr float b = 0.28466892f;
    constexpr float c = 0.55991073f;

    auto channelSceneLight = [&](float e) -> float {
        float val = std::clamp(e, 0.0f, 1.0f);
        if (val <= 0.5f) {
            return val * val / 3.0f;
        }
        return (std::exp((val - c) / a) + b) / 12.0f;
    };

    ColorRgb sceneLight = {channelSceneLight(hlg.r), channelSceneLight(hlg.g), channelSceneLight(hlg.b)};

    float ys = sceneLight.r * 0.2627f + sceneLight.g * 0.6780f + sceneLight.b * 0.0593f;
    float scale = std::pow(std::max(ys, 1e-6f), 0.2f) * 1000.0f;

    return {sceneLight.r * scale, sceneLight.g * scale, sceneLight.b * scale};
}

// BT.2020 -> BT.709 primaries (ITU-R BT.2087) in linear light.
inline ColorRgb Bt2020ToBt709(ColorRgb bt2020) {
    return {1.6605f * bt2020.r - 0.5876f * bt2020.g - 0.0728f * bt2020.b,
            -0.1246f * bt2020.r + 1.1329f * bt2020.g - 0.0083f * bt2020.b,
            -0.0182f * bt2020.r - 0.1006f * bt2020.g + 1.1187f * bt2020.b};
}

// HDR -> linear SDR, before the display gamma (PQ / HLG for Quest 3 SDR display).
// `transfer`: 1 = PQ (ST 2084), 2 = HLG (BT.2100).
// `luminance`: viewer brightness multiplier on the exposure, 1.0 = neutral.
inline ColorRgb TonemapHdrToLinear(ColorRgb hdrColor, int transfer,
                                   float luminance = color_grading::kHdrLuminanceDefault) {
    using namespace color_grading;
    ColorRgb nits = (transfer == 2) ? HlgToDisplayNits(hdrColor)
                                    : ColorRgb{PqEotf(hdrColor).r * 10000.0f, PqEotf(hdrColor).g * 10000.0f,
                                               PqEotf(hdrColor).b * 10000.0f};

    ColorRgb nitsDiv100 = {nits.r / 100.0f, nits.g / 100.0f, nits.b / 100.0f};
    ColorRgb scene709 = Bt2020ToBt709(nitsDiv100);
    ColorRgb scene = {std::max(scene709.r, 0.0f), std::max(scene709.g, 0.0f), std::max(scene709.b, 0.0f)};

    // Linear white point W calibrated for cinema mastering peak (4,000 nits). It moves with the exposure so
    // 4,000 nits stays at 1.0 at any luminance setting.
    float exposure = kExposure * luminance;
    float whiteScale = 1.0f / std::max(HableFilmicCurve(kW * exposure), 1e-4f);

    // Map the largest channel and scale the others by the same ratio, which keeps R:G:B and so the hue.
    float peak = std::max({scene.r, scene.g, scene.b});
    float mappedPeak = std::clamp(HableFilmicCurve(peak * exposure) * whiteScale, 0.0f, 1.0f);
    float ratio = mappedPeak / std::max(peak, 1e-5f);

    return {scene.r * ratio, scene.g * ratio, scene.b * ratio};
}

// Cinematic HDR -> SDR Tonemapping: TonemapHdrToLinear, display gamma, then contrast.
inline ColorRgb TonemapHdrToSdr(ColorRgb hdrColor, int transfer,
                                float luminance = color_grading::kHdrLuminanceDefault) {
    using namespace color_grading;
    ColorRgb tonemapped = TonemapHdrToLinear(hdrColor, transfer, luminance);

    ColorRgb gammaColor = {std::pow(tonemapped.r, 1.0f / 2.2f), std::pow(tonemapped.g, 1.0f / 2.2f),
                           std::pow(tonemapped.b, 1.0f / 2.2f)};

    // On the peak for the same reason as the curve: a per-channel S-curve would shift the hue again.
    float gammaPeak = std::max({gammaColor.r, gammaColor.g, gammaColor.b});
    float contrastRatio = ApplyCubicSCurve(gammaPeak, kSCurveStrength) / std::max(gammaPeak, 1e-5f);

    return {gammaColor.r * contrastRatio, gammaColor.g * contrastRatio, gammaColor.b * contrastRatio};
}

// Planckian locus approximation (Kelvin 2700K to 6500K / Night Mode ~3000K)
inline ColorRgb ApplyColorTemperature(ColorRgb color, float tempK) {
    float t = tempK / 100.0f;
    ColorRgb tempColor;

    if (t <= 66.0f) {
        tempColor.r = 1.0f;
    } else {
        tempColor.r = std::clamp(1.292f * std::pow(t - 60.0f, -0.1332f), 0.0f, 1.0f);
    }

    if (t <= 66.0f) {
        tempColor.g = std::clamp(0.3901f * std::log(std::max(t, 1.0f)) - 0.6318f, 0.0f, 1.0f);
    } else {
        tempColor.g = std::clamp(1.130f * std::pow(t - 60.0f, -0.0755f), 0.0f, 1.0f);
    }

    if (t >= 66.0f) {
        tempColor.b = 1.0f;
    } else if (t <= 19.0f) {
        tempColor.b = 0.0f;
    } else {
        tempColor.b = std::clamp(0.5432f * std::log(std::max(t - 10.0f, 1.0f)) - 1.1962f, 0.0f, 1.0f);
    }

    return {color.r * tempColor.r, color.g * tempColor.g, color.b * tempColor.b};
}

// Trilinear sampling of a 3D RGBA8 LUT table (size x size x size entries).
// Ordering: for b in 0..size { for g in 0..size { for r in 0..size { ... } } }
// Matches Vulkan normalized coordinate half-texel mapping:
//   coord = (clamp(val, 0.0, 1.0) * (size - 1) + 0.5) / size
// Output is in [0.0, 1.0].
inline void SampleLut3DTrilinear(const uint8_t *rgba8, uint32_t size, float r, float g, float b, float &outR,
                                 float &outG, float &outB) {
    if (rgba8 == nullptr || size < 2) {
        outR = std::clamp(r, 0.0f, 1.0f);
        outG = std::clamp(g, 0.0f, 1.0f);
        outB = std::clamp(b, 0.0f, 1.0f);
        return;
    }

    float clR = std::clamp(r, 0.0f, 1.0f);
    float clG = std::clamp(g, 0.0f, 1.0f);
    float clB = std::clamp(b, 0.0f, 1.0f);

    float span = static_cast<float>(size - 1);
    float x = clR * span;
    float y = clG * span;
    float z = clB * span;

    uint32_t i0 = static_cast<uint32_t>(x);
    uint32_t i1 = std::min(i0 + 1, size - 1);
    float fx = x - static_cast<float>(i0);

    uint32_t j0 = static_cast<uint32_t>(y);
    uint32_t j1 = std::min(j0 + 1, size - 1);
    float fy = y - static_cast<float>(j0);

    uint32_t k0 = static_cast<uint32_t>(z);
    uint32_t k1 = std::min(k0 + 1, size - 1);
    float fz = z - static_cast<float>(k0);

    auto sampleAt = [rgba8, size](uint32_t i, uint32_t j, uint32_t k) -> ColorRgb {
        size_t idx = ((static_cast<size_t>(k) * size + j) * size + i) * 4;
        return {
            static_cast<float>(rgba8[idx + 0]) / 255.0f,
            static_cast<float>(rgba8[idx + 1]) / 255.0f,
            static_cast<float>(rgba8[idx + 2]) / 255.0f,
        };
    };

    ColorRgb c000 = sampleAt(i0, j0, k0);
    ColorRgb c100 = sampleAt(i1, j0, k0);
    ColorRgb c010 = sampleAt(i0, j1, k0);
    ColorRgb c110 = sampleAt(i1, j1, k0);
    ColorRgb c001 = sampleAt(i0, j0, k1);
    ColorRgb c101 = sampleAt(i1, j0, k1);
    ColorRgb c011 = sampleAt(i0, j1, k1);
    ColorRgb c111 = sampleAt(i1, j1, k1);

    auto lerpColor = [](ColorRgb a, ColorRgb b, float t) -> ColorRgb {
        return {
            a.r + (b.r - a.r) * t,
            a.g + (b.g - a.g) * t,
            a.b + (b.b - a.b) * t,
        };
    };

    ColorRgb c00 = lerpColor(c000, c100, fx);
    ColorRgb c10 = lerpColor(c010, c110, fx);
    ColorRgb c01 = lerpColor(c001, c101, fx);
    ColorRgb c11 = lerpColor(c011, c111, fx);

    ColorRgb c0 = lerpColor(c00, c10, fy);
    ColorRgb c1 = lerpColor(c01, c11, fy);

    ColorRgb c = lerpColor(c0, c1, fz);

    outR = c.r;
    outG = c.g;
    outB = c.b;
}
