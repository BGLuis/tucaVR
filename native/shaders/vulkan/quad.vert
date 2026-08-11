#version 450

// Estagio 2 do plano de migracao Vulkan (docs/VULKAN-MIGRATION-PLAN.md):
// quad estatico com cor solida, sem textura — a textura de video entra no
// Estagio 3.

layout(location = 0) in vec3 inPosition;

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    vec4 color;
} pc;

layout(location = 0) out vec4 vColor;

void main() {
    gl_Position = pc.mvp * vec4(inPosition, 1.0);
    vColor = pc.color;
}
