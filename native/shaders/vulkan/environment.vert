#version 450

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec4 inColor;
layout(location = 3) in vec2 inUv;

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    vec4 baseColorFactor; // linear RGBA
    vec4 emissiveFactor;  // linear RGB (pre-multiplied by emissiveStrength), w: metallicFactor
    vec4 materialParams;  // x: roughnessFactor, y: occlusionStrength, z: unlitFlag, w: hasEmissiveTex
} pc;

layout(location = 0) out vec3 vLocalPosition;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out vec4 vColor;
layout(location = 3) out vec2 vUv;

void main() {
    gl_Position = pc.mvp * vec4(inPosition, 1.0);
    vLocalPosition = inPosition;
    vNormal = inNormal;
    vColor = inColor;
    vUv = inUv;
}
