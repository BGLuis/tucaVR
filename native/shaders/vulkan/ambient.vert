#version 450

// docs/reports/MODO-AMBIENTE.md F3: quad do halo — mesma geometria do quad
// de video (posicao -0.5..0.5, UV 0..1, ver CreateVideoVertexBuffer), MVP
// escalado por kAmbientHaloScale sobre screenScaleX/Y no lado C++
// (DrawAmbientHalo). Reusa videoVertexBuffer, zero geometria nova.

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;

layout(push_constant) uniform PushConstants {
    mat4  mvp;
    float insetScale; // 1 / kAmbientHaloScale — tamanho do retangulo do video em espaco UV do halo
    float edgeWidth;  // largura da rampa smoothstep (kAmbientEdgeWidth)
    float intensity;  // alpha maximo, ja com gates + suavizacao aplicados no C++
    float aspect;     // screenScaleX / screenScaleY — corrige o falloff pra nao ficar esticado
} pc;

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) flat out float vInsetScale;
layout(location = 2) flat out float vEdgeWidth;
layout(location = 3) flat out float vIntensity;
layout(location = 4) flat out float vAspect;

void main() {
    gl_Position = pc.mvp * vec4(inPosition, 1.0);
    vTexCoord = inTexCoord;
    vInsetScale = pc.insetScale;
    vEdgeWidth = pc.edgeWidth;
    vIntensity = pc.intensity;
    vAspect = pc.aspect;
}
