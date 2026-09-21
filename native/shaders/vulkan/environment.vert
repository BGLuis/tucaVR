#version 450

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec4 inColor;

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    vec4 tintColor;
    vec4 glowParams; // x: screenGlowIntensity (0.0-1.0), y: environmentBrightness (0.0-1.0), z/w: reserved
    vec4 screenPos;  // xyz: screenWorldPosition
} pc;

layout(location = 0) out vec3 vWorldPosition;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out vec4 vColor;

void main() {
    gl_Position = pc.mvp * vec4(inPosition, 1.0);
    vWorldPosition = inPosition;
    vNormal = inNormal;
    vColor = inColor * pc.tintColor;
}
