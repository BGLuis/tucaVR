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
    gl_Position = (pc.mvp * vec4(inPosition, 1.0)).xyww;
    vTexCoord = inTexCoord;
    vTintColor = pc.tintColor;
}
