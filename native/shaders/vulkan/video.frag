#version 450
#extension GL_GOOGLE_include_directive : require

// Estagio 3 do plano de migracao Vulkan + Upscaling SGSR1 (Modo Qualidade/Auto):
// Fragment shader que amostra textura YCbCr via sampler imutavel com suporte
// a amostragem simples (1 fetch) ou reconstrucao espacial 9-tap SGSR1 adaptativa.

#include "video_common.glsl"

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) flat in float vSharpness;
layout(location = 2) flat in float vHdrLuminance;
layout(location = 3) flat in int vIsHdr;
layout(location = 4) flat in vec2 vTexelSize;
layout(location = 5) flat in int vChromaKeyEnabled;
layout(location = 6) flat in uint vChromaColorRgb;
layout(location = 7) flat in float vChromaSimilarity;
layout(location = 8) flat in float vChromaSmoothness;
layout(location = 9) flat in float vColorTemperature;

layout(location = 0) out vec4 outColor;

void main() {
    vec3 color = (vSharpness <= 0.01 || vTexelSize.x <= 0.0 || vTexelSize.y <= 0.0)
        ? texture(videoTexture, vTexCoord).rgb
        : ApplySGSR1(vTexCoord, vSharpness, vTexelSize);
    if (vIsHdr != 0) {
        color = TonemapHdrToSdr(color, vIsHdr, vHdrLuminance);
    }

    if (vColorTemperature < 0.0) {
        // Night Mode: reduce blue light (~3000K)
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
    outColor = vec4(SrgbEotf(color) * finalAlpha, finalAlpha);
}
