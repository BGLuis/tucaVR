#pragma once

#include <algorithm>
#include <cmath>

// Tratamento de Cor & Tone Mapping Cinematografico (Hable Filmic + rgb2sat + Curva S de Contraste).
// Fornece a referencia C++ exata das funcoes executadas nos shaders Vulkan (video.frag, stereo.frag,
// stereo_cubemap.frag) para testes de host com AddressSanitizer/UBSan e processamento nativo.

struct ColorRgb {
    float r = 0.0f;
    float g = 0.0f;
    float b = 0.0f;

    bool operator==(const ColorRgb &o) const { return r == o.r && g == o.g && b == o.b; }
};

// Conversao RGB para YCbCr (ITU-R BT.601) para medicao de distancia cromatica e chroma key
inline ColorRgb RgbToYcbcr(ColorRgb rgb) {
    float y = 0.299f * rgb.r + 0.587f * rgb.g + 0.114f * rgb.b;
    float cb = -0.168736f * rgb.r - 0.331264f * rgb.g + 0.5f * rgb.b;
    float cr = 0.5f * rgb.r - 0.418688f * rgb.g - 0.081312f * rgb.b;
    return {y, cb, cr};
}

// Curva cinematografica Hable Filmic (Uncharted 2 / John Hable)
// Constantes:
//   A = 0.15 (Shoulder Strength)
//   B = 0.50 (Linear Strength)
//   C = 0.10 (Linear Angle)
//   D = 0.20 (Toe Strength)
//   E = 0.02 (Toe Numerator)
//   F = 0.30 (Toe Denominator)
inline float HableFilmicCurve(float x) {
    constexpr float A = 0.15f;
    constexpr float B = 0.50f;
    constexpr float C = 0.10f;
    constexpr float D = 0.20f;
    constexpr float E = 0.02f;
    constexpr float F = 0.30f;
    float num = x * (A * x + C * B) + D * E;
    float den = x * (A * x + B) + D * F;
    if (std::abs(den) < 1e-6f) {
        return 0.0f;
    }
    return (num / den) - (E / F);
}

inline ColorRgb HableFilmicCurve(ColorRgb x) {
    return {HableFilmicCurve(x.r), HableFilmicCurve(x.g), HableFilmicCurve(x.b)};
}

// Converte RGB linear ou sRGB para saturacao [0.0, 1.0] (HSV saturation)
inline float RgbToSat(ColorRgb rgb) {
    float maxVal = std::max({rgb.r, rgb.g, rgb.b});
    float minVal = std::min({rgb.r, rgb.g, rgb.b});
    if (maxVal <= 1e-5f) {
        return 0.0f;
    }
    return (maxVal - minVal) / maxVal;
}

// Curva de Contraste Polinomial Cubica (S-Curve) adaptativa
// f(x) = x * x * (3.0 - 2.0 * x)
// Ancoras fixas em preto (0.0), meio-tom (0.5) e branco (1.0).
// Elimina o desvio de gamma medio mantendo suavidade nos extremos (toe e shoulder).
inline float ApplyCubicSCurve(float x, float strength) {
    if (strength <= 0.0f) {
        return x;
    }
    float c = std::clamp(x, 0.0f, 1.0f);
    float s = c * c * (3.0f - 2.0f * c);
    return c + strength * (s - c);
}

inline ColorRgb ApplyCubicSCurve(ColorRgb c, float strength) {
    return {ApplyCubicSCurve(c.r, strength), ApplyCubicSCurve(c.g, strength), ApplyCubicSCurve(c.b, strength)};
}

// ST 2084 (PQ EOTF): converte sinal PQ [0.0, 1.0] para luminancia relativa [0.0, 1.0],
// onde 1.0 corresponde ao pico teorico de 10.000 nits.
inline ColorRgb PqEotf(ColorRgb pq) {
    constexpr float m1 = 0.1593017578125f;
    constexpr float m2 = 78.84375f;
    constexpr float c1 = 0.8359375f;
    constexpr float c2 = 18.8515625f;
    constexpr float c3 = 18.6875f;

    auto evalChannel = [&](float v) -> float {
        float p = std::pow(std::clamp(v, 0.0f, 1.0f), 1.0f / m2);
        float num = std::max(p - c1, 0.0f);
        float den = std::max(c2 - c3 * p, 1e-6f);
        return std::pow(num / den, 1.0f / m1);
    };

    return {evalChannel(pq.r), evalChannel(pq.g), evalChannel(pq.b)};
}

// BT.2100 HLG: inverso da OETF -> luz de cena -> OOTF do sistema (gamma 1.2,
// pico nominal de 1000 nits) -> nits de display.
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

// Primarias BT.2020 -> BT.709 (ITU-R BT.2087), em luz linear.
inline ColorRgb Bt2020ToBt709(ColorRgb bt2020) {
    return {1.6605f * bt2020.r - 0.5876f * bt2020.g - 0.0728f * bt2020.b,
            -0.1246f * bt2020.r + 1.1329f * bt2020.g - 0.0083f * bt2020.b,
            -0.0182f * bt2020.r - 0.1006f * bt2020.g + 1.1187f * bt2020.b};
}

// Tonemap cinematografico HDR -> SDR (PQ / HLG para display SDR do Quest 3)
// `transfer`: 1 = PQ (ST 2084), 2 = HLG (BT.2100).
inline ColorRgb TonemapHdrToSdr(ColorRgb hdrColor, int transfer) {
    ColorRgb nits = (transfer == 2) ? HlgToDisplayNits(hdrColor)
                                    : ColorRgb{PqEotf(hdrColor).r * 10000.0f, PqEotf(hdrColor).g * 10000.0f,
                                               PqEotf(hdrColor).b * 10000.0f};

    ColorRgb nitsDiv100 = {nits.r / 100.0f, nits.g / 100.0f, nits.b / 100.0f};
    ColorRgb scene709 = Bt2020ToBt709(nitsDiv100);
    ColorRgb scene = {std::max(scene709.r, 0.0f), std::max(scene709.g, 0.0f), std::max(scene709.b, 0.0f)};

    // Ponto branco linear W calibrado para o pico de mastering cinematografico (4.000 nits)
    constexpr float kW = 40.0f;
    constexpr float kExposure = 2.0f;
    float whiteVal = HableFilmicCurve(kW * kExposure);
    float whiteScale = 1.0f / std::max(whiteVal, 1e-4f);

    ColorRgb filmColor = {std::max(HableFilmicCurve(scene.r * kExposure) * whiteScale, 0.0f),
                          std::max(HableFilmicCurve(scene.g * kExposure) * whiteScale, 0.0f),
                          std::max(HableFilmicCurve(scene.b * kExposure) * whiteScale, 0.0f)};

    // Recuperacao de Saturacao de Realces (RgbToSat)
    constexpr float kBt709LumaR = 0.2126f;
    constexpr float kBt709LumaG = 0.7152f;
    constexpr float kBt709LumaB = 0.0722f;
    float sceneLuma = scene.r * kBt709LumaR + scene.g * kBt709LumaG + scene.b * kBt709LumaB;
    float mappedLuma = std::max(HableFilmicCurve(sceneLuma * kExposure) * whiteScale, 0.0f);
    float lumaRatio = mappedLuma / std::max(sceneLuma, 1e-5f);

    ColorRgb lumaMappedColor = {scene.r * lumaRatio, scene.g * lumaRatio, scene.b * lumaRatio};

    float origSat = RgbToSat(scene);
    float highlightWeight = std::clamp(sceneLuma * 0.1f, 0.0f, 1.0f);
    float blendFactor = origSat * highlightWeight * 0.6f;

    ColorRgb tonemapped = {filmColor.r + blendFactor * (lumaMappedColor.r - filmColor.r),
                           filmColor.g + blendFactor * (lumaMappedColor.g - filmColor.g),
                           filmColor.b + blendFactor * (lumaMappedColor.b - filmColor.b)};

    float peak = std::max({tonemapped.r, tonemapped.g, tonemapped.b});
    if (peak > 1.0f) {
        tonemapped.r /= peak;
        tonemapped.g /= peak;
        tonemapped.b /= peak;
    }
    tonemapped.r = std::clamp(tonemapped.r, 0.0f, 1.0f);
    tonemapped.g = std::clamp(tonemapped.g, 0.0f, 1.0f);
    tonemapped.b = std::clamp(tonemapped.b, 0.0f, 1.0f);

    ColorRgb gammaColor = {std::pow(tonemapped.r, 1.0f / 2.2f), std::pow(tonemapped.g, 1.0f / 2.2f),
                           std::pow(tonemapped.b, 1.0f / 2.2f)};

    return ApplyCubicSCurve(gammaColor, 0.18f);
}

// Planckian locus approximation (Kelvin 2700K a 6500K / Night Mode ~3000K)
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
