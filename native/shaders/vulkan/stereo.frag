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
layout(location = 7) flat in int vIsHdr;
layout(location = 8) flat in vec2  vTexelSize;

layout(location = 0) out vec4 outColor;

// T-HDR (Fase 2, primeira versao) — ver o comentario completo em video.frag.
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

vec3 ApplySGSR1(vec2 uv, float sharpness, vec2 texelSize) {
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

    // Inversao de olho se requisitada (swapEyes)
    int eye = (vSwapEyes != 0) ? (1 - vEye) : vEye;

    // Mapeamento hemisferio frontal 180 graus (T6.4) ou Fisheye 190 graus:
    // A malha da esfera mapeia U de 0 a 1 em torno de 360 graus.
    // Para video 180 (polar180 == 1), o conteudo util esta no
    // hemisferio frontal [-90, +90], que corresponde a U em [0.25, 0.75].
    // Reescalamos U para que [0.25, 0.75] cubra toda a faixa [0, 1] do frame.
    // Pixels fora do hemisferio frontal ficam pretos (descartados).
    if (vPolar180 == 1) {
        if (uv.x < 0.25 || uv.x > 0.75) {
            outColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
        uv.x = (uv.x - 0.25) * 2.0;
    } else if (vPolar180 == 2) {
        // Projeção Fisheye 190° (equidistante f-theta com FOV total de 190° / semi-FOV 95°)
        const float PI = 3.141592653589793;
        float theta = 2.0 * PI * (vTexCoord.x - 0.5);
        float phi   = PI * vTexCoord.y;
        float sinPhi = sin(phi);
        vec3 dir = vec3(sinPhi * sin(theta), cos(phi), -sinPhi * cos(theta));

        // Ângulo óptico a partir do eixo frontal (-Z)
        float cosPsi = clamp(-dir.z, -1.0, 1.0);
        float psi = acos(cosPsi);
        const float kMaxHalfFov = 190.0 * PI / 360.0; // 95 graus em radianos (~1.65806 rad)

        if (psi > kMaxHalfFov) {
            outColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }

        // Projeção azimutal equidistante: r = psi / kMaxHalfFov
        float rho = length(dir.xy);
        vec2 planeDir = (rho > 1e-6) ? (dir.xy / rho) : vec2(0.0, 0.0);
        float rNorm = psi / kMaxHalfFov; // 0.0 no centro óptico até 1.0 na borda do círculo de 190°

        // Coordenadas UV locais no círculo fisheye do olho [0, 1] x [0, 1]
        uv = vec2(0.5 + 0.5 * rNorm * planeDir.x, 0.5 - 0.5 * rNorm * planeDir.y);
    }

    // Recorte estereo por olho
    if (vStereoLayout == 2) {
        // OU: recorta em Y — olho esquerdo = metade superior
        uv.y = uv.y * 0.5 + float(eye) * 0.5;
    } else if (vStereoLayout == 1) {
        // SBS: recorta em X — olho esquerdo = metade esquerda
        uv.x = uv.x * 0.5 + float(eye) * 0.5;
    }

    // alpha forcado a 1.0 (video sempre opaco) — ver nota em video.frag
    // sobre composicao por alpha com passthrough ativo.
    vec3 color = (vSharpness <= 0.01 || vTexelSize.x <= 0.0 || vTexelSize.y <= 0.0)
        ? texture(videoTexture, uv).rgb
        : ApplySGSR1(uv, vSharpness, vTexelSize);
    if (vIsHdr != 0) {
        color = TonemapHdrToSdr(color);
    }
    outColor = vec4(color, 1.0);
}
