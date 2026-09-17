#version 450

// Estagio 5 — vertex shader para quad SBS/OU e esfera 360/180 + Upscaling SGSR1.
// Passa UV, indices de olho e parametros de nitidez/upscaling para o fragment shader.

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;

layout(push_constant) uniform PushConstants {
    mat4  mvp;
    int   eyeIndex;      // 0 = esquerdo, 1 = direito
    int   swapEyes;      // 1 = inverte qual metade cada olho recebe
    int   stereoLayout;  // 0 = mono, 1 = SBS, 2 = OU
    int   polar180;      // 0 = 360 completo, 1 = 180 hemisferio frontal
    float sharpness;     // 0.0 = amostragem direta, > 0.0 = forca do SGSR1
    int   upscalingMode; // 0 = Off, 1 = Quality, 2 = Performance, 3 = Auto
    // cubemapLayout/projectionType nao sao usados por este shader (so pelo
    // par stereo_cubemap), mas precisam ficar declarados aqui pra manter o
    // mesmo layout de offsets do push constant que StereoPushConstants no
    // C++ (vkCmdPushConstants manda o struct inteiro pra qualquer um dos
    // pipelines estereo) — sem isso, isHdr logo abaixo leria o byte errado.
    int   cubemapLayout;
    int   projectionType;
    int   isHdr;         // T-HDR: 1 = fonte PQ/HLG, aplicar tonemap no fragment shader
    float texelWidth;    // 1.0 / videoWidth
    float texelHeight;   // 1.0 / videoHeight
} pc;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) flat out int vEye;
layout(location = 2) flat out int vSwapEyes;
layout(location = 3) flat out int vStereoLayout;
layout(location = 4) flat out int vPolar180;
layout(location = 5) flat out float vSharpness;
layout(location = 6) flat out int vUpscalingMode;
layout(location = 7) flat out int vIsHdr;
layout(location = 8) flat out vec2 vTexelSize;

void main() {
    gl_Position = pc.mvp * vec4(inPosition, 1.0);
    vTexCoord   = inTexCoord;
    vEye        = pc.eyeIndex;
    vSwapEyes   = pc.swapEyes;
    vStereoLayout = pc.stereoLayout;
    vPolar180   = pc.polar180;
    vSharpness  = pc.sharpness;
    vUpscalingMode = pc.upscalingMode;
    vIsHdr = pc.isHdr;
    vTexelSize  = vec2(pc.texelWidth, pc.texelHeight);
}
