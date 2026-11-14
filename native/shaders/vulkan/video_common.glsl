// Video color pipeline shared by the shaders that sample the decoded YCbCr frame (included, not compiled alone):
// HDR tone mapping, color temperature, SGSR1 sharpening, sRGB linearization for the *_SRGB swapchains, and
// ITU-R BT.601 RGB-to-YCbCr conversion.
// Included by video.frag, stereo.frag, stereo_cubemap.frag, and video_layer.frag.

layout(set = 0, binding = 0) uniform sampler2D videoTexture;

// RGB to YCbCr conversion (ITU-R BT.601) for illumination-invariant color distance measurement
vec3 RgbToYcbcr(vec3 rgb) {
    float y  =  0.299 * rgb.r + 0.587 * rgb.g + 0.114 * rgb.b;
    float cb = -0.168736 * rgb.r - 0.331264 * rgb.g + 0.5 * rgb.b;
    float cr =  0.5 * rgb.r - 0.418688 * rgb.g - 0.081312 * rgb.b;
    return vec3(y, cb, cr);
}

// ST 2084 (PQ EOTF): converts PQ signal [0.0, 1.0] to relative linear luminance [0.0, 1.0],
// where 1.0 corresponds to the theoretical peak of 10,000 nits.
vec3 PqEotf(vec3 pq) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    vec3 p = pow(clamp(pq, 0.0, 1.0), vec3(1.0 / m2));
    vec3 num = max(p - c1, 0.0);
    vec3 den = max(c2 - c3 * p, 1e-6);
    return pow(num / den, vec3(1.0 / m1));
}

// BT.2100 HLG: inverse OETF -> scene light -> system OOTF (gamma 1.2,
// 1000 nits nominal peak) -> display nits.
vec3 HlgToDisplayNits(vec3 hlg) {
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    vec3 e = clamp(hlg, 0.0, 1.0);
    vec3 sceneLight = mix((exp((e - c) / a) + b) / 12.0, e * e / 3.0, lessThanEqual(e, vec3(0.5)));
    float ys = dot(sceneLight, vec3(0.2627, 0.6780, 0.0593));
    return sceneLight * pow(max(ys, 1e-6), 0.2) * 1000.0;
}

// BT.2020 -> BT.709 primaries (ITU-R BT.2087) in linear light. Column by column (GLSL).
const mat3 kBt2020ToBt709 = mat3(
     1.6605, -0.1246, -0.0182,
    -0.5876,  1.1329, -0.1006,
    -0.0728, -0.0083,  1.1187);

// Hable Filmic tone mapping curve (Uncharted 2 / John Hable)
// Constants:
//   A = 0.15 (Shoulder Strength)
//   B = 0.50 (Linear Strength)
//   C = 0.10 (Linear Angle)
//   D = 0.20 (Toe Strength)
//   E = 0.02 (Toe Numerator)
//   F = 0.30 (Toe Denominator)
float HableFilmicCurve(float x) {
    const float A = 0.15;
    const float B = 0.50;
    const float C = 0.10;
    const float D = 0.20;
    const float E = 0.02;
    const float F = 0.30;
    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - (E / F);
}

// Adaptive Polynomial Cubic S-Curve
// f(x) = x * x * (3.0 - 2.0 * x)
// Fixed anchors at black (0.0), mid-tone (0.5), and white (1.0).
// Eliminates mid-tone gamma drift and enriches micro-contrast without banding.
float ApplyCubicSCurve(float value, float strength) {
    if (strength <= 0.0) {
        return value;
    }
    float c = clamp(value, 0.0, 1.0);
    float s = c * c * (3.0 - 2.0 * c);
    return mix(c, s, strength);
}

// Filmic HDR -> SDR Tonemapping (PQ / HLG for Quest 3 display)
// `transfer`: 1 = PQ (ST 2084), 2 = HLG (BT.2100).
// `luminance`: viewer brightness multiplier on the exposure, 1.0 = neutral.
vec3 TonemapHdrToSdr(vec3 hdrColor, int transfer, float luminance) {
    vec3 nits = (transfer == 2) ? HlgToDisplayNits(hdrColor) : PqEotf(hdrColor) * 10000.0;
    // Linear BT.709 scene normalization (100 nits = 1.0 linear)
    vec3 scene = max(kBt2020ToBt709 * (nits / 100.0), vec3(0.0));

    // Linear white point W calibrated for cinema mastering peak (4,000 nits). It moves with the exposure so
    // 4,000 nits stays at 1.0 at any luminance setting.
    const float kW = 40.0;
    const float kExposure = 3.0;
    float exposure = kExposure * luminance;
    float whiteScale = 1.0 / max(HableFilmicCurve(kW * exposure), 1e-4);

    // 1. Map the largest channel and scale the others by the same ratio, which keeps R:G:B and so the hue.
    float peak = max(max(scene.r, scene.g), scene.b);
    float mappedPeak = clamp(HableFilmicCurve(peak * exposure) * whiteScale, 0.0, 1.0);
    vec3 tonemapped = scene * (mappedPeak / max(peak, 1e-5));

    // 2. Conversion to SDR display gamma (Rec.709 / sRGB 2.2)
    vec3 gammaColor = pow(tonemapped, vec3(1.0 / 2.2));

    // 3. Cubic S-Curve for contrast, on the peak for the same reason as the curve
    float gammaPeak = max(max(gammaColor.r, gammaColor.g), gammaColor.b);
    return gammaColor * (ApplyCubicSCurve(gammaPeak, 0.18) / max(gammaPeak, 1e-5));
}

// Planckian locus approximation (2700K to 6500K Kelvin / Night Mode ~3000K)
vec3 ApplyColorTemperature(vec3 color, float tempK) {
    float t = tempK / 100.0;
    vec3 tempColor;
    if (t <= 66.0) {
        tempColor.r = 1.0;
    } else {
        tempColor.r = clamp(1.292 * pow(t - 60.0, -0.1332), 0.0, 1.0);
    }

    if (t <= 66.0) {
        tempColor.g = clamp(0.3901 * log(max(t, 1.0)) - 0.6318, 0.0, 1.0);
    } else {
        tempColor.g = clamp(1.130 * pow(t - 60.0, -0.0755), 0.0, 1.0);
    }

    if (t >= 66.0) {
        tempColor.b = 1.0;
    } else if (t <= 19.0) {
        tempColor.b = 0.0;
    } else {
        tempColor.b = clamp(0.5432 * log(max(t - 10.0, 1.0)) - 1.1962, 0.0, 1.0);
    }

    return color * tempColor;
}

// SGSR1 kernel (Snapdragon Game Super Resolution v1 / edge-aware, 9 taps)
vec3 ApplySGSR1(vec2 uv, float sharpness, vec2 texelSize) {
    vec2 dx = vec2(texelSize.x, 0.0);
    vec2 dy = vec2(0.0, texelSize.y);

    // 9 taps: center, 4 cardinal, 4 diagonal
    vec3 c  = texture(videoTexture, uv).rgb;
    vec3 n  = texture(videoTexture, uv + dy).rgb;
    vec3 s  = texture(videoTexture, uv - dy).rgb;
    vec3 e  = texture(videoTexture, uv + dx).rgb;
    vec3 w  = texture(videoTexture, uv - dx).rgb;
    vec3 ne = texture(videoTexture, uv + dx + dy).rgb;
    vec3 nw = texture(videoTexture, uv - dx + dy).rgb;
    vec3 se = texture(videoTexture, uv + dx - dy).rgb;
    vec3 sw = texture(videoTexture, uv - dx - dy).rgb;

    // Luma for the gradient analysis
    const vec3 luma = vec3(0.299, 0.587, 0.114);
    float lC  = dot(c, luma);
    float lN  = dot(n, luma);
    float lS  = dot(s, luma);
    float lE  = dot(e, luma);
    float lW  = dot(w, luma);
    float lNE = dot(ne, luma);
    float lNW = dot(nw, luma);
    float lSE = dot(se, luma);
    float lSW = dot(sw, luma);

    // Directional edge gradients
    float gradH = abs((lNW + 2.0 * lW + lSW) - (lNE + 2.0 * lE + lSE));
    float gradV = abs((lNW + 2.0 * lN + lNE) - (lSW + 2.0 * lS + lSE));
    float gradD1 = abs((2.0 * lNW + lN + lW) - (2.0 * lSE + lS + lE));
    float gradD2 = abs((2.0 * lNE + lN + lE) - (2.0 * lSW + lS + lW));

    // Local min and max to clamp ringing
    vec3 minColor = min(c, min(min(n, s), min(e, w)));
    minColor = min(minColor, min(min(ne, nw), min(se, sw)));
    vec3 maxColor = max(c, max(max(n, s), max(e, w)));
    maxColor = max(maxColor, max(max(ne, nw), max(se, sw)));

    // Directional adaptive weights
    float wH = 1.0 / (1.0 + gradH * 4.0);
    float wV = 1.0 / (1.0 + gradV * 4.0);
    float wDiag = 1.0 / (1.0 + (gradD1 + gradD2) * 2.0);

    vec3 cardinalAvg = (n + s) * wH + (e + w) * wV;
    float cardinalSum = 2.0 * (wH + wV);
    vec3 diagonalAvg = (ne + nw + se + sw) * wDiag;
    float diagonalSum = 4.0 * wDiag;

    vec3 reconstructed = (c * 2.0 + cardinalAvg + diagonalAvg * 0.5) / (2.0 + cardinalSum + diagonalSum * 0.5);

    // Sharpening adapted to the local contrast
    vec3 amp = sqrt(clamp(min(minColor, 2.0 - maxColor) / max(maxColor, vec3(0.001)), 0.0, 1.0));
    float peak = -mix(8.0, 4.0, clamp(sharpness, 0.0, 1.0));
    vec3 wgt = amp / peak;
    vec3 sharpened = ((n + s + e + w) * wgt + reconstructed) / (1.0 + 4.0 * wgt);

    vec3 finalColor = clamp(sharpened, minColor, maxColor);
    return mix(c, finalColor, clamp(sharpness * 1.5, 0.0, 1.0));
}

// Exact piecewise sRGB EOTF. The eye swapchain is an *_SRGB format, so the hardware applies the
// sRGB OETF on write; linearizing the video's gamma-coded value here makes that encode round-trip to the
// original code value instead of encoding twice.
vec3 SrgbEotf(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}
