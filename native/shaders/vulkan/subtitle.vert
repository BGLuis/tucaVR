#version 450

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;
layout(location = 2) in vec4 inColor;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out vec4 vColor;

layout(push_constant) uniform SubtitlePushConstants {
    mat4 mvp;
    vec4 textColor;
    vec4 outlineColor;
    float textScale;
    float outlineWidth;
} pc;

void main() {
    vTexCoord = inTexCoord;
    vColor = inColor * pc.textColor;
    gl_Position = pc.mvp * vec4(inPosition, 1.0);
}
