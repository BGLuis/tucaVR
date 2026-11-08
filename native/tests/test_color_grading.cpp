#include "color_grading.h"

#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

static void TestHableFilmicCurveMonotonicity() {
    float prev = HableFilmicCurve(0.0f);
    assert(std::abs(prev) < 1e-4f);

    for (int i = 1; i <= 2000; i++) {
        float x = static_cast<float>(i) * 0.05f;
        float curr = HableFilmicCurve(x);
        assert(curr > prev); // Strictly monotonic
        prev = curr;
    }
    printf("[PASS] TestHableFilmicCurveMonotonicity\n");
}

static void TestHableFilmicWhitePointAndToe() {
    constexpr float kW = 11.2f;
    float whiteVal = HableFilmicCurve(kW);
    assert(whiteVal > 0.70f && whiteVal < 0.75f);

    constexpr float kW4000 = 40.0f;
    constexpr float kExp = 2.0f;
    float whiteVal4000 = HableFilmicCurve(kW4000 * kExp);
    assert(whiteVal4000 > 0.88f && whiteVal4000 < 0.92f);

    // Toe smoothness: small input produces smooth non-negative curve
    float toeVal = HableFilmicCurve(0.01f);
    assert(toeVal >= 0.0f && toeVal < 0.01f);

    // Shoulder compression: 100 vs 500 should stay bounded
    float high1 = HableFilmicCurve(50.0f);
    float high2 = HableFilmicCurve(200.0f);
    assert(high1 > whiteVal);
    assert(high2 > high1);
    assert(high2 < 1.0f); // Asymptote <= 1.0 (A/A = 1.0)

    printf("[PASS] TestHableFilmicWhitePointAndToe\n");
}

static void TestRgbToSatInvariants() {
    // Pure neutral/grayscale: saturation must be 0.0
    assert(RgbToSat({0.0f, 0.0f, 0.0f}) == 0.0f);
    assert(RgbToSat({0.5f, 0.5f, 0.5f}) == 0.0f);
    assert(RgbToSat({1.0f, 1.0f, 1.0f}) == 0.0f);
    assert(RgbToSat({100.0f, 100.0f, 100.0f}) == 0.0f);

    // Fully saturated primary colors: saturation must be 1.0
    assert(std::abs(RgbToSat({1.0f, 0.0f, 0.0f}) - 1.0f) < 1e-5f);
    assert(std::abs(RgbToSat({0.0f, 2.0f, 0.0f}) - 1.0f) < 1e-5f);
    assert(std::abs(RgbToSat({0.0f, 0.0f, 50.0f}) - 1.0f) < 1e-5f);

    // Intermediate saturation
    // max=10.0, min=5.0 -> (10.0 - 5.0) / 10.0 = 0.5
    float satMid = RgbToSat({10.0f, 5.0f, 7.5f});
    assert(std::abs(satMid - 0.5f) < 1e-5f);

    printf("[PASS] TestRgbToSatInvariants\n");
}

static void TestCubicSCurveInvariants() {
    // Strength 0.0 must be pure identity
    for (float x = 0.0f; x <= 1.0f; x += 0.05f) {
        float res = ApplyCubicSCurve(x, 0.0f);
        assert(std::abs(res - x) < 1e-6f);
    }

    // Anchor points: 0.0, 0.5, and 1.0 must be invariant for any strength
    for (float strength = 0.0f; strength <= 1.0f; strength += 0.1f) {
        assert(std::abs(ApplyCubicSCurve(0.0f, strength) - 0.0f) < 1e-6f);
        assert(std::abs(ApplyCubicSCurve(0.5f, strength) - 0.5f) < 1e-6f);
        assert(std::abs(ApplyCubicSCurve(1.0f, strength) - 1.0f) < 1e-6f);
    }

    // Contrast behavior: deepens shadows (< 0.5) and elevates highlights (> 0.5)
    float shadow = ApplyCubicSCurve(0.25f, 0.5f);
    float highlight = ApplyCubicSCurve(0.75f, 0.5f);
    assert(shadow < 0.25f);
    assert(highlight > 0.75f);

    // Monotonicity of S-curve
    float prev = -0.01f;
    for (int i = 0; i <= 100; i++) {
        float x = static_cast<float>(i) / 100.0f;
        float curr = ApplyCubicSCurve(x, 0.3f);
        assert(curr >= prev);
        prev = curr;
    }

    printf("[PASS] TestCubicSCurveInvariants\n");
}

static void TestHighlightSaturationRecovery() {
    // Saturated HDR light source (fire/neon/explosion):
    // R is very high, G is moderate, B is low
    ColorRgb saturatedHdr = {0.85f, 0.35f, 0.05f}; // PQ code
    ColorRgb mappedSaturated = TonemapHdrToSdr(saturatedHdr, 1);

    // High chromaticity must be preserved: R should dominate G and B
    assert(mappedSaturated.r > mappedSaturated.g * 1.5f);
    assert(mappedSaturated.r > mappedSaturated.b * 3.0f);
    assert(mappedSaturated.r <= 1.0f && mappedSaturated.g <= 1.0f && mappedSaturated.b <= 1.0f);
    assert(mappedSaturated.r >= 0.0f && mappedSaturated.g >= 0.0f && mappedSaturated.b >= 0.0f);

    // Desaturated HDR light source (sun/specular reflection):
    // R, G, B are all high and equal
    ColorRgb whiteHdr = {0.85f, 0.85f, 0.85f};
    ColorRgb mappedWhite = TonemapHdrToSdr(whiteHdr, 1);

    // Channels must remain balanced and bright (pure white specular highlight)
    assert(std::abs(mappedWhite.r - mappedWhite.g) < 0.02f);
    assert(std::abs(mappedWhite.r - mappedWhite.b) < 0.02f);
    assert(mappedWhite.r > 0.85f); // High specular luminance

    printf("[PASS] TestHighlightSaturationRecovery\n");
}

static void TestTonemapHdrToSdrPqAndHlg() {
    // 100 nits (PQ code approx 0.508)
    ColorRgb pq100Nits = {0.508078f, 0.508078f, 0.508078f};
    ColorRgb mappedPq100 = TonemapHdrToSdr(pq100Nits, 1);

    // Diffuse white HDR reference (203 nits per ITU-R BT.2408, PQ code approx 0.5807)
    ColorRgb pq203Nits = {0.580689f, 0.580689f, 0.580689f};
    ColorRgb mappedPq203 = TonemapHdrToSdr(pq203Nits, 1);

    // Under Reinhard, 100 nits was compressed to 0.50 (linear) -> 0.73 (gamma 2.2).
    // Under Hable Filmic + S-Curve calibrated for 4000 nits mastering, diffuse white
    // is rendered vividly around ~0.78 to ~0.82 on Quest 3 display.
    assert(mappedPq100.r > 0.60f && mappedPq100.r < 0.75f);
    assert(mappedPq203.r > 0.75f && mappedPq203.r < 0.85f);
    assert(mappedPq203.r > mappedPq100.r);
    assert(std::abs(mappedPq100.r - mappedPq100.g) < 0.005f);
    assert(std::abs(mappedPq100.g - mappedPq100.b) < 0.005f);

    // HLG diffuse white (HLG code 0.75 is nominal 100% SDR white / 203 nits)
    ColorRgb hlg100Nits = {0.75f, 0.75f, 0.75f};
    ColorRgb mappedHlg100 = TonemapHdrToSdr(hlg100Nits, 2);
    assert(mappedHlg100.r > 0.70f && mappedHlg100.r < 0.90f);

    // 1000 nits highlight (PQ code approx 0.7518)
    ColorRgb pq1000Nits = {0.751827f, 0.751827f, 0.751827f};
    ColorRgb mappedPq1000 = TonemapHdrToSdr(pq1000Nits, 1);
    assert(mappedPq1000.r > mappedPq203.r);

    // 2000 nits highlight (PQ code approx 0.8274)
    ColorRgb pq2000Nits = {0.827425f, 0.827425f, 0.827425f};
    ColorRgb mappedPq2000 = TonemapHdrToSdr(pq2000Nits, 1);
    assert(mappedPq2000.r > mappedPq1000.r);

    // 4000 nits highlight (PQ code approx 0.9026)
    ColorRgb pq4000Nits = {0.902572f, 0.902572f, 0.902572f};
    ColorRgb mappedPq4000 = TonemapHdrToSdr(pq4000Nits, 1);
    assert(mappedPq4000.r > mappedPq2000.r);
    assert(mappedPq4000.r <= 1.0f);

    printf("[PASS] TestTonemapHdrToSdrPqAndHlg\n");
}

static void TestColorTemperature() {
    ColorRgb white = {1.0f, 1.0f, 1.0f};

    // 6500K is neutral reference daylight
    ColorRgb d65 = ApplyColorTemperature(white, 6500.0f);
    assert(std::abs(d65.r - 1.0f) < 0.05f);
    assert(std::abs(d65.g - 1.0f) < 0.05f);
    assert(std::abs(d65.b - 1.0f) < 0.05f);

    // 3000K (Night Mode / warm incandescent)
    ColorRgb warm = ApplyColorTemperature(white, 3000.0f);
    assert(warm.r == 1.0f); // Red fully preserved
    assert(warm.g > 0.6f && warm.g < 0.85f);
    assert(warm.b < 0.5f); // Blue strongly attenuated
    assert(warm.r > warm.g && warm.g > warm.b);

    printf("[PASS] TestColorTemperature\n");
}

static void TestRgbToYcbcr() {
    ColorRgb white = {1.0f, 1.0f, 1.0f};
    ColorRgb ycbcr = RgbToYcbcr(white);
    // White in YCbCr: Y = 1.0, Cb = 0.0, Cr = 0.0
    assert(std::abs(ycbcr.r - 1.0f) < 1e-4f);
    assert(std::abs(ycbcr.g) < 1e-4f);
    assert(std::abs(ycbcr.b) < 1e-4f);

    ColorRgb black = {0.0f, 0.0f, 0.0f};
    ColorRgb ycbcrBlack = RgbToYcbcr(black);
    assert(ycbcrBlack.r == 0.0f && ycbcrBlack.g == 0.0f && ycbcrBlack.b == 0.0f);

    printf("[PASS] TestRgbToYcbcr\n");
}

static void TestColorGradingConstantsParity() {
    using namespace color_grading;
    assert(kHableA == 0.15f);
    assert(kHableB == 0.50f);
    assert(kHableC == 0.10f);
    assert(kHableD == 0.20f);
    assert(kHableE == 0.02f);
    assert(kHableF == 0.30f);

    assert(kPqM1 == 0.1593017578125f);
    assert(kPqM2 == 78.84375f);
    assert(kPqC1 == 0.8359375f);
    assert(kPqC2 == 18.8515625f);
    assert(kPqC3 == 18.6875f);

    assert(kW == 40.0f);
    assert(kExposure == 2.0f);
    assert(kSCurveStrength == 0.18f);

    printf("[PASS] TestColorGradingConstantsParity\n");
}

static void TestSrgbEotfRoundTripsEightBitCodes() {
    // Every 8-bit code, including the linear toe (codes 0..10), must survive EOTF -> hardware OETF.
    for (int code = 0; code <= 255; ++code) {
        float v = static_cast<float>(code) / 255.0f;
        float encoded = SrgbOetf(SrgbEotf(v));
        assert(std::abs(encoded - v) < 1e-5f);
        assert(static_cast<int>(std::lround(encoded * 255.0f)) == code);
    }
    // Segment boundary and anchors.
    assert(SrgbEotf(0.0f) == 0.0f);
    assert(std::abs(SrgbEotf(1.0f) - 1.0f) < 1e-6f);
    assert(std::abs(SrgbEotf(0.04045f) - 0.04045f / 12.92f) < 1e-6f);
    // A known midtone: sRGB 0.5 is 21.4% linear. Plain pow 2.2 would give 0.2176.
    assert(std::abs(SrgbEotf(0.5f) - 0.21404f) < 1e-4f);
    printf("[PASS] TestSrgbEotfRoundTripsEightBitCodes\n");
}

int main() {
    printf("--- Executing unit tests for color_grading.h ---\n");
    TestHableFilmicCurveMonotonicity();
    TestHableFilmicWhitePointAndToe();
    TestRgbToSatInvariants();
    TestCubicSCurveInvariants();
    TestHighlightSaturationRecovery();
    TestTonemapHdrToSdrPqAndHlg();
    TestColorTemperature();
    TestRgbToYcbcr();
    TestColorGradingConstantsParity();
    TestSrgbEotfRoundTripsEightBitCodes();
    printf("--- All color_grading.h unit tests passed successfully! ---\n");
    return 0;
}
