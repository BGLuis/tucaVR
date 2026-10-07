#version 450

layout(set = 0, binding = 0) uniform sampler2D screenAmbientTexture;

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    vec4 tintColor;
    vec4 glowParams; // x: screenGlowIntensity (0.0-1.0), y: environmentBrightness (0.0-1.0), z/w: reserved
    vec4 screenPos;  // xyz: screenWorldPosition
} pc;

layout(location = 0) in vec3 vWorldPosition;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec4 vColor;

layout(location = 0) out vec4 outColor;

void main() {
    vec3 lightDir = normalize(vec3(0.2, 1.0, 0.4));
    float diff = max(dot(normalize(vNormal), lightDir), 0.0);
    float ambient = 0.50;
    // Modulação pelo brilho configurado do ambiente (pc.glowParams.y)
    vec3 baseLighting = vec3(ambient + diff * 0.50) * pc.glowParams.y;

    // Screen Glow: luz difusa emitida pela tela virtual sobre a malha da sala
    vec3 glow = vec3(0.0);
    if (pc.glowParams.x > 0.001) {
        vec3 toScreen = pc.screenPos.xyz - vWorldPosition;
        float dist = length(toScreen);
        float attenuation = 1.0 / (1.0 + dist * dist * 0.25);
        float nDotL = max(dot(normalize(vNormal), normalize(toScreen)), 0.0);
        vec3 screenColor = texture(screenAmbientTexture, vec2(0.5, 0.5)).rgb;
        glow = screenColor * (pc.glowParams.x * attenuation * nDotL);
    }

    vec3 finalLighting = baseLighting + glow;
    outColor = vec4(vColor.rgb * finalLighting, vColor.a);
}
