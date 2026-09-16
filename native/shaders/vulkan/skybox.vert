#version 450

layout(location = 0) in vec3 inPosition;

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    vec4 tintColor;
} pc;

layout(location = 0) out vec3 vDirection;
layout(location = 1) out vec4 vTintColor;

void main() {
    gl_Position = (pc.mvp * vec4(inPosition, 1.0)).xyww;
    vDirection = inPosition;
    vTintColor = pc.tintColor;
}
