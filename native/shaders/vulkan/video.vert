#version 450

// Estagio 3 do plano de migracao Vulkan + Upscaling SGSR1 (Modo Qualidade/Auto):
// shader de vertice do quad de video — passa UV e parametros de upscaling para o fragment shader.

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;

layout(push_constant) uniform PushConstants {
    mat4  mvp;
    float sharpness;     // 0.0 = amostragem bilinear direta, > 0.0 = forca do kernel SGSR1
    int   upscalingMode; // 0 = Off, 1 = Quality, 2 = Performance, 3 = Auto
    int   isHdr;         // T-HDR: 1 = fonte PQ/HLG, aplicar tonemap no fragment shader
    float texelWidth;    // 1.0 / videoWidth
    float texelHeight;   // 1.0 / videoHeight
} pc;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) flat out float vSharpness;
layout(location = 2) flat out int vUpscalingMode;
layout(location = 3) flat out int vIsHdr;
layout(location = 4) flat out vec2 vTexelSize;

void main() {
    gl_Position = pc.mvp * vec4(inPosition, 1.0);
    vTexCoord = inTexCoord;
    vSharpness = pc.sharpness;
    vUpscalingMode = pc.upscalingMode;
    vIsHdr = pc.isHdr;
    vTexelSize = vec2(pc.texelWidth, pc.texelHeight);
}
