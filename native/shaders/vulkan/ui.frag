#version 450

// Estagio 4 — fragment shader dos paineis de UI/controles.
// A textura e RGBA8888 normal (nao YCbCr), gerada pelo AImageReader do
// VirtualDisplay/Presentation do Kotlin via ANativeWindow_toSurface.
// Alpha blending e habilitado no pipeline para o fade de auto-hide.

layout(set = 0, binding = 0) uniform sampler2D uiTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in float vAlpha;
layout(location = 0) out vec4 outColor;

void main() {
    vec4 texColor = texture(uiTexture, vTexCoord);
    outColor = vec4(texColor.rgb, texColor.a * vAlpha);
}
