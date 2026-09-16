#version 450

#define PI 3.14159265358979323846

layout(set = 0, binding = 0) uniform sampler2D equirectMap;

layout(location = 0) in vec3 vDirection;
layout(location = 1) in vec4 vTintColor;

layout(location = 0) out vec4 outColor;

void main() {
    vec3 dir = normalize(vDirection);
    vec2 uv = vec2(atan(dir.z, dir.x) / (2.0 * PI) + 0.5, asin(clamp(dir.y, -1.0, 1.0)) / PI + 0.5);
    vec4 texColor = texture(equirectMap, uv);
    outColor = texColor * vTintColor;
}
