#version 450
#extension GL_GOOGLE_include_directive : require

// Estagio 5 — fragment shader para quad SBS/OU e esfera 360/180 + Upscaling SGSR1.
// Substitui o CAS estático de 5 taps pelo kernel adaptativo SGSR1 com suporte a
// amostragem direta quando desativado e ajuste contínuo de força (vSharpness).

#include "video_common.glsl"

layout(location = 0) in vec2  vTexCoord;
layout(location = 1) flat in int vEye;
layout(location = 2) flat in int vSwapEyes;
layout(location = 3) flat in int vStereoLayout;
layout(location = 4) flat in int vPolar180;
layout(location = 5) flat in float vSharpness;
layout(location = 6) flat in float vHdrLuminance;
layout(location = 7) flat in int vIsHdr;
layout(location = 8) flat in vec2  vTexelSize;
layout(location = 9) flat in int vChromaKeyEnabled;
layout(location = 10) flat in uint vChromaColorRgb;
layout(location = 11) flat in float vChromaSimilarity;
layout(location = 12) flat in float vChromaSmoothness;
layout(location = 13) flat in float vColorTemperature;

layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = vTexCoord;

    // Eye swap if requested (swapEyes)
    int eye = (vSwapEyes != 0) ? (1 - vEye) : vEye;

    // Front hemisphere 180° mapping (T6.4) or Fisheye (180°, 190°, 200°, 220°):
    // The sphere mesh maps U from 0 to 1 around 360 degrees.
    // For 180 video (polarMode == 1), useful content is in the
    // front hemisphere [-90, +90], corresponding to U in [0.25, 0.75].
    // Rescale U so [0.25, 0.75] covers the full [0, 1] range of the frame.
    // Pixels outside the front hemisphere are 100% transparent (alpha 0)
    // to reveal Passthrough (real world) behind the user.
    int polarMode = vPolar180 & 0xFF;
    int rawFov = (vPolar180 >> 8) & 0xFFFF;
    float hemisphereAlpha = 1.0;
    if (polarMode == 1) {
        if (uv.x < 0.25 || uv.x > 0.75) {
            outColor = vec4(0.0, 0.0, 0.0, 0.0);
            return;
        }
        // Feathering (~5 degrees) on 180-degree edge
        const float feather = 0.02;
        float edgeL = smoothstep(0.25, 0.25 + feather, uv.x);
        float edgeR = smoothstep(0.75, 0.75 - feather, uv.x);
        hemisphereAlpha = min(edgeL, edgeR);

        uv.x = (uv.x - 0.25) * 2.0;
    } else if (polarMode == 2) {
        // Fisheye projection (equidistant f-theta with configurable FOV: 180°, 190°, 200°, 220°)
        const float PI = 3.141592653589793;
        float theta = 2.0 * PI * (vTexCoord.x - 0.5);
        float phi   = PI * vTexCoord.y;
        float sinPhi = sin(phi);
        vec3 dir = vec3(sinPhi * sin(theta), cos(phi), -sinPhi * cos(theta));

        // Optical angle from the forward axis (-Z)
        float cosPsi = clamp(-dir.z, -1.0, 1.0);
        float psi = acos(cosPsi);
        float fisheyeFov = (rawFov == 0) ? 190.0 : float(rawFov);
        float kMaxHalfFov = fisheyeFov * PI / 360.0;

        if (psi > kMaxHalfFov) {
            outColor = vec4(0.0, 0.0, 0.0, 0.0);
            return;
        }

        // Edge feathering on the outer margin (~3 degrees feather)
        const float kFeatherAngle = 3.0 * PI / 180.0;
        hemisphereAlpha = smoothstep(kMaxHalfFov, kMaxHalfFov - kFeatherAngle, psi);

        // Equidistant azimuthal projection: r = psi / kMaxHalfFov
        float rho = length(dir.xy);
        vec2 planeDir = (rho > 1e-6) ? (dir.xy / rho) : vec2(0.0, 0.0);
        float rNorm = psi / kMaxHalfFov; // 0.0 at optical center up to 1.0 at circle edge

        // Local UV coordinates in eye fisheye circle [0, 1] x [0, 1]
        uv = vec2(0.5 + 0.5 * rNorm * planeDir.x, 0.5 - 0.5 * rNorm * planeDir.y);
    }

    // Local eye UV coordinates before stereo crop for mask unpacking
    vec2 eyeLocalUv = clamp(uv, 0.0, 1.0);

    // Per-eye stereo crop
    if (vStereoLayout == 2) {
        // OU: crop in Y — left eye = top half
        uv.y = uv.y * 0.5 + float(eye) * 0.5;
    } else if (vStereoLayout == 1) {
        // SBS: crop in X — left eye = left half
        uv.x = uv.x * 0.5 + float(eye) * 0.5;
    }

    // polarMode != 0: skip SGSR1 near crop return above (LOD derivative diverges, black screen on Adreno).
    vec3 color = (vSharpness <= 0.01 || vTexelSize.x <= 0.0 || vTexelSize.y <= 0.0 || polarMode != 0)
        ? texture(videoTexture, uv).rgb
        : ApplySGSR1(uv, vSharpness, vTexelSize);
    if (vIsHdr != 0) {
        color = TonemapHdrToSdr(color, vIsHdr, vHdrLuminance);
    }

    if (vColorTemperature < 0.0) {
        // Night Mode: reduce blue light (~3000K)
        color = ApplyColorTemperature(color, 3000.0);
    } else if (abs(vColorTemperature - 6500.0) > 1.0 && vColorTemperature > 1000.0) {
        color = ApplyColorTemperature(color, vColorTemperature);
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
    outColor = vec4(SrgbEotf(color) * finalAlpha, finalAlpha);
}
