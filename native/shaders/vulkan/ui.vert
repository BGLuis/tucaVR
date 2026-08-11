#version 450

// Estagio 4 — painel de UI/controles (RGBA8888 normal, sem YCbCr).
// Mesma estrutura do video.vert mas para texturas normais.

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    float alpha; // auto-hide fade
} pc;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out float vAlpha;

void main() {
    gl_Position = pc.mvp * vec4(inPosition, 1.0);
    vTexCoord = inTexCoord;
    vAlpha = pc.alpha;
}
