#version 450

// Estagio 5 — vertex shader para quad SBS/OU e esfera 360/180.
// Passa UV e o indice de olho (via push constant eyeIndex) para o
// fragment shader recortar a metade correta do frame estereo.

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    int  eyeIndex;     // 0 = esquerdo, 1 = direito
    int  swapEyes;     // 1 = inverte qual metade cada olho recebe
    int  stereoLayout; // 0 = mono, 1 = SBS, 2 = OU
    int  polar180;     // 0 = 360 completo, 1 = 180 hemisferio frontal
} pc;

layout(location = 0) out vec2 vTexCoord;
// Passa os uniforms necessarios ao fragment via flat (sem interpolacao)
layout(location = 1) flat out int vEye;
layout(location = 2) flat out int vSwapEyes;
layout(location = 3) flat out int vStereoLayout;
layout(location = 4) flat out int vPolar180;

void main() {
    gl_Position = pc.mvp * vec4(inPosition, 1.0);
    vTexCoord   = inTexCoord;
    vEye        = pc.eyeIndex;
    vSwapEyes   = pc.swapEyes;
    vStereoLayout = pc.stereoLayout;
    vPolar180   = pc.polar180;
}
