#version 450

// Estagio 3 do plano de migracao Vulkan (docs/VULKAN-MIGRATION-PLAN.md):
// shader de vertice do quad de video — passa UV para o fragment shader
// amostrar a textura YCbCr importada via AHardwareBuffer.

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;

layout(push_constant) uniform PushConstants {
    mat4 mvp;
} pc;

layout(location = 0) out vec2 vTexCoord;

void main() {
    gl_Position = pc.mvp * vec4(inPosition, 1.0);
    vTexCoord = inTexCoord;
}
