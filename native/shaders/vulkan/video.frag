#version 450

// Estagio 3 do plano de migracao Vulkan + Upscaling SGSR1 (Modo Qualidade/Auto):
// Fragment shader que amostra textura YCbCr via sampler imutavel com suporte
// a amostragem simples (1 fetch) ou reconstrucao espacial 12-tap SGSR1 adaptativa.

layout(set = 0, binding = 0) uniform sampler2D videoTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) flat in float vSharpness;
layout(location = 2) flat in int vUpscalingMode;
layout(location = 3) flat in int vIsHdr;
layout(location = 4) flat in vec2 vTexelSize;
layout(location = 5) flat in int vChromaKeyEnabled;
layout(location = 6) flat in uint vChromaColorRgb;
layout(location = 7) flat in float vChromaSimilarity;
layout(location = 8) flat in float vChromaSmoothness;
layout(location = 9) flat in float vColorTemperature;

layout(location = 0) out vec4 outColor;

// Conversao RGB para YCbCr para medicao de distancia cromatica invariante a iluminacao
vec3 RgbToYcbcr(vec3 rgb) {
    float y  =  0.299 * rgb.r + 0.587 * rgb.g + 0.114 * rgb.b;
    float cb = -0.168736 * rgb.r - 0.331264 * rgb.g + 0.5 * rgb.b;
    float cr =  0.5 * rgb.r - 0.418688 * rgb.g - 0.081312 * rgb.b;
    return vec3(y, cb, cr);
}

// T-HDR (Fase 2, primeira versao — ver docs/phases/PHASE-0.3-POLISH-AUDIO.md
// T6.2): tonemap HDR->SDR pra exibir PQ/HLG no display SDR do Quest 3.
// Pipeline: linearizar a EOTF PQ (ST 2084) -> nits absolutos -> normalizar
// pelo branco de referencia SDR nominal (100 nits) -> Reinhard em luz
// linear -> gamma de saida aproximado (mesmo espaco "pronto pra exibir" que
// o passthrough SDR abaixo ja assume). Aproximacao deliberada pra uma
// primeira versao: nao le metadado estatico HDR (MaxCLL/MaxFALL), e trata
// HLG com a mesma curva de PQ apos a normalizacao (ambas convergem pra
// resultado proximo no branco de referencia) — Hable/ACES e leitura de
// metadado ficam pra uma iteracao futura (ver plano).
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

vec3 TonemapHdrToSdr(vec3 hdrColor) {
    vec3 nits = PqEotf(hdrColor) * 10000.0;
    vec3 scene = nits / 100.0;
    vec3 tonemapped = scene / (1.0 + scene);
    return pow(clamp(tonemapped, 0.0, 1.0), vec3(1.0 / 2.2));
}

// Planckian locus approximation (Kelvin 2700K a 6500K / Night Mode ~3000K)
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

// Kernel SGSR1 (Snapdragon Game Super Resolution v1 / 12-tap edge-aware)
vec3 ApplySGSR1(vec2 uv, float sharpness, vec2 texelSize) {
    vec2 dx = vec2(texelSize.x, 0.0);
    vec2 dy = vec2(0.0, texelSize.y);

    // 12 taps: centro, 4 cardinais, 4 diagonais
    vec3 c  = texture(videoTexture, uv).rgb;
    vec3 n  = texture(videoTexture, uv + dy).rgb;
    vec3 s  = texture(videoTexture, uv - dy).rgb;
    vec3 e  = texture(videoTexture, uv + dx).rgb;
    vec3 w  = texture(videoTexture, uv - dx).rgb;
    vec3 ne = texture(videoTexture, uv + dx + dy).rgb;
    vec3 nw = texture(videoTexture, uv - dx + dy).rgb;
    vec3 se = texture(videoTexture, uv + dx - dy).rgb;
    vec3 sw = texture(videoTexture, uv - dx - dy).rgb;

    // Luminancias para analise de gradiente
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

    // Gradientes direcionais de borda
    float gradH = abs((lNW + 2.0 * lW + lSW) - (lNE + 2.0 * lE + lSE));
    float gradV = abs((lNW + 2.0 * lN + lNE) - (lSW + 2.0 * lS + lSE));
    float gradD1 = abs((2.0 * lNW + lN + lW) - (2.0 * lSE + lS + lE));
    float gradD2 = abs((2.0 * lNE + lN + lE) - (2.0 * lSW + lS + lW));

    // Minimo e maximo locais para clamp anti-ringing
    vec3 minColor = min(c, min(min(n, s), min(e, w)));
    minColor = min(minColor, min(min(ne, nw), min(se, sw)));
    vec3 maxColor = max(c, max(max(n, s), max(e, w)));
    maxColor = max(maxColor, max(max(ne, nw), max(se, sw)));

    // Pesos adaptativos direcionais
    float wH = 1.0 / (1.0 + gradH * 4.0);
    float wV = 1.0 / (1.0 + gradV * 4.0);
    float wDiag = 1.0 / (1.0 + (gradD1 + gradD2) * 2.0);

    vec3 cardinalAvg = (n + s) * wH + (e + w) * wV;
    float cardinalSum = 2.0 * (wH + wV);
    vec3 diagonalAvg = (ne + nw + se + sw) * wDiag;
    float diagonalSum = 4.0 * wDiag;

    vec3 reconstructed = (c * 2.0 + cardinalAvg + diagonalAvg * 0.5) / (2.0 + cardinalSum + diagonalSum * 0.5);

    // Sharpening adaptativo ao contraste local
    vec3 amp = sqrt(clamp(min(minColor, 2.0 - maxColor) / max(maxColor, vec3(0.001)), 0.0, 1.0));
    float peak = -mix(8.0, 4.0, clamp(sharpness, 0.0, 1.0));
    vec3 wgt = amp / peak;
    vec3 sharpened = ((n + s + e + w) * wgt + reconstructed) / (1.0 + 4.0 * wgt);

    vec3 finalColor = clamp(sharpened, minColor, maxColor);
    return mix(c, finalColor, clamp(sharpness * 1.5, 0.0, 1.0));
}

void main() {
    vec3 color = (vSharpness <= 0.01 || vTexelSize.x <= 0.0 || vTexelSize.y <= 0.0)
        ? texture(videoTexture, vTexCoord).rgb
        : ApplySGSR1(vTexCoord, vSharpness, vTexelSize);
    if (vIsHdr != 0) {
        color = TonemapHdrToSdr(color);
    }

    if (vColorTemperature < 0.0) {
        // Night Mode: reduz luz azul (~3000K)
        color = ApplyColorTemperature(color, 3000.0);
    } else if (abs(vColorTemperature - 6500.0) > 1.0 && vColorTemperature > 1000.0) {
        color = ApplyColorTemperature(color, vColorTemperature);
    }

    float finalAlpha = 1.0;
    if (vChromaKeyEnabled == 1) {
        vec3 keyColor = vec3(
            float((vChromaColorRgb >> 16) & 0xFFu) / 255.0,
            float((vChromaColorRgb >> 8) & 0xFFu) / 255.0,
            float(vChromaColorRgb & 0xFFu) / 255.0
        );
        vec3 ycbcr = RgbToYcbcr(color);
        vec3 keyYcbcr = RgbToYcbcr(keyColor);

        // Distancia cromatica + luminancia adaptativa para suportar Cinza/Preto/Branco
        float keyChromaLen = length(keyYcbcr.yz);
        float lumaWeight = mix(1.0, 0.15, smoothstep(0.04, 0.16, keyChromaLen));
        vec3 deltaYcbcr = vec3(
            (ycbcr.x - keyYcbcr.x) * lumaWeight,
            ycbcr.y - keyYcbcr.y,
            ycbcr.z - keyYcbcr.z
        );
        float diff = length(deltaYcbcr);
        float chromaAlpha = smoothstep(vChromaSimilarity, vChromaSimilarity + vChromaSmoothness, diff);

        // Despill adaptativo para verde, azul ou vermelho
        if (keyColor.g > keyColor.r && keyColor.g > keyColor.b) {
            float spill = max(0.0, color.g - max(color.r, color.b));
            color.g -= spill * (1.0 - chromaAlpha);
        } else if (keyColor.b > keyColor.r && keyColor.b > keyColor.g) {
            float spill = max(0.0, color.b - max(color.r, color.g));
            color.b -= spill * (1.0 - chromaAlpha);
        } else if (keyColor.r > keyColor.g && keyColor.r > keyColor.b) {
            float spill = max(0.0, color.r - max(color.g, color.b));
            color.r -= spill * (1.0 - chromaAlpha);
        }
        finalAlpha = chromaAlpha;
    }

    // Pre-multiplied alpha para o compositor OpenXR (XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT)
    outColor = vec4(color * finalAlpha, finalAlpha);
}
