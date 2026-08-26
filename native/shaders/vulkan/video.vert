#version 450

// Estagio 3 do plano de migracao Vulkan + Upscaling SGSR1 (Modo Qualidade/Auto):
// shader de vertice do quad de video — passa UV e parametros de upscaling para o fragment shader.

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;

layout(push_constant) uniform PushConstants {
    mat4  mvp;
    float sharpness;     // 0.0 = amostragem bilinear direta, > 0.0 = forca do kernel SGSR1
    int   upscalingMode; // 0 = Off, 1 = Quality, 2 = Performance, 3 = Auto
} pc;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) flat out float vSharpness;
layout(location = 2) flat out int vUpscalingMode;

void main() {
    gl_Position = pc.mvp * vec4(inPosition, 1.0);
    vTexCoord = inTexCoord;
    vSharpness = pc.sharpness;
    vUpscalingMode = pc.upscalingMode;
}
