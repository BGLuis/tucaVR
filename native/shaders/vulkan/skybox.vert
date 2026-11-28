#version 450

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    vec4 tintColor;
} pc;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out vec4 vTintColor;

void main() {
    vec4 clip = pc.mvp * vec4(inPosition, 1.0);
    // Keep NDC depth strictly < 1.0 so Adreno MSAA 4x never clips or z-fights against depth clear 1.0.
    gl_Position = vec4(clip.xy, clip.w * 0.9999, clip.w);
    vTexCoord = inTexCoord;
    vTintColor = pc.tintColor;
}
