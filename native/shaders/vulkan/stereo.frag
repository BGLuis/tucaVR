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
layout(location = 9) flat in int vChromaKeyEnabled;
layout(location = 10) flat in uint vChromaColorRgb;
layout(location = 11) flat in float vChromaSimilarity;
layout(location = 12) flat in float vChromaSmoothness;

layout(location = 0) out vec4 outColor;

// Conversao RGB para YCbCr para medicao de distancia cromatica invariante a iluminacao
vec3 RgbToYcbcr(vec3 rgb) {
    float y  =  0.299 * rgb.r + 0.587 * rgb.g + 0.114 * rgb.b;
    float cb = -0.168736 * rgb.r - 0.331264 * rgb.g + 0.5 * rgb.b;
    float cr =  0.5 * rgb.r - 0.418688 * rgb.g - 0.081312 * rgb.b;
    return vec3(y, cb, cr);
}

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
    // Pixels fora do hemisferio frontal ficam 100% transparentes (alpha 0)
    // para revelar o Passthrough (mundo real) atras do usuario.
    float hemisphereAlpha = 1.0;
    if (vPolar180 == 1) {
        if (uv.x < 0.25 || uv.x > 0.75) {
            outColor = vec4(0.0, 0.0, 0.0, 0.0);
            return;
        }
        // Suavizacao (feathering) de ~5 graus na borda de 180 graus
        const float feather = 0.02;
        float edgeL = smoothstep(0.25, 0.25 + feather, uv.x);
        float edgeR = smoothstep(0.75, 0.75 - feather, uv.x);
        hemisphereAlpha = min(edgeL, edgeR);

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
            outColor = vec4(0.0, 0.0, 0.0, 0.0);
            return;
        }

        // Suavizacao de borda na margem externa de 190° (feathering de ~3 graus)
        const float kFeatherAngle = 3.0 * PI / 180.0;
        hemisphereAlpha = smoothstep(kMaxHalfFov, kMaxHalfFov - kFeatherAngle, psi);

        // Projeção azimutal equidistante: r = psi / kMaxHalfFov
        float rho = length(dir.xy);
        vec2 planeDir = (rho > 1e-6) ? (dir.xy / rho) : vec2(0.0, 0.0);
        float rNorm = psi / kMaxHalfFov; // 0.0 no centro óptico até 1.0 na borda do círculo de 190°

        // Coordenadas UV locais no círculo fisheye do olho [0, 1] x [0, 1]
        uv = vec2(0.5 + 0.5 * rNorm * planeDir.x, 0.5 - 0.5 * rNorm * planeDir.y);
    }

    // Coordenadas UV locais do olho antes do recorte estéreo para desempacotamento de máscara
    vec2 eyeLocalUv = clamp(uv, 0.0, 1.0);

    // Recorte estereo por olho
    if (vStereoLayout == 2) {
        // OU: recorta em Y — olho esquerdo = metade superior
        uv.y = uv.y * 0.5 + float(eye) * 0.5;
    } else if (vStereoLayout == 1) {
        // SBS: recorta em X — olho esquerdo = metade esquerda
        uv.x = uv.x * 0.5 + float(eye) * 0.5;
    }

    vec3 color = (vSharpness <= 0.01 || vTexelSize.x <= 0.0 || vTexelSize.y <= 0.0)
        ? texture(videoTexture, uv).rgb
        : ApplySGSR1(uv, vSharpness, vTexelSize);
    if (vIsHdr != 0) {
        color = TonemapHdrToSdr(color);
    }

    // Calculo de Alpha: composicao do hemisferio, Chroma Key ou Packed Alpha (DeoVR/HereSphere)
    float finalAlpha = hemisphereAlpha;
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

        // Despill adaptativo para evitar bordas esverdeadas, azuladas ou avermelhadas
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
        finalAlpha *= chromaAlpha;
    } else if (vChromaKeyEnabled == 2 && vStereoLayout == 1) {
        // Modo 2: DeoVR / HereSphere 6-Segment Packed Alpha
        // Desempacota a máscara alfa embutida nos 4 cantos e 2 cunhas centrais do frame SBS 2:1
        vec2 alphaUv;
        if (eye == 0) {
            // Olho Esquerdo: dividido horizontalmente em 2 semicírculos
            // Metade superior (v < 0.5) -> Bottom-Center [U: 0.4..0.6, V: 0.8..1.0]
            // Metade inferior (v >= 0.5) -> Top-Center [U: 0.4..0.6, V: 0.0..0.2]
            if (eyeLocalUv.y < 0.5) {
                alphaUv = vec2(0.4 + 0.2 * eyeLocalUv.x, 0.8 + 0.4 * eyeLocalUv.y);
            } else {
                alphaUv = vec2(0.4 + 0.2 * eyeLocalUv.x, 0.4 * eyeLocalUv.y - 0.2);
            }
        } else {
            // Olho Direito: dividido em 4 quadrantes
            // R1 (TL da máscara) -> Bottom-Right [U: 0.9..1.0, V: 0.8..1.0]
            // R2 (TR da máscara) -> Bottom-Left  [U: 0.0..0.1, V: 0.8..1.0]
            // R3 (BL da máscara) -> Top-Right    [U: 0.9..1.0, V: 0.0..0.2]
            // R4 (BR da máscara) -> Top-Left     [U: 0.0..0.1, V: 0.0..0.2]
            vec2 quadUv = vec2(
                (eyeLocalUv.x < 0.5) ? (eyeLocalUv.x * 2.0) : ((eyeLocalUv.x - 0.5) * 2.0),
                (eyeLocalUv.y < 0.5) ? (eyeLocalUv.y * 2.0) : ((eyeLocalUv.y - 0.5) * 2.0)
            );
            if (eyeLocalUv.x < 0.5) {
                if (eyeLocalUv.y < 0.5) {
                    alphaUv = vec2(0.9 + 0.1 * quadUv.x, 0.8 + 0.2 * quadUv.y);
                } else {
                    alphaUv = vec2(0.9 + 0.1 * quadUv.x, 0.0 + 0.2 * quadUv.y);
                }
            } else {
                if (eyeLocalUv.y < 0.5) {
                    alphaUv = vec2(0.0 + 0.1 * quadUv.x, 0.8 + 0.2 * quadUv.y);
                } else {
                    alphaUv = vec2(0.0 + 0.1 * quadUv.x, 0.0 + 0.2 * quadUv.y);
                }
            }
        }

        // Amostra a máscara no canal R (DeoVR / HereSphere Packed Alpha)
        float rawMask = texture(videoTexture, alphaUv).r;

        // Fator de Choke / Desbaste de borda (default 65%, ajustável de 0% a 100% via vChromaColorRgb)
        float chokeFactor = (vChromaColorRgb <= 100u) 
            ? (float(vChromaColorRgb) / 100.0) 
            : 0.65;

        // Amostragem morfológica dos 4 vizinhos (N, S, L, O) para erodir o contorno cinza da parede (edge fringe)
        vec2 mStep = (vTexelSize.x > 0.0) ? (vTexelSize * 2.0) : vec2(0.0005, 0.0005);
        float mN = texture(videoTexture, alphaUv + vec2(0.0, mStep.y)).r;
        float mS = texture(videoTexture, alphaUv - vec2(0.0, mStep.y)).r;
        float mE = texture(videoTexture, alphaUv + vec2(mStep.x, 0.0)).r;
        float mW = texture(videoTexture, alphaUv - vec2(mStep.x, 0.0)).r;
        float eroded = min(rawMask, min(min(mN, mS), min(mE, mW)));

        // Aplica o encolhimento de borda
        float choked = mix(rawMask, eroded, chokeFactor);

        // Limiar de corte de fundo / ruído de compressão (Black Cutoff entre 0.005 e 0.25)
        float cutoff = clamp(vChromaSmoothness, 0.005, 0.25);

        // Multiplicador de opacidade (1.0x a 2.5x, default 1.5x) para encorpar cabelos e corpos sólidos
        float opacityMult = (vChromaSimilarity >= 0.8) ? vChromaSimilarity : 1.5;

        // Remove ruído de compressão do fundo preservando faixa dinâmica útil
        float cleanMask = max(0.0, choked - cutoff) / max(0.001, 1.0 - cutoff);

        // Curva de opacidade gama reforçada: corpos e mechas ficam 100% sólidos, fios finos ganham densidade natural
        float maskAlpha = clamp(pow(cleanMask, 0.90) * opacityMult, 0.0, 1.0);

        finalAlpha *= maskAlpha;
    }

    // Pre-multiplied alpha para o compositor OpenXR (XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT)
    outColor = vec4(color * finalAlpha, finalAlpha);
}
