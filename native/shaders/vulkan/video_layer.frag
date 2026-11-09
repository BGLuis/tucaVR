#version 450
#extension GL_GOOGLE_include_directive : require

// Same color path as video.frag for a flat, opaque frame (HDR tone mapping, color temperature, SGSR1 sharpening, sRGB
// linearization), written into a composition layer swapchain. No chroma key or packed alpha: those keep the eye buffer.

#include "video_common.glsl"

layout(push_constant) uniform PushConstants {
    vec2  uvOffset;
    vec2  uvScale;
    float sharpness;
    float texelWidth;
    float texelHeight;
    int   isHdr;
    float colorTemperature;
    float hdrLuminance;
} pc;

layout(location = 0) in vec2 vTexCoord;

layout(location = 0) out vec4 outColor;

void main() {
    const vec2 texelSize = vec2(pc.texelWidth, pc.texelHeight);
    vec3 color = (pc.sharpness <= 0.01 || texelSize.x <= 0.0 || texelSize.y <= 0.0)
        ? texture(videoTexture, vTexCoord).rgb
        : ApplySGSR1(vTexCoord, pc.sharpness, texelSize);
    if (pc.isHdr != 0) {
        color = TonemapHdrToSdr(color, pc.isHdr, pc.hdrLuminance);
    }

    if (pc.colorTemperature < 0.0) {
        color = ApplyColorTemperature(color, 3000.0);
    } else if (abs(pc.colorTemperature - 6500.0) > 1.0 && pc.colorTemperature > 1000.0) {
        color = ApplyColorTemperature(color, pc.colorTemperature);
    }

    outColor = vec4(SrgbEotf(color), 1.0);
}
