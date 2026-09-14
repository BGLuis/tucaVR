#version 450

// Vertex shader para Cubemap / EAC projetado na esfera 360 e suporte a SGSR1.
// Passa direção tridimensional no espaço do objeto (vWorldDirection),
// coordenadas UV, parâmetros estéreo e índices de layout para o fragment shader.

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;

layout(push_constant) uniform PushConstants {
    mat4  mvp;
    int   eyeIndex;      // 0 = esquerdo, 1 = direito
    int   swapEyes;      // 1 = inverte olhos
    int   stereoLayout;  // 0 = mono, 1 = SBS, 2 = OU
    int   polar180;      // não usado em cubemap (sempre 360)
    float sharpness;     // 0.0 = amostragem direta, > 0.0 = SGSR1
    int   upscalingMode; // 0 = Off, 1 = Quality, 2 = Performance, 3 = Auto
    int   cubemapLayout; // 0 = 3x2, 1 = 6x1, 2 = EAC 3x2, 3 = cross
    int   projectionType;// 0 = Linear Cubemap, 1 = EAC
    int   isHdr;         // T-HDR: 1 = fonte PQ/HLG, aplicar tonemap no fragment shader
} pc;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) flat out int vEye;
layout(location = 2) flat out int vSwapEyes;
layout(location = 3) flat out int vStereoLayout;
layout(location = 4) flat out float vSharpness;
layout(location = 5) flat out int vUpscalingMode;
layout(location = 6) flat out int vCubemapLayout;
layout(location = 7) flat out int vProjectionType;
layout(location = 8) out vec3 vWorldDirection;
layout(location = 9) flat out int vIsHdr;

void main() {
    gl_Position       = pc.mvp * vec4(inPosition, 1.0);
    vTexCoord         = inTexCoord;
    vEye              = pc.eyeIndex;
    vSwapEyes         = pc.swapEyes;
    vStereoLayout     = pc.stereoLayout;
    vSharpness        = pc.sharpness;
    vUpscalingMode    = pc.upscalingMode;
    vCubemapLayout    = pc.cubemapLayout;
    vProjectionType   = pc.projectionType;
    vWorldDirection   = inPosition;
    vIsHdr            = pc.isHdr;
}
