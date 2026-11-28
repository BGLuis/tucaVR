#version 450

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec4 inColor;

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    vec4 tintColor;
    vec4 glowParams; // x: screenGlow, y: brightnessLinear, z: timeSeconds, w: reserved
    vec4 screenPos;
} pc;

layout(location = 0) out vec3 vWorldPosition;
layout(location = 1) out vec4 vColor;
layout(location = 2) out vec2 vQuadCoord;

void main() {
    // Subtle per-particle micro-drift (a few cm over ~25-40s) for depth life with zero motion sickness.
    // Particle center hash uses UV/color so all 4 quad corners drift identically.
    float t = pc.glowParams.z;
    float phase = dot(inColor.rgb, vec3(19.3, 47.7, 83.1));
    vec3 drift = vec3(
        sin(t * 0.17 + phase) * 0.035,
        cos(t * 0.13 + phase * 1.37) * 0.028,
        sin(t * 0.11 + phase * 2.11) * 0.035
    );
    vec3 pos = inPosition + drift;
    gl_Position = pc.mvp * vec4(pos, 1.0);
    vWorldPosition = pos;
    vColor = inColor * pc.tintColor;
    vQuadCoord = inNormal.xy;
}
