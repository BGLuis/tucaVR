#version 450

// Estagio 5 — fragment shader para quad SBS/OU e esfera 360/180 + Upscaling SGSR1.
// Substitui o CAS estático de 5 taps pelo kernel adaptativo SGSR1 com suporte a
// amostragem direta quando desativado e ajuste contínuo de força (vSharpness).

layout(set = 0, binding = 0) uniform sampler2D videoTexture;

layout(location = 0) in vec2  vTexCoord;
layout(location = 1) flat in int vEye;
layout(location = 2) flat in int vSwapEyes;
layout(location = 3) flat in int vStereoLayout;
layout(location = 4) flat in int vPolar180;
layout(location = 5) flat in float vSharpness;
layout(location = 6) flat in int vUpscalingMode;

layout(location = 0) out vec4 outColor;

vec3 ApplySGSR1(vec2 uv, float sharpness) {
    vec2 texelSize = 1.0 / vec2(textureSize(videoTexture, 0));
    vec2 dx = vec2(texelSize.x, 0.0);
    vec2 dy = vec2(0.0, texelSize.y);

    vec3 c  = texture(videoTexture, uv).rgb;
    vec3 n  = texture(videoTexture, uv + dy).rgb;
    vec3 s  = texture(videoTexture, uv - dy).rgb;
    vec3 e  = texture(videoTexture, uv + dx).rgb;
    vec3 w  = texture(videoTexture, uv - dx).rgb;
    vec3 ne = texture(videoTexture, uv + dx + dy).rgb;
    vec3 nw = texture(videoTexture, uv - dx + dy).rgb;
    vec3 se = texture(videoTexture, uv + dx - dy).rgb;
    vec3 sw = texture(videoTexture, uv - dx - dy).rgb;

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

    float gradH = abs((lNW + 2.0 * lW + lSW) - (lNE + 2.0 * lE + lSE));
    float gradV = abs((lNW + 2.0 * lN + lNE) - (lSW + 2.0 * lS + lSE));
    float gradD1 = abs((2.0 * lNW + lN + lW) - (2.0 * lSE + lS + lE));
    float gradD2 = abs((2.0 * lNE + lN + lE) - (2.0 * lSW + lS + lW));

    vec3 minColor = min(c, min(min(n, s), min(e, w)));
    minColor = min(minColor, min(min(ne, nw), min(se, sw)));
    vec3 maxColor = max(c, max(max(n, s), max(e, w)));
    maxColor = max(maxColor, max(max(ne, nw), max(se, sw)));

    float wH = 1.0 / (1.0 + gradH * 4.0);
    float wV = 1.0 / (1.0 + gradV * 4.0);
    float wDiag = 1.0 / (1.0 + (gradD1 + gradD2) * 2.0);

    vec3 cardinalAvg = (n + s) * wH + (e + w) * wV;
    float cardinalSum = 2.0 * (wH + wV);
    vec3 diagonalAvg = (ne + nw + se + sw) * wDiag;
    float diagonalSum = 4.0 * wDiag;

    vec3 reconstructed = (c * 2.0 + cardinalAvg + diagonalAvg * 0.5) / (2.0 + cardinalSum + diagonalSum * 0.5);

    vec3 amp = sqrt(clamp(min(minColor, 2.0 - maxColor) / max(maxColor, vec3(0.001)), 0.0, 1.0));
    float peak = -mix(8.0, 4.0, clamp(sharpness, 0.0, 1.0));
    vec3 wgt = amp / peak;
    vec3 sharpened = ((n + s + e + w) * wgt + reconstructed) / (1.0 + 4.0 * wgt);

    vec3 finalColor = clamp(sharpened, minColor, maxColor);
    return mix(c, finalColor, clamp(sharpness * 1.5, 0.0, 1.0));
}

void main() {
    vec2 uv = vTexCoord;
    int  eye = vEye;

    // Inversao de olhos (swapEyes)
    if (vSwapEyes != 0) {
        eye = 1 - eye;
    }

    // Recorte polar 180: descarta fora do hemisferio frontal
    if (vPolar180 != 0) {
        if (uv.x < 0.25 || uv.x > 0.75) {
            discard;
        }
        uv.x = (uv.x - 0.25) * 2.0;
    }

    // Recorte estereo por olho
    if (vStereoLayout == 2) {
        // OU: recorta em Y — olho esquerdo = metade superior
        uv.y = uv.y * 0.5 + float(eye) * 0.5;
    } else if (vStereoLayout == 1) {
        // SBS: recorta em X — olho esquerdo = metade esquerda
        uv.x = uv.x * 0.5 + float(eye) * 0.5;
    }

    if (vSharpness <= 0.01) {
        // alpha forcado a 1.0 (video sempre opaco) — ver nota em video.frag
        // sobre composicao por alpha com passthrough ativo.
        outColor = vec4(texture(videoTexture, uv).rgb, 1.0);
    } else {
        outColor = vec4(ApplySGSR1(uv, vSharpness), 1.0);
    }
}
